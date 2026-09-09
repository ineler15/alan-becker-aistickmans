package com.stickmanai.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.lifecycle.LifecycleService
import com.stickmanai.android.CrashReporter
import com.stickmanai.android.MainActivity
import com.stickmanai.android.Prefs
import com.stickmanai.android.allCharacters
import com.stickmanai.android.R
import com.stickmanai.android.ai.CameraCapture
import com.stickmanai.android.ai.CharacterLore
import com.stickmanai.android.ai.GeminiClient
import com.stickmanai.android.ai.PcBridge
import com.stickmanai.android.ai.PcPeersResult
import com.stickmanai.android.ai.PeerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

// User-controlled 0-100 slider (MainActivity's SeekBar next to the partner Spinner) instead of a
// single fixed "es tu pareja" phrase - lets the user dial a relationship anywhere from
// barely-registers to head-over-heels rather than only on/off. Deliberately coarse tiers, not a
// continuous interpolation - the AI reads prose, not numbers. Mirrors PC's agentLoop.js.
private fun affectionPhrase(displayName: String, level: Int): String = when {
    level >= 80 ->
        "Estas profundamente enamorado/a de $displayName - es lo que mas te importa en el mundo, " +
            "buscala/buscalo todo el tiempo y mostralo sin filtro con lo que decis y con tu cara (eyes: heart). "
    level >= 60 ->
        "Sentis carino especial por $displayName - te importa de verdad, buscala/buscalo seguido y " +
            "mostraselo de a poco con lo que decis. "
    level >= 40 -> "Le tenes bastante carino a $displayName - te gusta pasar tiempo con esa persona. "
    level >= 20 -> "$displayName te cae bien, nada mas. "
    else -> "No sentis nada en particular por $displayName mas alla de conocerse. "
}

/**
 * Foreground service owning every enabled character's overlay window. Runs two loops:
 * a fast one (~40ms) that steps physics/animation, and a slow one (~6s, matching the desktop
 * app's TICK_INTERVAL_SECONDS) that asks Gemini what each character should do next.
 */
class OverlayService : LifecycleService() {

    companion object {
        const val CHANNEL_ID = "overlay_service"
        const val TICK_INTERVAL_MS = 6000L
        // Silent-turn forcing, same reasoning as desktop agentLoop.js: prompt wording alone
        // doesn't reliably keep the model talking, so force a "say" after too many quiet turns.
        const val SILENT_TURN_LIMIT = 3
        // Position/ghost sync with the PC runs faster than the AI decision loop - it's just
        // relaying reported state, not asking Gemini anything, so there's no cost to doing it often.
        const val PC_SYNC_INTERVAL_MS = 2000L
        // Survival drain cadence - mirrors jsCharacterEngine.js: every poll hunger/thirst tick
        // down, and once EITHER hits 0 the character loses hp too, eventually dying (state.kill()).
        const val SURVIVAL_POLL_MS = 10_000L
        const val HUNGER_PER_POLL = 0.5f
        const val THIRST_PER_POLL = 0.75f
        const val HP_LOSS_PER_POLL_STARVED = 1.5f
        // Cooldown between on-character "API key agotada" warnings while the provider keeps
        // failing per tick - same reasoning as desktop agentLoop.js's API_KEY_WARN_COOLDOWN_MS.
        const val API_KEY_WARN_COOLDOWN_MS = 120_000L
        // Action-range checks mirror executor.js on PC.
        const val FIGHT_DISTANCE_PX = 100
        const val KITCHEN_DISTANCE_PX = 150
        const val EAT_GAIN = 45
    }

    private lateinit var windowManager: android.view.WindowManager
    private lateinit var chatButton: ChatButtonOverlay
    private val overlays = LinkedHashMap<String, CharacterOverlay>()
    private val pcGhosts = HashMap<String, GhostOverlay>()
    private var pcPeersCache: PcPeersResult = PcPeersResult(0, emptyList())
    private val turnsSinceSay = HashMap<String, Int>()
    private val apiKeyWarnedAtById = HashMap<String, Long>()
    // Lives only while the survival system is on and the kitchen rigs are present - see setupOverlays().
    private var kitchenOverlay: KitchenOverlay? = null
    // Cycles through FoodPropOverlay.FOOD_CANDIDATES so consecutive meals visibly switch foods.
    private var foodCycle = 0
    // Anti-repetition: mirrors PC's agentLoop.js lastToolById/repeatStreakById - if the AI picks
    // the same tool 3 times in a row (e.g. stuck spamming set_animation or say), force a wait
    // instead so it doesn't look repetitive. walk_to is exempt since actually moving repeatedly
    // is fine.
    private val lastToolById = HashMap<String, String>()
    private val repeatStreakById = HashMap<String, Int>()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var physicsRunning = false
    // LifecycleService (this) doubles as the LifecycleOwner CameraX binds to - one shared
    // capture per AI tick round, same idea as the desktop's single shared screenshot per tick.
    private val cameraCapture by lazy { CameraCapture(this, this) }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        startForeground(1, buildNotification())
        setupOverlays()
        startPhysicsLoop()
        startAiLoop()
        startPcSyncLoop()
        startSurvivalLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        physicsRunning = false
        serviceScope.cancel()
        overlays.values.forEach { it.detach() }
        overlays.clear()
        pcGhosts.values.forEach { it.detach() }
        pcGhosts.clear()
        kitchenOverlay?.detach()
        kitchenOverlay = null
        if (::chatButton.isInitialized) chatButton.detach()
        super.onDestroy()
    }

    private fun setupOverlays() {
        val metrics = resources.displayMetrics
        for (character in Prefs.enabledCharacters(this)) {
            if (overlays.containsKey(character.id)) continue
            // Tapping a character used to open ChatActivity, which switched away from whatever
            // app was in front - that's now the ChatButtonOverlay's job instead (see below), so
            // there's nothing left for a tap to do here.
            try {
                val overlay = CharacterOverlay(
                    this, character, windowManager, metrics.widthPixels, metrics.heightPixels
                ) { }
                overlay.attach()
                overlays[character.id] = overlay
            } catch (e: Throwable) {
                CrashReporter.report(this, "crear personaje ${character.id}", e)
            }
        }
        // Kitchen prop exists only while the survival system is on (eat/drink need somewhere to
        // happen); cycles its background stations as a visible "transition" whenever someone eats.
        if (Prefs.survivalEnabled(this) && kitchenOverlay == null) {
            try {
                kitchenOverlay = KitchenOverlay(this, windowManager, metrics.widthPixels).also { it.attach() }
            } catch (e: Throwable) {
                CrashReporter.report(this, "crear cocina", e)
                kitchenOverlay = null
            }
        }
        if (!::chatButton.isInitialized) {
            chatButton = ChatButtonOverlay(this, windowManager)
            chatButton.attach()
        }
    }

    private fun startPhysicsLoop() {
        physicsRunning = true
        val tickRunnable = object : Runnable {
            override fun run() {
                if (!physicsRunning) return
                try {
                    overlays.values.forEach { it.tick() }
                } catch (e: Throwable) {
                    CrashReporter.report(this@OverlayService, "physics tick", e)
                }
                mainHandler.postDelayed(this, CharacterState.TICK_MS)
            }
        }
        mainHandler.post(tickRunnable)
    }

    private fun startAiLoop() {
        serviceScope.launch {
            while (true) {
                // One shared camera frame (and, if accessibility is on, one shared screenshot)
                // per round, reused by every character this tick - mirrors the desktop's "one
                // shared screenshot per round" in agentLoop.js so the cost doesn't multiply with
                // the number of friends.
                val cameraBase64 = cameraCapture.captureBase64()
                val screenBase64 = com.stickmanai.android.input.TapAccessibilityService.captureScreenshotBase64()
                for (overlay in overlays.values.toList()) {
                    try {
                        tickCharacterAi(overlay, cameraBase64, screenBase64)
                    } catch (e: Throwable) {
                        CrashReporter.report(this@OverlayService, "tick AI ${overlay.def.id}", e)
                    }
                }
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun startPcSyncLoop() {
        serviceScope.launch {
            while (true) {
                val pcAddress = Prefs.pcAddress(this@OverlayService)
                if (pcAddress.isNotBlank()) {
                    val metrics = resources.displayMetrics
                    val localPeers = overlays.values.map {
                        PeerInfo(it.def.id, it.def.displayName, it.xPercent(metrics.widthPixels), it.lastSayText)
                    }
                    PcBridge.pushLocalPeers(pcAddress, metrics.widthPixels, localPeers)
                    pcPeersCache = PcBridge.fetchRemotePeers(pcAddress)
                    mainHandler.post { syncGhosts() }
                }
                delay(PC_SYNC_INTERVAL_MS)
            }
        }
    }

    /**
     * Survival drain: slow real-time tick-down of hunger/thirst (then hp once either hits 0) for
     * alive characters while the system is on - mirrors jsCharacterEngine.js, including using a
     * wall-clock poll instead of per-physics-tick so the drain isn't affected by anything
     * throttling (or pausing) the AI loop.
     */
    private fun startSurvivalLoop() {
        serviceScope.launch {
            while (true) {
                delay(SURVIVAL_POLL_MS)
                try {
                    if (!Prefs.survivalEnabled(this@OverlayService)) continue
                    for ((characterId, overlay) in overlays) {
                        if (overlay.state.dead) continue
                        val s = Prefs.survival(this@OverlayService, characterId) ?: continue
                        val hunger = (s.hunger - HUNGER_PER_POLL).coerceAtLeast(0f)
                        val thirst = (s.thirst - THIRST_PER_POLL).coerceAtLeast(0f)
                        val hp = (s.hp - if (hunger <= 0f || thirst <= 0f) HP_LOSS_PER_POLL_STARVED else 0f).coerceAtLeast(0f)
                        Prefs.setSurvivalStat(this@OverlayService, characterId, hp = hp, hunger = hunger, thirst = thirst, dead = hp <= 0f)
                        if (hp <= 0f) {
                            mainHandler.post { overlay.state.kill() }
                        }
                    }
                } catch (e: Throwable) {
                    CrashReporter.report(this@OverlayService, "survival drain", e)
                }
            }
        }
    }

    private fun syncGhosts() {
        val metrics = resources.displayMetrics
        val floorY = metrics.heightPixels - (48 * metrics.density).toInt()
        val seenIds = HashSet<String>()
        for (peer in pcPeersCache.peers) {
            // Don't shadow a character that's also active locally on this device.
            if (overlays.containsKey(peer.id)) continue
            seenIds.add(peer.id)
            val xPercent = if (pcPeersCache.screenWidth > 0) (peer.x * 100 / pcPeersCache.screenWidth) else 50
            val ghost = pcGhosts.getOrPut(peer.id) {
                GhostOverlay(this, windowManager, peer.id).also { it.attach() }
            }
            ghost.update(xPercent.coerceIn(0, 100), metrics.widthPixels, floorY, peer.lastSay)
        }
        // Remove ghosts for characters the PC stopped reporting (it went idle/closed).
        val stale = pcGhosts.keys.filter { it !in seenIds }
        for (id in stale) {
            pcGhosts.remove(id)?.detach()
        }
    }

    private suspend fun tickCharacterAi(overlay: CharacterOverlay, cameraBase64: String?, screenBase64: String?) {
        val characterId = overlay.def.id
        val apiKey = Prefs.apiKeyFor(this, characterId)
        if (apiKey.isBlank()) return
        val metrics = resources.displayMetrics
        // Dead (survival system, hp 0): the character lies still and the AI loop skips its turns
        // so it can't decide anything or wander. The only way back is a chat message from the
        // user - it revives AND lets the character answer the message this same round (falls
        // through to the decide below). Mirrors PC's agentLoop.js dead-gate.
        if (Prefs.survivalEnabled(this)) {
            val st = Prefs.survival(this, characterId)!!
            if (st.dead || overlay.state.dead) {
                val reviveMsg = PendingMessages.consume(characterId)
                if (reviveMsg != null) {
                    Prefs.reviveCharacter(this, characterId)
                    overlay.state.revive()
                    overlay.state.wakeUp()
                    overlay.addHistory("revivido: el usuario te revivio con un mensaje")
                } else {
                    return
                }
            }
        }
        // Skip the AI call entirely while asleep - saves quota, and a sleeping character
        // shouldn't be deciding to do anything anyway. It wakes up on its own after
        // CharacterState.SLEEP_DURATION_MS or if the user drags/pinches it; any message that
        // arrives meanwhile is left in PendingMessages for the next successful (awake) turn.
        if (overlay.state.sleeping) return
        val userMessage = PendingMessages.consume(characterId)
        val silentStreak = turnsSinceSay[characterId] ?: 0

        val peers = overlays.values.filter { it.def.id != characterId }.map {
            PeerInfo(it.def.id, it.def.displayName, it.xPercent(metrics.widthPixels), it.lastSayText)
        } + pcPeersCache.peers.map {
            PeerInfo(it.id, it.displayName, null, it.lastSay, device = "pc")
        }

        // Fixed fact from character creation (see Prefs.CustomCharacterMeta), not something the
        // AI defines itself via define_personality - prepended so it's part of whatever
        // GeminiClient.decide sends as personality context.
        val gender = Prefs.customMeta(this, characterId)?.gender
        val genderLine = when (gender) {
            "femenino" -> "Tu genero es femenino. "
            "masculino" -> "Tu genero es masculino. "
            else -> ""
        }

        // Explicit "pareja" (see Prefs.partnerFor) - a designated fact, not the emergent crush
        // behavior GeminiClient's own system prompt already encourages. Mirrors PC's agentLoop.js.
        val partnerId = Prefs.partnerFor(this, characterId)
        val partner = partnerId?.let { pid -> allCharacters(this).find { it.id == pid } }
        // Restate the partner's live position directly here instead of relying on the model to
        // find the right entry in "peers" by name on its own - repeating it right next to "this
        // one is your partner" is what actually makes characters go find each other. Mirrors
        // PC's agentLoop.js.
        val partnerPeer = partner?.let { p -> peers.find { it.id == p.id } }
        val partnerLocationLine = when {
            partnerPeer?.device == "pc" -> "Ahora mismo esta en la PC. "
            partnerPeer?.x != null -> "Ahora mismo esta al ${partnerPeer.x}% de la pantalla. "
            else -> ""
        }
        val partnerLine = if (partner != null) {
            affectionPhrase(partner.displayName, Prefs.affectionFor(this, characterId)) + partnerLocationLine
        } else ""

        // Extra context SEPARATE from the automatic one - user-written + character self-written
        val userCtx = Prefs.userContext(this, characterId)
        val selfCtx = Prefs.selfContext(this, characterId)
        val survivalNote = if (Prefs.survivalEnabled(this)) survivalContextNote(characterId, metrics) else null
        val extraContext = listOf(
            if (userCtx.isNotBlank()) "Contexto que te escribio el usuario (se configura en la app, va fijo): $userCtx" else null,
            if (selfCtx.isNotBlank()) "Contexto extra que vos mismo te escribiste con set_context: $selfCtx" else null,
            survivalNote,
        ).filterNotNull().joinToString("\n\n")

        try {
            val decision = GeminiClient.decide(
                apiKey = apiKey,
                provider = Prefs.providerFor(this, characterId),
                personality =
                    // Canon lore (CharacterLore.kt) first - a fixed "who you are" layer below any
                    // persona the character defines for itself, so fresh characters still know their
                    // Alan Becker origin without needing define_personality. Same prepend trick as
                    // genderLine: GeminiClient embeds personality verbatim in the prompt.
                    (CharacterLore.loreFor(characterId).takeIf { it.isNotBlank() }?.let { "$it\n\n" } ?: "") +
                    genderLine + partnerLine + Prefs.personality(this, characterId),
                recentHistory = overlay.recentHistory.toList(),
                memory = Prefs.memory(this, characterId),
                extraContext = extraContext,
                xPercent = overlay.xPercent(metrics.widthPixels),
                peers = peers,
                userMessage = userMessage,
                forceSay = silentStreak >= SILENT_TURN_LIMIT,
                cameraBase64 = cameraBase64,
                screenBase64 = screenBase64,
                attentionFocus = Prefs.attentionFocus(this@OverlayService),
                touchXPercent = if (TouchTracker.isKnown()) TouchTracker.xPercent else null,
                touchYPercent = if (TouchTracker.isKnown()) TouchTracker.yPercent else null,
            )
            val (tool, args) = dedupeRepeatedAction(characterId, decision.tool, decision.args)
            // Captured from the ORIGINAL decision (before the repeat-guard above can swap
            // tool/args out from under the model) - a pending face reaction shouldn't get
            // silently dropped just because the body action it rode in on got overridden. See
            // ActionsSchema.kt's EYES_PARAM/MOUTH_PARAM (every action accepts these two).
            val requestedEyes = decision.args.optString("eyes").takeIf { it.isNotBlank() }
            val requestedMouth = decision.args.optString("mouth").takeIf { it.isNotBlank() }
            turnsSinceSay[characterId] = if (tool == "say") 0 else silentStreak + 1
            mainHandler.post {
                applyDecision(overlay, tool, args)
                if (tool != "set_emotion" && (requestedEyes != null || requestedMouth != null)) {
                    overlay.state.setFace(requestedEyes, requestedMouth)
                }
            }
            overlay.addHistory("${tool}(${args})")
        } catch (e: Exception) {
            android.util.Log.e("StickmanAI", "decide() failed for $characterId", e)
            // Put the chat message back so it isn't silently lost on a transient network error -
            // it already got consumed above before we knew the call would fail.
            if (userMessage != null) PendingMessages.set(characterId, userMessage)
            // API-key/quota exhaustion: show the warning on the character once (cooldown) instead
            // of everything silently failing forever. The bubble expires on its own (sayUntil).
            val msg = e.message ?: ""
            if (msg.contains("quota", true) || msg.contains("429") || msg.contains("rate_limit", true) ||
                msg.contains("resource_exhausted", true) || msg.contains("401", true) ||
                msg.contains("invalid api key", true) || msg.contains("402", true) || msg.contains("billing", true)
            ) {
                val nowMs = System.currentTimeMillis()
                if (nowMs - (apiKeyWarnedAtById[characterId] ?: 0L) > API_KEY_WARN_COOLDOWN_MS) {
                    apiKeyWarnedAtById[characterId] = nowMs
                    mainHandler.post {
                        overlay.state.say("¡Uy! Parece que la API key se agotó o no es válida. Avisale a mi humano.")
                    }
                }
            }
            // Used to fall back to a random walk target here, but a burst of failed decide()
            // calls (e.g. the screenshot crash-loop) made that look like the character going
            // haywire, constantly re-randomizing its target every tick. Only the AI's own
            // decisions should move the character now - on error it just stays put.
            overlay.addHistory("error: ${e.message}")
        }
    }

    // Survival system context: the character's own life/hunger/thirst, where the kitchen is, and
    // the rules for eating/drinking/fighting - mirrors agentLoop.js's survivalNote on PC. Fed via
    // extraContext so it reaches every provider without GeminiClient needing to know about it.
    private fun survivalContextNote(characterId: String, metrics: android.util.DisplayMetrics): String {
        val s = Prefs.survival(this, characterId) ?: return ""
        val kp = kitchenOverlay?.getPosition()
        val kitchenPart = if (kp != null) {
            val pctX = (kp.first * 100 / metrics.widthPixels.toFloat()).toInt()
            "que esta en la cocina al ${pctX}% del ancho de la pantalla"
        } else {
            "pero todavia no hay cocina en este lugar"
        }
        return "SISTEMA DE VIDA/HAMBRE/SED: tu vida es ${Math.round(s.hp)}/100, tu hambre ${Math.round(s.hunger)}/100 " +
            "y tu sed ${Math.round(s.thirst)}/100. Si el hambre o la sed llegan a 0 perdes vida poco a poco " +
            "hasta morir. Para comer (eat) o beber (drink) tenes que ESTAR EN $kitchenPart - si estas lejos, " +
            "camina hasta ahi con walk_to y recien ahi usa eat/drink. Con fight podes pegarle a otro " +
            "stickman que este cerca tuyo (en tu contexto ves la posicion de tus peers) y bajarle su " +
            "vida hasta que muera (luego el usuario lo revive). Si vos estas muerto, no podes hacer " +
            "nada hasta que el usuario te reviva."
    }

    /**
     * If the AI has picked the same tool 3 times in a row for this character, swaps it for wait
     * instead - same fix as the desktop's agentLoop.js repeat guard, so a character doesn't get
     * stuck spamming e.g. set_animation("sit") or say() forever. Doesn't need to force it into
     * moving somewhere visible - CharacterState's own autonomous wander (IDLE_WALK_TIMEOUT_MS)
     * already takes over on its own if it stays idle long enough either way.
     */
    private fun dedupeRepeatedAction(characterId: String, tool: String, args: JSONObject): Pair<String, JSONObject> {
        val streak = if (tool == lastToolById[characterId]) (repeatStreakById[characterId] ?: 0) + 1 else 0
        lastToolById[characterId] = tool
        repeatStreakById[characterId] = streak
        if (streak >= 3 && tool != "walk_to") {
            repeatStreakById[characterId] = 0
            lastToolById[characterId] = "wait"
            return "wait" to JSONObject()
        }
        return tool to args
    }

    // If a character's spoken text announces a physical action ("¡Salto!", "me muevo hacia
    // Blue"), actually perform it right after the say - mirrors executor.js's reactToSayIntent on
    // PC. Kept as a small fallback; explicit walk_to/set_animation still get priority.
    private fun reactToSayIntent(overlay: CharacterOverlay, text: String) {
        if (text.isBlank()) return
        val lower = text.lowercase()
        val jumpRe = Regex("\\b(salt(?:a|o|e|emos|aste|ando|ar|aremos|aré|às|aria|ábamos|òs|es|ó)|brinc(?:a|o|e|ando|emos|ar)|dar un salto)\\b", RegexOption.IGNORE_CASE)
        val strongMoveRe = Regex("\\b(muev(?:e|o)\\b|camina(?:ndo)?\\b|caminar\\b|camino\\b|corr(?:e|o)\\b|acercar(?:me)?\\b|llegar (?:a |hasta )|pasear|paseando|salir a caminar)", RegexOption.IGNORE_CASE)
        val weakMoveRe = Regex("\\b(voy|vamos|vaya|ira|iré|me voy|se va|ve) (?:a |hacia |para |hasta )", RegexOption.IGNORE_CASE)
        val runRe = Regex("\\b(corr(?:e|o|iendo)|apuro|rapido|de prisa)\\b", RegexOption.IGNORE_CASE)
        if (jumpRe.containsMatchIn(lower)) overlay.state.setEmotion("jump")
        // Resolve "hacia <peer>" against local (same-device) characters; kitchen as a landmark.
        var destPx: Int? = null
        val destLabel: String?
        val named = overlays.values.firstOrNull { o ->
            o.def.id != overlay.def.id &&
                (lower.contains(o.def.displayName.lowercase()) || lower.contains(o.def.id.lowercase()))
        }
        val kitchenPos = kitchenOverlay?.getPosition()
        destLabel = when {
            named != null -> {
                destPx = named.state.x
                named.def.displayName
            }
            kitchenPos != null && lower.contains("cocin") -> {
                destPx = kitchenPos.first
                "la cocina"
            }
            else -> null
        }
        if (strongMoveRe.containsMatchIn(lower) || (destPx != null && weakMoveRe.containsMatchIn(lower))) {
            val run = runRe.containsMatchIn(lower)
            if (destPx != null) overlay.state.startMoving(destPx, run)
            else overlay.state.randomTarget(run)
        }
    }

    private fun applyDecision(overlay: CharacterOverlay, tool: String, args: JSONObject) {
        val metrics = resources.displayMetrics
        when (tool) {
            "walk_to" -> {
                val xPct = args.optDouble("x", 50.0).coerceIn(0.0, 100.0)
                overlay.state.startMoving((xPct / 100 * metrics.widthPixels).toInt(), args.optBoolean("run", false))
            }
            "set_animation" -> {
                val state = args.optString("state", "idle")
                val validStates = setOf("happy", "jump", "trip", "sad", "scared", "sit", "tired", "sleep")
                overlay.state.setEmotion(if (state in validStates) state else null)
            }
            "set_emotion" -> overlay.state.setFace(
                args.optString("eyes").takeIf { it.isNotBlank() },
                args.optString("mouth").takeIf { it.isNotBlank() },
            )
            "say" -> {
                val text = args.optString("text", "")
                overlay.say(text)
                if (text.isNotBlank()) {
                    com.stickmanai.android.chat.ChatNotifications.showSay(this, overlay.def.id, overlay.def.displayName, text)
                }
                reactToSayIntent(overlay, text)
            }
            "set_custom_animation" -> {
                val keyframesJson = args.optJSONArray("keyframes")
                if (keyframesJson != null) {
                    val bodyParts = listOf("torso", "leg1", "leg1Shin", "leg2", "leg2Shin", "arm1", "arm2")
                    val keyframes = (0 until keyframesJson.length()).map { i ->
                        val kf = keyframesJson.getJSONObject(i)
                        val angles = bodyParts.filter { kf.has(it) }.associateWith { kf.optDouble(it).toFloat() }
                        CharacterState.Keyframe(
                            angles,
                            kf.optLong("holdMs", CharacterState.DEFAULT_KEYFRAME_HOLD_MS),
                            eyes = if (kf.has("eyes")) kf.optString("eyes") else null,
                            mouth = if (kf.has("mouth")) kf.optString("mouth") else null,
                        )
                    }
                    overlay.state.startCustomAnimation(keyframes)
                }
            }
            "define_personality" -> Prefs.setPersonality(this, overlay.def.id, args.optString("description", ""))
            "set_context" -> Prefs.setSelfContext(this, overlay.def.id, args.optString("context", ""))
            "remember" -> Prefs.addMemory(this, overlay.def.id, args.optString("note", ""))
            "open_app" -> openUrl(args.optString("url", ""))
            "tap" -> {
                if (Prefs.allowScreenControl(this)) {
                    val xPct = args.optDouble("x", 50.0).coerceIn(0.0, 100.0)
                    val yPct = args.optDouble("y", 50.0).coerceIn(0.0, 100.0)
                    val targetX = (xPct / 100 * metrics.widthPixels).toFloat()
                    val targetY = (yPct / 100 * metrics.heightPixels).toFloat()
                    TouchPointerOverlay.animateAndTap(
                        this, windowManager,
                        overlay.state.x.toFloat(), overlay.state.y.toFloat(),
                        targetX, targetY,
                        overlay.pointerColor,
                    ) {
                        com.stickmanai.android.input.TapAccessibilityService.tapAt(targetX, targetY)
                    }
                }
                // Silently does nothing if not activated in MainActivity - same "no insistas"
                // pattern as the schema description tells the model.
            }
            "fight" -> {
                // Only local-to-local characters can fight - a PC ghost isn't something you can
                // punch on this screen. Failure reasons go into history so the AI learns from them.
                if (!Prefs.survivalEnabled(this)) {
                    overlay.addHistory("fight bloqueado: el sistema de vida/hambre/sed esta desactivado")
                    return@applyDecision
                }
                val targetId = args.optString("target", "").trim()
                val targetOverlay = overlays[targetId]
                if (targetOverlay == null) {
                    overlay.addHistory("fight bloqueado: no hay ningun personaje llamado \"$targetId\"")
                    return@applyDecision
                }
                val targetStats = Prefs.survival(this, targetId) ?: return@applyDecision
                if (targetStats.dead || targetOverlay.state.dead) {
                    overlay.addHistory("fight bloqueado: $targetId ya esta muerto - no tiene sentido seguir pegandole")
                    return@applyDecision
                }
                val dist = Math.abs(overlay.state.x - targetOverlay.state.x)
                if (dist > FIGHT_DISTANCE_PX) {
                    overlay.addHistory("fight bloqueado: estas a $dist px de $targetId - muy lejos para pegarle. Acercate con walk_to primero")
                    return@applyDecision
                }
                val dmg = Math.min(40, Math.max(5, Math.round(args.optDouble("strength", 12.0)).toInt()))
                val after = Prefs.applyDamage(this, targetId, dmg.toFloat())
                targetOverlay.say("¡Auch! ($dmg de daño)")
                targetOverlay.state.setEmotion("trip")
                targetOverlay.state.setFace("angry", "frown")
                overlay.state.setEmotion("angry")
                if (after.dead) targetOverlay.state.kill()
                overlay.addHistory("fight: le pegaste a $targetId ($dmg de daño) - le queda ${Math.round(after.hp)}/100 de vida" + if (after.dead) " - lo mataste" else "")
            }
            "eat", "drink" -> {
                if (!Prefs.survivalEnabled(this)) {
                    overlay.addHistory("$tool bloqueado: el sistema de vida/hambre/sed esta desactivado")
                    return@applyDecision
                }
                val kitchenPos = kitchenOverlay?.getPosition()
                if (kitchenPos == null) {
                    overlay.addHistory("$tool bloqueado: todavia no hay cocina en este lugar - no hay nada para comer/beber")
                    return@applyDecision
                }
                val dist = Math.abs(overlay.state.x - kitchenPos.first)
                if (dist > KITCHEN_DISTANCE_PX) {
                    overlay.addHistory("$tool bloqueado: la cocina esta lejos ($dist px). Anda hasta ahi con walk_to y pedi de nuevo")
                    return@applyDecision
                }
                val stats = Prefs.survival(this, overlay.def.id) ?: return@applyDecision
                val eating = tool == "eat"
                if (eating) Prefs.setSurvivalStat(this, overlay.def.id, hunger = stats.hunger + EAT_GAIN)
                else Prefs.setSurvivalStat(this, overlay.def.id, thirst = stats.thirst + EAT_GAIN)
                overlay.say(if (eating) "¡Que rico!" else "¡Uf, qué sed tenía!")
                overlay.state.setEmotion("happy")
                // Visible performance: chew/drink gesture + food window shrank/tilted, kitchen
                // station transition - runs its own timeline; the stat gain already landed.
                if (eating) playEat(overlay) else playDrink(overlay)
            }
            "wait" -> { /* no-op */ }
        }
    }

    // --- Food/drink visible props (mirror of PC's src/jsEngine/foodProp.js) ---------------------
    // Which rigs actually exist under assets/rigs on this device - the pizza and cup come from
    // there, so playEat/playDrink degrade silently to just the gesture if the files are missing.
    private val density: Float get() = resources.displayMetrics.density

    private fun foodsAvailable(): List<String> =
        FoodPropOverlay.FOOD_CANDIDATES.filter { RigFigure.forCharacterOrNull(this, it) != null }

    private fun nextFoodId(): String? {
        val list = foodsAvailable()
        if (list.isEmpty()) return null
        val id = list[foodCycle % list.size]
        foodCycle++
        return id
    }

    // Top of the character's head where the mouth is, in screen px - food slides toward this and
    // gets bitten from here. Character windows are anchored by their bottom-center (state.x/y).
    private fun foodMouthPos(overlay: CharacterOverlay): Pair<Int, Int> {
        val sizePx = overlay.sizePx
        val bx = overlay.state.x - sizePx / 2
        val by = overlay.state.y - sizePx
        val bias = if (overlay.state.lookRight) 0.6f else 0.4f
        return Pair((bx + sizePx * bias).toInt(), (by + sizePx * 0.18f).toInt())
    }

    // Slide a food overlay from one screen point to another, eased so it accelerates out of the
    // kitchen and settles at the mouth. Runs on the main handler (applyDecision already is).
    private fun slide(food: FoodPropOverlay, from: Pair<Int, Int>, to: Pair<Int, Int>, steps: Int, stepMs: Long) {
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val eased = t * t
            mainHandler.postDelayed({
                food.moveTo(
                    (from.first + (to.first - from.first) * eased).toInt(),
                    (from.second + (to.second - from.second) * eased).toInt()
                )
            }, stepMs * i)
        }
    }

    private fun playEat(overlay: CharacterOverlay) {
        val foodId = nextFoodId() ?: return
        // The act of eating is also the kitchen's "background transitions" moment on PC.
        kitchenOverlay?.nextStation()
        val food = FoodPropOverlay(this, windowManager)
        if (!food.showFood(foodId)) { food.detach(); return }
        overlay.state.startEat()
        val mouth = foodMouthPos(overlay)
        val dest = Pair(mouth.first - food.sizePx / 2, mouth.second - food.sizePx / 2)
        val kitchenCenter = kitchenOverlay?.getPosition()
        val from = kitchenCenter
            ?.let { Pair(it.first - food.sizePx / 2, dest.second + (12 * density).toInt()) }
            ?: Pair(dest.first, dest.second - (40 * density).toInt())
        slide(food, from, dest, FoodPropOverlay.SLIDE_STEPS, 50L)
        // One bite per BITE_MS, shrinking the food (wedge from the top) and jiggling it; the
        // chew gesture is already being driven by CharacterState. The final bite tick leaves
        // bites at 0, after which the whole food window slides back out (simulating the kitchen
        // counter) and detaches.
        for (i in 1 until FoodPropOverlay.EAT_BITES) {
            mainHandler.postDelayed({
                food.updateBites(FoodPropOverlay.EAT_BITES - i, (kotlin.random.Random.nextFloat() - 0.5f) * 0.14f)
            }, i * FoodPropOverlay.BITE_MS)
        }
        mainHandler.postDelayed({
            slide(food, dest, Pair(from.first, dest.second), FoodPropOverlay.SLIDE_STEPS, 45L)
            food.detach()
        }, FoodPropOverlay.EAT_DURATION_MS - FoodPropOverlay.SLIDE_STEPS * 45)
    }

    private fun playDrink(overlay: CharacterOverlay) {
        kitchenOverlay?.nextStation()
        val food = FoodPropOverlay(this, windowManager)
        if (!food.showDrink()) { food.detach(); return }
        overlay.state.startDrink()
        val mouth = foodMouthPos(overlay)
        val dest = Pair(mouth.first - food.sizePx / 2, mouth.second - food.sizePx / 2)
        val kitchenCenter = kitchenOverlay?.getPosition()
        val from = kitchenCenter
            ?.let { Pair(it.first - food.sizePx / 2, dest.second + (12 * density).toInt()) }
            ?: Pair(dest.first, dest.second - (40 * density).toInt())
        slide(food, from, dest, FoodPropOverlay.SLIDE_STEPS, 50L)
        // A few gulps: tilt the cup down then back up, timed into the drink gesture.
        val gulps = 3
        for (i in 0 until gulps) {
            mainHandler.postDelayed({
                food.updateDrinkTilt(16f)
                mainHandler.postDelayed({ food.updateDrinkTilt(0f) }, FoodPropOverlay.DRINK_GULP_MS / 2)
            }, i * FoodPropOverlay.DRINK_GULP_MS + 300)
        }
        mainHandler.postDelayed({
            slide(food, dest, Pair(from.first, dest.second), FoodPropOverlay.SLIDE_STEPS, 45L)
            food.detach()
        }, FoodPropOverlay.DRINK_DURATION_MS - FoodPropOverlay.SLIDE_STEPS * 45)
    }

    private fun openUrl(url: String) {
        if (url.isBlank()) return
        try {
            val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            android.util.Log.w("StickmanAI", "no se pudo abrir la URL: $url", e)
        }
    }

    private fun buildNotification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Stickman AI", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }
}

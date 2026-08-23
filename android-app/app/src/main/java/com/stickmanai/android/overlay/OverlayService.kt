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
import com.stickmanai.android.MainActivity
import com.stickmanai.android.Prefs
import com.stickmanai.android.R
import com.stickmanai.android.ai.CameraCapture
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
    }

    private lateinit var windowManager: android.view.WindowManager
    private lateinit var chatButton: ChatButtonOverlay
    private val overlays = LinkedHashMap<String, CharacterOverlay>()
    private val pcGhosts = HashMap<String, GhostOverlay>()
    private var pcPeersCache: PcPeersResult = PcPeersResult(0, emptyList())
    private val turnsSinceSay = HashMap<String, Int>()
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
        return START_STICKY
    }

    override fun onDestroy() {
        physicsRunning = false
        serviceScope.cancel()
        overlays.values.forEach { it.detach() }
        overlays.clear()
        pcGhosts.values.forEach { it.detach() }
        pcGhosts.clear()
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
            val overlay = CharacterOverlay(
                this, character, windowManager, metrics.widthPixels, metrics.heightPixels
            ) { }
            overlay.attach()
            overlays[character.id] = overlay
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
                overlays.values.forEach { it.tick() }
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
                    tickCharacterAi(overlay, cameraBase64, screenBase64)
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
        // Skip the AI call entirely while asleep - saves quota, and a sleeping character
        // shouldn't be deciding to do anything anyway. It wakes up on its own after
        // CharacterState.SLEEP_DURATION_MS or if the user drags/pinches it; any message that
        // arrives meanwhile is left in PendingMessages for the next successful (awake) turn.
        if (overlay.state.sleeping) return
        val metrics = resources.displayMetrics
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

        try {
            val decision = GeminiClient.decide(
                apiKey = apiKey,
                provider = Prefs.providerFor(this, characterId),
                personality = genderLine + Prefs.personality(this, characterId),
                recentHistory = overlay.recentHistory.toList(),
                memory = Prefs.memory(this, characterId),
                xPercent = overlay.xPercent(metrics.widthPixels),
                peers = peers,
                userMessage = userMessage,
                forceSay = silentStreak >= SILENT_TURN_LIMIT,
                cameraBase64 = cameraBase64,
                screenBase64 = screenBase64,
            )
            val (tool, args) = dedupeRepeatedAction(characterId, decision.tool, decision.args)
            turnsSinceSay[characterId] = if (tool == "say") 0 else silentStreak + 1
            mainHandler.post { applyDecision(overlay, tool, args) }
            overlay.addHistory("${tool}(${args})")
        } catch (e: Exception) {
            android.util.Log.e("StickmanAI", "decide() failed for $characterId", e)
            // Put the chat message back so it isn't silently lost on a transient network error -
            // it already got consumed above before we knew the call would fail.
            if (userMessage != null) PendingMessages.set(characterId, userMessage)
            // Used to fall back to a random walk target here, but a burst of failed decide()
            // calls (e.g. the screenshot crash-loop) made that look like the character going
            // haywire, constantly re-randomizing its target every tick. Only the AI's own
            // decisions should move the character now - on error it just stays put.
            overlay.addHistory("error: ${e.message}")
        }
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

    private fun applyDecision(overlay: CharacterOverlay, tool: String, args: JSONObject) {
        val metrics = resources.displayMetrics
        when (tool) {
            "walk_to" -> {
                val xPct = args.optDouble("x", 50.0).coerceIn(0.0, 100.0)
                overlay.state.startMoving((xPct / 100 * metrics.widthPixels).toInt(), args.optBoolean("run", false))
            }
            "set_animation" -> {
                val state = args.optString("state", "idle")
                val validStates = setOf("happy", "trip", "sad", "scared", "sit", "tired", "sleep")
                overlay.state.setEmotion(if (state in validStates) state else null)
            }
            "set_emotion" -> overlay.state.setFaceEmotion(args.optString("emotion").takeIf { it.isNotBlank() })
            "say" -> {
                val text = args.optString("text", "")
                overlay.say(text)
                if (text.isNotBlank()) {
                    com.stickmanai.android.chat.ChatNotifications.showSay(this, overlay.def.id, overlay.def.displayName, text)
                }
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
                            face = if (kf.has("face")) kf.optString("face") else null,
                        )
                    }
                    overlay.state.startCustomAnimation(keyframes)
                }
            }
            "define_personality" -> Prefs.setPersonality(this, overlay.def.id, args.optString("description", ""))
            "remember" -> Prefs.addMemory(this, overlay.def.id, args.optString("note", ""))
            "open_app" -> openUrl(args.optString("url", ""))
            "tap" -> {
                val xPct = args.optDouble("x", 50.0).coerceIn(0.0, 100.0)
                val yPct = args.optDouble("y", 50.0).coerceIn(0.0, 100.0)
                com.stickmanai.android.input.TapAccessibilityService.tapAt(
                    (xPct / 100 * metrics.widthPixels).toFloat(),
                    (yPct / 100 * metrics.heightPixels).toFloat(),
                )
            }
            "wait" -> { /* no-op */ }
        }
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

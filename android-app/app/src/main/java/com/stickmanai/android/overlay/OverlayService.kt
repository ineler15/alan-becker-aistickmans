package com.stickmanai.android.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import com.stickmanai.android.MainActivity
import com.stickmanai.android.Prefs
import com.stickmanai.android.R
import com.stickmanai.android.ai.GeminiClient
import com.stickmanai.android.ai.PcBridge
import com.stickmanai.android.ai.PcPeersResult
import com.stickmanai.android.ai.PeerInfo
import com.stickmanai.android.chat.ChatActivity
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
class OverlayService : Service() {

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
    private val overlays = LinkedHashMap<String, CharacterOverlay>()
    private val pcGhosts = HashMap<String, GhostOverlay>()
    private var pcPeersCache: PcPeersResult = PcPeersResult(0, emptyList())
    private val turnsSinceSay = HashMap<String, Int>()
    private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
    private val mainHandler = Handler(Looper.getMainLooper())
    private var physicsRunning = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, buildNotification())
        setupOverlays()
        startPhysicsLoop()
        startAiLoop()
        startPcSyncLoop()
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        physicsRunning = false
        serviceScope.cancel()
        overlays.values.forEach { it.detach() }
        overlays.clear()
        pcGhosts.values.forEach { it.detach() }
        pcGhosts.clear()
    }

    private fun setupOverlays() {
        val metrics = resources.displayMetrics
        for (character in Prefs.enabledCharacters(this)) {
            if (overlays.containsKey(character.id)) continue
            val overlay = CharacterOverlay(
                this, character, windowManager, metrics.widthPixels, metrics.heightPixels
            ) { characterId -> openChat(characterId) }
            overlay.attach()
            overlays[character.id] = overlay
        }
    }

    private fun openChat(characterId: String) {
        val intent = Intent(this, ChatActivity::class.java).apply {
            putExtra(ChatActivity.EXTRA_CHARACTER_ID, characterId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        startActivity(intent)
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
                for (overlay in overlays.values.toList()) {
                    tickCharacterAi(overlay)
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

    private suspend fun tickCharacterAi(overlay: CharacterOverlay) {
        val characterId = overlay.def.id
        val apiKey = Prefs.apiKeyFor(this, characterId)
        if (apiKey.isBlank()) return
        val metrics = resources.displayMetrics
        val userMessage = PendingMessages.consume(characterId)
        val silentStreak = turnsSinceSay[characterId] ?: 0

        val peers = overlays.values.filter { it.def.id != characterId }.map {
            PeerInfo(it.def.id, it.def.displayName, it.xPercent(metrics.widthPixels), it.lastSayText)
        } + pcPeersCache.peers.map {
            PeerInfo(it.id, it.displayName, null, it.lastSay, device = "pc")
        }

        try {
            val decision = GeminiClient.decide(
                apiKey = apiKey,
                personality = Prefs.personality(this, characterId),
                recentHistory = overlay.recentHistory.toList(),
                memory = Prefs.memory(this, characterId),
                xPercent = overlay.xPercent(metrics.widthPixels),
                peers = peers,
                userMessage = userMessage,
                forceSay = silentStreak >= SILENT_TURN_LIMIT,
            )
            turnsSinceSay[characterId] = if (decision.tool == "say") 0 else silentStreak + 1
            mainHandler.post { applyDecision(overlay, decision.tool, decision.args) }
            overlay.addHistory("${decision.tool}(${decision.args})")
        } catch (e: Exception) {
            android.util.Log.e("StickmanAI", "decide() failed for $characterId", e)
            // Put the chat message back so it isn't silently lost on a transient network error -
            // it already got consumed above before we knew the call would fail.
            if (userMessage != null) PendingMessages.set(characterId, userMessage)
            mainHandler.post { overlay.state.randomTarget() }
            overlay.addHistory("error: ${e.message}")
        }
    }

    private fun applyDecision(overlay: CharacterOverlay, tool: String, args: JSONObject) {
        val metrics = resources.displayMetrics
        when (tool) {
            "walk_to" -> {
                val xPct = args.optDouble("x", 50.0).coerceIn(0.0, 100.0)
                overlay.state.startMoving((xPct / 100 * metrics.widthPixels).toInt(), args.optBoolean("run", false))
            }
            "move_random" -> overlay.state.randomTarget(args.optBoolean("run", false))
            "set_animation" -> {
                val state = args.optString("state", "idle")
                overlay.state.setEmotion(if (state == "happy" || state == "trip" || state == "sad" || state == "scared") state else null)
            }
            "say" -> overlay.say(args.optString("text", ""))
            "define_personality" -> Prefs.setPersonality(this, overlay.def.id, args.optString("description", ""))
            "remember" -> Prefs.addMemory(this, overlay.def.id, args.optString("note", ""))
            "wait" -> { /* no-op */ }
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

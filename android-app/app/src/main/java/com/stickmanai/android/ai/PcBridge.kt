package com.stickmanai.android.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** One PC character as reported by the desktop peer server - raw PC-screen pixel coordinates. */
data class PcPeer(val id: String, val displayName: String, val x: Int, val y: Int, val lastSay: String?)

data class PcPeersResult(val screenWidth: Int, val peers: List<PcPeer>)

/**
 * Talks to the desktop app's peer server (src/net/peerServer.js) over the LAN, mirroring what
 * characters on the same device already do for each other: position + last thing said, nothing
 * more (no shared personality/memory). Best-effort - if the PC is unreachable this just no-ops.
 */
object PcBridge {

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .build()

    suspend fun pushLocalPeers(pcAddress: String, screenWidthPx: Int, peers: List<PeerInfo>): Unit =
        withContext(Dispatchers.IO) {
            if (pcAddress.isBlank()) return@withContext
            try {
                val peersJson = JSONArray(peers.map {
                    JSONObject().put("id", it.id).put("displayName", it.displayName).put("x", it.x).put("lastSay", it.lastSay)
                })
                val body = JSONObject().put("screenWidth", screenWidthPx).put("peers", peersJson)
                val request = Request.Builder()
                    .url("http://$pcAddress/peers")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(request).execute().close()
            } catch (e: Exception) {
                // best-effort - PC might be off/unreachable, that's fine
            }
        }

    suspend fun fetchRemotePeers(pcAddress: String): PcPeersResult = withContext(Dispatchers.IO) {
        if (pcAddress.isBlank()) return@withContext PcPeersResult(0, emptyList())
        try {
            val request = Request.Builder().url("http://$pcAddress/peers").get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext PcPeersResult(0, emptyList())
                val text = response.body?.string().orEmpty()
                val root = JSONObject(text)
                val screenWidth = root.optInt("screenWidth", 0)
                val array = root.optJSONArray("peers") ?: JSONArray()
                val peers = (0 until array.length()).map { i ->
                    val obj = array.getJSONObject(i)
                    PcPeer(
                        id = obj.optString("id"),
                        displayName = obj.optString("displayName"),
                        x = obj.optInt("x", 0),
                        y = obj.optInt("y", 0),
                        lastSay = obj.optString("lastSay", null.toString()).takeIf { it != "null" },
                    )
                }
                PcPeersResult(screenWidth, peers)
            }
        } catch (e: Exception) {
            PcPeersResult(0, emptyList())
        }
    }
}

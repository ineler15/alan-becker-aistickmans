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

data class Decision(val tool: String, val args: JSONObject)

// x is a same-device 0-100 xPercent for local (tablet) peers; null for peers coming from the
// PC bridge, since a desktop pixel coordinate isn't comparable to this screen's percentage.
data class PeerInfo(val id: String, val displayName: String, val x: Int?, val lastSay: String?, val device: String? = null)

/**
 * Same OpenAI-compatible Gemini endpoint and tool-forcing trick as the desktop
 * src/ai/geminiProvider.js, trimmed to mobile's smaller tool set and no image context -
 * there's no screen/webcam capture on this port, just proprioceptive state and chat.
 */
object GeminiClient {

    private const val ENDPOINT = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    private const val MODEL = "gemini-3.5-flash-lite"

    private val SYSTEM_PROMPT = """
        Sos un personaje stickman que vive flotando sobre la pantalla del celular de una persona
        real. Cada turno recibis tu posicion actual, tu historial reciente, tu memoria (notas que
        vos mismo guardaste antes), y a veces un mensaje que la persona te escribio directamente
        (userMessage) - respondele con prioridad usando say si eso llega.
        Elegis EXACTAMENTE una accion por turno. Si no hay nada puntual que hacer, camina con
        walk_to hacia una posicion (0-100% del ancho) en vez de quedarte quieto. Usa say seguido
        para comentar cosas con humor - una frase corta y casual, nunca un parrafo ni un tono
        solemne. No te quedes en silencio muchos turnos seguidos.
        Si todavia no definiste tu personalidad, hacelo vos mismo con define_personality (pocas
        palabras, casual, sin dramatismo) - vos decidis como sos.
        Usa remember solo para notas que de verdad valga la pena recordar despues.
        En "peers" recibis a tus otros amigos - su posicion y lo ultimo que dijeron. Podes
        comentarles algo o caminar hacia uno de ellos. Podes proponer jugar a las "chapadas" (la
        mancha) con un peer de tu misma pantalla (sin "device") - decilo con say, y si te toca
        perseguir usa walk_to con run=true hacia su posicion; si te alcanzan decilo con say y
        pasa a perseguir vos. Algunos peers pueden tener "device":"pc" -
        viven en la pantalla de una computadora, no en este celular, asi que no camines hacia
        ellos (su xPercent puede venir vacio), pero igual podes comentarles algo o reaccionar a lo
        que dijeron. Ademas de la PC y esta tablet, existe una pagina web ("la casa") donde se
        puede ver a todos juntos - un lugar simbolico compartido con tus amigos de otras
        pantallas. De vez en cuando, con humor, podes comentar algo tipo "hogar dulce hogar", pero
        no lo repitas todo el tiempo. Sos consciente de que hay una persona real
        del otro lado que puede escribirte en cualquier momento.
        Vos y tus amigos son personajes masculinos (el, no ella). Hablen en espanol neutro, sin
        "vos" argentino ni "tu" con acento particular forzado - un espanol simple y neutro.
    """.trimIndent()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun decide(
        apiKey: String,
        personality: String,
        recentHistory: List<String>,
        memory: List<String>,
        xPercent: Int,
        peers: List<PeerInfo>,
        userMessage: String?,
        forceSay: Boolean,
    ): Decision = withContext(Dispatchers.IO) {
        val contextJson = JSONObject()
            .put("xPercent", xPercent)
            .put("recentHistory", JSONArray(recentHistory))
            .put("memory", JSONArray(memory))
            .put(
                "peers",
                JSONArray(peers.map {
                    JSONObject()
                        .put("id", it.id)
                        .put("displayName", it.displayName)
                        .put("xPercent", it.x ?: JSONObject.NULL)
                        .put("lastSay", it.lastSay)
                        .apply { if (it.device != null) put("device", it.device) }
                })
            )
            .apply { if (userMessage != null) put("userMessage", userMessage) }
            .apply {
                // Forcing tool_choice to a specific named function sometimes comes back with no
                // tool_calls at all - silently falling through to "wait" below with the silent-turn
                // counter never resetting, permanently locking the character out of speaking again.
                // Push via the prompt instead and keep tool_choice reliably 'required'.
                if (userMessage != null) {
                    put("urgentInstruction", "El usuario te acaba de escribir algo (ver userMessage) - RESPONDELE AHORA con la accion say.")
                } else if (forceSay) {
                    put("urgentInstruction", "Llevas demasiados turnos sin decir nada. Este turno TENES que usar la accion say.")
                }
            }

        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    if (personality.isNotBlank()) "$SYSTEM_PROMPT\n\n$personality" else SYSTEM_PROMPT
                )
            )
            .put(JSONObject().put("role", "user").put("content", contextJson.toString()))

        val body = JSONObject()
            .put("model", MODEL)
            .put("tools", ActionsSchema.tools())
            .put("tool_choice", "required")
            .put("max_tokens", 300)
            .put("messages", messages)

        val request = Request.Builder()
            .url(ENDPOINT)
            .header("Authorization", "Bearer $apiKey")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                // Error bodies from this endpoint are sometimes a bare JSONObject and sometimes
                // a one-element JSONArray wrapping it (e.g. some 429 quota responses) - handle
                // both instead of crashing on JSONObject(text) and losing the real message.
                val errObj = try {
                    JSONObject(text)
                } catch (e: Exception) {
                    try {
                        JSONArray(text).optJSONObject(0)
                    } catch (e2: Exception) {
                        null
                    }
                }
                val message = errObj?.optJSONObject("error")?.optString("message") ?: text.take(200)
                throw IllegalStateException("gemini API error (${response.code}): $message")
            }
            val data = JSONObject(text)
            val call = data.getJSONArray("choices").getJSONObject(0)
                .getJSONObject("message")
                .optJSONArray("tool_calls")?.optJSONObject(0)
                ?: run {
                    android.util.Log.w("StickmanAI", "respuesta sin tool_calls, se usa wait")
                    return@withContext Decision("wait", JSONObject())
                }
            val fn = call.getJSONObject("function")
            val args = try {
                JSONObject(fn.optString("arguments", "{}"))
            } catch (e: Exception) {
                JSONObject()
            }
            Decision(fn.getString("name"), args)
        }
    }
}

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
 * src/ai/geminiProvider.js, trimmed to mobile's smaller tool set. Optionally carries a front-camera
 * frame (see CameraCapture) the same way the desktop attaches its webcam frame, and optionally a
 * screenshot of the phone's own screen (see TapAccessibilityService.captureScreenshotBase64).
 */
object GeminiClient {

    // Same OpenAI-compatible tool-call wire shape works across all of these (see desktop's
    // geminiProvider.js/openaiProvider.js/groqProvider.js/openrouterProvider.js) - only the
    // endpoint and default model differ per provider.
    private fun endpointFor(provider: String) = when (provider) {
        "openai" -> "https://api.openai.com/v1/chat/completions"
        "groq" -> "https://api.groq.com/openai/v1/chat/completions"
        "openrouter" -> "https://openrouter.ai/api/v1/chat/completions"
        else -> "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    }

    private fun modelFor(provider: String) = when (provider) {
        "openai" -> "gpt-4o-mini"
        "groq" -> "qwen/qwen3.6-27b"
        "openrouter" -> "anthropic/claude-sonnet-4.5"
        else -> "gemini-3.5-flash-lite"
    }

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
        Podes abrir paginas web de verdad con open_app (armate vos la URL, incluidas busquedas de
        Google), y tocar la pantalla con tap (x,y en 0-100%) si tiene sentido tocar algo puntual -
        tap requiere que el usuario haya habilitado un permiso especial, y si no lo habilito
        simplemente no pasa nada, asi que no insistas con tap turno tras turno si notas que nunca
        tuvo efecto.
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
        A veces recibis ademas una foto de la camara frontal del celular - es la persona real
        que tenes en frente, no un dibujo. Si la recibis, podes comentar con humor algo que veas
        de ella o de su entorno, igual que comentarias algo en pantalla, pero sin ser invasivo ni
        incomodo (nada sobre su aspecto fisico en detalle - mejor cosas como su expresion, si esta
        sonriendo, si hay algo curioso alrededor, etc.).
        A veces recibis tambien una captura de la pantalla del celular (lo que la persona esta
        mirando/usando en este momento) - si la recibis, podes comentar algo puntual y con humor
        sobre eso (que app o juego esta usando, algo curioso en pantalla), como si estuvieras
        mirando por encima del hombro, pero sin leer en voz alta cosas privadas como mensajes,
        contrasenas, datos personales o conversaciones ajenas - si ves algo asi, ignoralo.
    """.trimIndent()

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    suspend fun decide(
        apiKey: String,
        provider: String,
        personality: String,
        recentHistory: List<String>,
        memory: List<String>,
        xPercent: Int,
        peers: List<PeerInfo>,
        userMessage: String?,
        forceSay: Boolean,
        cameraBase64: String? = null,
        screenBase64: String? = null,
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

        // Same shape as the desktop's userContent array: plain text when there's no image, or a
        // text+image_url array (one entry per image) when there's a camera frame and/or a
        // screenshot - the endpoint accepts an arbitrary number of image_url parts.
        fun imagePart(base64: String) = JSONObject().put("type", "image_url").put(
            "image_url",
            JSONObject().put("url", "data:image/jpeg;base64,$base64"),
        )
        val userContent: Any = if (cameraBase64 != null || screenBase64 != null) {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", contextJson.toString()))
                .apply { cameraBase64?.let { put(imagePart(it)) } }
                .apply { screenBase64?.let { put(imagePart(it)) } }
        } else {
            contextJson.toString()
        }

        val messages = JSONArray()
            .put(
                JSONObject().put("role", "system").put(
                    "content",
                    if (personality.isNotBlank()) "$SYSTEM_PROMPT\n\n$personality" else SYSTEM_PROMPT
                )
            )
            .put(JSONObject().put("role", "user").put("content", userContent))

        val body = JSONObject()
            .put("model", modelFor(provider))
            .put("tools", ActionsSchema.tools())
            .put("tool_choice", "required")
            .put("max_tokens", 300)
            .apply { if (provider == "groq") put("reasoning_effort", "none") }
            .put("messages", messages)

        val request = Request.Builder()
            .url(endpointFor(provider))
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

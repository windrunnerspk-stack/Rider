package com.diego.rider

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * El cerebro de RIDER: envía la conversación al API de Anthropic (Claude)
 * y devuelve respuestas cortas pensadas para leerse en voz alta.
 */
class Brain(private val ctx: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val history = mutableListOf<Pair<String, String>>() // rol -> texto

    fun ask(text: String, onReply: (String) -> Unit) {
        val key = ctx.getSharedPreferences("rider", Context.MODE_PRIVATE)
            .getString("apiKey", "") ?: ""
        if (key.isEmpty()) {
            onReply("Aún no tengo cerebro conectado, Diego. Pega tu clave del API de Anthropic en la pantalla principal y guárdala.")
            return
        }
        history.add("user" to text)
        while (history.size > 16) history.removeAt(0)

        executor.execute {
            try {
                val now = SimpleDateFormat("EEEE d 'de' MMMM 'de' yyyy, HH:mm:ss", Locale("es")).format(Date())
                val body = JSONObject().apply {
                    put("model", "claude-sonnet-4-6")
                    put("max_tokens", 1000)
                    put("system", "Eres RIDER, el asistente personal de voz de Diego, al estilo Jarvis: " +
                            "eficiente, leal y con un toque de elegancia. Respondes SIEMPRE en español y de forma BREVE " +
                            "(una a tres frases), porque tus respuestas se leen en voz alta. Sin listas, sin markdown, " +
                            "sin emojis. Fecha y hora actual: $now. Si Diego pide una acción del sistema que no controlas, " +
                            "dilo con franqueza y sugiere una alternativa.")
                    put("messages", JSONArray().apply {
                        history.forEach { (role, msg) ->
                            put(JSONObject().put("role", role).put("content", msg))
                        }
                    })
                }

                val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 20000
                conn.readTimeout = 60000
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("x-api-key", key)
                conn.setRequestProperty("anthropic-version", "2023-06-01")
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }

                val code = conn.responseCode
                val raw = (if (code in 200..299) conn.inputStream else conn.errorStream)
                    ?.bufferedReader()?.readText() ?: ""
                conn.disconnect()

                if (code !in 200..299) {
                    onReply(when (code) {
                        401 -> "Mi clave del cerebro no es válida, Diego. Revisa la clave del API."
                        429 -> "El cerebro central está saturado. Intenta de nuevo en un momento."
                        else -> "Error del cerebro central, código $code."
                    })
                    return@execute
                }

                val content = JSONObject(raw).optJSONArray("content")
                val sb = StringBuilder()
                if (content != null) for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.optString("type") == "text") sb.append(b.optString("text")).append(' ')
                }
                val reply = sb.toString().trim().ifEmpty { "No obtuve respuesta del cerebro central." }
                history.add("assistant" to reply)
                onReply(reply)
            } catch (e: Exception) {
                onReply("No pude conectar con mi cerebro central. Revisa la conexión a internet, Diego.")
            }
        }
    }
}

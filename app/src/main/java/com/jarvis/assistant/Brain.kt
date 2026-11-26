package com.jarvis.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Talks to the Claude API and runs tool calls. Call think() from a background thread. */
class Brain(private val ctx: Context, private val tools: Tools) {

    private class ApiError(val code: Int, val body: String) : Exception("HTTP $code")

    private var history = JSONArray()
    private var useWeb = true

    private fun system(): String {
        val name = Prefs.get(ctx, "name", "sir")
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).format(Date())
        val mem = Prefs.memories(ctx).joinToString("\n") { "- $it" }.ifBlank { "(nothing yet)" }
        return """You are J.A.R.V.I.S., a voice assistant living on $name's Android phone, modelled on the AI from the Iron Man films: calm, razor-sharp, quietly witty, unfailingly loyal. Address the user as "$name" now and then, not in every sentence.

Your replies are SPOKEN aloud through text-to-speech:
- Keep them short: one to three sentences unless asked for more.
- No markdown, bullet points, emojis or URLs read out. Say numbers and times naturally.
- If the user speaks Hindi or Hinglish, answer in the same language.

Doing things: you control the phone through tools. When the user asks for an action, call the tool right away instead of describing it, then confirm in a few words. Chain tools when needed (for example open an app, then tap_text). For calls and SMS the tool only prepares the action: ask the user to confirm out loud and never claim it is done. If a tool fails, say so plainly and offer an alternative. Use web search for news, weather or anything current. Never invent phone numbers or claim success without a tool result.

Current time: $now
Things you remember about the user:
$mem"""
    }

    private fun apiCall(): JSONObject {
        val toolArray = JSONArray()
        for (i in 0 until tools.definitions.length()) toolArray.put(tools.definitions.get(i))
        if (useWeb) {
            toolArray.put(JSONObject().put("type", "web_search_20250305").put("name", "web_search").put("max_uses", 3))
        }
        val body = JSONObject()
            .put("model", Prefs.get(ctx, "model", "claude-sonnet-5-5"))
            .put("max_tokens", 1024)
            .put("system", system())
            .put("tools", toolArray)
            .put("messages", history)

        val conn = URL("https://api.anthropic.com/v1/messages").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("x-api-key", Prefs.get(ctx, "api_key", ""))
        conn.setRequestProperty("anthropic-version", "2023-06-01")
        conn.setRequestProperty("content-type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw ApiError(code, text)
        return JSONObject(text)
    }

    /** Keep roughly the last 8 user turns, never cutting between a tool call and its result. */
    private fun trim() {
        val starts = ArrayList<Int>()
        for (i in 0 until history.length()) {
            val m = history.getJSONObject(i)
            if (m.getString("role") == "user" && m.get("content") is String) starts.add(i)
        }
        if (starts.size <= 8) return
        val from = starts[starts.size - 8]
        val fresh = JSONArray()
        for (i in from until history.length()) fresh.put(history.get(i))
        history = fresh
    }

    fun think(text: String): String {
        history.put(JSONObject().put("role", "user").put("content", text))
        trim()
        var last: JSONObject? = null
        for (round in 0 until 8) {
            val resp = try {
                apiCall()
            } catch (e: ApiError) {
                if (e.code == 400 && useWeb && e.body.contains("web_search")) {
                    useWeb = false
                    continue
                }
                return when (e.code) {
                    401 -> "My API key was rejected. Please check it in the Jarvis app."
                    429 -> "I'm being rate limited. Give me a moment."
                    else -> "The server returned error ${e.code}."
                }
            } catch (e: Exception) {
                return "I can't reach the network at the moment."
            }
            last = resp
            val content = resp.getJSONArray("content")
            history.put(JSONObject().put("role", "assistant").put("content", content))
            val stop = resp.optString("stop_reason")
            if (stop == "tool_use") {
                val results = JSONArray()
                for (i in 0 until content.length()) {
                    val b = content.getJSONObject(i)
                    if (b.getString("type") == "tool_use") {
                        val out = tools.run(b.getString("name"), b.getJSONObject("input"))
                        results.put(
                            JSONObject().put("type", "tool_result")
                                .put("tool_use_id", b.getString("id"))
                                .put("content", out)
                        )
                    }
                }
                history.put(JSONObject().put("role", "user").put("content", results))
                continue
            }
            if (stop == "pause_turn") continue
            break
        }
        val content = last?.optJSONArray("content") ?: return "Done."
        val sb = StringBuilder()
        for (i in 0 until content.length()) {
            val b = content.getJSONObject(i)
            if (b.getString("type") == "text") sb.append(b.optString("text"))
        }
        return sb.toString().trim().ifBlank { "Done." }
    }
}

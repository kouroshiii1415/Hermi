package com.hermi.assistant.core

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Minimal OpenAI-compatible chat client with tool-calling support.
 * Providers: Hermi Bridge (keyless), Z.AI, BigModel, or any custom endpoint.
 *
 * All network calls run synchronously — call from a background thread.
 */
object LlmClient {

    class LlmException(message: String) : Exception(message)

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class LlmResponse(val content: String?, val toolCalls: List<ToolCall>)

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun chat(messages: List<JSONObject>, tools: JSONArray?, context: Context): LlmResponse {
        val provider = Prefs.provider

        val url: String
        val apiKey: String?
        val model: String?

        val body = JSONObject()
        body.put("messages", JSONArray(messages))

        when (provider) {
            Prefs.PROVIDER_BRIDGE -> {
                val bridge = Prefs.bridgeUrl
                if (!bridge.startsWith("http")) {
                    throw LlmException("Bridge URL is not set. Open Hermi → Settings → Brain.")
                }
                url = bridge
                apiKey = null
                model = null
            }
            Prefs.PROVIDER_ZAI -> {
                url = "https://api.z.ai/api/paas/v4/chat/completions"
                apiKey = Prefs.apiKey
                model = Prefs.model.ifBlank { "glm-4.5-flash" }
            }
            Prefs.PROVIDER_BIGMODEL -> {
                url = "https://open.bigmodel.cn/api/paas/v4/chat/completions"
                apiKey = Prefs.apiKey
                model = Prefs.model.ifBlank { "glm-4-flash" }
            }
            Prefs.PROVIDER_CUSTOM -> {
                val custom = Prefs.customUrl
                if (!custom.startsWith("http")) {
                    throw LlmException("Custom endpoint URL is not set.")
                }
                url = custom
                apiKey = Prefs.apiKey.ifBlank { null }
                model = Prefs.model.ifBlank { null }
            }
            else -> throw LlmException("Unknown provider: $provider")
        }

        if (model != null) body.put("model", model)
        if (provider != Prefs.PROVIDER_BRIDGE) body.put("temperature", 0.4)
        if (tools != null && tools.length() > 0) {
            body.put("tools", tools)
            body.put("tool_choice", "auto")
        }

        val builder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
        if (apiKey != null) {
            builder.header("Authorization", "Bearer $apiKey")
        }

        val response = client.newCall(builder.build()).execute()
        response.use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                throw LlmException("HTTP ${resp.code}: ${text.take(300)}")
            }

            val root = JSONObject(text)

            // Standard OpenAI format: choices[0].message
            val choice = root.optJSONArray("choices")?.optJSONObject(0)
                ?: throw LlmException("Unexpected response: ${text.take(300)}")
            val message = choice.optJSONObject("message")
                ?: throw LlmException("No message in response: ${text.take(300)}")

            val content = message.optString("content", "").trim().ifBlank { null }
            val toolCalls = mutableListOf<ToolCall>()
            val calls = message.optJSONArray("tool_calls")
            if (calls != null) {
                for (i in 0 until calls.length()) {
                    val tc = calls.optJSONObject(i) ?: continue
                    val fn = tc.optJSONObject("function") ?: continue
                    val name = fn.optString("name", "")
                    if (name.isBlank()) continue
                    toolCalls.add(
                        ToolCall(
                            id = tc.optString("id", "call_$i"),
                            name = name,
                            arguments = fn.optString("arguments", "{}").ifBlank { "{}" }
                        )
                    )
                }
            }
            return LlmResponse(content, toolCalls)
        }
    }

    /** Quick connectivity test used by Settings. */
    fun test(context: Context): String {
        val ping = listOf(JSONObject().put("role", "user").put("content", "Reply with exactly: HERMI_OK"))
        val resp = chat(ping, null, context)
        return resp.content ?: "empty"
    }
}

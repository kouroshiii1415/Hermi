package com.hermi.assistant.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Keeps the rolling conversation with the LLM (OpenAI message format):
 * system prompt with live device context + last N user/assistant/tool turns.
 */
object ConversationManager {

    private const val MAX_PERSISTED = 24
    private val messages = mutableListOf<JSONObject>()
    private var loaded = false

    @Synchronized
    private fun load() {
        if (loaded) return
        loaded = true
        try {
            val arr = JSONArray(Prefs.historyJson)
            for (i in 0 until arr.length()) {
                messages.add(arr.optJSONObject(i) ?: continue)
            }
        } catch (e: Exception) {
            messages.clear()
        }
    }

    @Synchronized
    fun systemPrompt(ctx: Context? = null): JSONObject {
        val sb = StringBuilder()
        sb.append(
            "You are Hermi, a Siri-class voice assistant running natively on the user's Android phone. " +
                "You control the device through tools (calls, SMS, alarms, timers, reminders, apps, flashlight, " +
                "volume, media, Wi-Fi/Bluetooth panels, weather, notes, contacts, web search). " +
                "RULES: 1) For any device action, ALWAYS call the matching tool instead of giving instructions. " +
                "2) If a tool returns ERROR about permissions, tell the user briefly to grant it in Hermi settings. " +
                "3) Keep spoken replies SHORT (1-2 sentences), friendly and natural — they will be read aloud. " +
                "4) You can chat normally for questions, math, translations and general knowledge. " +
                "5) The user speaks English."
        )
        if (ctx != null) {
            sb.append(" ").append(AgentTools.deviceContext(ctx))
        }
        return JSONObject().put("role", "system").put("content", sb.toString())
    }

    @Synchronized
    fun addUser(text: String) {
        load()
        messages.add(JSONObject().put("role", "user").put("content", text))
    }

    @Synchronized
    fun addAssistant(content: String?, toolCalls: List<LlmClient.ToolCall>?) {
        load()
        val m = JSONObject().put("role", "assistant").put("content", content ?: "")
        if (!toolCalls.isNullOrEmpty()) {
            val arr = JSONArray()
            for (tc in toolCalls) {
                arr.put(
                    JSONObject()
                        .put("id", tc.id)
                        .put("type", "function")
                        .put("function", JSONObject().put("name", tc.name).put("arguments", tc.arguments))
                )
            }
            m.put("tool_calls", arr)
        }
        messages.add(m)
    }

    @Synchronized
    fun addToolResult(callId: String, toolName: String, result: String) {
        load()
        messages.add(
            JSONObject()
                .put("role", "tool")
                .put("tool_call_id", callId)
                .put("content", result)
        )
    }

    /** Snapshot sent to the LLM: fresh system prompt + trimmed history. */
    @Synchronized
    fun snapshot(ctx: Context? = null): List<JSONObject> {
        load()
        val trimmed = if (messages.size > MAX_PERSISTED) messages.takeLast(MAX_PERSISTED) else messages.toList()
        return listOf(systemPrompt(ctx)) + trimmed
    }

    @Synchronized
    fun persist() {
        load()
        try {
            val trimmed = if (messages.size > MAX_PERSISTED) messages.takeLast(MAX_PERSISTED) else messages.toList()
            val arr = JSONArray()
            for (m in trimmed) arr.put(m)
            Prefs.historyJson = arr.toString()
        } catch (e: Exception) {
            // ignore persistence failures
        }
    }

    @Synchronized
    fun clear() {
        messages.clear()
        Prefs.historyJson = "[]"
    }
}

package com.hermi.assistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hermi's orchestration engine — the Siri loop:
 *   input → [instant offline path] → [LLM brain + function-calling] → tools → reply
 *
 * v1.3 speed rules (why Siri "just does it"):
 *  1. INSTANT PATH: simple device commands (call/text/alarm/timer/open/flash…)
 *     never touch the network — they execute in ~0 ms.
 *  2. SHORT-CONFIRMATION PATH: when the LLM only triggers device actions, we
 *     skip the second LLM round-trip and speak a short confirmation directly.
 *     No more "حرف اضافه".
 */
object BrainEngine {

    private const val TAG = "BrainEngine"
    private const val MAX_TOOL_LOOPS = 6

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    /** Tools that perform a device action and need no LLM commentary afterwards. */
    private val ACTION_TOOLS = setOf(
        "make_call", "send_sms", "set_alarm", "set_timer", "set_reminder",
        "open_app", "open_url", "flashlight", "media_control", "set_volume",
        "open_wifi_settings", "open_bluetooth_settings", "take_note"
    )

    interface Callback {
        /** Transient state like "Calling John…", "Tool: flashlight" */
        fun onStatus(status: String)
        /** Final spoken reply */
        fun onReply(text: String)
        fun onError(message: String)
    }

    fun process(context: Context, userInput: String, callback: Callback) {
        val appContext = context.applicationContext
        executor.execute {
            try {
                val brainOk = Prefs.isBrainConfigured()

                // ---- 1) INSTANT PATH: simple, short, single-intent commands ----
                // Guard: only short simple utterances (multi-intent sentences go to the LLM).
                val words = userInput.trim().split(Regex("\\s+"))
                val simple = words.size <= 7 && !userInput.contains(" and ", ignoreCase = true)
                if (simple) {
                    val instant = OfflineCommandRouter.handle(appContext, userInput, brainOk)
                    if (instant != null) {
                        Log.i(TAG, "instant offline path → $instant")
                        ConversationManager.addUser(userInput)
                        ConversationManager.addAssistant(instant, null)
                        ConversationManager.persist()
                        main.post { callback.onReply(instant) }
                        return@execute
                    }
                }

                if (!brainOk) {
                    Log.i(TAG, "no brain → offline router")
                    val reply = OfflineCommandRouter.handle(appContext, userInput, false)
                        ?: "I can do some things offline, but for full power open Hermi settings and connect a free GLM brain."
                    main.post { callback.onReply(reply) }
                    return@execute
                }

                main.post { callback.onStatus("Thinking…") }
                ConversationManager.addUser(userInput)

                var loops = 0
                while (true) {
                    if (loops++ > MAX_TOOL_LOOPS) {
                        ConversationManager.addAssistant("That took too many steps — let's try that again.", null)
                        main.post { callback.onReply("That took too many steps — let's try that again.") }
                        break
                    }

                    val response = LlmClient.chat(
                        ConversationManager.snapshot(appContext),
                        AgentTools.toolDefinitions(),
                        appContext
                    )

                    if (response.toolCalls.isNotEmpty()) {
                        ConversationManager.addAssistant(response.content, response.toolCalls)
                        val executed = mutableListOf<Pair<String, String>>() // name → result
                        for (call in response.toolCalls) {
                            main.post { callback.onStatus("⚡ ${prettyTool(call.name, call.arguments)}") }
                            val result = AgentTools.execute(appContext, call.name, call.arguments)
                            ConversationManager.addToolResult(call.id, call.name, result)
                            executed.add(call.name to result)
                        }

                        // ---- 2) SHORT-CONFIRMATION PATH: actions only → answer now ----
                        if (executed.isNotEmpty() && executed.all { it.first in ACTION_TOOLS }) {
                            val reply = shortConfirmation(executed)
                            ConversationManager.addAssistant(reply, null)
                            main.post { callback.onReply(reply) }
                            break
                        }
                        continue // info tools (weather, search…) → let the LLM compose
                    }

                    val reply = response.content ?: "I'm not sure what to say."
                    ConversationManager.addAssistant(reply, null)
                    main.post { callback.onReply(reply) }
                    break
                }
                ConversationManager.persist()
            } catch (e: Exception) {
                Log.e(TAG, "brain error", e)
                main.post { callback.onError(e.message ?: "Unknown brain error") }
            }
        }
    }

    /** Siri-style: "Calling mom." / "Alarm set for 07:30." — short, then silent. */
    private fun shortConfirmation(executed: List<Pair<String, String>>): String {
        val errors = executed.filter { it.second.startsWith("ERROR") }
        if (errors.isNotEmpty()) {
            val reason = errors.first().second
                .removePrefix("ERROR:")
                .trim()
                .lowercase(Locale.US)
            return "Sorry, I couldn't do that — $reason."
        }
        return executed.joinToString(" ") { it.second.trim() }
    }

    private fun prettyTool(name: String, args: String): String {
        return try {
            val a = org.json.JSONObject(args)
            when (name) {
                "make_call" -> "Calling ${a.optString("contact_name", a.optString("number", "…"))}"
                "send_sms" -> "Sending SMS…"
                "set_alarm" -> "Setting alarm…"
                "set_timer" -> "Starting timer…"
                "set_reminder" -> "Scheduling reminder…"
                "open_app" -> "Opening ${a.optString("app_name", "app")}…"
                "flashlight" -> "Flashlight ${if (a.optBoolean("on")) "on" else "off"}"
                "set_volume" -> "Volume → ${a.optInt("percent")}%"
                "get_weather" -> "Checking weather in ${a.optString("city", "…")}…"
                else -> name.replace('_', ' ')
            }
        } catch (e: Exception) {
            name.replace('_', ' ')
        }
    }
}

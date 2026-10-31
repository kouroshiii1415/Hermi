package com.hermi.assistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Hermi's orchestration engine — the Siri loop:
 *   input → [LLM brain + function-calling] → tools on device → reply
 * Falls back to OfflineCommandRouter when no brain is configured.
 */
object BrainEngine {

    private const val TAG = "BrainEngine"
    private const val MAX_TOOL_LOOPS = 6

    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

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
                if (!Prefs.isBrainConfigured()) {
                    Log.i(TAG, "no brain → offline router")
                    val reply = OfflineCommandRouter.handle(appContext, userInput)
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
                        for (call in response.toolCalls) {
                            main.post { callback.onStatus("⚡ ${prettyTool(call.name, call.arguments)}") }
                            val result = AgentTools.execute(appContext, call.name, call.arguments)
                            ConversationManager.addToolResult(call.id, call.name, result)
                        }
                        continue // let the LLM speak with tool results
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

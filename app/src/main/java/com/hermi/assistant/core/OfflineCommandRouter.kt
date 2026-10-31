package com.hermi.assistant.core

import android.content.Context
import android.content.Intent
import android.provider.AlarmClock
import java.util.Locale

/**
 * Rule-based fallback so Hermi still works (partially) with NO brain/API.
 * Handles the most common Siri-style commands with regex matching.
 * Returns the spoken reply, or null if the command was not understood.
 */
object OfflineCommandRouter {

    fun handle(ctx: Context, input: String): String? {
        val text = input.trim().lowercase(Locale.US)
        if (text.isEmpty()) return null

        // Flashlight
        Regex("(turn|switch)\\s+(on|off)\\s+(the\\s+)?(flashlight|torch|light)").find(text)?.let { m ->
            val on = m.groupValues[2] == "on"
            return AgentTools.execute(ctx, "flashlight", """{"on":$on}""")
        }
        if (Regex("^(flashlight|torch)\\s*(on|off)$").find(text) != null) {
            val on = text.endsWith("on")
            return AgentTools.execute(ctx, "flashlight", """{"on":$on}""")
        }

        // Timer: "set a timer for 5 minutes" / "timer 30 seconds"
        Regex("timer.*?(\\d+)\\s*(second|minute|hour)s?").find(text)?.let { m ->
            val n = m.groupValues[1].toLongOrNull() ?: return@let
            val seconds = when (m.groupValues[2]) {
                "hour" -> n * 3600
                "minute" -> n * 60
                else -> n
            }
            return AgentTools.execute(ctx, "set_timer", """{"seconds":$seconds}""")
        }

        // Alarm: "set alarm for 7:30" / "wake me up at 7" / "set an alarm at 7 pm"
        Regex("(alarm|wake me).*?(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(text)?.let { m ->
            var hour = m.groupValues[2].toIntOrNull() ?: return@let
            val minute = m.groupValues[3].toIntOrNull() ?: 0
            val pm = m.groupValues[4] == "pm"
            if (pm && hour < 12) hour += 12
            if (!pm && m.groupValues[4] == "am" && hour == 12) hour = 0
            if (hour !in 0..23) return@let
            return AgentTools.execute(ctx, "set_alarm", """{"hour":$hour,"minute":$minute}""")
        }

        // Call: "call 0912..." / "call mom"
        Regex("^call\\s+(.+)$").find(text)?.let { m ->
            val target = m.groupValues[1].trim()
            val args = if (target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' || it == '(' || it == ')' })
                """{"number":"$target"}"""
            else
                """{"contact_name":"${target.replace("\"", "'")}"}"""
            return AgentTools.execute(ctx, "make_call", args)
        }

        // SMS: "text john saying hi" / "send message to 0912... hello there"
        Regex("(?:text|send (?:a )?(?:sms|message))(?:\\s+to)?\\s+([\\w\\s]+?)\\s+(?:saying|that|:|about)?\\s*(.*)$").find(text)?.let { m ->
            val target = m.groupValues[1].trim()
            val msg = m.groupValues[2].trim()
            if (msg.isEmpty()) return "What should the message say?"
            val args = if (target.all { it.isDigit() || it == '+' || it == ' ' || it == '-' })
                """{"number":"$target","message":"${msg.replace("\"", "'")}"}"""
            else
                """{"contact_name":"${target.replace("\"", "'")}","message":"${msg.replace("\"", "'")}"}"""
            return AgentTools.execute(ctx, "send_sms", args)
        }

        // Open app: "open whatsapp" / "launch camera"
        Regex("^(?:open|launch|start)\\s+(?:the\\s+)?(?:app\\s+)?(.+)$").find(text)?.let { m ->
            val appName = m.groupValues[1].trim()
            if (appName.split(" ").size <= 3) {
                return AgentTools.execute(ctx, "open_app", """{"app_name":"${appName.replace("\"", "'")}"}""")
            }
        }

        // Volume: "set volume to 50" / "volume up" / "volume down" / "mute"
        Regex("volume.*?(\\d+)").find(text)?.let { m ->
            return AgentTools.execute(ctx, "set_volume", """{"percent":${m.groupValues[1]}}""")
        }
        if (Regex("volume (up|down)|louder|quieter").find(text) != null) {
            val up = Regex("up|louder").find(text) != null
            return AgentTools.execute(ctx, "set_volume", """{"percent":${if (up) 80 else 30}}""")
        }
        if (Regex("^(mute|silence)$").find(text) != null) {
            return AgentTools.execute(ctx, "set_volume", """{"percent":0}""")
        }

        // Music
        if (Regex("play (some )?music|play a song").find(text) != null) {
            return AgentTools.execute(ctx, "media_control", """{"action":"play"}""")
        }
        if (Regex("pause music|stop music|pause the music").find(text) != null) {
            return AgentTools.execute(ctx, "media_control", """{"action":"pause"}""")
        }

        // Battery / time
        if (Regex("battery|how much charge").find(text) != null) {
            return AgentTools.execute(ctx, "get_battery_status", "{}")
        }
        if (Regex("what time|current time|the time").find(text) != null) {
            return AgentTools.execute(ctx, "get_current_time", "{}")
        }
        if (Regex("what.*(day|date)|today'?s date").find(text) != null) {
            return AgentTools.execute(ctx, "get_current_time", "{}")
        }

        // WiFi / Bluetooth -> settings panels
        if (Regex("wi-?fi|internet").find(text) != null && Regex("turn on|turn off|toggle|open|settings").find(text) != null) {
            return AgentTools.execute(ctx, "open_wifi_settings", "{}")
        }
        if (Regex("bluetooth").find(text) != null && Regex("turn on|turn off|toggle|open|settings").find(text) != null) {
            return AgentTools.execute(ctx, "open_bluetooth_settings", "{}")
        }

        // Web search fallback: "search for X" / "google X"
        Regex("^(?:search(?: for)?|google)\\s+(.+)$").find(text)?.let { m ->
            return AgentTools.execute(ctx, "web_search", """{"query":"${m.groupValues[1].replace("\"", "'")}"}""")
        }

        // Hello
        if (Regex("^(hi|hello|hey|hey hermi)\\b").find(text) != null) {
            return "Hey! I'm Hermi. I'm running in offline mode — connect a GLM brain in Settings for my full power."
        }

        return null
    }

    @Suppress("unused")
    private fun noop(ctx: Context) {
        ctx.startActivity(Intent(AlarmClock.ACTION_SHOW_ALARMS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

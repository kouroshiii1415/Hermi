package com.hermi.assistant.core

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Hermi's agent tools: every real device action Siri-like assistants can do.
 * Each tool has a JSON schema (for GLM function calling) and an executor that
 * runs on the phone and returns a plain-text result back to the LLM.
 */
object AgentTools {

    private const val TAG = "AgentTools"
    const val REMINDER_CHANNEL = "hermi_reminders"
    const val REMINDER_REQUEST_CODE = 4711

    // ---------------------------------------------------------------------
    // JSON schema helpers
    // ---------------------------------------------------------------------

    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun fn(name: String, desc: String, properties: JSONObject, required: JSONArray): JSONObject =
        JSONObject()
            .put("type", "function")
            .put(
                "function",
                JSONObject()
                    .put("name", name)
                    .put("description", desc)
                    .put(
                        "parameters",
                        JSONObject()
                            .put("type", "object")
                            .put("properties", properties)
                            .put("required", required)
                    )
            )

    // ---------------------------------------------------------------------
    // Tool definitions (sent to the LLM)
    // ---------------------------------------------------------------------

    fun toolDefinitions(): JSONArray {
        val arr = JSONArray()

        arr.put(
            fn(
                "make_call",
                "Place a phone call. Provide either a phone number or a contact name.",
                JSONObject()
                    .put("number", prop("string", "E.164 or plain phone number"))
                    .put("contact_name", prop("string", "Contact name from the address book")),
                JSONArray().put("contact_name").put("number")
            )
        )
        arr.put(
            fn(
                "send_sms",
                "Send an SMS text message. Provide message plus number or contact name.",
                JSONObject()
                    .put("message", prop("string", "The message body to send"))
                    .put("number", prop("string", "Phone number"))
                    .put("contact_name", prop("string", "Contact name from the address book")),
                JSONArray().put("message")
            )
        )
        arr.put(
            fn(
                "set_alarm",
                "Set a device alarm clock (24h time).",
                JSONObject()
                    .put("hour", prop("integer", "Hour in 24h format (0-23)"))
                    .put("minute", prop("integer", "Minute (0-59)"))
                    .put("label", prop("string", "Optional alarm label")),
                JSONArray().put("hour").put("minute")
            )
        )
        arr.put(
            fn(
                "set_timer",
                "Start a countdown timer.",
                JSONObject()
                    .put("seconds", prop("integer", "Duration in seconds"))
                    .put("label", prop("string", "Optional timer label")),
                JSONArray().put("seconds")
            )
        )
        arr.put(
            fn(
                "set_reminder",
                "Schedule a reminder notification after N minutes from now.",
                JSONObject()
                    .put("text", prop("string", "What to remind"))
                    .put("minutes_from_now", prop("integer", "Delay in minutes (default 1)")),
                JSONArray().put("text")
            )
        )
        arr.put(
            fn(
                "open_app",
                "Open an installed app by name, e.g. WhatsApp, Instagram, Camera.",
                JSONObject().put("app_name", prop("string", "App name to open")),
                JSONArray().put("app_name")
            )
        )
        arr.put(
            fn(
                "open_url",
                "Open a website URL in the browser.",
                JSONObject().put("url", prop("string", "Full URL or domain")),
                JSONArray().put("url")
            )
        )
        arr.put(
            fn(
                "web_search",
                "Search the web in the browser.",
                JSONObject().put("query", prop("string", "Search query")),
                JSONArray().put("query")
            )
        )
        arr.put(
            fn(
                "flashlight",
                "Turn the phone flashlight/torch on or off.",
                JSONObject().put("on", prop("boolean", "true = on, false = off")),
                JSONArray().put("on")
            )
        )
        arr.put(
            fn(
                "media_control",
                "Control music playback: play, pause, next or previous track.",
                JSONObject()
                    .put(
                        "action",
                        JSONObject().put("type", "string")
                            .put("enum", JSONArray().put("play").put("pause").put("next").put("previous"))
                            .put("description", "Playback action")
                    ),
                JSONArray().put("action")
            )
        )
        arr.put(
            fn(
                "set_volume",
                "Set media volume to a percentage (0-100).",
                JSONObject().put("percent", prop("integer", "Volume percent 0-100")),
                JSONArray().put("percent")
            )
        )
        arr.put(
            fn(
                "open_wifi_settings",
                "Open the Wi-Fi / internet quick settings panel so the user can toggle Wi-Fi.",
                JSONObject(),
                JSONArray()
            )
        )
        arr.put(
            fn(
                "open_bluetooth_settings",
                "Open Bluetooth settings so the user can toggle it.",
                JSONObject(),
                JSONArray()
            )
        )
        arr.put(
            fn(
                "get_weather",
                "Get current weather for a city (live, via wttr.in).",
                JSONObject().put("city", prop("string", "City name, e.g. Tehran")),
                JSONArray().put("city")
            )
        )
        arr.put(
            fn(
                "get_current_time",
                "Get the phone's current date and time.",
                JSONObject(),
                JSONArray()
            )
        )
        arr.put(
            fn(
                "get_battery_status",
                "Get battery level and charging state.",
                JSONObject(),
                JSONArray()
            )
        )
        arr.put(
            fn(
                "lookup_contact",
                "Look up phone numbers for a contact name in the address book.",
                JSONObject().put("name", prop("string", "Contact name to find")),
                JSONArray().put("name")
            )
        )
        arr.put(
            fn(
                "take_note",
                "Save a note on the device.",
                JSONObject().put("text", prop("string", "Note content")),
                JSONArray().put("text")
            )
        )
        arr.put(
            fn(
                "get_notes",
                "Read the latest saved notes.",
                JSONObject(),
                JSONArray()
            )
        )
        return arr
    }

    // ---------------------------------------------------------------------
    // Device context injected into the system prompt
    // ---------------------------------------------------------------------

    fun deviceContext(ctx: Context): String {
        val timeFmt = SimpleDateFormat("EEEE, HH:mm, MMM d yyyy", Locale.US)
        val battery = try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val lvl = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            if (lvl in 1..100) "$lvl%" else "unknown"
        } catch (e: Exception) {
            "unknown"
        }
        return "Now: ${timeFmt.format(Date())}. Device: ${Build.MANUFACTURER} ${Build.MODEL}. Battery: $battery."
    }

    // ---------------------------------------------------------------------
    // Execution
    // ---------------------------------------------------------------------

    fun execute(ctx: Context, name: String, argumentsJson: String): String {
        return try {
            val args = try {
                JSONObject(argumentsJson)
            } catch (e: Exception) {
                JSONObject()
            }
            val result = run(ctx, name, args)
            Log.i(TAG, "tool $name -> $result")
            result
        } catch (e: SecurityException) {
            "ERROR: missing Android permission (${e.message}). Tell the user to grant permissions in Hermi settings."
        } catch (e: Exception) {
            Log.e(TAG, "tool $name failed", e)
            "ERROR: ${e.message ?: "tool failed"}"
        }
    }

    private fun run(ctx: Context, name: String, args: JSONObject): String = when (name) {
        "make_call" -> makeCall(ctx, args)
        "send_sms" -> sendSms(ctx, args)
        "set_alarm" -> setAlarm(ctx, args)
        "set_timer" -> setTimer(ctx, args)
        "set_reminder" -> setReminder(ctx, args)
        "open_app" -> openApp(ctx, args)
        "open_url" -> openUrl(ctx, args)
        "web_search" -> webSearch(ctx, args)
        "flashlight" -> FlashlightController.setTorch(ctx, args.optBoolean("on"))
        "media_control" -> mediaControl(ctx, args)
        "set_volume" -> setVolume(ctx, args)
        "open_wifi_settings" -> {
            ctx.startActivity(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened internet settings panel."
        }
        "open_bluetooth_settings" -> {
            ctx.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            "Opened Bluetooth settings."
        }
        "get_weather" -> getWeather(args.optString("city", ""))
        "get_current_time" -> {
            val f = SimpleDateFormat("EEEE, HH:mm:ss, MMMM d yyyy", Locale.US)
            "Current date/time: ${f.format(Date())}"
        }
        "get_battery_status" -> batteryStatus(ctx)
        "lookup_contact" -> lookupContact(ctx, args.optString("name", ""))
        "take_note" -> takeNote(ctx, args.optString("text", ""))
        "get_notes" -> getNotes(ctx)
        else -> "Unknown tool: $name"
    }

    // ---------------------------------------------------------------------
    // Individual tools
    // ---------------------------------------------------------------------

    private fun makeCall(ctx: Context, args: JSONObject): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            return "ERROR: CALL_PHONE permission not granted."
        }
        val number = resolveNumber(ctx, args)
        if (number.isNullOrBlank()) return "ERROR: no phone number or matching contact found."
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.filter { it.isDigit() || it == '+' || it == '#' || it == '*' }}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(intent)
        return "Placing call to $number."
    }

    private fun sendSms(ctx: Context, args: JSONObject): String {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.SEND_SMS) != PackageManager.PERMISSION_GRANTED) {
            return "ERROR: SEND_SMS permission not granted."
        }
        val message = args.optString("message", "")
        if (message.isBlank()) return "ERROR: empty message."
        val number = resolveNumber(ctx, args)
        if (number.isNullOrBlank()) return "ERROR: no phone number or matching contact found."
        val smsManager: SmsManager = if (Build.VERSION.SDK_INT >= 31) {
            ctx.getSystemService(SmsManager::class.java)
        } else {
            @Suppress("DEPRECATION")
            SmsManager.getDefault()
        } ?: return "ERROR: SmsManager unavailable."
        // split long messages
        val parts = smsManager.divideMessage(message)
        if (parts.size == 1) smsManager.sendTextMessage(number, null, message, null, null)
        else smsManager.sendMultipartTextMessage(number, null, parts, null, null)
        return "SMS sent to $number."
    }

    private fun setAlarm(ctx: Context, args: JSONObject): String {
        val hour = args.optInt("hour", -1)
        val minute = args.optInt("minute", 0)
        if (hour < 0 || hour > 23) return "ERROR: invalid hour."
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, minute)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label", "").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.startActivity(intent)
        return String.format(Locale.US, "Alarm set for %02d:%02d.", hour, minute)
    }

    private fun setTimer(ctx: Context, args: JSONObject): String {
        val seconds = args.optInt("seconds", 0)
        if (seconds <= 0) return "ERROR: invalid timer duration."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        args.optString("label", "").takeIf { it.isNotBlank() }?.let { intent.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        ctx.startActivity(intent)
        return "Timer started for $seconds seconds."
    }

    private fun setReminder(ctx: Context, args: JSONObject): String {
        val text = args.optString("text", "")
        if (text.isBlank()) return "ERROR: empty reminder text."
        val minutes = (args.optInt("minutes_from_now", 1)).coerceAtLeast(1)
        val triggerAt = System.currentTimeMillis() + minutes * 60_000L

        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx,
            REMINDER_REQUEST_CODE + text.hashCode(),
            reminderIntent(ctx, text),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val canExact = if (Build.VERSION.SDK_INT >= 31) am.canScheduleExactAlarms() else true
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
        return "Reminder scheduled in $minutes minute(s): \"$text\"."
    }

    fun reminderIntent(ctx: Context, text: String): Intent {
        return Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
    }

    fun ensureReminderChannel(ctx: Context) {
        val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    REMINDER_CHANNEL,
                    "Hermi reminders",
                    NotificationManager.IMPORTANCE_HIGH
                )
            )
        }
    }

    private fun openApp(ctx: Context, args: JSONObject): String {
        val query = args.optString("app_name", "").trim().lowercase().replace(" ", "")
        if (query.isBlank()) return "ERROR: no app name."
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        var best: android.content.pm.ResolveInfo? = null
        var bestScore = 0
        for (ri in apps) {
            val label = ri.loadLabel(pm).toString().lowercase().replace(" ", "")
            val score = when {
                label == query -> 100
                label.startsWith(query) -> 80
                label.contains(query) -> 60
                else -> 0
            }
            if (score > bestScore) {
                bestScore = score
                best = ri
            }
        }
        val target = best ?: return "ERROR: no installed app matches \"$query\"."
        val launch = pm.getLaunchIntentForPackage(target.activityInfo.packageName)
            ?: return "ERROR: cannot launch ${target.loadLabel(pm)}."
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(launch)
        return "Opened ${target.loadLabel(pm)}."
    }

    private fun openUrl(ctx: Context, args: JSONObject): String {
        var url = args.optString("url", "").trim()
        if (url.isBlank()) return "ERROR: empty URL."
        if (!url.startsWith("http")) url = "https://$url"
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Opened $url."
    }

    private fun webSearch(ctx: Context, args: JSONObject): String {
        val q = args.optString("query", "").trim()
        if (q.isBlank()) return "ERROR: empty query."
        val url = "https://www.google.com/search?q=" + URLEncoder.encode(q, "UTF-8")
        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return "Searching the web for \"$q\"."
    }

    private fun mediaControl(ctx: Context, args: JSONObject): String {
        val action = args.optString("action", "play").lowercase()
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val keyCode = when (action) {
            "play" -> android.view.KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> android.view.KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> android.view.KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> return "ERROR: unknown media action."
        }
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
        am.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
        return "Media $action."
    }

    private fun setVolume(ctx: Context, args: JSONObject): String {
        val percent = args.optInt("percent", -1)
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val target = if (percent in 0..100) (max * percent) / 100
        else return "ERROR: percent must be 0-100."
        am.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        return "Media volume set to $percent%."
    }

    private fun batteryStatus(ctx: Context): String {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS) == BatteryManager.BATTERY_STATUS_CHARGING
        return if (level in 1..100) "Battery at $level%, ${if (charging) "charging" else "discharging"}."
        else "Battery status unavailable."
    }

    private fun lookupContact(ctx: Context, name: String): String {
        if (name.isBlank()) return "ERROR: empty name."
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            return "ERROR: READ_CONTACTS permission not granted."
        }
        val results = mutableListOf<String>()
        val cursor = ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ),
            null, null, null
        )
        cursor?.use { c ->
            val nameIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
            val numIdx = c.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER)
            while (c.moveToNext() && results.size < 5) {
                val cn = c.getString(nameIdx) ?: continue
                if (cn.lowercase(Locale.US).contains(name.lowercase(Locale.US))) {
                    results.add("$cn: ${c.getString(numIdx)}")
                }
            }
        }
        return if (results.isEmpty()) "No contact matching \"$name\"."
        else "Found: ${results.joinToString("; ")}"
    }

    private fun takeNote(ctx: Context, text: String): String {
        if (text.isBlank()) return "ERROR: empty note."
        val f = java.io.File(ctx.filesDir, "notes.txt")
        val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date())
        f.appendText("[$stamp] $text\n")
        return "Note saved."
    }

    private fun getNotes(ctx: Context): String {
        val f = java.io.File(ctx.filesDir, "notes.txt")
        if (!f.exists()) return "No notes saved yet."
        val lines = f.readLines().takeLast(5)
        return if (lines.isEmpty()) "No notes saved yet." else "Latest notes:\n" + lines.joinToString("\n")
    }

    private fun getWeather(city: String): String {
        if (city.isBlank()) return "ERROR: empty city."
        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()
        val url = "https://wttr.in/${URLEncoder.encode(city, "UTF-8")}?format=j1"
        val resp = client.newCall(Request.Builder().url(url).build()).execute()
        resp.use { r ->
            if (!r.isSuccessful) return "ERROR: weather service returned ${r.code}."
            val root = JSONObject(r.body?.string() ?: return "ERROR: empty weather response.")
            val cur = root.optJSONArray("current_condition")?.optJSONObject(0)
                ?: return "ERROR: no weather data."
            val temp = cur.optString("temp_C", "?")
            val feels = cur.optString("FeelsLikeC", "?")
            val desc = cur.optJSONArray("weatherDesc")?.optJSONObject(0)?.optString("value", "") ?: ""
            val humidity = cur.optString("humidity", "?")
            val wind = cur.optString("windspeedKmph", "?")
            return "Weather in $city: $desc, ${temp}°C (feels like ${feels}°C), humidity $humidity%, wind $wind km/h."
        }
    }

    // ---------------------------------------------------------------------

    private fun resolveNumber(ctx: Context, args: JSONObject): String? {
        val number = args.optString("number", "").trim()
        if (number.isNotBlank()) return number
        val contact = args.optString("contact_name", "").trim()
        if (contact.isBlank()) return null
        val found = lookupContact(ctx, contact)
        // "Found: Name: +98...; Name2: ..." -> extract first number after first colon
        val regex = Regex(":\\s*([+]?[0-9][0-9 ()-]{4,})")
        val match = regex.find(found) ?: return null
        return match.groupValues.get(1).trim()
    }
}

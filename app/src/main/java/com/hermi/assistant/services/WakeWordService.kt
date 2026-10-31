package com.hermi.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermi.assistant.R
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.core.SpeechManager
import com.hermi.assistant.ui.MainActivity
import com.hermi.assistant.ui.SessionActivity
import java.util.Locale

/**
 * Always-on foreground service listening for "Hey Hermi".
 *
 * v1.2 fixes:
 *  - The listening loop no longer dies after a recognition that didn't
 *    contain the keyword (v1.0/v1.1 went permanently deaf — root cause).
 *  - Fuzzy matching: Google often hears "Hey Hermi" as "hey hermie",
 *    "hey harmi", "hey army", "her name is"… all of these now trigger.
 *  - Uses the fast on-device recognizer when available, self-heals to the
 *    network recognizer if it keeps failing.
 *  - Launch path: assistant overlay (showSession) → SessionActivity (if
 *    overlay permission) → heads-up notification tap as last resort.
 */
class WakeWordService : Service() {

    companion object {
        const val TAG = "WakeWordService"
        const val CHANNEL_ID = "hermi_wake"
        const val FALLBACK_CHANNEL_ID = "hermi_wake_fallback"
        const val NOTIF_ID = 1001
        const val FALLBACK_NOTIF_ID = 1002

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            val intent = Intent(context, WakeWordService::class.java)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }

        // Words Google commonly produces for "Hermi" — unambiguous variants.
        private val EXACT_ALIASES = setOf(
            "hermi", "hermie", "hermes", "herme", "hermy", "harmi", "harmee",
            "hormi", "hormy", "hirmi", "hurmi", "ermi", "hermo", "hermey"
        )

        // Riskier mishearings — only accepted next to a greeting word
        // ("hey army!", "hi mimi"…) to avoid false positives.
        private val GATED_ALIASES = setOf(
            "army", "mimi", "remi", "herby", "hemi", "herne", "yermi"
        )

        private val GREETINGS = setOf(
            "hey", "hay", "heyy", "heyo", "hi", "hiya", "hello", "yo", "ok", "okay"
        )

        fun levenshtein(a: String, b: String): Int {
            if (a == b) return 0
            if (a.isEmpty()) return b.length
            if (b.isEmpty()) return a.length
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return prev[b.length]
        }

        /**
         * Decides whether a recognized phrase contains the wake word.
         */
        fun containsWakeWord(raw: String): Boolean {
            val clean = raw.lowercase(Locale.US).replace(Regex("[^a-z ]"), " ")
            val tokens = clean.split(" ").filter { it.isNotBlank() }
            if (tokens.isEmpty()) return false

            // Tier 1: clear Hermi variants anywhere in the phrase.
            for (t in tokens) {
                if (t in EXACT_ALIASES) return true
                if (t.length in 5..7 && levenshtein(t, "hermi") <= 1) return true
                if (t.length in 6..8 && levenshtein(t, "hermie") <= 1) return true
            }

            // Tier 2: risky mishearings only when a greeting is present.
            val hasGreeting = tokens.any {
                it in GREETINGS || (it.length in 3..5 && levenshtein(it, "hey") <= 1)
            }
            if (hasGreeting) {
                if (tokens.any { it in GATED_ALIASES }) return true
                // "hey, her name is…" → classic mishearing of "hey hermi"
                if (tokens.zipWithNext().any { (a, b) -> a == "her" && b == "name" }) return true
            }
            return false
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speech: SpeechManager? = null
    private var destroyed = false
    private var attempts = 0
    private var suppressedUntil = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        destroyed = false
        startForegroundWithType()
        Prefs.init(this)
        createSpeech()
        startListeningLoop()
        Log.i(TAG, "Wake word service started (v1.2)")
    }

    private fun startForegroundWithType() {
        val notif = buildNotification(CHANNEL_ID, NOTIF_ID, false)
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(
                NOTIF_ID,
                notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(channelId: String, id: Int, highPriority: Boolean): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            val importance =
                if (highPriority) NotificationManager.IMPORTANCE_HIGH
                else NotificationManager.IMPORTANCE_LOW
            nm.createNotificationChannel(NotificationChannel(channelId, "Hermi wake word", importance))
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_hermi_notification)
            .setOngoing(!highPriority)
            .setAutoCancel(highPriority)
            .setContentTitle(getString(R.string.wake_notif_title))
            .setContentText(getString(R.string.wake_notif_text))
            .setContentIntent(open)
        if (highPriority) builder.setPriority(NotificationCompat.PRIORITY_HIGH)
        return builder.build()
    }

    private fun createSpeech() {
        speech = SpeechManager(
            this,
            object : SpeechManager.Callback {
                override fun onStart() {
                    attempts = 0
                }

                override fun onPartial(text: String) {
                    if (suppressed()) return
                    if (containsWakeWord(text)) trigger()
                }

                override fun onFinal(text: String) {
                    if (suppressed()) return
                    if (containsWakeWord(text)) trigger()
                    else scheduleRestart(250) // CRITICAL FIX: keep listening after any final result
                }

                override fun onError(message: String) {
                    // Busy/no-match/speech-timeout are normal in a wake-word loop.
                    attempts++
                    val delay = when {
                        attempts <= 2 -> 500L
                        attempts <= 5 -> 1200L
                        else -> 2500L
                    }
                    scheduleRestart(delay)
                }
            },
            preferOnDevice = true
        )
    }

    private fun startListeningLoop() {
        if (destroyed) return
        if (!Prefs.wakeWordEnabled) {
            stopSelf()
            return
        }
        speech?.start()
    }

    private fun scheduleRestart(delayMs: Long) {
        if (destroyed) return
        handler.removeCallbacks(restartRunnable)
        handler.postDelayed(restartRunnable, delayMs)
    }

    private val restartRunnable = Runnable { startListeningLoop() }

    private fun suppressed(): Boolean =
        SystemClock.elapsedRealtime() < suppressedUntil

    private fun trigger() {
        suppressedUntil = SystemClock.elapsedRealtime() + 4000
        Log.i(TAG, "Wake word detected!")
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            handler.postDelayed({ tone.release() }, 400)
        } catch (e: Exception) {
            // ignore
        }
        try {
            val vib = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= 26) {
                vib?.vibrate(VibrationEffect.createOneShot(60, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vib?.vibrate(60)
            }
        } catch (e: Exception) {
            // ignore
        }
        speech?.stop()

        launchSession()

        // resume listening once the session has had a chance to run
        scheduleRestart(3500)
    }

    /**
     * Three launch paths, most reliable first:
     *  1. System assistant overlay (showSession) — always works from background.
     *  2. SessionActivity — allowed from background when overlay permission granted.
     *  3. Heads-up notification — user taps to open.
     */
    private fun launchSession() {
        if (isDefaultAssistant()) {
            try {
                startService(
                    Intent(this, HermiVoiceInteractionService::class.java)
                        .setAction(HermiVoiceInteractionService.ACTION_SHOW_SESSION)
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "showSession path failed: ${e.message}")
            }
        }
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(
                    Intent(this, SessionActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                )
                return
            } catch (e: Exception) {
                Log.w(TAG, "SessionActivity path failed: ${e.message}")
            }
        }
        // Last resort: heads-up notification
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val open = PendingIntent.getActivity(
                this, 1,
                Intent(this, SessionActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(this, FALLBACK_CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_hermi_notification)
                .setContentTitle(getString(R.string.wake_fallback_title))
                .setContentText(getString(R.string.wake_fallback_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build()
            nm.notify(FALLBACK_NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.e(TAG, "fallback notification failed", e)
        }
    }

    private fun isDefaultAssistant(): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val rm = getSystemService(RoleManager::class.java)
                if (rm != null) rm.isRoleHeld(RoleManager.ROLE_ASSISTANT) else legacyAssistantCheck()
            } else {
                legacyAssistantCheck()
            }
        } catch (e: Exception) {
            legacyAssistantCheck()
        }
    }

    private fun legacyAssistantCheck(): Boolean {
        return try {
            Settings.Secure.getString(contentResolver, "voice_interaction_service")
                ?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        handler.removeCallbacksAndMessages(null)
        speech?.stop()
        speech = null
        Log.i(TAG, "Wake word service stopped")
        super.onDestroy()
    }
}

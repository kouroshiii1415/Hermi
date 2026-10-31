package com.hermi.assistant.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.hermi.assistant.R
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.core.SpeechManager
import com.hermi.assistant.ui.MainActivity
import com.hermi.assistant.ui.SessionActivity

/**
 * Always-on foreground service listening for "Hey Hermi".
 * Uses Android's SpeechRecognizer in a restart loop and scans partial
 * results for the keyword, then opens the SessionActivity overlay.
 */
class WakeWordService : Service() {

    companion object {
        const val TAG = "WakeWordService"
        const val CHANNEL_ID = "hermi_wake"
        const val NOTIF_ID = 1001
        const val KEYWORD = "hermi"

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
    }

    private val handler = Handler(Looper.getMainLooper())
    private var speech: SpeechManager? = null
    private var triggered = false
    private var destroyed = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        destroyed = false
        startForegroundWithType()
        Prefs.init(this)
        startListeningLoop()
        Log.i(TAG, "Wake word service started")
    }

    private fun startForegroundWithType() {
        val notif = buildNotification()
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

    private fun buildNotification(): Notification {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Hermi wake word", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hermi_notification)
            .setContentTitle(getString(R.string.wake_notif_title))
            .setContentText(getString(R.string.wake_notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun startListeningLoop() {
        if (destroyed) return
        triggered = false
        speech?.stop()
        speech = SpeechManager(this, object : SpeechManager.Callback {
            override fun onStart() {}

            override fun onPartial(text: String) {
                if (triggered) return
                val clean = text.lowercase().replace(",", " ").replace(".", " ")
                if (clean.contains(KEYWORD)) {
                    triggered = true
                    onWakeWord()
                }
            }

            override fun onFinal(text: String) {
                if (!triggered && text.lowercase().contains(KEYWORD)) {
                    triggered = true
                    onWakeWord()
                }
            }

            override fun onError(message: String) {
                // restart with a small delay (busy / no match / timeouts are normal)
                handler.postDelayed({ startListeningLoop() }, 700)
            }
        })
        speech?.start()
    }

    private fun onWakeWord() {
        Log.i(TAG, "Wake word detected!")
        try {
            val tone = ToneGenerator(AudioManager.STREAM_MUSIC, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
            handler.postDelayed({ tone.release() }, 400)
        } catch (e: Exception) {
            // ignore
        }
        speech?.stop()

        val intent = Intent(this, SessionActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(intent)

        // resume listening after the session has had a chance to finish
        handler.postDelayed({ if (!destroyed) startListeningLoop() }, 2500)
    }

    override fun onDestroy() {
        destroyed = true
        isRunning = false
        speech?.stop()
        speech = null
        handler.removeCallbacksAndMessages(null)
        Log.i(TAG, "Wake word service stopped")
        super.onDestroy()
    }
}

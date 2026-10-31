package com.hermi.assistant.services

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.voice.VoiceInteractionService
import android.util.Log
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.ui.SessionActivity

/**
 * Registered as the system assistant: long-press Home / power swipe / "Hey Google" replacement.
 * The actual voice UI is drawn by HermiSession (via HermiSessionService).
 *
 * v1.2: exposes ACTION_SHOW_SESSION so the wake-word service can open the
 * assistant overlay from the background (the same way Siri pops up on iOS).
 */
class HermiVoiceInteractionService : VoiceInteractionService() {

    companion object {
        const val TAG = "HermiVIS"
        const val ACTION_SHOW_SESSION = "com.hermi.assistant.SHOW_SESSION"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var ready = false
    private var pendingShow = false

    override fun onReady() {
        super.onReady()
        ready = true
        Prefs.init(this)
        if (pendingShow) {
            pendingShow = false
            doShow()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_SHOW_SESSION) {
            if (ready) doShow() else pendingShow = true
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun doShow() {
        try {
            showSession(Bundle.EMPTY, 0)
        } catch (e: Exception) {
            Log.w(TAG, "showSession failed (${e.message}); trying SessionActivity")
            try {
                if (Settings.canDrawOverlays(this)) {
                    startActivity(
                        Intent(this, SessionActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    )
                }
            } catch (ignored: Exception) {
                Log.e(TAG, "SessionActivity fallback also failed", ignored)
            }
        }
    }
}

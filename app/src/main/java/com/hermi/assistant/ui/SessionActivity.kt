package com.hermi.assistant.ui

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.hermi.assistant.R
import com.hermi.assistant.core.BrainEngine
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.core.SpeechManager
import com.hermi.assistant.core.TtsManager

/**
 * Full-screen Siri-style voice session, launched by the wake-word service.
 * Animated orb + transcript → thinks → acts → speaks → dismisses.
 * v1.3: Siri-like overlay UI with live mic-reactive orb.
 */
class SessionActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var replyText: TextView? = null
    private var orb: OrbView? = null
    private var speech: SpeechManager? = null
    private var processing = false
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_session)

        statusText = findViewById(R.id.txtSessionStatus)
        transcriptText = findViewById(R.id.txtSessionTranscript)
        replyText = findViewById(R.id.txtSessionReply)
        orb = findViewById(R.id.orb)

        // tap anywhere to dismiss
        findViewById<android.view.View>(R.id.sessionRoot).setOnClickListener { finishIt() }

        // safety: auto-dismiss after 90s
        handler.postDelayed({ finishIt() }, 90_000)
    }

    override fun onResume() {
        super.onResume()
        orb?.start()
        setOrbState(OrbView.STATE_LISTENING)
        statusText?.text = "LISTENING"
        handler.postDelayed({ startListening() }, 400)
    }

    override fun onPause() {
        super.onPause()
        speech?.stop()
    }

    override fun finish() {
        super.finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    private fun startListening() {
        speech?.stop()
        speech = SpeechManager(this, object : SpeechManager.Callback {
            override fun onStart() {}

            override fun onRms(rmsdB: Float) {
                orb?.onRms(rmsdB)
            }

            override fun onPartial(text: String) {
                transcriptText?.text = text
            }

            override fun onFinal(text: String) {
                if (processing) return
                processing = true
                transcriptText?.text = text
                statusText?.text = "THINKING"
                setOrbState(OrbView.STATE_THINKING)
                handleInput(text)
            }

            override fun onError(message: String) {
                if (processing) {
                    return
                }
                statusText?.text = "LISTENING"
                setOrbState(OrbView.STATE_LISTENING)
                handler.postDelayed({ finishIt() }, 1400)
            }
        })
        speech?.start()
    }

    private fun handleInput(text: String) {
        BrainEngine.process(this, text, object : BrainEngine.Callback {
            override fun onStatus(status: String) {
                statusText?.text = status.uppercase()
            }

            override fun onReply(text: String) {
                statusText?.text = "HERMI"
                replyText?.text = text
                setOrbState(OrbView.STATE_SPEAKING)
                TtsManager.speak(this@SessionActivity, text) {
                    handler.postDelayed({ finishIt() }, 2200)
                }
            }

            override fun onError(message: String) {
                statusText?.text = "ERROR"
                replyText?.text = message
                setOrbState(OrbView.STATE_IDLE)
                TtsManager.speak(this@SessionActivity, "Sorry, something went wrong.") {
                    handler.postDelayed({ finishIt() }, 1800)
                }
            }
        })
    }

    private fun setOrbState(state: Int) {
        orb?.setOrbState(state)
    }

    private fun finishIt() {
        if (finished) return
        finished = true
        speech?.stop()
        orb?.stop()
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.stop()
        speech = null
        orb?.stop()
        orb = null
        handler.removeCallbacksAndMessages(null)
    }
}

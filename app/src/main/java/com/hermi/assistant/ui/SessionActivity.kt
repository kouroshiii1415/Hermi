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
 * Full-screen translucent voice session, launched by the wake-word service.
 * Listens → thinks → acts → speaks → dismisses.
 */
class SessionActivity : AppCompatActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var replyText: TextView? = null
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

        // safety: auto-dismiss after 90s
        handler.postDelayed({ finishIt() }, 90_000)
    }

    override fun onResume() {
        super.onResume()
        handler.postDelayed({ startListening() }, 400)
    }

    override fun onPause() {
        super.onPause()
        speech?.stop()
    }

    private fun startListening() {
        speech?.stop()
        speech = SpeechManager(this, object : SpeechManager.Callback {
            override fun onStart() {}

            override fun onPartial(text: String) {
                transcriptText?.text = text
            }

            override fun onFinal(text: String) {
                if (processing) return
                processing = true
                transcriptText?.text = text
                statusText?.text = "Thinking…"
                handleInput(text)
            }

            override fun onError(message: String) {
                if (processing) {
                    return
                }
                statusText?.text = message
                handler.postDelayed({ finishIt() }, 1400)
            }
        })
        speech?.start()
    }

    private fun handleInput(text: String) {
        BrainEngine.process(this, text, object : BrainEngine.Callback {
            override fun onStatus(status: String) {
                statusText?.text = status
            }

            override fun onReply(text: String) {
                statusText?.text = "Hermi"
                replyText?.text = text
                TtsManager.speak(this@SessionActivity, text) {
                    handler.postDelayed({ finishIt() }, 2200)
                }
            }

            override fun onError(message: String) {
                statusText?.text = "Error"
                replyText?.text = message
                TtsManager.speak(this@SessionActivity, "Sorry, something went wrong.") {
                    handler.postDelayed({ finishIt() }, 1800)
                }
            }
        })
    }

    private fun finishIt() {
        if (finished) return
        finished = true
        speech?.stop()
        finish()
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.stop()
        speech = null
        handler.removeCallbacksAndMessages(null)
    }
}

package com.hermi.assistant.services

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.view.LayoutInflater
import android.widget.TextView
import com.hermi.assistant.R
import com.hermi.assistant.core.BrainEngine
import com.hermi.assistant.core.SpeechManager
import com.hermi.assistant.core.TtsManager
import com.hermi.assistant.ui.OrbView

/**
 * The overlay UI shown when the user invokes the assistant gesture
 * (long-press Home, power swipe, or assistant corner-swipe).
 * v1.3: Siri-like full-screen design with the animated orb.
 */
class HermiSession(context: android.content.Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var replyText: TextView? = null
    private var orb: OrbView? = null
    private var speech: SpeechManager? = null
    private var processing = false

    override fun onCreateContentView(): android.view.View? {
        val view = LayoutInflater.from(context).inflate(R.layout.activity_session, null)
        statusText = view.findViewById(R.id.txtSessionStatus)
        transcriptText = view.findViewById(R.id.txtSessionTranscript)
        replyText = view.findViewById(R.id.txtSessionReply)
        orb = view.findViewById(R.id.orb)
        view.findViewById<android.view.View>(R.id.sessionRoot).setOnClickListener { hide() }
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        transcriptText?.text = ""
        replyText?.text = ""
        statusText?.text = "LISTENING"
        orb?.setOrbState(OrbView.STATE_LISTENING)
        orb?.start()
        processing = false
        handler.postDelayed({ startListening() }, 350)
    }

    override fun onHide() {
        super.onHide()
        speech?.stop()
        orb?.stop()
        TtsManager.stop()
    }

    private fun startListening() {
        speech?.stop()
        speech = SpeechManager(context, object : SpeechManager.Callback {
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
                orb?.setOrbState(OrbView.STATE_THINKING)
                handleInput(text)
            }

            override fun onError(message: String) {
                if (processing) return
                statusText?.text = "LISTENING"
                orb?.setOrbState(OrbView.STATE_LISTENING)
                // retry once, then dismiss
                handler.postDelayed({
                    if (!processing) {
                        hide()
                    }
                }, 1500)
            }
        })
        speech?.start()
    }

    private fun handleInput(text: String) {
        BrainEngine.process(context, text, object : BrainEngine.Callback {
            override fun onStatus(status: String) {
                statusText?.text = status.uppercase()
            }

            override fun onReply(text: String) {
                statusText?.text = "HERMI"
                replyText?.text = text
                orb?.setOrbState(OrbView.STATE_SPEAKING)
                TtsManager.speak(context, text) {
                    handler.postDelayed({ hide() }, 2500)
                }
            }

            override fun onError(message: String) {
                statusText?.text = "ERROR"
                replyText?.text = message
                orb?.setOrbState(OrbView.STATE_IDLE)
                TtsManager.speak(context, "Sorry, something went wrong.") {
                    handler.postDelayed({ hide() }, 2000)
                }
            }
        })
    }
}

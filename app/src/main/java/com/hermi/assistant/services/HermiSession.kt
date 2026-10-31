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

/**
 * The overlay UI shown when the user invokes the assistant gesture
 * (long-press Home, power swipe, or assistant corner-swipe).
 */
class HermiSession(context: android.content.Context) : VoiceInteractionSession(context) {

    private val handler = Handler(Looper.getMainLooper())
    private var statusText: TextView? = null
    private var transcriptText: TextView? = null
    private var replyText: TextView? = null
    private var speech: SpeechManager? = null
    private var processing = false

    override fun onCreateContentView(): android.view.View? {
        val view = LayoutInflater.from(context).inflate(R.layout.activity_session, null)
        statusText = view.findViewById(R.id.txtSessionStatus)
        transcriptText = view.findViewById(R.id.txtSessionTranscript)
        replyText = view.findViewById(R.id.txtSessionReply)
        return view
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        transcriptText?.text = ""
        replyText?.text = ""
        statusText?.text = "Listening…"
        processing = false
        handler.postDelayed({ startListening() }, 350)
    }

    override fun onHide() {
        super.onHide()
        speech?.stop()
        TtsManager.stop()
    }

    private fun startListening() {
        speech?.stop()
        speech = SpeechManager(context, object : SpeechManager.Callback {
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
                if (processing) return
                statusText?.text = message
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
                statusText?.text = status
            }

            override fun onReply(text: String) {
                statusText?.text = "Hermi"
                replyText?.text = text
                TtsManager.speak(context, text) {
                    handler.postDelayed({ hide() }, 2500)
                }
            }

            override fun onError(message: String) {
                statusText?.text = "Error"
                replyText?.text = message
                TtsManager.speak(context, "Sorry, something went wrong.") {
                    handler.postDelayed({ hide() }, 2000)
                }
            }
        })
    }
}

package com.hermi.assistant.ui

import android.graphics.Color
import android.os.Bundle
import android.speech.SpeechRecognizer
import android.text.TextUtils
import android.view.Gravity
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.hermi.assistant.R
import com.hermi.assistant.core.BrainEngine
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.core.SpeechManager
import com.hermi.assistant.core.TtsManager

class ChatActivity : AppCompatActivity() {

    private lateinit var boxMessages: LinearLayout
    private lateinit var scrollChat: ScrollView
    private lateinit var etInput: EditText
    private lateinit var txtStatus: TextView
    private var speech: SpeechManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_chat)

        boxMessages = findViewById(R.id.boxMessages)
        scrollChat = findViewById(R.id.scrollChat)
        etInput = findViewById(R.id.etInput)
        txtStatus = findViewById(R.id.txtStatus)

        findViewById<ImageButton>(R.id.btnSend).setOnClickListener {
            val text = etInput.text?.toString()?.trim().orEmpty()
            if (text.isNotEmpty()) {
                etInput.setText("")
                submit(text)
            }
        }

        findViewById<ImageButton>(R.id.btnMic).setOnClickListener { startVoiceInput() }

        if (!Prefs.isBrainConfigured()) {
            addBubble("hermi", "Hi! I'm Hermi 🤖 I work offline for simple things, but connect my GLM brain in Settings for full Siri-level power!")
        } else {
            addBubble("hermi", "Hey! I'm listening — type or tap the mic. 🎙")
        }
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Speech recognition unavailable", Toast.LENGTH_SHORT).show()
            return
        }
        txtStatus.text = "🎙 Listening…"
        speech?.stop()
        speech = SpeechManager(this, object : SpeechManager.Callback {
            override fun onStart() {}

            override fun onPartial(text: String) {
                runOnUiThread { txtStatus.text = "🎙 $text" }
            }

            override fun onFinal(text: String) {
                runOnUiThread {
                    txtStatus.text = ""
                    etInput.setText(text)
                    submit(text)
                }
            }

            override fun onError(message: String) {
                runOnUiThread { txtStatus.text = message }
            }
        })
        speech?.start()
    }

    private fun submit(text: String) {
        addBubble("user", text)
        txtStatus.text = "Thinking…"
        BrainEngine.process(this, text, object : BrainEngine.Callback {
            override fun onStatus(status: String) {
                runOnUiThread { txtStatus.text = status }
            }

            override fun onReply(text: String) {
                runOnUiThread {
                    txtStatus.text = ""
                    addBubble("hermi", text)
                    TtsManager.speak(this@ChatActivity, text)
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    txtStatus.text = ""
                    addBubble("hermi", "⚠️ $message")
                }
            }
        })
    }

    private fun addBubble(who: String, text: String) {
        val tv = TextView(this)
        tv.text = text
        tv.maxLines = 12
        tv.ellipsize = TextUtils.TruncateAt.END
        tv.setPadding(38, 26, 38, 26)
        tv.textSize = 15f
        val params = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        params.setMargins(0, 8, 0, 8)
        params.bottomMargin = 8
        params.topMargin = 8

        if (who == "user") {
            params.gravity = Gravity.END
            params.marginStart = 120
            tv.background = getResources().getDrawable(R.drawable.bg_mic_button, theme)
            tv.setTextColor(Color.WHITE)
        } else {
            params.gravity = Gravity.START
            params.marginEnd = 120
            tv.background = getResources().getDrawable(R.drawable.bg_session_card, theme)
            tv.setTextColor(Color.parseColor("#EDE9F5"))
        }
        tv.layoutParams = params
        boxMessages.addView(tv)
        scrollChat.post { scrollChat.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun onDestroy() {
        super.onDestroy()
        speech?.stop()
        speech = null
    }
}

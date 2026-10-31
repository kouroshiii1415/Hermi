package com.hermi.assistant.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

/**
 * Text-to-speech wrapper. Uses an English voice, prefers female voices,
 * slightly raised pitch for the "Hermi" personality.
 */
object TtsManager {

    private const val TAG = "TtsManager"

    private var tts: TextToSpeech? = null
    private var ready = false
    private val pendingQueue = mutableListOf<Pair<String, (() -> Unit)?>>()
    private var initializing = false

    /** Ensure the engine exists. Returns true when ready. */
    @Synchronized
    private fun ensure(context: Context): Boolean {
        if (ready) return true
        if (tts == null && !initializing) {
            initializing = true
            tts = TextToSpeech(context.applicationContext) { status ->
                synchronized(this) {
                    initializing = false
                    if (status == TextToSpeech.SUCCESS) {
                        try {
                            tts?.language = Locale.US
                            selectVoice()
                            tts?.setPitch(Prefs.voicePitch)
                            tts?.setSpeechRate(1.0f)
                            ready = true
                        } catch (e: Exception) {
                            Log.e(TAG, "tts config failed", e)
                        }
                    } else {
                        Log.e(TAG, "TTS init failed: $status")
                    }
                    // flush queued utterances
                    val queue = pendingQueue.toList()
                    pendingQueue.clear()
                    if (ready) {
                        for ((text, done) in queue) doSpeak(text, done)
                    } else {
                        for ((_, done) in queue) done?.invoke()
                    }
                }
            }
        }
        return false
    }

    private fun selectVoice() {
        try {
            val voices = tts?.voices ?: return
            val en = voices.filter { it.locale.language == "en" }
            if (en.isEmpty()) return

            // v1.3 voice-quality ranking — Siri-level clarity:
            //  1) Google "network" neural voices (best quality, needs internet)
            //  2) known female voices
            //  3) any en-US, then any English
            val network = en.filter { it.name.contains("network", ignoreCase = true) }
            val female = en.firstOrNull { v ->
                v.name.contains("female", ignoreCase = true) ||
                    listOf("samantha", "victoria", "karen", "serena", "zira", "susan", "aria", "jenny")
                        .any { n -> v.name.contains(n, true) }
            }
            val chosen = network.firstOrNull { it.locale.country == "US" }
                ?: network.firstOrNull()
                ?: female
                ?: en.firstOrNull { it.locale.country == "US" }
                ?: en.first()
            tts?.voice = chosen
        } catch (e: Exception) {
            Log.w(TAG, "voice select failed", e)
        }
    }

    /** Speak text; init lazily if needed. Safe from any thread. */
    @Synchronized
    fun speak(context: Context, text: String, onDone: (() -> Unit)? = null) {
        if (text.isBlank()) {
            onDone?.invoke()
            return
        }
        if (ensure(context)) {
            doSpeak(text, onDone)
        } else {
            pendingQueue.add(text to onDone)
        }
    }

    private fun doSpeak(text: String, onDone: (() -> Unit)?) {
        val engine = tts ?: run { onDone?.invoke(); return }
        val id = "hermi_${System.currentTimeMillis()}"
        if (onDone != null) {
            engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    if (utteranceId == id) onDone()
                }
            })
        }
        engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    @Synchronized
    fun stop() {
        try {
            tts?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    @Synchronized
    fun shutdown() {
        try {
            tts?.shutdown()
        } catch (e: Exception) {
            // ignore
        }
        tts = null
        ready = false
        pendingQueue.clear()
    }

    val isReady: Boolean get() = ready
}

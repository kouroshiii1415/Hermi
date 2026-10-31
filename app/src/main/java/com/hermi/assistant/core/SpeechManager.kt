package com.hermi.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * English speech-to-text wrapper around Android's built-in SpeechRecognizer.
 * Must be created/used on the main thread.
 */
class SpeechManager(
    private val context: Context,
    private val callback: Callback
) : RecognitionListener {

    interface Callback {
        fun onStart()
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onError(message: String)
    }

    companion object {
        private const val TAG = "SpeechManager"

        fun isAvailable(context: Context): Boolean =
            SpeechRecognizer.isRecognitionAvailable(context)
    }

    private var recognizer: SpeechRecognizer? = null

    fun start() {
        stop()
        if (!isAvailable(context)) {
            callback.onError("Speech recognition is not available on this device.")
            return
        }
        try {
            val sr = SpeechRecognizer.createSpeechRecognizer(context)
            sr.setRecognitionListener(this)
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(
                    RecognizerIntent.EXTRA_CALLING_PACKAGE,
                    context.packageName
                )
            }
            sr.startListening(intent)
            recognizer = sr
            callback.onStart()
        } catch (e: Exception) {
            Log.e(TAG, "start failed", e)
            callback.onError("Could not start mic: ${e.message}")
        }
    }

    fun stop() {
        try {
            recognizer?.stopListening()
            recognizer?.destroy()
        } catch (e: Exception) {
            // ignore
        }
        recognizer = null
    }

    // ---- RecognitionListener ----

    override fun onReadyForSpeech(params: Bundle?) {}
    override fun onBeginningOfSpeech() {}
    override fun onRmsChanged(rmsdB: Float) {}
    override fun onBufferReceived(buffer: ByteArray?) {}
    override fun onEndOfSpeech() {}

    override fun onError(error: Int) {
        val msg = when (error) {
            SpeechRecognizer.ERROR_AUDIO -> "Audio error"
            SpeechRecognizer.ERROR_CLIENT -> "Client error"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission missing"
            SpeechRecognizer.ERROR_NETWORK -> "Network error (speech service)"
            SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
            SpeechRecognizer.ERROR_NO_MATCH -> "I didn't catch that"
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
            SpeechRecognizer.ERROR_SERVER -> "Speech server error"
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected"
            else -> "Speech error $error"
        }
        callback.onError(msg)
    }

    override fun onResults(results: Bundle?) {
        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
        if (!text.isNullOrBlank()) callback.onFinal(text)
        else callback.onError("No speech recognized")
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) callback.onPartial(text)
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

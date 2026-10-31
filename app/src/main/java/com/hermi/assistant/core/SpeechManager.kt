package com.hermi.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale

/**
 * English speech-to-text wrapper around Android's SpeechRecognizer.
 * Must be created/used on the main thread.
 *
 * v1.2: optional on-device recognizer (API 31+, fast & offline — ideal for
 * the wake word). If the on-device engine keeps failing (e.g. English model
 * not downloaded), it automatically falls back to the standard recognizer.
 */
class SpeechManager(
    private val context: Context,
    private val callback: Callback,
    private val preferOnDevice: Boolean = false
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

        // Errors that are part of normal life in a listening loop:
        // silence, no match, busy. They do NOT indicate a broken engine.
        private val SOFT_ERRORS = setOf(
            SpeechRecognizer.ERROR_NO_MATCH,
            SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
            SpeechRecognizer.ERROR_RECOGNIZER_BUSY
        )
    }

    private var recognizer: SpeechRecognizer? = null
    private var usingOnDevice = false
    private var onDeviceHardFailures = 0

    fun start() {
        stop()
        if (!isAvailable(context)) {
            callback.onError("Speech recognition is not available on this device.")
            return
        }
        try {
            val sr = createRecognizer()
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

    private fun createRecognizer(): SpeechRecognizer {
        if (preferOnDevice &&
            onDeviceHardFailures < 3 &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        ) {
            try {
                usingOnDevice = true
                return SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
            } catch (e: Exception) {
                Log.w(TAG, "on-device recognizer unavailable, falling back: ${e.message}")
            }
        }
        usingOnDevice = false
        return SpeechRecognizer.createSpeechRecognizer(context)
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
        // Count only hard engine failures toward the on-device fallback;
        // silence/busy are normal and must not burn the fallback budget.
        if (usingOnDevice && error !in SOFT_ERRORS) {
            onDeviceHardFailures++
            Log.w(TAG, "on-device hard failure #$onDeviceHardFailures: $error")
        }
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
        if (!text.isNullOrBlank()) {
            if (usingOnDevice) onDeviceHardFailures = 0
            callback.onFinal(text)
        } else {
            callback.onError("No speech recognized")
        }
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults
            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            ?.firstOrNull()
        if (!text.isNullOrBlank()) {
            if (usingOnDevice) onDeviceHardFailures = 0
            callback.onPartial(text)
        }
    }

    override fun onEvent(eventType: Int, params: Bundle?) {}
}

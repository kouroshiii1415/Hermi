package com.hermi.assistant.services

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import com.hermi.assistant.core.Prefs

/**
 * Registered as the system assistant: long-press Home / power swipe / "Hey Google" replacement.
 * The actual voice UI is drawn by HermiSession (via HermiSessionService).
 */
class HermiVoiceInteractionService : VoiceInteractionService() {

    override fun onReady() {
        super.onReady()
        Prefs.init(this)
    }
}

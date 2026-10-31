package com.hermi.assistant.services

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import com.hermi.assistant.core.Prefs

class HermiSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        Prefs.init(this)
        return HermiSession(this)
    }
}

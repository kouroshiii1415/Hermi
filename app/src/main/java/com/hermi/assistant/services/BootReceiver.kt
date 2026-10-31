package com.hermi.assistant.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.hermi.assistant.core.Prefs

/**
 * Restarts the wake-word listener after reboot if the user enabled it.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        Prefs.init(context)
        if (Prefs.wakeWordEnabled) {
            WakeWordService.start(context)
        }
    }
}

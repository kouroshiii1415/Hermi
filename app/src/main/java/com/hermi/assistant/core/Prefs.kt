package com.hermi.assistant.core

import android.content.Context
import android.content.SharedPreferences

/**
 * Central settings store for Hermi.
 */
object Prefs {
    private const val FILE = "hermi_prefs"

    const val PROVIDER_BRIDGE = "bridge"
    const val PROVIDER_ZAI = "zai"
    const val PROVIDER_BIGMODEL = "bigmodel"
    const val PROVIDER_CUSTOM = "custom"

    private lateinit var sp: SharedPreferences

    fun init(ctx: Context) {
        if (!::sp.isInitialized) {
            sp = ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        }
    }

    private fun check() {
        if (!::sp.isInitialized) {
            throw IllegalStateException("Prefs.init(context) was not called")
        }
    }

    // ---- Provider ----
    var provider: String
        get() = check().let { sp.getString("provider", PROVIDER_BRIDGE) ?: PROVIDER_BRIDGE }
        set(v) = check().let { sp.edit().putString("provider", v).apply() }

    var bridgeUrl: String
        get() = check().let { sp.getString("bridge_url", "") ?: "" }
        set(v) = check().let { sp.edit().putString("bridge_url", v.trim()).apply() }

    var apiKey: String
        get() = check().let { sp.getString("api_key", "") ?: "" }
        set(v) = check().let { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = check().let { sp.getString("model", "") ?: "" }
        set(v) = check().let { sp.edit().putString("model", v.trim()).apply() }

    var customUrl: String
        get() = check().let { sp.getString("custom_url", "") ?: "" }
        set(v) = check().let { sp.edit().putString("custom_url", v.trim()).apply() }

    // ---- Features ----
    var wakeWordEnabled: Boolean
        get() = check().let { sp.getBoolean("wake_word", false) }
        set(v) = check().let { sp.edit().putBoolean("wake_word", v).apply() }

    var voicePitch: Float
        get() = check().let { sp.getFloat("voice_pitch", 1.1f) }
        set(v) = check().let { sp.edit().putFloat("voice_pitch", v).apply() }

    // ---- Conversation history (persisted as JSON) ----
    var historyJson: String
        get() = check().let { sp.getString("history", "[]") ?: "[]" }
        set(v) = check().let { sp.edit().putString("history", v).apply() }

    /**
     * A brain is usable when:
     *  - bridge provider has a URL, or
     *  - zai/bigmodel has an API key, or
     *  - custom has a URL.
     */
    fun isBrainConfigured(): Boolean {
        check()
        return when (provider) {
            PROVIDER_BRIDGE -> bridgeUrl.startsWith("http")
            PROVIDER_ZAI, PROVIDER_BIGMODEL -> apiKey.isNotBlank()
            PROVIDER_CUSTOM -> customUrl.startsWith("http")
            else -> false
        }
    }
}

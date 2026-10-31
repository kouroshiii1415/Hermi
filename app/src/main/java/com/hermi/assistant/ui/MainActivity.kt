package com.hermi.assistant.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial
import com.hermi.assistant.R
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.services.WakeWordService

class MainActivity : AppCompatActivity() {

    private lateinit var txtBrainStatus: TextView
    private lateinit var txtPermStatus: TextView
    private lateinit var swWakeWord: SwitchMaterial

    private val corePerms = mutableListOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.CALL_PHONE,
        Manifest.permission.SEND_SMS,
        Manifest.permission.READ_CONTACTS
    )

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshStatus()
        }

    private val notifLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            refreshStatus()
        }

    private val assistantLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    private val overlayLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            refreshStatus()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_main)

        txtBrainStatus = findViewById(R.id.txtBrainStatus)
        txtPermStatus = findViewById(R.id.txtPermStatus)
        swWakeWord = findViewById(R.id.swWakeWord)

        findViewById<MaterialButton>(R.id.btnSetupBrain).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnGrantCore).setOnClickListener {
            val toRequest = corePerms.filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (toRequest.isNotEmpty()) permLauncher.launch(toRequest.toTypedArray())
        }

        findViewById<MaterialButton>(R.id.btnGrantNotif).setOnClickListener {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                Toast.makeText(this, "Notifications already allowed", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnSetAssistant).setOnClickListener {
            try {
                assistantLauncher.launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
            } catch (e: Exception) {
                Toast.makeText(this, "Open Settings → Apps → Default apps → Digital assistant app", Toast.LENGTH_LONG).show()
            }
        }

        findViewById<MaterialButton>(R.id.btnOverlay).setOnClickListener {
            if (Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Overlay already allowed", Toast.LENGTH_SHORT).show()
            } else {
                overlayLauncher.launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
        }

        findViewById<MaterialButton>(R.id.btnOpenChat).setOnClickListener {
            startActivity(Intent(this, ChatActivity::class.java))
        }

        findViewById<MaterialButton>(R.id.btnOpenSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        swWakeWord.setOnCheckedChangeListener { _, checked ->
            Prefs.wakeWordEnabled = checked
            if (checked) {
                val missing = missingCorePerms()
                if (missing.isNotEmpty()) {
                    Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
                    swWakeWord.isChecked = false
                    Prefs.wakeWordEnabled = false
                    return@setOnCheckedChangeListener
                }
                WakeWordService.start(this)
                Toast.makeText(this, "Say “Hey Hermi” anytime ✨", Toast.LENGTH_SHORT).show()
            } else {
                WakeWordService.stop(this)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        // sync switch with actual service state
        val running = WakeWordService.isRunning
        swWakeWord.setOnCheckedChangeListener(null)
        swWakeWord.isChecked = running || Prefs.wakeWordEnabled
        swWakeWord.setOnCheckedChangeListener { _, checked ->
            Prefs.wakeWordEnabled = checked
            if (checked) {
                if (missingCorePerms().isNotEmpty()) {
                    Toast.makeText(this, "Grant microphone permission first", Toast.LENGTH_SHORT).show()
                    swWakeWord.isChecked = false
                    Prefs.wakeWordEnabled = false
                } else {
                    WakeWordService.start(this)
                }
            } else {
                WakeWordService.stop(this)
            }
        }
    }

    private fun missingCorePerms(): List<String> = corePerms.filter {
        ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
    }

    private fun refreshStatus() {
        // Brain status
        txtBrainStatus.text = when {
            !Prefs.isBrainConfigured() -> "❌ Not connected. Bridge URL or a free GLM key is required for conversations."
            Prefs.provider == Prefs.PROVIDER_BRIDGE -> "🌉 Bridge mode — GLM brain, free, no key."
            Prefs.provider == Prefs.PROVIDER_ZAI -> "⚡ Z.AI GLM (${Prefs.model.ifBlank { "glm-4.5-flash" }}) — free model."
            Prefs.provider == Prefs.PROVIDER_BIGMODEL -> "⚡ BigModel GLM (${Prefs.model.ifBlank { "glm-4-flash" }})."
            else -> "🛠 Custom endpoint."
        }

        // Permission status
        val parts = mutableListOf<String>()
        parts.add(if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) "🎙 Mic ✓" else "🎙 Mic ✗")
        parts.add(if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) "📞 Calls ✓" else "📞 Calls ✗")
        parts.add(if (ContextCompat.checkSelfPermission(this, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED) "✉️ SMS ✓" else "✉️ SMS ✗")
        parts.add(if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED) "👤 Contacts ✓" else "👤 Contacts ✗")
        parts.add(if (Settings.canDrawOverlays(this)) "🪟 Overlay ✓" else "🪟 Overlay ✗")
        parts.add(if (isDefaultAssistant()) "🤝 Assistant ✓" else "🤝 Assistant ✗")
        txtPermStatus.text = parts.joinToString("   ")
    }

    private fun isDefaultAssistant(): Boolean {
        return try {
            val setting = Settings.Secure.getString(contentResolver, "voice_interaction_service")
            setting?.contains(packageName) == true
        } catch (e: Exception) {
            false
        }
    }
}

package com.hermi.assistant.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import com.hermi.assistant.R
import com.hermi.assistant.core.LlmClient
import com.hermi.assistant.core.Prefs
import com.hermi.assistant.core.TtsManager
import java.util.concurrent.Executors

class SettingsActivity : AppCompatActivity() {

    private val providers = listOf(
        "Bridge (free, no key)" to Prefs.PROVIDER_BRIDGE,
        "Z.AI (free GLM-4.5-Flash)" to Prefs.PROVIDER_ZAI,
        "BigModel (free GLM-4-Flash)" to Prefs.PROVIDER_BIGMODEL,
        "Custom (OpenAI-compatible)" to Prefs.PROVIDER_CUSTOM
    )

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var spinner: AutoCompleteTextView
    private lateinit var etBridge: TextInputEditText
    private lateinit var etKey: TextInputEditText
    private lateinit var etModel: TextInputEditText
    private lateinit var etCustom: TextInputEditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Prefs.init(this)
        setContentView(R.layout.activity_settings)

        spinner = findViewById(R.id.spinnerProvider)
        etBridge = findViewById(R.id.etBridgeUrl)
        etKey = findViewById(R.id.etApiKey)
        etModel = findViewById(R.id.etModel)
        etCustom = findViewById(R.id.etCustomUrl)

        spinner.setAdapter(
            ArrayAdapter(this, android.R.layout.simple_list_item_1, providers.map { it.first })
        )
        spinner.setOnItemClickListener { _, _, pos, _ -> onProviderChanged(providers[pos].second) }

        // Load current values
        val currentIdx = providers.indexOfFirst { it.second == Prefs.provider }.takeIf { it >= 0 } ?: 0
        spinner.setText(providers[currentIdx].first, false)
        etBridge.setText(Prefs.bridgeUrl)
        etKey.setText(Prefs.apiKey)
        etModel.setText(Prefs.model)
        etCustom.setText(Prefs.customUrl)
        updateFieldVisibility(providers[currentIdx].second)

        findViewById<MaterialButton>(R.id.btnSaveBrain).setOnClickListener { save() }
        findViewById<MaterialButton>(R.id.btnTestBrain).setOnClickListener { test() }
        findViewById<MaterialButton>(R.id.btnOpenZai).setOnClickListener {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://z.ai/manage-api-key/api-key-list"))
                )
            } catch (e: Exception) {
                Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun currentProvider(): String =
        providers.firstOrNull { it.first == spinner.text.toString() }?.second ?: Prefs.PROVIDER_BRIDGE

    private fun onProviderChanged(p: String) {
        updateFieldVisibility(p)
        // Prefill defaults for convenience
        when (p) {
            Prefs.PROVIDER_ZAI -> if (etModel.text.isNullOrBlank()) etModel.setText("glm-4.5-flash")
            Prefs.PROVIDER_BIGMODEL -> if (etModel.text.isNullOrBlank()) etModel.setText("glm-4-flash")
        }
    }

    private fun updateFieldVisibility(p: String) {
        findViewById<View>(R.id.tilBridge).visibility =
            if (p == Prefs.PROVIDER_BRIDGE) View.VISIBLE else View.GONE
        findViewById<View>(R.id.tilApiKey).visibility =
            if (p == Prefs.PROVIDER_BRIDGE) View.GONE else View.VISIBLE
        findViewById<View>(R.id.tilModel).visibility =
            if (p == Prefs.PROVIDER_CUSTOM) View.VISIBLE else View.VISIBLE
        findViewById<View>(R.id.tilCustom).visibility =
            if (p == Prefs.PROVIDER_CUSTOM) View.VISIBLE else View.GONE
    }

    private fun save() {
        Prefs.provider = currentProvider()
        Prefs.bridgeUrl = etBridge.text?.toString()?.trim() ?: ""
        Prefs.apiKey = etKey.text?.toString()?.trim() ?: ""
        Prefs.model = etModel.text?.toString()?.trim() ?: ""
        Prefs.customUrl = etCustom.text?.toString()?.trim() ?: ""

        if (!Prefs.isBrainConfigured()) {
            Toast.makeText(this, "⚠️ Fill the required field for this provider", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Brain saved ✅", Toast.LENGTH_SHORT).show()
        finish()
    }

    private fun test() {
        // Save silently first so LlmClient sees current values
        Prefs.provider = currentProvider()
        Prefs.bridgeUrl = etBridge.text?.toString()?.trim() ?: ""
        Prefs.apiKey = etKey.text?.toString()?.trim() ?: ""
        Prefs.model = etModel.text?.toString()?.trim() ?: ""
        Prefs.customUrl = etCustom.text?.toString()?.trim() ?: ""

        if (!Prefs.isBrainConfigured()) {
            Toast.makeText(this, "⚠️ Fill the required field first", Toast.LENGTH_LONG).show()
            return
        }

        Toast.makeText(this, "Testing brain…", Toast.LENGTH_SHORT).show()
        executor.execute {
            try {
                val reply = LlmClient.test(this)
                runOnUiThread {
                    Toast.makeText(this, "✅ Brain replied: $reply", Toast.LENGTH_LONG).show()
                    TtsManager.speak(this, "My brain is connected!")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "❌ Failed: ${e.message?.take(180)}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
    }
}

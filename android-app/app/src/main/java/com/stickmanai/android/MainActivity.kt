package com.stickmanai.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.stickmanai.android.databinding.ActivityMainBinding
import com.stickmanai.android.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val apiKeyFields = HashMap<String, EditText>()
    private val providerFields = HashMap<String, Spinner>()

    // First entry means "usar el compartido" (empty -> Prefs.providerFor falls back to shared).
    private val perCharacterProviderOptions = listOf("(compartido)") + Prefs.PROVIDERS

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    // If denied, characters just run without a camera frame (CameraCapture checks the
    // permission itself before every capture) - not a hard requirement to use the app.
    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.spinnerSharedProvider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Prefs.PROVIDERS)
        binding.spinnerSharedProvider.setSelection(Prefs.PROVIDERS.indexOf(Prefs.sharedProvider(this)).coerceAtLeast(0))
        binding.editSharedApiKey.setText(Prefs.sharedApiKey(this))
        binding.editPcAddress.setText(Prefs.pcAddress(this))

        buildCharacterRows()

        binding.btnGrantOverlay.setOnClickListener {
            if (!Settings.canDrawOverlays(this)) {
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            } else {
                Toast.makeText(this, "Ya concedido", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnGrantAccessibility.setOnClickListener {
            if (com.stickmanai.android.input.TapAccessibilityService.isEnabled) {
                Toast.makeText(this, "Ya habilitado", Toast.LENGTH_SHORT).show()
            } else {
                // No per-service API to check/request this directly - just send the user to the
                // general Accessibility settings screen where they enable "Stickman AI" by hand.
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }

        binding.btnStart.setOnClickListener { startOverlayService() }
        binding.btnStop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }
        binding.btnGroupChat.setOnClickListener {
            startActivity(
                Intent(this, com.stickmanai.android.chat.ChatActivity::class.java).apply {
                    putExtra(
                        com.stickmanai.android.chat.ChatActivity.EXTRA_CHARACTER_ID,
                        com.stickmanai.android.chat.ChatActivity.GROUP_CHAT_ID,
                    )
                }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
        requestCameraPermission.launch(android.Manifest.permission.CAMERA)
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun buildCharacterRows() {
        for (character in CHARACTERS) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

            val checkBox = CheckBox(this).apply {
                text = character.displayName
                isChecked = Prefs.isEnabled(this@MainActivity, character.id)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            val apiKeyField = EditText(this).apply {
                hint = "API key propia (opcional)"
                setText(Prefs.perCharacterApiKey(this@MainActivity, character.id))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            apiKeyFields[character.id] = apiKeyField

            val providerField = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, perCharacterProviderOptions)
                val current = Prefs.perCharacterProvider(this@MainActivity, character.id)
                setSelection(if (current.isBlank()) 0 else perCharacterProviderOptions.indexOf(current).coerceAtLeast(0))
            }
            providerFields[character.id] = providerField

            row.addView(checkBox)
            row.addView(providerField)
            row.addView(apiKeyField)
            binding.characterList.addView(row)

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                Prefs.setEnabled(this, character.id, isChecked)
            }
        }
    }

    private fun savePrefs() {
        Prefs.setSharedProvider(this, binding.spinnerSharedProvider.selectedItem as String)
        Prefs.setSharedApiKey(this, binding.editSharedApiKey.text.toString().trim())
        Prefs.setPcAddress(this, binding.editPcAddress.text.toString().trim())
        for ((characterId, field) in apiKeyFields) {
            Prefs.setApiKeyFor(this, characterId, field.text.toString().trim())
        }
        for ((characterId, spinner) in providerFields) {
            val selected = spinner.selectedItem as String
            Prefs.setProviderFor(this, characterId, if (selected == perCharacterProviderOptions[0]) "" else selected)
        }
    }

    private fun startOverlayService() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Primero da el permiso de superposicion", Toast.LENGTH_SHORT).show()
            return
        }
        savePrefs()
        if (Prefs.enabledCharacters(this).isEmpty()) {
            Toast.makeText(this, "Activa al menos un personaje", Toast.LENGTH_SHORT).show()
            return
        }
        startForegroundService(Intent(this, OverlayService::class.java))
    }
}

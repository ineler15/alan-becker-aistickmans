package com.stickmanai.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.stickmanai.android.databinding.ActivityMainBinding
import com.stickmanai.android.overlay.OverlayService

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val apiKeyFields = HashMap<String, EditText>()

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

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

        binding.btnStart.setOnClickListener { startOverlayService() }
        binding.btnStop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
        }
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

            row.addView(checkBox)
            row.addView(apiKeyField)
            binding.characterList.addView(row)

            checkBox.setOnCheckedChangeListener { _, isChecked ->
                Prefs.setEnabled(this, character.id, isChecked)
            }
        }
    }

    private fun savePrefs() {
        Prefs.setSharedApiKey(this, binding.editSharedApiKey.text.toString().trim())
        Prefs.setPcAddress(this, binding.editPcAddress.text.toString().trim())
        for ((characterId, field) in apiKeyFields) {
            Prefs.setApiKeyFor(this, characterId, field.text.toString().trim())
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

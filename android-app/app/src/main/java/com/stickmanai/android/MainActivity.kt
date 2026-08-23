package com.stickmanai.android

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private val partnerFields = HashMap<String, Spinner>()
    private val affectionFields = HashMap<String, SeekBar>()

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
        binding.checkAllowScreenControl.isChecked = Prefs.allowScreenControl(this)
        binding.checkAllowScreenControl.setOnCheckedChangeListener { _, checked ->
            Prefs.setAllowScreenControl(this, checked)
        }

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

        binding.btnCreateCharacter.setOnClickListener {
            startActivity(Intent(this, com.stickmanai.android.CreateCharacterActivity::class.java))
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
        // Overlay and accessibility can't be requested via a normal runtime-permission dialog -
        // each is its own dedicated system Settings screen the user has to flip a switch on, so
        // there's no way to ask for both (or all four) in a single prompt. Chaining them via
        // onResume - launching the next missing one as soon as the user returns from the
        // previous screen - is the closest thing to "ask for everything at once" this allows.
        requestOverlayOrAccessibilityIfNeeded()
    }

    override fun onResume() {
        super.onResume()
        requestOverlayOrAccessibilityIfNeeded()
        // Picks up any character just created in CreateCharacterActivity - buildCharacterRows()
        // clears characterList first, so this doesn't duplicate rows on every resume.
        buildCharacterRows()
    }

    // Each screen only auto-launches once per MainActivity lifetime (i.e. once per app open) -
    // without this, backing out of one without granting it would just relaunch the same screen
    // every time onResume fires, trapping the user in a loop they can't dismiss.
    private var autoPromptedOverlay = false
    private var autoPromptedAccessibility = false

    private fun requestOverlayOrAccessibilityIfNeeded() {
        if (!Settings.canDrawOverlays(this)) {
            if (!autoPromptedOverlay) {
                autoPromptedOverlay = true
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
        } else if (!com.stickmanai.android.input.TapAccessibilityService.isEnabled) {
            if (!autoPromptedAccessibility) {
                autoPromptedAccessibility = true
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    override fun onPause() {
        super.onPause()
        savePrefs()
    }

    private fun buildCharacterRows() {
        binding.characterList.removeAllViews()
        for (character in allCharacters(this)) {
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

            // Options/ids stashed on the Spinner's tag so savePrefs() can read the selection back
            // without recomputing (and risking a mismatched order) - "(sin pareja)" is index 0.
            val partnerIds = listOf<String?>(null) + allCharacters(this).filter { it.id != character.id }.map { it.id }
            val partnerLabels = listOf("(sin pareja)") + allCharacters(this).filter { it.id != character.id }.map { it.displayName }
            val partnerField = Spinner(this).apply {
                adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, partnerLabels)
                setSelection(partnerIds.indexOf(Prefs.partnerFor(this@MainActivity, character.id)).coerceAtLeast(0))
                tag = partnerIds
            }
            partnerFields[character.id] = partnerField

            // How strong that affection is - a slider instead of just on/off, only meaningful
            // once a target is picked above (disabled otherwise).
            val affectionField = SeekBar(this).apply {
                layoutParams = LinearLayout.LayoutParams((80 * resources.displayMetrics.density).toInt(), LinearLayout.LayoutParams.WRAP_CONTENT)
                max = 100
                progress = Prefs.affectionFor(this@MainActivity, character.id)
                isEnabled = partnerField.selectedItemPosition != 0
            }
            partnerField.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                    affectionField.isEnabled = position != 0
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            })
            affectionFields[character.id] = affectionField

            row.addView(checkBox)
            row.addView(providerField)
            row.addView(apiKeyField)
            row.addView(partnerField)
            row.addView(affectionField)

            // Only custom characters (see Prefs.customMeta) have appearance fields worth editing -
            // the vanilla/built-in ones (Red, TCO, etc.) don't get this button.
            if (Prefs.customMeta(this, character.id) != null) {
                val editButton = Button(this).apply {
                    text = "Editar"
                    setOnClickListener {
                        startActivity(
                            Intent(this@MainActivity, CreateCharacterActivity::class.java).apply {
                                putExtra(CreateCharacterActivity.EXTRA_EDIT_ID, character.id)
                            }
                        )
                    }
                }
                row.addView(editButton)
            }

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
        for ((characterId, spinner) in partnerFields) {
            @Suppress("UNCHECKED_CAST")
            val ids = spinner.tag as? List<String?>
            val selectedId = ids?.getOrNull(spinner.selectedItemPosition)
            Prefs.setPartnerFor(this, characterId, selectedId)
        }
        for ((characterId, seekBar) in affectionFields) {
            Prefs.setAffectionFor(this, characterId, seekBar.progress)
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

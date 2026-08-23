package com.stickmanai.android

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.stickmanai.android.databinding.ActivityCreateCharacterBinding
import com.stickmanai.android.overlay.RigFigure
import com.stickmanai.android.overlay.RigTemplate
import com.stickmanai.android.overlay.RigView

/**
 * "Crear tu propio stickman" - name + color (from the shared Palette) + head model (hollow vs
 * normal, see RigTemplate). Mirrors renderer/createCharacter.html/js on the desktop app so the
 * feature works the same way on both platforms.
 */
class CreateCharacterActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCreateCharacterBinding
    private var selectedColor: IntArray = Palette.COLORS[0]
    private var previewView: RigView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateCharacterBinding.inflate(layoutInflater)
        setContentView(binding.root)

        buildSwatches()
        setupColorSliders()
        binding.headGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.checkHasFace.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.genderGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        binding.accessoryGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()

        binding.btnCreate.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.create_character_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val headModel = if (isHollow()) "hollow" else "normal"
            val def = Prefs.addCustomCharacter(this, name, headModel, binding.checkHasFace.isChecked, genderValue(), accessoryValue())
            val rig = RigTemplate.build(this, selectedColor, isHollow())
            RigTemplate.save(this, def.id, rig)
            finish()
        }
    }

    private fun isHollow(): Boolean = binding.radioHollow.isChecked

    private fun genderValue(): String = when (binding.genderGroup.checkedRadioButtonId) {
        binding.radioMasculino.id -> "masculino"
        binding.radioFemenino.id -> "femenino"
        else -> "otro"
    }

    private fun accessoryValue(): String = when (binding.accessoryGroup.checkedRadioButtonId) {
        binding.radioAccessoryHair.id -> "hair"
        binding.radioAccessoryBow.id -> "bow"
        else -> "none"
    }

    private fun buildSwatches() {
        val sizePx = (32 * resources.displayMetrics.density).toInt()
        val marginPx = (6 * resources.displayMetrics.density).toInt()
        for (color in Palette.COLORS) {
            val swatch = View(this).apply {
                layoutParams = GridLayout.LayoutParams().apply {
                    width = sizePx
                    height = sizePx
                    setMargins(marginPx, marginPx, marginPx, marginPx)
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Palette.toColorInt(color))
                }
                setOnClickListener {
                    selectedColor = color
                    syncSlidersToSelectedColor()
                    updatePreview()
                }
            }
            binding.swatchGrid.addView(swatch)
        }
    }

    // "Elegir cualquier color" - three plain RGB sliders instead of a real HSV picker widget
    // (Android has none built in without pulling a third-party dependency) - still covers the
    // full color space the swatches don't, which is all "selector avanzado" needs to mean here.
    private fun setupColorSliders() {
        val listener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                selectedColor = intArrayOf(binding.seekRed.progress, binding.seekGreen.progress, binding.seekBlue.progress, 255)
                updateColorSwatchPreview()
                updatePreview()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        }
        binding.seekRed.setOnSeekBarChangeListener(listener)
        binding.seekGreen.setOnSeekBarChangeListener(listener)
        binding.seekBlue.setOnSeekBarChangeListener(listener)
        syncSlidersToSelectedColor()
    }

    private fun syncSlidersToSelectedColor() {
        binding.seekRed.progress = selectedColor[0]
        binding.seekGreen.progress = selectedColor[1]
        binding.seekBlue.progress = selectedColor[2]
        updateColorSwatchPreview()
    }

    private fun updateColorSwatchPreview() {
        binding.colorSwatchPreview.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            setColor(Palette.toColorInt(selectedColor))
        }
    }

    private fun updatePreview() {
        val rig = RigTemplate.build(this, selectedColor, isHollow())
        val figure = RigFigure.fromJson(rig)
        binding.previewContainer.removeAllViews()
        previewView = RigView(this, figure, hasFace = binding.checkHasFace.isChecked, accessory = accessoryValue()).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        binding.previewContainer.addView(previewView)
    }
}

package com.stickmanai.android

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
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
        binding.headGroup.setOnCheckedChangeListener { _, _ -> updatePreview() }
        updatePreview()

        binding.btnCreate.setOnClickListener {
            val name = binding.editName.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(this, R.string.create_character_name_required, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val def = Prefs.addCustomCharacter(this, name)
            val rig = RigTemplate.build(this, selectedColor, isHollow())
            RigTemplate.save(this, def.id, rig)
            finish()
        }
    }

    private fun isHollow(): Boolean = binding.radioHollow.isChecked

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
                    updatePreview()
                }
            }
            binding.swatchGrid.addView(swatch)
        }
    }

    private fun updatePreview() {
        val rig = RigTemplate.build(this, selectedColor, isHollow())
        val figure = RigFigure.fromJson(rig)
        binding.previewContainer.removeAllViews()
        previewView = RigView(this, figure).apply {
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        binding.previewContainer.addView(previewView)
    }
}

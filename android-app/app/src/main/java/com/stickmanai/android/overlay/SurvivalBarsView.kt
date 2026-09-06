package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import com.stickmanai.android.Prefs

/**
 * Tiny overlay strip drawn above its character's head showing the survival stats as three
 * mini-bars (vida #e53935 / hambre #fb8c00 / sed #1e88e5) mirroring the desktop's character.js,
 * plus a bold red X over the middle when the character is dead. Hidden entirely when the
 * survival system is off (Prefs.survivalEnabled) - it just isn't created in that case.
 */
class SurvivalBarsView(context: Context, private val characterId: String) : View(context) {

    private val barFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val barBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#73000000") }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 7f * resources.displayMetrics.density
        textAlign = Paint.Align.CENTER
    }
    private val deadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.parseColor("#F2282828")
        strokeWidth = 4f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    // Bar geometry in dp, mirroring character.js (barW 20 / barH 4 / gap 3).
    private val density = resources.displayMetrics.density
    private val dp: Float get() = density

var stats: Prefs.SurvivalStats? = null
        set(value) {
            field = value
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val stats = stats ?: return
        val barW = 20f * dp
        val barH = 4f * dp
        val gap = 3f * dp
        val totalW = barW * 3 + gap * 2
        var bx = width / 2f - totalW / 2f
        val by = 2f * dp

        // Dead: bold red X over the middle of the character reads as "muerto/eliminado" - the
        // bars themselves stay too (they're all 0/whatever persisted).
        if (stats.dead) {
            val cx = width / 2f
            val cy = height / 2f
            val r = minOf(width, height) * 0.35f
            canvas.drawLine(cx - r, cy - r, cx + r, cy + r, deadPaint)
            canvas.drawLine(cx + r, cy - r, cx - r, cy + r, deadPaint)
        }

        val bars = listOf(
            Triple(stats.hp, Color.parseColor("#e53935"), "vida"),
            Triple(stats.hunger, Color.parseColor("#fb8c00"), "hambre"),
            Triple(stats.thirst, Color.parseColor("#1e88e5"), "sed"),
        )
        for ((value, color, label) in bars) {
            canvas.drawRect(bx, by, bx + barW, by + barH, barBg)
            val fill = (value.coerceIn(0f, 100f) / 100f) * barW
            barFill.color = color
            canvas.drawRect(bx, by, bx + maxOf(barH, fill), by + barH, barFill)
            canvas.drawText(label, bx + barW / 2f, by + barH + 7f * dp, labelPaint)
            bx += barW + gap
        }
    }
}
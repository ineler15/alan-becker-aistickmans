package com.stickmanai.android.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Shared face/gender-accessory drawing for RigView - same 6-expression vocabulary and geometry
 * (relative to the head's own center/radius in canvas space) as the desktop's renderer/face.js,
 * so a character looks the same on both platforms and in CreateCharacterActivity's preview as it
 * does live.
 */
object FaceRenderer {
    val EMOTIONS = listOf("neutral", "happy", "sad", "angry", "surprised", "love")

    private val fillBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.BLACK }
    private val strokeBlack = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.BLACK
    }
    private val fillBow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Color.parseColor("#e0409a") }

    private fun drawEyes(canvas: Canvas, cx: Float, cy: Float, r: Float, style: String) {
        val dx = r * 0.35f
        val dy = -r * 0.1f
        val eyeR = if (style == "surprised") r * 0.22f else r * 0.13f
        if (style == "love") {
            for (sign in intArrayOf(-1, 1)) {
                val ex = cx + sign * dx
                val ey = cy + dy
                val s = r * 0.16f
                val path = Path()
                path.addCircle(ex - s * 0.5f, ey, s * 0.5f, Path.Direction.CW)
                path.addCircle(ex + s * 0.5f, ey, s * 0.5f, Path.Direction.CW)
                path.moveTo(ex - s, ey + s * 0.15f)
                path.lineTo(ex, ey + s * 1.2f)
                path.lineTo(ex + s, ey + s * 0.15f)
                path.close()
                canvas.drawPath(path, fillBlack)
            }
            return
        }
        for (sign in intArrayOf(-1, 1)) {
            canvas.drawCircle(cx + sign * dx, cy + dy, eyeR, fillBlack)
        }
        if (style == "angry") {
            strokeBlack.strokeWidth = (r * 0.06f).coerceAtLeast(1f)
            for (sign in intArrayOf(-1, 1)) {
                canvas.drawLine(
                    cx + sign * (dx - eyeR * 1.4f), cy + dy - eyeR * 1.6f,
                    cx + sign * (dx + eyeR * 1.4f), cy + dy - eyeR * 0.4f,
                    strokeBlack,
                )
            }
        }
    }

    private fun drawMouth(canvas: Canvas, cx: Float, cy: Float, r: Float, style: String) {
        val my = cy + r * 0.35f
        strokeBlack.strokeWidth = (r * 0.08f).coerceAtLeast(1f)
        when (style) {
            "happy", "love" -> {
                val path = Path()
                val rect = android.graphics.RectF(cx - r * 0.32f, my - r * 0.15f - r * 0.32f, cx + r * 0.32f, my - r * 0.15f + r * 0.32f)
                path.addArc(rect, 27f, 126f)
                canvas.drawPath(path, strokeBlack)
            }
            "sad" -> {
                val path = Path()
                val rect = android.graphics.RectF(cx - r * 0.32f, my + r * 0.35f - r * 0.32f, cx + r * 0.32f, my + r * 0.35f + r * 0.32f)
                path.addArc(rect, 207f, 126f)
                canvas.drawPath(path, strokeBlack)
            }
            "angry" -> {
                val path = Path().apply {
                    moveTo(cx - r * 0.28f, my + r * 0.05f)
                    lineTo(cx, my - r * 0.08f)
                    lineTo(cx + r * 0.28f, my + r * 0.05f)
                }
                canvas.drawPath(path, strokeBlack)
            }
            "surprised" -> canvas.drawCircle(cx, my, r * 0.14f, fillBlack)
            else -> canvas.drawLine(cx - r * 0.22f, my, cx + r * 0.22f, my, strokeBlack)
        }
    }

    fun drawFace(canvas: Canvas, cx: Float, cy: Float, r: Float, emotion: String?) {
        val style = if (EMOTIONS.contains(emotion)) emotion!! else "neutral"
        drawEyes(canvas, cx, cy, r, style)
        drawMouth(canvas, cx, cy, r, style)
    }

    /** A small bow sitting on top of the head - the only gender-driven visual difference (see Prefs.kt). */
    fun drawGenderAccessory(canvas: Canvas, cx: Float, cy: Float, r: Float, gender: String?) {
        if (gender != "femenino") return
        val by = cy - r * 0.95f
        val wing = r * 0.3f
        for (sign in intArrayOf(-1, 1)) {
            val path = Path().apply {
                moveTo(cx, by)
                lineTo(cx + sign * wing, by - wing * 0.6f)
                lineTo(cx + sign * wing, by + wing * 0.6f)
                close()
            }
            canvas.drawPath(path, fillBow)
        }
        canvas.drawCircle(cx, by, wing * 0.28f, fillBow)
    }
}

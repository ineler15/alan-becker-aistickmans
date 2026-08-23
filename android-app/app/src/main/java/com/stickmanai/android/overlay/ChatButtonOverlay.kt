package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A small always-on-top "chat" button fixed at the top-right corner, plus the floating text-entry
 * panel it opens. Replaces the old per-character tap-to-chat flow (which launched a full
 * ChatActivity and switched away from whatever app the user was using) - this stays an overlay the
 * whole time, like the character/speech overlays, so answering never leaves the app in front.
 * Sends to everyone (same recipient as MainActivity's existing "hablarle a todos" group chat),
 * since there's one shared button instead of one per character.
 */
class ChatButtonOverlay(private val context: Context, private val windowManager: WindowManager) {

    private val density = context.resources.displayMetrics.density

    private val button = TextView(context).apply {
        text = "💬"
        textSize = 20f
        setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
        setBackgroundColor(Color.parseColor("#CC6650B8"))
        setTextColor(Color.WHITE)
    }

    private val editText = EditText(context).apply {
        hint = "Escribile algo a todos..."
        setBackgroundColor(Color.WHITE)
        imeOptions = EditorInfo.IME_ACTION_SEND
        setSingleLine()
    }

    private val sendButton = Button(context).apply {
        text = "Enviar"
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        setBackgroundColor(Color.parseColor("#EEFFFFFF"))
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(sendButton)
        visibility = View.GONE
    }

    private val buttonParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (8 * density).toInt()
        y = (24 * density).toInt()
    }

    private val panelParams = WindowManager.LayoutParams(
        (260 * density).toInt(), WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.END
        x = (8 * density).toInt()
        y = (70 * density).toInt()
    }

    fun attach() {
        windowManager.addView(button, buttonParams)
        windowManager.addView(panel, panelParams)
        button.setOnClickListener { togglePanel() }
        sendButton.setOnClickListener { send() }
        editText.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
    }

    fun detach() {
        try { windowManager.removeView(button) } catch (e: Exception) { /* already gone */ }
        try { windowManager.removeView(panel) } catch (e: Exception) { /* already gone */ }
    }

    private fun togglePanel() {
        if (panel.visibility == View.VISIBLE) hidePanel() else showPanel()
    }

    private fun showPanel() {
        panel.visibility = View.VISIBLE
        // Overlay windows are FLAG_NOT_FOCUSABLE by default so they never steal keyboard focus
        // from whatever app is in front - clearing it only while the panel is open is what lets
        // this EditText actually receive input, the same trick floating chat-head apps use.
        panelParams.flags = panelParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        windowManager.updateViewLayout(panel, panelParams)
        editText.requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(editText, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun hidePanel() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(editText.windowToken, 0)
        panel.visibility = View.GONE
        panelParams.flags = panelParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        windowManager.updateViewLayout(panel, panelParams)
    }

    private fun send() {
        val text = editText.text.toString().trim()
        if (text.isNotEmpty()) {
            PendingMessages.setAll(context, text)
            editText.setText("")
        }
        hidePanel()
    }
}

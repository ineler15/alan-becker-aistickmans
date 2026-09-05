package com.stickmanai.android.overlay

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.stickmanai.android.allCharacters

/**
 * A small always-on-top "chat" button fixed at the top-right corner, plus the floating text-entry
 * panel it opens. Replaces the old per-character tap-to-chat flow (which launched a full
 * ChatActivity and switched away from whatever app the user was using) - this stays an overlay the
 * whole time, like the character/speech overlays, so answering never leaves the app in front.
 * The panel has a recipient picker: "(a todos)" group chat, or ONE specific character (private
 * chat via PendingMessages.set) - the per-character path the old group-only overlay lacked.
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

    // Recipient picker: null (position 0) = group chat to everyone, otherwise one character id.
    private val recipientIds = mutableListOf<String?>(null)
    private val recipientSpinner = Spinner(context).apply {
        val labels = mutableListOf("(a todos)")
        for (character in allCharacters(context)) {
            recipientIds.add(character.id)
            labels.add(character.displayName)
        }
        adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, labels)
    }

    private val editText = EditText(context).apply {
        hint = "Escribile algo..."
        setBackgroundColor(Color.WHITE)
        imeOptions = EditorInfo.IME_ACTION_SEND
        setSingleLine()
    }

    private val sendButton = Button(context).apply {
        text = "Enviar"
    }

    // Just a friendly nudge through the normal chat pipeline - the character reacts in its own
    // voice/personality via say + set_emotion (already wired up), not a hardcoded canned response.
    // Sent to whichever recipient is selected (group or one character).
    private val giveSnackButton = Button(context).apply {
        text = "🍪"
    }

    private val contentRow = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(editText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        addView(sendButton)
        addView(giveSnackButton)
    }

    private val panel = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundColor(Color.parseColor("#EEFFFFFF"))
        val pad = (8 * density).toInt()
        setPadding(pad, pad, pad, pad)
        addView(
            recipientSpinner,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
        )
        addView(contentRow)
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
        giveSnackButton.setOnClickListener {
            sendToRecipient("🍪 Te acaban de regalar un alfajor. ¡Disfrutalo!")
        }
        recipientSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val recipientId = recipientIds.getOrNull(position)
                editText.hint = if (recipientId == null) {
                    "Escribile algo a todos..."
                } else {
                    val name = allCharacters(context).find { it.id == recipientId }?.displayName ?: recipientId
                    "Escribile algo a $name (privado)..."
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
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

    private fun selectedRecipient(): String? = recipientIds.getOrNull(recipientSpinner.selectedItemPosition)

    private fun sendToRecipient(text: String) {
        val recipient = selectedRecipient()
        if (recipient == null) PendingMessages.setAll(context, text) else PendingMessages.set(recipient, text)
    }

    private fun send() {
        val text = editText.text.toString().trim()
        if (text.isNotEmpty()) {
            sendToRecipient(text)
            editText.setText("")
        }
        hidePanel()
    }
}
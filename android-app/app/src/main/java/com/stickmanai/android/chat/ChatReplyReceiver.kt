package com.stickmanai.android.chat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import com.stickmanai.android.overlay.PendingMessages

/** Handles the reply typed straight from a character's notification (see ChatNotifications). */
class ChatReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val characterId = intent.getStringExtra(ChatNotifications.EXTRA_CHARACTER_ID) ?: return
        val displayName = intent.getStringExtra(ChatNotifications.EXTRA_DISPLAY_NAME) ?: characterId
        val text = RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(ChatNotifications.KEY_REPLY)
            ?.toString()
            ?.trim()
        if (!text.isNullOrEmpty()) {
            PendingMessages.set(characterId, text)
            ChatNotifications.showSent(context, characterId, displayName, text)
        }
    }
}

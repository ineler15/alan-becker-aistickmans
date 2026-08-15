package com.stickmanai.android.chat

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput

/**
 * Lets the user reply to a character straight from the notification shade (RemoteInput action)
 * instead of having to switch into ChatActivity - each character gets its own notification that
 * updates with whatever it last said, same idea as a messaging app's per-conversation notification.
 */
object ChatNotifications {
    const val CHANNEL_ID = "character_chat"
    const val KEY_REPLY = "key_reply_text"
    const val EXTRA_CHARACTER_ID = "extra_character_id"
    const val EXTRA_DISPLAY_NAME = "extra_display_name"

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Chat con tus stickmans", NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
        }
    }

    private fun notificationId(characterId: String) = characterId.hashCode()

    /** Shows/updates the notification with what the character just said, with a reply action. */
    fun showSay(context: Context, characterId: String, displayName: String, text: String) {
        ensureChannel(context)

        val remoteInput = RemoteInput.Builder(KEY_REPLY).setLabel("Responderle a $displayName").build()
        val replyIntent = Intent(context, ChatReplyReceiver::class.java).apply {
            putExtra(EXTRA_CHARACTER_ID, characterId)
            putExtra(EXTRA_DISPLAY_NAME, displayName)
        }
        val replyPendingIntent = PendingIntent.getBroadcast(
            context,
            notificationId(characterId),
            replyIntent,
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val replyAction = NotificationCompat.Action.Builder(
            android.R.drawable.ic_menu_send, "Responder", replyPendingIntent
        ).addRemoteInput(remoteInput).build()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(displayName)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .addAction(replyAction)
            .setOnlyAlertOnce(true)
            .build()

        NotificationManagerCompat.from(context).notify(notificationId(characterId), notification)
    }

    /** Updates the notification to show the reply that was just sent, so the user gets feedback. */
    fun showSent(context: Context, characterId: String, displayName: String, sentText: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setContentTitle(displayName)
            .setContentText("Vos: $sentText")
            .build()
        NotificationManagerCompat.from(context).notify(notificationId(characterId), notification)
    }
}

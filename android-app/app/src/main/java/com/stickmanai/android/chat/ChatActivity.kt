package com.stickmanai.android.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stickmanai.android.CHARACTERS
import com.stickmanai.android.databinding.ActivityChatBinding
import com.stickmanai.android.overlay.PendingMessages

/**
 * Opened by tapping a character overlay - sends one message to that character's next AI turn.
 * Also doubles as the group chat when opened with GROUP_CHAT_ID (see MainActivity's group chat
 * button): the same message reaches every character's own context instead of just one.
 */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHARACTER_ID = "characterId"
        const val GROUP_CHAT_ID = "__all__"
    }

    private lateinit var binding: ActivityChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val characterId = intent.getStringExtra(EXTRA_CHARACTER_ID) ?: return finish()
        val isGroup = characterId == GROUP_CHAT_ID
        binding.chatTitle.text = if (isGroup) {
            "Hablarle a todos"
        } else {
            "Hablar con ${CHARACTERS.find { it.id == characterId }?.displayName ?: characterId}"
        }

        binding.btnSend.setOnClickListener {
            val text = binding.editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                if (isGroup) PendingMessages.setAll(text) else PendingMessages.set(characterId, text)
                finish()
            }
        }
    }
}

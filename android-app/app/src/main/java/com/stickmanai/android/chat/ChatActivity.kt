package com.stickmanai.android.chat

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.stickmanai.android.CHARACTERS
import com.stickmanai.android.databinding.ActivityChatBinding
import com.stickmanai.android.overlay.PendingMessages

/** Opened by tapping a character overlay - sends one message to that character's next AI turn. */
class ChatActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_CHARACTER_ID = "characterId"
    }

    private lateinit var binding: ActivityChatBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityChatBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val characterId = intent.getStringExtra(EXTRA_CHARACTER_ID) ?: return finish()
        val displayName = CHARACTERS.find { it.id == characterId }?.displayName ?: characterId
        binding.chatTitle.text = "Hablar con $displayName"

        binding.btnSend.setOnClickListener {
            val text = binding.editMessage.text.toString().trim()
            if (text.isNotEmpty()) {
                PendingMessages.set(characterId, text)
                finish()
            }
        }
    }
}

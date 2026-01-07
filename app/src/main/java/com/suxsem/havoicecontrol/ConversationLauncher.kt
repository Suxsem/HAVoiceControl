package com.suxsem.havoicecontrol

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

class ConversationLauncher : ComponentActivity() {

    private val conversation by lazy { Conversation(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        finish()
        lifecycleScope.launch {
            conversation.chat()
        }
    }
}
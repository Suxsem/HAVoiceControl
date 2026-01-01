package com.suxsem.havoicesatellite

import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
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
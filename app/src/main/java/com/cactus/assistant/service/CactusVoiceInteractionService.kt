package com.cactus.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.util.Log
import com.cactus.assistant.CactusAssistantApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Top-level Android Digital Assistant service (`android.service.voice.VoiceInteractionService`).
 *
 * Pre-loads Cactus Compute's Whistle (`whistle.cact`) and Needle 3 (`needle3.cact`) models
 * as soon as the system binds and readies the default assistant.
 */
class CactusVoiceInteractionService : VoiceInteractionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onReady() {
        super.onReady()
        Log.i(TAG, "CactusVoiceInteractionService is ready as system Digital Assistant")
        serviceScope.launch {
            val status = CactusAssistantApp.getEngine(applicationContext).initializeIfNeeded()
            Log.i(TAG, "Engine status onReady: ${status.statusMessage}")
        }
    }

    fun triggerSession(args: Bundle? = null) {
        showSession(
            args,
            VoiceInteractionSession.SHOW_WITH_ASSIST or VoiceInteractionSession.SHOW_WITH_SCREENSHOT
        )
    }

    override fun onShutdown() {
        serviceScope.cancel()
        super.onShutdown()
    }

    companion object {
        private const val TAG = "CactusVIService"
    }
}

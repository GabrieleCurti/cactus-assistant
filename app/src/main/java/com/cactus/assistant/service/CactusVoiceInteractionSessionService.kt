package com.cactus.assistant.service

import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService

/**
 * Factory service that spawns [CactusVoiceInteractionSession] instances when the user
 * invokes the system Digital Assistant (long-press power/home, corner swipe, or assist gesture).
 */
class CactusVoiceInteractionSessionService : VoiceInteractionSessionService() {

    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return CactusVoiceInteractionSession(this)
    }
}

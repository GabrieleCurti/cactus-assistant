package com.cactus.assistant.service

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.cactus.assistant.CactusAssistantApp
import com.cactus.assistant.audio.WhistleAudioRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Android [RecognitionService] backed by Cactus Compute's Whistle (`whistle.cact`)
 * 16 kHz on-device speech-to-text model.
 *
 * Required by Android's `<voice-interaction-service>` contract so the app is eligible
 * to be selected as the system Default Digital Assistant.
 */
class CactusRecognitionService : RecognitionService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val recorder = WhistleAudioRecorder()
    private var recognitionJob: Job? = null

    override fun onStartListening(intent: Intent?, listener: Callback?) {
        if (listener == null) return
        recognitionJob?.cancel()

        val language = intent?.getStringExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE)
            ?.substringBefore('-')
            ?.ifBlank { "en" }
            ?: "en"

        recognitionJob = serviceScope.launch {
            try {
                listener.readyForSpeech(Bundle())
                listener.beginningOfSpeech()

                val pcm = recorder.recordUtterance(
                    maxDurationMs = 15_000L,
                    trailingSilenceMs = 1_100L,
                    onAmplitude = { rms ->
                        try {
                            listener.rmsChanged(rms * 10f)
                        } catch (_: Exception) {
                        }
                    }
                )

                listener.endOfSpeech()

                if (pcm.isEmpty()) {
                    listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                    return@launch
                }

                val engine = CactusAssistantApp.getEngine(applicationContext)
                val transcript = engine.transcribeOnly(pcm, language = language)

                if (transcript.text.isBlank()) {
                    listener.error(SpeechRecognizer.ERROR_NO_MATCH)
                } else {
                    val resultsBundle = Bundle().apply {
                        putStringArrayList(
                            SpeechRecognizer.RESULTS_RECOGNITION,
                            arrayListOf(transcript.text)
                        )
                        putFloatArray(
                            SpeechRecognizer.CONFIDENCE_SCORES,
                            floatArrayOf(0.95f)
                        )
                    }
                    listener.results(resultsBundle)
                }
            } catch (_: Exception) {
                try {
                    listener.error(SpeechRecognizer.ERROR_CLIENT)
                } catch (_: Exception) {
                }
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        recorder.stop()
    }

    override fun onCancel(listener: Callback?) {
        recorder.stop()
        recognitionJob?.cancel()
    }

    override fun onDestroy() {
        recorder.stop()
        serviceScope.cancel()
        super.onDestroy()
    }
}

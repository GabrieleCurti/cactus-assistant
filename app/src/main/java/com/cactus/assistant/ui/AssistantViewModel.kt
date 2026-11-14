package com.cactus.assistant.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cactus.assistant.CactusAssistantApp
import com.cactus.assistant.audio.WhistleAudioRecorder
import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.engine.EngineStatus
import com.cactus.assistant.routing.ActionExecutionResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

data class AssistantUiState(
    val engineStatus: EngineStatus = EngineStatus(),
    val isListening: Boolean = false,
    val isProcessing: Boolean = false,
    val liveAmplitude: Float = 0f,
    val queryInput: String = "",
    val statusBanner: String = "Ready — tap the mic or enter a command",
    val lastResult: ActionExecutionResult? = null,
    val history: List<ActionExecutionResult> = emptyList()
)

class AssistantViewModel(application: Application) : AndroidViewModel(application) {

    private val engine = CactusAssistantApp.getEngine(application)
    private val router = CactusAssistantApp.getRouter(application)
    private val audioRecorder = WhistleAudioRecorder()

    private val _uiState = MutableStateFlow(AssistantUiState())
    val uiState: StateFlow<AssistantUiState> = _uiState.asStateFlow()

    private var listeningJob: Job? = null

    init {
        refreshEngineStatus()
    }

    fun refreshEngineStatus() {
        viewModelScope.launch {
            val status = engine.initializeIfNeeded()
            _uiState.update {
                it.copy(
                    engineStatus = status,
                    statusBanner = status.statusMessage
                )
            }
        }
    }

    fun onMicPermissionDenied() {
        _uiState.update {
            it.copy(
                isListening = false,
                isProcessing = false,
                statusBanner = "Microphone permission is required for voice commands — grant it in App Settings or type below"
            )
        }
    }

    fun updateQueryInput(text: String) {
        _uiState.update { it.copy(queryInput = text) }
    }

    fun submitTextQuery(
        query: String = _uiState.value.queryInput,
        onComplete: ((ActionExecutionResult) -> Unit)? = null
    ) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) return

        audioRecorder.stop()
        listeningJob?.cancel()

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isListening = false,
                    isProcessing = true,
                    statusBanner = "Needle 3 evaluating tool schemas…"
                )
            }

            val decision = engine.processText(trimmed)
            val result = router.execute(decision)

            _uiState.update { state ->
                state.copy(
                    isProcessing = false,
                    queryInput = "",
                    statusBanner = result.summary,
                    lastResult = result,
                    history = listOf(result) + state.history
                )
            }
            onComplete?.invoke(result)
        }
    }

    fun startVoiceCapture(onComplete: ((ActionExecutionResult) -> Unit)? = null) {
        listeningJob?.cancel()
        listeningJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isListening = true,
                    isProcessing = false,
                    liveAmplitude = 0.01f,
                    statusBanner = "Listening with Whistle (16 kHz mono)…"
                )
            }

            val capture = audioRecorder.recordUtteranceDetailed(
                maxDurationMs = 15_000L,
                trailingSilenceMs = 1_100L,
                onAmplitude = { rms ->
                    _uiState.update { state ->
                        state.copy(liveAmplitude = rms.coerceIn(0f, 1f))
                    }
                }
            )

            if (capture.errorMessage != null) {
                val errDecision = AssistantDecision.Error(capture.errorMessage)
                val errResult = router.execute(errDecision)
                _uiState.update { state ->
                    state.copy(
                        isListening = false,
                        isProcessing = false,
                        liveAmplitude = 0f,
                        statusBanner = capture.errorMessage,
                        lastResult = errResult,
                        history = listOf(errResult) + state.history
                    )
                }
                onComplete?.invoke(errResult)
                return@launch
            }

            if (!capture.isUsable) {
                val reason = String.format(
                    Locale.US,
                    "No audio signal captured (samples=%d, peak=%.5f) — check mic or speak closer",
                    capture.samples.size,
                    capture.peakAmplitude
                )
                val emptyDecision = AssistantDecision.EmptyAudio(reason)
                val emptyResult = router.execute(emptyDecision)
                _uiState.update {
                    it.copy(
                        isListening = false,
                        isProcessing = false,
                        liveAmplitude = 0f,
                        statusBanner = reason
                    )
                }
                onComplete?.invoke(emptyResult)
                return@launch
            }

            _uiState.update {
                it.copy(
                    isListening = false,
                    isProcessing = true,
                    liveAmplitude = 0f,
                    statusBanner = String.format(
                        Locale.US,
                        "Transcribing %.1fs audio (peak=%.3f) with Whistle + Needle 3…",
                        capture.samples.size / 16000.0f,
                        capture.peakAmplitude
                    )
                )
            }

            val decision = engine.processAudio(capture.samples)
            if (decision is AssistantDecision.EmptyAudio) {
                val emptyResult = router.execute(decision)
                val msg = String.format(
                    Locale.US,
                    "%s (captured %.1fs, peak=%.3f, rms=%.4f)",
                    decision.reason,
                    capture.samples.size / 16000.0f,
                    capture.peakAmplitude,
                    capture.rmsAmplitude
                )
                _uiState.update {
                    it.copy(
                        isProcessing = false,
                        statusBanner = msg
                    )
                }
                onComplete?.invoke(emptyResult)
                return@launch
            }

            val result = router.execute(decision)
            _uiState.update { state ->
                state.copy(
                    isProcessing = false,
                    statusBanner = result.summary,
                    lastResult = result,
                    history = listOf(result) + state.history
                )
            }
            onComplete?.invoke(result)
        }
    }

    fun stopVoiceCapture() {
        audioRecorder.stop()
    }

    override fun onCleared() {
        audioRecorder.stop()
        super.onCleared()
    }
}

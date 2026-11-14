package com.cactus.assistant.ui

import android.Manifest
import android.app.SearchManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.cactus.assistant.engine.AssistantDecision
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Translucent bottom-sheet activity invoked by `Intent.ACTION_ASSIST`,
 * `VOICE_SEARCH_HANDS_FREE`, and `WEB_SEARCH`.
 */
class AssistActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AssistantViewModel(application) as T
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceFlow()
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            val state by viewModel.uiState.collectAsState()
            AssistOverlaySheet(
                state = state,
                onMicToggle = {
                    if (state.isListening) {
                        viewModel.stopVoiceCapture()
                    } else {
                        ensureAudioPermissionAndListen()
                    }
                },
                onQueryChange = viewModel::updateQueryInput,
                onSubmitQuery = { query ->
                    viewModel.submitTextQuery(query) { result ->
                        if (result.decision !is AssistantDecision.EmptyAudio &&
                            result.decision !is AssistantDecision.Error
                        ) {
                            lifecycleScope.launch {
                                delay(750L)
                                finish()
                            }
                        }
                    }
                },
                onDismiss = { finish() }
            )
        }

        handleIncomingAssistIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingAssistIntent(intent)
    }

    private fun handleIncomingAssistIntent(incoming: Intent?) {
        val prefilledQuery = incoming?.getStringExtra(Intent.EXTRA_TEXT)
            ?: incoming?.getStringExtra(SearchManager.QUERY)
            ?: incoming?.getStringExtra("query")

        if (!prefilledQuery.isNullOrBlank()) {
            viewModel.submitTextQuery(prefilledQuery) { result ->
                if (result.decision !is AssistantDecision.EmptyAudio &&
                    result.decision !is AssistantDecision.Error
                ) {
                    lifecycleScope.launch {
                        delay(600L)
                        finish()
                    }
                }
            }
        } else {
            ensureAudioPermissionAndListen()
        }
    }

    private fun ensureAudioPermissionAndListen() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            startVoiceFlow()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startVoiceFlow() {
        viewModel.startVoiceCapture { result ->
            if (result.decision !is AssistantDecision.EmptyAudio &&
                result.decision !is AssistantDecision.Error
            ) {
                lifecycleScope.launch {
                    delay(850L)
                    finish()
                }
            }
        }
    }
}

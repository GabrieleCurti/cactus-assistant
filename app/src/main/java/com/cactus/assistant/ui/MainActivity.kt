package com.cactus.assistant.ui

import android.Manifest
import android.app.role.RoleManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * Main application dashboard and assistant configuration screen.
 * Allows users to:
 * - Register Cactus Assistant as Android's default Digital Assistant (`RoleManager.ROLE_ASSISTANT`
 *   or `Settings.ACTION_VOICE_INPUT_SETTINGS`).
 * - Inspect on-device Whistle (`whistle.cact`) and Needle 3 (`needle3.cact`) status.
 * - Test voice and text queries and inspect the resulting tool calls and Android intents.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: AssistantViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AssistantViewModel(application) as T
            }
        }
    }
    private var isDefaultAssistant by mutableStateOf(false)

    private val roleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        checkDefaultAssistantStatus()
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startVoiceCapture()
        } else {
            viewModel.onMicPermissionDenied()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        checkDefaultAssistantStatus()

        setContent {
            val state by viewModel.uiState.collectAsState()
            AssistantDashboardScreen(
                state = state,
                isDefaultAssistant = isDefaultAssistant,
                onRequestDefaultAssistant = ::requestDefaultAssistantRole,
                onMicClick = {
                    if (state.isListening) {
                        viewModel.stopVoiceCapture()
                    } else {
                        startVoiceWithPermission()
                    }
                },
                onQueryChange = viewModel::updateQueryInput,
                onSubmitQuery = { query -> viewModel.submitTextQuery(query) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        checkDefaultAssistantStatus()
        viewModel.refreshEngineStatus()
    }

    private fun checkDefaultAssistantStatus() {
        isDefaultAssistant = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            roleManager?.isRoleHeld(RoleManager.ROLE_ASSISTANT) == true
        } else {
            val currentAssistant = Settings.Secure.getString(
                contentResolver,
                "assistant"
            )
            currentAssistant?.startsWith(packageName) == true
        }
    }

    private fun requestDefaultAssistantRole() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_ASSISTANT)) {
                if (!roleManager.isRoleHeld(RoleManager.ROLE_ASSISTANT)) {
                    val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_ASSISTANT)
                    roleLauncher.launch(intent)
                    return
                }
            }
        }

        // Open Default Digital Assistant / Voice Input system settings
        val voiceSettingsIntent = Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)
        try {
            roleLauncher.launch(voiceSettingsIntent)
        } catch (_: Exception) {
            roleLauncher.launch(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS))
        }
    }

    private fun startVoiceWithPermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (granted) {
            viewModel.startVoiceCapture()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}

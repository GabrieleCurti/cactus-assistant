package com.cactus.assistant.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AccessAlarm
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.SettingsVoice
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.routing.ActionExecutionResult
import java.util.Locale

private val CactusEmerald = Color(0xFF10B981)
private val CactusSlateDark = Color(0xFF0F172A)
private val CactusCardBg = Color(0xFF1E293B)
private val CactusBorder = Color(0xFF334155)
private val CactusMuted = Color(0xFF94A3B8)

private val CactusColorScheme = darkColorScheme(
    primary = CactusEmerald,
    onPrimary = CactusSlateDark,
    background = CactusSlateDark,
    surface = CactusCardBg,
    onSurface = Color.White
)

private val SAMPLE_PROMPTS = listOf(
    "Set a timer for 10 minutes for pasta",
    "Wake me up at 7:30 am",
    "Add a todo to buy coffee beans",
    "What is the distance to Mars?"
)

@Composable
fun AssistantDashboardScreen(
    state: AssistantUiState,
    isDefaultAssistant: Boolean,
    onRequestDefaultAssistant: () -> Unit,
    onMicClick: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String) -> Unit
) {
    MaterialTheme(colorScheme = CactusColorScheme) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = CactusSlateDark
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(CactusEmerald.copy(alpha = 0.16f))
                            .border(1.dp, CactusEmerald, RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsVoice,
                            contentDescription = null,
                            tint = CactusEmerald
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Cactus Assistant",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Text(
                            text = "Whistle (16.9 MB ASR) + Needle 3 (Tool Calling)",
                            fontSize = 12.sp,
                            color = CactusMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Default Assistant Registration Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = CactusCardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(
                            1.dp,
                            if (isDefaultAssistant) CactusEmerald else CactusBorder,
                            RoundedCornerShape(16.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = null,
                            tint = if (isDefaultAssistant) CactusEmerald else CactusMuted,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (isDefaultAssistant) {
                                    "Active Default Digital Assistant"
                                } else {
                                    "Set as Android Digital Assistant"
                                },
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                            Text(
                                text = "VoiceInteractionService + ACTION_ASSIST",
                                fontSize = 12.sp,
                                color = CactusMuted
                            )
                        }
                        Button(
                            onClick = onRequestDefaultAssistant,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = CactusEmerald,
                                contentColor = CactusSlateDark
                            ),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                text = if (isDefaultAssistant) "Settings" else "Enable",
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // On-Device Engine Status Card
                Card(
                    colors = CardDefaults.cardColors(containerColor = CactusCardBg),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, CactusBorder, RoundedCornerShape(16.dp))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Memory,
                                contentDescription = null,
                                tint = CactusEmerald,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "On-Device Runtime (android-arm64 JNI)",
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 13.sp,
                                color = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = state.engineStatus.statusMessage,
                            fontSize = 12.sp,
                            color = CactusMuted,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ModelStatusBadge(
                                label = "whistle.cact",
                                ready = state.engineStatus.whistleWeightsPresent
                            )
                            ModelStatusBadge(
                                label = "needle3.cact",
                                ready = state.engineStatus.needle3WeightsPresent
                            )
                            ModelStatusBadge(
                                label = "JNI Bridge",
                                ready = state.engineStatus.isNativeLoaded
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Quick-test sample prompts
                Text(
                    text = "Try a command (or tap the mic):",
                    fontSize = 12.sp,
                    color = CactusMuted,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    for (prompt in SAMPLE_PROMPTS) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(CactusCardBg)
                                .border(1.dp, CactusBorder, RoundedCornerShape(20.dp))
                                .clickable { onSubmitQuery(prompt) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = prompt,
                                fontSize = 12.sp,
                                color = Color(0xFFE2E8F0)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Live status & history list
                Text(
                    text = state.statusBanner,
                    fontSize = 13.sp,
                    color = CactusEmerald,
                    fontWeight = FontWeight.Medium
                )

                if (state.isListening || state.isProcessing) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val animatedAmp by animateFloatAsState(
                        targetValue = if (state.isListening) {
                            (state.liveAmplitude * 25f).coerceIn(0.08f, 1f)
                        } else {
                            1f
                        },
                        label = "amplitude"
                    )
                    if (state.isProcessing) {
                        LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth(),
                            color = CactusEmerald
                        )
                    } else {
                        LinearProgressIndicator(
                            progress = { animatedAmp },
                            modifier = Modifier.fillMaxWidth(),
                            color = CactusEmerald
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(state.history) { item ->
                        ExecutionResultCard(item)
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Bottom Voice + Text Input Bar
                CommandInputBar(
                    query = state.queryInput,
                    isListening = state.isListening,
                    onQueryChange = onQueryChange,
                    onMicClick = onMicClick,
                    onSubmit = { onSubmitQuery(state.queryInput) }
                )
            }
        }
    }
}

@Composable
fun AssistOverlaySheet(
    state: AssistantUiState,
    onMicToggle: () -> Unit,
    onQueryChange: (String) -> Unit,
    onSubmitQuery: (String) -> Unit,
    onDismiss: () -> Unit
) {
    MaterialTheme(colorScheme = CactusColorScheme) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                colors = CardDefaults.cardColors(containerColor = CactusSlateDark),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(
                        width = 1.dp,
                        color = CactusEmerald,
                        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
                    )
                    .clickable(enabled = false) {}
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(
                            imageVector = Icons.Default.SettingsVoice,
                            contentDescription = null,
                            tint = CactusEmerald
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Cactus Assistant",
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                        IconButton(onClick = onDismiss) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close",
                                tint = CactusMuted
                            )
                        }
                    }

                    Text(
                        text = state.statusBanner,
                        fontSize = 14.sp,
                        color = CactusEmerald,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )

                    if (state.isListening || state.isProcessing) {
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            color = CactusEmerald
                        )
                    }

                    state.lastResult?.let { res ->
                        Spacer(modifier = Modifier.height(6.dp))
                        ExecutionResultCard(res)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    CommandInputBar(
                        query = state.queryInput,
                        isListening = state.isListening,
                        onQueryChange = onQueryChange,
                        onMicClick = onMicToggle,
                        onSubmit = { onSubmitQuery(state.queryInput) }
                    )
                }
            }
        }
    }
}

@Composable
private fun CommandInputBar(
    query: String,
    isListening: Boolean,
    onQueryChange: (String) -> Unit,
    onMicClick: () -> Unit,
    onSubmit: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            placeholder = {
                Text(
                    text = "Timer, alarm, todo, or ask Gemini…",
                    fontSize = 14.sp,
                    color = CactusMuted
                )
            },
            singleLine = true,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CactusEmerald,
                unfocusedBorderColor = CactusBorder,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSubmit() }),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.weight(1f)
        )

        Spacer(modifier = Modifier.width(8.dp))

        if (query.isNotBlank()) {
            IconButton(
                onClick = onSubmit,
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(CactusEmerald)
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Send",
                    tint = CactusSlateDark
                )
            }
            Spacer(modifier = Modifier.width(6.dp))
        }

        IconButton(
            onClick = onMicClick,
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (isListening) Color(0xFFEF4444) else CactusEmerald)
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Stop else Icons.Default.Mic,
                contentDescription = if (isListening) "Stop listening" else "Speak with Whistle",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun ModelStatusBadge(label: String, ready: Boolean) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                if (ready) CactusEmerald.copy(alpha = 0.15f)
                else Color(0xFF334155).copy(alpha = 0.5f)
            )
            .border(
                1.dp,
                if (ready) CactusEmerald else CactusBorder,
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = if (ready) "$label ✓" else label,
            fontSize = 11.sp,
            color = if (ready) CactusEmerald else CactusMuted,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ExecutionResultCard(result: ActionExecutionResult) {
    val decision = result.decision
    val (icon, badgeLabel, accentColor) = when (decision) {
        is AssistantDecision.SetTimer ->
            Triple(Icons.Default.Timer, "Needle 3 → set_timer (Silent AlarmClock)", CactusEmerald)
        is AssistantDecision.SetAlarm ->
            Triple(Icons.Default.AccessAlarm, "Needle 3 → set_alarm (Silent AlarmClock)", CactusEmerald)
        is AssistantDecision.CreateTodo ->
            Triple(Icons.Default.TaskAlt, "Needle 3 → create_todo (Google Tasks)", Color(0xFF38BDF8))
        is AssistantDecision.FallbackToGemini ->
            Triple(Icons.Default.AutoAwesome, "Refusal [] → Google Gemini (com.google.android.apps.bard)", Color(0xFFA855F7))
        is AssistantDecision.EmptyAudio ->
            Triple(Icons.Default.Mic, "Whistle Silence Gate", CactusMuted)
        is AssistantDecision.Error ->
            Triple(Icons.Default.Close, "Cactus Engine Error", Color(0xFFEF4444))
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = CactusCardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, accentColor.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = badgeLabel,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = accentColor
                )
            }

            if (decision.transcript.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "\"${decision.transcript}\"",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = result.summary,
                fontSize = 12.sp,
                color = CactusMuted
            )

            val metaLine = when (decision) {
                is AssistantDecision.SetTimer ->
                    String.format(
                        Locale.US,
                        "confidence=%.2f | EXTRA_LENGTH=%ds | EXTRA_SKIP_UI=true",
                        decision.confidence,
                        decision.durationSeconds
                    )
                is AssistantDecision.SetAlarm ->
                    String.format(
                        Locale.US,
                        "confidence=%.2f | EXTRA_HOUR=%d EXTRA_MINUTES=%d | EXTRA_SKIP_UI=true",
                        decision.confidence,
                        decision.hour24,
                        decision.minute
                    )
                is AssistantDecision.CreateTodo ->
                    String.format(
                        Locale.US,
                        "confidence=%.2f | pkg=com.google.android.apps.tasks | title=\"%s\"",
                        decision.confidence,
                        decision.title
                    )
                is AssistantDecision.FallbackToGemini ->
                    "pkg=com.google.android.apps.bard | ${decision.reason}"
                is AssistantDecision.EmptyAudio ->
                    decision.reason
                is AssistantDecision.Error ->
                    decision.message
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = metaLine,
                fontSize = 11.sp,
                color = CactusMuted,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

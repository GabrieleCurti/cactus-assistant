package com.cactus.assistant.service

import android.Manifest
import android.app.SearchManager
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.service.voice.VoiceInteractionSession
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.cactus.assistant.CactusAssistantApp
import com.cactus.assistant.audio.WhistleAudioRecorder
import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Active Digital Assistant session overlay (`VoiceInteractionSession`).
 *
 * When shown:
 * 1. Verifies `RECORD_AUDIO` permission (or offers one-tap permission setup) and captures
 *    16 kHz mono audio via [WhistleAudioRecorder].
 * 2. Runs Whistle + Needle 3 via [com.cactus.assistant.engine.CactusAssistantEngine].
 * 3. Executes the resulting tool call (`set_timer`, `set_alarm`, `create_todo`) or forwards
 *    unmatched queries to Google Gemini (`com.google.android.apps.bard`).
 */
class CactusVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    private val sessionScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val audioRecorder = WhistleAudioRecorder()
    private val engine by lazy { CactusAssistantApp.getEngine(context) }
    private val router by lazy { CactusAssistantApp.getRouter(context) }

    private var activeJob: Job? = null
    private var needsMicPermission: Boolean = false

    private lateinit var titleText: TextView
    private lateinit var statusText: TextView
    private lateinit var transcriptText: TextView
    private lateinit var queryInput: EditText
    private lateinit var actionButton: Button
    private lateinit var progressBar: ProgressBar

    override fun onCreateContentView(): View {
        val root = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#66000000"))
            setOnClickListener { hide() }
        }

        val card = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            val paddingPx = dp(20)
            setPadding(paddingPx, paddingPx, paddingPx, paddingPx)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(Color.parseColor("#0F172A"))
                setStroke(dp(1), Color.parseColor("#10B981"))
            }
            isClickable = true
            isFocusable = true
        }

        val cardParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            val margin = dp(16)
            setMargins(margin, margin, margin, margin)
        }

        titleText = TextView(context).apply {
            text = "Cactus Assistant (Whistle + Needle 3)"
            setTextColor(Color.parseColor("#10B981"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            setTypeface(typeface, Typeface.BOLD)
        }

        statusText = TextView(context).apply {
            text = "Listening with Whistle (16 kHz)…"
            setTextColor(Color.parseColor("#E2E8F0"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(0, dp(6), 0, dp(6))
        }

        progressBar = ProgressBar(
            context,
            null,
            android.R.attr.progressBarStyleHorizontal
        ).apply {
            isIndeterminate = false
            max = 100
            progress = 10
        }

        transcriptText = TextView(context).apply {
            text = "Say a timer, alarm, Google Tasks todo, or ask anything…"
            setTextColor(Color.parseColor("#94A3B8"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(0, dp(8), 0, dp(12))
        }

        val inputRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        queryInput = EditText(context).apply {
            hint = "Or type a command…"
            setHintTextColor(Color.parseColor("#64748B"))
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEND
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    submitTextQuery(text.toString())
                    true
                } else {
                    false
                }
            }
        }

        actionButton = Button(context).apply {
            text = "Done"
            setTextColor(Color.parseColor("#0F172A"))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#10B981"))
            }
            setOnClickListener {
                val typed = queryInput.text.toString().trim()
                when {
                    typed.isNotEmpty() -> submitTextQuery(typed)
                    needsMicPermission -> {
                        val intent = Intent(context, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(intent)
                        hide()
                    }
                    activeJob?.isActive == true -> audioRecorder.stop()
                    else -> startVoiceCaptureAndRoute()
                }
            }
        }

        inputRow.addView(queryInput)
        inputRow.addView(actionButton)

        card.addView(titleText)
        card.addView(statusText)
        card.addView(progressBar)
        card.addView(transcriptText)
        card.addView(inputRow)

        root.addView(card, cardParams)
        return root
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        val initialQuery = args?.getString(Intent.EXTRA_TEXT)
            ?: args?.getString(SearchManager.QUERY)
            ?: args?.getString("query")

        if (!initialQuery.isNullOrBlank()) {
            submitTextQuery(initialQuery)
        } else {
            startVoiceCaptureAndRoute()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onHandleAssist(
        data: Bundle?,
        structure: AssistStructure?,
        content: AssistContent?
    ) {
        super.onHandleAssist(data, structure, content)
    }

    private fun startVoiceCaptureAndRoute() {
        val micGranted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (!micGranted) {
            needsMicPermission = true
            statusText.text = "Microphone permission needed — tap Grant Mic or type below"
            progressBar.isIndeterminate = false
            progressBar.progress = 0
            actionButton.text = "Grant Mic"
            return
        }

        needsMicPermission = false
        activeJob?.cancel()
        activeJob = sessionScope.launch {
            statusText.text = "Listening with Whistle (16 kHz on-device)…"
            actionButton.text = "Done"
            progressBar.isIndeterminate = false
            progressBar.progress = 8

            val capture = audioRecorder.recordUtteranceDetailed(
                maxDurationMs = 12_000L,
                trailingSilenceMs = 1_100L,
                onAmplitude = { rms ->
                    val scaled = (rms * 2500f).toInt().coerceIn(5, 100)
                    progressBar.post { progressBar.progress = scaled }
                }
            )

            if (capture.errorMessage != null) {
                statusText.text = capture.errorMessage
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                actionButton.text = "Retry"
                return@launch
            }

            if (!capture.isUsable) {
                statusText.text = String.format(
                    Locale.US,
                    "No audio detected (peak=%.4f) — tap Retry or type below",
                    capture.peakAmplitude
                )
                progressBar.isIndeterminate = false
                progressBar.progress = 0
                actionButton.text = "Retry"
                return@launch
            }

            statusText.text = String.format(
                Locale.US,
                "Running Whistle + Needle 3 (%.1fs, peak=%.3f)…",
                capture.samples.size / 16000.0f,
                capture.peakAmplitude
            )
            progressBar.isIndeterminate = true

            val decision = engine.processAudio(capture.samples)
            handleDecision(decision)
        }
    }

    private fun submitTextQuery(query: String) {
        val cleaned = query.trim()
        if (cleaned.isEmpty()) return
        audioRecorder.stop()
        activeJob?.cancel()
        activeJob = sessionScope.launch {
            statusText.text = "Running Needle 3 tool calling…"
            transcriptText.text = "\"$cleaned\""
            progressBar.isIndeterminate = true

            val decision = engine.processText(cleaned)
            handleDecision(decision)
        }
    }

    private suspend fun handleDecision(decision: AssistantDecision) {
        progressBar.isIndeterminate = false
        progressBar.progress = 100

        when (decision) {
            is AssistantDecision.EmptyAudio -> {
                statusText.text = "${decision.reason} — tap Retry or type a query below"
                actionButton.text = "Retry"
            }
            is AssistantDecision.Error -> {
                statusText.text = "Engine error: ${decision.message}"
                actionButton.text = "Retry"
            }
            else -> {
                transcriptText.text = "\"${decision.transcript}\""
                val result = router.execute(decision)
                statusText.text = result.summary
                delay(900L)
                hide()
            }
        }
    }

    override fun onHide() {
        audioRecorder.stop()
        activeJob?.cancel()
        super.onHide()
    }

    override fun onDestroy() {
        audioRecorder.stop()
        sessionScope.cancel()
        super.onDestroy()
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }
}

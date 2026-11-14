package com.cactus.assistant.routing

import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import com.cactus.assistant.engine.AssistantDecision
import java.util.Locale

/**
 * Outcome of executing an [AssistantDecision] via Android Intents.
 */
data class ActionExecutionResult(
    val decision: AssistantDecision,
    val primaryIntent: Intent?,
    val executed: Boolean,
    val summary: String,
    val targetPackage: String? = null
)

/**
 * Builds and dispatches Android Intents for:
 * 1. Silent timers (`AlarmClock.ACTION_SET_TIMER` + `EXTRA_SKIP_UI = true`)
 * 2. Silent alarms (`AlarmClock.ACTION_SET_ALARM` + `EXTRA_SKIP_UI = true`)
 * 3. Pre-filled todos in Google Tasks (`com.google.android.apps.tasks`)
 * 4. Pre-filled queries in Google Gemini (`com.google.android.apps.bard`)
 */
class AssistantActionRouter(private val context: Context?) {

    /**
     * Build a silent `AlarmClock.ACTION_SET_TIMER` intent.
     */
    fun buildSetTimerIntent(durationSeconds: Int, label: String?): Intent {
        return Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, durationSeconds.coerceAtLeast(1))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Build a silent `AlarmClock.ACTION_SET_ALARM` intent.
     */
    fun buildSetAlarmIntent(hour24: Int, minute: Int, label: String?): Intent {
        return Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour24.coerceIn(0, 23))
            putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            if (!label.isNullOrBlank()) {
                putExtra(AlarmClock.EXTRA_MESSAGE, label.trim())
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Build an intent that opens the Google Tasks app (`com.google.android.apps.tasks`)
     * pre-filled with the todo title and optional notes.
     */
    fun buildGoogleTasksIntent(title: String, notes: String? = null): Intent {
        val cleanTitle = title.trim()
        val bodyText = if (!notes.isNullOrBlank()) {
            "$cleanTitle\n\n${notes.trim()}"
        } else {
            cleanTitle
        }
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(GOOGLE_TASKS_PACKAGE)
            putExtra(Intent.EXTRA_SUBJECT, cleanTitle)
            putExtra(Intent.EXTRA_TITLE, cleanTitle)
            putExtra(Intent.EXTRA_TEXT, bodyText)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

    /**
     * Build an intent that opens the installed Google Gemini Android app
     * (`com.google.android.apps.bard`) pre-filled with the user's query.
     */
    fun buildGeminiIntent(query: String): Intent {
        val cleanQuery = query.trim()
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            setPackage(GOOGLE_GEMINI_PACKAGE)
            putExtra(Intent.EXTRA_TEXT, cleanQuery)
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra("query", cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Secondary fallback intent for Google Gemini (`com.google.android.apps.bard`) using
     * `ACTION_WEB_SEARCH` if `ACTION_SEND` is not handled by the installed version.
     */
    fun buildGeminiSearchFallbackIntent(query: String): Intent {
        val cleanQuery = query.trim()
        return Intent(Intent.ACTION_WEB_SEARCH).apply {
            setPackage(GOOGLE_GEMINI_PACKAGE)
            putExtra(SearchManager.QUERY, cleanQuery)
            putExtra(Intent.EXTRA_TEXT, cleanQuery)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    /**
     * Execute an [AssistantDecision] on the Android system and return an [ActionExecutionResult].
     */
    fun execute(decision: AssistantDecision): ActionExecutionResult {
        return when (decision) {
            is AssistantDecision.SetTimer -> {
                val intent = buildSetTimerIntent(decision.durationSeconds, decision.label)
                val launched = startIntentSafely(intent)
                val labelSuffix = decision.label?.let { " ($it)" }.orEmpty()
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = intent,
                    executed = launched,
                    summary = "Silent timer set for ${formatDuration(decision.durationSeconds)}$labelSuffix"
                )
            }

            is AssistantDecision.SetAlarm -> {
                val intent = buildSetAlarmIntent(decision.hour24, decision.minute, decision.label)
                val launched = startIntentSafely(intent)
                val timeFormatted = String.format(Locale.US, "%02d:%02d", decision.hour24, decision.minute)
                val labelSuffix = decision.label?.let { " ($it)" }.orEmpty()
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = intent,
                    executed = launched,
                    summary = "Silent alarm set for $timeFormatted$labelSuffix"
                )
            }

            is AssistantDecision.CreateTodo -> {
                val primary = buildGoogleTasksIntent(decision.title, decision.notes)
                var launched = startIntentSafely(primary)
                if (!launched) {
                    // Fallback to generic text/plain share or web Google Tasks
                    val genericShare = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, decision.title)
                        putExtra(Intent.EXTRA_TEXT, decision.title)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    launched = startIntentSafely(genericShare)
                }
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = primary,
                    executed = launched,
                    summary = "Opened Google Tasks pre-filled with todo: \"${decision.title}\"",
                    targetPackage = GOOGLE_TASKS_PACKAGE
                )
            }

            is AssistantDecision.FallbackToGemini -> {
                val primary = buildGeminiIntent(decision.query)
                var launched = startIntentSafely(primary)
                if (!launched) {
                    val secondary = buildGeminiSearchFallbackIntent(decision.query)
                    launched = startIntentSafely(secondary)
                }
                if (!launched) {
                    // Try launching Gemini package directly with query extras
                    val launchIntent = context?.packageManager
                        ?.getLaunchIntentForPackage(GOOGLE_GEMINI_PACKAGE)
                        ?.apply {
                            putExtra(Intent.EXTRA_TEXT, decision.query)
                            putExtra(SearchManager.QUERY, decision.query)
                            putExtra("query", decision.query)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    if (launchIntent != null) {
                        launched = startIntentSafely(launchIntent)
                    }
                }
                if (!launched) {
                    val webUri = Uri.parse("https://gemini.google.com/app")
                        .buildUpon()
                        .appendQueryParameter("q", decision.query)
                        .build()
                    val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    launched = startIntentSafely(webIntent)
                }
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = primary,
                    executed = launched,
                    summary = "Forwarded to Google Gemini (${GOOGLE_GEMINI_PACKAGE}): \"${decision.query}\"",
                    targetPackage = GOOGLE_GEMINI_PACKAGE
                )
            }

            is AssistantDecision.EmptyAudio -> {
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = null,
                    executed = false,
                    summary = decision.reason
                )
            }

            is AssistantDecision.Error -> {
                ActionExecutionResult(
                    decision = decision,
                    primaryIntent = null,
                    executed = false,
                    summary = "Engine error: ${decision.message}"
                )
            }
        }
    }

    private fun startIntentSafely(intent: Intent): Boolean {
        val ctx = context ?: return false
        return try {
            ctx.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: Exception) {
            false
        }
    }

    companion object {
        const val GOOGLE_TASKS_PACKAGE = "com.google.android.apps.tasks"
        const val GOOGLE_GEMINI_PACKAGE = "com.google.android.apps.bard"

        fun formatDuration(totalSeconds: Int): String {
            val h = totalSeconds / 3600
            val m = (totalSeconds % 3600) / 60
            val s = totalSeconds % 60
            val parts = mutableListOf<String>()
            if (h > 0) parts.add("${h}h")
            if (m > 0) parts.add("${m}m")
            if (s > 0 || parts.isEmpty()) parts.add("${s}s")
            return parts.joinToString(" ")
        }
    }
}

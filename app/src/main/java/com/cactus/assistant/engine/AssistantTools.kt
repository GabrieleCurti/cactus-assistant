package com.cactus.assistant.engine

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Represents the parsed output of Whistle speech-to-text (`cactus_transcribe`).
 */
data class WhistleTranscript(
    val text: String,
    val language: String = "en",
    val ttftMs: Double = 0.0,
    val decodeTps: Double = 0.0,
    val isStub: Boolean = false,
    val error: String? = null
)

/**
 * Represents a single function call emitted by Needle 3 (`cactus_complete`).
 */
data class NeedleToolCall(
    val name: String,
    val arguments: Map<String, Any?>
)

/**
 * Represents the full JSON envelope returned by Needle 3 (`cactus_complete`),
 * including audio fields when `cactus_complete` processes 16 kHz audio directly.
 */
data class NeedleResponse(
    val type: String,
    val success: Boolean,
    val functionCalls: List<NeedleToolCall>,
    val suppressedCalls: List<NeedleToolCall> = emptyList(),
    val reasoning: String? = null,
    val confidence: Double? = null,
    val audioText: String? = null,
    val audioLanguage: String? = null,
    val error: String? = null,
    val isStub: Boolean = false,
    val rawJson: String = ""
)

/**
 * Strongly-typed assistant routing decision produced from Whistle + Needle 3.
 */
sealed interface AssistantDecision {
    val transcript: String

    data class SetTimer(
        val durationSeconds: Int,
        val label: String?,
        val confidence: Double,
        override val transcript: String,
        val reasoning: String? = null
    ) : AssistantDecision

    data class SetAlarm(
        val hour24: Int,
        val minute: Int,
        val label: String?,
        val confidence: Double,
        override val transcript: String,
        val reasoning: String? = null
    ) : AssistantDecision

    data class CreateTodo(
        val title: String,
        val notes: String?,
        val confidence: Double,
        override val transcript: String,
        val reasoning: String? = null
    ) : AssistantDecision

    data class FallbackToGemini(
        val query: String,
        val reason: String
    ) : AssistantDecision {
        override val transcript: String get() = query
    }

    data class EmptyAudio(
        val reason: String = "No speech detected"
    ) : AssistantDecision {
        override val transcript: String get() = ""
    }

    data class Error(
        val message: String,
        override val transcript: String = ""
    ) : AssistantDecision
}

/**
 * Tool schemas and response parser for Cactus Compute's Needle 3 and Whistle models.
 *
 * Aligns with Cactus Compute's official Needle 3 tool design contract:
 * - Verbatim `time_human` / `title` string spans copied word for word by Needle 3
 *   so calibrated confidence stays high and span-grounding never penalizes unit conversions.
 * - Host app resolves `time_human` into silent `AlarmClock` timer seconds or 24-hour alarm times.
 * - Empty `function_calls: []` refusal routes to the Google Gemini app (`com.google.android.apps.bard`).
 */
object AssistantTools {

    const val TOOL_SET_TIMER = "set_timer"
    const val TOOL_SET_ALARM = "set_alarm"
    const val TOOL_CREATE_TODO = "create_todo"

    const val MIN_CONFIDENCE_THRESHOLD = 0.15

    const val SYSTEM_INSTRUCTIONS =
        "Copy titles and time phrases verbatim from the user; never rephrase or guess missing values. " +
            "set_timer requires a duration, set_alarm requires a clock time, and create_todo handles tasks and 'remind me to' requests. " +
            "Unsupported, incomplete, and negated requests return no call."

    /**
     * Keyword biasing phrases passed to Whistle's Aho-Corasick beam search (`cactus_set_audio`).
     */
    const val WHISTLE_KEYWORDS =
        "timer\nalarm\nwake me up\ntodo\nto-do\ntask\nadd a todo\ncreate a task\nremind me to\nset a timer\nset an alarm\nGoogle Tasks\nGemini\nminutes\nseconds\nhours"

    /**
     * Needle 3 JSON schema array for the three supported local tools.
     * Kept identical to `app/src/main/assets/models/tools.json`.
     */
    val TOOLS_JSON: String = """
[
  {
    "name": "set_timer",
    "description": "Set a countdown timer when a duration (minutes, seconds, hours) is stated.",
    "parameters": {
      "type": "object",
      "properties": {
        "time_human": {
          "type": "string",
          "minLength": 1,
          "maxLength": 60,
          "description": "The duration copied word for word, e.g. '10 minutes', '90 seconds', '1 hour 30 minutes'."
        },
        "label": {
          "type": "string",
          "minLength": 1,
          "maxLength": 60,
          "description": "Optional name for the timer; include only when the user names one."
        }
      },
      "required": ["time_human"]
    }
  },
  {
    "name": "set_alarm",
    "description": "Set an alarm or wake-up call when an explicit time of day is stated.",
    "parameters": {
      "type": "object",
      "properties": {
        "time_human": {
          "type": "string",
          "minLength": 1,
          "maxLength": 60,
          "description": "The time of day copied word for word, e.g. '7:30 am', '8 pm', '6 am'."
        },
        "label": {
          "type": "string",
          "minLength": 1,
          "maxLength": 60,
          "description": "Optional alarm label; include only when the user names one."
        }
      },
      "required": ["time_human"]
    }
  },
  {
    "name": "create_todo",
    "description": "Add a todo item, task, or undated reminder (like 'remind me to ...') in Google Tasks.",
    "parameters": {
      "type": "object",
      "properties": {
        "title": {
          "type": "string",
          "minLength": 1,
          "maxLength": 160,
          "description": "The task or reminder description copied word for word."
        }
      },
      "required": ["title"]
    }
  }
]
    """.trimIndent()

    /**
     * Parse Whistle JSON response (`{"text": "...", "language": "en", "ttft_ms": 5.9, "decode_tps": 1319.0}`).
     */
    fun parseWhistleJson(rawJson: String): WhistleTranscript {
        if (rawJson.isBlank()) return WhistleTranscript(text = "")
        return try {
            val obj = JSONObject(rawJson)
            val err = if (obj.has("error") && !obj.isNull("error")) {
                obj.optString("error").takeIf { it.isNotBlank() }
            } else {
                null
            }
            WhistleTranscript(
                text = obj.optString("text", "").trim(),
                language = obj.optString("language", "en"),
                ttftMs = obj.optDouble("ttft_ms", 0.0),
                decodeTps = obj.optDouble("decode_tps", 0.0),
                isStub = obj.optBoolean("stub", false),
                error = err
            )
        } catch (e: Exception) {
            WhistleTranscript(text = "", error = "Invalid Whistle JSON: ${e.message}")
        }
    }

    /**
     * Parse Needle 3 JSON response (`{"type":"call","function_calls":[...],"confidence":0.94,"audio_text":"..."}`).
     */
    fun parseNeedleJson(rawJson: String): NeedleResponse {
        if (rawJson.isBlank()) {
            return NeedleResponse(
                type = "error",
                success = false,
                functionCalls = emptyList(),
                error = "Empty response from engine",
                rawJson = rawJson
            )
        }
        return try {
            val obj = JSONObject(rawJson)
            val calls = parseToolCallArray(obj.optJSONArray("function_calls"))
            val suppressed = parseToolCallArray(obj.optJSONArray("suppressed_calls"))
            val conf = if (obj.has("confidence") && !obj.isNull("confidence")) {
                obj.optDouble("confidence", 0.0)
            } else {
                null
            }
            val reasoning = if (obj.has("reasoning") && !obj.isNull("reasoning")) {
                obj.optString("reasoning")
            } else {
                null
            }
            val audioText = if (obj.has("audio_text") && !obj.isNull("audio_text")) {
                obj.optString("audio_text")
            } else {
                null
            }
            val audioLanguage = if (obj.has("audio_language") && !obj.isNull("audio_language")) {
                obj.optString("audio_language")
            } else {
                null
            }
            val error = if (obj.has("error") && !obj.isNull("error")) {
                obj.optString("error").takeIf { it.isNotBlank() }
            } else {
                null
            }
            val type = obj.optString("type", "call")
            val success = obj.optBoolean("success", type != "error" && error == null)
            NeedleResponse(
                type = type,
                success = success,
                functionCalls = calls,
                suppressedCalls = suppressed,
                reasoning = reasoning,
                confidence = conf,
                audioText = audioText,
                audioLanguage = audioLanguage,
                error = error,
                isStub = obj.optBoolean("stub", false),
                rawJson = rawJson
            )
        } catch (e: Exception) {
            NeedleResponse(
                type = "error",
                success = false,
                functionCalls = emptyList(),
                error = "Invalid Needle JSON: ${e.message}",
                rawJson = rawJson
            )
        }
    }

    private fun parseToolCallArray(arr: JSONArray?): List<NeedleToolCall> {
        if (arr == null) return emptyList()
        val list = mutableListOf<NeedleToolCall>()
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val name = item.optString("name", "").trim()
            if (name.isEmpty()) continue
            val argsObj = item.optJSONObject("arguments") ?: JSONObject()
            val argsMap = mutableMapOf<String, Any?>()
            val keys = argsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val v = argsObj.opt(key)
                argsMap[key] = if (v == JSONObject.NULL) null else v
            }
            list.add(NeedleToolCall(name = name, arguments = argsMap))
        }
        return list
    }

    /**
     * Convert a parsed [NeedleResponse] (and optional fallback transcript) into an [AssistantDecision].
     *
     * - Engine failures (`!success` or non-empty `error`) return [AssistantDecision.Error] rather than
     *   silently masquerading as a Gemini refusal.
     * - Empty `function_calls` (or invalid/below-threshold calls) route to Google Gemini
     *   (`com.google.android.apps.bard`).
     */
    fun toDecision(
        response: NeedleResponse,
        fallbackTranscript: String = ""
    ): AssistantDecision {
        val transcript = (response.audioText?.takeIf { it.isNotBlank() } ?: fallbackTranscript).trim()

        if (!response.success || !response.error.isNullOrBlank()) {
            return AssistantDecision.Error(
                message = response.error ?: "Cactus engine returned an error",
                transcript = transcript
            )
        }

        if (transcript.isEmpty() && response.functionCalls.isEmpty()) {
            return AssistantDecision.EmptyAudio()
        }

        val confidence = response.confidence ?: 1.0
        if (response.functionCalls.isEmpty()) {
            return AssistantDecision.FallbackToGemini(
                query = transcript,
                reason = "No matching tool call in Needle 3 (function_calls=[])"
            )
        }

        if (confidence < MIN_CONFIDENCE_THRESHOLD) {
            return AssistantDecision.FallbackToGemini(
                query = transcript,
                reason = String.format(
                    Locale.US,
                    "Needle 3 confidence (%.2f) below threshold (%.2f)",
                    confidence,
                    MIN_CONFIDENCE_THRESHOLD
                )
            )
        }

        val call = response.functionCalls.first()
        return when (call.name) {
            TOOL_SET_TIMER -> {
                val timeHuman = (call.arguments["time_human"] as? String)?.trim().orEmpty()
                val fromArgs = extractDurationSeconds(call.arguments, timeHuman)
                val groundedInTranscript = if (transcript.isNotBlank()) {
                    parseHumanDurationSeconds(transcript) > 0
                } else {
                    true
                }
                val durationSeconds = when {
                    fromArgs > 0 && groundedInTranscript -> fromArgs
                    fromArgs > 0 && transcript.isBlank() -> fromArgs
                    else -> 0
                }
                if (durationSeconds <= 0) {
                    AssistantDecision.FallbackToGemini(
                        query = transcript,
                        reason = "Timer duration missing or not grounded in query"
                    )
                } else {
                    val label = (call.arguments["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    AssistantDecision.SetTimer(
                        durationSeconds = durationSeconds,
                        label = label,
                        confidence = confidence,
                        transcript = transcript.ifEmpty { "Set timer for $durationSeconds seconds" },
                        reasoning = response.reasoning
                    )
                }
            }

            TOOL_SET_ALARM -> {
                val timeHuman = (call.arguments["time_human"] as? String)?.trim().orEmpty()
                val parsedHuman = if (timeHuman.isNotEmpty()) parseHumanAlarmTime(timeHuman) else null
                val rawHour = parsedHuman?.first ?: call.arguments["hour"].asIntOrNull()
                val rawMinute = parsedHuman?.second ?: (call.arguments["minute"].asIntOrNull() ?: 0)
                val period = (call.arguments["period"] as? String)?.lowercase(Locale.US)?.trim()
                val groundedInTranscript = if (transcript.isNotBlank() && parsedHuman != null) {
                    parseHumanAlarmTime(transcript) != null
                } else {
                    true
                }

                if (rawHour == null || rawHour !in 0..23 || !groundedInTranscript) {
                    AssistantDecision.FallbackToGemini(
                        query = transcript,
                        reason = "Alarm time missing or not grounded in query"
                    )
                } else {
                    val minute = rawMinute.coerceIn(0, 59)
                    val hour24 = if (parsedHuman != null) {
                        rawHour
                    } else {
                        normalizeHour24(rawHour, period, transcript)
                    }
                    val label = (call.arguments["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    AssistantDecision.SetAlarm(
                        hour24 = hour24,
                        minute = minute,
                        label = label,
                        confidence = confidence,
                        transcript = transcript.ifEmpty {
                            String.format(Locale.US, "Set alarm for %02d:%02d", hour24, minute)
                        },
                        reasoning = response.reasoning
                    )
                }
            }

            TOOL_CREATE_TODO -> {
                val rawTitle = (call.arguments["title"] as? String)?.trim().orEmpty()
                val cleanTitle = cleanTodoTitle(rawTitle, transcript)
                if (cleanTitle.isEmpty()) {
                    AssistantDecision.FallbackToGemini(
                        query = transcript,
                        reason = "Todo title missing"
                    )
                } else {
                    val notes = (call.arguments["notes"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                    AssistantDecision.CreateTodo(
                        title = cleanTitle,
                        notes = notes,
                        confidence = confidence,
                        transcript = transcript.ifEmpty { "Create todo: $cleanTitle" },
                        reasoning = response.reasoning
                    )
                }
            }

            else -> AssistantDecision.FallbackToGemini(
                query = transcript,
                reason = "Unsupported tool '${call.name}'"
            )
        }
    }

    /**
     * Parse human timer duration phrases such as:
     * - `"10 minutes"`, `"10-minute"`, `"1 hour 30 minutes"`, `"90 seconds"`, `"half an hour"`
     * - `"ten minutes"`, `"twenty five minutes"`, `"one hour and thirty minutes"`
     * - `"1h 15m"`, `"45s"`, `"2.5 minutes"`
     */
    fun parseHumanDurationSeconds(text: String): Int {
        if (text.isBlank()) return 0
        val normalized = normalizeNumberWords(text.lowercase(Locale.US))
            .replace("-", " ")
            .replace(Regex("""\b(?:a\s+)?half\s+(?:of\s+)?(?:an\s+|a\s+)?hour\b"""), "30 minutes")
            .replace(Regex("""\b(?:a\s+)?quarter\s+(?:of\s+)?(?:an\s+|a\s+)?hour\b"""), "15 minutes")
            .replace(Regex("""\ba\s+(hour|minute|min|second|sec)\b"""), "1 $1")
            .replace(Regex("""\ban\s+hour\b"""), "1 hour")

        var totalSeconds = 0.0
        var matchedAny = false

        Regex("""(\d+(?:\.\d+)?)\s*(?:hours?|hrs?|hr|h)\b""").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                totalSeconds += it * 3600.0
                matchedAny = true
            }
        }
        Regex("""(\d+(?:\.\d+)?)\s*(?:minutes?|mins?|min|m)\b""").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                totalSeconds += it * 60.0
                matchedAny = true
            }
        }
        Regex("""(\d+(?:\.\d+)?)\s*(?:seconds?|secs?|sec|s)\b""").findAll(normalized).forEach { m ->
            m.groupValues[1].toDoubleOrNull()?.let {
                totalSeconds += it
                matchedAny = true
            }
        }

        val rounded = totalSeconds.toInt()
        return if (matchedAny && rounded > 0) rounded else 0
    }

    /**
     * Parse human alarm time phrases such as:
     * - `"7:30 am"`, `"7.30 a.m."`, `"8 pm"`, `"6 am"`, `"14:30"`, `"noon"`, `"midnight"`
     * - `"7 30 am"`, `"07:05"`, `"seven thirty am"`
     * Returns `(hour24, minute)` or `null` if no clock time is present.
     */
    fun parseHumanAlarmTime(text: String): Pair<Int, Int>? {
        if (text.isBlank()) return null
        val normalized = normalizeNumberWords(text.lowercase(Locale.US))
            .replace("o'clock", "")
            .trim()

        if (Regex("""\bnoon\b""").containsMatchIn(normalized)) return 12 to 0
        if (Regex("""\bmidnight\b""").containsMatchIn(normalized)) return 0 to 0

        // Match H:MM [am/pm], H.MM [am/pm], H MM am/pm, or H am/pm
        val withAmPm = Regex(
            """\b(\d{1,2})(?:[:.](\d{2})|\s+(\d{2}))?\s*(a\.?\s*m\.?|p\.?\s*m\.?)(?:\b|$)"""
        ).find(normalized)
        if (withAmPm != null) {
            val h = withAmPm.groupValues[1].toIntOrNull() ?: return null
            val m = withAmPm.groupValues[2].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: withAmPm.groupValues[3].takeIf { it.isNotEmpty() }?.toIntOrNull()
                ?: 0
            if (h !in 0..23 || m !in 0..59) return null
            val period = withAmPm.groupValues[4].replace(".", "").replace(" ", "")
            val h24 = normalizeHour24(h, period)
            return h24 to m
        }

        // Match 24h or colon/dot time like 14:30 or 7:30 or 06.45
        val colonTime = Regex("""\b(\d{1,2})[:.](\d{2})\b""").find(normalized)
        if (colonTime != null) {
            val h = colonTime.groupValues[1].toIntOrNull() ?: return null
            val m = colonTime.groupValues[2].toIntOrNull() ?: return null
            if (h !in 0..23 || m !in 0..59) return null
            return h to m
        }

        // Match bare hour after at/for e.g. "alarm for 7" or standalone "7"
        val bareAfterPrep = Regex("""\b(?:at|for)\s+(\d{1,2})\b""").find(normalized)
            ?: Regex("""^\s*(\d{1,2})\s*$""").find(normalized)
        if (bareAfterPrep != null) {
            val h = bareAfterPrep.groupValues[1].toIntOrNull() ?: return null
            if (h in 0..23) return h to 0
        }

        return null
    }

    private fun cleanTodoTitle(rawTitle: String, transcript: String): String {
        var title = rawTitle.trim().trim('"', '\'', '.', '!')
        // Guard against Needle 3 returning a bare filler token when a full title exists in transcript
        if (title.equals("to", ignoreCase = true) ||
            title.equals("toto", ignoreCase = true) ||
            title.equals("todo", ignoreCase = true)
        ) {
            parseGroundedTodo(transcript)?.let { return it.title }
        }
        title = title.replace(Regex("""^(?:to|that)\s+""", RegexOption.IGNORE_CASE), "").trim()
        return title
    }

    /**
     * Grounded tool-call extractor matching Needle 3's `time_human` / `title` contract for
     * offline/JVM environments when `.cact` model weights are not yet bundled.
     */
    fun synthesizeGroundedNeedleJson(query: String): String {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return """{"type":"call","success":true,"function_calls":[],"confidence":0.0}"""
        }

        // Reject negated or cancellation requests per Needle 3 contract ("don't set a timer...")
        val lower = trimmed.lowercase(Locale.US)
        if (Regex("""\b(don'?t|do\s+not|never|cancel|delete|remove)\b""").containsMatchIn(lower)) {
            return """{"type":"call","success":true,"function_calls":[],"suppressed_calls":[],"confidence":0.92}"""
        }

        // 1. Check timer patterns
        parseGroundedTimer(trimmed)?.let { (timeHuman, label, reasoning) ->
            val args = JSONObject().apply {
                put("time_human", timeHuman)
                if (!label.isNullOrBlank()) put("label", label)
            }
            return buildCallJson(TOOL_SET_TIMER, args, reasoning, 0.96)
        }

        // 2. Check alarm patterns
        parseGroundedAlarm(trimmed)?.let { (timeHuman, label, reasoning) ->
            val args = JSONObject().apply {
                put("time_human", timeHuman)
                if (!label.isNullOrBlank()) put("label", label)
            }
            return buildCallJson(TOOL_SET_ALARM, args, reasoning, 0.95)
        }

        // 3. Check todo / Google Tasks patterns
        parseGroundedTodo(trimmed)?.let { (title, _, reasoning) ->
            val args = JSONObject().apply {
                put("title", title)
            }
            return buildCallJson(TOOL_CREATE_TODO, args, reasoning, 0.94)
        }

        // 4. Anything else -> Needle 3 empty refusal `[]` (routes to Google Gemini)
        return """{"type":"call","success":true,"function_calls":[],"suppressed_calls":[],"confidence":0.98}"""
    }

    private fun buildCallJson(
        name: String,
        arguments: JSONObject,
        reasoning: String,
        confidence: Double
    ): String {
        val callObj = JSONObject().apply {
            put("name", name)
            put("arguments", arguments)
        }
        val root = JSONObject().apply {
            put("type", "call")
            put("success", true)
            put("function_calls", JSONArray().put(callObj))
            put("suppressed_calls", JSONArray())
            put("reasoning", reasoning)
            put("confidence", confidence)
        }
        return root.toString()
    }

    private data class TimerGrounded(
        val timeHuman: String,
        val label: String?,
        val reasoning: String
    )

    private fun parseGroundedTimer(text: String): TimerGrounded? {
        if (!Regex("""\btimer\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return null
        }
        val durationSeconds = parseHumanDurationSeconds(text)
        if (durationSeconds <= 0) return null

        val h = durationSeconds / 3600
        val m = (durationSeconds % 3600) / 60
        val s = durationSeconds % 60
        val timeHuman = buildList {
            if (h > 0) add("$h ${if (h == 1) "hour" else "hours"}")
            if (m > 0) add("$m ${if (m == 1) "minute" else "minutes"}")
            if (s > 0) add("$s ${if (s == 1) "second" else "seconds"}")
        }.joinToString(" ")

        var label: String? = null
        val namedMatch = Regex(
            """\btimer\b.*?\b(?:called|named|labeled|for)\s+([a-zA-Z][a-zA-Z0-9\s'-]{1,40})$""",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (namedMatch != null) {
            val candidate = namedMatch.groupValues[1].trim()
            if (parseHumanDurationSeconds(candidate) == 0) {
                label = candidate
            }
        } else {
            val prefixLabel = Regex(
                """\b(?:hours?|hrs?|minutes?|mins?|seconds?|secs?)\s+([a-zA-Z]{2,25})\s+timer\b""",
                RegexOption.IGNORE_CASE
            ).find(text)
            if (prefixLabel != null) {
                label = prefixLabel.groupValues[1].trim()
            }
        }

        val reasoning = buildString {
            append("'$timeHuman' -> time_human")
            if (label != null) append("; '$label' -> label")
        }
        return TimerGrounded(timeHuman, label, reasoning)
    }

    private data class AlarmGrounded(
        val timeHuman: String,
        val label: String?,
        val reasoning: String
    )

    private fun parseGroundedAlarm(text: String): AlarmGrounded? {
        val hasAlarmTrigger = Regex(
            """\b(?:alarm|wake\s+me\s+up)\b""",
            RegexOption.IGNORE_CASE
        ).containsMatchIn(text)
        if (!hasAlarmTrigger) return null

        val parsed = parseHumanAlarmTime(text) ?: return null
        val timeHuman = String.format(Locale.US, "%02d:%02d", parsed.first, parsed.second)

        var label: String? = null
        val labelMatch = Regex(
            """\b(?:called|named|labeled|for)\s+([a-zA-Z][a-zA-Z0-9\s'-]{1,40})$""",
            RegexOption.IGNORE_CASE
        ).find(text)
        if (labelMatch != null) {
            val candidate = labelMatch.groupValues[1].trim()
            if (!candidate.equals("tomorrow", ignoreCase = true) &&
                !candidate.equals("today", ignoreCase = true) &&
                parseHumanAlarmTime("at $candidate") == null
            ) {
                label = candidate
            }
        }

        val reasoning = buildString {
            append("'$timeHuman' -> time_human")
            if (label != null) append("; '$label' -> label")
        }
        return AlarmGrounded(timeHuman, label, reasoning)
    }

    private data class TodoGrounded(
        val title: String,
        val notes: String?,
        val reasoning: String
    )

    private fun parseGroundedTodo(text: String): TodoGrounded? {
        val patterns = listOf(
            Regex(
                """^(?:please\s+)?(?:add|create|make|new|put)\s+(?:a\s+)?(?:new\s+)?(?:todo|toto|to-do|to\s+do|task)(?:\s+in\s+google\s+tasks)?(?:\s+(?:to|for|called|named|:))?\s+(.+)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:please\s+)?(?:add|put)\s+(.+?)\s+(?:to|on|in)\s+(?:my\s+)?(?:todo|to-do|to\s+do|tasks?|google\s+tasks)(?:\s+list)?$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:please\s+)?remind\s+me\s+to\s+(.+)$""",
                RegexOption.IGNORE_CASE
            ),
            Regex(
                """^(?:todo|to-do|task)\s*:\s*(.+)$""",
                RegexOption.IGNORE_CASE
            )
        )

        for (regex in patterns) {
            val match = regex.find(text.trim()) ?: continue
            var rawTitle = match.groupValues[1].trim()
            rawTitle = rawTitle.replace(
                Regex("""\s+in\s+google\s+tasks$""", RegexOption.IGNORE_CASE),
                ""
            ).trim()
            rawTitle = rawTitle.trim('"', '\'', '.', '!')
            if (rawTitle.isNotEmpty()) {
                return TodoGrounded(
                    title = rawTitle,
                    notes = null,
                    reasoning = "'$rawTitle' -> title"
                )
            }
        }
        return null
    }

    private fun extractDurationSeconds(args: Map<String, Any?>, timeHuman: String): Int {
        if (timeHuman.isNotEmpty()) {
            val fromHuman = parseHumanDurationSeconds(timeHuman)
            if (fromHuman > 0) return fromHuman
        }
        val hours = args["hours"].asIntOrNull() ?: 0
        val minutes = args["minutes"].asIntOrNull() ?: 0
        val seconds = args["seconds"].asIntOrNull() ?: 0
        val combined = hours * 3600 + minutes * 60 + seconds
        if (combined > 0) return combined
        return args["duration_seconds"].asIntOrNull() ?: 0
    }

    fun normalizeHour24(rawHour: Int, period: String?, transcript: String = ""): Int {
        val resolvedPeriod = period?.lowercase(Locale.US)?.replace(".", "")?.trim()
            ?: run {
                val m = Regex("""\b(a\.?m\.?|p\.?m\.?)\b""", RegexOption.IGNORE_CASE).find(transcript)
                m?.groupValues?.get(1)?.lowercase(Locale.US)?.replace(".", "")
            }

        return when (resolvedPeriod) {
            "pm" -> if (rawHour in 1..11) rawHour + 12 else rawHour.coerceIn(0, 23)
            "am" -> if (rawHour == 12) 0 else rawHour.coerceIn(0, 23)
            else -> rawHour.coerceIn(0, 23)
        }
    }

    private fun normalizeNumberWords(input: String): String {
        // Handle compound number words like "twenty five" -> "25", "forty-five" -> "45"
        var result = input.replace(Regex("""\btwenty[\s-]+one\b"""), "21")
            .replace(Regex("""\btwenty[\s-]+two\b"""), "22")
            .replace(Regex("""\btwenty[\s-]+three\b"""), "23")
            .replace(Regex("""\btwenty[\s-]+four\b"""), "24")
            .replace(Regex("""\btwenty[\s-]+five\b"""), "25")
            .replace(Regex("""\bthirty[\s-]+five\b"""), "35")
            .replace(Regex("""\bforty[\s-]+five\b"""), "45")
            .replace(Regex("""\bfifty[\s-]+five\b"""), "55")

        val wordToNumber = mapOf(
            "zero" to "0",
            "one" to "1",
            "two" to "2",
            "three" to "3",
            "four" to "4",
            "five" to "5",
            "six" to "6",
            "seven" to "7",
            "eight" to "8",
            "nine" to "9",
            "ten" to "10",
            "eleven" to "11",
            "twelve" to "12",
            "thirteen" to "13",
            "fourteen" to "14",
            "fifteen" to "15",
            "sixteen" to "16",
            "seventeen" to "17",
            "eighteen" to "18",
            "nineteen" to "19",
            "twenty" to "20",
            "thirty" to "30",
            "forty" to "40",
            "fifty" to "50",
            "sixty" to "60",
            "ninety" to "90"
        )
        for ((word, digit) in wordToNumber) {
            result = result.replace(Regex("""\b$word\b""", RegexOption.IGNORE_CASE), digit)
        }
        return result
    }

    private fun Any?.asIntOrNull(): Int? = when (this) {
        is Int -> this
        is Long -> this.toInt()
        is Double -> this.toInt()
        is Float -> this.toInt()
        is Number -> this.toInt()
        is String -> this.trim().toIntOrNull(10)
        else -> null
    }
}

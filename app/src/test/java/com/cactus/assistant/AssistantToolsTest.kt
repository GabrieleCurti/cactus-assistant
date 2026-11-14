package com.cactus.assistant

import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.engine.AssistantTools
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantToolsTest {

    @Test
    fun toolsSchema_isValidNeedle3JsonWithThreeToolsAndMatchesAssetsFile() {
        val arr = JSONArray(AssistantTools.TOOLS_JSON)
        assertEquals(3, arr.length())

        val names = (0 until arr.length()).map { arr.getJSONObject(it).getString("name") }
        assertEquals(
            listOf("set_timer", "set_alarm", "create_todo"),
            names
        )

        for (i in 0 until arr.length()) {
            val tool = arr.getJSONObject(i)
            assertTrue(tool.getString("description").isNotBlank())
            assertFalse("No fragile regex triggers", tool.has("triggers"))
            assertEquals("object", tool.getJSONObject("parameters").getString("type"))
        }

        // Keep assets/models/tools.json identical to AssistantTools.TOOLS_JSON
        val assetsTools = File("src/main/assets/models/tools.json")
            .takeIf { it.exists() }
            ?: File("app/src/main/assets/models/tools.json")
        if (assetsTools.exists()) {
            assertEquals(
                AssistantTools.TOOLS_JSON.trim(),
                assetsTools.readText().trim()
            )
        }
    }

    @Test
    fun parseUnifiedWhistleAndNeedleJson_extractsVerbatimTimeHumanAndAudioText() {
        val raw = """
            {
              "type": "call",
              "success": true,
              "function_calls": [
                {
                  "name": "set_timer",
                  "arguments": {
                    "time_human": "10 minutes",
                    "label": "pasta"
                  }
                }
              ],
              "confidence": 0.64,
              "reasoning": "'10 minutes' -> time_human; 'pasta' -> label",
              "audio_text": "Set a timer for ten minutes for pasta.",
              "audio_language": "en"
            }
        """.trimIndent()

        val parsed = AssistantTools.parseNeedleJson(raw)
        assertTrue(parsed.success)
        assertEquals("Set a timer for ten minutes for pasta.", parsed.audioText)
        assertEquals("en", parsed.audioLanguage)
        assertEquals(1, parsed.functionCalls.size)

        val decision = AssistantTools.toDecision(parsed)
        assertTrue(decision is AssistantDecision.SetTimer)
        val timer = decision as AssistantDecision.SetTimer
        assertEquals(600, timer.durationSeconds)
        assertEquals("pasta", timer.label)
        assertEquals(0.64, timer.confidence, 1e-4)
        assertEquals("Set a timer for ten minutes for pasta.", timer.transcript)
    }

    @Test
    fun parseHumanDurationSeconds_handlesNumericWordAndCompoundDurations() {
        assertEquals(600, AssistantTools.parseHumanDurationSeconds("10 minutes"))
        assertEquals(600, AssistantTools.parseHumanDurationSeconds("ten minute"))
        assertEquals(90, AssistantTools.parseHumanDurationSeconds("ninety seconds"))
        assertEquals(5400, AssistantTools.parseHumanDurationSeconds("one hour 30 minutes"))
        assertEquals(1800, AssistantTools.parseHumanDurationSeconds("half an hour"))
        assertEquals(4500, AssistantTools.parseHumanDurationSeconds("1h 15m"))
        assertEquals(45, AssistantTools.parseHumanDurationSeconds("45 seconds"))
        assertEquals(0, AssistantTools.parseHumanDurationSeconds("pasta"))
    }

    @Test
    fun parseHumanAlarmTime_handlesColonDotAmPmAnd24HourFormats() {
        assertEquals(7 to 30, AssistantTools.parseHumanAlarmTime("7:30 am"))
        assertEquals(7 to 30, AssistantTools.parseHumanAlarmTime("7.30 a.m."))
        assertEquals(20 to 0, AssistantTools.parseHumanAlarmTime("8 pm"))
        assertEquals(18 to 45, AssistantTools.parseHumanAlarmTime("6:45 p.m."))
        assertEquals(14 to 30, AssistantTools.parseHumanAlarmTime("14:30"))
        assertEquals(0 to 0, AssistantTools.parseHumanAlarmTime("12:00 am"))
        assertEquals(12 to 0, AssistantTools.parseHumanAlarmTime("12 pm"))
        assertEquals(12 to 0, AssistantTools.parseHumanAlarmTime("noon"))
        assertEquals(0 to 0, AssistantTools.parseHumanAlarmTime("midnight"))
        assertNull(AssistantTools.parseHumanAlarmTime("tomorrow"))
    }

    @Test
    fun groundedTimer_parsesHoursMinutesSecondsAndLabel() {
        val json = AssistantTools.synthesizeGroundedNeedleJson("Set a timer for 1 hour 15 minutes and 30 seconds for workout")
        val response = AssistantTools.parseNeedleJson(json)
        val decision = AssistantTools.toDecision(response, "Set a timer for 1 hour 15 minutes and 30 seconds for workout")

        assertTrue(decision is AssistantDecision.SetTimer)
        val timer = decision as AssistantDecision.SetTimer
        assertEquals(3600 + 15 * 60 + 30, timer.durationSeconds)
        assertEquals("workout", timer.label)
    }

    @Test
    fun groundedAlarm_converts12HourAnd24HourCorrectly() {
        val morningJson = AssistantTools.synthesizeGroundedNeedleJson("Wake me up at 7:30 am for flight")
        val morningDecision = AssistantTools.toDecision(
            AssistantTools.parseNeedleJson(morningJson),
            "Wake me up at 7:30 am for flight"
        )
        assertTrue(morningDecision is AssistantDecision.SetAlarm)
        val morning = morningDecision as AssistantDecision.SetAlarm
        assertEquals(7, morning.hour24)
        assertEquals(30, morning.minute)
        assertEquals("flight", morning.label)

        val eveningJson = AssistantTools.synthesizeGroundedNeedleJson("Set an alarm for 6:45 pm")
        val eveningDecision = AssistantTools.toDecision(
            AssistantTools.parseNeedleJson(eveningJson),
            "Set an alarm for 6:45 pm"
        )
        assertTrue(eveningDecision is AssistantDecision.SetAlarm)
        val evening = eveningDecision as AssistantDecision.SetAlarm
        assertEquals(18, evening.hour24)
        assertEquals(45, evening.minute)

        assertEquals(0, AssistantTools.normalizeHour24(12, "am"))
        assertEquals(12, AssistantTools.normalizeHour24(12, "pm"))
    }

    @Test
    fun groundedTodo_extractsGoogleTasksTitle() {
        val queries = listOf(
            "Add a todo to buy oat milk and eggs" to "buy oat milk and eggs",
            "Create a task in Google Tasks to submit expense report" to "submit expense report",
            "Remind me to call Dr. Patel tomorrow" to "call Dr. Patel tomorrow"
        )

        for ((query, expectedTitle) in queries) {
            val json = AssistantTools.synthesizeGroundedNeedleJson(query)
            val decision = AssistantTools.toDecision(AssistantTools.parseNeedleJson(json), query)
            assertTrue("Expected CreateTodo for '$query', got $decision", decision is AssistantDecision.CreateTodo)
            val todo = decision as AssistantDecision.CreateTodo
            assertEquals(expectedTitle, todo.title)
        }
    }

    @Test
    fun unmatchedOrNegatedQueries_routeToGoogleGeminiFallback() {
        val unmatchedQueries = listOf(
            "Explain how transformers work in machine learning",
            "What's the weather like in Tokyo right now?",
            "Write a haiku about desert cacti",
            "Don't set a timer for 5 minutes"
        )

        for (query in unmatchedQueries) {
            val json = AssistantTools.synthesizeGroundedNeedleJson(query)
            val parsed = AssistantTools.parseNeedleJson(json)
            assertTrue("Expected empty function_calls for '$query'", parsed.functionCalls.isEmpty())

            val decision = AssistantTools.toDecision(parsed, query)
            assertTrue(
                "Expected FallbackToGemini for '$query', got $decision",
                decision is AssistantDecision.FallbackToGemini
            )
            assertEquals(query, (decision as AssistantDecision.FallbackToGemini).query)
        }
    }

    @Test
    fun ungroundedHallucinatedTimer_routesToGeminiInsteadOfSettingFalseTimer() {
        val hallucinatedJson = """
            {
              "type": "call",
              "success": true,
              "function_calls": [
                {"name": "set_timer", "arguments": {"time_human": "10 minutes", "label": "Pasta"}}
              ],
              "confidence": 0.45,
              "audio_text": "set a timer for the pasta"
            }
        """.trimIndent()

        val parsed = AssistantTools.parseNeedleJson(hallucinatedJson)
        val decision = AssistantTools.toDecision(parsed)
        assertTrue(decision is AssistantDecision.FallbackToGemini)
    }

    @Test
    fun lowConfidenceToolCall_routesToGoogleGeminiFallback() {
        val lowConfJson = """
            {
              "type": "call",
              "success": true,
              "function_calls": [
                {"name": "set_timer", "arguments": {"time_human": "60 seconds"}}
              ],
              "confidence": 0.11,
              "audio_text": "maybe sixty seconds or something"
            }
        """.trimIndent()

        val parsed = AssistantTools.parseNeedleJson(lowConfJson)
        val decision = AssistantTools.toDecision(parsed)
        assertTrue(decision is AssistantDecision.FallbackToGemini)
        assertEquals("maybe sixty seconds or something", decision.transcript)
    }

    @Test
    fun engineErrorJson_returnsExplicitErrorNotGeminiFallback() {
        val errorJson = """
            {"type":"error","success":false,"error":"cactus_init not called","error_code":-1,"function_calls":[],"confidence":0.0}
        """.trimIndent()

        val parsed = AssistantTools.parseNeedleJson(errorJson)
        val decision = AssistantTools.toDecision(parsed, "set a 10 minute timer")
        assertTrue(decision is AssistantDecision.Error)
        assertEquals("cactus_init not called", (decision as AssistantDecision.Error).message)
    }

    @Test
    fun parseWhistleJson_parsesTranscriptMetricsAndErrors() {
        val raw = """{"text":"set an alarm for 8 am","language":"en","ttft_ms":5.9,"decode_tps":1319.0}"""
        val transcript = AssistantTools.parseWhistleJson(raw)
        assertNotNull(transcript)
        assertEquals("set an alarm for 8 am", transcript.text)
        assertEquals("en", transcript.language)
        assertEquals(5.9, transcript.ttftMs, 1e-3)
        assertFalse(transcript.isStub)

        val errTranscript = AssistantTools.parseWhistleJson("""{"text":"","error":"no speech model"}""")
        assertEquals("no speech model", errTranscript.error)
    }
}

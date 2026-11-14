package com.cactus.assistant

import com.cactus.assistant.engine.AssistantDecision
import com.cactus.assistant.engine.CactusAssistantEngine
import com.cactus.assistant.engine.CactusNativeBridge
import com.cactus.assistant.engine.CactusRuntime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class CactusAssistantEngineTest {

    private class FakeCactusRuntime(
        private val completeAudioHandler: (FloatArray) -> String = {
            """{"type":"call","success":true,"stub":true,"function_calls":[],"confidence":0.0}"""
        },
        private val completeTextHandler: (String) -> String = {
            """{"type":"call","success":true,"stub":true,"function_calls":[],"confidence":0.0}"""
        },
        private val transcribeHandler: (FloatArray) -> String = {
            """{"text":"","language":"en","stub":true}"""
        },
        private val initRc: Int = 3,
        private val lastErr: String = "",
        private val stubMode: Boolean = false
    ) : CactusRuntime {
        override val isNativeLibraryLoaded: Boolean = true
        var lastSystemPrompt: String? = null
        var lastToolsJson: String? = null
        var audioKeywords: String? = null
        var initCount: Int = 0
        var resetCount: Int = 0
        var threadsSet: Int = -1

        override fun loadModel(path: String): Int =
            CactusNativeBridge.MODEL_BIT_NEEDLE3 or CactusNativeBridge.MODEL_BIT_WHISTLE

        override fun loadModelBytes(bytes: ByteArray): Int = 3
        override fun loadedModels(): Int = 3
        override fun lastError(): String = lastErr

        override fun init(systemPrompt: String?, toolsJson: String?, toolIndexPath: String?): Int {
            initCount++
            lastSystemPrompt = systemPrompt
            lastToolsJson = toolsJson
            return initRc
        }

        override fun setAudio(language: String?, keywords: String?, wordTimestamps: Boolean) {
            audioKeywords = keywords
        }

        override fun setThreads(threads: Int) {
            threadsSet = threads
        }

        override fun transcribe(
            pcm16kMono: FloatArray,
            language: String?,
            keywords: String?,
            wordTimestamps: Boolean
        ): String = transcribeHandler(pcm16kMono)

        override fun completeText(input: String, maxNewTokens: Int): String =
            completeTextHandler(input)

        override fun completeAudio(pcm16kMono: FloatArray, maxNewTokens: Int): String =
            completeAudioHandler(pcm16kMono)

        override fun reset() {
            resetCount++
        }

        override fun isUsingStub(): Boolean = stubMode
    }

    @Test
    fun processAudio_usesUnifiedWhistleAndNeedle3SingleCallWithVerbatimTimeHuman() = runTest {
        val fakeRuntime = FakeCactusRuntime(
            completeAudioHandler = {
                """
                {
                  "type": "call",
                  "success": true,
                  "function_calls": [
                    {"name": "set_alarm", "arguments": {"time_human": "6.30 a.m.", "label": "run"}}
                  ],
                  "confidence": 0.97,
                  "audio_text": "set an alarm for 6.30 a.m. for run",
                  "audio_language": "en"
                }
                """.trimIndent()
            }
        )

        val engine = CactusAssistantEngine(context = null, runtime = fakeRuntime)
        val nonSilentPcm = FloatArray(16_000) { i -> if (i % 2 == 0) 0.25f else -0.25f }

        val decision = engine.processAudio(nonSilentPcm)
        assertTrue(decision is AssistantDecision.SetAlarm)
        val alarm = decision as AssistantDecision.SetAlarm
        assertEquals(6, alarm.hour24)
        assertEquals(30, alarm.minute)
        assertEquals("run", alarm.label)
        assertEquals("set an alarm for 6.30 a.m. for run", alarm.transcript)
        assertTrue(fakeRuntime.lastSystemPrompt?.startsWith("date: ") == true)
        assertTrue(fakeRuntime.audioKeywords?.contains("timer") == true)
        assertEquals(4, fakeRuntime.threadsSet)
    }

    @Test
    fun processAudio_returnsEmptyAudioOnSilence() = runTest {
        val fakeRuntime = FakeCactusRuntime()
        val engine = CactusAssistantEngine(context = null, runtime = fakeRuntime)
        val silentPcm = FloatArray(8_000) { 0.0f }

        val decision = engine.processAudio(silentPcm)
        assertTrue(decision is AssistantDecision.EmptyAudio)
    }

    @Test
    fun processAudio_fallsBackToTwoStepWhenCompleteAudioReturnsError() = runTest {
        val fakeRuntime = FakeCactusRuntime(
            completeAudioHandler = {
                """{"type":"error","success":false,"error":"audio combined busy","function_calls":[],"confidence":0.0}"""
            },
            transcribeHandler = {
                """{"text":"set a 10 minute timer","language":"en"}"""
            }
        )

        val engine = CactusAssistantEngine(context = null, runtime = fakeRuntime)
        val nonSilentPcm = FloatArray(8_000) { 0.01f }

        val decision = engine.processAudio(nonSilentPcm)
        assertTrue(decision is AssistantDecision.SetTimer)
        assertEquals(600, (decision as AssistantDecision.SetTimer).durationSeconds)
    }

    @Test
    fun processText_routesTimerAlarmTodoAndGeminiFallbackEndToEndAndReusesInit() = runTest {
        val fakeRuntime = FakeCactusRuntime(stubMode = true)
        val engine = CactusAssistantEngine(context = null, runtime = fakeRuntime)

        val timerDecision = engine.processText("Set a timer for 5 minutes")
        assertTrue(timerDecision is AssistantDecision.SetTimer)
        assertEquals(300, (timerDecision as AssistantDecision.SetTimer).durationSeconds)

        val alarmDecision = engine.processText("Set an alarm for 8:15 pm")
        assertTrue(alarmDecision is AssistantDecision.SetAlarm)
        assertEquals(20, (alarmDecision as AssistantDecision.SetAlarm).hour24)
        assertEquals(15, alarmDecision.minute)

        val todoDecision = engine.processText("Add a todo to renew passport")
        assertTrue(todoDecision is AssistantDecision.CreateTodo)
        assertEquals("renew passport", (todoDecision as AssistantDecision.CreateTodo).title)

        val geminiDecision = engine.processText("Summarize the history of the Roman Empire")
        assertTrue(geminiDecision is AssistantDecision.FallbackToGemini)
        assertEquals(
            "Summarize the history of the Roman Empire",
            (geminiDecision as AssistantDecision.FallbackToGemini).query
        )

        // init() runs once per day, reset() runs before every turn
        assertEquals(1, fakeRuntime.initCount)
        assertEquals(4, fakeRuntime.resetCount)
    }

    @Test
    fun normalizePcmForWhistle_boostsQuietSpeechWithoutModifyingNormalOrSilentAudio() {
        val engine = CactusAssistantEngine(context = null, runtime = FakeCactusRuntime())

        val silent = FloatArray(100) { 0.00001f }
        assertSame(silent, engine.normalizePcmForWhistle(silent))

        val normal = FloatArray(100) { if (it % 2 == 0) 0.4f else -0.4f }
        assertSame(normal, engine.normalizePcmForWhistle(normal))

        val quiet = FloatArray(100) { if (it % 2 == 0) 0.01f else -0.01f }
        val boosted = engine.normalizePcmForWhistle(quiet)
        val maxBoosted = boosted.maxOf { abs(it) }
        assertTrue("Expected boosted peak around 0.20, was $maxBoosted", maxBoosted in 0.15f..0.6f)
    }

    @Test
    fun initFailure_surfacesEngineErrorInsteadOfGeminiFallback() = runTest {
        val fakeRuntime = FakeCactusRuntime(
            initRc = -1,
            lastErr = "invalid tool schema",
            completeTextHandler = {
                """{"type":"error","success":false,"error":"cactus_init not called","function_calls":[]}"""
            }
        )
        val engine = CactusAssistantEngine(context = null, runtime = fakeRuntime)
        val status = engine.initializeIfNeeded()
        assertTrue(status.lastError?.contains("invalid tool schema") == true)

        val decision = engine.processText("set a 10 minute timer")
        assertTrue(decision is AssistantDecision.Error)
    }
}

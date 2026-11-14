package com.cactus.assistant.engine

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/**
 * Status snapshot of the on-device Cactus Compute runtime and `.cact` model weights.
 */
data class EngineStatus(
    val isInitialized: Boolean = false,
    val isNativeLoaded: Boolean = false,
    val isUsingStub: Boolean = true,
    val whistleWeightsPresent: Boolean = false,
    val needle3WeightsPresent: Boolean = false,
    val loadedModelsBitmask: Int = 0,
    val whistlePath: String = "",
    val needle3Path: String = "",
    val initOk: Boolean = false,
    val lastError: String? = null,
    val statusMessage: String = "Not initialized"
) {
    val isWhistleLoaded: Boolean
        get() = (loadedModelsBitmask and CactusNativeBridge.MODEL_BIT_WHISTLE) != 0

    val isNeedle3Loaded: Boolean
        get() = (loadedModelsBitmask and CactusNativeBridge.MODEL_BIT_NEEDLE3) != 0
}

/**
 * Coordinates Cactus Compute's Whistle (`whistle.cact`) speech-to-text model and
 * Needle 3 (`needle3.cact`) tool-calling model on-device.
 */
class CactusAssistantEngine(
    private val context: Context?,
    private val runtime: CactusRuntime = CactusNativeBridge
) {
    private val mutex = Mutex()

    @Volatile
    private var status: EngineStatus = EngineStatus(
        isNativeLoaded = runtime.isNativeLibraryLoaded,
        isUsingStub = runtime.isUsingStub()
    )

    @Volatile
    private var initializedDateKey: String? = null

    fun getStatus(): EngineStatus = status

    /**
     * Extracts `whistle.cact` and `needle3.cact` from APK `assets/models/` (if bundled)
     * into `filesDir/models/` so they can be `mmap`-ed in place via `cactus_load`,
     * then initializes Needle 3 with our tool schemas and environment facts.
     */
    suspend fun initializeIfNeeded(): EngineStatus = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (status.isInitialized && (status.isUsingStub || status.initOk)) {
                return@withLock status
            }
            initializeLocked()
        }
    }

    private fun initializeLocked(): EngineStatus {
        if (!runtime.isNativeLibraryLoaded) {
            status = EngineStatus(
                isInitialized = true,
                isNativeLoaded = false,
                isUsingStub = true,
                whistleWeightsPresent = false,
                needle3WeightsPresent = false,
                loadedModelsBitmask = 0,
                initOk = true,
                lastError = null,
                statusMessage = "JNI library not loaded (JVM fallback active)"
            )
            return status
        }

        val modelsDir = context?.filesDir?.let { File(it, "models") }
        modelsDir?.mkdirs()

        val whistleFile = modelsDir?.let { stageModelFile(it, WHISTLE_FILENAME) }
        val needle3File = modelsDir?.let { stageModelFile(it, NEEDLE3_FILENAME) }

        val whistleExists = whistleFile?.exists() == true && whistleFile.length() > 0L
        val needleExists = needle3File?.exists() == true && needle3File.length() > 0L

        var loadError: String? = null

        if (whistleFile != null && whistleExists) {
            val rc = runtime.loadModel(whistleFile.absolutePath)
            if (rc < 0) {
                val err = runtime.lastError().ifBlank { "code $rc" }
                Log.e(TAG, "cactus_load(whistle.cact) failed ($rc): $err")
                loadError = "Whistle load failed ($rc): $err"
            }
        }

        if (needle3File != null && needleExists) {
            val rc = runtime.loadModel(needle3File.absolutePath)
            if (rc < 0) {
                val err = runtime.lastError().ifBlank { "code $rc" }
                Log.e(TAG, "cactus_load(needle3.cact) failed ($rc): $err")
                loadError = listOfNotNull(loadError, "Needle 3 load failed ($rc): $err").joinToString("; ")
            }
        }

        runtime.setThreads(DEFAULT_ENGINE_THREADS)

        val dateKey = currentDateKey()
        val systemFacts = buildSystemFacts()
        val initRc = runtime.init(
            systemPrompt = systemFacts,
            toolsJson = AssistantTools.TOOLS_JSON,
            toolIndexPath = null
        )
        if (initRc < 0) {
            val err = runtime.lastError().ifBlank { "code $initRc" }
            Log.e(TAG, "cactus_init failed ($initRc): $err")
            loadError = listOfNotNull(loadError, "cactus_init failed ($initRc): $err").joinToString("; ")
        } else {
            initializedDateKey = dateKey
        }

        runtime.setAudio(
            language = "en",
            keywords = AssistantTools.WHISTLE_KEYWORDS,
            wordTimestamps = false
        )

        val bitmask = runtime.loadedModels()
        val bothLoaded = (bitmask and CactusNativeBridge.MODEL_BIT_WHISTLE) != 0 &&
            (bitmask and CactusNativeBridge.MODEL_BIT_NEEDLE3) != 0
        val usingStub = runtime.isUsingStub() ||
            (context != null && (!whistleExists || !needleExists)) ||
            (context == null && !bothLoaded)
        val nativeReady = loadError == null &&
            initRc >= 0 &&
            (usingStub || bothLoaded)

        val msg = when {
            loadError != null ->
                "Engine error: $loadError"
            whistleExists && needleExists && !runtime.isUsingStub() ->
                "Whistle + Needle 3 active on-device (models=$bitmask, promptTokens=$initRc)"
            else ->
                "Engine initialized (run ./scripts/download_cactus.sh to bundle .cact weights)"
        }

        status = EngineStatus(
            isInitialized = true,
            isNativeLoaded = runtime.isNativeLibraryLoaded,
            isUsingStub = usingStub,
            whistleWeightsPresent = whistleExists,
            needle3WeightsPresent = needleExists,
            loadedModelsBitmask = bitmask,
            whistlePath = whistleFile?.absolutePath.orEmpty(),
            needle3Path = needle3File?.absolutePath.orEmpty(),
            initOk = nativeReady,
            lastError = loadError,
            statusMessage = msg
        )
        return status
    }

    /**
     * Transcribe 16 kHz mono float32 PCM samples using Whistle (`cactus_transcribe`).
     */
    suspend fun transcribeOnly(
        pcm16kMono: FloatArray,
        language: String = "en"
    ): WhistleTranscript = withContext(Dispatchers.IO) {
        initializeIfNeeded()
        if (isSilent(pcm16kMono)) {
            return@withContext WhistleTranscript(text = "", language = language)
        }
        val prepared = normalizePcmForWhistle(pcm16kMono)
        mutex.withLock {
            val rawJson = runtime.transcribe(
                pcm16kMono = prepared,
                language = language,
                keywords = AssistantTools.WHISTLE_KEYWORDS,
                wordTimestamps = false
            )
            AssistantTools.parseWhistleJson(rawJson)
        }
    }

    /**
     * Full voice-to-tool-call pipeline:
     * 1. Checks silence gate on 16 kHz mono PCM audio and boosts quiet microphone captures cleanly.
     * 2. Uses Cactus Compute's unified `cactus_complete` audio path (Whistle + Needle 3 in one call)
     *    with keyword biasing configured via `cactus_set_audio`.
     * 3. If unified audio fails, returns empty transcript, or is running in stub mode, falls back
     *    to explicit two-step Whistle `transcribe` -> Needle 3 `completeText`, surfacing real native
     *    errors instead of silently collapsing them to empty audio or Gemini fallback.
     */
    suspend fun processAudio(
        pcm16kMono: FloatArray,
        fallbackTranscriptHint: String = ""
    ): AssistantDecision = withContext(Dispatchers.IO) {
        val currentStatus = initializeIfNeeded()
        if (isSilent(pcm16kMono) && fallbackTranscriptHint.isBlank()) {
            return@withContext AssistantDecision.EmptyAudio("Silence detected on 16 kHz audio")
        }

        val preparedPcm = normalizePcmForWhistle(pcm16kMono)

        if (!currentStatus.isUsingStub) {
            if (!currentStatus.initOk && currentStatus.lastError != null) {
                return@withContext AssistantDecision.Error(currentStatus.lastError)
            }

            val combinedResponse = mutex.withLock {
                ensureCurrentDayInitializedLocked()
                runtime.setAudio(
                    language = "en",
                    keywords = AssistantTools.WHISTLE_KEYWORDS,
                    wordTimestamps = false
                )
                runtime.reset()
                val raw = runtime.completeAudio(preparedPcm, MAX_NEW_TOKENS)
                AssistantTools.parseNeedleJson(raw)
            }

            if (!combinedResponse.isStub &&
                combinedResponse.success &&
                combinedResponse.error.isNullOrBlank() &&
                (!combinedResponse.audioText.isNullOrBlank() || combinedResponse.functionCalls.isNotEmpty())
            ) {
                return@withContext AssistantTools.toDecision(
                    response = combinedResponse,
                    fallbackTranscript = fallbackTranscriptHint
                )
            }

            if (!combinedResponse.success || !combinedResponse.error.isNullOrBlank()) {
                Log.w(TAG, "completeAudio failed (${combinedResponse.error}); trying two-step Whistle -> Needle 3")
            }
        }

        // Two-step path: transcribe with Whistle first, then pass transcript to Needle 3
        val whistle = transcribeOnly(preparedPcm)
        if (!whistle.error.isNullOrBlank() && whistle.text.isBlank() && fallbackTranscriptHint.isBlank()) {
            return@withContext AssistantDecision.Error("Whistle transcription failed: ${whistle.error}")
        }

        val transcriptText = whistle.text.ifBlank { fallbackTranscriptHint.trim() }
        if (transcriptText.isBlank()) {
            return@withContext AssistantDecision.EmptyAudio("Whistle returned empty transcript")
        }
        processText(transcriptText)
    }

    /**
     * Turn a text transcript into a tool call (`set_timer`, `set_alarm`, `create_todo`)
     * or a Gemini fallback (`FallbackToGemini`) using Needle 3 (`cactus_complete`).
     */
    suspend fun processText(query: String): AssistantDecision = withContext(Dispatchers.IO) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return@withContext AssistantDecision.EmptyAudio("Empty query")
        }

        val currentStatus = initializeIfNeeded()

        val needleResponse = mutex.withLock {
            if (!currentStatus.isUsingStub && !currentStatus.initOk && currentStatus.lastError != null) {
                return@withLock NeedleResponse(
                    type = "error",
                    success = false,
                    functionCalls = emptyList(),
                    error = currentStatus.lastError
                )
            }

            ensureCurrentDayInitializedLocked()
            runtime.reset()

            val rawJson = runtime.completeText(trimmed, MAX_NEW_TOKENS)
            val parsed = AssistantTools.parseNeedleJson(rawJson)
            if (parsed.isStub || (!status.needle3WeightsPresent && !status.isNeedle3Loaded)) {
                val groundedJson = AssistantTools.synthesizeGroundedNeedleJson(trimmed)
                AssistantTools.parseNeedleJson(groundedJson)
            } else {
                parsed
            }
        }

        AssistantTools.toDecision(needleResponse, fallbackTranscript = trimmed)
    }

    private fun ensureCurrentDayInitializedLocked() {
        val today = currentDateKey()
        if (initializedDateKey != today) {
            val rc = runtime.init(
                systemPrompt = buildSystemFacts(),
                toolsJson = AssistantTools.TOOLS_JSON,
                toolIndexPath = null
            )
            if (rc >= 0) {
                initializedDateKey = today
            }
        }
    }

    /**
     * Normalize very quiet 16 kHz microphone captures while leaving normal-level speech untouched
     * and preserving true digital silence.
     */
    fun normalizePcmForWhistle(pcm: FloatArray): FloatArray {
        if (pcm.isEmpty()) return pcm
        var peak = 0f
        for (sample in pcm) {
            val a = abs(sample)
            if (a > peak) peak = a
        }
        if (peak < SILENCE_PEAK_THRESHOLD || peak >= NORMALIZE_MIN_PEAK) {
            return pcm
        }
        val gain = (NORMALIZE_TARGET_PEAK / peak).coerceAtMost(MAX_NORMALIZE_GAIN)
        val out = FloatArray(pcm.size)
        for (i in pcm.indices) {
            out[i] = (pcm[i] * gain).coerceIn(-1.0f, 1.0f)
        }
        return out
    }

    private fun isSilent(pcm: FloatArray): Boolean {
        if (pcm.isEmpty()) return true
        var peak = 0f
        for (sample in pcm) {
            val a = abs(sample)
            if (a > peak) peak = a
        }
        return peak < SILENCE_PEAK_THRESHOLD
    }

    private fun currentDateKey(now: Date = Date()): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
    }

    /**
     * Build Needle 3 system facts string:
     * `date: 2026-11-13 Fri 08:15; locale: en-US; device: phone; <instructions>`
     */
    fun buildSystemFacts(now: Date = Date()): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd EEE HH:mm", Locale.US)
        val dateStr = formatter.format(now)
        val localeStr = Locale.getDefault().toLanguageTag().ifEmpty { "en-US" }
        return "date: $dateStr; locale: $localeStr; device: phone; ${AssistantTools.SYSTEM_INSTRUCTIONS}"
    }

    private fun stageModelFile(modelsDir: File, fileName: String): File {
        val target = File(modelsDir, fileName)
        val assetPath = "models/$fileName"
        val assetManager = context?.assets ?: return target

        val expectedSize = try {
            assetManager.openFd(assetPath).use { it.length }
        } catch (_: Exception) {
            -1L
        }

        if (target.exists() && target.length() > 0L) {
            if (expectedSize <= 0L || target.length() == expectedSize) {
                return target
            }
            Log.w(TAG, "Re-staging $fileName (cached ${target.length()} bytes != asset $expectedSize bytes)")
        }

        val tmpTarget = File(modelsDir, "$fileName.tmp")
        return try {
            assetManager.open(assetPath).use { input ->
                FileOutputStream(tmpTarget).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            }
            if (target.exists()) target.delete()
            if (!tmpTarget.renameTo(target)) {
                tmpTarget.copyTo(target, overwrite = true)
                tmpTarget.delete()
            }
            target
        } catch (e: Exception) {
            Log.w(TAG, "Asset $assetPath not bundled or failed to stage: ${e.message}")
            tmpTarget.delete()
            target
        }
    }

    companion object {
        private const val TAG = "CactusAssistantEngine"
        const val WHISTLE_FILENAME = "whistle.cact"
        const val NEEDLE3_FILENAME = "needle3.cact"
        private const val MAX_NEW_TOKENS = 512
        private const val DEFAULT_ENGINE_THREADS = 4
        private const val SILENCE_PEAK_THRESHOLD = 1e-4f
        private const val NORMALIZE_MIN_PEAK = 0.25f
        private const val NORMALIZE_TARGET_PEAK = 0.55f
        private const val MAX_NORMALIZE_GAIN = 20.0f
    }
}

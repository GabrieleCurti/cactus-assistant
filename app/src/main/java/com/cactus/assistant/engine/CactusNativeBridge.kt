package com.cactus.assistant.engine

import android.util.Log

/**
 * Abstraction over the Cactus Compute native C++ engine (`cactus.h`), allowing both
 * real JNI execution on `android-arm64` devices and deterministic testing on the JVM.
 */
interface CactusRuntime {
    val isNativeLibraryLoaded: Boolean
    fun loadModel(path: String): Int
    fun loadModelBytes(bytes: ByteArray): Int
    fun loadedModels(): Int
    fun lastError(): String
    fun init(systemPrompt: String?, toolsJson: String?, toolIndexPath: String? = null): Int
    fun setAudio(language: String?, keywords: String?, wordTimestamps: Boolean = false)
    fun setThreads(threads: Int = 0)
    fun transcribe(
        pcm16kMono: FloatArray,
        language: String? = "en",
        keywords: String? = null,
        wordTimestamps: Boolean = false
    ): String
    fun completeText(input: String, maxNewTokens: Int = 512): String
    fun completeAudio(pcm16kMono: FloatArray, maxNewTokens: Int = 512): String
    fun reset()
    fun isUsingStub(): Boolean
}

/**
 * JNI bridge to `libcactus_jni.so` which links against Cactus Compute's `libcactus.a`
 * (`android-arm64`) and calls `cactus_load`, `cactus_init`, `cactus_transcribe`,
 * and `cactus_complete`.
 */
object CactusNativeBridge : CactusRuntime {
    private const val TAG = "CactusNativeBridge"

    override val isNativeLibraryLoaded: Boolean = try {
        System.loadLibrary("cactus_jni")
        true
    } catch (e: UnsatisfiedLinkError) {
        Log.w(TAG, "libcactus_jni.so not loaded in this runtime (${e.message}); using JVM fallback")
        false
    } catch (e: SecurityException) {
        Log.w(TAG, "SecurityException loading libcactus_jni.so: ${e.message}")
        false
    }

    override fun loadModel(path: String): Int {
        if (!isNativeLibraryLoaded) return -1
        return nativeLoadModel(path)
    }

    override fun loadModelBytes(bytes: ByteArray): Int {
        if (!isNativeLibraryLoaded) return -1
        return nativeLoadModelBytes(bytes)
    }

    override fun loadedModels(): Int {
        if (!isNativeLibraryLoaded) return 0
        return nativeLoadedModels()
    }

    override fun lastError(): String {
        if (!isNativeLibraryLoaded) return "Native library not loaded"
        return nativeLastError()
    }

    override fun init(systemPrompt: String?, toolsJson: String?, toolIndexPath: String?): Int {
        if (!isNativeLibraryLoaded) return -1
        return nativeInit(systemPrompt, toolsJson, toolIndexPath)
    }

    override fun setAudio(language: String?, keywords: String?, wordTimestamps: Boolean) {
        if (!isNativeLibraryLoaded) return
        nativeSetAudio(language, keywords, wordTimestamps)
    }

    override fun setThreads(threads: Int) {
        if (!isNativeLibraryLoaded) return
        nativeSetThreads(threads)
    }

    override fun transcribe(
        pcm16kMono: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean
    ): String {
        if (!isNativeLibraryLoaded) {
            return """{"text":"","language":"en","stub":true}"""
        }
        return nativeTranscribe(pcm16kMono, language, keywords, wordTimestamps)
    }

    override fun completeText(input: String, maxNewTokens: Int): String {
        if (!isNativeLibraryLoaded) {
            return """{"type":"call","success":true,"stub":true,"function_calls":[],"confidence":0.0}"""
        }
        return nativeCompleteText(input, maxNewTokens)
    }

    override fun completeAudio(pcm16kMono: FloatArray, maxNewTokens: Int): String {
        if (!isNativeLibraryLoaded) {
            return """{"type":"call","success":true,"stub":true,"function_calls":[],"confidence":0.0,"audio_text":"","audio_language":"en"}"""
        }
        return nativeCompleteAudio(pcm16kMono, maxNewTokens)
    }

    override fun reset() {
        if (!isNativeLibraryLoaded) return
        nativeReset()
    }

    override fun isUsingStub(): Boolean {
        if (!isNativeLibraryLoaded) return true
        return nativeIsUsingStub()
    }

    @JvmStatic
    private external fun nativeLoadModel(path: String): Int

    @JvmStatic
    private external fun nativeLoadModelBytes(bytes: ByteArray): Int

    @JvmStatic
    private external fun nativeLoadedModels(): Int

    @JvmStatic
    private external fun nativeLastError(): String

    @JvmStatic
    private external fun nativeInit(
        systemPrompt: String?,
        toolsJson: String?,
        toolIndexPath: String?
    ): Int

    @JvmStatic
    private external fun nativeSetAudio(
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean
    )

    @JvmStatic
    private external fun nativeSetThreads(threads: Int)

    @JvmStatic
    private external fun nativeTranscribe(
        pcm16kMono: FloatArray,
        language: String?,
        keywords: String?,
        wordTimestamps: Boolean
    ): String

    @JvmStatic
    private external fun nativeCompleteText(
        input: String,
        maxNewTokens: Int
    ): String

    @JvmStatic
    private external fun nativeCompleteAudio(
        pcm16kMono: FloatArray,
        maxNewTokens: Int
    ): String

    @JvmStatic
    private external fun nativeReset()

    @JvmStatic
    private external fun nativeIsUsingStub(): Boolean

    const val MODEL_BIT_NEEDLE3 = 1
    const val MODEL_BIT_WHISTLE = 2
    const val MODEL_BIT_MIRAGE = 4
}

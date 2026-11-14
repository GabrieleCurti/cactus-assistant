package com.cactus.assistant.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detailed result of capturing a 16 kHz mono microphone utterance.
 */
data class AudioCaptureResult(
    val samples: FloatArray,
    val peakAmplitude: Float,
    val rmsAmplitude: Float,
    val speechDetected: Boolean,
    val audioSource: Int = -1,
    val errorMessage: String? = null
) {
    val isUsable: Boolean
        get() = errorMessage == null && samples.isNotEmpty() && peakAmplitude >= 1e-4f
}

/**
 * Captures 16 kHz mono PCM audio from the Android microphone for Cactus Compute's Whistle model.
 *
 * Improvements over raw `VOICE_RECOGNITION` capture:
 * - Tries `AudioSource.MIC` (which keeps device AGC enabled on OEMs where `VOICE_RECOGNITION`
 *   produces very quiet audio), falling back to `VOICE_RECOGNITION` and `DEFAULT`.
 * - Uses adaptive noise-floor calibration during the first 150 ms so soft speech (`rms >= 0.003f`)
 *   triggers reliable end-of-speech detection instead of timing out as silence.
 * - Handles `AudioRecord.read` errors/zero reads cleanly without spinning in an infinite loop.
 */
class WhistleAudioRecorder {

    @Volatile
    private var isRecording: Boolean = false

    fun stop() {
        isRecording = false
    }

    suspend fun recordUtterance(
        maxDurationMs: Long = 15_000L,
        trailingSilenceMs: Long = 1_100L,
        onAmplitude: (Float) -> Unit = {}
    ): FloatArray {
        return recordUtteranceDetailed(maxDurationMs, trailingSilenceMs, onAmplitude).samples
    }

    /**
     * Records 16 kHz mono audio until either:
     * - [stop] is called,
     * - the user has spoken and then remained silent for [trailingSilenceMs],
     * - or [maxDurationMs] (default 15s, max 30s for Whistle single pass) elapses.
     */
    @SuppressLint("MissingPermission")
    suspend fun recordUtteranceDetailed(
        maxDurationMs: Long = 15_000L,
        trailingSilenceMs: Long = 1_100L,
        onAmplitude: (Float) -> Unit = {}
    ): AudioCaptureResult = withContext(Dispatchers.IO) {
        val rawMinBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (rawMinBuf <= 0) {
            return@withContext AudioCaptureResult(
                samples = FloatArray(0),
                peakAmplitude = 0f,
                rmsAmplitude = 0f,
                speechDetected = false,
                errorMessage = "Device reported unsupported 16 kHz mono AudioRecord configuration ($rawMinBuf)"
            )
        }
        val minBufBytes = rawMinBuf.coerceAtLeast(SAMPLE_RATE_HZ / 5 * 2)

        val candidateSources = intArrayOf(
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.DEFAULT
        )

        var recorder: AudioRecord? = null
        var selectedSource = -1
        var lastSecurityError: SecurityException? = null

        for (source in candidateSources) {
            val attempt = try {
                AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBufBytes * 2
                )
            } catch (se: SecurityException) {
                lastSecurityError = se
                null
            } catch (_: Exception) {
                null
            }

            if (attempt != null && attempt.state == AudioRecord.STATE_INITIALIZED) {
                recorder = attempt
                selectedSource = source
                break
            } else {
                attempt?.release()
            }
        }

        if (recorder == null) {
            val errMsg = if (lastSecurityError != null) {
                "Microphone permission (RECORD_AUDIO) not granted"
            } else {
                "Could not initialize Android AudioRecord on any microphone source"
            }
            return@withContext AudioCaptureResult(
                samples = FloatArray(0),
                peakAmplitude = 0f,
                rmsAmplitude = 0f,
                speechDetected = false,
                errorMessage = errMsg
            )
        }

        val maxSamples = ((maxDurationMs.coerceAtMost(30_000L) * SAMPLE_RATE_HZ) / 1000L).toInt()
        val captured = FloatArray(maxSamples)
        var capturedCount = 0

        val frameSamples = SAMPLE_RATE_HZ / 20 // 50 ms chunks (800 samples)
        val shortBuffer = ShortArray(frameSamples)

        var speechDetected = false
        var silenceAccumulatedMs = 0L
        var noiseFloorRms = 0f
        var calibrationFrames = 0
        var overallPeak = 0f
        var totalSumSquares = 0.0
        var consecutiveReadFailures = 0
        var captureError: String? = null
        val startMs = System.currentTimeMillis()

        isRecording = true
        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                captureError = "Microphone stream unavailable (another app or privacy switch may be holding it)"
                isRecording = false
            }

            while (isActive && isRecording && capturedCount < maxSamples) {
                val read = recorder.read(shortBuffer, 0, frameSamples)
                if (read <= 0) {
                    consecutiveReadFailures++
                    if (read < 0) {
                        if (consecutiveReadFailures >= MAX_CONSECUTIVE_READ_FAILURES) {
                            captureError = "AudioRecord read failed (code $read)"
                            break
                        }
                    } else if (System.currentTimeMillis() - startMs >= INITIAL_NO_SPEECH_TIMEOUT_MS) {
                        captureError = "Microphone produced 0 audio samples"
                        break
                    }
                    continue
                }
                consecutiveReadFailures = 0

                var frameSumSquares = 0.0
                val copyCount = read.coerceAtMost(maxSamples - capturedCount)
                for (i in 0 until copyCount) {
                    val normalized = (shortBuffer[i] / 32768.0f).coerceIn(-1.0f, 1.0f)
                    captured[capturedCount++] = normalized
                    val absVal = abs(normalized)
                    if (absVal > overallPeak) overallPeak = absVal
                    frameSumSquares += normalized * normalized
                }
                totalSumSquares += frameSumSquares

                val rms = sqrt(frameSumSquares / copyCount).toFloat()
                onAmplitude(rms)

                // Calibrate initial ambient floor during the first ~150 ms (3 frames)
                if (calibrationFrames < NOISE_CALIBRATION_FRAMES) {
                    noiseFloorRms = if (calibrationFrames == 0) rms else (noiseFloorRms + rms) * 0.5f
                    calibrationFrames++
                }

                val adaptiveThreshold = max(SPEECH_RMS_THRESHOLD, noiseFloorRms * 2.5f).coerceAtMost(0.03f)
                val chunkDurationMs = (copyCount * 1000L) / SAMPLE_RATE_HZ

                if (rms >= adaptiveThreshold) {
                    speechDetected = true
                    silenceAccumulatedMs = 0L
                } else if (speechDetected) {
                    silenceAccumulatedMs += chunkDurationMs
                    if (silenceAccumulatedMs >= trailingSilenceMs) {
                        break
                    }
                } else if (System.currentTimeMillis() - startMs >= INITIAL_NO_SPEECH_TIMEOUT_MS) {
                    break
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (se: SecurityException) {
            captureError = "Microphone permission (RECORD_AUDIO) denied: ${se.message}"
        } catch (e: Exception) {
            captureError = "Microphone capture error: ${e.message}"
        } finally {
            isRecording = false
            try {
                recorder.stop()
            } catch (_: Exception) {
            }
            recorder.release()
        }

        val overallRms = if (capturedCount > 0) sqrt(totalSumSquares / capturedCount).toFloat() else 0f
        AudioCaptureResult(
            samples = captured.copyOf(capturedCount),
            peakAmplitude = overallPeak,
            rmsAmplitude = overallRms,
            speechDetected = speechDetected,
            audioSource = selectedSource,
            errorMessage = captureError
        )
    }

    companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val MAX_WHISTLE_PASS_SAMPLES = 480_000 // 30 seconds at 16 kHz
        private const val SPEECH_RMS_THRESHOLD = 0.003f
        private const val NOISE_CALIBRATION_FRAMES = 3
        private const val INITIAL_NO_SPEECH_TIMEOUT_MS = 7_000L
        private const val MAX_CONSECUTIVE_READ_FAILURES = 10
    }
}

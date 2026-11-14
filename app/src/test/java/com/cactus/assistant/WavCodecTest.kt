package com.cactus.assistant

import com.cactus.assistant.audio.WavCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.sin

class WavCodecTest {

    @Test
    fun encodeAndDecode16kMonoWav_preservesAudioSamples() {
        val numSamples = 1600 // 100 ms at 16 kHz
        val original = FloatArray(numSamples) { i ->
            (0.5 * sin(2.0 * Math.PI * 440.0 * i / WavCodec.SAMPLE_RATE_HZ)).toFloat()
        }

        val wavBytes = WavCodec.encode16kMonoWav(original)
        assertEquals(44 + numSamples * 2, wavBytes.size)
        assertEquals("RIFF", String(wavBytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(wavBytes, 8, 4, Charsets.US_ASCII))

        val decoded = WavCodec.decode16kMonoWav(wavBytes)
        assertEquals(numSamples, decoded.size)

        for (i in original.indices) {
            assertTrue(
                "Sample $i differed by more than 16-bit quantization step",
                abs(original[i] - decoded[i]) < 1e-3f
            )
        }
    }
}

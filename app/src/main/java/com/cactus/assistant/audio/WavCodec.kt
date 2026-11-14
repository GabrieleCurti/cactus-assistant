package com.cactus.assistant.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility to encode and decode 16 kHz mono 16-bit PCM WAV files for Whistle (`clip.wav`).
 */
object WavCodec {

    const val SAMPLE_RATE_HZ = 16_000

    /**
     * Encode 16 kHz mono float32 samples in [-1.0, 1.0] into a standard 44-byte RIFF WAV byte array.
     */
    fun encode16kMonoWav(samples: FloatArray): ByteArray {
        val dataSize = samples.size * 2
        val totalSize = 36 + dataSize
        val buffer = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)

        // RIFF header
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(totalSize)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))

        // fmt subchunk
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16) // PCM chunk size
        buffer.putShort(1) // AudioFormat = 1 (PCM)
        buffer.putShort(1) // NumChannels = 1 (Mono)
        buffer.putInt(SAMPLE_RATE_HZ) // SampleRate = 16000
        buffer.putInt(SAMPLE_RATE_HZ * 2) // ByteRate = 32000
        buffer.putShort(2) // BlockAlign = 2
        buffer.putShort(16) // BitsPerSample = 16

        // data subchunk
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataSize)

        for (sample in samples) {
            val clamped = sample.coerceIn(-1.0f, 1.0f)
            val pcm16 = (clamped * 32767.0f).toInt().toShort()
            buffer.putShort(pcm16)
        }

        return buffer.array()
    }

    /**
     * Decode a 16-bit mono PCM WAV byte array into normalized float32 samples in [-1.0, 1.0].
     */
    fun decode16kMonoWav(wavBytes: ByteArray): FloatArray {
        if (wavBytes.size <= 44) return FloatArray(0)
        val buf = ByteBuffer.wrap(wavBytes).order(ByteOrder.LITTLE_ENDIAN)

        var offset = 12
        var dataOffset = 44
        var dataLength = wavBytes.size - 44

        while (offset + 8 <= wavBytes.size) {
            val chunkId = String(wavBytes, offset, 4, Charsets.US_ASCII)
            val chunkSize = buf.getInt(offset + 4)
            if (chunkId == "data") {
                dataOffset = offset + 8
                dataLength = chunkSize.coerceAtMost(wavBytes.size - dataOffset)
                break
            }
            offset += 8 + chunkSize
        }

        val numSamples = dataLength / 2
        val out = FloatArray(numSamples)
        for (i in 0 until numSamples) {
            val pcm16 = buf.getShort(dataOffset + i * 2)
            out[i] = (pcm16 / 32768.0f).coerceIn(-1.0f, 1.0f)
        }
        return out
    }
}

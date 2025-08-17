package dev.davidportal.tunerd

import android.media.AudioFormat
import android.media.AudioRecord

data class AudioConfig(
    val sampleRate: Int,
    val bufferSizeInFrames: Int,
    val channelConfig: Int = AudioFormat.CHANNEL_IN_MONO,
    val audioFormat: Int = AudioFormat.ENCODING_PCM_16BIT
) {
    companion object {
        private val CANDIDATE_RATES = intArrayOf(48_000, 44_100, 32_000, 22_050, 16_000, 11_025, 8_000)

        fun chooseSampleRate(): Int {
            for (rate in CANDIDATE_RATES) {
                val min = AudioRecord.getMinBufferSize(
                    rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
                )
                if (min > 0) return rate
            }
            return 44_100
        }

        private fun nextPow2(x: Int): Int {
            var v = x.coerceAtLeast(1)
            v--
            v = v or (v shr 1)
            v = v or (v shr 2)
            v = v or (v shr 4)
            v = v or (v shr 8)
            v = v or (v shr 16)
            return v + 1
        }

        fun default(): AudioConfig {
            val sr = chooseSampleRate()
            val minBytes = AudioRecord.getMinBufferSize(
                sr, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bytesPerFrame = 2 /* 16-bit */ * 1 /* mono */
            val minFrames = (minBytes + bytesPerFrame - 1) / bytesPerFrame

            // ~46ms window (FFT/tuning-friendly), rounded up to power-of-two and >= minFrames
            val targetFrames = nextPow2((sr * 0.046).toInt()).coerceAtLeast(minFrames)

            return AudioConfig(sampleRate = sr, bufferSizeInFrames = targetFrames)
        }
    }
}

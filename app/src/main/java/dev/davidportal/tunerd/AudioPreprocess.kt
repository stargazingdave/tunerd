package dev.davidportal.tunerd

import kotlin.math.*

/**
 * DSP helpers for tuner preprocessing.
 */
object AudioPreprocess {

    /**
     * Apply a simple 2nd-order band-pass (biquad cascade) to PCM16 samples.
     * Keeps only ~\[low..high] Hz. Returns NEW array.
     */
    fun bandPassFilter(
        input: ShortArray,
        sampleRate: Int,
        lowHz: Float = 60f,
        highHz: Float = 1200f
    ): ShortArray {
        if (input.isEmpty()) return input
        val out = ShortArray(input.size)

        // Use biquad coefficients (Butterworth-style)
        val omegaLow = 2.0 * Math.PI * lowHz / sampleRate
        val omegaHigh = 2.0 * Math.PI * highHz / sampleRate

        // Pre-warp
        val tanLow = tan(omegaLow / 2.0)
        val tanHigh = tan(omegaHigh / 2.0)

        // Band-pass transform
        val bw = tanHigh - tanLow
        val w0 = sqrt(tanLow * tanHigh)

        val norm = 1.0 / (1.0 + bw + w0 * w0)
        val b0 = bw * norm
        val b1 = 0.0
        val b2 = -bw * norm
        val a1 = 2.0 * (w0 * w0 - 1.0) * norm
        val a2 = (1.0 - bw + w0 * w0) * norm

        var x1 = 0.0; var x2 = 0.0
        var y1 = 0.0; var y2 = 0.0

        for (i in input.indices) {
            val x0 = input[i].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            out[i] = y0.coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble()).toInt().toShort()

            x2 = x1; x1 = x0
            y2 = y1; y1 = y0
        }
        return out
    }

    /**
     * Normalize RMS of a PCM16 frame to [targetRms].
     * Prevents over-amplifying silence by using [minRms] guard.
     * Mutates a COPY (does not modify input).
     */
    fun normalizeRms(
        input: ShortArray,
        targetRms: Float = 1200f,
        minRms: Float = 200f
    ): ShortArray {
        val rms = rmsPcm16(input)
        if (rms < minRms) return input.copyOf() // too quiet, skip boost

        val factor = targetRms / rms
        val out = ShortArray(input.size)
        for (i in input.indices) {
            val y = input[i] * factor
            out[i] = y.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        }
        return out
    }
}

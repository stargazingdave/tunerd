package dev.davidportal.tunerd

/**
 * Apply a Hann window to 16-bit PCM samples.
 * Returns a new array (non-mutating).
 */
fun applyHannWindow(samples: ShortArray): ShortArray {
    val windowed = ShortArray(samples.size)
    val nMinus1 = (samples.size - 1).toFloat()
    var i = 0
    while (i < samples.size) {
        val multiplier = (0.5f * (1f - kotlin.math.cos((2.0 * Math.PI * i / nMinus1).toFloat()))).toFloat()
        windowed[i] = (samples[i] * multiplier).toInt().toShort()
        i++
    }
    return windowed
}

package dev.davidportal.tunerd

/**
 * Simple 1-pole low-pass filter for 16-bit PCM.
 * cutoffHz: filter cutoff in Hz
 * sampleRate: input sample rate (e.g., 44100)
 * Returns a NEW array (does not mutate input).
 */
fun applyLowPassFilter(input: ShortArray, cutoffHz: Float, sampleRate: Int): ShortArray {
    val output = ShortArray(input.size)
    if (cutoffHz <= 0f || sampleRate <= 0) return input.copyOf()

    val rc = 1.0f / (2f * Math.PI.toFloat() * cutoffHz)
    val dt = 1.0f / sampleRate
    val alpha = dt / (rc + dt)

    var prev = 0f
    var i = 0
    while (i < input.size) {
        val x = input[i].toFloat()
        val y = prev + alpha * (x - prev)
        output[i] = y.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        prev = y
        i++
    }
    return output
}

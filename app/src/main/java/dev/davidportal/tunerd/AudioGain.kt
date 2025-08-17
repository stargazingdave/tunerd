package dev.davidportal.tunerd

/**
 * Multiply 16-bit PCM samples by [factor] in-place with clipping.
 */
fun amplifyPcm16InPlace(samples: ShortArray, factor: Float) {
    var i = 0
    while (i < samples.size) {
        val y = samples[i] * factor
        samples[i] = y
            .coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat())
            .toInt()
            .toShort()
        i++
    }
}

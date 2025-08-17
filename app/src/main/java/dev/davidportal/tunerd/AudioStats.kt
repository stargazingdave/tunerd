package dev.davidportal.tunerd
import kotlin.math.sqrt

/** Fast RMS for 16-bit PCM buffer. */
fun rmsPcm16(samples: ShortArray): Float {
    var sum = 0.0
    var i = 0
    val n = samples.size
    while (i < n) {
        val v = samples[i].toDouble()
        sum += v * v
        i++
    }
    return if (n == 0) 0f else sqrt((sum / n)).toFloat()
}

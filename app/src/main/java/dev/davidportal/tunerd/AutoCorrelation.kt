package dev.davidportal.tunerd

import android.util.Log
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Unnormalized autocorrelation at given lag for 16-bit PCM frame. */
fun autoCorrAt(buffer: ShortArray, lag: Int): Float {
    var corr = 0f
    var i = 0
    val stop = buffer.size - lag
    while (i < stop) {
        corr += buffer[i] * buffer[i + lag].toFloat()
        i++
    }
    return corr
}

/* ===========================
 * Debug helpers (non-breaking)
 * ===========================
 *
 * Call logAcfPeaks(...) from your detector/engine right after you have the frame.
 * Example:
 *   logAcfPeaks(
 *       buffer = frame,
 *       sampleRate = 44100,
 *       minFreq = 70f,
 *       maxFreq = 1200f,
 *       topN = 5,
 *       pickedTau = tauOrNull,   // your chosen tau, optional
 *       enableCenterClip = true, // optional
 *       clipFactor = 0.7f,
 *       tag = "Tuner"
 *   )
 */

/** Find and log top-N normalized ACF peaks as (freqHz:strength) plus optional chosen tau. */
fun logAcfPeaks(
    buffer: ShortArray,
    sampleRate: Int,
    minFreq: Float,
    maxFreq: Float,
    topN: Int = 5,
    pickedTau: Int? = null,
    enableCenterClip: Boolean = false,
    clipFactor: Float = 0.7f,
    tag: String = "Tuner"
) {
    if (buffer.isEmpty()) return

    // Optionally center-clip a copy (so we don't mutate caller's buffer)
    val (work, clipThresh, clipApplied) = if (enableCenterClip) {
        val (clipped, thr) = centerClip(buffer, clipFactor)
        Triple(clipped, thr, true)
    } else {
        Triple(buffer, 0, false)
    }

    val tauMin = max(1, (sampleRate / max(1f, maxFreq)).roundToInt())
    val tauMax = min(buffer.size - 2, (sampleRate / max(1f, minFreq)).roundToInt())
    if (tauMin >= tauMax) return

    // Compute r0 and normalized r[tau]
    val r0 = autoCorrAt(work, 0).coerceAtLeast(1e-9f)
    val peaks = ArrayList<Pair<Int, Float>>() // (tau, normVal)

    // Simple local-max search over [tauMin, tauMax]
    var tau = tauMin
    var prev = autoCorrAt(work, tau - 1) / r0
    var curr = autoCorrAt(work, tau) / r0
    while (tau + 1 <= tauMax) {
        val next = autoCorrAt(work, tau + 1) / r0
        if (curr >= prev && curr >= next) {
            peaks += tau to curr
        }
        tau++
        prev = curr
        curr = next
    }

    if (peaks.isEmpty()) {
        Log.i(tag, "acf=[] pick=none r0=%.3f clip=%s thr=%d".format(r0, if (clipApplied) "on" else "off", clipThresh))
        return
    }

    // Sort by strength desc to select topN for display, but we’ll keep freq ascending in the string for readability.
    val top = peaks.sortedByDescending { it.second }.take(topN)
    val display = top
        .sortedBy { it.first }
        .joinToString(separator = ", ") { (t, v) ->
            val f = sampleRate / t.toFloat()
            "%.1f:%.2f".format(f, v)
        }

    // If caller provided a chosen tau, show it as the pick; otherwise, show strongest.
    val (bestTau, bestVal) = pickedTau?.let { it to (autoCorrAt(work, it) / r0) }
        ?: (top.maxByOrNull { it.second } ?: top.first())

    val pickHz = sampleRate / bestTau.toFloat()
    Log.i(
        tag,
        "acf=[$display] pick=%.1fHz(tau=%d,%.2f) r0=%.3f clip=%s thr=%d".format(
            pickHz, bestTau, bestVal, r0, if (clipApplied) "on" else "off", clipThresh
        )
    )
}

/** Center clipping on a copy of the buffer; returns (clipped, thresholdUsed). */
private fun centerClip(src: ShortArray, factor: Float = 0.7f): Pair<ShortArray, Int> {
    val maxAbs = src.maxOf { abs(it.toInt()) }
    val thr = (maxAbs * factor).roundToInt()
    if (thr <= 0) return src.copyOf() to 0

    val out = ShortArray(src.size)
    for (i in src.indices) {
        val s = src[i].toInt()
        out[i] = when {
            s > thr  -> (s - thr).toShort()
            s < -thr -> (s + thr).toShort()
            else     -> 0
        }
    }
    return out to thr
}

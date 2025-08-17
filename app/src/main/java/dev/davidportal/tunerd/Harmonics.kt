package dev.davidportal.tunerd

import android.util.Log
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Goertzel magnitude^2 at frequency `freq` (Hz) for a 16-bit PCM frame. */
fun goertzelPow(frame: ShortArray, sampleRate: Int, freq: Float): Double {
    val n = frame.size
    if (freq <= 0f || n <= 0) return 0.0
    val k = (0.5 + (n * freq) / sampleRate).toInt()
    val omega = (2.0 * Math.PI * k) / n
    val coeff = 2.0 * cos(omega)
    var s0 = 0.0; var s1 = 0.0; var s2 = 0.0
    var i = 0
    while (i < n) {
        val x = frame[i].toDouble()
        s0 = x + coeff * s1 - s2
        s2 = s1
        s1 = s0
        i++
    }
    val real = s1 - s2 * cos(omega)
    val imag = s2 * sin(omega)
    return real * real + imag * imag
}

/**
 * Sum harmonic energy for f, 2f, 3f, 4f (stops before Nyquist).
 * Useful to compare fundamental vs. collapsed-octave hypotheses.
 */
fun harmonicScore(frame: ShortArray, sampleRate: Int, f: Float): Double {
    if (f <= 0f) return 0.0
    val nyquist = sampleRate * 0.5f
    var sum = 0.0
    var h = 1
    while (h <= 4) {
        val fh = f * h
        if (fh >= nyquist) break
        sum += goertzelPow(frame, sampleRate, fh)
        h++
    }
    return sum
}

/* =======================================================================
 *                          DEBUG HELPERS (OPT-IN)
 * These helpers let you log spectrum peaks via Goertzel and explain
 * octave errors by comparing harmonic sums at f vs 2f (and 0.5f/3f).
 * Nothing runs unless you call them from your detector/engine.
 * =======================================================================
 */

/**
 * Do a Goertzel sweep over [fMin,fMax] using `bins` discrete test freqs
 * and return a list of (freqHz, power). Useful for debug visual/logs.
 */
fun goertzelGrid(
    frame: ShortArray,
    sampleRate: Int,
    fMin: Float,
    fMax: Float,
    bins: Int
): List<Pair<Float, Double>> {
    val nyq = sampleRate * 0.5f
    if (bins <= 1 || fMax <= 0f || fMin <= 0f || fMin >= fMax) return emptyList()
    val hi = min(fMax, nyq - 1f)
    val lo = max(1f, fMin)
    if (lo >= hi) return emptyList()

    val out = ArrayList<Pair<Float, Double>>(bins)
    for (i in 0 until bins) {
        val f = lo + (hi - lo) * (i.toFloat() / (bins - 1).coerceAtLeast(1))
        val p = goertzelPow(frame, sampleRate, f)
        out += f to p
    }
    return out
}

/**
 * Simple non-maximum suppression (NMS) over a (freq,power) grid.
 * Keeps the highest power within +/- neighborhoodHz and returns topN.
 */
private fun topPeaksFromGrid(
    grid: List<Pair<Float, Double>>,
    topN: Int,
    neighborhoodHz: Float
): List<Pair<Float, Double>> {
    if (grid.isEmpty() || topN <= 0) return emptyList()
    val sorted = grid.sortedByDescending { it.second }.toMutableList()
    val kept = ArrayList<Pair<Float, Double>>(min(topN, sorted.size))
    val used = BooleanArray(sorted.size)

    for (i in sorted.indices) {
        if (used[i]) continue
        val (fi, pi) = sorted[i]
        kept += fi to pi
        if (kept.size >= topN) break
        // suppress neighbors
        for (j in (i + 1) until sorted.size) {
            if (used[j]) continue
            val (fj, _) = sorted[j]
            if (abs(fj - fi) <= neighborhoodHz) used[j] = true
        }
    }

    // Sort ascending by freq for nicer printing
    return kept.sortedBy { it.first }
}

/**
 * Log top-N spectral peaks (via Goertzel sweep). Good for pairing with ACF logs.
 *
 * Example:
 *   logSpectrumPeaks(
 *       frame, 44100, fMin = 60f, fMax = 1200f,
 *       bins = 512, topN = 5, neighborhoodHz = 20f, tag = "Tuner/GOERTZEL"
 *   )
 *
 * Output example:
 *   I/Tuner/GOERTZEL: peaks=[110.0:0.19, 165.0:0.33, 330.0:0.78, 660.0:0.41, 990.0:0.22] pick≈330.0? (optional)
 */
fun logSpectrumPeaks(
    frame: ShortArray,
    sampleRate: Int,
    fMin: Float,
    fMax: Float,
    bins: Int = 512,
    topN: Int = 5,
    neighborhoodHz: Float = 20f,
    approxPickHz: Float? = null,
    tag: String = "Tuner/GOERTZEL"
) {
    val grid = goertzelGrid(frame, sampleRate, fMin, fMax, bins)
    if (grid.isEmpty()) {
        Log.i(tag, "peaks=[]")
        return
    }
    val peaks = topPeaksFromGrid(grid, topN, neighborhoodHz)
    val disp = peaks.joinToString { (f, p) -> "%.1f:%.2f".format(f, p) }
    if (approxPickHz != null) {
        Log.i(tag, "peaks=[$disp] pick≈${"%.1f".format(approxPickHz)}")
    } else {
        Log.i(tag, "peaks=[$disp]")
    }
}

/**
 * Compare harmonic sums at f vs. 2f (and optionally 0.5f/3f) to explain octave errors.
 *
 * Example:
 *   logHarmonicComparison(frame, 44100, fCandidateHz = 165f, tag = "Tuner/HARM")
 *
 * Output example:
 *   I/Tuner/HARM: f=165.0 s=1.23 | 2f=330.0 s2=2.01 | 0.5f=82.5 s05=0.44 | 3f=495.0 s3=0.88 -> prefer=2f
 */
fun logHarmonicComparison(
    frame: ShortArray,
    sampleRate: Int,
    fCandidateHz: Float,
    checkHalf: Boolean = true,
    checkTriple: Boolean = true,
    tag: String = "Tuner/HARM"
) {
    if (fCandidateHz <= 0f) {
        Log.i(tag, "invalid f=$fCandidateHz")
        return
    }

    val nyq = sampleRate * 0.5f
    val f = fCandidateHz
    val sF = harmonicScore(frame, sampleRate, f)

    val f2 = f * 2f
    val s2 = if (f2 < nyq) harmonicScore(frame, sampleRate, f2) else 0.0

    val f05 = f * 0.5f
    val s05 = if (checkHalf && f05 > 20f) harmonicScore(frame, sampleRate, f05) else 0.0

    val f3 = f * 3f
    val s3 = if (checkTriple && f3 < nyq) harmonicScore(frame, sampleRate, f3) else 0.0

    // Decide which looks most harmonic
    val candidates = mutableListOf(
        "f" to sF,
        "2f" to s2
    )
    if (checkHalf) candidates += "0.5f" to s05
    if (checkTriple) candidates += "3f" to s3

    val best = candidates.maxByOrNull { it.second }?.first ?: "f"

    val parts = buildList {
        add("f=${"%.1f".format(f)} s=${"%.2f".format(sF)}")
        add("2f=${"%.1f".format(f2)} s2=${"%.2f".format(s2)}")
        if (checkHalf) add("0.5f=${"%.1f".format(f05)} s05=${"%.2f".format(s05)}")
        if (checkTriple) add("3f=${"%.1f".format(f3)} s3=${"%.2f".format(s3)}")
    }.joinToString(" | ")

    Log.i(tag, "$parts -> prefer=$best")
}

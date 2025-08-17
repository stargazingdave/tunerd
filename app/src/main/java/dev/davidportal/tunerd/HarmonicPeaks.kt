package dev.davidportal.tunerd

import android.util.Log
import kotlin.math.abs
import kotlin.math.min

/**
 * Peaks-first fundamental estimation using Goertzel:
 * 1) Sweep 60..1200 Hz, collect top-N peaks with NMS.
 * 2) For each peak fp and k in 1..5, consider f0=fp/k (in range).
 * 3) Score f0 by a weighted harmonic comb sum at h*f0 (h=1..H) using local power.
 * 4) Return the best f0 and a confidence margin vs runner-up.
 *
 * Requires goertzelPow(...) from PitchUnits.kt
 */
object HarmonicPeaks {

    data class F0Result(
        val f0: Float,              // best estimated fundamental (Hz), or <=0 if not found
        val score: Double,          // raw comb score of the winner
        val confidence: Double,     // 0..1 margin vs runner-up
        val fromPeakHz: Float,      // which observed peak generated this hypothesis
        val k: Int,                 // fp / k = f0
        val peaks: List<Pair<Float, Double>> // [(freq,power)] used for context/logs
    )

    // Config
    private const val FMIN = 60f
    private const val FMAX = 1200f
    private const val GRID_BINS = 640       // sweep resolution
    private const val TOP_N = 5              // number of peaks to keep
    private const val NMS_HZ = 18f           // non-maximum suppression radius
    private const val HARMONICS = 6          // 4-6 works well for guitar
    private const val DEBUG = true
    private const val TAG = "Tuner/PEAKS"

    private fun harmonicWeight(h: Int): Double = 1.0 / h

    /** Sweep Goertzel over [fMin,fMax] and return (freq,power) samples. */
    private fun goertzelGrid(
        frame: ShortArray,
        sr: Int,
        fMin: Float,
        fMax: Float,
        bins: Int
    ): List<Pair<Float, Double>> {
        val nyq = sr * 0.5f
        val lo = maxOf(1f, fMin)
        val hi = min(fMax, nyq - 1f)
        if (bins < 2 || lo >= hi) return emptyList()
        val out = ArrayList<Pair<Float, Double>>(bins)
        for (i in 0 until bins) {
            val f = lo + (hi - lo) * (i.toFloat() / (bins - 1))
            val p = goertzelPow(frame, sr, f)
            out += f to p
        }
        return out
    }

    /** Simple NMS: keep highest peaks, suppress neighbors within ±nmsHz. */
    private fun topPeaks(
        grid: List<Pair<Float, Double>>,
        topN: Int,
        nmsHz: Float
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
            for (j in i + 1 until sorted.size) {
                if (used[j]) continue
                val (fj, _) = sorted[j]
                if (abs(fj - fi) <= nmsHz) used[j] = true
            }
        }
        return kept.sortedBy { it.first }
    }

    /** Sum local power around freq (± one bin) to be tolerant to tiny detune. */
    private fun localPower(frame: ShortArray, sr: Int, freq: Float, binHz: Float): Double {
        if (freq <= 1f) return 0.0
        val fA = (freq - binHz).coerceAtLeast(1f)
        val fB = freq
        val fC = freq + binHz
        return goertzelPow(frame, sr, fA) + goertzelPow(frame, sr, fB) + goertzelPow(frame, sr, fC)
    }

    /** Harmonic comb score at f0 using local power around h*f0 (h=1..HARMONICS). */
    private fun combScore(frame: ShortArray, sr: Int, f0: Float, binHz: Float): Double {
        if (f0 <= 0f) return 0.0
        val nyq = sr * 0.5f
        var sum = 0.0
        var h = 1
        while (h <= HARMONICS) {
            val fh = f0 * h
            if (fh >= nyq) break
            sum += harmonicWeight(h) * localPower(frame, sr, fh, binHz)
            h++
        }
        return sum
    }

    /**
     * Main entry: estimate f0 from peaks. Returns f0<=0 if insufficient evidence.
     */
    fun estimateF0(
        frame: ShortArray,
        sampleRate: Int,
        fMin: Float = FMIN,
        fMax: Float = FMAX,
        bins: Int = GRID_BINS,
        topN: Int = TOP_N,
        nmsHz: Float = NMS_HZ
    ): F0Result {
        val n = frame.size
        if (n < 256) return F0Result(0f, 0.0, 0.0, 0f, 1, emptyList())

        val binHz = sampleRate.toFloat() / n
        val grid = goertzelGrid(frame, sampleRate, fMin, fMax, bins)
        val peaks = topPeaks(grid, topN, nmsHz)
        if (peaks.isEmpty()) return F0Result(0f, 0.0, 0.0, 0f, 1, peaks)

        // Generate hypotheses from peaks: f0 = fp / k, k=1..5
        data class Hyp(val f0: Float, val fp: Float, val k: Int)
        val hyps = ArrayList<Hyp>()
        for ((fp, _) in peaks) {
            for (k in 1..5) {
                val f0 = fp / k
                if (f0 in fMin..fMax) hyps += Hyp(f0, fp, k)
            }
        }
        if (hyps.isEmpty()) return F0Result(0f, 0.0, 0.0, 0f, 1, peaks)

        // Score all hypotheses
        val scores = hyps.map { hyp ->
            combScore(frame, sampleRate, hyp.f0, binHz) to hyp
        }.sortedByDescending { it.first }

        val (bestScore, bestHyp) = scores.first()
        val runnerUp = scores.getOrNull(1)?.first ?: 0.0
        val conf = if (bestScore <= 0.0) 0.0 else ((bestScore - runnerUp) / (bestScore + 1e-9)).coerceIn(0.0, 1.0)

        if (DEBUG) {
            val peakStr = peaks.joinToString { (f,p) -> "%.1f:%.2e".format(f, p) }
            Log.i(TAG, "peaks=[$peakStr]")
            Log.i(TAG, "best f0=%.1f (from %.1f/k=%d) score=%.2e conf=%.2f".format(bestHyp.f0, bestHyp.fp, bestHyp.k, bestScore, conf))
        }

        return F0Result(
            f0 = bestHyp.f0,
            score = bestScore,
            confidence = conf,
            fromPeakHz = bestHyp.fp,
            k = bestHyp.k,
            peaks = peaks
        )
    }
}

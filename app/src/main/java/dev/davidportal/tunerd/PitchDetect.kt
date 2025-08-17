package dev.davidportal.tunerd

import android.util.Log
import kotlin.math.abs
import kotlin.math.pow

private const val DEBUG_PITCH = true
private const val DEBUG_TAG = "Tuner/Pitch"

/**
 * Autocorrelation-based pitch detector with band-limited search,
 * normalized ACF selection, harmonic guards, and parabolic interpolation.
 *
 * NOTE (fixes):
 * - Removed the 2×-lag (octave-down) guard that was pulling ~330 Hz → ~165 Hz.
 * - Made octave-up (halve lag) guard more permissive.
 * - Lowered harmonic disambiguation threshold to prefer 2f when appropriate.
 */
fun detectPitch(buffer: ShortArray, sampleRate: Int, lastStableHz: Float?): Float {
    fun dbg(msg: () -> String) { if (DEBUG_PITCH) Log.i(DEBUG_TAG, msg()) }

    val n = buffer.size
    if (n < 256) return -1f

    // Global range (~60..1000 Hz)
    var minLag = (sampleRate / 1000f).toInt().coerceAtLeast(2)       // ~1000 Hz
    var maxLag = (sampleRate / 60f).toInt().coerceAtMost(n / 2 - 2)  // ~60 Hz

    // Band-limit around last stable Hz; asymmetric on trebles
    if (lastStableHz != null && lastStableHz > 0f) {
        val (downSemi, upSemi) = if (lastStableHz >= 250f) 2f to 5f else 4f to 4f
        val fMin = (lastStableHz / 2f.pow(downSemi / 12f)).coerceAtLeast(40f)
        val fMax = (lastStableHz * 2f.pow(upSemi   / 12f)).coerceAtMost(2000f)
        val bMinLag = (sampleRate / fMax).toInt().coerceAtLeast(minLag)
        val bMaxLag = (sampleRate / fMin).toInt().coerceAtMost(maxLag)
        if (bMaxLag - bMinLag >= 6) { minLag = bMinLag; maxLag = bMaxLag }
    }

    dbg { "win lags=$minLag..$maxLag lastStable=${lastStableHz?.let { "%.1f".format(it) } ?: "none"}" }

    // ---- ACF (unnormalized + normalized by r0) ----
    val r = FloatArray(maxLag + 1)
    var maxCorr = 0f
    for (lag in minLag..maxLag) {
        val c = autoCorrAt(buffer, lag)
        r[lag] = c
        if (c > maxCorr) maxCorr = c
    }
    if (maxCorr <= 0f) return -1f

    val r0 = autoCorrAt(buffer, 0).coerceAtLeast(1e-9f)
    val rn = FloatArray(maxLag + 1)
    var maxRn = 0f
    for (lag in minLag..maxLag) {
        rn[lag] = r[lag] / r0
        if (rn[lag] > maxRn) maxRn = rn[lag]
    }

    // ---- Peak selection: FIRST strong local max (normalized) ----
    val absThrN = 0.30f
    val relThrN = 0.60f * maxRn

    fun scoreLag(lag: Int): Float {
        val base = rn[lag]
        val h2 = if (lag * 2 <= maxLag) rn[lag * 2] else 0f
        val h3 = if (lag * 3 <= maxLag) rn[lag * 3] else 0f
        val h05 = if (lag / 2 >= minLag) rn[lag / 2] else 0f
        val penalty = 0.90f * maxOf(h2, h3) + 0.60f * h05
        val smallLagBias = 0.002f * (maxLag - lag)
        return base - penalty + smallLagBias
    }

    var bestLag = -1
    var firstStrongLag = -1
    var chosenBy = "first-strong-local-maxN"

    for (lag in (minLag + 1) until maxLag) {
        val c = rn[lag]
        if (c >= rn[lag - 1] && c >= rn[lag + 1] && c >= absThrN && c >= relThrN) {
            firstStrongLag = lag
            val sc = scoreLag(lag)
            if (sc >= 0.02f) { bestLag = lag; break }
        }
    }

    if (bestLag < 0) {
        var bestScore = Float.NEGATIVE_INFINITY
        var bestIdx = -1
        for (lag in (minLag + 1) until maxLag) {
            val c = rn[lag]
            if (c >= rn[lag - 1] && c >= rn[lag + 1]) {
                val sc = scoreLag(lag)
                if (sc > bestScore) { bestScore = sc; bestIdx = lag }
            }
        }
        if (bestIdx >= 0) { bestLag = bestIdx; chosenBy = "best-score-fallbackN" }
        if (bestLag < 0) {
            var maxV = Float.NEGATIVE_INFINITY
            var maxI = -1
            for (lag in minLag..maxLag) if (rn[lag] > maxV) { maxV = rn[lag]; maxI = lag }
            bestLag = maxI
            chosenBy = "global-max-fallbackN"
            if (bestLag < 0) return -1f
        }
    }

    if (DEBUG_PITCH) {
        val firstInfo = if (firstStrongLag > 0)
            "firstStrongLag=$firstStrongLag(${sampleRate / firstStrongLag.toFloat()}Hz)"
        else "firstStrongLag=none"
        Log.i("$DEBUG_TAG/ACF",
            "pickN=${"%.1f".format(sampleRate / bestLag.toFloat())}Hz (tau=$bestLag, rn=${"%.2f".format(rn[bestLag])}) r0=${"%.3e".format(r0)}"
        )
        dbg { "cand lag=$bestLag(${sampleRate / bestLag.toFloat()}Hz) by=$chosenBy thrAbsN=$absThrN thrRelN=${"%.2f".format(relThrN)} | $firstInfo" }
    }

    // --- Guards (NO octave-down). Allow octave-UP if evidence supports it. ---
    var tau = bestLag
    var score = rn[bestLag]
    val guards = StringBuilder()

    // Octave-up (halve lag) — more permissive to escape subharmonics.
    val half = bestLag / 2
    if (half >= minLag) {
        val ch = rn[half]
        // if half-lag peak is close to current or stronger, go up
        if (ch >= score * 0.92f) {
            tau = half; score = ch
            guards.append("octave-up(1/2x) ")
        }
    }

    // 3× lag guard (prevents 1/3 locks like E4 → A2)
    val three = tau * 3
    if (three <= maxLag) {
        val c3 = rn[three]
        if (tau < 140 && c3 >= score * 0.98f) {
            tau = three; score = c3
            guards.append("tripled(3x) ")
        }
    }

    // Treble-collapse sanity vs lastStableHz
    if (lastStableHz != null && lastStableHz >= 250f) {
        val fNow = sampleRate / tau.toFloat()
        if (fNow < lastStableHz / 1.8f) {
            val half2 = tau / 2
            if (half2 >= minLag) {
                val ch2 = rn[half2]
                val ct  = rn[tau]
                if (ch2 >= ct * 0.90f) {
                    tau = half2; score = ch2
                    guards.append("treble-collapse-fix ")
                }
            }
        }
    }

    // Parabolic interpolation
    val l0 = (tau - 1).coerceAtLeast(minLag)
    val l1 = tau
    val l2 = (tau + 1).coerceAtMost(maxLag)
    val c0 = r[l0]; val c1 = r[l1]; val c2 = r[l2]
    val denom = (c0 - 2f * c1 + c2)
    val delta = if (abs(denom) < 1e-9f) 0f else 0.5f * (c0 - c2) / denom
    val tauInterp = (l1 + delta).coerceIn(minLag.toFloat(), maxLag.toFloat())

    var f = sampleRate / tauInterp
    dbg { "guards=[${guards.toString().trim()}] tau=$tau -> tau*=${"%.3f".format(tauInterp)} fPre=${"%.1f".format(f)}Hz" }

    // ---- Harmonic disambiguation: prefer 2f if slightly stronger ----
    if (f.isFinite() && f in 20f..1000f) {
        val sFund = harmonicScore(buffer, sampleRate, f)
        val s2    = harmonicScore(buffer, sampleRate, 2f * f)
        // Lower threshold: even modest advantage for 2f should flip up
        val prefer2f = s2 > sFund * 1.04
        if (prefer2f && 2f * f <= 1200f) {
            f *= 2f
            dbg { "disamb: *2 (2f harmonic sum stronger)" }
        }

        // Extra rescue for first frames (no lastStable) that collapse down
        if (lastStableHz == null && f < 220f) {
            val sBack = harmonicScore(buffer, sampleRate, 2f * f)
            if (sBack >= sFund * 1.02) {
                f *= 2f
                dbg { "rescue: bootstrap *2" }
            }
        }

        dbg { "final=${"%.1f".format(f)}Hz" }
        return f
    } else {
        dbg { "final=invalid f=$f" }
        return -1f
    }
}

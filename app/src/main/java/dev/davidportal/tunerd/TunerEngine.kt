package dev.davidportal.tunerd

class TunerEngine(
    private val rmsThreshold: Float = 300f,
    private val invalidHideFrames: Int = 2, // ~60ms at ~50 fps
    private val ampFactor: Float = 6f,
    private val fixedSampleRate: Int? = null // optional: set this to avoid passing sr on every call
) {
    // ========= DEBUG TOGGLES =========
    private companion object {
        private const val DEBUG_EVERY_N_FRAMES = 3
        private const val DEBUG = true
    }
    private var dbgFrame = 0
    // =================================

    // smoothing + memory
    private val median5 = MedianFilter(5)
    private val centsStick = CentsStickiness()
    private var emaCents: Float? = null

    @Volatile private var lastRefHz: Float? = null   // nearest-note freq last frame
    @Volatile private var lastStableHz: Float? = null// smoothed Hz last frame
    private var suspectFrames = 0
    private var invalidStreak = 0

    data class Result(val state: PitchState?, val shouldHideNow: Boolean)

    fun reset() {
        emaCents = null
        median5.clear()
        centsStick.reset()
        lastRefHz = null
        lastStableHz = null
        suspectFrames = 0
        invalidStreak = 0
        dbgFrame = 0
    }

    // Convenience overload if you pass fixedSampleRate in constructor
    fun processFrame(buffer: ShortArray, tuning: List<GuitarNote>): Result {
        val sr = fixedSampleRate
            ?: error("Use processFrame(buffer, sampleRate, tuning) or set fixedSampleRate in ctor.")
        return processFrame(buffer, sr, tuning)
    }

    /**
     * Feed a raw mono PCM16 frame. Returns UI state (if valid) and whether UI should hide now.
     */
    fun processFrame(
        buffer: ShortArray,
        sampleRate: Int,
        tuning: List<GuitarNote>
    ): Result {
        val rms = rmsPcm16(buffer)
        if (rms <= rmsThreshold) {
            invalidStreak = 0
            reset()
            return Result(state = null, shouldHideNow = true)
        }

        // --- Preprocess: band-pass + normalize + Hann ---
        val banded   = AudioPreprocess.bandPassFilter(buffer, sampleRate)
        val leveled  = AudioPreprocess.normalizeRms(banded)
        val windowed = applyHannWindow(leveled)

        // --- Peaks-first f0 ---
        val peaksF0 = HarmonicPeaks.estimateF0(
            frame = windowed,
            sampleRate = sampleRate,
            fMin = 60f, fMax = 1200f,
            bins = 640, topN = 5, nmsHz = 18f
        )

        // Fallback with legacy ACF if peaks confidence is too low
        val usePeaks = peaksF0.f0 in 60f..1000f && peaksF0.confidence >= 0.10
        val acfF0 = if (!usePeaks) detectPitch(windowed, sampleRate, lastStableHz) else peaksF0.f0

        var pitch = acfF0
        if (!pitch.isFinite() || pitch !in 20f..2000f) {
            invalidStreak++
            val hide = invalidStreak >= invalidHideFrames
            if (hide) {
                emaCents = null
                median5.clear()
                centsStick.reset()
            }
            return Result(state = null, shouldHideNow = hide)
        }

        // ---------- DEBUG (throttled) ----------
        dbgFrame++
        if (DEBUG && (dbgFrame % DEBUG_EVERY_N_FRAMES == 0)) {
            // brief summary
            android.util.Log.i("Tuner/F0", "peaksF0=%.1fHz (conf=%.2f, from=%.1f/k=%d) -> pitch=%.1f".format(
                peaksF0.f0, peaksF0.confidence, peaksF0.fromPeakHz, peaksF0.k, pitch
            ))
        }
        // --------------------------------------

        // Median for short-term jitter
        invalidStreak = 0
        val m = median5.push(pitch)

        // Map to nearest target note for UI label and cents
        val nearestNote = getClosestTargetNote(m, tuning).first
        val ref = nearestNote.frequency

        // Cents and smoothing (unchanged)
        val centsNowRaw = hzToCentsSafe(m, ref)
        val centsNow = centsStick.apply(centsNowRaw)
        val alpha = adaptiveAlpha(emaCents, centsNow)
        emaCents = if (emaCents == null) centsNow else (alpha * centsNow + (1 - alpha) * emaCents!!)
        val smoothHz = centsToHz(emaCents!!, ref)

        // remember for next frame (band-limited search + LPF)
        lastRefHz = ref
        val was = lastStableHz
        if (was != null && was >= 250f && smoothHz < was / 1.8f) {
            // suspicious collapse (≥ ~9 semitones down on treble)
            suspectFrames++
            if (suspectFrames >= 3) { lastStableHz = smoothHz; suspectFrames = 0 }
        } else {
            lastStableHz = smoothHz
            suspectFrames = 0
        }

        val state = PitchState(
            nearestNoteName = nearestNote.name,
            nearestNoteFreqHz = nearestNote.frequency,
            detectedFreqHz = smoothHz
        )
        return Result(state = state, shouldHideNow = false)
    }
}

package dev.davidportal.tunerd

/**
 * Frame-level note classifier that scores each target note by how well
 * its harmonic series explains the frame. Uses Goertzel at harmonics.
 *
 * It returns (bestNote, confidence, rawScores) and is robust to octave traps:
 * for each note it considers f, f/2, and 2f variants and takes the best.
 */
object NoteClassifier {

    data class Result(
        val note: GuitarNote,
        val confidence: Double,               // 0..1 (margin vs runner-up)
        val bestVariantHz: Float,             // the f variant (f, f/2 or 2f) that scored best
        val scores: Map<String, Double>       // noteName -> score (for debugging/telemetry)
    )

    // How many harmonics to sum. 4–6 works well for guitar; higher risks Nyquist aliasing.
    private const val HARMONICS = 6
    // Weight per harmonic h. Classic is 1/h; tweakable.
    private fun weight(h: Int): Double = 1.0 / h

    private fun combScore(frame: ShortArray, sampleRate: Int, f: Float): Double {
        if (f <= 0f) return 0.0
        val nyq = sampleRate * 0.5f
        var sum = 0.0
        var h = 1
        while (h <= HARMONICS) {
            val fh = f * h
            if (fh >= nyq) break
            sum += weight(h) * goertzelPow(frame, sampleRate, fh)
            h++
        }
        return sum
    }

    /**
     * Score one musical note by its best octave variant among {f, f/2, 2f}.
     * Returns (bestScore, whichHzWasBest).
     */
    private fun scoreNoteWithOctaveVariants(
        frame: ShortArray,
        sampleRate: Int,
        baseHz: Float
    ): Pair<Double, Float> {
        val s1 = combScore(frame, sampleRate, baseHz)
        val s2 = combScore(frame, sampleRate, baseHz * 2f)
        val s05 = combScore(frame, sampleRate, baseHz * 0.5f)
        // biases: prefer the nominal octave slightly to reduce cross-string “magnetism”
        val b1  = s1 * 1.00
        val b2  = s2 * 0.95
        val b05 = s05 * 0.90
        return when {
            b1 >= b2 && b1 >= b05 -> b1 to baseHz
            b2 >= b1 && b2 >= b05 -> b2 to (baseHz * 2f)
            else                  -> b05 to (baseHz * 0.5f)
        }
    }

    /**
     * Classify the played note from a set of target notes (your tuning).
     * Returns the best note, a confidence (0..1), which octave variant won,
     * and a name->score map for telemetry/debugging if desired.
     */
    fun classify(
        frame: ShortArray,
        sampleRate: Int,
        tuning: List<GuitarNote>
    ): Result {
        require(tuning.isNotEmpty())
        val noteScores = mutableMapOf<String, Double>()
        var best: GuitarNote = tuning.first()
        var bestScore = Double.NEGATIVE_INFINITY
        var bestVariantHz = best.frequency

        for (note in tuning) {
            val (score, variantHz) = scoreNoteWithOctaveVariants(frame, sampleRate, note.frequency)
            noteScores[note.name] = score
            if (score > bestScore) {
                bestScore = score
                best = note
                bestVariantHz = variantHz
            }
        }

        // confidence = margin vs runner-up
        val sorted = noteScores.entries.sortedByDescending { it.value }
        val s0 = sorted.getOrNull(0)?.value ?: 0.0
        val s1 = sorted.getOrNull(1)?.value ?: 0.0
        val conf = if (s0 <= 0.0) 0.0 else ((s0 - s1) / (s0 + 1e-9)).coerceIn(0.0, 1.0)

        return Result(
            note = best,
            confidence = conf,
            bestVariantHz = bestVariantHz,
            scores = noteScores
        )
    }
}

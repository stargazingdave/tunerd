package dev.davidportal.tunerd

/**
 * Convert frequency (Hz) relative to a reference to cents.
 * Returns 0f for non-positive inputs to avoid NaNs.
 */
fun hzToCentsSafe(f: Float, refHz: Float): Float {
    if (f <= 0f || refHz <= 0f) return 0f
    return (1200f * (kotlin.math.ln(f / refHz) / kotlin.math.ln(2.0))).toFloat()
}

/** Convert cents offset back to absolute frequency (Hz) relative to a reference. */
fun centsToHz(c: Float, refHz: Float): Float =
    (refHz * Math.pow(2.0, (c / 1200.0).toDouble())).toFloat()

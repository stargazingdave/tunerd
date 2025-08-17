package dev.davidportal.tunerd
import kotlin.math.abs

/**
 * Deadband/“stickiness” around 0 cents:
 * - If |c| < inC → snap to 0 (stick)
 * - If stuck and |c| > outC → release and pass c through
 */
class CentsStickiness(
    private val inC: Float = 3f,
    private val outC: Float = 5f
) {
    private var stuck = false

    fun apply(c: Float): Float {
        return if (stuck) {
            if (abs(c) > outC) { stuck = false; c } else 0f
        } else {
            if (abs(c) < inC) { stuck = true; 0f } else c
        }
    }

    fun reset() { stuck = false }
}

/**
 * Adaptive EMA alpha based on change magnitude in cents.
 * Larger jumps → faster smoothing; tiny changes → slower.
 */
fun adaptiveAlpha(prevC: Float?, currC: Float): Float {
    if (prevC == null) return 0.22f
    val d = abs(currC - prevC)
    return when {
        d > 20f -> 0.28f
        d > 10f -> 0.22f
        d > 5f  -> 0.20f
        else    -> 0.16f
    }
}

package dev.davidportal.tunerd

/**
 * Rolling median filter over the last [capacity] Float samples.
 * Default capacity=5 works well for pitch smoothing.
 */
class MedianFilter(private val capacity: Int = 5) {
    private val q = ArrayDeque<Float>(capacity)

    /** Push a value, returns current median. */
    fun push(v: Float): Float {
        if (q.size == capacity) q.removeFirst()
        q.addLast(v)
        val sorted = q.sorted()
        return sorted[sorted.size / 2]
    }

    fun clear() = q.clear()
}

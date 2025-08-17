package dev.davidportal.tunerd

import kotlin.math.abs

/**
 * Returns (closestNote, pitchHz - closestNote.frequency)
 */
fun getClosestTargetNote(pitchHz: Float, targets: List<GuitarNote>): Pair<GuitarNote, Float> {
    var closest = targets.first()
    var smallestDiff = Float.MAX_VALUE
    for (note in targets) {
        val diff = abs(note.frequency - pitchHz)
        if (diff < smallestDiff) {
            smallestDiff = diff
            closest = note
        }
    }
    return Pair(closest, pitchHz - closest.frequency)
}

/**
 * Returns a small list of notes around the given note (±range semitones) from noteTable.
 * Falls back to just [note] if it's not found.
 */
fun getTuningOptionsFor(note: GuitarNote, range: Int = 4): List<GuitarNote> {
    val index = noteTable.indexOfFirst { it.name == note.name }
    if (index == -1) return listOf(note)
    val start = (index - range).coerceAtLeast(0)
    val end = (index + range).coerceAtMost(noteTable.lastIndex)
    return noteTable.subList(start, end + 1)
}
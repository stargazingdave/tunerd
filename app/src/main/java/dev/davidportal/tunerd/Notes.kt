package dev.davidportal.tunerd

data class GuitarNote(val name: String, val frequency: Float)

val standardTuning = listOf(
    GuitarNote("E2", 82.41f),
    GuitarNote("A2", 110.00f),
    GuitarNote("D3", 146.83f),
    GuitarNote("G3", 196.00f),
    GuitarNote("B3", 246.94f),
    GuitarNote("E4", 329.63f),
)

val noteTable = listOf(
    GuitarNote("C1", 32.70f), GuitarNote("C#1", 34.65f), GuitarNote("D1", 36.71f), GuitarNote("D#1", 38.89f), GuitarNote("E1", 41.20f),
    GuitarNote("F1", 43.65f), GuitarNote("F#1", 46.25f), GuitarNote("G1", 49.00f), GuitarNote("G#1", 51.91f), GuitarNote("A1", 55.00f),
    GuitarNote("A#1", 58.27f), GuitarNote("B1", 61.74f),
    GuitarNote("C2", 65.41f), GuitarNote("C#2", 69.30f), GuitarNote("D2", 73.42f), GuitarNote("D#2", 77.78f), GuitarNote("E2", 82.41f),
    GuitarNote("F2", 87.31f), GuitarNote("F#2", 92.50f), GuitarNote("G2", 98.00f), GuitarNote("G#2", 103.83f), GuitarNote("A2", 110.00f),
    GuitarNote("A#2", 116.54f), GuitarNote("B2", 123.47f),
    GuitarNote("C3", 130.81f), GuitarNote("C#3", 138.59f), GuitarNote("D3", 146.83f), GuitarNote("D#3", 155.56f), GuitarNote("E3", 164.81f),
    GuitarNote("F3", 174.61f), GuitarNote("F#3", 185.00f), GuitarNote("G3", 196.00f), GuitarNote("G#3", 207.65f), GuitarNote("A3", 220.00f),
    GuitarNote("A#3", 233.08f), GuitarNote("B3", 246.94f),
    GuitarNote("C4", 261.63f), GuitarNote("C#4", 277.18f), GuitarNote("D4", 293.66f), GuitarNote("D#4", 311.13f), GuitarNote("E4", 329.63f),
    GuitarNote("F4", 349.23f), GuitarNote("F#4", 369.99f), GuitarNote("G4", 392.00f), GuitarNote("G#4", 415.30f), GuitarNote("A4", 440.00f),
    GuitarNote("A#4", 466.16f), GuitarNote("B4", 493.88f)
)

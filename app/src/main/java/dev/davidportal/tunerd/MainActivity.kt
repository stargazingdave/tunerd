package dev.davidportal.tunerd

import android.Manifest
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread
import kotlin.math.abs
import kotlin.math.sqrt

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

fun applyLowPassFilter(input: ShortArray, cutoffHz: Float, sampleRate: Int): ShortArray {
    val output = ShortArray(input.size)
    val rc = 1.0f / (2 * Math.PI.toFloat() * cutoffHz)
    val dt = 1.0f / sampleRate
    val alpha = dt / (rc + dt)
    var prevOutput = 0f

    for (i in input.indices) {
        val inputSample = input[i].toFloat()
        val filtered = prevOutput + alpha * (inputSample - prevOutput)
        output[i] = filtered.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
        prevOutput = filtered
    }
    return output
}

class MainActivity : ComponentActivity() {
    private lateinit var closestNote: TextView
    private lateinit var closestFreq: TextView
    private lateinit var detectedFreq: TextView
    private lateinit var archedBar: ArchedBarView
    private lateinit var infoColumn: LinearLayout
    private val stringButtons = mutableListOf<Button>()
    private val customTuning = standardTuning.toMutableList()

    private val uiHandler = Handler(Looper.getMainLooper())
    private val RECORD_AUDIO_PERMISSION_CODE = 200

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        closestNote = findViewById(R.id.closestNote)
        closestFreq = findViewById(R.id.closestFreq)
        detectedFreq = findViewById(R.id.detectedFreq)
        archedBar = findViewById(R.id.archedBar)
        infoColumn = findViewById(R.id.infoColumn)

        for (i in 0 until 6) {
            val buttonId = resources.getIdentifier("stringButton$i", "id", packageName)
            val btn = findViewById<Button>(buttonId)
            stringButtons.add(btn)
            btn.text = customTuning[i].name
            btn.setOnClickListener {
                showNotePickerDialog(i)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.RECORD_AUDIO),
                RECORD_AUDIO_PERMISSION_CODE
            )
        } else {
            startAudioRecording()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == RECORD_AUDIO_PERMISSION_CODE &&
            grantResults.isNotEmpty() &&
            grantResults[0] == PackageManager.PERMISSION_GRANTED
        ) {
            startAudioRecording()
        } else {
            println("❌ Microphone permission denied by user.")
        }
    }

    fun applyHannWindow(samples: ShortArray): ShortArray {
        val windowed = ShortArray(samples.size)
        for (i in samples.indices) {
            val multiplier = 0.5f * (1 - kotlin.math.cos(2 * Math.PI * i / (samples.size - 1))).toFloat()
            windowed[i] = (samples[i] * multiplier).toInt().toShort()
        }
        return windowed
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudioRecording() {
        val sampleRate = 44100
        val bufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        val audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSize
        )

        audioRecord.startRecording()

        thread(start = true) {
            val buffer = ShortArray(bufferSize)

            while (true) {
                val read = audioRecord.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val rms = sqrt(buffer.map { it.toFloat() * it }.average()).toFloat()

                    if (rms > 300f) {
                        val filtered = applyLowPassFilter(buffer, 1000f, sampleRate)
                        val windowed = applyHannWindow(filtered)

                        val ampFactor = 6f
                        for (i in windowed.indices) {
                            val amplified = windowed[i] * ampFactor
                            windowed[i] = amplified.coerceIn(Short.MIN_VALUE.toFloat(), Short.MAX_VALUE.toFloat()).toInt().toShort()
                        }

                        val pitch = detectPitch(windowed, sampleRate)
                        if (pitch in 20f..1000f) {
                            val (targetNote, _) = getClosestTargetNote(pitch, customTuning)

                            uiHandler.post {
                                closestNote.text = targetNote.name
                                closestFreq.text = "%.2f Hz".format(targetNote.frequency)
                                detectedFreq.text = "%.2f Hz".format(pitch)
                                archedBar.updatePitch(pitch, targetNote.frequency)
                                infoColumn.visibility = View.VISIBLE
                            }
                        }
                    } else {
                        uiHandler.post {
                            infoColumn.visibility = View.INVISIBLE
                            archedBar.updatePitch(null, null)
                        }
                    }
                }
            }
        }
    }

    private fun detectPitch(buffer: ShortArray, sampleRate: Int): Float {
        var maxCorr = 0f
        var bestLag = -1
        val maxShift = buffer.size / 2

        for (lag in 20 until maxShift) {
            var corr = 0f
            for (i in 0 until buffer.size - lag) {
                corr += buffer[i] * buffer[i + lag].toFloat()
            }
            if (corr > maxCorr) {
                maxCorr = corr
                bestLag = lag
            }
        }

        return if (bestLag > 0) sampleRate.toFloat() / bestLag else -1f
    }

    private fun getClosestTargetNote(pitchHz: Float, targets: List<GuitarNote>): Pair<GuitarNote, Float> {
        var closest = targets[0]
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

    private fun getTuningOptionsFor(note: GuitarNote): List<GuitarNote> {
        val index = noteTable.indexOfFirst { it.name == note.name }
        if (index == -1) return listOf(note)

        val range = 4 // semitones above and below
        val start = (index - range).coerceAtLeast(0)
        val end = (index + range).coerceAtMost(noteTable.lastIndex)

        return noteTable.subList(start, end + 1)
    }

    private fun showNotePickerDialog(index: Int) {
        val currentNote = customTuning[index]
        val options = getTuningOptionsFor(currentNote)
        val noteNames = options.map { it.name }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Select note for string ${6 - index}")
            .setItems(noteNames) { _, which ->
                val selectedNote = options[which]
                customTuning[index] = selectedNote
                stringButtons[index].text = selectedNote.name
            }
            .show()
    }
}

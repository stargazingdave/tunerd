package dev.davidportal.tunerd

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : ComponentActivity() {
    private var audioInput: AudioInput? = null
    private val audioCfg by lazy { AudioConfig.default() }
    private lateinit var closestNote: TextView
    private lateinit var closestFreq: TextView
    private lateinit var detectedFreq: TextView
    private lateinit var archedBar: ArchedBarView
    private lateinit var infoColumn: LinearLayout
    private val stringButtons = mutableListOf<Button>()
    private val customTuning = standardTuning.toMutableList()
    private val engine = TunerEngine()
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
                val uiStringNumber = 6 - i
                // pass Activity explicitly
                showNotePickerDialog(
                    activity = this,                 // <-- IMPORTANT (Activity, not app context)
                    stringNumber = uiStringNumber,
                    currentNote = customTuning[i]
                ) { selected ->
                    customTuning[i] = selected
                    stringButtons[i].text = selected.name
                }
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
            startAudio()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioInput?.stop()
        engine.reset()
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
            startAudio()
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    private fun startAudio() {
        // ensure previous instance is stopped if called twice
        audioInput?.stop()

        audioInput = AudioInput(audioCfg).also { input ->
            input.start { buffer ->
                val result = engine.processFrame(
                    buffer = buffer,
                    sampleRate = audioCfg.sampleRate,  // <-- single source of truth
                    tuning = customTuning
                )

                uiHandler.post {
                    if (result.shouldHideNow) {
                        applyPitchState(
                            state = null,
                            closestNoteView = closestNote,
                            closestFreqView = closestFreq,
                            detectedFreqView = detectedFreq,
                            archedBarView = archedBar,
                            infoColumnView = infoColumn
                        )
                    } else {
                        result.state?.let { state ->
                            applyPitchState(
                                state,
                                closestNoteView = closestNote,
                                closestFreqView = closestFreq,
                                detectedFreqView = detectedFreq,
                                archedBarView = archedBar,
                                infoColumnView = infoColumn
                            )
                        }
                    }
                }
            }
        }
    }
}

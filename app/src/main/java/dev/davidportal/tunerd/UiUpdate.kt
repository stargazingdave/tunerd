package dev.davidportal.tunerd

import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

data class PitchState(
    val nearestNoteName: String?,
    val nearestNoteFreqHz: Float?,
    val detectedFreqHz: Float?
)

/** Apply a PitchState to the UI views (main thread). */
fun applyPitchState(
    state: PitchState?,
    closestNoteView: TextView,
    closestFreqView: TextView,
    detectedFreqView: TextView,
    archedBarView: ArchedBarView,
    infoColumnView: LinearLayout
) {
    if (state == null || state.nearestNoteName == null || state.nearestNoteFreqHz == null || state.detectedFreqHz == null) {
        infoColumnView.visibility = View.INVISIBLE
        archedBarView.updatePitch(null, null)
        return
    }

    closestNoteView.text = state.nearestNoteName
    closestFreqView.text = "%.2f Hz".format(state.nearestNoteFreqHz)
    detectedFreqView.text = "%.2f Hz".format(state.detectedFreqHz)
    archedBarView.updatePitch(state.detectedFreqHz, state.nearestNoteFreqHz)
    infoColumnView.visibility = View.VISIBLE
}

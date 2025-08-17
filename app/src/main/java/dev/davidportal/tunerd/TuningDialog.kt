// TuningDialog.kt
package dev.davidportal.tunerd

import android.app.Activity
import android.app.AlertDialog

fun showNotePickerDialog(
    activity: Activity,
    stringNumber: Int,
    currentNote: GuitarNote,
    onSelect: (GuitarNote) -> Unit
) {
    val options = getTuningOptionsFor(currentNote)
    val noteNames = options.map { it.name }.toTypedArray()

    AlertDialog.Builder(activity)
        .setTitle("Select note for string $stringNumber")
        .setItems(noteNames) { _, which -> onSelect(options[which]) }
        .show()
}

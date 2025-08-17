package dev.davidportal.tunerd

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlin.concurrent.thread
import kotlin.math.max

/**
 * Minimal wrapper around AudioRecord that pushes mono 16-bit frames to a callback on a worker thread.
 * Now uses AudioConfig for a single source of truth (sampleRate + bufferSizeInFrames).
 */
class AudioInput(
    private val config: AudioConfig = AudioConfig.default(),
    private val source: Int = MediaRecorder.AudioSource.MIC
) {
    private var audioRecord: AudioRecord? = null
    @Volatile private var running = false
    private var worker: Thread? = null

    val sampleRate: Int get() = config.sampleRate
    val bufferSizeInFrames: Int get() = config.bufferSizeInFrames

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(onFrame: (ShortArray) -> Unit) {
        if (running) return

        val bytesPerSample = if (config.audioFormat == AudioFormat.ENCODING_PCM_16BIT) 2 else 1
        val channels = if (config.channelConfig == AudioFormat.CHANNEL_IN_MONO) 1 else 2
        val bytesPerFrame = bytesPerSample * channels

        val minBytes = AudioRecord.getMinBufferSize(
            config.sampleRate, config.channelConfig, config.audioFormat
        ).coerceAtLeast(0)

        val requestedBytes = max(minBytes, config.bufferSizeInFrames * bytesPerFrame)

        val rec = AudioRecord(
            source,
            config.sampleRate,
            config.channelConfig,
            config.audioFormat,
            requestedBytes
        )
        require(rec.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed sr=${config.sampleRate}, bytes=$requestedBytes"
        }

        audioRecord = rec
        rec.startRecording()

        running = true
        worker = thread(name = "AudioInput") {
            val buffer = ShortArray(config.bufferSizeInFrames)
            while (running) {
                // READ_BLOCKING guarantees we fill the whole buffer (API 23+).
                val read = rec.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                if (read > 0) {
                    // Keep copy to allow downstream mutation without race with recorder thread.
                    onFrame(if (read == buffer.size) buffer.copyOf() else buffer.copyOf(read))
                }
            }
        }
    }

    fun stop() {
        running = false
        try { worker?.join(250) } catch (_: InterruptedException) {}
        worker = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
    }
}

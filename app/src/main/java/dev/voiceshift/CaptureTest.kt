package dev.voiceshift

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.sqrt

object CaptureTest {
    private fun chip(i: Int): Double {
        var v = i + 1
        v = v xor (v ushr 16); v *= 0x7feb352d
        v = v xor (v ushr 15); v *= 0x846ca68b.toInt()
        v = v xor (v ushr 16)
        return if (v and 1 != 0) 1.0 else -1.0
    }
    // Conservative: requires very high matching score and HAL consumption.
    // Only tests these two AudioRecord sources, never certifies other apps.
    private fun correlation(pcm: ShortArray, count: Int): Double {
        if (count < 1023 * 48) return 0.0
        val observed = DoubleArray(1023) { k ->
            var sum = 0.0
            for (i in 0 until 48) sum += pcm[k * 48 + i]
            sum / 48.0
        }
        val mean = observed.average()
        val ref = DoubleArray(1023) { chip(it) }; val refMean = ref.average()
        var energy = 0.0; var refEnergy = 0.0
        for (i in observed.indices) { observed[i] -= mean; energy += observed[i]*observed[i]; ref[i] -= refMean; refEnergy += ref[i]*ref[i] }
        if (sqrt(energy / 1023) < 200) return 0.0
        var best = 0.0
        for (phase in 0 until 1023) {
            var dot = 0.0
            for (i in observed.indices) dot += observed[i] * ref[(i + phase) % 1023]
            best = maxOf(best, abs(dot) / sqrt(energy * refEnergy))
        }
        return best
    }
    @Suppress("MissingPermission")
    suspend fun run(source: Int): String {
        val label = if (source == MediaRecorder.AudioSource.MIC) "MIC" else "VOICE_COMMUNICATION"
        val started = Native.startProbe()
        if (started != "SENDING") return "$label: $started"
        var record: AudioRecord? = null
        try {
            val min = AudioRecord.getMinBufferSize(48000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (min <= 0) return "$label: 48 kHz mono capture unsupported"
            record = AudioRecord.Builder().setAudioSource(source)
                .setAudioFormat(AudioFormat.Builder().setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(maxOf(min, 48000)).build()
            if (record.state != AudioRecord.STATE_INITIALIZED) return "$label: AudioRecord initialization failed"
            record.startRecording()
            val pcm = ShortArray(96000)
            var count = 0
            val deadline = SystemClock.elapsedRealtime() + 4000
            while (count < pcm.size && SystemClock.elapsedRealtime() < deadline) {
                coroutineContext.ensureActive()
                val n = record.read(pcm, count, minOf(4800, pcm.size-count), AudioRecord.READ_NON_BLOCKING)
                if (n < 0) return "$label: capture error $n"
                count += n
                if (n == 0) delay(5)
            }
            val score = correlation(pcm, count)
            val stats = Native.stats()
            val passed = score >= 0.90 && stats[1] > 48000 && stats[0] > 48000
            return "$label: ${if(passed) "PCM injection matched" else "FAILED / unverified"}\nCorrelation %.3f · captured %d · HAL consumed %d · overflows %d\nThis is a transport test, not converted-voice acceptance.".format(score, count, stats[1], stats[2])
        } finally {
            runCatching { record?.stop() }; record?.release(); Native.stopProbe()
        }
    }
}

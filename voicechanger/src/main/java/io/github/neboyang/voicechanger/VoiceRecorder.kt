package io.github.neboyang.voicechanger

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** 一次녹음的结果。 */
data class RecordingResult(
    /** 裸 PCM 文件（16-bit LE，参数见 [config]）。 */
    val file: File,
    /** 녹음时长（毫秒，不含暂停时间）。 */
    val durationMs: Long,
    val config: AudioConfig,
)

/**
 * PCM 녹음器。数据边录边写入文件（1.x 版本全量驻留内存，长녹음会 OOM）。
 *
 * - 状态通过 [state] 观察，실시간音量通过 [amplitude]（0~1 的 RMS 归一化值）观察
 * - 暂停采用锁等待实现（1.x 为忙等空转），暂停期间끔采集、不占用마이크缓冲
 * - 实例可复用：stop 之后可再次 start
 *
 * 调用方需自行申请并持有 RECORD_AUDIO 运行时权限。
 */
class VoiceRecorder(val config: AudioConfig = AudioConfig()) {

    enum class State { IDLE, RECORDING, PAUSED }

    private val _state = MutableStateFlow(State.IDLE)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _amplitude = MutableStateFlow(0f)
    /** 当前音量，RMS 归一化到 0~1，可直接绑定进度条。 */
    val amplitude: StateFlow<Float> = _amplitude.asStateFlow()

    private val lock = Object()
    private val lifecycleLock = Any()

    @Volatile private var stopRequested = false
    @Volatile private var pauseRequested = false
    @Volatile private var cancelRequested = false
    private var pending: CompletableDeferred<RecordingResult>? = null
    @Volatile private var activeRecord: AudioRecord? = null
    @Volatile private var worker: Thread? = null
    @Volatile internal var activeOutputFile: File? = null
        private set

    /**
     * 시작녹음，PCM 数据流式写入 [outputFile]。
     *
     * @throws IllegalStateException 已在녹음中，或 AudioRecord 初始化실패（通常是未授予녹음权限）
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start(outputFile: File) {
        synchronized(lifecycleLock) {
            check(_state.value == State.IDLE && worker == null) { "녹음器忙，当前状态: ${_state.value}" }

            val channelMask = if (config.channels == 1)
                AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
            val minBuffer = AudioRecord.getMinBufferSize(
                config.sampleRate, channelMask, AudioFormat.ENCODING_PCM_16BIT)
            check(minBuffer > 0) { "设备不支持该音频配置: $config" }

            val audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                config.sampleRate, channelMask,
                AudioFormat.ENCODING_PCM_16BIT, minBuffer * 2)
            if (audioRecord.state != AudioRecord.STATE_INITIALIZED) {
                audioRecord.release()
                throw IllegalStateException("AudioRecord 初始化실패，请确认已授予 RECORD_AUDIO 权限")
            }

            stopRequested = false
            pauseRequested = false
            cancelRequested = false
            val deferred = CompletableDeferred<RecordingResult>()
            pending = deferred
            activeRecord = audioRecord
            activeOutputFile = outputFile
            _state.value = State.RECORDING

            worker = Thread({ recordLoop(audioRecord, outputFile, deferred) }, "VoiceRecorder").also { it.start() }
        }
    }

    /** 暂停녹음。暂停期间释放采集，不会丢数据也不空转 CPU。 */
    fun pause() {
        if (_state.value == State.RECORDING) {
            pauseRequested = true
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /** 恢复녹음。 */
    fun resume() {
        if (pauseRequested) {
            pauseRequested = false
            synchronized(lock) { lock.notifyAll() }
        }
    }

    /**
     * 중지녹음并等待收尾완료。
     *
     * @return 녹음结果；녹음线程内发生的异常会在此处抛出
     */
    suspend fun stop(): RecordingResult {
        val deferred = synchronized(lifecycleLock) {
            pending ?: throw IllegalStateException("当前没有进行中的녹음")
        }
        requestStop(cancel = false)
        return try {
            deferred.await()
        } finally {
            synchronized(lifecycleLock) {
                if (pending === deferred) pending = null
            }
        }
    }

    /**
     * 取消当前녹음并立即请求底层采集중지。可重复调用，不阻塞调用线程。
     * 未완료的 [stop] 会收到 [CancellationException]，临时녹음文件会被删除。
     */
    fun cancel() {
        requestStop(cancel = true)
    }

    private fun requestStop(cancel: Boolean) {
        val record = synchronized(lifecycleLock) {
            if (worker == null) return
            if (cancel) cancelRequested = true
            stopRequested = true
            pauseRequested = false
            activeRecord
        }
        synchronized(lock) { lock.notifyAll() }
        runCatching { record?.stop() }
    }

    private fun recordLoop(
        audioRecord: AudioRecord,
        outputFile: File,
        deferred: CompletableDeferred<RecordingResult>,
    ) {
        var totalFrames = 0L
        var result: RecordingResult? = null
        var failure: Throwable? = null
        try {
            outputFile.parentFile?.mkdirs()
            if (!stopRequested) audioRecord.startRecording()

            // 约 100ms 一个读取块
            val buffer = ShortArray(config.sampleRate / 10 * config.channels)
            val byteBuffer = ByteBuffer.allocate(buffer.size * 2).order(ByteOrder.LITTLE_ENDIAN)

            BufferedOutputStream(FileOutputStream(outputFile)).use { out ->
                while (!stopRequested) {
                    if (pauseRequested) {
                        audioRecord.stop()
                        _amplitude.value = 0f
                        _state.value = State.PAUSED
                        synchronized(lock) {
                            while (pauseRequested && !stopRequested) lock.wait()
                        }
                        if (stopRequested) break
                        audioRecord.startRecording()
                        _state.value = State.RECORDING
                    }

                    val read = audioRecord.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        if (stopRequested) break
                        throw IllegalStateException("AudioRecord.read 실패: $read")
                    }
                    if (read == 0) continue

                    _amplitude.value = rms(buffer, read)

                    byteBuffer.clear()
                    byteBuffer.asShortBuffer().put(buffer, 0, read)
                    out.write(byteBuffer.array(), 0, read * 2)
                    totalFrames += read / config.channels
                }
            }
            result = RecordingResult(
                outputFile, totalFrames * 1000L / config.sampleRate, config)
        } catch (t: Throwable) {
            failure = t
        } finally {
            runCatching { audioRecord.stop() }
            runCatching { audioRecord.release() }
            _amplitude.value = 0f
            _state.value = State.IDLE
            var wasCancelled = false
            synchronized(lifecycleLock) {
                wasCancelled = cancelRequested
                if (activeRecord === audioRecord) activeRecord = null
                if (activeOutputFile == outputFile) activeOutputFile = null
                if (worker === Thread.currentThread()) worker = null
            }
            if (wasCancelled) outputFile.delete()
            val error = if (wasCancelled) CancellationException("녹음已取消") else failure
            if (error != null) {
                deferred.completeExceptionally(error)
            } else {
                deferred.complete(checkNotNull(result))
            }
        }
    }

    private fun rms(buffer: ShortArray, length: Int): Float {
        var sum = 0.0
        for (i in 0 until length) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        return (sqrt(sum / length) / Short.MAX_VALUE).toFloat().coerceIn(0f, 1f)
    }
}

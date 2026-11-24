package com.whispercppdemo.recorder

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

const val SAMPLE_RATE = 16000
private const val CHUNK = 1024
private const val FFT_N = 2048
const val BANDS = 48
private const val F_LO = 100.0
private const val F_HI = 8000.0

/** Live analysis of the latest audio: loudness and a log-spaced spectrum (same math as the desktop app). */
class AudioFrame(val db: Float, val bands: FloatArray, val waveform: FloatArray) {
    companion object {
        val SILENT = AudioFrame(-60f, FloatArray(BANDS), FloatArray(CHUNK))
    }
}

/**
 * Records 16 kHz mono PCM in memory. Every ~64 ms chunk is analysed and reported via [onFrame],
 * which drives the wave / spectrum. [stop] returns all samples as floats ready for whisper.
 */
class Recorder {
    private var thread: Thread? = null
    private val quit = AtomicBoolean(false)
    private var samples = ShortArray(0)
    private var size = 0

    @SuppressLint("MissingPermission")
    fun start(onFrame: (AudioFrame) -> Unit, onError: (Exception) -> Unit) {
        quit.set(false)
        samples = ShortArray(SAMPLE_RATE * 30)
        size = 0
        thread = Thread({
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
                val record = AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(minBuf, CHUNK * 4)
                )
                val analyzer = Analyzer()
                val buf = ShortArray(CHUNK)
                try {
                    record.startRecording()
                    while (!quit.get()) {
                        val n = record.read(buf, 0, buf.size)
                        if (n <= 0) throw RuntimeException("AudioRecord.read returned $n")
                        append(buf, n)
                        onFrame(analyzer.push(buf, n))
                    }
                    record.stop()
                } finally {
                    record.release()
                }
            } catch (e: Exception) {
                onError(e)
            }
        }, "AudioRecorder").also { it.start() }
    }

    /** Stops recording and returns the captured audio normalised to [-1, 1]. */
    fun stop(): FloatArray {
        quit.set(true)
        thread?.join()
        thread = null
        return FloatArray(size) { samples[it] / 32768f }
    }

    val seconds: Float get() = size / SAMPLE_RATE.toFloat()

    private fun append(buf: ShortArray, n: Int) {
        if (size + n > samples.size) samples = samples.copyOf(samples.size * 2)
        System.arraycopy(buf, 0, samples, size, n)
        size += n
    }
}

/** Keeps the last FFT_N samples; RMS → dBFS and Hann-windowed FFT → BANDS log bands in 0..1. */
private class Analyzer {
    private val ring = FloatArray(FFT_N)
    private val window = FloatArray(FFT_N) { (0.5 - 0.5 * cos(2 * PI * it / (FFT_N - 1))).toFloat() }
    private val re = DoubleArray(FFT_N)
    private val im = DoubleArray(FFT_N)
    private val bandIdx: List<IntRange>

    init {
        val binHz = SAMPLE_RATE.toDouble() / FFT_N
        val edges = DoubleArray(BANDS + 1) { F_LO * Math.exp(ln(F_HI / F_LO) * it / BANDS) }
        bandIdx = List(BANDS) { i ->
            val a = (edges[i] / binHz).toInt()
            val b = max((edges[i + 1] / binHz).toInt(), a + 1)
            a until b
        }
    }

    fun push(buf: ShortArray, n: Int): AudioFrame {
        System.arraycopy(ring, n, ring, 0, FFT_N - n)
        var sum = 0.0
        for (i in 0 until n) {
            val v = buf[i] / 32768f
            ring[FFT_N - n + i] = v
            sum += v * v
        }
        val rms = sqrt(sum / n) + 1e-9
        val db = max(-60.0, 20 * log10(rms)).toFloat()

        for (i in 0 until FFT_N) { re[i] = (ring[i] * window[i]).toDouble(); im[i] = 0.0 }
        fft(re, im)
        val bands = FloatArray(BANDS) { b ->
            var m = 0.0
            for (k in bandIdx[b]) m += sqrt(re[k] * re[k] + im[k] * im[k])
            m /= bandIdx[b].count()
            ((20 * log10(m + 1e-6) + 20) / 50).toFloat().coerceIn(0f, 1f)
        }
        return AudioFrame(db, bands, ring.copyOfRange(FFT_N - CHUNK, FFT_N))
    }

    /** In-place iterative radix-2 FFT. */
    private fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }
}

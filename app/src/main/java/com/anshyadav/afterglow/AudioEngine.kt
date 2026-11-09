package com.anshyadav.afterglow

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import kotlin.math.*

/**
 * Turns the tablet's audio output into band levels, a waveform and beat events.
 * Reads the output mix (session 0); the microphone is never opened. When Android refuses
 * that or the feed is silent, a procedural signal keeps the screen moving, and [live] is false.
 */
class AudioEngine {
    companion object { const val N = 56; const val W = 240 }

    val bands = FloatArray(N)
    val peaks = FloatArray(N)
    val wave = FloatArray(W)
    var bass = 0f; private set
    var energy = 0f; private set
    var beat = 0f; private set
    @Volatile var live = false; private set

    private val target = FloatArray(N)
    private val waveTarget = FloatArray(W)
    private val peakHold = FloatArray(N)
    @Volatile private var pendingBeat = false
    @Volatile private var rawBass = 0f
    @Volatile private var rawEnergy = 0f

    private var viz: Visualizer? = null
    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var fftBytes = ByteArray(0)
    private var waveBytes = ByteArray(0)
    private var mags = FloatArray(0)
    private var rate = 44100f
    private var agc = 0.5f
    private var silent = 0
    private var lastAudible = false
    private val bassHist = FloatArray(43)
    private var histPos = 0
    private var lastBeat = 0L
    private var simT = 0f
    private var simBeatT = 0f

    fun start(allowLive: Boolean) {
        stop()
        if (!allowLive) return
        try {
            val v = Visualizer(0)
            v.enabled = false
            v.captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(1024)
            v.scalingMode = Visualizer.SCALING_MODE_NORMALIZED
            v.measurementMode = Visualizer.MEASUREMENT_MODE_NONE
            v.enabled = true
            fftBytes = ByteArray(v.captureSize)
            waveBytes = ByteArray(v.captureSize)
            mags = FloatArray(v.captureSize / 2)
            rate = v.samplingRate / 1000f
            viz = v
            silent = 0
            live = true
            val t = HandlerThread("audio-poll").also { it.start() }
            thread = t
            handler = Handler(t.looper).also { it.post(poll) }
        } catch (_: Exception) {
            stop()
        }
    }

    fun stop() {
        val h = handler
        val t = thread
        val v = viz
        handler = null; thread = null; viz = null; live = false
        h?.removeCallbacksAndMessages(null)
        t?.quitSafely()
        try { t?.join(150) } catch (_: InterruptedException) {}
        try { v?.enabled = false; v?.release() } catch (_: Exception) {}
    }

    private val poll = object : Runnable {
        override fun run() {
            val v = viz ?: return
            try {
                if (v.getFft(fftBytes) == Visualizer.SUCCESS) analyze()
                if (v.getWaveForm(waveBytes) == Visualizer.SUCCESS) {
                    val step = waveBytes.size / W
                    for (i in 0 until W) waveTarget[i] = ((waveBytes[i * step].toInt() and 255) - 128) / 128f
                }
            } catch (_: Exception) {}
            handler?.postDelayed(this, 26)
        }
    }

    private fun analyze() {
        val n = fftBytes.size / 2
        var sum = 0f
        for (k in 1 until n) {
            val re = fftBytes[2 * k].toFloat()
            val im = fftBytes[2 * k + 1].toFloat()
            val m = sqrt(re * re + im * im)
            mags[k] = m
            sum += m
        }
        silent = if (sum < 2f) silent + 1 else 0
        live = silent < 110
        val audible = sum >= 2f
        if (audible != lastAudible) {
            lastAudible = audible
            Log.i("Afterglow", if (audible) "audio feed has signal" else "audio feed silent")
        }

        val binHz = rate / fftBytes.size
        var frameMax = 0f
        for (b in 0 until N) {
            val f0 = 42f * (14000f / 42f).pow(b / N.toFloat())
            val f1 = 42f * (14000f / 42f).pow((b + 1) / N.toFloat())
            val lo = f0 / binHz
            val hi = f1 / binHz
            var m = 0f
            if (hi - lo < 1f) {
                val i = lo.toInt().coerceIn(1, n - 2)
                // The lowest band sits just under bin 1; an unclamped t goes negative and can
                // push the log below into NaN, which would poison every value downstream.
                val t = (lo - i).coerceIn(0f, 1f)
                m = mags[i] * (1 - t) + mags[i + 1] * t
            } else {
                for (i in lo.toInt().coerceIn(1, n - 1)..hi.toInt().coerceIn(1, n - 1)) m = max(m, mags[i])
            }
            val tilt = 1f + 1.9f * b / N.toFloat()
            val db = 20f * log10(1f + m * tilt)
            val v = ((db - 4f) / 36f).coerceIn(0f, 1f)
            target[b] = v
            frameMax = max(frameMax, v)
        }
        agc = max(frameMax, agc * 0.997f).coerceAtLeast(0.32f)
        var e = 0f
        for (b in 0 until N) {
            val v = (target[b] / agc).coerceIn(0f, 1f)
            target[b] = v * v * (1.6f - 0.6f * v)
            e += target[b]
        }
        rawEnergy = e / N

        val b0 = (40f / binHz).toInt().coerceAtLeast(1)
        val b1 = (140f / binHz).toInt().coerceAtLeast(b0 + 1)
        var low = 0f
        for (k in b0..b1) low += mags[k] * mags[k]
        low /= (b1 - b0 + 1)
        val avg = bassHist.average().toFloat()
        bassHist[histPos] = low
        histPos = (histPos + 1) % bassHist.size
        rawBass = (target[0] + target[1] + target[2] + target[3]) / 4f
        val now = System.currentTimeMillis()
        if (low > avg * 1.42f && low > 30f && now - lastBeat > 230) {
            lastBeat = now
            pendingBeat = true
        }
    }

    private fun simulate(dt: Float, playing: Boolean) {
        if (!playing) {
            target.fill(0f); waveTarget.fill(0f); rawBass = 0f; rawEnergy = 0f
            return
        }
        simT += dt
        simBeatT += dt
        val spb = 60f / 118f
        if (simBeatT >= spb) { simBeatT -= spb; pendingBeat = true }
        val kick = exp(-simBeatT * 7.5f)
        var e = 0f
        for (b in 0 until N) {
            val x = b / N.toFloat()
            val motion = 0.5f + 0.5f * sin(simT * (1.7f + x * 5f) + b * 0.71f) * cos(simT * 0.83f + b * 0.23f)
            val shimmer = 0.5f + 0.5f * sin(simT * 11f + b * 1.9f)
            val shape = (1f - x).pow(0.8f) * 0.62f + 0.14f
            val v = (shape * (0.42f + 0.58f * motion) + kick * (1f - x).pow(4f) * 0.55f + shimmer * x * 0.1f).coerceIn(0f, 1f)
            target[b] = v
            e += v
        }
        rawEnergy = e / N
        rawBass = (target[0] + target[1] + target[2] + target[3]) / 4f
        for (i in 0 until W) {
            val x = i / W.toFloat() * 2f * PI.toFloat()
            waveTarget[i] = (sin(x * 3f + simT * 5.1f) * 0.32f + sin(x * 9f - simT * 7.3f) * 0.17f +
                sin(x * 23f + simT * 13f) * 0.08f) * (0.4f + kick * 0.8f)
        }
    }

    /** Called once per display frame, so 40 Hz audio data still moves at 60 fps. */
    fun tick(dt: Float, playing: Boolean) {
        if (!live) simulate(dt, playing)
        else if (!playing) { target.fill(0f); waveTarget.fill(0f); rawBass = 0f; rawEnergy = 0f }
        for (b in 0 until N) {
            bands[b] = smooth(bands[b], target[b], dt, if (target[b] > bands[b]) 0.022f else 0.16f)
            if (bands[b] >= peaks[b]) {
                peaks[b] = bands[b]; peakHold[b] = 0.28f
            } else {
                peakHold[b] -= dt
                if (peakHold[b] <= 0f) peaks[b] = max(0f, peaks[b] - dt * 0.65f)
            }
        }
        for (i in 0 until W) wave[i] = smooth(wave[i], waveTarget[i], dt, 0.018f)
        bass = smooth(bass, rawBass, dt, if (rawBass > bass) 0.03f else 0.22f)
        energy = smooth(energy, rawEnergy, dt, 0.35f)
        if (pendingBeat) { pendingBeat = false; beat = 1f }
        else beat *= exp(-dt / 0.17f)
    }
}

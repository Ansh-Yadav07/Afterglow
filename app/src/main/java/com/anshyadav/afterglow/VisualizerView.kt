package com.anshyadav.afterglow

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*

class VisualizerView(ctx: Context, private val u: Float, private val audio: AudioEngine) : View(ctx) {
    companion object {
        val NAMES = arrayOf("NO VISUAL", "SPECTRUM", "HALO", "SCOPE", "RIDGE")
        private const val ROWS = 24
        private const val COLS = 72
    }

    var mode = 1
        set(v) { field = v; shader = null; invalidate() }

    private var accent = PAPER
    private var accent2 = PAPER
    private var shader: Shader? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val pts = FloatArray(4 * 240)
    private val peakPts = FloatArray(4 * 120)
    private val trail = Array(3) { FloatArray(AudioEngine.W) }
    private val history = Array(ROWS) { FloatArray(COLS) }
    private var head = 0
    private var histClock = 0f

    var artCx = 0f
    var artCy = 0f
    var artHalf = 0f
    var morph = 0f
    private var beat = 0f

    fun setColors(a: Int, b: Int) { accent = a; accent2 = b; shader = null }

    fun tick(dt: Float) {
        beat = audio.beat
        if (mode == 4) {
            histClock += dt
            if (histClock > 0.055f) {
                histClock = 0f
                head = (head + ROWS - 1) % ROWS
                val row = history[head]
                for (i in 0 until COLS) {
                    val x = abs(i / (COLS - 1f) * 2f - 1f)
                    row[i] = audio.bands[(x * (AudioEngine.N - 1)).toInt()]
                }
            }
        }
        if (mode == 3) {
            val t = trail[2]
            trail[2] = trail[1]; trail[1] = trail[0]; trail[0] = t
            System.arraycopy(audio.wave, 0, t, 0, AudioEngine.W)
        }
        if (mode != 0) invalidate()
    }

    override fun onDraw(c: Canvas) {
        when (mode) {
            1 -> spectrum(c)
            2 -> halo(c)
            3 -> scope(c)
            4 -> ridge(c)
        }
    }

    private fun withAlpha(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

    private fun spectrum(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val n = AudioEngine.N
        val total = n * 2
        val step = w / total
        val maxH = 168f * u
        if (shader == null) shader = LinearGradient(0f, h, 0f, h - maxH, withAlpha(accent, 235), withAlpha(accent2, 26), Shader.TileMode.CLAMP)
        line.shader = shader
        line.strokeWidth = step * 0.44f
        line.alpha = 255
        var k = 0; var q = 0
        for (i in 0 until total) {
            val fromMid = if (i < n) n - 1 - i else i - n
            val x = (i + 0.5f) * step
            pts[k++] = x; pts[k++] = h + 4f
            // Silent bars drop fully off-screen, so a paused song leaves a clean bottom edge.
            val v = audio.bands[fromMid]
            pts[k++] = x; pts[k++] = if (v < 0.006f) h + 24f * u else h - (5f * u + v * maxH)
            if (audio.peaks[fromMid] > 0.012f) {
                val py = h - (9f * u + audio.peaks[fromMid] * maxH)
                peakPts[q++] = x; peakPts[q++] = py
                peakPts[q++] = x; peakPts[q++] = py
            }
        }
        c.drawLines(pts, 0, k, line)
        line.shader = null
        line.color = PAPER
        line.alpha = 120
        c.drawLines(peakPts, 0, q, line)
    }

    private fun halo(c: Canvas) {
        val count = 200
        val pw = lerp(9f, 2f, morph)
        var k = 0
        val gap = 12f * u + beat * 4f * u
        val maxLen = 72f * u
        line.shader = null
        line.color = accent
        line.alpha = 235
        line.strokeWidth = 3.1f * u
        for (i in 0 until count) {
            val th = i / count.toFloat() * 2f * PI.toFloat()
            val cs = cos(th); val sn = sin(th)
            val ac = abs(cs); val `as` = abs(sn)
            val rad = artHalf / (ac.pow(pw) + `as`.pow(pw)).pow(1f / pw)
            val sx = rad * cs; val sy = rad * sn
            // Normal of the rounded square, so bars stand straight off each edge.
            var nx = sign(sx) * ac.pow(pw - 1f)
            var ny = sign(sy) * `as`.pow(pw - 1f)
            val d = hypot(nx, ny).coerceAtLeast(0.0001f)
            nx /= d; ny /= d
            val f = abs(i / count.toFloat() * 2f - 1f)
            val v = audio.bands[(f * (AudioEngine.N - 1)).toInt()]
            val x0 = artCx + sx + nx * gap
            val y0 = artCy + sy + ny * gap
            pts[k++] = x0; pts[k++] = y0
            pts[k++] = x0 + nx * (3f * u + v * maxLen); pts[k++] = y0 + ny * (3f * u + v * maxLen)
        }
        c.drawLines(pts, 0, k, line)
    }

    private fun scope(c: Canvas) {
        val w = width.toFloat()
        val cy = height - 96f * u
        val amp = 84f * u
        line.shader = null
        for (t in 2 downTo 0) {
            val wave = trail[t]
            var k = 0
            for (i in 0 until AudioEngine.W - 1) {
                pts[k++] = i * w / (AudioEngine.W - 1f); pts[k++] = cy + wave[i] * amp
                pts[k++] = (i + 1) * w / (AudioEngine.W - 1f); pts[k++] = cy + wave[i + 1] * amp
            }
            line.strokeWidth = if (t == 0) 2.6f * u else 7f * u
            line.color = if (t == 0) accent else accent2
            line.alpha = if (t == 0) 245 else 34 - t * 9
            c.drawLines(pts, 0, k, line)
        }
    }

    private fun ridge(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        line.shader = null
        line.strokeWidth = 2f * u
        val flat = FloatArray(4 * (COLS - 1))
        for (r in ROWS - 1 downTo 0) {
            val z = r / (ROWS - 1f)
            val row = history[(head + r) % ROWS]
            val y = lerp(h * 1.0f, h * 0.81f, z.pow(0.72f))
            val span = lerp(1.2f, 0.72f, z)
            val amp = lerp(86f, 12f, z) * u
            var k = 0
            for (i in 0 until COLS - 1) {
                val a = i / (COLS - 1f) - 0.5f
                val b = (i + 1) / (COLS - 1f) - 0.5f
                flat[k++] = w / 2 + a * w * span; flat[k++] = y - row[i] * amp
                flat[k++] = w / 2 + b * w * span; flat[k++] = y - row[i + 1] * amp
            }
            line.color = if (r < ROWS / 2) accent else accent2
            line.alpha = (lerp(205f, 18f, z)).toInt()
            c.drawLines(flat, 0, k, line)
        }
    }
}

package com.anshyadav.afterglow

import android.content.Context
import android.graphics.*
import android.view.View
import kotlin.math.*
import kotlin.random.Random

/** The album cover, or the same cover pressed onto a spinning record with a tonearm that follows the song. */
class ArtView(ctx: Context) : View(ctx) {
    private class Art(val bmp: Bitmap, val glow: Bitmap) {
        val shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
    }

    private val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val glowPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val m = Matrix()
    private val rect = RectF()
    private val desat = ColorMatrix()

    private var cur: Art? = null
    private var prev: Art? = null
    private var change = 1f
    private var t = 0f
    private var beat = 0f
    private var songFrac = 0f

    var vinylTarget = false
    private var vinyl = 0f
    private var spin = 0f
    private var spinSpeed = 0f
    private var arm = 0f
    private var playing = false
    private var sat = 1f

    private var record: Bitmap? = null
    private var sheen: Shader? = null
    private var accent = PAPER

    val morph get() = vinyl

    /** Half the side of the cover, in pixels. Set from the screen layout. */
    var half = 0f

    fun setArt(square: Bitmap, glow: Bitmap, animate: Boolean) {
        if (animate && cur != null) { prev = cur; change = 0f } else { prev = null; change = 1f }
        cur = Art(square, glow)
        invalidate()
    }

    fun setAccent(c: Int) {
        accent = c
        sheen = null
    }

    fun tick(dt: Float, isPlaying: Boolean, beatIn: Float, fraction: Float) {
        t += dt
        playing = isPlaying
        beat = beatIn
        songFrac = fraction
        if (change < 1f) change = min(1f, change + dt / 0.85f)
        vinyl = smooth(vinyl, if (vinylTarget) 1f else 0f, dt, 0.2f)
        if (abs(vinyl - (if (vinylTarget) 1f else 0f)) < 0.002f) vinyl = if (vinylTarget) 1f else 0f
        spinSpeed = smooth(spinSpeed, if (isPlaying) 84f else 0f, dt, if (isPlaying) 0.6f else 1.1f)
        spin = (spin + spinSpeed * dt) % 360f
        arm = smooth(arm, if (isPlaying) 1f else 0f, dt, 0.42f)
        sat = smooth(sat, if (isPlaying) 1f else 0.55f, dt, 0.4f)
        val flat = 1f - vinyl
        rotationY = sin(t * 0.43f) * 2.3f * flat
        rotationX = cos(t * 0.31f) * 1.7f * flat
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        if (w > 0 && half > 0f) {
            record = buildRecord((half * 2).toInt())
            cameraDistance = 12000f * resources.displayMetrics.density
        }
    }

    private fun buildRecord(d: Int): Bitmap {
        val b = Bitmap.createBitmap(d, d, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = d / 2f
        val q = Paint(Paint.ANTI_ALIAS_FLAG)
        q.color = 0xFF0D0C0C.toInt()
        c.drawCircle(r, r, r, q)
        q.style = Paint.Style.STROKE
        val rng = Random(7)
        var ring = r * 0.985f
        var band = 0
        while (ring > r * 0.40f) {
            q.strokeWidth = 1f
            q.color = Color.argb(14 + rng.nextInt(22), 255, 250, 240)
            c.drawCircle(r, r, ring, q)
            ring -= 2.1f
            if (++band % 31 == 0) {
                q.strokeWidth = 5f
                q.color = 0xFF050505.toInt()
                c.drawCircle(r, r, ring - 3f, q)
                ring -= 7f
            }
        }
        q.strokeWidth = 2f
        q.color = Color.argb(70, 255, 250, 240)
        c.drawCircle(r, r, r - 1f, q)
        return b
    }

    override fun onDraw(c: Canvas) {
        val a = cur ?: return
        val cx = width / 2f
        val cy = height / 2f
        val pulse = 1f + beat * 0.016f
        c.save()
        c.scale(pulse, pulse, cx, cy)
        if (vinyl < 0.999f) drawCover(c, a, prev, cx, cy, 1f - vinyl)
        if (vinyl > 0.001f) drawVinyl(c, a, cx, cy, vinyl)
        c.restore()
    }

    private fun applySat() {
        if (sat > 0.995f) { shaderPaint.colorFilter = null; return }
        desat.setSaturation(sat)
        shaderPaint.colorFilter = ColorMatrixColorFilter(desat)
    }

    private fun drawCover(c: Canvas, a: Art, old: Art?, cx: Float, cy: Float, alpha: Float) {
        val e = outExpo(change)
        if (old != null && change < 0.7f) drawOneCover(c, old, cx - e * half * 0.42f, cy, 1f - e * 0.08f, alpha * (1f - e))
        val k = if (old != null && change < 1f) e else 1f
        drawOneCover(c, a, cx + (1f - k) * half * 0.55f, cy, 0.9f + 0.1f * k, alpha * k)
    }

    private fun drawOneCover(c: Canvas, a: Art, x: Float, y: Float, scale: Float, alpha: Float) {
        val s = half * scale
        val spread = s * 1.62f
        rect.set(x - spread, y - spread + s * 0.08f, x + spread, y + spread + s * 0.08f)
        glowPaint.alpha = (alpha * (190 + beat * 65)).toInt().coerceIn(0, 255)
        c.drawBitmap(a.glow, null, rect, glowPaint)

        m.setScale(2 * s / a.bmp.width, 2 * s / a.bmp.height)
        m.postTranslate(x - s, y - s)
        a.shader.setLocalMatrix(m)
        shaderPaint.shader = a.shader
        applySat()
        shaderPaint.alpha = (alpha * 255).toInt()
        val r = s * 0.028f
        rect.set(x - s, y - s, x + s, y + s)
        c.drawRoundRect(rect, r, r, shaderPaint)

        p.style = Paint.Style.STROKE
        p.strokeWidth = 1.5f
        p.color = Color.argb((alpha * 34).toInt(), 255, 255, 255)
        c.drawRoundRect(rect, r, r, p)
        p.style = Paint.Style.FILL
    }

    private fun drawVinyl(c: Canvas, a: Art, cx: Float, cy: Float, alpha: Float) {
        val rec = record ?: return
        val R = half
        val grow = 0.86f + 0.14f * alpha
        c.save()
        c.scale(grow, grow, cx, cy)
        val glowR = R * 1.7f
        rect.set(cx - glowR, cy - glowR + R * 0.06f, cx + glowR, cy + glowR + R * 0.06f)
        glowPaint.alpha = (alpha * (150 + beat * 80)).toInt().coerceIn(0, 255)
        c.drawBitmap(a.glow, null, rect, glowPaint)

        c.save()
        c.rotate(spin, cx, cy)
        p.alpha = (alpha * 255).toInt()
        p.isFilterBitmap = true
        rect.set(cx - R, cy - R, cx + R, cy + R)
        c.drawBitmap(rec, null, rect, p)

        val lab = R * 0.40f
        val bmp = a.bmp
        m.setScale(2 * lab / bmp.width, 2 * lab / bmp.height)
        m.postTranslate(cx - lab, cy - lab)
        a.shader.setLocalMatrix(m)
        shaderPaint.shader = a.shader
        applySat()
        shaderPaint.alpha = (alpha * 255).toInt()
        c.drawCircle(cx, cy, lab, shaderPaint)
        if (prev != null && change < 1f) {
            val q = prev!!
            m.setScale(2 * lab / q.bmp.width, 2 * lab / q.bmp.height)
            m.postTranslate(cx - lab, cy - lab)
            q.shader.setLocalMatrix(m)
            shaderPaint.shader = q.shader
            shaderPaint.alpha = (alpha * (1f - change) * 255).toInt()
            c.drawCircle(cx, cy, lab, shaderPaint)
        }
        c.restore()

        val light = sheen ?: makeSheen(cx, cy).also { sheen = it }
        p.shader = light
        p.alpha = (alpha * 255).toInt()
        c.drawCircle(cx, cy, R, p)
        p.shader = null

        p.color = PAPER; p.alpha = (alpha * 255).toInt()
        c.drawCircle(cx, cy, R * 0.022f, p)
        p.color = INK; p.alpha = (alpha * 255).toInt()
        c.drawCircle(cx, cy, R * 0.012f, p)
        c.restore()

        drawArm(c, cx, cy, R, alpha)
    }

    private fun makeSheen(cx: Float, cy: Float): Shader {
        val tint = Color.argb(58, Color.red(accent), Color.green(accent), Color.blue(accent))
        val pale = Color.argb(34, 255, 250, 240)
        val none = Color.argb(0, 255, 255, 255)
        return SweepGradient(
            cx, cy,
            intArrayOf(none, pale, tint, none, none, tint, pale, none, none),
            floatArrayOf(0f, 0.06f, 0.12f, 0.2f, 0.5f, 0.56f, 0.62f, 0.7f, 1f),
        )
    }

    private fun drawArm(c: Canvas, cx: Float, cy: Float, R: Float, alpha: Float) {
        val px = cx + R * 0.99f
        val py = cy - R * 0.88f
        val len = R * 1.5f
        // Angles were solved so the stylus lands on the outer groove and crawls to the label.
        val rest = 1.5f
        val play = lerp(11f, 29.5f, songFrac.coerceIn(0f, 1f))
        val angle = lerp(rest, play, outCubic(arm))
        val a255 = (alpha * 255).toInt()

        p.style = Paint.Style.FILL
        p.color = 0xFF1B1A19.toInt(); p.alpha = a255
        c.drawCircle(px, py, R * 0.115f, p)
        p.color = 0xFF3A3835.toInt(); p.alpha = a255
        c.drawCircle(px, py, R * 0.075f, p)

        c.save()
        c.rotate(angle, px, py)
        p.color = 0xFFCFC9BE.toInt(); p.alpha = a255
        p.style = Paint.Style.STROKE
        p.strokeCap = Paint.Cap.ROUND
        p.strokeWidth = R * 0.022f
        c.drawLine(px, py - R * 0.13f, px, py + len * 0.86f, p)
        p.style = Paint.Style.FILL
        p.color = 0xFF2A2927.toInt(); p.alpha = a255
        rect.set(px - R * 0.05f, py - R * 0.27f, px + R * 0.05f, py - R * 0.13f)
        c.drawRoundRect(rect, R * 0.012f, R * 0.012f, p)
        p.color = PAPER; p.alpha = a255
        rect.set(px - R * 0.045f, py + len * 0.86f, px + R * 0.045f, py + len)
        c.drawRoundRect(rect, R * 0.012f, R * 0.012f, p)
        p.color = accent; p.alpha = a255
        rect.set(px - R * 0.045f, py + len * 0.93f, px + R * 0.045f, py + len * 0.95f)
        c.drawRect(rect, p)
        c.restore()
        p.strokeCap = Paint.Cap.BUTT
        p.color = PAPER; p.alpha = a255
        c.drawCircle(px, py, R * 0.026f, p)
        p.alpha = 255
    }
}

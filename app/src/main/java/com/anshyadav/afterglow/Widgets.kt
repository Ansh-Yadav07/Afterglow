package com.anshyadav.afterglow

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.*
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.MotionEvent
import android.view.View
import kotlin.math.*

/** Poster-sized clock. Digits roll when the minute changes; the whole view shrinks into the top bar. */
class ClockView(ctx: Context) : View(ctx) {
    private val p = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.clock; color = PAPER; textAlign = Paint.Align.CENTER }
    private var text = ""
    private var prev = ""
    private var roll = 1f
    private var digitW = 0f
    private var colonW = 0f
    var capH = 0f; private set
    private var phase = 0f
    val centerY get() = height * 0.455f

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        p.textSize = 100f
        var dw = 0f
        for (d in '0'..'9') dw = max(dw, p.measureText(d.toString()))
        val cw = p.measureText(":") * 1.25f
        val b = Rect(); p.getTextBounds("0", 0, 1, b)
        val perEm = b.height() / 100f
        val size = min(w * 0.84f / ((4 * dw + cw) / 100f), h * 0.62f / perEm)
        p.textSize = size
        digitW = dw * size / 100f
        colonW = cw * size / 100f
        capH = perEm * size
        pivotX = w / 2f
        pivotY = centerY
    }

    fun setTime(t: String) {
        if (t == text) return
        prev = text
        text = t
        roll = if (prev.length == text.length) 0f else 1f
        invalidate()
    }

    fun tick(dt: Float, frameDue: Boolean) {
        phase += dt
        if (roll < 1f) { roll = min(1f, roll + dt / 0.85f); invalidate() }
        else if (frameDue) invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (text.isEmpty() || width == 0) return
        val cells = text.map { if (it == ':') colonW else digitW }
        var x = width / 2f - cells.sum() / 2f
        val base = centerY + capH / 2f
        val colon = 0.55f + 0.45f * (0.5f + 0.5f * cos(phase * 2f * PI.toFloat()))
        val e = outExpo(roll)
        for (i in text.indices) {
            val cw = cells[i]; val mid = x + cw / 2f
            if (text[i] == ':') {
                p.alpha = (colon * 255).toInt()
                c.drawText(":", mid, base, p)
                p.alpha = 255
            } else if (roll < 1f && prev.getOrNull(i) != text[i]) {
                val travel = capH * 1.14f
                c.save()
                c.clipRect(x, base - capH * 1.06f, x + cw, base + capH * 0.06f)
                c.drawText(prev[i].toString(), mid, base - travel * e, p)
                c.drawText(text[i].toString(), mid, base + travel * (1f - e), p)
                c.restore()
            } else {
                c.drawText(text[i].toString(), mid, base, p)
            }
            x += cw
        }
    }
}

/** Track title set as big as it will go. Each line rises out of a mask when the song changes. */
class PosterTitle(ctx: Context) : View(ctx) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display; color = PAPER; letterSpacing = 0.004f }
    private class Block(val text: String, val layout: StaticLayout, val paint: TextPaint)
    private var cur: Block? = null
    private var old: Block? = null
    private var t = 1f
    private var pending: String? = null
    var maxH = 0f
    var maxSize = 150f
    var minSize = 40f
    var onHeight: ((Int) -> Unit)? = null

    fun setTitle(text: String, force: Boolean = false) {
        val up = text.uppercase()
        if (!force && up == cur?.text) return
        if (width == 0) { pending = up; return }
        old = cur
        cur = fit(up)
        t = 0f
        onHeight?.invoke(cur!!.layout.height)
        invalidate()
    }

    /** Restarts the entrance after [delay] seconds; a negative clock keeps every line masked until then. */
    fun replay(delay: Float) { old = null; t = -delay; invalidate() }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        pending?.let { pending = null; setTitle(it, true) }
    }

    private fun build(text: String, p: TextPaint) = StaticLayout.Builder.obtain(text, 0, text.length, p, width)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED)
        .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
        .setIncludePad(false)
        .setLineSpacing(0f, 1.0f)
        .build()

    private fun fit(text: String): Block {
        val p = TextPaint(paint)
        val words = text.split(' ').filter { it.isNotEmpty() }
        var s = maxSize
        while (true) {
            p.textSize = s
            val widest = words.maxOfOrNull { p.measureText(it) } ?: 0f
            val l = build(text, p)
            if (s <= minSize || (widest <= width && l.lineCount <= 3 && l.height <= maxH)) return Block(text, l, p)
            s -= 3f
        }
    }

    fun tick(dt: Float) {
        if (t < 1.6f) { t = min(1.6f, t + dt); invalidate() }
    }

    private fun drawBlock(c: Canvas, b: Block, out: Boolean) {
        val l = b.layout
        for (i in 0 until l.lineCount) {
            val delay = i * 0.085f
            val top = l.getLineTop(i).toFloat()
            val bottom = l.getLineBottom(i).toFloat()
            val h = bottom - top
            val dy = if (out) {
                val q = ((t - delay * 0.5f) / 0.42f).coerceIn(0f, 1f)
                if (q >= 1f) continue
                -q * q * h * 1.05f
            } else {
                val q = ((t - 0.20f - delay) / 0.95f).coerceIn(0f, 1f)
                if (q <= 0f) continue
                (1f - outExpo(q)) * h * 1.05f
            }
            c.save()
            c.clipRect(-8f, top - 2f, width.toFloat() + 8f, bottom + 2f)
            c.translate(0f, dy)
            c.drawText(b.text, l.getLineStart(i), l.getLineVisibleEnd(i), l.getLineLeft(i), l.getLineBaseline(i).toFloat(), b.paint)
            c.restore()
        }
    }

    override fun onDraw(c: Canvas) {
        old?.let { if (t < 0.6f) drawBlock(c, it, true) }
        cur?.let { drawBlock(c, it, false) }
    }
}

class ProgressView(ctx: Context, private val u: Float) : View(ctx) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; style = Paint.Style.STROKE }
    private var accent = PAPER
    private var fraction = 0f
    private var thick = 0f
    var dragging = false; private set
    var onSeek: ((Float) -> Unit)? = null
    var onScrub: ((Float) -> Unit)? = null

    fun setAccent(c: Int) { accent = c; invalidate() }

    fun setFraction(f: Float, dt: Float) {
        if (!dragging && abs(f - fraction) > 0.0002f) { fraction = f; invalidate() }
        val t = if (dragging) 1f else 0f
        if (abs(t - thick) > 0.005f) { thick = smooth(thick, t, dt, 0.08f); invalidate() }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                dragging = true
                parent.requestDisallowInterceptTouchEvent(true)
                fraction = (e.x / width).coerceIn(0f, 1f)
                onScrub?.invoke(fraction)
                invalidate()
            }
            MotionEvent.ACTION_UP -> { dragging = false; onSeek?.invoke(fraction) }
            MotionEvent.ACTION_CANCEL -> dragging = false
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val y = height / 2f
        val w = width.toFloat()
        val sw = lerp(2.5f * u, 7f * u, thick)
        p.strokeWidth = sw
        p.color = PAPER; p.alpha = 56
        c.drawLine(sw, y, w - sw, y, p)
        p.color = accent; p.alpha = 255
        val x = sw + (w - 2 * sw) * fraction
        c.drawLine(sw, y, x, y, p)
        p.color = PAPER
        p.strokeWidth = 3f * u
        c.drawLine(x, y - lerp(9f, 14f, thick) * u, x, y + lerp(9f, 14f, thick) * u, p)
    }
}

class IconButton(ctx: Context, private val kind: Kind, private val u: Float) : View(ctx) {
    enum class Kind { PREV, NEXT, PLAY }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var morph = 0f
    private var morphTarget = 0f
    private var press = 0f
    private var down = false

    /** Morphs between the two bars of pause and the triangle of play. */
    fun setPlaying(playing: Boolean) { morphTarget = if (playing) 0f else 1f; invalidate() }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { down = true; invalidate() }
            MotionEvent.ACTION_UP -> { down = false; invalidate(); if (e.x in 0f..width.toFloat() && e.y in 0f..height.toFloat()) performClick() }
            MotionEvent.ACTION_CANCEL -> { down = false; invalidate() }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val dt = 1f / 60f
        var again = false
        val pt = if (down) 1f else 0f
        if (abs(pt - press) > 0.01f) { press = smooth(press, pt, dt, 0.05f); again = true } else press = pt
        if (abs(morphTarget - morph) > 0.01f) { morph = smooth(morph, morphTarget, dt, 0.07f); again = true } else morph = morphTarget
        val cx = width / 2f; val cy = height / 2f
        c.save()
        val s = 1f - press * 0.12f
        c.scale(s, s, cx, cy)
        if (kind == Kind.PLAY) {
            p.color = PAPER
            c.drawCircle(cx, cy, 36f * u, p)
            p.color = INK
            drawPlayPause(c, cx, cy, 25f * u)
        } else {
            p.color = PAPER
            val r = 16f * u
            val d = if (kind == Kind.NEXT) 1f else -1f
            path.reset()
            path.moveTo(cx - d * r * 0.8f, cy - r)
            path.lineTo(cx + d * r * 0.55f, cy)
            path.lineTo(cx - d * r * 0.8f, cy + r)
            path.close()
            c.drawPath(path, p)
            val bx = cx + d * r * 0.75f
            c.drawRect(min(bx, bx + d * 4f * u), cy - r, max(bx, bx + d * 4f * u), cy + r, p)
        }
        c.restore()
        if (again) postInvalidateOnAnimation()
    }

    private fun drawPlayPause(c: Canvas, cx: Float, cy: Float, r: Float) {
        if (morph >= 0.99f) {
            // At rest, one shape: two abutting antialiased halves would show a seam.
            val k = r * 1.3f
            path.reset()
            path.moveTo(cx - .26f * k, cy - .46f * k)
            path.lineTo(cx + .54f * k, cy)
            path.lineTo(cx - .26f * k, cy + .46f * k)
            path.close()
            c.drawPath(path, p)
            return
        }
        val pauseL = floatArrayOf(-.36f, -.42f, -.11f, -.42f, -.11f, .42f, -.36f, .42f)
        val pauseR = floatArrayOf(.11f, -.42f, .36f, -.42f, .36f, .42f, .11f, .42f)
        val playL = floatArrayOf(-.26f, -.46f, .13f, -.235f, .13f, .235f, -.26f, .46f)
        val playR = floatArrayOf(.13f, -.235f, .54f, 0f, .54f, 0f, .13f, .235f)
        for ((a, b) in listOf(pauseL to playL, pauseR to playR)) {
            path.reset()
            for (i in 0 until 4) {
                val x = cx + lerp(a[i * 2], b[i * 2], morph) * r * 1.3f
                val y = cy + lerp(a[i * 2 + 1], b[i * 2 + 1], morph) * r * 1.3f
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
            c.drawPath(path, p)
        }
    }
}

class LyricLine(val timeMs: Long, val text: String)

/** Synced lyrics: the current line in full strength, its neighbours fading away on a smooth scroll. */
class LyricsView(ctx: Context, private val u: Float) : View(ctx) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.display; color = PAPER; textSize = 50f * u }
    private var lines: List<LyricLine> = emptyList()
    private var layouts: List<StaticLayout> = emptyList()
    private var tops = FloatArray(0)
    private var scroll = 0f
    private var active = -1
    private val gap = 24f * u

    fun setLines(l: List<LyricLine>) {
        lines = l
        active = -1
        scroll = 0f
        rebuild()
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) = rebuild()

    private fun rebuild() {
        if (width == 0) return
        layouts = lines.map {
            val t = it.text.ifBlank { "· · ·" }
            StaticLayout.Builder.obtain(t, 0, t.length, paint, width)
                .setBreakStrategy(Layout.BREAK_STRATEGY_BALANCED).setIncludePad(false).build()
        }
        tops = FloatArray(layouts.size)
        var y = 0f
        for (i in layouts.indices) { tops[i] = y; y += layouts[i].height + gap }
    }

    fun tick(dt: Float, posMs: Long) {
        if (lines.isEmpty()) return
        var idx = -1
        for (i in lines.indices) if (lines[i].timeMs <= posMs) idx = i else break
        active = idx
        val i = idx.coerceAtLeast(0)
        val target = tops[i] + layouts[i].height / 2f - height * 0.42f
        val next = smooth(scroll, target, dt, 0.16f)
        if (abs(next - scroll) > 0.1f) { scroll = next; invalidate() }
    }

    override fun onDraw(c: Canvas) {
        for (i in layouts.indices) {
            val y = tops[i] - scroll
            val l = layouts[i]
            if (y + l.height < 0 || y > height) continue
            val dist = abs(y + l.height / 2f - height * 0.42f) / height
            val base = (1f - dist * 2.4f).coerceIn(0f, 1f)
            paint.alpha = ((if (i == active) 1f else base * 0.34f) * 255).toInt()
            c.save(); c.translate(0f, y); l.draw(c); c.restore()
        }
    }
}

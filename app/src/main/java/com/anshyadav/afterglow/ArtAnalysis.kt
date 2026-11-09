package com.anshyadav.afterglow

import android.graphics.*
import java.util.Calendar
import kotlin.math.*

/** Everything the display needs to dress itself for one album cover. */
class Mood(
    /** Five RGB triples (0..1) that drive the moving background. */
    val ambient: FloatArray,
    val glow: FloatArray,
    val accent: Int,
    val accent2: Int,
)

class Analyzed(val square: Bitmap, val glow: Bitmap, val mood: Mood)

object Color3 {
    private fun lin(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun gam(c: Float) = if (c <= 0.0031308f) 12.92f * c else 1.055f * c.pow(1f / 2.4f) - 0.055f

    fun toLab(c: Int, out: FloatArray, at: Int) {
        val r = lin(Color.red(c) / 255f)
        val g = lin(Color.green(c) / 255f)
        val b = lin(Color.blue(c) / 255f)
        val l = cbrt(0.4122f * r + 0.5363f * g + 0.0514f * b)
        val m = cbrt(0.2119f * r + 0.6807f * g + 0.1074f * b)
        val s = cbrt(0.0883f * r + 0.2817f * g + 0.6300f * b)
        out[at] = 0.2105f * l + 0.7936f * m - 0.0041f * s
        out[at + 1] = 1.9780f * l - 2.4286f * m + 0.4506f * s
        out[at + 2] = 0.0259f * l + 0.7828f * m - 0.8087f * s
    }

    fun fromLab(L: Float, a: Float, b: Float): Int {
        val l = L + 0.3963f * a + 0.2158f * b
        val m = L - 0.1056f * a - 0.0639f * b
        val s = L - 0.0895f * a - 1.2915f * b
        val l3 = l * l * l; val m3 = m * m * m; val s3 = s * s * s
        fun ch(v: Float) = (gam(v.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        return Color.rgb(
            ch(4.0767f * l3 - 3.3077f * m3 + 0.2310f * s3),
            ch(-1.2684f * l3 + 2.6098f * m3 - 0.3413f * s3),
            ch(-0.0042f * l3 - 0.7034f * m3 + 1.7076f * s3),
        )
    }
}

object ArtAnalysis {

    fun process(src: Bitmap): Analyzed {
        val square = cropSquare(src, 760)
        return Analyzed(square, makeGlow(square), extract(square))
    }

    private fun cropSquare(src: Bitmap, max: Int): Bitmap {
        val soft = if (src.config == Bitmap.Config.HARDWARE) src.copy(Bitmap.Config.ARGB_8888, false) else src
        val side = min(soft.width, soft.height)
        val target = min(side, max).coerceAtLeast(64)
        val out = Bitmap.createBitmap(target, target, Bitmap.Config.ARGB_8888)
        val sx = (soft.width - side) / 2
        val sy = (soft.height - side) / 2
        Canvas(out).drawBitmap(
            soft, Rect(sx, sy, sx + side, sy + side), Rect(0, 0, target, target),
            Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG),
        )
        return out
    }

    /** A tiny blurred copy with soft edges, stretched behind the cover as a coloured shadow. */
    private fun makeGlow(square: Bitmap): Bitmap {
        val size = 96; val pad = 20f
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(square, null, RectF(pad, pad, size - pad, size - pad), Paint(Paint.FILTER_BITMAP_FLAG))
        val px = IntArray(size * size)
        out.getPixels(px, 0, size, 0, 0, size, size)
        val ch = Array(4) { FloatArray(size * size) }
        for (i in px.indices) {
            val a = Color.alpha(px[i]) / 255f
            ch[0][i] = a; ch[1][i] = Color.red(px[i]) * a; ch[2][i] = Color.green(px[i]) * a; ch[3][i] = Color.blue(px[i]) * a
        }
        val tmp = FloatArray(size * size)
        repeat(3) { for (c in ch) boxBlur(c, tmp, size, 6) }
        for (i in px.indices) {
            val a = ch[0][i]
            px[i] = if (a < 0.003f) 0 else Color.argb(
                (a * 255).toInt().coerceIn(0, 255),
                (ch[1][i] / a).toInt().coerceIn(0, 255),
                (ch[2][i] / a).toInt().coerceIn(0, 255),
                (ch[3][i] / a).toInt().coerceIn(0, 255),
            )
        }
        out.setPixels(px, 0, size, 0, 0, size, size)
        return out
    }

    private fun boxBlur(c: FloatArray, tmp: FloatArray, n: Int, r: Int) {
        val w = 1f / (2 * r + 1)
        for (y in 0 until n) for (x in 0 until n) {
            var s = 0f
            for (k in -r..r) s += c[y * n + (x + k).coerceIn(0, n - 1)]
            tmp[y * n + x] = s * w
        }
        for (x in 0 until n) for (y in 0 until n) {
            var s = 0f
            for (k in -r..r) s += tmp[(y + k).coerceIn(0, n - 1) * n + x]
            c[y * n + x] = s * w
        }
    }

    private class Cluster(val L: Float, val a: Float, val b: Float, val weight: Float) {
        val chroma get() = hypot(a, b)
        val hue get() = atan2(b, a)
    }

    private fun clusters(bmp: Bitmap, k: Int): List<Cluster> {
        val s = Bitmap.createScaledBitmap(bmp, 40, 40, true)
        val px = IntArray(1600)
        s.getPixels(px, 0, 40, 0, 0, 40, 40)
        val lab = FloatArray(px.size * 3)
        for (i in px.indices) Color3.toLab(px[i], lab, i * 3)

        // Farthest-point seeding keeps a small accent colour from being averaged away.
        val cent = FloatArray(k * 3)
        for (j in 0..2) cent[j] = lab[j]
        for (c in 1 until k) {
            var best = 0; var bestD = -1f
            for (i in px.indices) {
                var dmin = Float.MAX_VALUE
                for (q in 0 until c) dmin = min(dmin, dist(lab, i * 3, cent, q * 3))
                if (dmin > bestD) { bestD = dmin; best = i }
            }
            for (j in 0..2) cent[c * 3 + j] = lab[best * 3 + j]
        }
        val assign = IntArray(px.size)
        repeat(10) {
            val sum = FloatArray(k * 3); val cnt = IntArray(k)
            for (i in px.indices) {
                var bq = 0; var bd = Float.MAX_VALUE
                for (q in 0 until k) { val d = dist(lab, i * 3, cent, q * 3); if (d < bd) { bd = d; bq = q } }
                assign[i] = bq; cnt[bq]++
                for (j in 0..2) sum[bq * 3 + j] += lab[i * 3 + j]
            }
            for (q in 0 until k) if (cnt[q] > 0) for (j in 0..2) cent[q * 3 + j] = sum[q * 3 + j] / cnt[q]
        }
        val cnt = IntArray(k)
        for (a in assign) cnt[a]++
        return (0 until k).filter { cnt[it] > 0 }.map {
            Cluster(cent[it * 3], cent[it * 3 + 1], cent[it * 3 + 2], cnt[it] / px.size.toFloat())
        }
    }

    private fun dist(a: FloatArray, i: Int, b: FloatArray, j: Int): Float {
        val x = a[i] - b[j]; val y = a[i + 1] - b[j + 1]; val z = a[i + 2] - b[j + 2]
        return x * x + y * y + z * z
    }

    fun extract(art: Bitmap): Mood {
        val cs = clusters(art, 7)
        val ranked = cs.sortedByDescending { sqrt(it.weight) * (0.28f + it.chroma * 7f) }
        val ambient = FloatArray(15)
        for (i in 0 until 5) {
            val c = ranked[i % ranked.size]
            val gain = 1.35f
            val chromaCap = min(1f, 0.19f / max(c.chroma * gain, 0.001f))
            // Keep the wash deep enough that off-white type always reads over it.
            val L = when (i) {
                4 -> (c.L * 0.45f).coerceIn(0.10f, 0.22f)
                else -> (c.L * 0.86f).coerceIn(0.20f, 0.52f)
            }
            val rgb = Color3.fromLab(L, c.a * gain * chromaCap, c.b * gain * chromaCap)
            ambient[i * 3] = Color.red(rgb) / 255f
            ambient[i * 3 + 1] = Color.green(rgb) / 255f
            ambient[i * 3 + 2] = Color.blue(rgb) / 255f
        }

        val usable = cs.filter { it.weight > 0.012f }.ifEmpty { cs }
        val a1 = usable.maxByOrNull { it.chroma * sqrt(it.weight).pow(0.5f) }!!
        val neutral = a1.chroma < 0.035f
        val accent: Int
        val accent2: Int
        if (neutral) {
            accent = PAPER
            accent2 = 0xFFB9B2A6.toInt()
        } else {
            accent = lifted(a1, 0.76f)
            val a2 = usable.filter { it !== a1 && it.chroma > 0.03f }
                .maxByOrNull { abs(angleDelta(it.hue, a1.hue)) * sqrt(it.weight) }
            accent2 = if (a2 != null) lifted(a2, 0.68f) else lifted(a1, 0.58f)
        }
        val g = if (neutral) 0xFF8C867C.toInt() else accent
        return Mood(ambient, floatArrayOf(Color.red(g) / 255f, Color.green(g) / 255f, Color.blue(g) / 255f), accent, accent2)
    }

    private fun lifted(c: Cluster, minL: Float): Int {
        val boost = max(1f, 0.11f / max(c.chroma, 0.001f)).coerceAtMost(1.8f)
        return Color3.fromLab(max(c.L, minL), c.a * boost, c.b * boost)
    }

    private fun angleDelta(a: Float, b: Float): Float {
        var d = a - b
        while (d > PI) d -= (2 * PI).toFloat()
        while (d < -PI) d += (2 * PI).toFloat()
        return d
    }

    private val daySky = arrayOf(
        // hour -> five stops. Earth and fire only: oxblood nights, ochre dusks, olive days.
        0f to intArrayOf(0x3A120C, 0x1C0A07, 0x4E190E, 0x140706, 0x2A0F09),
        5f to intArrayOf(0x4A1A12, 0x2A100C, 0x70301A, 0x180A08, 0x3A1610),
        8f to intArrayOf(0xA5552C, 0xC9853F, 0x70321F, 0xD5A566, 0x2E1712),
        12f to intArrayOf(0x6B6A3F, 0x9C8B57, 0x4C5430, 0xAD9A68, 0x262A1C),
        17f to intArrayOf(0x8A3A1B, 0xC06A24, 0x58200F, 0xB88A35, 0x24110B),
        20f to intArrayOf(0x6A2410, 0x96401A, 0x42150C, 0x7E4A1A, 0x1C0C08),
        24f to intArrayOf(0x3A120C, 0x1C0A07, 0x4E190E, 0x140706, 0x2A0F09),
    )

    /** The clock's background follows the sun, so the idle screen is never the same twice a day. */
    fun moodForNow(): Mood {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY) + cal.get(Calendar.MINUTE) / 60f
        var i = 0
        while (i < daySky.size - 2 && h >= daySky[i + 1].first) i++
        val (h0, a) = daySky[i]
        val (h1, b) = daySky[i + 1]
        val t = ((h - h0) / (h1 - h0)).coerceIn(0f, 1f)
        val ambient = FloatArray(15)
        for (k in 0 until 5) {
            for ((j, shift) in intArrayOf(16, 8, 0).withIndex()) {
                val c0 = (a[k] shr shift and 255) / 255f
                val c1 = (b[k] shr shift and 255) / 255f
                ambient[k * 3 + j] = lerp(c0, c1, t) * 0.8f
            }
        }
        return Mood(ambient, floatArrayOf(ambient[3] * .5f, ambient[4] * .5f, ambient[5] * .5f), 0xFFE8A15C.toInt(), 0xFFC65D3B.toInt())
    }

    /** Stand-in cover for tracks with no artwork, so the layout never falls apart. */
    fun placeholder(seedText: String): Bitmap {
        val palette = arrayOf(
            intArrayOf(0xFFB9431B.toInt(), 0xFFF2C14E.toInt(), 0xFF1C1411.toInt()),
            intArrayOf(0xFF2F4A3A.toInt(), 0xFFE0D5B7.toInt(), 0xFF101512.toInt()),
            intArrayOf(0xFF7A1F2B.toInt(), 0xFFF1B5A0.toInt(), 0xFF140A0C.toInt()),
            intArrayOf(0xFF1E5F63.toInt(), 0xFFF0A868.toInt(), 0xFF0C1414.toInt()),
        )[abs(seedText.hashCode()) % 4]
        val size = 640
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(palette[0])
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = palette[1]; c.drawCircle(size * .68f, size * .36f, size * .26f, p)
        p.color = palette[2]; c.drawRect(0f, size * .62f, size.toFloat(), size.toFloat(), p)
        return b
    }
}

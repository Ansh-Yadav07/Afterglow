package com.anshyadav.afterglow

import android.content.Context
import android.graphics.Typeface
import android.view.animation.PathInterpolator
import kotlin.math.exp
import kotlin.math.pow

const val PAPER = 0xFFF4EFE6.toInt()
const val INK = 0xFF0B0A09.toInt()

/** Long, confident settle. Used for almost every entrance in the app. */
val EXPRESSIVE = PathInterpolator(0.16f, 1f, 0.3f, 1f)
val SOFT_IN_OUT = PathInterpolator(0.65f, 0f, 0.35f, 1f)

fun smooth(cur: Float, target: Float, dt: Float, tau: Float): Float =
    cur + (target - cur) * (1f - exp(-dt / tau))

fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
fun outExpo(t: Float) = if (t >= 1f) 1f else 1f - 2f.pow(-10f * t)
fun outCubic(t: Float) = 1f - (1f - t).pow(3)

object Fonts {
    lateinit var display: Typeface
    lateinit var displayLight: Typeface
    lateinit var clock: Typeface
    lateinit var mono: Typeface
    lateinit var monoMedium: Typeface
    private var ready = false

    fun init(ctx: Context) {
        if (ready) return
        fun antonio(w: Int) = Typeface.Builder(ctx.assets, "fonts/Antonio.ttf")
            .setFontVariationSettings("'wght' $w").build()
        display = antonio(700)
        displayLight = antonio(400)
        clock = antonio(300)
        mono = Typeface.createFromAsset(ctx.assets, "fonts/DMMono-Regular.ttf")
        monoMedium = Typeface.createFromAsset(ctx.assets, "fonts/DMMono-Medium.ttf")
        ready = true
    }
}

fun fmtTime(ms: Long): String {
    val s = (ms.coerceAtLeast(0) / 1000).toInt()
    return "%d:%02d".format(s / 60, s % 60)
}

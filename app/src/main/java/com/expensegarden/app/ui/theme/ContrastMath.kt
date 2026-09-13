package com.expensegarden.app.ui.theme

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.pow

/**
 * WCAG contrast and CIE L*a*b* chroma/hue over 8-bit sRGB packed as 0xRRGGBB.
 *
 * Takes and returns primitives on purpose. Compose's Color or android.graphics.Color would both
 * make this untestable on the JVM, and the whole point is that the theme's invariants are
 * checked by `./gradlew testDebugUnitTest` with no emulator in the loop.
 */
object ContrastMath {

    private fun channel(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.04045) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    private fun red(rgb: Int) = channel((rgb shr 16) and 0xFF)
    private fun green(rgb: Int) = channel((rgb shr 8) and 0xFF)
    private fun blue(rgb: Int) = channel(rgb and 0xFF)

    /** WCAG 2.1 relative luminance, 0.0 (black) .. 1.0 (white). */
    fun luminance(rgb: Int): Double =
        0.2126 * red(rgb) + 0.7152 * green(rgb) + 0.0722 * blue(rgb)

    /** WCAG contrast ratio, 1.0 (identical) .. 21.0 (black on white). Symmetric. */
    fun ratio(a: Int, b: Int): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** CIE L*a*b* under a D65 white point, as (L, a, b). */
    fun lab(rgb: Int): Triple<Double, Double, Double> {
        val r = red(rgb)
        val g = green(rgb)
        val b = blue(rgb)
        val x = (r * 0.4124564 + g * 0.3575761 + b * 0.1804375) / 0.95047
        val y = (r * 0.2126729 + g * 0.7151522 + b * 0.0721750) / 1.00000
        val z = (r * 0.0193339 + g * 0.1191920 + b * 0.9503041) / 1.08883
        fun f(t: Double) = if (t > 0.008856) t.pow(1.0 / 3.0) else 7.787 * t + 16.0 / 116.0
        val fx = f(x)
        val fy = f(y)
        val fz = f(z)
        return Triple(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    /**
     * Lab chroma — how much colour is actually present. A grey is 0.
     *
     * This is the assertion that catches the bug this pass exists for: the retired pace-warning
     * colour #7D5260 scores 20.1, and a hue angle that low in chroma is not perceptible. Neither
     * contrast ratio nor CIE76 dE caught it; both scored it as fine.
     */
    fun chroma(rgb: Int): Double {
        val (_, a, b) = lab(rgb)
        return hypot(a, b)
    }

    /** Lab hue angle in degrees, 0..360. */
    fun hue(rgb: Int): Double {
        val (_, a, b) = lab(rgb)
        return (Math.toDegrees(atan2(b, a)) + 360.0) % 360.0
    }

    /** Shortest angular distance between two hues, 0..180 degrees. */
    fun hueSeparation(a: Int, b: Int): Double {
        val d = abs(hue(a) - hue(b)) % 360.0
        return minOf(d, 360.0 - d)
    }
}

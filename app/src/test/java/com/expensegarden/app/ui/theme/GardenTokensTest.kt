package com.expensegarden.app.ui.theme

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The theme's invariants. Every number here was measured before it was written down; if one of
 * these fails, the hex changes, not the floor.
 */
class GardenTokensTest {

    private fun assertContrast(name: String, fg: Int, bg: Int, floor: Double) {
        val r = ContrastMath.ratio(fg, bg)
        assertTrue(
            "$name: #%06X on #%06X is %.2f:1, needs %.1f:1".format(fg, bg, r, floor),
            r >= floor,
        )
    }

    /** Text pairs must clear WCAG AA. */
    @Test
    fun `every text pair in the light scheme clears 4_5 to 1`() {
        with(GardenTokens) {
            assertContrast("onPrimary", LightOnPrimary, LightPrimary, 4.5)
            assertContrast("onPrimaryContainer", LightOnPrimaryContainer, LightPrimaryContainer, 4.5)
            assertContrast("onSecondary", LightOnSecondary, LightSecondary, 4.5)
            assertContrast("onSecondaryContainer", LightOnSecondaryContainer, LightSecondaryContainer, 4.5)
            assertContrast("onTertiary", LightOnTertiary, LightTertiary, 4.5)
            assertContrast("onTertiaryContainer", LightOnTertiaryContainer, LightTertiaryContainer, 4.5)
            assertContrast("onError", LightOnError, LightError, 4.5)
            assertContrast("onErrorContainer", LightOnErrorContainer, LightErrorContainer, 4.5)
            assertContrast("onSurface", LightOnSurface, LightSurface, 4.5)
            assertContrast("onSurfaceVariant/variant", LightOnSurfaceVariant, LightSurfaceVariant, 4.5)
            assertContrast("onSurfaceVariant/surface", LightOnSurfaceVariant, LightSurface, 4.5)
            assertContrast("primary as text", LightPrimary, LightSurface, 4.5)
            assertContrast("error as text", LightError, LightSurface, 4.5)
        }
    }

    @Test
    fun `every text pair in the dark scheme clears 4_5 to 1`() {
        with(GardenTokens) {
            assertContrast("onPrimary", DarkOnPrimary, DarkPrimary, 4.5)
            assertContrast("onPrimaryContainer", DarkOnPrimaryContainer, DarkPrimaryContainer, 4.5)
            assertContrast("onSecondary", DarkOnSecondary, DarkSecondary, 4.5)
            assertContrast("onSecondaryContainer", DarkOnSecondaryContainer, DarkSecondaryContainer, 4.5)
            assertContrast("onTertiary", DarkOnTertiary, DarkTertiary, 4.5)
            assertContrast("onTertiaryContainer", DarkOnTertiaryContainer, DarkTertiaryContainer, 4.5)
            assertContrast("onError", DarkOnError, DarkError, 4.5)
            assertContrast("onErrorContainer", DarkOnErrorContainer, DarkErrorContainer, 4.5)
            assertContrast("onSurface", DarkOnSurface, DarkSurface, 4.5)
            assertContrast("onSurfaceVariant/variant", DarkOnSurfaceVariant, DarkSurfaceVariant, 4.5)
            assertContrast("onSurfaceVariant/surface", DarkOnSurfaceVariant, DarkSurface, 4.5)
            assertContrast("primary as text", DarkPrimary, DarkSurface, 4.5)
            assertContrast("error as text", DarkError, DarkSurface, 4.5)
        }
    }

    /** Outline is a UI element, not text, so 3:1. */
    @Test
    fun `outline clears 3 to 1 in both schemes`() {
        assertContrast("light outline", GardenTokens.LightOutline, GardenTokens.LightSurface, 3.0)
        assertContrast("dark outline", GardenTokens.DarkOutline, GardenTokens.DarkSurface, 3.0)
    }

    /** A status colour must read as a colour, not as a grey. This is the chroma floor. */
    @Test
    fun `status colours are saturated enough to perceive`() {
        val triads = mapOf(
            "light" to (GardenTokens.lightStatus to GardenTokens.LightSurface),
            "dark" to (GardenTokens.darkStatus to GardenTokens.DarkSurface),
        )
        for ((scheme, pair) in triads) {
            val (triad, surface) = pair
            for ((name, colour) in triad) {
                val c = ContrastMath.chroma(colour)
                assertTrue("$scheme $name chroma is %.1f, needs 25".format(c), c >= 25.0)
                assertContrast("$scheme $name on surface", colour, surface, 4.5)
            }
        }
    }

    /** And they must be different hues, so the triad cannot drift into one family. */
    @Test
    fun `status colours are at least 30 degrees apart in hue`() {
        for (triad in listOf(GardenTokens.lightStatus, GardenTokens.darkStatus)) {
            val entries = triad.entries.toList()
            for (i in entries.indices) {
                for (j in i + 1 until entries.size) {
                    val sep = ContrastMath.hueSeparation(entries[i].value, entries[j].value)
                    assertTrue(
                        "${entries[i].key}/${entries[j].key} are %.1f deg apart, needs 30".format(sep),
                        sep >= 30.0,
                    )
                }
            }
        }
    }
}

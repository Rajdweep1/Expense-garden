package com.expensegarden.app.ui.theme

import androidx.compose.material3.ColorScheme
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The wiring, which GardenTokensTest cannot see. That test polices the tokens, and the purple
 * dialogs of 2026-10-11 had nothing wrong with any token: eleven roles were simply never passed,
 * so Material filled them with its baseline.
 *
 * Roles are found by reflection rather than listed, so a Material upgrade that adds one fails
 * here ("no garden token for …") instead of shipping its default silently.
 */
class GardenSchemeTest {

    /** Every Color getter on ColorScheme. Color is an inline class over Long, so each getter
     *  returns a primitive long under a mangled name such as getPrimary-0d7_KjU. */
    private val roles = ColorScheme::class.java.methods
        .filter { it.name.startsWith("get") && it.parameterCount == 0 && it.returnType == java.lang.Long.TYPE }
        .associateBy { it.name.removePrefix("get").substringBefore('-').replaceFirstChar(Char::lowercase) }

    /** An sRGB Color packs its ARGB into the top 32 bits of the Long. */
    private fun rgbOf(packed: Long): Int = ((packed ushr 32) and 0xFFFFFF).toInt()

    private fun assertWired(label: String, scheme: ColorScheme, tokens: Map<String, Int>) {
        // Without this, a reflection change that finds nothing would pass vacuously.
        assertTrue("reflection found only ${roles.size} colour roles", roles.size >= 36)
        val missing = roles.keys - tokens.keys
        assertTrue("$label: no garden token for $missing, so Material would paint them purple", missing.isEmpty())
        val wrong = roles.mapNotNull { (role, getter) ->
            val want = tokens.getValue(role)
            val got = rgbOf(getter.invoke(scheme) as Long)
            if (want == got) null else "$role: want #%06X, got #%06X".format(want, got)
        }.sorted()
        assertTrue("$label scheme is wired wrong:\n${wrong.joinToString("\n")}", wrong.isEmpty())
    }

    @Test
    fun `every role in both schemes comes from a garden token`() {
        assertWired("light", GardenLightScheme, GardenTokens.lightRoles)
        assertWired("dark", GardenDarkScheme, GardenTokens.darkRoles)
    }
}

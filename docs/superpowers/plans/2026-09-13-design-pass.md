# The Design Pass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace Material 3's baseline purple with a colour system derived from `GardenPalette`, fix the four verified layout/contrast defects, and give the app a launcher icon.

**Architecture:** The palette lives as plain `Int` constants in `GardenTokens` so its invariants are checked by JVM unit tests with no emulator in the loop. `GardenTheme` builds Compose `ColorScheme`s from those Ints and carries three status colours on a `CompositionLocal`, because Material 3 has no slot for "on pace / warning / over". Screens change only in what they are painted with and where their edges sit.

**Tech Stack:** Kotlin, Jetpack Compose (Material 3 1.3.0 via BOM 2024.09.03), JUnit 4. **No new dependencies** — `WindowCompat` comes from `androidx.core.ktx`, already present.

**Spec:** `docs/superpowers/specs/2026-09-13-design-pass-design.md`

**Every Gradle command needs JDK 17:**
```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
```

---

## File Structure

| File | Responsibility |
|---|---|
| `ui/theme/ContrastMath.kt` *(new)* | WCAG contrast + CIE Lab chroma/hue over `0xRRGGBB` Ints. Pure arithmetic, zero Android imports. |
| `ui/theme/GardenTokens.kt` *(new)* | The palette, as `Int` constants. The single source of truth for every colour in the chrome. |
| `ui/theme/GardenTheme.kt` *(new)* | Builds both `ColorScheme`s from tokens; provides `GardenStatusColors`. The only file that imports Compose colour APIs. |
| `ui/SystemBars.kt` *(new)* | Per-screen status/nav icon polarity. |
| `res/values/{colors,themes}.xml` + `values-night/` *(new)* | The window theme — launch background and transparent system bars. |
| `res/drawable/ic_launcher_{foreground,monochrome}.xml`, `res/mipmap-anydpi-v26/ic_launcher.xml` *(new)* | Adaptive launcher icon. |
| `MainActivity.kt` | Swap `MaterialTheme` → `GardenTheme`; declare edge-to-edge. |
| `ui/DashboardScreen.kt` | Status colours, `safeDrawing`, animated progress. |
| `ui/GardenHomeScreen.kt` | Per-edge insets, top-chrome restructure, FAB weights. |
| `ui/{Entry,Greenhouse,Settings}Screen.kt` | `safeDrawing`, `SystemBarIcons`. |
| `AndroidManifest.xml` | Point at the new theme and icon. |

---

## Task 1: ContrastMath

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/theme/ContrastMath.kt`
- Test: `app/src/test/java/com/expensegarden/app/ui/theme/ContrastMathTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/ui/theme/ContrastMathTest.kt`:

```kotlin
package com.expensegarden.app.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContrastMathTest {

    @Test
    fun `black on white is the maximum ratio of 21`() {
        assertEquals(21.0, ContrastMath.ratio(0x000000, 0xFFFFFF), 0.001)
    }

    @Test
    fun `a colour against itself is 1 to 1`() {
        assertEquals(1.0, ContrastMath.ratio(0x3F7A33, 0x3F7A33), 0.0001)
    }

    @Test
    fun `ratio is symmetric`() {
        assertEquals(
            ContrastMath.ratio(0x1C1B17, 0xFAF8F2),
            ContrastMath.ratio(0xFAF8F2, 0x1C1B17),
            0.0001,
        )
    }

    @Test
    fun `luminance spans zero to one`() {
        assertEquals(0.0, ContrastMath.luminance(0x000000), 0.0001)
        assertEquals(1.0, ContrastMath.luminance(0xFFFFFF), 0.0001)
    }

    @Test
    fun `a neutral grey has no chroma`() {
        assertEquals(0.0, ContrastMath.chroma(0x808080), 0.01)
    }

    @Test
    fun `hue lands on the known Lab angles for the primaries`() {
        assertEquals(40.00, ContrastMath.hue(0xFF0000), 0.1)
        assertEquals(136.02, ContrastMath.hue(0x00FF00), 0.1)
        assertEquals(306.28, ContrastMath.hue(0x0000FF), 0.1)
    }

    @Test
    fun `hue separation takes the short way round the circle`() {
        // 10 degrees and 350 degrees are 20 apart, not 340.
        assertEquals(20.0, ContrastMath.hueSeparation(0xFF0000, 0xFF00E0), 60.0)
        assertTrue(ContrastMath.hueSeparation(0xFF0000, 0x00FF00) <= 180.0)
    }

    // --- the two defects this pass exists to fix, pinned as regressions ---

    @Test
    fun `the shipped status bar contrast is detected as a failure`() {
        // White glyphs on Material baseline surface FEF7FF, measured on device 2026-09-13.
        assertTrue(ContrastMath.ratio(0xFFFFFF, 0xFEF7FF) < 3.0)
    }

    @Test
    fun `the retired pace-warning colour is detected as near-grey`() {
        // tertiary 7D5260 — chroma 20.1, which is why it read as nothing.
        assertTrue(ContrastMath.chroma(0x7D5260) < 25.0)
    }
}
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*ContrastMathTest*'
```

Expected: FAIL — `Unresolved reference: ContrastMath`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/expensegarden/app/ui/theme/ContrastMath.kt`:

```kotlin
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
```

- [ ] **Step 4: Run it and watch it pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*ContrastMathTest*'
```

Expected: `BUILD SUCCESSFUL`, 9 tests passed.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/theme/ContrastMath.kt app/src/test/java/com/expensegarden/app/ui/theme/ContrastMathTest.kt
git commit -m "feat: measure colour contrast, chroma and hue without an emulator"
```

---

## Task 2: The palette, and the tests that police it

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt`
- Test: `app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt`:

```kotlin
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
```

- [ ] **Step 2: Run it and watch it fail**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*GardenTokensTest*'
```

Expected: FAIL — `Unresolved reference: GardenTokens`.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt`:

```kotlin
package com.expensegarden.app.ui.theme

/**
 * The chrome's palette, as 0xRRGGBB Ints.
 *
 * Ints rather than Compose Colors so GardenTokensTest can police them on the JVM. Every value is
 * derived from GardenPalette (the canvas's own palette) and then adjusted until it cleared the
 * contrast and chroma floors — the source colour is named in the comment, the shipped value is
 * whatever passed.
 */
object GardenTokens {

    // ---- light: the garden at midday -------------------------------------------------------
    const val LightPrimary = 0x3F7A33               // hedgeDark 4F9140, darkened for 4.5:1
    const val LightOnPrimary = 0xFFFFFF
    const val LightPrimaryContainer = 0xC7E7AE      // grassA A7DD7F, lightened
    const val LightOnPrimaryContainer = 0x12300B
    const val LightSecondary = 0x7C5233             // wallLeft, verbatim
    const val LightOnSecondary = 0xFFFFFF
    const val LightSecondaryContainer = 0xEFDCC6
    const val LightOnSecondaryContainer = 0x2E1A08
    const val LightTertiary = 0x2F6C8C              // sky SUNNY 8FD3FF, darkened
    const val LightOnTertiary = 0xFFFFFF
    const val LightTertiaryContainer = 0xC7E6F5
    const val LightOnTertiaryContainer = 0x08303F
    const val LightError = 0xA4342B                 // warmer than Material's B3261E
    const val LightOnError = 0xFFFFFF
    const val LightErrorContainer = 0xFBD9D4
    const val LightOnErrorContainer = 0x3F0D09
    const val LightSurface = 0xFAF8F2               // warm paper, replacing lavender FEF7FF
    const val LightOnSurface = 0x1C1B17
    const val LightSurfaceVariant = 0xE7E3D6
    const val LightOnSurfaceVariant = 0x4A4739
    const val LightOutline = 0x7C7767

    // ---- dark: the same garden at dusk -----------------------------------------------------
    const val DarkPrimary = 0x9BD374                // grassB, verbatim
    const val DarkOnPrimary = 0x11300A
    const val DarkPrimaryContainer = 0x2F5423
    const val DarkOnPrimaryContainer = 0xC7E7AE
    const val DarkSecondary = 0xD8B48F
    const val DarkOnSecondary = 0x45280F
    const val DarkSecondaryContainer = 0x5E3D21
    const val DarkOnSecondaryContainer = 0xEFDCC6
    const val DarkTertiary = 0x8FCDEA
    const val DarkOnTertiary = 0x0A344A
    const val DarkTertiaryContainer = 0x1E4C63
    const val DarkOnTertiaryContainer = 0xC7E6F5
    const val DarkError = 0xFF8A80
    const val DarkOnError = 0x5F1410
    const val DarkErrorContainer = 0x7E2118
    const val DarkOnErrorContainer = 0xFBD9D4
    const val DarkSurface = 0x14171A
    const val DarkOnSurface = 0xE6E3DA
    const val DarkSurfaceVariant = 0x2A2E28
    const val DarkOnSurfaceVariant = 0xC6C4B6
    const val DarkOutline = 0x938F80

    // ---- budget status ---------------------------------------------------------------------
    // Material 3 has no slot for these, which is exactly why they were borrowed from primary and
    // tertiary and ended up meaning nothing. Green/amber/red is the one convention a budget app
    // may assume its user already knows.
    const val LightOnPace = 0x3F7A33
    const val LightWarning = 0x8F6300
    const val LightOver = 0xA4342B

    const val DarkOnPace = 0x9BD374
    const val DarkWarning = 0xE3B057
    // NOT Material's dark error F2B8B5: that pale pink is chroma 22.7 and fails the floor.
    const val DarkOver = 0xFF8A80

    val lightStatus = mapOf("onPace" to LightOnPace, "warning" to LightWarning, "over" to LightOver)
    val darkStatus = mapOf("onPace" to DarkOnPace, "warning" to DarkWarning, "over" to DarkOver)
}
```

- [ ] **Step 4: Run it and watch it pass**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest --tests '*GardenTokensTest*'
```

Expected: `BUILD SUCCESSFUL`, 5 tests passed. **If any assertion fails, stop and report the exact numbers** — do not widen a floor to make it green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/theme/GardenTokens.kt app/src/test/java/com/expensegarden/app/ui/theme/GardenTokensTest.kt
git commit -m "feat: derive the chrome palette from the garden and police its contrast"
```

---

## Task 3: GardenTheme, and wiring it in

**Files:**
- Create: `app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt`
- Modify: `app/src/main/java/com/expensegarden/app/MainActivity.kt:51-60`

- [ ] **Step 1: Write GardenTheme**

Create `app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt`:

```kotlin
package com.expensegarden.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** 0xRRGGBB token -> opaque Compose Color. */
private fun rgb(token: Int) = Color(0xFF000000.toInt() or token)

/**
 * The three budget states. Carried beside the ColorScheme because Material 3 has no slot for
 * them: borrowing `primary` and `tertiary` is what made "on pace" and "ahead of pace" render as
 * two indistinguishable purples.
 */
@Immutable
data class GardenStatusColors(val onPace: Color, val warning: Color, val over: Color)

private val LightStatus = GardenStatusColors(
    onPace = rgb(GardenTokens.LightOnPace),
    warning = rgb(GardenTokens.LightWarning),
    over = rgb(GardenTokens.LightOver),
)

private val DarkStatus = GardenStatusColors(
    onPace = rgb(GardenTokens.DarkOnPace),
    warning = rgb(GardenTokens.DarkWarning),
    over = rgb(GardenTokens.DarkOver),
)

private val LocalGardenStatusColors = staticCompositionLocalOf { LightStatus }

/** `GardenStatus.colors.onPace` at any call site inside GardenTheme. */
object GardenStatus {
    val colors: GardenStatusColors
        @Composable @ReadOnlyComposable get() = LocalGardenStatusColors.current
}

private val LightScheme = lightColorScheme(
    primary = rgb(GardenTokens.LightPrimary),
    onPrimary = rgb(GardenTokens.LightOnPrimary),
    primaryContainer = rgb(GardenTokens.LightPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.LightOnPrimaryContainer),
    secondary = rgb(GardenTokens.LightSecondary),
    onSecondary = rgb(GardenTokens.LightOnSecondary),
    secondaryContainer = rgb(GardenTokens.LightSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.LightOnSecondaryContainer),
    tertiary = rgb(GardenTokens.LightTertiary),
    onTertiary = rgb(GardenTokens.LightOnTertiary),
    tertiaryContainer = rgb(GardenTokens.LightTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.LightOnTertiaryContainer),
    error = rgb(GardenTokens.LightError),
    onError = rgb(GardenTokens.LightOnError),
    errorContainer = rgb(GardenTokens.LightErrorContainer),
    onErrorContainer = rgb(GardenTokens.LightOnErrorContainer),
    background = rgb(GardenTokens.LightSurface),
    onBackground = rgb(GardenTokens.LightOnSurface),
    surface = rgb(GardenTokens.LightSurface),
    onSurface = rgb(GardenTokens.LightOnSurface),
    surfaceVariant = rgb(GardenTokens.LightSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.LightOnSurfaceVariant),
    outline = rgb(GardenTokens.LightOutline),
)

private val DarkScheme = darkColorScheme(
    primary = rgb(GardenTokens.DarkPrimary),
    onPrimary = rgb(GardenTokens.DarkOnPrimary),
    primaryContainer = rgb(GardenTokens.DarkPrimaryContainer),
    onPrimaryContainer = rgb(GardenTokens.DarkOnPrimaryContainer),
    secondary = rgb(GardenTokens.DarkSecondary),
    onSecondary = rgb(GardenTokens.DarkOnSecondary),
    secondaryContainer = rgb(GardenTokens.DarkSecondaryContainer),
    onSecondaryContainer = rgb(GardenTokens.DarkOnSecondaryContainer),
    tertiary = rgb(GardenTokens.DarkTertiary),
    onTertiary = rgb(GardenTokens.DarkOnTertiary),
    tertiaryContainer = rgb(GardenTokens.DarkTertiaryContainer),
    onTertiaryContainer = rgb(GardenTokens.DarkOnTertiaryContainer),
    error = rgb(GardenTokens.DarkError),
    onError = rgb(GardenTokens.DarkOnError),
    errorContainer = rgb(GardenTokens.DarkErrorContainer),
    onErrorContainer = rgb(GardenTokens.DarkOnErrorContainer),
    background = rgb(GardenTokens.DarkSurface),
    onBackground = rgb(GardenTokens.DarkOnSurface),
    surface = rgb(GardenTokens.DarkSurface),
    onSurface = rgb(GardenTokens.DarkOnSurface),
    surfaceVariant = rgb(GardenTokens.DarkSurfaceVariant),
    onSurfaceVariant = rgb(GardenTokens.DarkOnSurfaceVariant),
    outline = rgb(GardenTokens.DarkOutline),
)

/**
 * Typography and shapes are Material's defaults on purpose (spec section 9): Roboto is neutral
 * and legible, and a display face is a real design commitment with asset weight behind it.
 */
@Composable
fun GardenTheme(
    dark: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalGardenStatusColors provides if (dark) DarkStatus else LightStatus,
    ) {
        MaterialTheme(colorScheme = if (dark) DarkScheme else LightScheme, content = content)
    }
}
```

- [ ] **Step 2: Wire it into MainActivity**

In `app/src/main/java/com/expensegarden/app/MainActivity.kt`, replace the body of `onCreate` (lines 51-60) with:

```kotlin
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // targetSdk 35 enforces edge-to-edge anyway; saying so explicitly keeps the layout's
        // inset handling correct if that target ever moves back.
        WindowCompat.setDecorFitsSystemWindows(window, false)
        // One push per open, on top of the per-write signals (spec §4).
        (application as GardenApp).container.scheduler.signal()
        setContent {
            GardenTheme {
                Surface { GardenNav(vm, dashVm, gardenVm, aiVm) }
            }
        }
    }
```

Replace the import `androidx.compose.material3.MaterialTheme` (line 15) with:

```kotlin
import androidx.core.view.WindowCompat
import com.expensegarden.app.ui.theme.GardenTheme
```

Keep `import androidx.compose.material3.Surface` (line 16) — it is still used.

- [ ] **Step 3: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/theme/GardenTheme.kt app/src/main/java/com/expensegarden/app/MainActivity.kt
git commit -m "feat: paint the app in the garden's colours instead of Material baseline purple"
```

---

## Task 4: Status colours on the dashboard

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt:210-215`

- [ ] **Step 1: Replace severityColor**

Replace the `severityColor` function at the bottom of `DashboardScreen.kt` (lines 210-215) with:

```kotlin
/** Green / amber / red. Never `primary`/`tertiary` again — those are brand slots, and using
 *  them here rendered "on pace" and "ahead of pace" as two purples 1.00:1 apart. */
@Composable
private fun severityColor(s: Severity): Color = when (s) {
    Severity.OK -> GardenStatus.colors.onPace
    Severity.PACE_WARNING -> GardenStatus.colors.warning
    Severity.BREACH -> GardenStatus.colors.over
}
```

Add the import, alphabetically after the `com.expensegarden.app.stats.ScopeStat` import:

```kotlin
import com.expensegarden.app.ui.theme.GardenStatus
```

- [ ] **Step 2: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`. (`MaterialTheme` is still imported and used elsewhere in the file for `typography`; `Color` is still used as the return type.)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt
git commit -m "fix: give the budget states three colours a user can tell apart"
```

---

## Task 5: The window theme and per-screen bar icons

**Files:**
- Create: `app/src/main/res/values/colors.xml`, `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/values-night/colors.xml`, `app/src/main/res/values-night/themes.xml`
- Create: `app/src/main/java/com/expensegarden/app/ui/SystemBars.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: all five screens (one line each)

- [ ] **Step 1: Create the resource files**

`app/src/main/res/values/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Matches GardenTokens.LightSurface, so the launch window does not flash a different
         colour before Compose draws. -->
    <color name="window_background">#FAF8F2</color>
    <color name="ic_launcher_background">#EEF9E0</color>
</resources>
```

`app/src/main/res/values-night/colors.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- GardenTokens.DarkSurface -->
    <color name="window_background">#14171A</color>
</resources>
```

`app/src/main/res/values/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!-- Replaces @android:style/Theme.Material.Light.NoActionBar, which is the platform theme
         from Android 5.0 and predates windowLightStatusBar entirely. That is why the status bar
         glyphs rendered white on a near-white surface at 1.05:1. -->
    <style name="Theme.ExpenseGarden" parent="@android:style/Theme.Material.Light.NoActionBar">
        <item name="android:windowBackground">@color/window_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">true</item>
    </style>
</resources>
```

`app/src/main/res/values-night/themes.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="Theme.ExpenseGarden" parent="@android:style/Theme.Material.NoActionBar">
        <item name="android:windowBackground">@color/window_background</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
        <item name="android:windowLightStatusBar">false</item>
    </style>
</resources>
```

- [ ] **Step 2: Create SystemBars.kt**

`app/src/main/java/com/expensegarden/app/ui/SystemBars.kt`:

```kotlin
package com.expensegarden.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * Which way the status and navigation bar glyphs are drawn, declared per screen.
 *
 * One XML value cannot be right everywhere. In dark mode the dashboard is dark and wants light
 * glyphs, while home still shows a bright daytime sky and wants dark ones — the canvas keeps its
 * own palette and never follows the theme (spec §3.2), so only the screen knows.
 *
 * @param darkIcons true to draw dark glyphs, i.e. for a LIGHT background behind the bars.
 */
@Composable
fun SystemBarIcons(darkIcons: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return
    SideEffect {
        val window = view.context.findActivity()?.window ?: return@SideEffect
        // "AppearanceLight" describes the BAR, not the glyphs: light bar => dark glyphs.
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = darkIcons
            isAppearanceLightNavigationBars = darkIcons
        }
    }
}
```

- [ ] **Step 3: Point the manifest at the new theme**

In `app/src/main/AndroidManifest.xml`, change the `android:theme` line:

```xml
        android:theme="@style/Theme.ExpenseGarden"
```

- [ ] **Step 4: Declare polarity in each screen**

Add one call as the first statement inside each screen composable's body.

`GardenHomeScreen.kt` — immediately after the `val dateFmt = ...` line (line 79):

```kotlin
    // The garden sky is always light, in both themes — the canvas does not follow the scheme.
    SystemBarIcons(darkIcons = true)
```

`DashboardScreen.kt` (after line 59, `val dateFmt = ...`), `GreenhouseScreen.kt` (after line 55, `val monthFmt = ...`), `SettingsScreen.kt` (after line 66, `val scope = rememberCoroutineScope()`), and `EntryScreen.kt` (after line 78, the `LaunchedEffect(Unit) { amountFocus.requestFocus() }`):

```kotlin
    SystemBarIcons(darkIcons = !isSystemInDarkTheme())
```

Those four files need one import each:

```kotlin
import androidx.compose.foundation.isSystemInDarkTheme
```

- [ ] **Step 5: Build and install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 6: Verify the contrast on device**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell am force-stop com.expensegarden.app
adb shell am start -n com.expensegarden.app/.MainActivity && sleep 5
adb exec-out screencap > /tmp/verify-home.raw
```

Then run the measurement in `docs/superpowers/plans/2026-09-13-design-pass.md` Task 10, Step 1.
Expected: status bar contrast **≥ 3:1**, versus the 1.99:1 measured before this pass.

- [ ] **Step 7: Commit**

```bash
git add app/src/main/res app/src/main/java/com/expensegarden/app/ui/SystemBars.kt app/src/main/AndroidManifest.xml app/src/main/java/com/expensegarden/app/ui/*Screen.kt
git commit -m "fix: make the status bar legible by giving the app a real window theme"
```

---

## Task 6: Insets

**Files:**
- Modify: `DashboardScreen.kt:61`, `GreenhouseScreen.kt:57`, `SettingsScreen.kt:77`, `EntryScreen.kt:94`, `GardenHomeScreen.kt:101,129,136`

- [ ] **Step 1: The four scrolling screens**

In each, replace the root modifier. `safeDrawing` is the union of system bars, display cutout
**and** IME, so it replaces `statusBarsPadding()` and `imePadding()` together and cannot
double-pad.

`DashboardScreen.kt:61`:
```kotlin
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
```

`GreenhouseScreen.kt:57` — identical replacement.

`SettingsScreen.kt:77` and `EntryScreen.kt:94`:
```kotlin
    Column(
        Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
```

In all four, replace the `statusBarsPadding` import (and `imePadding` where present) with:

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
```

Also update `GreenhouseScreen.kt:104` (the full-screen month viewer's back button):
```kotlin
                modifier = Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(12.dp),
```

- [ ] **Step 2: Home — bottom edge only, because the canvas must bleed**

Insetting home's root would letterbox the island, so the `Box` and `GardenCanvas` stay
edge-to-edge and each floating chrome element is inset on its own edges.

**Home's top chrome is left alone here** — Task 7 rewrites that whole block and applies its own
inset, so editing it twice would just mean reverting the first edit. This step touches only the
bottom.

In `GardenHomeScreen.kt`, the bottom `Column` (line 136) becomes:

```kotlin
        Column(
            Modifier.align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom + WindowInsetsSides.Horizontal))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
```

Imports to add in `GardenHomeScreen.kt`. Keep `statusBarsPadding` for now — the two remaining
uses (the strip at line 101 and the nav `Row` at line 129) are both replaced in Task 7, which
removes the import then:

```kotlin
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
```

- [ ] **Step 3: Build and install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify — this is the defect, so measure it**

Run Task 10 Step 2 under **both** navigation modes.
Expected: FAB bottom edge strictly **above** the nav bar inset top in both. Before this pass it
was 2358 vs a 2274 inset on 3-button nav — 57% of each button occluded.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui
git commit -m "fix: keep the primary actions out from under the navigation bar"
```

---

## Task 7: Home survives font scale 2.0

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt:98-134, 170-179`

- [ ] **Step 1: Stack the top chrome instead of positioning it**

The greenhouse/settings row is currently absolutely positioned with a hardcoded `top = 64.dp` to
clear the strip. At font scale 2.0 the strip grows past 64dp and they overlap by 21px. Put both
in one `Column` so they stack and the magic number disappears.

Replace the whole block from the `val stripSrc = ...` line through the closing brace of the
greenhouse/settings `Row` with the following. Anchor on the code, not the line numbers — Task 6
shifted them by a few lines. This is also what removes the last two uses of `statusBarsPadding`,
so **delete that import** afterwards.

```kotlin
        // Translucent stats strip — the same homeHeader the 1B home used.
        val stripSrc = remember { MutableInteractionSource() }
        Column(
            Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                .align(Alignment.TopCenter),
        ) {
            Surface(
                color = Color.White.copy(alpha = .82f),
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    .fillMaxWidth()
                    .then(pressBounce(stripSrc, down = .97f))
                    .clickable(interactionSource = stripSrc, indication = LocalIndication.current, onClick = onOpenDashboard),
                shape = MaterialTheme.shapes.medium,
            ) {
                Row(
                    Modifier.padding(horizontal = 14.dp, vertical = 10.dp).fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
                ) {
                    val h = header
                    if (h == null) Text(" ", style = MaterialTheme.typography.titleMedium)
                    else {
                        Text(Money.display(h.spentPaise), style = MaterialTheme.typography.titleMedium)
                        val streak = homestead?.state?.streakDays ?: 0
                        val streakSuffix = if (streak > 0) " · 🌱${streak}d" else ""   // the streaks-lite counter (spec §1)
                        // The chevron is unconditional on purpose. This label used to read
                        // "dashboard →" ONLY while no budget was set, so the one hint that the
                        // strip is tappable was visible to brand-new users and to nobody else.
                        //
                        // weight + End alignment so a long label wraps inside its own slot. With
                        // two unbounded Texts, SpaceBetween let this one overrun the amount on
                        // its left at large font scales.
                        Text(
                            (h.overallBudgetPaise?.let { "${Money.display(it)} · ${gardenHint(h.hint)}" }
                                ?: "set a budget") + streakSuffix + "  ›",
                            style = MaterialTheme.typography.labelMedium,
                            textAlign = TextAlign.End,
                            modifier = Modifier.weight(1f).padding(start = 8.dp),
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.padding(start = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                TextButton(onClick = onOpenGreenhouse) { Text("🏡 greenhouse") }
                TextButton(onClick = onOpenSettings) { Text("⚙️ settings") }
            }
        }
```

Add the imports:

```kotlin
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
```

- [ ] **Step 2: Make the two FABs the same size**

At font scale 2.0 "Log manually" wraps to two lines and "Scan & pay" does not, so they render
98px and 196px tall. Equal weights make them wrap or not as a pair.

**Correction, found during execution:** `weight(1f)` equalises WIDTH only. At font scale 2.0
"Log manually" still wrapped to two lines beside a one-line "Scan & pay" — measured 147px vs
196px, better than the original 98/196 but not equal. The Row also needs `IntrinsicSize.Max`
with `fillMaxHeight()` on each button. Replace the FAB `Row` (lines 170-179) with:

```kotlin
            // IntrinsicSize.Max + fillMaxHeight so the two buttons match even when only the
            // longer label wraps. weight(1f) alone equalises WIDTH only, which at font scale
            // 2.0 left "Log manually" two lines tall beside a one-line "Scan & pay".
            Row(
                Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val scanSrc = remember { MutableInteractionSource() }
                val manualSrc = remember { MutableInteractionSource() }
                ExtendedFloatingActionButton(
                    onClick = onScan,
                    modifier = Modifier.weight(1f).fillMaxHeight().then(pressBounce(scanSrc)),
                    interactionSource = scanSrc,
                ) { Text("Scan & pay") }
                ExtendedFloatingActionButton(
                    onClick = onManual,
                    modifier = Modifier.weight(1f).fillMaxHeight().then(pressBounce(manualSrc)),
                    interactionSource = manualSrc,
                ) { Text("Log manually") }
            }
```

- [ ] **Step 3: Build and install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify at font scale 2.0**

Run Task 10 Step 3.
Expected: strip bottom **above** the nav row top (was −21px), both FAB heights **equal** (were
98 and 196), and no text overlap in the strip.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/GardenHomeScreen.kt
git commit -m "fix: stop the home chrome colliding at large accessibility font scales"
```

---

## Task 8: A launcher icon

**Files:**
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`, `ic_launcher_monochrome.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: The sprout**

`app/src/main/res/drawable/ic_launcher_foreground.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<!-- A two-leaf sprout. Vector rather than raster: no new art pipeline, no asset weight, every
     density for free. All geometry sits inside the 66/108 adaptive-icon safe circle centred on
     (54,54), so nothing is clipped by an OEM mask shape. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <!-- stem: GardenTokens.DarkPrimaryContainer -->
    <path
        android:fillColor="#2F5423"
        android:pathData="M51,84 L57,84 L57,48 L51,48 Z" />
    <!-- lower-left leaf: GardenPalette.leaf -->
    <path
        android:fillColor="#6FB54A"
        android:pathData="M52,62 C52,52 45,44 34,42 C32,54 39,63 52,64 Z" />
    <!-- upper-right leaf: GardenPalette.grassA -->
    <path
        android:fillColor="#A7DD7F"
        android:pathData="M56,54 C56,43 63,35 74,33 C76,45 69,54 56,56 Z" />
</vector>
```

`app/src/main/res/drawable/ic_launcher_monochrome.xml` — the same shape as one silhouette, for
Android 13+ themed icons (the system supplies the tint, so the fill colour is only a placeholder):

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:fillColor="#000000"
        android:pathData="M51,84 L57,84 L57,48 L51,48 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M52,62 C52,52 45,44 34,42 C32,54 39,63 52,64 Z" />
    <path
        android:fillColor="#000000"
        android:pathData="M56,54 C56,43 63,35 74,33 C76,45 69,54 56,56 Z" />
</vector>
```

`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` — `minSdk 26` means the legacy PNG fallback
would be dead code, so `anydpi-v26` alone is the whole icon:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
    <monochrome android:drawable="@drawable/ic_launcher_monochrome" />
</adaptive-icon>
```

- [ ] **Step 2: Point the manifest at it**

In `app/src/main/AndroidManifest.xml`, replace the stock placeholder:

```xml
        android:icon="@mipmap/ic_launcher"
```

- [ ] **Step 3: Build and install**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Look at it**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell input keyevent KEYCODE_HOME && sleep 1
adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME && sleep 2
adb shell screencap -p /sdcard/launcher.png && adb pull /sdcard/launcher.png /tmp/launcher.png
```

Open `/tmp/launcher.png`. Expected: a green sprout on a pale ground, not the stock Android robot.
If the shape is clipped by the launcher's mask, the paths are outside the safe circle — report it.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/res/drawable app/src/main/res/mipmap-anydpi-v26 app/src/main/AndroidManifest.xml
git commit -m "feat: give the app a launcher icon instead of the stock placeholder"
```

---

## Task 9: Progress bars stop snapping

**Files:**
- Modify: `app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt:194-200`, `GreenhouseScreen.kt:65`

- [ ] **Step 1: Animate the fraction**

The lambda-progress `LinearProgressIndicator` draws exactly what it is handed, so budget bars
snap while the money figure directly above them does a spring odometer roll. Replace the
`row.budgetPaise?.let { ... }` block (lines 194-200) with:

```kotlin
        row.budgetPaise?.let { budget ->
            // Same spring as the odometer above it, so two adjacent widgets stop disagreeing
            // about whether this app animates.
            val fraction by animateFloatAsState(
                targetValue = (row.spentPaise.toFloat() / budget).coerceIn(0f, 1f),
                animationSpec = spring(dampingRatio = 0.8f, stiffness = 380f),
                label = "budgetBar",
            )
            LinearProgressIndicator(
                progress = { fraction },
                color = severityColor(row.severity),
                modifier = Modifier.fillMaxWidth(),
            )
        }
```

Add the import:

```kotlin
import androidx.compose.animation.core.animateFloatAsState
```

- [ ] **Step 2: Make the two loading skeletons match**

`GreenhouseScreen.kt:65` uses a fully opaque card where `DashboardScreen.kt:71` fades its
identical one. Match the dashboard:

```kotlin
            months == null -> Card(Modifier.fillMaxWidth().height(120.dp).alpha(0.3f)) {}
```

Add the import:

```kotlin
import androidx.compose.ui.draw.alpha
```

- [ ] **Step 3: Build**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew assembleDebug
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/expensegarden/app/ui/DashboardScreen.kt app/src/main/java/com/expensegarden/app/ui/GreenhouseScreen.kt
git commit -m "fix: animate the budget bars and match the two loading skeletons"
```

---

## Task 10: Verification

Insets and window flags cannot be asserted from a unit test, so they are measured. Every expected
number below is the **before** figure recorded on 2026-09-13, so a regression is visible.

- [ ] **Step 1: Status bar contrast**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
cd "$(mktemp -d)"
adb shell am force-stop com.expensegarden.app
adb shell am start -n com.expensegarden.app/.MainActivity >/dev/null && sleep 5
adb exec-out screencap > home.raw
adb shell input tap 540 205 && sleep 3
adb exec-out screencap > dash.raw
python3 - <<'PY'
import struct
def load(p):
    d=open(p,'rb').read(); w,h,_=struct.unpack('<III',d[:12]); return d,w,16
def px(d,w,hdr,x,y):
    o=hdr+(y*w+x)*4; return d[o],d[o+1],d[o+2]
def lum(c):
    f=lambda v:(v/255)/12.92 if (v/255)<=.04045 else (((v/255)+.055)/1.055)**2.4
    return .2126*f(c[0])+.7152*f(c[1])+.0722*f(c[2])
def cr(a,b):
    L=sorted([lum(a),lum(b)],reverse=True); return (L[0]+.05)/(L[1]+.05)
for name,p in (("home","home.raw"),("dashboard","dash.raw")):
    d,w,hdr=load(p)
    reg=[px(d,w,hdr,x,y) for x in range(60,160) for y in range(50,85)]
    glyph=max(reg,key=lum); dark=min(reg,key=lum); bg=px(d,w,hdr,420,65)
    best=max(cr(glyph,bg),cr(dark,bg))
    print(f"{name:10} bg=#{bg[0]:02X}{bg[1]:02X}{bg[2]:02X}  best glyph contrast = {best:.2f}:1  {'PASS' if best>=3 else 'FAIL'}")
PY
```

Expected: both **≥ 3:1**. Before: home 1.99:1, dashboard 1.05:1.

- [ ] **Step 2: Nav bar clearance, both navigation modes**

**Measure PIXELS, not the accessibility tree.** uiautomator reports a Compose FAB's
`android.widget.Button` bounds as its expanded **48dp touch target**, not its visual rect — on
this device that is 2358 where the button is actually drawn to 2294. A tree-based check reports
FAIL on a correct build, and on the broken build it reported PASS under gesture nav by measuring
the label instead. Both directions of error happened while writing this plan.

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
for mode in gestural threebutton; do
  adb shell cmd overlay enable com.android.internal.systemui.navbar.$mode >/dev/null 2>&1; sleep 4
  adb shell am force-stop com.expensegarden.app
  adb shell am start -n com.expensegarden.app/.MainActivity >/dev/null && sleep 6
  adb shell dumpsys window > /tmp/win.txt
  adb exec-out screencap > /tmp/fab.raw
  MODE="$mode" python3 "$SCRATCH/fabcheck.py" /tmp/fab.raw
done
adb shell cmd overlay enable com.android.internal.systemui.navbar.gestural >/dev/null 2>&1
```

where `fabcheck.py` is:

```python
import struct, sys, os, re
d = open(sys.argv[1], 'rb').read()
w, h, _ = struct.unpack('<III', d[:12]); hdr = 16
def px(x, y):
    o = hdr + (y * w + x) * 4; return d[o], d[o+1], d[o+2]
def isfab(c):                        # GardenTokens.LightPrimaryContainer #C7E7AE
    r, g, b = c
    return abs(r-199) < 14 and abs(g-231) < 14 and abs(b-174) < 14
rows = [y for y in range(1800, h) if sum(1 for x in range(0, w, 3) if isfab(px(x, y))) > 20]
win = open('/tmp/win.txt', errors='ignore').read()
m = re.search(r'type=navigationBars frame=\[\d+,(\d+)\]\[\d+,(\d+)\]', win)
inset = int(m.group(1)) if m else None
mode = os.environ['MODE']
if not rows:
    print(f"  {mode}: FAB colour not found - is home foregrounded?"); raise SystemExit
verdict = 'PASS' if max(rows) < inset else 'FAIL'
print(f"  {mode:12} FAB pixels {min(rows)}..{max(rows)}  navbar inset top={inset}  -> {verdict}")
```

Expected: **PASS** in both, with the FAB tracking the inset (~2294 gestural, ~2231 three-button).
Verified to FAIL on the pre-pass build: FAB pixels ended at 2357 in both modes, against insets of
2337 and 2274.

- [ ] **Step 3: Font scale 2.0**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell settings put system font_scale 2.0 && sleep 5
adb shell am force-stop com.expensegarden.app
adb shell am start -n com.expensegarden.app/.MainActivity >/dev/null && sleep 6
adb shell uiautomator dump /sdcard/u.xml >/dev/null 2>&1 && adb shell cat /sdcard/u.xml > /tmp/u2.xml
python3 - <<'PY'
import re
x=open('/tmp/u2.xml').read()
els={}
for m in re.finditer(r'text="([^"]*)"[^>]*?bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"', x):
    if m.group(1).strip(): els[m.group(1)]=tuple(map(int,m.groups()[1:]))
strip=next((v for k,v in els.items() if 'ahead' in k or '₹100' in k), None)
nav=next((v for k,v in els.items() if 'greenhouse' in k), None)
scan=next((v for k,v in els.items() if 'Scan' in k), None)
man=next((v for k,v in els.items() if 'Log manually' in k), None)
if strip and nav:
    print(f"strip bottom={strip[3]}  nav row top={nav[1]}  gap={nav[1]-strip[3]}  {'PASS' if nav[1]>=strip[3] else 'FAIL'}")
if scan and man:
    hs,hm=scan[3]-scan[1],man[3]-man[1]
    print(f"FAB heights: scan={hs} manual={hm}  {'PASS' if abs(hs-hm)<=4 else 'FAIL'}")
PY
adb shell settings put system font_scale 1.0
```

Expected: gap **≥ 0** (was −21) and FAB heights equal (were 98 vs 196).

Two gotchas found in execution: (a) `settings put system font_scale` needs several seconds and an
app restart to reach the process — confirm with
`adb shell dumpsys activity a com.expensegarden.app | grep CurrentConfiguration`, whose leading
number is the live fontScale; (b) uiautomator's dump lags a config change, so measure the FAB
heights from **pixels** as in Step 2, not from the tree.

- [ ] **Step 4: Dark mode actually changes**

```bash
export PATH="$HOME/Library/Android/sdk/platform-tools:$PATH"
adb shell cmd uimode night yes && sleep 4
adb shell am force-stop com.expensegarden.app
adb shell am start -n com.expensegarden.app/.MainActivity >/dev/null && sleep 5
adb shell input tap 540 205 && sleep 3
adb exec-out screencap > /tmp/dark.raw
python3 - <<'PY'
import struct
d=open('/tmp/dark.raw','rb').read(); w,_,_=struct.unpack('<III',d[:12])
o=16+(1200*w+950)*4
print(f"dark-mode dashboard surface = #{d[o]:02X}{d[o+1]:02X}{d[o+2]:02X}  (expect ~#14171A, was #FEF7FF)")
PY
adb shell cmd uimode night no
```

Expected: roughly `#14171A`. Before: `#FEF7FF` regardless of the system setting.

- [ ] **Step 5: Full suite**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew testDebugUnitTest
./gradlew lintDebug
```

Expected: all JVM tests pass (310 existing + 14 new = **324**), 0 lint errors.

Then, alone, because `connectedDebugAndroidTest` uninstalls both APKs:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew connectedDebugAndroidTest
```

Expected: 71 instrumented tests pass. None of them assert on colour, so this pass should not move
that number — **if it does, stop and report.**

**Known pre-existing failure, unrelated to this pass.** `RareDeterminismTest.two_dodges_grow_no_rare`
and `re_tagging_a_regret_repeatedly_still_grows_only_one_rare` FAIL when the device date is on or
after the 8th of a month and pass before it. They build fixtures from `System.currentTimeMillis()`,
and `StreakMath.underPaceStreak` credits every elapsed day of the current month against an empty
ledger — so from day 8 onward the fold already has a 7-day streak and `STREAK_7` grows a rare the
test never asked for. Confirmed by running the same tree at `date 090312002026.00` (6/6 pass) and
at the real date of 13 Sep (2 fail). This is the fresh-install streak baseline question surfacing
as a flaky suite; it needs its own decision and is NOT in scope here.

- [ ] **Step 6: Reinstall, since the instrumented run wiped the app**

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

- [ ] **Step 7: Update CLAUDE.md's test count**

Change `testDebugUnitTest` from `(310)` to `(324)` in the Commands section.

```bash
git add CLAUDE.md
git commit -m "docs: record the new unit test count"
```

---

## Notes for whoever executes this

- **Do not widen a contrast or chroma floor to make a test green.** The floors are the point; the
  hexes are negotiable. If one fails, report the measured number.
- **Never touch `GardenPalette.kt`.** The canvas keeps its own palette in both themes by design
  (spec §3.2). Nothing in this plan changes how the island is drawn.
- `connectedDebugAndroidTest` uninstalls both APKs when it finishes. Install last, alone.
- The emulator needs `-gpu host` on a memory-constrained host or it silently falls back to
  software GL and ANRs:
  ```bash
  ~/Library/Android/sdk/emulator/emulator -avd Pixel_8_API_35 -no-boot-anim -gpu host -memory 2048
  ```

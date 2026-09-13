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

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

    // ---- every ColorScheme role, explicitly (review fix, 2026-10-11) -------------------------
    // Material 3 fills any role you don't pass with its baseline PURPLE. The surface-container
    // roles arrived in M3 1.2, and dialogs, sheets and cards moved onto them, so a scheme that set
    // only `surface` still painted all of those lavender (#ECE6F0, #E6E0E9, #F7F2FA). Each value
    // below keeps its surface's Lab hue and steps lightness to M3's tone targets.
    const val LightSurfaceDim = 0xDCDAD4              // L* 87
    const val LightSurfaceBright = LightSurface       // M3: bright is the surface in light
    const val LightSurfaceContainerLowest = 0xFFFFFF  // L* 100
    const val LightSurfaceContainerLow = 0xF5F3ED     // L* 96 — ModalBottomSheet
    const val LightSurfaceContainer = 0xF0EEE8        // L* 94
    const val LightSurfaceContainerHigh = 0xEAE8E2    // L* 92 — AlertDialog
    const val LightSurfaceContainerHighest = 0xE4E2DC // L* 90 — Card
    const val LightOutlineVariant = 0xCCC6B5          // outline's hue at L* 80
    const val LightInverseSurface = 0x32302C          // L* 20
    const val LightInverseOnSurface = 0xF3F1EB        // L* 95
    const val LightInversePrimary = DarkPrimary
    const val LightScrim = 0x000000

    const val DarkSurfaceDim = DarkSurface            // M3: dim is the surface in dark
    const val DarkSurfaceBright = 0x36393D            // L* 24
    const val DarkSurfaceContainerLowest = 0x0A0F13   // L* 4
    const val DarkSurfaceContainerLow = 0x191C1F      // L* 10 — ModalBottomSheet
    const val DarkSurfaceContainer = 0x1D2023         // L* 12
    const val DarkSurfaceContainerHigh = 0x272A2E     // L* 17 — AlertDialog
    const val DarkSurfaceContainerHighest = 0x323538  // L* 22 — Card
    const val DarkOutlineVariant = 0x4A4739           // outline's hue at L* 30
    // L* 95, not M3's 90: lifted so inversePrimary (the light primary) clears 4.5:1 — 4.58 here,
    // 4.03 at 90.
    const val DarkInverseSurface = 0xEDF1F6
    const val DarkInverseOnSurface = 0x2E3134         // L* 20
    const val DarkInversePrimary = LightPrimary
    const val DarkScrim = 0x000000

    /** Role name -> token, for every ColorScheme role. GardenSchemeTest checks both schemes carry
     *  exactly these, and fails on any role Material adds that has no entry here. */
    val lightRoles: Map<String, Int> = mapOf(
        "primary" to LightPrimary, "onPrimary" to LightOnPrimary,
        "primaryContainer" to LightPrimaryContainer, "onPrimaryContainer" to LightOnPrimaryContainer,
        "inversePrimary" to LightInversePrimary,
        "secondary" to LightSecondary, "onSecondary" to LightOnSecondary,
        "secondaryContainer" to LightSecondaryContainer, "onSecondaryContainer" to LightOnSecondaryContainer,
        "tertiary" to LightTertiary, "onTertiary" to LightOnTertiary,
        "tertiaryContainer" to LightTertiaryContainer, "onTertiaryContainer" to LightOnTertiaryContainer,
        "background" to LightSurface, "onBackground" to LightOnSurface,
        "surface" to LightSurface, "onSurface" to LightOnSurface,
        "surfaceVariant" to LightSurfaceVariant, "onSurfaceVariant" to LightOnSurfaceVariant,
        "surfaceTint" to LightPrimary,
        "inverseSurface" to LightInverseSurface, "inverseOnSurface" to LightInverseOnSurface,
        "error" to LightError, "onError" to LightOnError,
        "errorContainer" to LightErrorContainer, "onErrorContainer" to LightOnErrorContainer,
        "outline" to LightOutline, "outlineVariant" to LightOutlineVariant, "scrim" to LightScrim,
        "surfaceBright" to LightSurfaceBright, "surfaceDim" to LightSurfaceDim,
        "surfaceContainer" to LightSurfaceContainer,
        "surfaceContainerHigh" to LightSurfaceContainerHigh,
        "surfaceContainerHighest" to LightSurfaceContainerHighest,
        "surfaceContainerLow" to LightSurfaceContainerLow,
        "surfaceContainerLowest" to LightSurfaceContainerLowest,
    )

    val darkRoles: Map<String, Int> = mapOf(
        "primary" to DarkPrimary, "onPrimary" to DarkOnPrimary,
        "primaryContainer" to DarkPrimaryContainer, "onPrimaryContainer" to DarkOnPrimaryContainer,
        "inversePrimary" to DarkInversePrimary,
        "secondary" to DarkSecondary, "onSecondary" to DarkOnSecondary,
        "secondaryContainer" to DarkSecondaryContainer, "onSecondaryContainer" to DarkOnSecondaryContainer,
        "tertiary" to DarkTertiary, "onTertiary" to DarkOnTertiary,
        "tertiaryContainer" to DarkTertiaryContainer, "onTertiaryContainer" to DarkOnTertiaryContainer,
        "background" to DarkSurface, "onBackground" to DarkOnSurface,
        "surface" to DarkSurface, "onSurface" to DarkOnSurface,
        "surfaceVariant" to DarkSurfaceVariant, "onSurfaceVariant" to DarkOnSurfaceVariant,
        "surfaceTint" to DarkPrimary,
        "inverseSurface" to DarkInverseSurface, "inverseOnSurface" to DarkInverseOnSurface,
        "error" to DarkError, "onError" to DarkOnError,
        "errorContainer" to DarkErrorContainer, "onErrorContainer" to DarkOnErrorContainer,
        "outline" to DarkOutline, "outlineVariant" to DarkOutlineVariant, "scrim" to DarkScrim,
        "surfaceBright" to DarkSurfaceBright, "surfaceDim" to DarkSurfaceDim,
        "surfaceContainer" to DarkSurfaceContainer,
        "surfaceContainerHigh" to DarkSurfaceContainerHigh,
        "surfaceContainerHighest" to DarkSurfaceContainerHighest,
        "surfaceContainerLow" to DarkSurfaceContainerLow,
        "surfaceContainerLowest" to DarkSurfaceContainerLowest,
    )
}

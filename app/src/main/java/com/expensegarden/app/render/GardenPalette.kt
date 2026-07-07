package com.expensegarden.app.render

import androidx.compose.ui.graphics.Color
import com.expensegarden.app.game.Weather

/** The whole scene's color vocabulary — matches the approved companion sample. */
object GardenPalette {
    fun sky(weather: Weather): List<Color> = when (weather) {
        Weather.SUNNY -> listOf(Color(0xFF8FD3FF), Color(0xFFCFEFFD), Color(0xFFEEF9E0))
        Weather.OVERCAST -> listOf(Color(0xFF9FB2C4), Color(0xFFC3CFD4), Color(0xFFDFE6DC))
        Weather.DROUGHT -> listOf(Color(0xFFD9B98A), Color(0xFFE8D3A8), Color(0xFFEFE3C2))
    }
    fun grassA(weather: Weather) = if (weather == Weather.DROUGHT) Color(0xFFC2BB6E) else Color(0xFFA7DD7F)
    fun grassB(weather: Weather) = if (weather == Weather.DROUGHT) Color(0xFFB0A85F) else Color(0xFF8CC968)
    val wallLeft = Color(0xFF7C5233)
    val wallRight = Color(0xFF63401F)
    val shadow = Color(0x28000000)
    val sun = Color(0xFFFFD54D)
    val sunHalo = Color(0x66FFE37E)
    val cloud = Color(0xF2FFFFFF)
    val trunk = Color(0xFF7A5230)
    val canopyLight = Color(0xFF93D47E)
    val canopyDark = Color(0xFF5DA24B)
    val stem = Color(0xFF5DA23C)
    val leaf = Color(0xFF6FB54A)
    val petalYellow = Color(0xFFFFCF3F)
    val petalCenterLight = Color(0xFFFFE066)
    val petalCenterDark = Color(0xFFF6A723)
    val tulipLight = Color(0xFFFF9BB0)
    val tulipDark = Color(0xFFE0577A)
    val bellViolet = Color(0xFF9A86D8)
    val hedgeLight = Color(0xFF79C268)
    val hedgeDark = Color(0xFF4F9140)
    val weedLight = Color(0xFF9A6FB4)
    val weedDark = Color(0xFF5F3C7A)
    val mushroomCap = Color(0xFFC96F8E)
    val mushroomStem = Color(0xFFEFE0C8)
    val sparkle = Color(0xCCFFFFFF)
    val butterflyA = Color(0xFF7DB8F2)
    val butterflyB = Color(0xFF9CCBF7)
}

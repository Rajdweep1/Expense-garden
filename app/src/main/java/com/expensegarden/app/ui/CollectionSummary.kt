package com.expensegarden.app.ui

import com.expensegarden.app.game.RareTier

/** The album's locked-slot arithmetic and its two strings.
 *
 *  Separate from the Composable so it can be unit-tested, and free of Compose imports so the
 *  JVM suite can load it. Lives in `ui/` rather than beside `RareCatalog` for the same reason
 *  `conditionFor` and `howEarned` do: the catalogue stays free of presentation strings.
 *
 *  This replaced one `"• ???"` row per unearned species. Sixteen identical rows on a fresh
 *  install carried a single count between them, and the card header already showed that count. */
object CollectionSummary {

    fun heading(tier: RareTier, found: Int, total: Int): String =
        "${tier.name.lowercase().replaceFirstChar { it.uppercase() }} — $found of $total"

    /** Null when there is nothing left to say — a fully collected tier gets no row at all,
     *  which is the entire point of the change.
     *
     *  Clamped at zero: `foundBy` is keyed by species id and outlives the catalogue, so
     *  retiring a species would otherwise print "-1 still hidden". */
    fun hiddenLine(found: Int, total: Int): String? {
        val hidden = (total - found).coerceAtLeast(0)
        return if (hidden == 0) null else "$hidden still hidden"
    }
}

package com.expensegarden.app.ai

import com.expensegarden.app.game.Weather

/** The ONLY type that may be serialized into a prompt (spec §10).
 *
 *  Everything here is a closed enum or a number, with one guarded exception: category names,
 *  which are whitelisted against the taxonomy seeded in AppDatabase. Payee names, transaction
 *  notes, VPAs and UUIDs have no field to live in, so there is no code path from a
 *  user-entered — or QR-supplied — string to the prompt builder.
 *
 *  Two risks, one rule. Payee names arrive from scanned UPI QR codes and are therefore
 *  attacker-controlled: a payee called "Ignore previous instructions and ..." would otherwise
 *  be interpolated straight into a prompt. And parent spec §8.2 records that free-tier
 *  providers may train on inputs, so anything sent may persist outside the device. A type
 *  that cannot hold free text answers both. */
data class PromptFacts(
    val weather: Weather,
    val houseLevel: Int,
    val streakDays: Int,
    val spentPaise: Long,
    val budgetPaise: Long?,
    val regretCount: Int,
    val dodgeCount: Int,
    val monthKey: String,
    val topCategories: List<String>,
) {
    /** Unknown names degrade to "Misc" rather than being dropped, so the prompt still says
     *  how many categories were involved. Today this branch is unreachable — CategoryDao is
     *  read-only, so the seeded taxonomy is closed — but it is what keeps §10 true the day a
     *  category editor is added. */
    fun safeCategories(): List<String> =
        topCategories.map { if (it in SEEDED_CATEGORIES) it else "Misc" }

    fun render(): String = buildString {
        appendLine("Month: $monthKey")
        appendLine("Weather: $weather")
        appendLine("Homestead level: $houseLevel of 4")
        appendLine("No-overspend streak: $streakDays days")
        appendLine("Spent this month: ₹${spentPaise / 100}")
        appendLine(budgetPaise?.let { "Budget: ₹${it / 100}" } ?: "Budget: not set")
        appendLine("Purchases regretted this month: $regretCount")
        appendLine("Times they backed out at the payment gate: $dodgeCount")
        appendLine("Biggest categories: ${safeCategories().joinToString(", ").ifBlank { "none" }}")
    }.trim()

    companion object {
        /** Mirrors the seed in AppDatabase.SeedCallback. PromptFactsTest asserts they agree —
         *  a category added there but not here would silently render as "Misc". */
        val SEEDED_CATEGORIES = setOf(
            "Food & Drinks", "Groceries", "Transport", "Housing", "Health", "Entertainment",
            "Shopping", "Personal", "Family", "Investments", "Misc", "Restaurants", "Delivery",
            "Chai & Snacks", "Fuel", "Cab & Auto", "Metro & Bus", "Rent", "Utilities",
            "Streaming", "Outings",
        )
    }
}

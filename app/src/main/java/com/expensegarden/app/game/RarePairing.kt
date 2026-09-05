package com.expensegarden.app.game

/** Banks each earned seed onto the next purchase that can honestly carry it (spec §4).
 *
 *  This exists to preserve the app's most load-bearing property: **every plant is exactly one
 *  real transaction, and it looks like what you actually bought**. Rewarding restraint collides
 *  with the first half — a no-spend week has no purchase behind it — and rewarding it with an
 *  arbitrary species would break the second. So a seed is banked, and it is only ever spent on
 *  a purchase whose natural archetype already matches the rare.
 *
 *  It reads better than any token grant, too: a week of restraint makes the next thing you buy
 *  grow into something better — not into something else.
 *
 *  Pure and order-stable, so the fold reproduces the same island on every replay. */
object RarePairing {

    /**
     * A transaction that might carry a rare.
     *
     * @param archetype what this purchase grows as naturally. A rare must match it, or the
     *   garden would misreport the category.
     * @param eligible false for weeds and zombies. An ineligible purchase does not *consume*
     *   the seed — it is skipped and the seed waits — so a breach or a regret can never eat a
     *   reward you earned by behaving well.
     */
    data class Candidate(
        val uuid: String,
        val occurredAt: Long,
        val archetype: Archetype,
        val eligible: Boolean,
    )

    /** @return uuid → the species that transaction should grow as. Absent means it grows normally. */
    fun assign(earns: List<Earn>, candidates: List<Candidate>): Map<String, RareSpecies> {
        // Landmarks are island features, not plants (spec §8). They belong to the album and,
        // from 4B, to the island itself — they must never consume a plant slot.
        val plantable = earns
            .filter { it.tier != RareTier.LANDMARK }
            .sortedWith(compareBy({ it.atMillis }, { it.scopeKey }))
        if (plantable.isEmpty()) return emptyMap()

        val ordered = candidates.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val out = LinkedHashMap<String, RareSpecies>()
        var next = 0

        for (c in ordered) {
            if (next >= plantable.size) break
            if (!c.eligible) continue
            val earn = plantable[next]
            // Strictly after: a purchase made before the earn cannot retroactively become it.
            // Without this the first transaction you ever logged would absorb a rare earned
            // years later, and its month in the greenhouse would be wrong.
            if (c.occurredAt <= earn.atMillis) continue
            // Declines purchases this tier has no honest form for — the seed waits rather than
            // turning your groceries into someone else's flower.
            val species = earn.speciesFor(c.archetype) ?: continue
            out[c.uuid] = species
            next++
        }
        return out
    }
}

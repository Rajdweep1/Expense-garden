package com.expensegarden.app.game

/** Banks each earned seed onto the next qualifying purchase (spec §4).
 *
 *  This exists to preserve the app's most load-bearing property: **every plant is exactly one
 *  real transaction**. Rewarding restraint collides with that head-on — a no-spend week has no
 *  purchase behind it — and the resolution is to bank the reward rather than plant something
 *  fictional. Once some plants are not purchases, "the garden is your spending" stops being
 *  literally true, and that sentence is the whole reason the garden is worth looking at.
 *
 *  It also reads better than any token grant: a week of restraint makes the next thing you buy
 *  grow into something better.
 *
 *  Pure and order-stable, so the fold reproduces the same island on every replay. */
object RarePairing {

    /**
     * A transaction that could carry a rare.
     *
     * @param eligible false for investments, weeds and zombies. An ineligible purchase does not
     *   *consume* the seed — it is skipped and the seed waits — so a breach or a regret can
     *   never eat a reward you earned by behaving well.
     */
    data class Candidate(val uuid: String, val occurredAt: Long, val eligible: Boolean)

    /** @return uuid → the [Earn] that transaction should render as. Absent means it grows normally. */
    fun assign(earns: List<Earn>, candidates: List<Candidate>): Map<String, Earn> {
        // Landmarks are island features, not plants (spec §8). They belong to the album and,
        // from 4B, to the island itself — they must never consume a plant slot.
        val plantable = earns
            .filter { it.tier != RareTier.LANDMARK }
            .sortedWith(compareBy({ it.atMillis }, { it.scopeKey }))
        if (plantable.isEmpty()) return emptyMap()

        val ordered = candidates.sortedWith(compareBy({ it.occurredAt }, { it.uuid }))
        val out = LinkedHashMap<String, Earn>()
        var next = 0

        for (c in ordered) {
            if (next >= plantable.size) break
            if (!c.eligible) continue
            val earn = plantable[next]
            // Strictly after: a purchase made before the earn cannot retroactively become it.
            // Without this, the first transaction you ever logged would absorb a rare earned
            // years later, and the plant's month in the greenhouse would be wrong.
            if (c.occurredAt <= earn.atMillis) continue
            out[c.uuid] = earn
            next++
        }
        return out
    }
}

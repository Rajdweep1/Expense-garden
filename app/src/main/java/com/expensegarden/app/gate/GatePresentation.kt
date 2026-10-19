package com.expensegarden.app.gate

import com.expensegarden.app.game.Archetype
import kotlin.math.abs

/**
 * What the gate shows. One variant per kind of stake, because the three cases genuinely differ:
 * a breach grows a weed, a pace warning costs the streak, and a necessity costs neither.
 *
 * Pure Kotlin on purpose — every branching rule here is a product decision, and decisions belong
 * in JVM tests rather than in a Composable no test can reach.
 */
sealed interface GateView {

    /**
     * Whether backing out of this counts as restraint.
     *
     * Declared on the interface so every variant is forced to answer. GATE_DODGES earns a rare
     * for backing out, and crediting a necessity would make that farmable - open a chemist
     * payment, tap Back three times, collect. 4A's rule is "earned by restraint, never by
     * spending", and a corrected amount on a necessity is neither.
     */
    val recordsDodge: Boolean

    /** Nothing to say. The silence rule: an OK verdict never interrupts. */
    data object None : GateView {
        override val recordsDodge: Boolean get() = false
    }

    /** A breach on a discretionary purchase — this really does grow a weed. */
    data class Weed(
        val archetype: Archetype,
        val variant: Int,
        val quip: String,
        val scopeLabel: String?,
        val overPaise: Long,
    ) : GateView {
        override val recordsDodge: Boolean get() = true
    }

    /** Ahead of pace on a discretionary purchase — nothing ugly grows, and the streak may end.
     *
     *  [days] is the streak this payment really ends, and 0 when it ends none: the streak is the
     *  overall budget's, so a category running ahead can't end it, and spend already over today's
     *  line lost today's streak day before this payment (review, 2026-10-18). [scopeLabel] names a
     *  category offender; null is the overall budget. */
    data class Streak(
        val days: Int,
        val quip: String,
        val overPaise: Long,
        val allowancePaise: Long,
        val scopeLabel: String? = null,
        val alreadyAhead: Boolean = false,
    ) : GateView {
        override val recordsDodge: Boolean get() = true

        /** A brand-new ledger has no streak yet, and "this ends a 0-day streak" is nonsense.
         *  At zero there is simply nothing to lose but the pace itself. */
        val endsAStreak: Boolean get() = days > 0
    }

    /**
     * A necessity, at either severity. No quip and no dodge, by construction rather than by
     * convention — there is no field here to put one in.
     */
    data class Neutral(
        val scopeLabel: String,
        val afterPaise: Long,
        val budgetPaise: Long,
    ) : GateView {
        override val recordsDodge: Boolean get() = false
    }
}

/**
 * What to flag on the saved transaction. Only a breach flags it — that flag is what grows the weed.
 *
 * `Neutral` deliberately reports PACE_WARNING rather than BREACH even when the verdict was a
 * breach: `PlantMapper` already exempts necessities via `!ownNecessity`, so the flag is inert
 * there either way, and this keeps `breachedAtLogging` meaning "this grew a weed" rather than
 * "this was over budget" — which is what the garden actually reads it for.
 */
/** The streak card's title: what this payment does, and nothing it doesn't. */
fun GateView.Streak.headline(): String = when {
    scopeLabel != null && alreadyAhead -> "This puts $scopeLabel further ahead of pace"
    scopeLabel != null -> "This puts $scopeLabel ahead of pace"
    days > 0 -> "This ends a $days-day streak"
    alreadyAhead -> "This puts you further ahead of pace"
    else -> "This puts you ahead of pace"
}

fun GateView.severityForLogging(): Severity = when (this) {
    is GateView.Weed -> Severity.BREACH
    is GateView.Streak -> Severity.PACE_WARNING
    is GateView.Neutral -> Severity.PACE_WARNING
    GateView.None -> Severity.OK
}

object GatePresentation {

    /**
     * Weeds are always variant 0 — `PlantMapper.kt:86` maps `isWeed -> 0` regardless of tier.
     */
    private const val WEED_VARIANT = 0

    /**
     * Which weed a transaction grows. Mirrors `PlantMapper.kt:74` exactly, so the preview cannot
     * promise a thistle and then grow a mushroom. Requires the UUID to exist before the gate is
     * answered, which is why the draft mints it.
     */
    fun weedFor(txnUuid: String): Archetype =
        if (abs(txnUuid.hashCode()) % 2 == 0) Archetype.THISTLE_WEED else Archetype.ODD_MUSHROOM

    @Suppress("LongParameterList")
    fun of(
        severity: Severity,
        isNecessity: Boolean,
        streakDays: Int,
        txnUuid: String,
        quip: String,
        scopeLabel: String?,
        spentPaise: Long,
        budgetPaise: Long,
        candidatePaise: Long,
        allowancePaise: Long,
        /** Today's line before this payment (GateVerdict.allowanceBeforePaise). */
        allowanceBeforePaise: Long,
    ): GateView {
        if (severity == Severity.OK) return GateView.None
        val after = spentPaise + candidatePaise

        // Necessity wins over severity. The garden already exempts these (PlantMapper gates both
        // the weed and the zombie on !ownNecessity), so the gate was threatening a punishment
        // that does not exist.
        if (isNecessity) {
            return GateView.Neutral(
                scopeLabel = scopeLabel ?: "Overall",
                afterPaise = after,
                budgetPaise = budgetPaise,
            )
        }

        return when (severity) {
            Severity.BREACH -> GateView.Weed(
                archetype = weedFor(txnUuid),
                variant = WEED_VARIANT,
                quip = quip,
                scopeLabel = scopeLabel,
                overPaise = after - budgetPaise,
            )
            Severity.PACE_WARNING -> {
                // Before this payment: a fixed-cost payment raises today's line, and spend already
                // over the old line had lost today's streak day without it.
                val alreadyAhead = spentPaise > allowanceBeforePaise
                GateView.Streak(
                    days = if (scopeLabel == null && !alreadyAhead) streakDays else 0,
                    quip = quip,
                    overPaise = after - allowancePaise,
                    allowancePaise = allowancePaise,
                    scopeLabel = scopeLabel,
                    alreadyAhead = alreadyAhead,
                )
            }
            Severity.OK -> GateView.None      // unreachable; the early return above covers it
        }
    }
}

package com.expensegarden.app.ai

/** Boundary check between the model's output and the `quip` table (spec §6, §7).
 *
 *  The prompt already states the boundaries; this is the belt to that braces. A rejected line
 *  is simply not inserted — the seeded STATIC bank is never deleted, so rejecting an entire
 *  response is a safe outcome rather than a failure to handle.
 *
 *  Keyword matching is crude and will have false positives ("rental car" trips the rent rule).
 *  That is the correct trade here: a false positive costs one unused line out of eight, a
 *  false negative puts a boundary violation in front of Rajdweep at the payment gate. */
object QuipSanitizer {
    private const val MAX_LEN = 120

    private val FORBIDDEN = listOf(
        // income digs
        "salary", "income", "afford", "you earn", "your wage", "paycheck", "paycheque",
        // comparisons to others
        "most people", "everyone else", "other people", "average person", "than others",
        // necessity shaming — parent spec §10: necessities are never mocked
        "rent", "groceries", "grocery", "medicine", "medical", "doctor", "hospital",
        "school fees", "electricity bill",
    )

    /** Cleans one candidate line. Returns null if it must not be inserted. */
    fun clean(raw: String): String? {
        val line = raw.trim()
            .removePrefix("-").removePrefix("*")
            .trim()
            .replace(NUMBER_PREFIX, "")
            .trim()
            .trim('"', '\'', '“', '”')
            .trim()

        if (line.isBlank()) return null
        if (line.length > MAX_LEN) return null
        if (line.contains('\n')) return null
        val lower = line.lowercase()
        if (FORBIDDEN_AT_WORD_START.any { it.containsMatchIn(lower) }) return null
        return line
    }

    /** Splits a multi-line model response and keeps only the lines that pass. */
    fun cleanAll(raw: String): List<String> =
        raw.lines().mapNotNull { clean(it) }.distinct()

    private val NUMBER_PREFIX = Regex("^\\d+[.)]\\s*")

    /** Anchored at a word START, not matched as a bare substring.
     *
     *  Plain `contains("rent")` fires on "cur-rent-", "diffe-rent-", "appa-rent-ly" and
     *  "inhe-rent-ly" — ordinary words a model writing budget copy reaches for constantly.
     *  That would reject most legitimate output while looking like it worked, since the gate
     *  silently falls back to the static bank.
     *
     *  The trailing end is deliberately left open so plurals and inflections still match:
     *  "doctors", "rents", "affording". "rental car" therefore still trips the rent rule —
     *  an accepted false positive, and the reason the refresher asks for 8 lines at a time. */
    private val FORBIDDEN_AT_WORD_START =
        FORBIDDEN.map { Regex("\\b" + Regex.escape(it)) }
}

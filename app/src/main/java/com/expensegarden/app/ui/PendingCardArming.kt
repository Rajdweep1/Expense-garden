package com.expensegarden.app.ui

/** When a pending card starts taking answers. Answering one pending payment puts the next in the
 *  same place a frame or two later, so the second tap of a double tap answered a payment the user
 *  never saw: a failed attempt and its retry read alike, and one double tap on Discard discarded
 *  the real payment too (final review, 2026-10-19). */
object PendingCardArming {
    /** Longer than Android's 300 ms double-tap timeout, shorter than anyone takes to read a card. */
    const val ARM_MS = 500L

    /** Both times from the same monotonic clock (SystemClock.uptimeMillis). */
    fun accepts(shownAtMs: Long, tapAtMs: Long): Boolean = tapAtMs - shownAtMs >= ARM_MS
}

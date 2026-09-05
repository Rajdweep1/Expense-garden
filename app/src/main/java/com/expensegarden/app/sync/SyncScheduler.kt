package com.expensegarden.app.sync

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** Coalesces change signals into at most one in-flight push (spec §4).
 *
 *  A CONFLATED channel is the whole design: logging a transaction fires several writes in
 *  quick succession, and each one signals. Conflation means the burst becomes one push
 *  carrying all of it, rather than four pushes racing each other.
 *
 *  Runs on its own scope rather than a viewModelScope, because a push must survive the
 *  screen that triggered it going away. Failures are silent and simply retried on the next
 *  signal — the same self-healing shape as 1D's job. The runCatching is not decoration: this
 *  collector is the outermost frame of a fire-and-forget coroutine, so an escaping throw
 *  would reach the default handler and take the app down. */
class SyncScheduler(private val repo: SyncRepository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val signals = Channel<Unit>(Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in signals) {
                runCatching { repo.pushOnce() }
            }
        }
    }

    /** Cheap and non-blocking: safe to call from anywhere, including right after a Room
     *  transaction commits. */
    fun signal() {
        signals.trySend(Unit)
    }
}

package com.expensegarden.app.sync

import java.io.IOException
import java.net.MalformedURLException
import java.net.UnknownServiceException

/** Why a backup request failed, by what he would have to fix (review R15, 2026-10-18). Every one of
 *  these used to render as the same nothing. */
enum class SyncFailure(val label: String) {
    UNAUTHORIZED("token rejected"),
    NOT_FOUND("nothing at that url"),
    SERVER("server error"),
    UNREACHABLE("can't reach the server"),
    CLEARTEXT_BLOCKED("http:// is blocked on this build — use https"),
    BAD_URL("that isn't a url");

    companion object {
        /** [code] when the server answered, else [error]. A DNS failure can't tell a typo'd host
         *  from no network, so it is UNREACHABLE. Order matters: the specific IOExceptions first. */
        fun classify(code: Int?, error: Exception?): SyncFailure = when {
            code == 401 || code == 403 -> UNAUTHORIZED
            code == 404 -> NOT_FOUND
            code != null -> SERVER
            error is UnknownServiceException -> CLEARTEXT_BLOCKED   // the release build's network config
            error is MalformedURLException || error is IllegalArgumentException -> BAD_URL
            error is IOException -> UNREACHABLE
            else -> SERVER   // an answer that wouldn't parse
        }
    }
}

/** A request's outcome, so callers can say what went wrong instead of getting null. */
sealed interface SyncResult<out T> {
    data class Ok<T>(val value: T) : SyncResult<T>
    data class Failed(val failure: SyncFailure, val code: Int?) : SyncResult<Nothing>
}

/** Settings' backup line. "Backup off" without a url and token: "never backed up" there read as a
 *  broken backup, not an absent one. A failure older than the last success is history. */
fun syncStatusLine(
    configured: Boolean,
    lastSuccessAt: Long,
    lastFailure: SyncFailure?,
    lastFailureAt: Long,
    pending: Int?,
    nowMillis: Long,
): String {
    if (!configured) return "Backup off"
    val waiting = pending?.let { if (it == 0) "" else " · $it waiting" } ?: ""
    val failed = if (lastFailure != null && lastFailureAt > lastSuccessAt)
        " · last try failed: ${lastFailure.label}, ${age(nowMillis - lastFailureAt)}" else ""
    if (lastSuccessAt == 0L) return "Never backed up$waiting$failed"
    val ageMs = nowMillis - lastSuccessAt
    val days = ageMs / 86_400_000
    val stale = if (days >= 1) "  ⚠ not backed up in ${days}d" else ""
    return "Backed up ${age(ageMs)}$waiting$stale$failed"
}

private fun age(ms: Long): String = when {
    ms < 60_000 -> "just now"
    ms < 3_600_000 -> "${ms / 60_000}m ago"
    else -> "${ms / 3_600_000}h ago"
}

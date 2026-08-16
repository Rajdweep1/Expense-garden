package com.expensegarden.app.data

import android.content.Context

/** Device-local VIEW state — deliberately not in Room (1C.7 spec §3).
 *
 *  The local-first invariant says Room is the source of truth *for the ledger*. "Has this
 *  device already played the homestead-expansion animation" is not ledger data: in Room it
 *  would sync to the Phase 2 backend, where it is meaningless and wrong on a second device.
 *  SharedPreferences is also framework, not a library, so this adds zero dependencies. */
class GardenPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("garden_view_state", Context.MODE_PRIVATE)

    /** 0 = never recorded. A fresh install adopts the current level silently rather than
     *  animating an expansion the user was never present for. */
    var lastSeenHouseLevel: Int
        get() = prefs.getInt(KEY_LAST_SEEN_HOUSE_LEVEL, 0)
        set(value) = prefs.edit().putInt(KEY_LAST_SEEN_HOUSE_LEVEL, value).apply()

    private companion object {
        const val KEY_LAST_SEEN_HOUSE_LEVEL = "lastSeenHouseLevel"
    }
}

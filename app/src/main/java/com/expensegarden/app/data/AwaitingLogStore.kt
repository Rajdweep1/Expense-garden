package com.expensegarden.app.data

import android.content.Context
import com.expensegarden.app.capture.AwaitingPayment

class AwaitingLogStore(context: Context) {
    fun all(): List<AwaitingPayment> = TODO()
    fun add(p: AwaitingPayment): Unit = TODO()
    fun remove(id: String): AwaitingPayment? = TODO()
    fun markShown(id: String): Unit = TODO()
    fun restore(p: AwaitingPayment): Unit = TODO()
}

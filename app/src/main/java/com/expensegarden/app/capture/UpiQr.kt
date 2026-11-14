package com.expensegarden.app.capture

enum class QrKind { MERCHANT, PERSON }

object UpiQr {
    fun kind(raw: String?): QrKind = TODO()
    fun fixedAmountPaise(raw: String): Long? = TODO()
    fun payableAmount(qrUri: String?, amountText: String): Long? = TODO()
    fun paymentUri(raw: String, amountPaise: Long): String = TODO()
}

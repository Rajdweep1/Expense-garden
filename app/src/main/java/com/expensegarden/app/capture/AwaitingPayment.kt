package com.expensegarden.app.capture

data class AwaitingPayment(
    val id: String,
    val vpa: String,
    val payeeName: String,
    val qrUri: String,
    val fixedAmountPaise: Long?,
    val scannedAt: Long,
    val handedOffAt: Long?,
    val estimatePaise: Long,
)

object AwaitingSelect {
    fun toOpen(list: List<AwaitingPayment>): AwaitingPayment? = TODO()
    fun homeCards(list: List<AwaitingPayment>, openId: String?): List<AwaitingPayment> = TODO()
}

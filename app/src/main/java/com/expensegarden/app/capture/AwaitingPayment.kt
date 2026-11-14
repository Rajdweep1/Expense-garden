package com.expensegarden.app.capture

/** A scanned payment handed to a UPI app and not logged yet (pay-first spec). [id] becomes the
 *  transaction's uuid, so logging it twice writes one row. */
data class AwaitingPayment(
    val id: String,
    val vpa: String,
    val payeeName: String,
    val qrUri: String,
    val fixedAmountPaise: Long?,
    val scannedAt: Long,
    val handedOffAt: Long?,
)

/** Which awaiting payment does what. Pure, so the choices are JVM-tested. */
object AwaitingSelect {
    /** The payment to open the Log it form for on return: the oldest just handed off (spec D8). */
    fun toOpen(list: List<AwaitingPayment>): AwaitingPayment? =
        list.filter { it.handedOffAt != null }.minByOrNull { it.scannedAt }

    /** The home cards: every awaiting payment, oldest first. */
    fun homeCards(list: List<AwaitingPayment>): List<AwaitingPayment> = list.sortedBy { it.scannedAt }
}

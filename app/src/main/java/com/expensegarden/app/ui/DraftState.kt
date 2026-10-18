package com.expensegarden.app.ui

import androidx.lifecycle.SavedStateHandle

/** EntryDraft in and out of a SavedStateHandle, one key per field. Every field is a type a Bundle
 *  carries, so this needs no Parcelable and no new Gradle plugin. */
object DraftState {
    private const val PRESENT = "draft.present"
    private const val FROM_SCAN = "draft.fromScan"
    private const val VPA = "draft.vpa"
    private const val PAYEE = "draft.payeeName"
    private const val AMOUNT = "draft.amountText"
    private const val CATEGORY = "draft.categoryId"
    private const val NOTE = "draft.note"
    private const val OCCURRED_AT = "draft.occurredAt"
    private const val TXN_UUID = "draft.txnUuid"

    fun save(handle: SavedStateHandle, draft: EntryDraft) {
        handle[FROM_SCAN] = draft.fromScan
        handle[VPA] = draft.vpa
        handle[PAYEE] = draft.payeeName
        handle[AMOUNT] = draft.amountText
        handle[CATEGORY] = draft.categoryId
        handle[NOTE] = draft.note
        handle[OCCURRED_AT] = draft.occurredAt
        handle[TXN_UUID] = draft.txnUuid
        handle[PRESENT] = true
    }

    /** Null when nothing was ever saved, so a first launch starts from an empty draft. */
    fun restore(handle: SavedStateHandle): EntryDraft? {
        if (handle.get<Boolean>(PRESENT) != true) return null
        return EntryDraft(
            fromScan = handle.get<Boolean>(FROM_SCAN) ?: false,
            vpa = handle.get<String>(VPA),
            payeeName = handle.get<String>(PAYEE) ?: "",
            amountText = handle.get<String>(AMOUNT) ?: "",
            categoryId = handle.get<Long>(CATEGORY),
            note = handle.get<String>(NOTE) ?: "",
            occurredAt = handle.get<Long>(OCCURRED_AT) ?: System.currentTimeMillis(),
            txnUuid = handle.get<String>(TXN_UUID),
        )
    }
}

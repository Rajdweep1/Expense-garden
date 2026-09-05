package com.expensegarden.app.sync

import com.expensegarden.app.data.BudgetEntity
import com.expensegarden.app.data.CategoryEntity
import com.expensegarden.app.data.GameEventEntity
import com.expensegarden.app.data.PayeeEntity
import com.expensegarden.app.data.Regret
import com.expensegarden.app.data.TransactionEntity
import com.expensegarden.app.data.TxnSource
import com.expensegarden.app.data.TxnStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/** The only file in the sync layer that touches the network (spec §4).
 *
 *  Same shape as ai/GeminiClient.kt, for the same reasons: HttpURLConnection and org.json are
 *  framework, and the dependency matrix is pinned. The withContext(Dispatchers.IO) is the
 *  outermost construct of every method rather than something call sites remember, because the
 *  scheduler may run on a Main-confined scope, where HttpURLConnection throws
 *  NetworkOnMainThreadException.
 *
 *  Logs failures, but never the token: it travels in a header, so it cannot appear in an
 *  exception's class or message. See logFailure. */
class SyncClient(private val prefs: SyncPrefs) {

    /** True when the server accepted the whole batch. */
    suspend fun push(batch: SyncBatch): Boolean = withContext(Dispatchers.IO) {
        val body = JSONObject()
            .put("categories", JSONArray(batch.categories.map(::categoryJson)))
            .put("payees", JSONArray(batch.payees.map(::payeeJson)))
            .put("txns", JSONArray(batch.txns.map(::txnJson)))
            .put("budgets", JSONArray(batch.budgets.map(::budgetJson)))
            .put(
                "tombstones",
                JSONArray(
                    batch.tombstones.map {
                        JSONObject().put("tableName", it.tableName)
                            .put("rowKey", it.rowKey).put("deletedAt", it.deletedAt)
                    }
                )
            )
            .put("events", JSONArray(batch.events.map(::eventJson)))
        post("/v1/sync/push", body) != null
    }

    suspend fun snapshot(): SyncSnapshot? = withContext(Dispatchers.IO) {
        val body = get("/v1/sync/snapshot") ?: return@withContext null
        runCatching {
            SyncSnapshot(
                categories = body.getJSONArray("categories").mapObjects(::readCategory),
                payees = body.getJSONArray("payees").mapObjects(::readPayee),
                txns = body.getJSONArray("txns").mapObjects(::readTxn),
                budgets = body.getJSONArray("budgets").mapObjects(::readBudget),
                events = body.getJSONArray("events").mapObjects(::readEvent),
            )
        }.getOrNull()
    }

    // ---------- transport ----------

    private fun open(path: String, method: String): HttpURLConnection =
        (URL(prefs.serverUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Authorization", "Bearer ${prefs.token}")
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
        }

    private fun post(path: String, body: JSONObject): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(path, "POST").apply { doOutput = true }
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } catch (e: Exception) {
            logFailure("push", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun get(path: String): JSONObject? {
        var conn: HttpURLConnection? = null
        return try {
            conn = open(path, "GET")
            if (conn.responseCode !in 200..299) return null
            JSONObject(conn.inputStream.bufferedReader().use(BufferedReader::readText))
        } catch (e: Exception) {
            logFailure("snapshot", e)
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Deliberate, and a considered departure from ai/GeminiClient, which logs nothing.
     *
     *  A quiet persona costs nothing; a quiet BACKUP costs everything precisely when you need
     *  it, and every failure mode here — wrong url, dead server, blocked cleartext, bad token —
     *  renders on screen as the same nothing. Exactly one line, carrying the exception class
     *  and message only: the bearer token travels in a header and is never part of either, so
     *  nothing secret can reach logcat through here. */
    private fun logFailure(verb: String, e: Exception) {
        android.util.Log.w("SyncClient", "$verb failed: ${e.javaClass.simpleName}: ${e.message}")
    }

    // ---------- writers ----------

    private fun categoryJson(c: CategoryEntity) = JSONObject()
        .put("id", c.id).put("name", c.name)
        .put("parentId", c.parentId ?: JSONObject.NULL)
        .put("isNecessity", c.isNecessity).put("updatedAt", c.updatedAt)

    private fun payeeJson(p: PayeeEntity) = JSONObject()
        .put("id", p.id).put("name", p.name)
        .put("vpa", p.vpa ?: JSONObject.NULL)
        .put("defaultCategoryId", p.defaultCategoryId ?: JSONObject.NULL)
        .put("updatedAt", p.updatedAt)

    private fun txnJson(t: TransactionEntity) = JSONObject()
        .put("uuid", t.uuid).put("amountPaise", t.amountPaise)
        .put("payeeId", t.payeeId).put("categoryId", t.categoryId)
        .put("source", t.source.name).put("status", t.status.name).put("regret", t.regret.name)
        .put("breachedAtLogging", t.breachedAtLogging)
        .put("note", t.note ?: JSONObject.NULL)
        .put("occurredAt", t.occurredAt).put("createdAt", t.createdAt).put("updatedAt", t.updatedAt)

    private fun budgetJson(b: BudgetEntity) = JSONObject()
        .put("categoryId", b.categoryId ?: JSONObject.NULL)
        .put("month", b.month).put("amountPaise", b.amountPaise).put("updatedAt", b.updatedAt)

    private fun eventJson(e: GameEventEntity) = JSONObject()
        .put("id", e.id).put("type", e.type).put("payloadJson", e.payloadJson)
        .put("transactionUuid", e.transactionUuid ?: JSONObject.NULL)
        .put("createdAt", e.createdAt)

    // ---------- readers ----------

    private fun readCategory(o: JSONObject) = CategoryEntity(
        id = o.getLong("id"), name = o.getString("name"),
        parentId = o.optLongOrNull("parentId"), isNecessity = o.getBoolean("isNecessity"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readPayee(o: JSONObject) = PayeeEntity(
        id = o.getLong("id"), name = o.getString("name"),
        vpa = o.optStringOrNull("vpa"), defaultCategoryId = o.optLongOrNull("defaultCategoryId"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readTxn(o: JSONObject) = TransactionEntity(
        uuid = o.getString("uuid"), amountPaise = o.getLong("amountPaise"),
        payeeId = o.getLong("payeeId"), categoryId = o.getLong("categoryId"),
        source = TxnSource.valueOf(o.getString("source")),
        status = TxnStatus.valueOf(o.getString("status")),
        regret = Regret.valueOf(o.getString("regret")),
        breachedAtLogging = o.getBoolean("breachedAtLogging"),
        note = o.optStringOrNull("note"),
        occurredAt = o.getLong("occurredAt"), createdAt = o.getLong("createdAt"),
        updatedAt = o.getLong("updatedAt"),
    )

    private fun readBudget(o: JSONObject) = BudgetEntity(
        categoryId = o.optLongOrNull("categoryId"), month = o.getString("month"),
        amountPaise = o.getLong("amountPaise"), updatedAt = o.getLong("updatedAt"),
    )

    private fun readEvent(o: JSONObject) = GameEventEntity(
        id = o.getLong("id"), type = o.getString("type"), payloadJson = o.getString("payloadJson"),
        transactionUuid = o.optStringOrNull("transactionUuid"), createdAt = o.getLong("createdAt"),
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

private fun <T> JSONArray.mapObjects(read: (JSONObject) -> T): List<T> =
    (0 until length()).map { read(getJSONObject(it)) }

/** org.json returns the STRING "null" from optString for a JSON null, which would sail
 *  straight into a non-null Kotlin field. These two helpers are the only safe readers. */
private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else getString(key)

private fun JSONObject.optLongOrNull(key: String): Long? =
    if (isNull(key)) null else getLong(key)

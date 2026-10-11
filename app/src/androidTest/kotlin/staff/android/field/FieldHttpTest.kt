package staff.android.field

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith
import staff.android.field.core.*

/** Actual bounded HTTP with independently named databases; does not enqueue WorkManager. */
@RunWith(AndroidJUnit4::class)
class FieldHttpTest {
    @get:Rule val testName = TestName()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: FieldStore
    private lateinit var api: FieldApi
    private val records = Collections.synchronizedList(mutableListOf<FieldTransport>())
    private val photo = byteArrayOf(1, 3, 5, 7)
    private val op = "a".repeat(32)
    private fun admin(path: String, body: JSONObject? = null): JSONObject {
        val connection = URL("http://127.0.0.1:8890/admin/$path").openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = 5000; connection.readTimeout = 5000
            if (body != null) {
                connection.requestMethod = "POST"; connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            check(connection.responseCode == 200)
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally { connection.disconnect() }
    }
    private fun config(vararg fields: Pair<String, Any>) = admin("config", JSONObject().apply { fields.forEach { put(it.first, it.second) } })
    private fun save() = store.save(op, Condition.Damaged, "Crack beside the lens", photo)
    @Before fun create() {
        admin("reset", JSONObject()); records.clear()
        name = "field-http-${UUID.randomUUID()}.db"; store = FieldStore(context, name)
        api = FieldApi(record = { records.add(it) })
    }
    @After fun retainAndClose() {
        try {
            val remote = admin("state")
            val local = JSONArray(store.history().map { row -> JSONObject().apply {
                put("operationId", row.payload.operationId); put("baseRevision", row.payload.baseRevision)
                put("stage", row.stage.name); put("photoRetained", row.photoRetained)
                put("receiptRevision", row.receipt?.revision ?: JSONObject.NULL)
                put("conflictRevision", row.current?.revision ?: JSONObject.NULL)
            } })
            val copy = synchronized(records) { records.toList() }
            val report = JSONObject().put("case", testName.methodName).put("local", local)
                .put("localPhotoBytes", store.photoBytes()).put("remoteEvents", remote.getJSONArray("events"))
                .put("remotePhotos", remote.getInt("photoCount")).put("remoteReports", remote.getInt("reportCount"))
                .put("remoteRevision", remote.getJSONObject("current").getInt("revision"))
                .put("transport", JSONArray(copy.map { row -> JSONObject().apply {
                    put("route", row.route); put("kind", row.kind); put("status", row.status); put("bytes", row.bytes)
                } }))
            File(context.filesDir, "field-http-${testName.methodName}.json").writeText(report.toString(2))
        } finally { store.close(); context.deleteDatabase(name) }
    }
    @Test fun acceptedReportAndOwnedPhotoSurviveNewStoreInstance() = runBlocking<Unit> {
        val saved = save()
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
        store.close(); store = FieldStore(context, name)
        assertEquals(Stage.Submitted, store.load(op)!!.stage)
        assertEquals(ReportReceipt(saved.payload, 8), store.load(op)!!.receipt)
        assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
        assertEquals(1, admin("state").getInt("reportCount"))
    }
    @Test fun conflictPreservesEvidenceAndExplicitResolutionCanConflictAgain() = runBlocking<Unit> {
        val saved = save(); admin("advance", JSONObject())
        assertEquals(SyncResult.NeedsReview, InspectionSync(store, api).run(op))
        assertEquals(saved.payload, store.load(op)!!.payload)
        assertEquals(8, store.load(op)!!.current!!.revision)
        assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
        val next = store.resolve(op, 8, true, "b".repeat(32))
        admin("advance", JSONObject())
        assertEquals(SyncResult.NeedsReview, InspectionSync(store, api).run(next.payload.operationId))
        assertEquals(9, store.load(next.payload.operationId)!!.current!!.revision)
        val final = store.resolve(next.payload.operationId, 9, true, "c".repeat(32))
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(final.payload.operationId))
        assertEquals(10, store.load(final.payload.operationId)!!.receipt!!.revision)
        assertEquals(1, admin("state").getInt("photoCount"))
        assertEquals(1, admin("state").getInt("reportCount"))
    }
    @Test fun lostUploadReplyHasOneLogicalPhotoAndOnlyReportReceiptCompletes() = runBlocking<Unit> {
        save(); config("dropUploadOnce" to true)
        val first = InspectionSync(store, api).run(op)
        if (first == SyncResult.Retry) assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
        else assertEquals(SyncResult.Finished, first)
        assertEquals(Stage.Submitted, store.load(op)!!.stage)
        val snapshot = admin("state")
        assertEquals(1, snapshot.getInt("photoCount")); assertEquals(1, snapshot.getInt("reportCount"))
        assertTrue(snapshot.getJSONArray("events").toString().contains("reply_lost"))
    }
    @Test fun lostReportReplyCanBeReconciledByStableOperationLookup() = runBlocking<Unit> {
        val saved = save(); config("dropReportOnce" to true)
        val first = InspectionSync(store, api).run(op)
        if (first == SyncResult.Retry) {
            assertEquals(Stage.Uploaded, store.load(op)!!.stage)
            assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
        } else assertEquals(SyncResult.Finished, first)
        assertEquals(ReportReceipt(saved.payload, 8), store.load(op)!!.receipt)
        val snapshot = admin("state")
        assertEquals(1, snapshot.getInt("reportCount")); assertEquals(8, snapshot.getJSONObject("current").getInt("revision"))
        assertTrue(snapshot.getJSONArray("events").toString().contains("reply_lost"))
    }
    @Test fun reportUnavailableLeavesUploadedIntentForLaterReconciliation() = runBlocking<Unit> {
        val saved = save(); config("reportHttp" to 503)
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(op))
        assertEquals(Stage.Uploaded, store.load(op)!!.stage)
        assertNull(store.load(op)!!.receipt); assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
        config("reportHttp" to 200)
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
        assertEquals(1, admin("state").getInt("photoCount"))
    }
    @Test fun incompatibleReplySchemaPreservesIntentAndRecoveryUsesSameIdentity() = runBlocking<Unit> {
        save(); config("schema" to 2)
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(op))
        assertEquals(Stage.Queued, store.load(op)!!.stage)
        assertNull(store.load(op)!!.receipt)
        config("schema" to 1)
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
        assertEquals(op, store.load(op)!!.receipt!!.payload.operationId)
    }
    @Test fun cancellingDelayedLookupDoesNotDeleteIntentOrClaimServerStopped() = runBlocking<Unit> {
        val saved = save(); config("delayMs" to 1000)
        val job = launch(Dispatchers.IO) { InspectionSync(store, api).run(op) }
        withTimeout(5000) {
            while (synchronized(records) { records.none { it.route == "report_lookup" && it.kind == "enqueued" } }) delay(10)
        }
        job.cancelAndJoin()
        assertTrue(job.isCancelled); assertEquals(Stage.Queued, store.load(op)!!.stage)
        assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
        // Allow the fixture's already admitted handler to complete; cancellation is not server rollback.
        delay(1100); config("delayMs" to 0)
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(op))
    }
    @Test fun observerFailureCannotConvertAcceptedOperationIntoProductFailure() = runBlocking<Unit> {
        save(); val noisy = FieldApi(record = { throw IOException("Diagnostic sink failed") })
        assertEquals(SyncResult.Finished, InspectionSync(store, noisy).run(op))
        assertEquals(Stage.Submitted, store.load(op)!!.stage)
        assertEquals(1, admin("state").getInt("reportCount"))
    }
}

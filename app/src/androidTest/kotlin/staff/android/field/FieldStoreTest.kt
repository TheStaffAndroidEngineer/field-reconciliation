package staff.android.field

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import staff.android.field.core.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FieldStoreTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var name: String
    private lateinit var store: FieldStore
    private val photo = byteArrayOf(1, 3, 5, 7)
    private fun id(n: Int) = n.toString(16).padStart(32, '0')
    @Before fun create() { name = "field-test-${UUID.randomUUID()}.db"; store = FieldStore(context, name) }
    @After fun close() { store.close(); context.deleteDatabase(name) }
    private fun save(n: Int = 1, bytes: ByteArray = photo) = store.save(id(n), Condition.Damaged, "Crack beside the lens", bytes)
    private fun accept(row: SavedInspection) = store.accepted(row.payload.operationId, ReportReceipt(row.payload, row.payload.baseRevision + 1))

    @Test fun packagedSampleBecomesBoundedDecodableJpegBeforeTheTransaction() {
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.assets.open("camera.png").use { android.graphics.BitmapFactory.decodeStream(it, null, bounds) }
        val bytes = prepareSamplePhoto(context)
        assertTrue(bytes.size in 1..MAX_PHOTO_BYTES)
        assertEquals(0xff, bytes[0].toInt() and 0xff); assertEquals(0xd8, bytes[1].toInt() and 0xff)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        assertNotNull(decoded)
        try {
            assertTrue(decoded.width > 0 && decoded.height > 0)
            assertTrue(kotlin.math.abs(decoded.width * 2 - bounds.outWidth) < 2)
            assertTrue(kotlin.math.abs(decoded.height * 2 - bounds.outHeight) < 2)
            val saved = save(bytes = bytes)
            val snapshot = store.snapshot()
            assertEquals(saved, snapshot.inspection)
            assertArrayEquals(bytes, snapshot.photo)
            assertEquals(saved.payload.baseRevision, snapshot.assessment.revision)
            val observation = org.json.JSONObject().apply {
                put("case", "packaged-sample-encoding"); put("sourceWidth", bounds.outWidth)
                put("sourceHeight", bounds.outHeight); put("jpegWidth", decoded.width)
                put("jpegHeight", decoded.height); put("jpegBytes", bytes.size)
                put("maxBytes", MAX_PHOTO_BYTES); put("sha256", digestOf(bytes))
                put("databaseBytesMatch", snapshot.photo!!.contentEquals(bytes))
            }
            java.io.File(context.filesDir, "field-sample-encoding.json").writeText(observation.toString(2))
        } finally { decoded.recycle() }
    }
    @Test fun blobAndImmutablePendingIntentSurviveClosingAndReopening() {
        val saved = save(); val expected = saved.payload
        store.close(); store = FieldStore(context, name)
        assertEquals(expected, store.latest()!!.payload)
        assertEquals(Stage.Queued, store.latest()!!.stage)
        assertEquals(listOf(id(1)), store.pendingIds())
        assertArrayEquals(photo, store.photo(expected.photoDigest))
        assertEquals(7, store.assessment().revision)
        assertEquals(photo.size.toLong(), store.photoBytes())
    }
    @Test fun callersCannotMutateOwnedPhotoThroughInputOrReadbackArray() {
        val input = photo.copyOf(); val saved = save(bytes = input)
        input[0] = 99
        val readback = store.photo(saved.payload.photoDigest)!!; readback[0] = 88
        assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
    }
    @Test fun failedDuplicateIdentityRollsBackNewBlobAndReportTogether() {
        val first = save(); accept(first)
        try { save(bytes = byteArrayOf(90, 91)); fail("Duplicate identity must fail") }
        catch (_: SQLiteConstraintException) { }
        assertEquals(1, store.history().size)
        assertEquals(photo.size.toLong(), store.photoBytes())
        assertNull(store.photo(digestOf(byteArrayOf(90, 91))))
        assertEquals(Stage.Submitted, store.latest()!!.stage)
    }
    @Test fun uniquePhotoQuotaIsInclusiveAndFailureDoesNotCreateIntent() {
        for (n in 1..4) {
            val saved = save(n, ByteArray(MAX_PHOTO_BYTES) { n.toByte() }); accept(saved)
        }
        assertEquals(MAX_LOCAL_PHOTO_BYTES.toLong(), store.photoBytes())
        try { save(5, byteArrayOf(9)); fail("Quota must reject new byte") } catch (_: IllegalArgumentException) { }
        assertEquals(4, store.history().size); assertNull(store.load(id(5)))
        // Existing identical bytes cost no new logical-photo bytes.
        val reused = save(6, ByteArray(MAX_PHOTO_BYTES) { 1 }); accept(reused)
        assertEquals(MAX_LOCAL_PHOTO_BYTES.toLong(), store.photoBytes())
        assertTrue(store.releasePhoto(id(1)))
        assertEquals(MAX_LOCAL_PHOTO_BYTES.toLong(), store.photoBytes())
        assertTrue(store.releasePhoto(id(6)))
        assertEquals((MAX_LOCAL_PHOTO_BYTES - MAX_PHOTO_BYTES).toLong(), store.photoBytes())
        assertEquals(Stage.Queued, save(7, byteArrayOf(9)).stage)
    }
    @Test fun photoReceiptNeverCompletesOrReleasesThePendingReport() {
        val saved = save(); store.uploaded(id(1))
        assertEquals(Stage.Uploaded, store.load(id(1))!!.stage)
        assertNull(store.load(id(1))!!.receipt)
        assertEquals(listOf(id(1)), store.pendingIds())
        assertFalse(store.releasePhoto(id(1)))
        assertArrayEquals(photo, store.photo(saved.payload.photoDigest))
        try { save(2); fail("Unresolved inspection must be retained") } catch (_: IllegalStateException) { }
    }
    @Test fun conflictPreservesLocalAndRemoteThenResolutionCreatesNewImmutableIntent() {
        val saved = save(); val remote = Assessment("CG-204", 8, Condition.Good, "Rechecked at the depot")
        store.conflicted(id(1), remote)
        val conflict = store.load(id(1))!!
        assertEquals(saved.payload, conflict.payload); assertEquals(remote, conflict.current)
        assertEquals(Stage.NeedsReview, conflict.stage); assertEquals(emptyList<String>(), store.pendingIds())
        assertFalse(store.releasePhoto(id(1)))
        val next = store.resolve(id(1), 8, true, id(2))
        assertEquals(Stage.Superseded, store.load(id(1))!!.stage)
        assertEquals(saved.payload.copy(operationId = id(2), baseRevision = 8), next.payload)
        assertEquals(Stage.Queued, next.stage); assertEquals(listOf(id(2)), store.pendingIds())
        assertEquals(photo.size.toLong(), store.photoBytes())
        assertTrue(store.releasePhoto(id(1)))
        assertArrayEquals(photo, store.photo(next.payload.photoDigest))
        accept(next); assertEquals(9, store.assessment().revision)
        assertTrue(store.releasePhoto(id(2))); assertEquals(0L, store.photoBytes())
    }
    @Test fun resolutionRequiresTheExactReviewedRevisionAndRollsBackOnBadNewIdentity() {
        save(); store.conflicted(id(1), Assessment("CG-204", 8, Condition.Good, "Remote"))
        for ((revision, newId) in listOf(9 to id(2), 8 to "invalid", 8 to id(1))) {
            try { store.resolve(id(1), revision, true, newId); fail("Invalid resolution must fail") }
            catch (_: IllegalArgumentException) { }
            catch (_: SQLiteConstraintException) { }
            assertEquals(Stage.NeedsReview, store.load(id(1))!!.stage)
            assertEquals(1, store.history().size); assertEquals(photo.size.toLong(), store.photoBytes())
        }
    }
    @Test fun useCurrentAssessmentDoesNotInventSubmittedReceiptOrNewIntent() {
        save(); val current = Assessment("CG-204", 8, Condition.Good, "Remote")
        store.conflicted(id(1), current)
        val result = store.resolve(id(1), 8, false, id(2))
        assertEquals(Stage.Superseded, result.stage); assertNull(result.receipt)
        assertNull(store.load(id(2))); assertEquals(current, store.assessment())
        assertEquals(emptyList<String>(), store.pendingIds())
        assertArrayEquals(photo, store.photo(result.payload.photoDigest))
    }
    @Test fun lateOldReceiptCannotOverwriteResolvedCurrentAssessment() {
        val first = save(); val remote = Assessment("CG-204", 9, Condition.Good, "Remote")
        store.conflicted(id(1), remote); store.resolve(id(1), 9, false, id(2))
        store.accepted(id(1), ReportReceipt(first.payload, 8))
        store.uploaded(id(1)); store.conflicted(id(1), Assessment("CG-204", 10, Condition.Damaged, "Later"))
        assertEquals(Stage.Superseded, store.load(id(1))!!.stage)
        assertNull(store.load(id(1))!!.receipt); assertEquals(remote, store.assessment())
    }
    @Test fun receiptMustMatchExactSavedPayloadAndAcceptedRowRetainsEvidence() {
        val first = save()
        try { store.accepted(id(1), ReportReceipt(first.payload.copy(note = "Other"), 8)); fail("Mismatched receipt") }
        catch (_: IllegalArgumentException) { }
        assertEquals(Stage.Queued, store.load(id(1))!!.stage)
        accept(first)
        assertEquals(Stage.Submitted, store.load(id(1))!!.stage)
        assertEquals(ReportReceipt(first.payload, 8), store.load(id(1))!!.receipt)
        assertTrue(store.load(id(1))!!.photoRetained)
        assertArrayEquals(photo, store.photo(first.payload.photoDigest))
    }
    @Test fun foreignKeyAndReportBoundPreventDanglingStorageGrowth() {
        store.writableDatabase.rawQuery("PRAGMA foreign_keys", null).use { assertTrue(it.moveToFirst()); assertEquals(1, it.getInt(0)) }
        for (n in 1..100) { val saved = save(n); accept(saved) }
        try { save(101, byteArrayOf(77)); fail("Report bound") } catch (_: IllegalArgumentException) { }
        assertEquals(100, store.history().size); assertEquals(photo.size.toLong(), store.photoBytes())
        store.readableDatabase.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
    }
}

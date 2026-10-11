package staff.android.field.core

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class InspectionTest {
    private val photo = byteArrayOf(1, 3, 5, 7)
    private fun payload(bytes: ByteArray = photo) = InspectionPayload(
        "a".repeat(32), "CG-204", 7, Condition.Damaged,
        "Crack beside the lens", digestOf(bytes),
    )
    private fun receipt(p: InspectionPayload = payload()) = ReportReceipt(p, 8)
    private class Store(var row: SavedInspection?, var bytes: ByteArray?) : InspectionStore {
        val writes = mutableListOf<String>()
        var reads = 0
        override fun load(operationId: String) = row?.takeIf { it.payload.operationId == operationId }
        override fun photo(digest: String): ByteArray? { reads++; return bytes?.copyOf() }
        override fun uploaded(operationId: String) {
            writes += "uploaded"
            row = row!!.copy(stage = Stage.Uploaded)
        }
        override fun accepted(operationId: String, receipt: ReportReceipt) {
            writes += "accepted"
            row = row!!.copy(stage = Stage.Submitted, receipt = receipt)
        }
        override fun conflicted(operationId: String, current: Assessment) {
            writes += "conflicted"
            row = row!!.copy(stage = Stage.NeedsReview, current = current)
        }
    }
    private class Api : InspectionService {
        val calls = mutableListOf<String>()
        var onLookup: suspend (String) -> ReportReceipt? = { null }
        var onFind: suspend (String) -> BlobReceipt? = { null }
        var onUpload: suspend (String, ByteArray) -> BlobReceipt = { digest, bytes -> BlobReceipt(digest, bytes.size) }
        var onCommit: suspend (InspectionPayload) -> CommitReply = { CommitReply.Accepted(ReportReceipt(it, it.baseRevision + 1)) }
        override suspend fun lookup(operationId: String): ReportReceipt? { calls += "lookup"; return onLookup(operationId) }
        override suspend fun findPhoto(digest: String): BlobReceipt? { calls += "find"; return onFind(digest) }
        override suspend fun uploadPhoto(digest: String, bytes: ByteArray): BlobReceipt { calls += "upload"; return onUpload(digest, bytes) }
        override suspend fun commit(payload: InspectionPayload): CommitReply { calls += "commit"; return onCommit(payload) }
    }
    private fun store(p: InspectionPayload = payload(), stage: Stage = Stage.Queued) =
        Store(SavedInspection(p, stage), photo.copyOf())

    @Test fun digestIsExactLowercaseSha256RatherThanFileName() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", digestOf("abc".toByteArray()))
        assertNotEquals(digestOf(byteArrayOf(1, 2)), digestOf(byteArrayOf(2, 1)))
        assertTrue(validDigest(digestOf(photo)))
    }
    @Test fun payloadRejectsWrongIdentityScopeRevisionAndControlText() {
        val p = payload()
        listOf("", "a".repeat(31), "A".repeat(32), "g".repeat(32)).forEach {
            assertThrows<IllegalArgumentException> { p.copy(operationId = it) }
        }
        assertThrows<IllegalArgumentException> { p.copy(visitId = "CG-205") }
        listOf(0, 1_000_001).forEach { assertThrows<IllegalArgumentException> { p.copy(baseRevision = it) } }
        assertThrows<IllegalArgumentException> { p.copy(photoDigest = "A".repeat(64)) }
        assertThrows<IllegalArgumentException> { p.copy(note = "x".repeat(161)) }
        assertThrows<IllegalArgumentException> { p.copy(note = "line\nbreak") }
        assertThrows<IllegalArgumentException> { p.copy(note = "hidden\u0000byte") }
        assertEquals("", p.copy(note = "").note)
        assertEquals(160, p.copy(note = "x".repeat(160)).note.length)
    }
    @Test fun noteLimitCountsUtf16UnitsAndRejectsUnpairedSurrogates() {
        val p = payload()
        val emoji = "\uD83D\uDE00"
        assertEquals(160, p.copy(note = emoji.repeat(80)).note.length)
        for (bad in listOf(emoji.repeat(81), "\uD800", "\uDFFF", "hidden\u0085control")) {
            assertThrows<IllegalArgumentException> { p.copy(note = bad) }
        }
    }
    @Test fun assessmentAndReceiptsEnforceTheirOwnBoundaries() {
        assertThrows<IllegalArgumentException> { Assessment("CG-999", 8, Condition.Good, "ok") }
        assertThrows<IllegalArgumentException> { Assessment("CG-204", 0, Condition.Good, "ok") }
        assertThrows<IllegalArgumentException> { Assessment("CG-204", 8, Condition.Good, "bad\ttext") }
        assertThrows<IllegalArgumentException> { BlobReceipt("x".repeat(64), 1) }
        listOf(0, MAX_PHOTO_BYTES + 1).forEach { assertThrows<IllegalArgumentException> { BlobReceipt(payload().photoDigest, it) } }
        listOf(7, 6, 1_000_001).forEach { assertThrows<IllegalArgumentException> { ReportReceipt(payload(), it) } }
        assertEquals(8, receipt().revision)
    }
    @Test fun missingAndTerminalOperationsDoNotCreateNetworkWork() = runTest {
        val api = Api()
        assertEquals(SyncResult.Finished, InspectionSync(Store(null, null), api).run("a".repeat(32)))
        for (stage in listOf(Stage.NeedsReview, Stage.Submitted, Stage.Superseded)) {
            val store = store(stage = stage)
            assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
            assertTrue(store.writes.isEmpty())
        }
        assertTrue(api.calls.isEmpty())
    }
    @Test fun knownCommitRecoversWithoutReadingOrReuploadingPhoto() = runTest {
        val store = Store(SavedInspection(payload(), Stage.Uploaded), null)
        val api = Api().apply { onLookup = { receipt() } }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup"), api.calls)
        assertEquals(0, store.reads)
        assertEquals(listOf("accepted"), store.writes)
        assertEquals(receipt(), store.row!!.receipt)
    }
    @Test fun mismatchedLookupReceiptCannotCompleteTheSavedIntent() = runTest {
        val store = store()
        val api = Api().apply { onLookup = { receipt(payload().copy(note = "different report")) } }
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup"), api.calls)
        assertTrue(store.writes.isEmpty())
        assertEquals(Stage.Queued, store.row!!.stage)
    }
    @Test fun newPhotoThenAuthoritativeReportAreSeparateOrderedBoundaries() = runTest {
        val store = store(); val api = Api()
        api.onCommit = { p ->
            assertEquals(Stage.Uploaded, store.row!!.stage)
            assertNull(store.row!!.receipt)
            CommitReply.Accepted(receipt(p))
        }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup", "find", "upload", "commit"), api.calls)
        assertEquals(listOf("uploaded", "accepted"), store.writes)
        assertEquals(Stage.Submitted, store.row!!.stage)
    }
    @Test fun existingRemotePhotoAvoidsUploadButStillRequiresReportCommit() = runTest {
        val store = store(); val api = Api().apply { onFind = { BlobReceipt(it, photo.size) } }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup", "find", "commit"), api.calls)
        assertEquals(Stage.Submitted, store.row!!.stage)
    }
    @Test fun missingEmptyOversizedAndChangedOwnedBytesNeverReachUpload() = runTest {
        for (bytes in listOf(null, byteArrayOf(), ByteArray(MAX_PHOTO_BYTES + 1), byteArrayOf(99))) {
            val store = store().apply { this.bytes = bytes }; val api = Api()
            assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
            assertEquals(listOf("lookup"), api.calls)
            assertTrue(store.writes.isEmpty())
        }
    }
    @Test fun maximumBoundedPhotoCanBeAcceptedExactly() = runTest {
        val bytes = ByteArray(MAX_PHOTO_BYTES) { (it % 251).toByte() }; val p = payload(bytes)
        val store = Store(SavedInspection(p, Stage.Queued), bytes); val api = Api()
        api.onUpload = { digest, sent ->
            assertEquals(MAX_PHOTO_BYTES, sent.size); assertArrayEquals(bytes, sent)
            BlobReceipt(digest, sent.size)
        }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(p.operationId))
    }
    @Test fun uploadedAndFoundPhotoReceiptsMustMatchDigestAndSize() = runTest {
        for (found in listOf(true, false)) for (wrong in listOf(BlobReceipt("b".repeat(64), photo.size), BlobReceipt(payload().photoDigest, photo.size + 1))) {
            val store = store(); val api = Api()
            if (found) api.onFind = { wrong } else api.onUpload = { _, _ -> wrong }
            assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
            assertFalse("commit" in api.calls); assertTrue(store.writes.isEmpty())
        }
    }
    @Test fun conflictRetainsLocalIntentAndBytesAlongsideNewAssessment() = runTest {
        val current = Assessment("CG-204", 8, Condition.Good, "Current inspection")
        val store = store(); val api = Api().apply { onCommit = { CommitReply.Conflict(current) } }
        assertEquals(SyncResult.NeedsReview, InspectionSync(store, api).run(payload().operationId))
        assertEquals(Stage.NeedsReview, store.row!!.stage)
        assertEquals(payload(), store.row!!.payload); assertEquals(current, store.row!!.current)
        assertArrayEquals(photo, store.bytes); assertTrue(store.row!!.photoRetained)
        assertEquals(listOf("uploaded", "conflicted"), store.writes)
        assertNull(store.row!!.receipt)
    }
    @Test fun nonAdvancingConflictCannotReplaceReviewedVersion() = runTest {
        for (revision in listOf(6, 7)) {
            val store = store(); val api = Api().apply { onCommit = { CommitReply.Conflict(Assessment("CG-204", revision, Condition.Good, "old")) } }
            assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
            assertEquals(Stage.Uploaded, store.row!!.stage); assertNull(store.row!!.current)
        }
    }
    @Test fun wrongReportPayloadAfterPhotoUploadCannotPublishSubmitted() = runTest {
        val store = store(); val api = Api().apply { onCommit = { CommitReply.Accepted(receipt(it.copy(condition = Condition.Good))) } }
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
        assertEquals(Stage.Uploaded, store.row!!.stage); assertNull(store.row!!.receipt)
        assertArrayEquals(photo, store.bytes)
    }
    @Test fun missingRemoteBlobAfterAcknowledgementLeavesRecoverableIntent() = runTest {
        val store = store(); val api = Api().apply { onCommit = { CommitReply.MissingPhoto } }
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
        assertEquals(Stage.Uploaded, store.row!!.stage); assertArrayEquals(photo, store.bytes)
        api.onCommit = { CommitReply.Accepted(receipt(it)) }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
        assertEquals(2, api.calls.count { it == "upload" })
    }
    @Test fun lostPhotoReplyRecoversStoredBytesBeforeAnyNewUpload() = runTest {
        val store = store(); val api = Api(); var remote: BlobReceipt? = null
        api.onFind = { remote }
        api.onUpload = { digest, bytes -> remote = BlobReceipt(digest, bytes.size); throw IOException("reply lost") }
        val sync = InspectionSync(store, api)
        assertEquals(SyncResult.Retry, sync.run(payload().operationId))
        assertEquals(Stage.Queued, store.row!!.stage)
        assertEquals(SyncResult.Finished, sync.run(payload().operationId))
        assertEquals(1, api.calls.count { it == "upload" })
        assertEquals(1, api.calls.count { it == "commit" })
    }
    @Test fun lostReportReplyUsesReplayBeforeConflictOrAnotherUpload() = runTest {
        val store = store(); val api = Api(); var accepted: ReportReceipt? = null
        api.onLookup = { accepted }
        api.onCommit = { accepted = receipt(it); throw IOException("committed reply lost") }
        val sync = InspectionSync(store, api)
        assertEquals(SyncResult.Retry, sync.run(payload().operationId)); assertEquals(Stage.Uploaded, store.row!!.stage)
        assertEquals(SyncResult.Finished, sync.run(payload().operationId)); assertEquals(Stage.Submitted, store.row!!.stage)
        assertEquals(1, api.calls.count { it == "commit" }); assertEquals(1, api.calls.count { it == "upload" })
    }
    @Test fun lookupIoFailureDoesNotInventAnUnacceptedNewOperation() = runTest {
        val store = store(); val api = Api().apply { onLookup = { throw IOException("unavailable") } }
        assertEquals(SyncResult.Retry, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup"), api.calls); assertEquals(payload(), store.row!!.payload)
        assertTrue(store.writes.isEmpty())
    }
    @Test fun cancellationDuringLookupIsNotConvertedToRetryOrSuccess() = runTest {
        val store = store(); val api = Api().apply { onLookup = { throw CancellationException("stopped") } }
        try { InspectionSync(store, api).run(payload().operationId); fail<Unit>("Cancellation must propagate") }
        catch (expected: CancellationException) { assertEquals("stopped", expected.message) }
        assertTrue(store.writes.isEmpty()); assertEquals(listOf("lookup"), api.calls)
    }
    @Test fun cancellationAfterBlobAcceptanceLeavesReportUnsubmitted() = runTest {
        val store = store(); val api = Api().apply { onCommit = { throw CancellationException("worker stopped") } }
        try { InspectionSync(store, api).run(payload().operationId); fail<Unit>("Cancellation must propagate") }
        catch (expected: CancellationException) { assertEquals("worker stopped", expected.message) }
        assertEquals(Stage.Uploaded, store.row!!.stage); assertNull(store.row!!.receipt)
        assertArrayEquals(photo, store.bytes)
    }
    @Test fun resumedUploadedStageStillReconcilesRatherThanAssumingCompletion() = runTest {
        val store = store(stage = Stage.Uploaded); val api = Api().apply { onFind = { BlobReceipt(it, photo.size) } }
        assertEquals(SyncResult.Finished, InspectionSync(store, api).run(payload().operationId))
        assertEquals(listOf("lookup", "find", "commit"), api.calls)
        assertEquals(Stage.Submitted, store.row!!.stage)
    }
    @Test fun wrongUploadMeansDoneCounterexampleDisagreesWithRealCompletionBoundary() = runTest {
        // Deliberately wrong model, executed only here: a blob acknowledgement is mistaken for report completion.
        fun wrongCompletion(@Suppress("UNUSED_PARAMETER") blob: BlobReceipt) = Stage.Submitted
        val store = store(); val api = Api().apply { onCommit = { CommitReply.Conflict(Assessment("CG-204", 8, Condition.Good, "Reviewed remotely")) } }
        assertEquals(Stage.Submitted, wrongCompletion(BlobReceipt(payload().photoDigest, photo.size)))
        assertEquals(SyncResult.NeedsReview, InspectionSync(store, api).run(payload().operationId))
        assertNotEquals(Stage.Submitted, store.row!!.stage)
        assertArrayEquals(photo, store.bytes)
    }
}

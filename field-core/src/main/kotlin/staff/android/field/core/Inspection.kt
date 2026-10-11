package staff.android.field.core

import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException

const val MAX_PHOTO_BYTES = 262_144
const val MAX_LOCAL_PHOTO_BYTES = 1_048_576
fun digestOf(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
    .digest(bytes).joinToString("") { "%02x".format(it) }
fun validId(value: String) = value.matches(Regex("[a-f0-9]{32}"))
fun validDigest(value: String) = value.matches(Regex("[a-f0-9]{64}"))
fun validNote(value: String) = value.length <= 160 && value.codePoints().allMatch {
    !Character.isISOControl(it) && it !in 0xD800..0xDFFF
}
enum class Condition { Good, Damaged }
enum class Stage { Queued, Uploaded, NeedsReview, Submitted, Superseded }
data class InspectionPayload(val operationId: String, val visitId: String,
    val baseRevision: Int, val condition: Condition, val note: String, val photoDigest: String) {
    init {
        require(validId(operationId)); require(visitId == "CG-204")
        require(baseRevision in 1..1_000_000); require(validNote(note)); require(validDigest(photoDigest))
    }
}
data class Assessment(val visitId: String, val revision: Int,
    val condition: Condition, val note: String) {
    init { require(visitId == "CG-204"); require(revision in 1..1_000_000)
        require(validNote(note)) }
}
data class ReportReceipt(val payload: InspectionPayload, val revision: Int) {
    init { require(revision > payload.baseRevision && revision <= 1_000_000) }
}
data class BlobReceipt(val digest: String, val size: Int) {
    init { require(validDigest(digest)); require(size in 1..MAX_PHOTO_BYTES) }
}
data class SavedInspection(val payload: InspectionPayload, val stage: Stage,
    val current: Assessment? = null, val receipt: ReportReceipt? = null,
    val photoRetained: Boolean = true)
sealed interface CommitReply {
    data class Accepted(val receipt: ReportReceipt) : CommitReply
    data class Conflict(val current: Assessment) : CommitReply
    data object MissingPhoto : CommitReply
}
interface InspectionService {
    suspend fun lookup(operationId: String): ReportReceipt?
    suspend fun findPhoto(digest: String): BlobReceipt?
    suspend fun uploadPhoto(digest: String, bytes: ByteArray): BlobReceipt
    suspend fun commit(payload: InspectionPayload): CommitReply
}
interface InspectionStore {
    fun load(operationId: String): SavedInspection?
    fun photo(digest: String): ByteArray?
    fun uploaded(operationId: String)
    fun accepted(operationId: String, receipt: ReportReceipt)
    fun conflicted(operationId: String, current: Assessment)
}
enum class SyncResult { Finished, NeedsReview, Retry }

/** One immutable intent; a receipt for its bytes never completes its report. */
class InspectionSync(private val store: InspectionStore, private val service: InspectionService) {
    suspend fun run(operationId: String): SyncResult {
        val saved = store.load(operationId) ?: return SyncResult.Finished
        if (saved.stage !in setOf(Stage.Queued, Stage.Uploaded)) return SyncResult.Finished
        val payload = saved.payload
        return try {
            val known = service.lookup(operationId)
            if (known != null) {
                requireReceipt(payload, known); store.accepted(operationId, known)
                return SyncResult.Finished
            }
            val bytes = store.photo(payload.photoDigest) ?: throw IOException("Owned photo missing")
            if (bytes.isEmpty() || bytes.size > MAX_PHOTO_BYTES || digestOf(bytes) != payload.photoDigest)
                throw IOException("Owned photo failed validation")
            val uploaded = service.findPhoto(payload.photoDigest)
                ?: service.uploadPhoto(payload.photoDigest, bytes)
            if (uploaded.digest != payload.photoDigest || uploaded.size != bytes.size)
                throw IOException("Photo receipt mismatch")
            store.uploaded(operationId)
            when (val reply = service.commit(payload)) {
                is CommitReply.Accepted -> {
                    requireReceipt(payload, reply.receipt)
                    store.accepted(operationId, reply.receipt); SyncResult.Finished
                }
                is CommitReply.Conflict -> {
                    if (reply.current.visitId != payload.visitId || reply.current.revision <= payload.baseRevision)
                        throw IOException("Conflict does not advance reviewed version")
                    store.conflicted(operationId, reply.current); SyncResult.NeedsReview
                }
                CommitReply.MissingPhoto -> SyncResult.Retry
            }
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: IOException) { SyncResult.Retry }
    }
    private fun requireReceipt(payload: InspectionPayload, receipt: ReportReceipt) {
        if (receipt.payload != payload) throw IOException("Report receipt mismatch")
    }
}

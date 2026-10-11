package staff.android.field

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import staff.android.field.core.*

data class StoredField(val assessment: Assessment, val inspection: SavedInspection?, val photo: ByteArray?)

/** The small BLOB and immutable outbox row commit together; all calls run off the UI thread. */
class FieldStore(context: Context, databaseName: String = "field.db") : SQLiteOpenHelper(context, databaseName, null, 1), InspectionStore {
    private val revisionSignal = MutableStateFlow(0L)
    val changes = revisionSignal.asStateFlow()
    override fun onConfigure(db: SQLiteDatabase) { db.setForeignKeyConstraintsEnabled(true) }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE photos(digest TEXT PRIMARY KEY, bytes BLOB NOT NULL)")
        db.execSQL("CREATE TABLE visit(id TEXT PRIMARY KEY, revision INTEGER NOT NULL, condition TEXT NOT NULL, note TEXT NOT NULL)")
        db.execSQL("INSERT INTO visit VALUES('CG-204',7,'Good','Packaging intact')")
        db.execSQL("""CREATE TABLE reports(seq INTEGER PRIMARY KEY AUTOINCREMENT, id TEXT NOT NULL UNIQUE,
            visit TEXT NOT NULL, base INTEGER NOT NULL, condition TEXT NOT NULL, note TEXT NOT NULL,
            digest TEXT NOT NULL, blob_ref TEXT REFERENCES photos(digest), stage TEXT NOT NULL,
            remote_rev INTEGER, remote_condition TEXT, remote_note TEXT, receipt_rev INTEGER)""")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("No migration exists beyond schema1") }
    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase; db.beginTransaction()
        val result: T
        try { result = block(db); db.setTransactionSuccessful() } finally { db.endTransaction() }
        revisionSignal.value += 1
        return result
    }
    @Synchronized fun assessment(): Assessment = readableDatabase.rawQuery("SELECT * FROM visit WHERE id='CG-204'", null).use { c ->
        check(c.moveToFirst()); Assessment(c.text("id"), c.number("revision"), Condition.valueOf(c.text("condition")), c.text("note"))
    }
    @Synchronized fun snapshot(): StoredField {
        val row = latest()
        return StoredField(assessment(), row, row?.takeIf { it.photoRetained }?.let { photo(it.payload.photoDigest) })
    }
    @Synchronized fun save(id: String, condition: Condition, note: String, bytes: ByteArray): SavedInspection {
        val owned = bytes.copyOf(); require(owned.size in 1..MAX_PHOTO_BYTES)
        val hash = digestOf(owned)
        return transaction { db ->
            check(latest()?.stage !in setOf(Stage.Queued, Stage.Uploaded, Stage.NeedsReview)) { "Resolve the saved inspection first" }
            val payload = InspectionPayload(id, "CG-204", assessment().revision, condition, note, hash)
            insert(db, payload, owned); load(id)!!
        }
    }
    private fun insert(db: SQLiteDatabase, payload: InspectionPayload, bytes: ByteArray) {
        val count = db.rawQuery("SELECT COUNT(*) FROM reports", null).use { it.moveToFirst(); it.getInt(0) }
        require(count < 100) { "This checkpoint retains at most100 reports" }
        val existing = photo(payload.photoDigest)
        if (existing != null) require(existing.contentEquals(bytes))
        else {
            val used = db.rawQuery("SELECT COALESCE(SUM(length(bytes)),0) FROM photos", null).use { it.moveToFirst(); it.getLong(0) }
            require(bytes.size <= MAX_LOCAL_PHOTO_BYTES - used) { "Saved photos use this checkpoint's allowance" }
            db.insertOrThrow("photos", null, ContentValues().apply { put("digest", payload.photoDigest); put("bytes", bytes) })
        }
        db.insertOrThrow("reports", null, ContentValues().apply {
            put("id", payload.operationId); put("visit", payload.visitId); put("base", payload.baseRevision)
            put("condition", payload.condition.name); put("note", payload.note); put("digest", payload.photoDigest)
            put("blob_ref", payload.photoDigest); put("stage", Stage.Queued.name)
        })
    }
    @Synchronized override fun load(operationId: String): SavedInspection? = readableDatabase.rawQuery(
        "SELECT * FROM reports WHERE id=?", arrayOf(operationId)).use { if (it.moveToFirst()) record(it) else null }
    @Synchronized fun latest(): SavedInspection? = readableDatabase.rawQuery("SELECT * FROM reports ORDER BY seq DESC LIMIT 1", null)
        .use { if (it.moveToFirst()) record(it) else null }
    @Synchronized fun history(): List<SavedInspection> = readableDatabase.rawQuery("SELECT * FROM reports ORDER BY seq", null).use { c ->
        buildList { while (c.moveToNext()) add(record(c)) }
    }
    @Synchronized fun pendingIds(): List<String> = readableDatabase.rawQuery(
        "SELECT id FROM reports WHERE stage IN ('Queued','Uploaded') ORDER BY seq", null).use { c ->
        buildList { while (c.moveToNext()) add(c.getString(0)) }
    }
    @Synchronized override fun photo(digest: String): ByteArray? = readableDatabase.rawQuery(
        "SELECT bytes FROM photos WHERE digest=?", arrayOf(digest)).use { if (it.moveToFirst()) it.getBlob(0) else null }
    @Synchronized fun photoBytes(): Long = readableDatabase.rawQuery("SELECT COALESCE(SUM(length(bytes)),0) FROM photos", null).use { it.moveToFirst(); it.getLong(0) }
    @Synchronized override fun uploaded(operationId: String) { transaction { db -> db.execSQL(
        "UPDATE reports SET stage='Uploaded' WHERE id=? AND stage='Queued'", arrayOf(operationId)) } }
    @Synchronized override fun accepted(operationId: String, receipt: ReportReceipt) {
        transaction { db ->
            val row = load(operationId) ?: return@transaction
            require(row.payload == receipt.payload)
            if (row.stage !in setOf(Stage.Queued, Stage.Uploaded)) return@transaction
            db.execSQL("UPDATE reports SET stage='Submitted',receipt_rev=? WHERE id=?", arrayOf<Any>(receipt.revision, operationId))
            updateAssessment(db, Assessment(row.payload.visitId, receipt.revision, row.payload.condition, row.payload.note))
        }
    }
    @Synchronized override fun conflicted(operationId: String, current: Assessment) {
        transaction { db ->
            val row = load(operationId) ?: return@transaction
            require(current.visitId == row.payload.visitId && current.revision > row.payload.baseRevision)
            if (row.stage !in setOf(Stage.Queued, Stage.Uploaded)) return@transaction
            db.execSQL("UPDATE reports SET stage='NeedsReview',remote_rev=?,remote_condition=?,remote_note=? WHERE id=?",
                arrayOf<Any>(current.revision, current.condition.name, current.note, operationId))
            updateAssessment(db, current)
        }
    }
    /** The review token must still name the exact conflict the worker saw. */
    @Synchronized fun resolve(operationId: String, reviewedRevision: Int, keepMine: Boolean, newId: String): SavedInspection {
        return transaction { db ->
            val old = load(operationId) ?: error("Inspection missing")
            require(old.stage == Stage.NeedsReview && old.current?.revision == reviewedRevision)
            db.execSQL("UPDATE reports SET stage='Superseded' WHERE id=?", arrayOf(operationId))
            if (keepMine) {
                val next = old.payload.copy(operationId = newId, baseRevision = reviewedRevision)
                val owned = photo(next.photoDigest) ?: error("Saved evidence missing")
                insert(db, next, owned); load(newId)!!
            } else load(operationId)!!
        }
    }
    @Synchronized fun releasePhoto(operationId: String): Boolean = transaction { db ->
        val row = load(operationId) ?: return@transaction false
        if (row.stage !in setOf(Stage.Submitted, Stage.Superseded)) return@transaction false
        db.execSQL("UPDATE reports SET blob_ref=NULL WHERE id=?", arrayOf(operationId))
        db.execSQL("DELETE FROM photos WHERE digest NOT IN (SELECT blob_ref FROM reports WHERE blob_ref IS NOT NULL)")
        true
    }
    private fun updateAssessment(db: SQLiteDatabase, current: Assessment) {
        db.execSQL("UPDATE visit SET revision=?,condition=?,note=? WHERE id=? AND revision<=?",
            arrayOf<Any>(current.revision, current.condition.name, current.note, current.visitId, current.revision))
    }
    private fun record(c: Cursor): SavedInspection {
        val payload = InspectionPayload(c.text("id"), c.text("visit"), c.number("base"), Condition.valueOf(c.text("condition")), c.text("note"), c.text("digest"))
        val remote = if (c.isNull(c.getColumnIndexOrThrow("remote_rev"))) null else
            Assessment(payload.visitId, c.number("remote_rev"), Condition.valueOf(c.text("remote_condition")), c.text("remote_note"))
        val receipt = if (c.isNull(c.getColumnIndexOrThrow("receipt_rev"))) null else ReportReceipt(payload, c.number("receipt_rev"))
        return SavedInspection(payload, Stage.valueOf(c.text("stage")), remote, receipt, !c.isNull(c.getColumnIndexOrThrow("blob_ref")))
    }
    private fun Cursor.text(name: String) = getString(getColumnIndexOrThrow(name))
    private fun Cursor.number(name: String) = getInt(getColumnIndexOrThrow(name))
}

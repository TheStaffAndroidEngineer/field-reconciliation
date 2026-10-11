package staff.android.field

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import staff.android.field.core.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class FieldTransport(val route: String, val kind: String, val status: Int = 0, val bytes: Int = 0)
class FieldApi(private val origin: String = "http://127.0.0.1:8890",
    private val record: (FieldTransport) -> Unit = {}) : InspectionService {
    private val client = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
        .retryOnConnectionFailure(true).cache(null).build()
    private fun observe(row: FieldTransport) { try { record(row) } catch (_: Exception) { } }
    override suspend fun lookup(operationId: String): ReportReceipt? {
        require(validId(operationId)); val (status, body) = request("report_lookup", Request.Builder().url("$origin/v1/reports/$operationId").get().build())
        if (status == 404 && body.text("error") == "unknown_operation") return null
        requireSuccess(status, body); return receipt(body.obj("receipt"))
    }
    override suspend fun findPhoto(digest: String): BlobReceipt? {
        require(validDigest(digest)); val (status, body) = request("photo_lookup", Request.Builder().url("$origin/v1/photos/$digest").get().build())
        if (status == 404 && body.text("error") == "missing_photo") return null
        requireSuccess(status, body); return blob(body)
    }
    override suspend fun uploadPhoto(digest: String, bytes: ByteArray): BlobReceipt {
        val owned = bytes.copyOf(); require(owned.size in 1..MAX_PHOTO_BYTES && digestOf(owned) == digest)
        val (status, body) = request("photo", Request.Builder().url("$origin/v1/photos/$digest")
            .put(owned.toRequestBody("application/octet-stream".toMediaType())).build())
        requireSuccess(status, body); return blob(body)
    }
    override suspend fun commit(payload: InspectionPayload): CommitReply {
        val body = buildJsonObject {
            put("operationId", payload.operationId); put("visitId", payload.visitId); put("baseRevision", payload.baseRevision)
            put("condition", payload.condition.name); put("note", payload.note); put("photoDigest", payload.photoDigest)
        }
        val (status, response) = request("report", Request.Builder().url("$origin/v1/reports")
            .header("Idempotency-Key", payload.operationId).post(body.toString().toRequestBody("application/json".toMediaType())).build())
        if (status == 409) {
            schema(response)
            return when (response.text("error")) {
                "revision_conflict" -> CommitReply.Conflict(assessment(response.obj("current")))
                "missing_photo" -> CommitReply.MissingPhoto
                else -> throw IOException("Unsupported report conflict")
            }
        }
        requireSuccess(status, response); return CommitReply.Accepted(receipt(response.obj("receipt")))
    }
    private suspend fun request(route: String, request: Request): Pair<Int, JsonObject> {
        val answer = withTimeoutOrNull(12_000) {
            suspendCancellableCoroutine<Pair<Int, String>> { continuation ->
                val call = client.newCall(request.newBuilder().header("Cache-Control", "no-store").build())
                call.timeout().timeout(10, TimeUnit.SECONDS)
                continuation.invokeOnCancellation { call.cancel(); observe(FieldTransport(route, "cancelled")) }
                observe(FieldTransport(route, "enqueued"))
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, e: IOException) {
                        observe(FieldTransport(route, "failed")); continuation.resumeWithException(e)
                    }
                    override fun onResponse(call: Call, response: Response) {
                        val pair = try { response.use {
                            observe(FieldTransport(route, "headers", response.code))
                            if (response.body.contentLength() > 16_384) throw IOException("Response too large")
                            val out = java.io.ByteArrayOutputStream(); val buffer = ByteArray(4096)
                            val input = response.body.byteStream()
                            while (true) {
                                val count = input.read(buffer); if (count == -1) break
                                if (count > 16_384 - out.size()) throw IOException("Response too large")
                                out.write(buffer, 0, count)
                            }
                            val data = out.toByteArray()
                            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()
                            observe(FieldTransport(route, "received", response.code, data.size))
                            response.code to text
                        } } catch (failure: IOException) { continuation.resumeWithException(failure); return }
                        continuation.resume(pair)
                    }
                })
            }
        } ?: throw IOException("Transport attempt expired")
        return answer.first to parse { Json.parseToJsonElement(answer.second) as? JsonObject ?: throw IOException("Expected object") }
    }
    private fun schema(body: JsonObject) { if (body.number("schema") != 1) throw IOException("Unsupported schema") }
    private fun requireSuccess(status: Int, body: JsonObject) { if (status != 200) throw IOException("HTTP $status"); schema(body) }
    private fun blob(body: JsonObject) = parse { BlobReceipt(body.text("digest"), body.number("size")) }
    private fun payload(body: JsonObject) = parse { InspectionPayload(body.text("operationId"), body.text("visitId"), body.number("baseRevision"), Condition.valueOf(body.text("condition")), body.text("note"), body.text("photoDigest")) }
    private fun receipt(body: JsonObject) = parse { ReportReceipt(payload(body.obj("payload")), body.number("revision")) }
    private fun assessment(body: JsonObject) = parse { Assessment(body.text("visitId"), body.number("revision"), Condition.valueOf(body.text("condition")), body.text("note")) }
    private fun <T> parse(block: () -> T): T = try { block() } catch (bad: IllegalArgumentException) { throw IOException("Invalid reply", bad) }
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: throw IOException("Invalid field")
    private fun JsonObject.obj(key: String) = get(key) as? JsonObject ?: throw IOException("Invalid object")
    private fun JsonObject.number(key: String): Int = (get(key) as? JsonPrimitive)?.takeIf { !it.isString && it.content.matches(Regex("0|[1-9][0-9]*")) }?.intOrNull ?: throw IOException("Invalid integer")
}

package staff.android.field

import android.app.Application
import android.content.Context
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import staff.android.field.core.*
import java.util.UUID
import java.util.concurrent.TimeUnit

object FieldEvidence {
    private val rows = ArrayDeque<FieldTransport>()
    @Synchronized fun record(row: FieldTransport) { if (rows.size == 256) rows.removeFirst(); rows.addLast(row) }
    @Synchronized fun snapshot() = rows.toList()
    @Synchronized fun reset() { rows.clear() }
}
class FieldApplication : Application() {
    val graph: FieldGraph by lazy { FieldGraph(this) }
    override fun onCreate() {
        super.onCreate()
        graph.scope.launch { graph.rescan() }
    }
}
class FieldGraph(val context: Context) {
    val store = FieldStore(context)
    val service: InspectionService = FieldApi(record = FieldEvidence::record)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val active = MutableStateFlow<Map<UUID, String>>(emptyMap())
    fun enqueue(operationId: String, replace: Boolean = false) {
        require(validId(operationId))
        val request = OneTimeWorkRequestBuilder<InspectionWorker>()
            .setInputData(workDataOf("operationId" to operationId))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("inspection-$operationId",
            if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
    }
    fun rescan() { store.pendingIds().forEach { enqueue(it) } }
}
class InspectionWorker(context: Context, parameters: WorkerParameters) : CoroutineWorker(context, parameters) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val operationId = inputData.getString("operationId")?.takeIf(::validId) ?: return@withContext Result.failure()
        val graph = (applicationContext as FieldApplication).graph
        graph.active.update { it + (id to operationId) }
        try {
            when (InspectionSync(graph.store, graph.service).run(operationId)) {
                SyncResult.Finished, SyncResult.NeedsReview -> Result.success()
                SyncResult.Retry -> Result.retry()
            }
        } finally { graph.active.update { it - id } }
    }
}

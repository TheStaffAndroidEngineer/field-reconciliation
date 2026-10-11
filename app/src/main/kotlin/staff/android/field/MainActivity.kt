package staff.android.field

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import staff.android.field.core.*
import java.util.UUID

data class FieldUi(val loaded: Boolean = false, val assessment: Assessment? = null,
    val inspection: SavedInspection? = null, val photo: ByteArray? = null, val sending: Boolean = false)
class FieldModel(val graph: FieldGraph) : ViewModel() {
    val error = MutableStateFlow<String?>(null)
    val actionRunning = MutableStateFlow(false)
    val state = combine(graph.store.changes, graph.active) { _, active ->
        withContext(Dispatchers.IO) {
            val saved = graph.store.snapshot()
            FieldUi(true, saved.assessment, saved.inspection, saved.photo,
                saved.inspection?.payload?.operationId in active.values)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FieldUi())
    private fun identity() = UUID.randomUUID().toString().replace("-", "")
    private fun action(block: () -> Unit) {
        if (actionRunning.value) return
        actionRunning.value = true; error.value = null
        // Saving and the scheduling hint belong to the application, not this screen's lifetime.
        graph.scope.launch {
            try { block() } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: IllegalArgumentException) { error.value = "Check the note and photo, then try again." }
            catch (_: java.io.IOException) { error.value = "The photo could not be read. Try again." }
            catch (_: android.database.sqlite.SQLiteException) { error.value = "The device could not save this change. Free some storage and try again." }
            catch (_: IllegalStateException) { error.value = "This inspection changed. Review the saved result before trying again." }
            finally { actionRunning.value = false }
        }
    }
    fun save(condition: Condition, note: String) = action {
        val bytes = prepareSamplePhoto(graph.context)
        val row = graph.store.save(identity(), condition, note, bytes)
        graph.enqueue(row.payload.operationId)
    }
    fun sendAgain(id: String) = action { graph.enqueue(id, replace = true) }
    fun resolve(id: String, revision: Int, keepMine: Boolean) = action {
        val row = graph.store.resolve(id, revision, keepMine, identity())
        if (row.stage == Stage.Queued) graph.enqueue(row.payload.operationId)
    }
    fun release(id: String) = action { graph.store.releasePhoto(id) }
}
class MainActivity : ComponentActivity() {
    lateinit var model: FieldModel; private set
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge()
        val graph = (application as FieldApplication).graph
        model = ViewModelProvider(this, object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST") return FieldModel(graph) as T
            }
        })[FieldModel::class.java]
        setContent { FieldApp(model, intent.getBooleanExtra("open_inspection", false)) }
    }
    override fun onStart() { super.onStart(); (application as FieldApplication).graph.scope.launch { model.graph.rescan() } }
}

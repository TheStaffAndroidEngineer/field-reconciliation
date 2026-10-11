package staff.android.field

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import staff.android.field.core.*

@Composable private fun Brand(back: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_common_goods), null, Modifier.size(40.dp))
        Spacer(Modifier.width(10.dp)); Column(Modifier.weight(1f)) {
            Text("Common Goods", style = MaterialTheme.typography.titleLarge)
            Text("FIELD · COLLECTIONS", style = MaterialTheme.typography.labelSmall)
        }
        if (back != null) TextButton(onClick = back, Modifier.testTag("back_collections")) { Text("Back") }
    }
}
@Composable private fun ProductPhoto(modifier: Modifier) {
    val context = LocalContext.current; val images = remember(context) { ProductImages.get(context) }
    val bitmap by produceState<Bitmap?>(null) { value = images.load("camera") }
    PhotoBox(bitmap, "Camera collection", modifier)
}
@Composable private fun PhotoBox(bitmap: Bitmap?, description: String, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bitmap?.let { Image(it.asImageBitmap(), description, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
    }
}
@Composable fun FieldApp(model: FieldModel, openInspection: Boolean) {
    val state by model.state.collectAsStateWithLifecycle(); val error by model.error.collectAsStateWithLifecycle()
    val action by model.actionRunning.collectAsStateWithLifecycle()
    var inspection by rememberSaveable { mutableStateOf(openInspection) }
    BackHandler(inspection) { inspection = false }
    CommonTheme {
        Surface(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.systemBars).imePadding()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.widthIn(max = 900.dp).fillMaxWidth().semantics { testTagsAsResourceId = true }) {
                    if (!state.loaded) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.testTag("loading")) }
                    else if (inspection) InspectionScreen(state, error, action, model) { inspection = false }
                    else Collections(state) { inspection = true }
                }
            }
        }
    }
}
@Composable private fun Collections(state: FieldUi, open: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize().testTag("collections"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        item { Brand() }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Good care,\nwherever you go.", style = MaterialTheme.typography.displaySmall)
            Text("Your assigned collections, ready to check in the field.", style = MaterialTheme.typography.bodyLarge)
        } }
        item { ProductPhoto(Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(1.65f)) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Camera collection", style = MaterialTheme.typography.headlineMedium)
            Text("CG-204 · North district", style = MaterialTheme.typography.bodyLarge)
            Text(if (state.inspection == null) "Assigned to you. Record the condition and keep a photo with your inspection." else "Your saved inspection is available on this device.")
            Button(onClick = open, Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("open_inspection")) { Text("Open inspection") }
        } }
    }
}
@Composable private fun InspectionScreen(state: FieldUi, error: String?, action: Boolean, model: FieldModel, back: () -> Unit) {
    var condition by rememberSaveable { mutableStateOf(Condition.Damaged) }
    var note by rememberSaveable { mutableStateOf("Crack beside the lens") }
    var attached by rememberSaveable { mutableStateOf(false) }
    val row = state.inspection
    val bitmap by produceState<Bitmap?>(null, state.photo) { value = state.photo?.let { bytes -> withContext(Dispatchers.IO) { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) } } }
    LazyColumn(Modifier.fillMaxSize().testTag("inspection"), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item { Brand(back) }
        item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Camera\ninspection", style = MaterialTheme.typography.displaySmall)
            Text("CG-204 · North district", Modifier.testTag("visit_scope"), style = MaterialTheme.typography.titleMedium)
            Text("Record what you find. A saved inspection can wait for a connection.")
        } }
        if (row == null) {
            item { ProductPhoto(Modifier.padding(horizontal = 20.dp).fillMaxWidth().aspectRatio(2f)) }
            item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Condition", style = MaterialTheme.typography.headlineSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Condition.entries.forEach { value -> FilterChip(selected = condition == value, onClick = { condition = value }, label = { Text(value.name) }, modifier = Modifier.testTag("condition_${value.name.lowercase()}"), colors = FilterChipDefaults.filterChipColors(selectedContainerColor = MaterialTheme.colorScheme.primary, selectedLabelColor = MaterialTheme.colorScheme.onPrimary)) }
                }
                OutlinedTextField(note, onValueChange = { if (it.length <= 160) note = it }, label = { Text("Inspection note") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("inspection_note"))
                Text("Add a photo", style = MaterialTheme.typography.headlineSmall)
                Text("Use a sample photo for this inspection.")
                OutlinedButton(onClick = { attached = true }, enabled = !attached, modifier = Modifier.fillMaxWidth().testTag("attach_photo")) { Text(if (attached) "Photo selected" else "Use provided photo") }
                if (attached) Text("Provided example photo selected. Save it with your inspection.", Modifier.testTag("photo_selected"))
                Button(onClick = { model.save(condition, note) }, enabled = attached && validNote(note) && !action, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("save_local")) { Text("Save on this device") }
            } }
        } else {
            item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val title = when (row.stage) {
                            Stage.Queued -> "Saved on this device"
                            Stage.Uploaded -> "Photo sent; report pending"
                            Stage.NeedsReview -> "Needs review"
                            Stage.Submitted -> "Submitted"
                            Stage.Superseded -> "Current assessment kept"
                        }
                        Text(title, Modifier.testTag("status_${row.stage.name.lowercase()}"), style = MaterialTheme.typography.headlineSmall)
                        Text(when (row.stage) {
                            Stage.Queued -> "Your note and photo are safe here. We'll send them when a connection is available."
                            Stage.Uploaded -> "The photo reached the service. Your inspection still needs to be accepted."
                            Stage.NeedsReview -> "The collection changed while your inspection was waiting. Compare both before you decide."
                            Stage.Submitted -> "Your inspection was accepted. You can return to your collections."
                            Stage.Superseded -> "The current assessment was kept. Your earlier inspection remains in local history."
                        }, Modifier.testTag("status_detail"))
                        if (state.sending && row.stage in setOf(Stage.Queued, Stage.Uploaded)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth().testTag("sending")); Text("Sending your saved inspection…")
                        }
                        row.receipt?.let { Text("Accepted revision ${it.revision}", Modifier.testTag("accepted_revision"), style = MaterialTheme.typography.labelLarge) }
                    }
                }
                if (row.stage == Stage.NeedsReview) {
                    Text("Your saved inspection", style = MaterialTheme.typography.titleLarge)
                    AssessmentCard(row.payload.condition.name, row.payload.note, "Reviewed revision ${row.payload.baseRevision}", "local_assessment")
                    Text("Current assessment", style = MaterialTheme.typography.titleLarge)
                    val current = row.current!!
                    AssessmentCard(current.condition.name, current.note, "Current revision ${current.revision}", "server_assessment")
                    Button(onClick = { model.resolve(row.payload.operationId, current.revision, true) }, enabled = !action, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("keep_mine")) { Text("Keep my inspection") }
                    OutlinedButton(onClick = { model.resolve(row.payload.operationId, current.revision, false) }, enabled = !action, modifier = Modifier.fillMaxWidth().testTag("use_current")) { Text("Use current assessment") }
                } else {
                    if (row.stage == Stage.Superseded) {
                        Text("Current assessment", style = MaterialTheme.typography.titleLarge)
                        val accepted = row.current!!
                        AssessmentCard(accepted.condition.name, accepted.note, "Current revision ${accepted.revision}", "server_assessment")
                    }
                    Text(if (row.stage == Stage.Superseded) "Earlier local inspection" else "Saved condition", style = MaterialTheme.typography.titleLarge)
                    AssessmentCard(row.payload.condition.name, row.payload.note, "Inspection for CG-204", "saved_assessment")
                }
                if (row.stage in setOf(Stage.Queued, Stage.Uploaded)) Button(onClick = { model.sendAgain(row.payload.operationId) }, enabled = !action, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("send_again")) { Text("Send again") }
            } }
            item { Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Inspection photo", style = MaterialTheme.typography.titleLarge)
                if (row.photoRetained) {
                    PhotoBox(bitmap, "Saved provided evidence photo", Modifier.fillMaxWidth().aspectRatio(1.7f).testTag("saved_photo"))
                    Text("Provided example photo · retained on this device", Modifier.testTag("photo_retained"))
                    if (row.stage in setOf(Stage.Submitted, Stage.Superseded)) TextButton(onClick = { model.release(row.payload.operationId) }, enabled = !action, modifier = Modifier.testTag("release_photo")) { Text("Remove local photo") }
                } else Text("Photo removed from this inspection’s local record. Other saved inspections may still retain it.", Modifier.testTag("photo_removed"))
                OutlinedButton(onClick = back, modifier = Modifier.fillMaxWidth().testTag("return_collections")) { Text("Back to collections") }
            } }
        }
        error?.let { item { Text(it, Modifier.padding(horizontal = 20.dp).testTag("action_error"), color = MaterialTheme.colorScheme.error) } }
    }
}
@Composable private fun AssessmentCard(condition: String, note: String, caption: String, tag: String) {
    OutlinedCard(Modifier.fillMaxWidth().testTag(tag)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(condition, style = MaterialTheme.typography.titleLarge); Text(note, style = MaterialTheme.typography.bodyLarge)
            Text(caption, style = MaterialTheme.typography.labelMedium)
        }
    }
}

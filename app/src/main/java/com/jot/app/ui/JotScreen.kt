package com.jot.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jot.app.capture.VoiceEngineState
import com.jot.app.companion.CompanionAccount
import com.jot.app.companion.CompanionState
import com.jot.app.data.ActionKind
import com.jot.app.data.NoteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JotScreen(
    notes: List<NoteItem>,
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
    account: CompanionAccount?,
    billingBusy: Boolean,
    callbacks: JotCallbacks,
) {
    val snackbar = remember { SnackbarHostState() }
    val message = state.message
    LaunchedEffect(message) {
        if (message != null) {
            snackbar.showSnackbar(message)
            callbacks.onDismissMessage()
        }
    }

    val libraryState = rememberSaveableStateHolder()
    Scaffold(
        containerColor = if (state.stage == Stage.NOTE) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { if (state.stage == Stage.HOME || state.stage == Stage.NOTE) SnackbarHost(snackbar) },
    ) { padding ->
        val viewport = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()
        if (state.stage == Stage.NOTE) {
            key(state.note?.id) { NoteEditor(state, voice, companion, callbacks, viewport) }
        } else {
            libraryState.SaveableStateProvider("notes") {
                NotesHome(notes, state, voice, companion, account, billingBusy, callbacks, viewport)
            }
        }
    }

    if (state.stage != Stage.HOME && state.stage != Stage.NOTE) {
        val busy by rememberUpdatedState(state.busy)
        ModalBottomSheet(
            onDismissRequest = callbacks.onClose,
            sheetState = rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = { it != SheetValue.Hidden || !busy },
            ),
            containerColor = MaterialTheme.colorScheme.surface,
            tonalElevation = 0.dp,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            contentWindowInsets = { WindowInsets.safeDrawing },
        ) {
            key(state.stage, state.note?.id, state.form?.id) {
                val scroll = rememberScrollState()
                LaunchedEffect(state.error, state.permission, voice.error, message) {
                    if (state.error != null || state.permission != null || voice.error != null || message != null) {
                        withFrameNanos { }
                        val bottom = scroll.maxValue
                        if (bottom != Int.MAX_VALUE) scroll.animateScrollTo(bottom)
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false)
                        .imePadding().verticalScroll(scroll)
                        .padding(start = 24.dp, end = 24.dp, bottom = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    val title = when (state.stage) {
                        Stage.HOME -> "Jot"
                        Stage.CAPTURE -> "Listening"
                        Stage.NOTE -> "Note"
                        Stage.ACTIONS -> "Add to"
                        Stage.DETAILS -> state.form?.kind?.label.orEmpty()
                        Stage.ANSWER -> "Answer"
                    }
                    SheetHeader(title, state.stage, !state.busy, callbacks.onClose)
                    if (state.thinking) LinearProgressIndicator(Modifier.fillMaxWidth())
                    when (state.stage) {
                        Stage.HOME -> Unit
                        Stage.CAPTURE -> LinearProgressIndicator(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        )
                        Stage.NOTE -> Unit
                        Stage.ACTIONS -> ActionChoices(state, callbacks)
                        Stage.DETAILS -> ActionDetails(state, callbacks)
                        Stage.ANSWER -> AnswerView(state, callbacks)
                    }
                    Feedback(state, voice, callbacks)
                    SnackbarHost(snackbar)
                }
            }
        }
    }
}

@Composable
internal fun SheetHeader(title: String, stage: Stage, enabled: Boolean, close: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = Modifier.weight(1f).semantics { heading() },
            style = MaterialTheme.typography.headlineSmall,
        )
        IconButton(onClick = close, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Outlined.Close, if (stage == Stage.CAPTURE) "Stop recording" else "Close")
        }
    }
}

@Composable
private fun AnswerView(state: JotUiState, callbacks: JotCallbacks) {
    Text(state.text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.answer?.let { answer ->
        Text(answer, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
    }
    state.sources.forEach { item ->
        HorizontalDivider()
        Text(
            item.note.text,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = !state.busy, role = Role.Button) { callbacks.onOpen(item) }
                .padding(vertical = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
    if (!state.thinking) {
        PrimaryAction("Done", enabled = !state.busy, modifier = Modifier.fillMaxWidth(), onClick = callbacks.onClose)
        TextButton(onClick = callbacks.onKeepQuestion, enabled = !state.busy, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
            Text("Save as note")
        }
    }
}

@Composable
private fun ActionChoices(state: JotUiState, callbacks: JotCallbacks) {
    Column {
        ActionKind.entries.forEach { kind ->
            val checked = kind in state.selected
            Row(
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
                    .toggleable(value = checked, enabled = !state.busy, role = Role.Checkbox) {
                        callbacks.onToggleAction(kind)
                    }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Checkbox(checked = checked, onCheckedChange = null, enabled = !state.busy)
                Column(Modifier.weight(1f)) {
                    Text(kind.label, style = MaterialTheme.typography.bodyLarge)
                    val detail = state.plan[kind]?.takeIf { checked }?.let { formSummary(it, state.text) }
                    if (detail != null) {
                        Text(
                            detail,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            if (kind != ActionKind.entries.last()) HorizontalDivider()
        }
    }
    if (!state.thinking && state.selected.any { it != ActionKind.REMINDER }) {
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(value = state.openWhenDone, enabled = !state.busy, role = Role.Checkbox, onValueChange = callbacks.onOpenWhenDone)
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Checkbox(checked = state.openWhenDone, onCheckedChange = null, enabled = !state.busy)
            Text("Open when done", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    PrimaryAction(
        if (state.selected.isEmpty()) "Done" else "Confirm",
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
        onClick = callbacks.onContinue,
    )
}

@Composable
internal fun Feedback(state: JotUiState, voice: VoiceEngineState, callbacks: JotCallbacks) {
    (state.error ?: voice.error)?.let { message ->
        Text(
            message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    state.permission?.let { permission ->
        val label = when (permission) {
            PermissionNeed.MICROPHONE -> "Allow microphone"
            PermissionNeed.NOTIFICATIONS -> "Allow notifications"
            PermissionNeed.EXACT_ALARMS -> "Allow exact reminders"
        }
        TextButton(
            onClick = callbacks.onRequestPermission,
            enabled = !state.busy,
            modifier = Modifier.heightIn(min = 48.dp),
        ) {
            Text(label)
        }
        if (permission == PermissionNeed.EXACT_ALARMS) {
            TextButton(
                onClick = callbacks.onApproximate,
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Use approximate time")
            }
        }
    }
}

@Composable
internal fun PrimaryAction(
    label: String,
    enabled: Boolean,
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.heightIn(min = 52.dp),
        enabled = enabled,
        shape = RoundedCornerShape(15.dp),
    ) {
        Text(label, textAlign = TextAlign.Center)
    }
}

package com.jot.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconToggleButton
import com.jot.app.companion.CompanionState
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SheetValue
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jot.app.capture.VoiceEngineState
import com.jot.app.data.ActionKind
import com.jot.app.data.ActionStatus
import com.jot.app.data.NoteItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JotScreen(
    notes: List<NoteItem>,
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        contentWindowInsets = WindowInsets.safeDrawing,
        snackbarHost = { if (state.stage == Stage.HOME) SnackbarHost(snackbar) },
    ) { padding ->
        NotesHome(
            notes,
            state,
            voice,
            companion,
            callbacks,
            Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding(),
        )
    }

    if (state.stage != Stage.HOME) {
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
                        Stage.NOTE -> NoteEditor(state, callbacks)
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
private fun NotesHome(
    notes: List<NoteItem>,
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
    callbacks: JotCallbacks,
    modifier: Modifier,
) {
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val closeSearch = {
        searching = false
        query = ""
        focusManager.clearFocus()
        keyboard?.hide()
    }
    BackHandler(enabled = searching && state.stage == Stage.HOME) { closeSearch() }
    val visibleNotes = remember(notes, query) {
        val needle = query.trim()
        if (needle.isEmpty()) notes else notes.filter { it.note.text.contains(needle, ignoreCase = true) }
    }

    Column(modifier) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Jot",
                modifier = Modifier.weight(1f).semantics { heading() },
                style = MaterialTheme.typography.headlineLarge,
            )
            CompanionButton(companion, enabled = !state.busy, onToggle = callbacks.onCompanionToggle)
            IconButton(
                onClick = {
                    if (searching) closeSearch() else searching = true
                },
                modifier = Modifier.size(48.dp),
            ) {
                if (searching) {
                    Icon(Icons.Outlined.Close, "Close search")
                } else {
                    Icon(Icons.Outlined.Search, "Search notes")
                }
            }
            IconButton(
                onClick = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    callbacks.onNew()
                },
                modifier = Modifier.size(48.dp),
                enabled = !state.busy,
            ) {
                Icon(Icons.Outlined.EditNote, "Write to Jot")
            }
        }
        if (searching) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("Search notes") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    keyboard?.hide()
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)
                    .focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) {
                focusRequester.requestFocus()
                keyboard?.show()
            }
        }

        LazyColumn(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (visibleNotes.isEmpty()) {
                item {
                    Text(
                        if (query.isBlank()) "No notes yet" else "No matches",
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 56.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
            itemsIndexed(visibleNotes, key = { _, item -> item.note.id }) { index, item ->
                NoteRow(item, enabled = !state.busy) {
                    focusManager.clearFocus()
                    keyboard?.hide()
                    callbacks.onOpen(item)
                }
                if (index < visibleNotes.lastIndex) {
                    HorizontalDivider(Modifier.padding(horizontal = 24.dp))
                }
            }
        }

        if (state.stage == Stage.HOME) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
                Feedback(state, voice, callbacks)
            }
        }
        if (!imeVisible) {
            CaptureControls(
                voice,
                state.busy,
                onWakeToggle = callbacks.onWakeToggle,
                onMic = callbacks.onMic,
            )
        }
    }
}

@Composable
private fun NoteRow(item: NoteItem, enabled: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 18.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            item.note.text.ifBlank { "Untitled note" },
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 4,
            overflow = TextOverflow.Ellipsis,
        )
        item.actions.filter { it.status == ActionStatus.PENDING || it.status == ActionStatus.SCHEDULED || it.status == ActionStatus.DONE }
            .forEach { action ->
                Text(
                    actionSummary(action),
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
    }
}

@Composable
private fun CaptureControls(
    voice: VoiceEngineState,
    busy: Boolean,
    onWakeToggle: (Boolean) -> Unit,
    onMic: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val enabled = !busy && !voice.loading && !voice.capturing && !voice.speaking
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            Row(
                modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(32.dp))
                    .background(if (voice.listening) colors.primaryContainer else colors.surfaceVariant)
                    .toggleable(
                        value = voice.listening,
                        enabled = enabled,
                        role = Role.Switch,
                        onValueChange = onWakeToggle,
                    )
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Hey Jot",
                    modifier = Modifier.weight(1f, fill = false),
                    color = if (voice.listening) colors.onPrimaryContainer else colors.onSurface,
                    style = MaterialTheme.typography.labelLarge,
                )
                Switch(checked = voice.listening, onCheckedChange = null)
            }
        }
        IconButton(
            onClick = onMic,
            enabled = enabled,
            modifier = Modifier.size(68.dp).clip(RoundedCornerShape(24.dp))
                .background(if (enabled) colors.primary else colors.surfaceVariant)
                .semantics { contentDescription = "Record a note" },
        ) {
            if (voice.loading) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    Icons.Outlined.Mic,
                    contentDescription = null,
                    tint = if (enabled) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

@Composable
private fun SheetHeader(title: String, stage: Stage, enabled: Boolean, close: () -> Unit) {
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
private fun NoteEditor(state: JotUiState, callbacks: JotCallbacks) {
    val draft = state.note?.draft != false
    var deleting by rememberSaveable { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    OutlinedTextField(
        value = state.text,
        onValueChange = callbacks.onTextChanged,
        modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
        label = { Text("Note text") },
        enabled = !state.busy,
        minLines = 3,
        maxLines = 8,
        textStyle = MaterialTheme.typography.bodyLarge,
    )
    LaunchedEffect(Unit) {
        if (!state.voiceSession && draft && state.text.isBlank()) focusRequester.requestFocus()
    }

    state.existingActions.filter { it.status != ActionStatus.CANCELLED }.forEach { action ->
        val canOpen = action.status == ActionStatus.PENDING ||
            action.kind == ActionKind.REMINDER || action.kind == ActionKind.MESSAGE
        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .clickable(enabled = canOpen && !state.busy, role = Role.Button) {
                    focusManager.clearFocus()
                    callbacks.onOpenAction(action)
                }
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (action.kind == ActionKind.MESSAGE && action.body != null && canOpen) {
                    "Open Messages"
                } else {
                    actionSummary(action)
                },
                modifier = Modifier.weight(1f),
                color = if (canOpen) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (canOpen) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, contentDescription = null)
        }
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.note != null) {
            IconButton(
                onClick = { deleting = true },
                enabled = !state.busy,
                modifier = Modifier.size(48.dp),
            ) {
                Icon(Icons.Outlined.Delete, "Delete note", tint = MaterialTheme.colorScheme.error)
            }
        }
        if (!draft) {
            TextButton(
                onClick = {
                    focusManager.clearFocus()
                    callbacks.onContinue()
                },
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("Add to")
            }
        }
        PrimaryAction(
            if (draft) "Confirm" else "Save",
            enabled = state.text.isNotBlank() && !state.busy,
            modifier = Modifier.weight(1f),
        ) {
            focusManager.clearFocus()
            callbacks.onConfirm()
        }
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete note?") },
            dismissButton = {
                TextButton(onClick = { deleting = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text("Cancel")
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleting = false
                        callbacks.onDeleteNote()
                    },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
        )
    }
}

@Composable
private fun CompanionButton(companion: CompanionState, enabled: Boolean, onToggle: (Boolean) -> Unit) {
    var asking by rememberSaveable { mutableStateOf<Boolean?>(null) }
    val on = companion is CompanionState.Ready || companion is CompanionState.Downloading
    IconToggleButton(
        checked = on,
        onCheckedChange = { asking = it },
        enabled = enabled,
        modifier = Modifier.size(48.dp).semantics { contentDescription = "Companion" },
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (companion is CompanionState.Downloading) {
                val progress = companion.progress
                if (progress == null || companion.waitingForWifi) CircularProgressIndicator(Modifier.size(34.dp), strokeWidth = 2.dp)
                else CircularProgressIndicator(progress = { progress }, modifier = Modifier.size(34.dp), strokeWidth = 2.dp)
            }
            Icon(
                if (on) Icons.Filled.AutoAwesome else Icons.Outlined.AutoAwesome,
                contentDescription = null,
                tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(if (companion is CompanionState.Downloading) 18.dp else 24.dp),
            )
        }
    }
    when (asking) {
        true -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Download companion?") },
            text = { Text("${"%.1f".format(com.jot.app.companion.CompanionModel.SIZE / 1e9)} GB over Wi-Fi") },
            confirmButton = { TextButton(onClick = { asking = null; onToggle(true) }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download") } },
            dismissButton = { TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
        false -> AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(if (companion is CompanionState.Downloading) "Stop download?" else "Remove companion?") },
            confirmButton = { TextButton(onClick = { asking = null; onToggle(false) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(if (companion is CompanionState.Downloading) "Stop" else "Remove", color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { asking = null }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") } },
        )
        null -> Unit
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
                    val detail = state.plan[kind]?.takeIf { checked }?.let { formSummary(it) }
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
    PrimaryAction(
        if (state.selected.isEmpty()) "Done" else "Confirm",
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
        onClick = callbacks.onContinue,
    )
}

@Composable
private fun Feedback(state: JotUiState, voice: VoiceEngineState, callbacks: JotCallbacks) {
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

package com.jot.app.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.jot.app.capture.VoiceEngineState
import com.jot.app.companion.CompanionState
import com.jot.app.core.CompanionText
import com.jot.app.data.ActionKind
import com.jot.app.data.ActionStatus
import java.util.Date

private val noteTypography = VisualTransformation { raw ->
    val titleEnd = raw.text.indexOf('\n').let { if (it == -1) raw.length else it }
    val styled = buildAnnotatedString {
        append(raw)
        if (titleEnd > 0) addStyle(SpanStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp), 0, titleEnd)
    }
    TransformedText(styled, OffsetMapping.Identity)
}

@Composable
internal fun NoteEditor(
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
    callbacks: JotCallbacks,
    modifier: Modifier,
) {
    val note = state.note ?: return
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val requester = remember { FocusRequester() }
    var more by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }
    var discarding by rememberSaveable { mutableStateOf(false) }
    val changed = if (note.draft) state.text.isNotBlank() else state.text != note.text
    val close = {
        if (!state.busy) {
            if (changed) discarding = true else callbacks.onClose()
        }
    }
    BackHandler(onBack = close)
    val action = when {
        !note.draft && !changed -> "Done"
        note.draft && state.voiceSession -> "Confirm"
        note.draft && companion == CompanionState.Ready && CompanionText.asksAboutNotes(state.text) -> "Ask"
        else -> "Save"
    }
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = close, enabled = !state.busy) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back to notes", tint = colors.primary)
            }
            Spacer(Modifier.weight(1f))
            if (!note.draft) {
                Box {
                    IconButton(onClick = { more = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.MoreHoriz, "Note actions", tint = colors.primary)
                    }
                    DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                        DropdownMenuItem(text = { Text("Add to") }, enabled = state.text.isNotBlank(), onClick = {
                            more = false
                            focus.clearFocus()
                            callbacks.onContinue()
                        })
                        DropdownMenuItem(text = { Text("Delete", color = colors.error) }, onClick = {
                            more = false
                            deleting = true
                        })
                    }
                }
            }
            TextButton(
                onClick = {
                    focus.clearFocus()
                    if (!note.draft && !changed) callbacks.onClose() else callbacks.onConfirm()
                },
                enabled = state.text.isNotBlank() && !state.busy, modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(action, style = MaterialTheme.typography.labelLarge) }
        }
        if (state.thinking) LinearProgressIndicator(Modifier.fillMaxWidth())
        val scroll = rememberScrollState()
        LaunchedEffect(state.error, state.permission, voice.error) {
            if (state.error != null || state.permission != null || voice.error != null) {
                withFrameNanos { }
                if (scroll.maxValue != Int.MAX_VALUE) scroll.animateScrollTo(scroll.maxValue)
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 22.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val stamp = Date(note.updatedAt)
            Text(
                DateFormat.getMediumDateFormat(context).format(stamp) + "   " + DateFormat.getTimeFormat(context).format(stamp),
                Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center, color = colors.onSurfaceVariant,
            )
            BasicTextField(
                state.text, callbacks.onTextChanged,
                modifier = Modifier.fillMaxWidth().heightIn(min = 156.dp).focusRequester(requester)
                    .semantics { contentDescription = "Note text" },
                enabled = !state.busy, textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                visualTransformation = noteTypography, cursorBrush = SolidColor(colors.primary),
                decorationBox = { field ->
                    Box(Modifier.fillMaxWidth()) {
                        if (state.text.isEmpty()) Text("Write a note…", color = colors.onSurfaceVariant, style = MaterialTheme.typography.headlineSmall)
                        field()
                    }
                },
            )
            state.existingActions.filter { it.status != ActionStatus.CANCELLED }.forEach { item ->
                val canOpen = item.status == ActionStatus.PENDING || item.kind == ActionKind.REMINDER || item.kind == ActionKind.MESSAGE
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .then(if (canOpen) Modifier.clickable(enabled = !state.busy, role = Role.Button) {
                            focus.clearFocus()
                            callbacks.onOpenAction(item)
                        } else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (item.kind == ActionKind.MESSAGE && item.body != null && canOpen) "Open Messages" else actionSummary(item),
                        Modifier.weight(1f), color = if (canOpen) colors.primary else colors.onSurfaceVariant,
                    )
                    if (canOpen) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.primary)
                }
            }
            Feedback(state, voice, callbacks)
        }
    }
    LaunchedEffect(Unit) {
        if (note.draft && !state.voiceSession && state.text.isBlank()) requester.requestFocus()
    }
    if (deleting || discarding) {
        val label = if (deleting) "Delete" else "Discard"
        val dismiss = { deleting = false; discarding = false }
        AlertDialog(
            onDismissRequest = dismiss,
            title = { Text(if (deleting) "Delete note?" else if (note.draft) "Discard note?" else "Discard changes?") },
            dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } },
            confirmButton = {
                TextButton(onClick = {
                    val deleteNote = deleting
                    dismiss()
                    if (deleteNote) callbacks.onDeleteNote() else callbacks.onClose()
                }) { Text(label, color = colors.error) }
            },
        )
    }
}

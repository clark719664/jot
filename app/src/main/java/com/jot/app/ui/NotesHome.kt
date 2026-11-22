package com.jot.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.MicNone
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.jot.app.capture.VoiceEngineState
import com.jot.app.companion.CompanionAccount
import com.jot.app.companion.CompanionState
import com.jot.app.data.ActionStatus
import com.jot.app.data.NoteItem
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date

@Composable
internal fun NotesHome(
    notes: List<NoteItem>,
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
    account: CompanionAccount?,
    billingBusy: Boolean,
    callbacks: JotCallbacks,
    modifier: Modifier,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var settings by rememberSaveable { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val colors = MaterialTheme.colorScheme
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now(zone)
    val locale = LocalConfiguration.current.locales[0]
    val dateFormat = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMMd"), locale)
    }
    val yearFormat = remember(locale) {
        DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "yMMMMd"), locale)
    }
    val groups = remember(notes, query, zone) {
        val term = query.trim()
        notes.filter { it.note.text.contains(term, ignoreCase = true) }
            .groupBy { Instant.ofEpochMilli(it.note.updatedAt).atZone(zone).toLocalDate() }
    }

    Column(modifier) {
        LazyColumn(modifier = Modifier.weight(1f), contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp)) {
            item(key = "heading") {
                Row(
                    Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Notes", Modifier.weight(1f).semantics { heading() }, style = MaterialTheme.typography.headlineLarge)
                    IconButton(onClick = { focus.clearFocus(); settings = true }, enabled = !state.busy) {
                        Icon(Icons.Outlined.Settings, "Settings", tint = colors.onSurfaceVariant)
                    }
                }
            }
            if (notes.isNotEmpty() || query.isNotEmpty()) {
                item(key = "search") { NoteSearch(query) { query = it } }
            }
            if (groups.isEmpty()) {
                item(key = "empty") {
                    Column(
                        Modifier.fillMaxWidth().fillParentMaxHeight(.6f),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(
                            if (query.isBlank()) Icons.AutoMirrored.Outlined.Notes else Icons.Outlined.Search,
                            null, Modifier.size(36.dp), tint = colors.onSurfaceVariant,
                        )
                        Text(
                            if (query.isBlank()) "No notes yet" else "No matches",
                            Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleMedium,
                            color = colors.onSurfaceVariant, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
            groups.forEach { (day, entries) ->
                item(key = "day-$day") {
                    val label = when (day) {
                        today -> "Today"
                        today.minusDays(1) -> "Yesterday"
                        else -> day.format(if (day.year == today.year) dateFormat else yearFormat)
                    }
                    Text(
                        label, Modifier.padding(start = 2.dp, top = 22.dp, bottom = 7.dp).semantics { heading() },
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                itemsIndexed(entries, key = { _, item -> item.note.id }) { index, item ->
                    NoteRow(item, index == 0, index == entries.lastIndex, !state.busy) {
                        focus.clearFocus()
                        callbacks.onOpen(item)
                    }
                }
            }
        }
        if (state.stage == Stage.HOME && !settings) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) { Feedback(state, voice, callbacks) }
        }
        HorizontalDivider(color = colors.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            IconButton(
                onClick = { focus.clearFocus(); callbacks.onMic() },
                enabled = !state.busy && !voice.loading && !voice.capturing && !voice.speaking,
            ) {
                if (voice.loading) {
                    CircularProgressIndicator(Modifier.size(24.dp).semantics { contentDescription = "Preparing microphone" }, strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Outlined.MicNone, "Record a note", tint = colors.primary)
                }
            }
            FilledTonalButton(
                onClick = { focus.clearFocus(); callbacks.onNew() },
                enabled = !state.busy,
                modifier = Modifier.heightIn(min = 48.dp),
                contentPadding = PaddingValues(horizontal = 17.dp, vertical = 8.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                    containerColor = colors.primaryContainer, contentColor = colors.onPrimaryContainer,
                ),
            ) {
                Icon(Icons.Outlined.Edit, null, Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text("New note", style = MaterialTheme.typography.labelLarge)
            }
        }
    }
    if (settings && state.stage == Stage.HOME) {
        JotSettings(state, voice, companion, account, billingBusy, callbacks, onClose = { settings = false })
    }
}

@Composable
private fun NoteSearch(value: String, onChange: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    BasicTextField(
        value, onChange,
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(colors.surfaceVariant)
            .semantics { contentDescription = "Search notes" },
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyLarge.copy(color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        decorationBox = { field ->
            Row(
                Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Search, null, Modifier.size(21.dp), tint = colors.onSurfaceVariant)
                Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                    if (value.isEmpty()) Text("Search", style = MaterialTheme.typography.bodyLarge, color = colors.onSurfaceVariant)
                    field()
                }
                if (value.isNotEmpty()) IconButton(onClick = { onChange("") }) {
                    Icon(Icons.Outlined.Close, "Clear search", tint = colors.onSurfaceVariant)
                }
            }
        },
    )
}

@Composable
private fun NoteRow(item: NoteItem, first: Boolean, last: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val time = DateFormat.getTimeFormat(context).format(Date(item.note.updatedAt))
    val lines = remember(item.note.text) { item.note.text.lineSequence().map(String::trim).filter(String::isNotEmpty).take(2).toList() }
    val preview = lines.getOrNull(1)
    val top = if (first) 15.dp else 0.dp
    val bottom = if (last) 15.dp else 0.dp
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(top, top, bottom, bottom)).background(colors.surface)) {
        Column(
            Modifier.fillMaxWidth().clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                lines.firstOrNull() ?: "Untitled note", style = MaterialTheme.typography.titleMedium,
                maxLines = if (preview == null) 2 else 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (preview == null) time else "$time   $preview", style = MaterialTheme.typography.bodyMedium,
                color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            item.actions.filter { it.status == ActionStatus.PENDING || it.status == ActionStatus.SCHEDULED || it.status == ActionStatus.DONE }.forEach { action ->
                Text(actionSummary(action), style = MaterialTheme.typography.bodyMedium, color = colors.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (!last) HorizontalDivider(Modifier.padding(start = 16.dp), color = colors.outlineVariant)
    }
}

package com.jot.app.ui

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DisplayMode
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.jot.app.data.ActionKind
import com.jot.app.data.ActionRecord
import com.jot.app.data.ActionStatus
import java.text.DateFormatSymbols
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import com.jot.app.core.JotLines
import com.jot.app.core.TimeParser
import java.util.Date
import java.util.TimeZone

private val PickerDialogProperties = DialogProperties(
    usePlatformDefaultWidth = false,
    decorFitsSystemWindows = false,
)

internal val ActionKind.label: String
    get() = when (this) {
        ActionKind.ALARM -> "Alarm"
        ActionKind.CALENDAR -> "Calendar"
        ActionKind.REMINDER -> "Reminder"
        ActionKind.MESSAGE -> "Message"
    }

@Composable
internal fun actionSummary(action: ActionRecord): String {
    val context = LocalContext.current
    val title = when (action.status) {
        ActionStatus.PENDING -> when (action.kind) {
            ActionKind.ALARM -> "Alarm pending"
            ActionKind.CALENDAR -> "Calendar pending"
            ActionKind.REMINDER -> "Reminder pending"
            ActionKind.MESSAGE -> if (action.body == null) "Message pending" else "Message draft"
        }
        ActionStatus.SCHEDULED -> if (action.exact) action.kind.label else "Approximate reminder"
        ActionStatus.HANDED_OFF -> when (action.kind) {
            ActionKind.ALARM -> "Opened in Clock"
            ActionKind.CALENDAR -> "Opened in Calendar"
            ActionKind.MESSAGE -> "Opened in Messages"
            ActionKind.REMINDER -> action.kind.label
        }
        ActionStatus.DONE -> when (action.kind) {
            ActionKind.ALARM -> "Alarm set"
            ActionKind.CALENDAR -> "Added to Calendar"
            else -> action.kind.label
        }
        ActionStatus.CANCELLED -> action.kind.label
    }
    val timestamp = action.atMillis ?: return title
    if (action.status != ActionStatus.PENDING && action.status != ActionStatus.SCHEDULED && action.status != ActionStatus.DONE) return title
    val instant = Date(timestamp)
    val time = DateFormat.getTimeFormat(context).format(instant)
    if (action.kind == ActionKind.ALARM) return "$title · $time"
    val date = DateFormat.getMediumDateFormat(context).format(instant)
    return "$title · $date, $time"
}

@Composable
internal fun formSummary(form: ActionForm, note: String): String? {
    val context = LocalContext.current
    val zone = java.time.ZoneId.systemDefault()
    val time = form.time?.let { DateFormat.getTimeFormat(context).format(Date.from(java.time.LocalDate.now().atTime(it).atZone(zone).toInstant())) }
    val date = form.date?.takeIf { form.kind != ActionKind.ALARM }?.let { DateFormat.getMediumDateFormat(context).format(Date.from(it.atStartOfDay(zone).toInstant())) }
    val title = form.body.trim().takeIf { it.isNotEmpty() }
    if (form.kind == ActionKind.MESSAGE) return title
    val repeat = JotLines.repeats(TimeParser.repeatDays(note)).takeIf { form.kind == ActionKind.ALARM }
    val whenText = listOfNotNull(date, time, repeat).joinToString(", ").ifEmpty { null }
    return listOfNotNull(if (form.kind == ActionKind.ALARM) whenText else title, if (form.kind == ActionKind.ALARM) title else whenText).joinToString(" · ").ifEmpty { null }
}

@Composable
internal fun ActionDetails(state: JotUiState, callbacks: JotCallbacks) {
    val form = state.form ?: return
    val focusManager = LocalFocusManager.current
    val enabled = !state.busy
    if (form.kind != ActionKind.MESSAGE) {
        OutlinedTextField(
            value = form.body,
            onValueChange = { callbacks.onFormChanged(form.copy(body = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Title") },
            maxLines = 2,
            enabled = enabled,
        )
    }

    if (form.kind == ActionKind.MESSAGE) {
        OutlinedTextField(
            value = form.body,
            onValueChange = { callbacks.onFormChanged(form.copy(body = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Message text") },
            minLines = 3,
            maxLines = 8,
            enabled = enabled,
        )
        OutlinedTextField(
            value = form.recipient,
            onValueChange = { callbacks.onFormChanged(form.copy(recipient = it)) },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Phone number (optional)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = enabled,
        )
    } else {
        if (form.kind != ActionKind.ALARM) {
            DateField("Date", form.date, enabled) {
                callbacks.onFormChanged(form.copy(date = it))
            }
        }
        TimeField("Time", form.time, enabled) {
            callbacks.onFormChanged(form.copy(time = it))
        }
        if (form.kind == ActionKind.CALENDAR) {
            DateField("End date", form.endDate, enabled) {
                callbacks.onFormChanged(form.copy(endDate = it))
            }
            TimeField("End time", form.endTime, enabled) {
                callbacks.onFormChanged(form.copy(endTime = it))
            }
        }
    }

    val label = when (form.kind) {
        ActionKind.ALARM -> "Open Clock"
        ActionKind.CALENDAR -> "Open Calendar"
        ActionKind.REMINDER -> "Save reminder"
        ActionKind.MESSAGE -> "Save draft"
    }
    PrimaryAction(label, enabled, Modifier.fillMaxWidth()) {
        focusManager.clearFocus()
        callbacks.onSaveAction()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateField(
    label: String,
    value: LocalDate?,
    enabled: Boolean,
    onChange: (LocalDate) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val configuration = LocalConfiguration.current
    val locale = configuration.locales[0]
    val formatted = value?.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
    PickerField(label, formatted, enabled) { showPicker = true }
    if (showPicker) {
        val compact = configuration.screenWidthDp < 360 || configuration.fontScale > 1.3f
        val initial = value?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli()
        val picker = rememberDatePickerState(
            initialSelectedDateMillis = initial,
            initialDisplayMode = if (compact) DisplayMode.Input else DisplayMode.Picker,
        )
        val close = { showPicker = false }
        val confirmButton: @Composable () -> Unit = {
            TextButton(
                onClick = {
                    picker.selectedDateMillis?.let {
                        onChange(Instant.ofEpochMilli(it).atOffset(ZoneOffset.UTC).toLocalDate())
                    }
                    close()
                },
                enabled = picker.selectedDateMillis != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("OK")
            }
        }
        val dismissButton: @Composable () -> Unit = {
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        }
        if (compact) {
            AlertDialog(
                onDismissRequest = close,
                modifier = Modifier.safeDrawingPadding().padding(horizontal = 12.dp).widthIn(max = 360.dp),
                title = { Text(label) },
                text = {
                    DatePicker(
                        state = picker,
                        title = null,
                        headline = null,
                        showModeToggle = false,
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                    )
                },
                confirmButton = confirmButton,
                dismissButton = dismissButton,
                properties = PickerDialogProperties,
            )
        } else {
            DatePickerDialog(
                onDismissRequest = close,
                confirmButton = confirmButton,
                dismissButton = dismissButton,
            ) {
                DatePicker(state = picker, modifier = Modifier.verticalScroll(rememberScrollState()))
            }
        }
    }
}

@Composable
private fun TimeField(
    label: String,
    value: LocalTime?,
    enabled: Boolean,
    onChange: (LocalTime) -> Unit,
) {
    var showPicker by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    val formatter = DateFormat.getTimeFormat(context).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val formatted = value?.let { formatter.format(Date(it.toSecondOfDay() * 1_000L)) }
    PickerField(label, formatted, enabled) { showPicker = true }
    if (showPicker) {
        TimeDialog(label, value ?: LocalTime.now(), { showPicker = false }) {
            onChange(it)
            showPicker = false
        }
    }
}

@Composable
private fun TimeDialog(
    label: String,
    initial: LocalTime,
    close: () -> Unit,
    confirm: (LocalTime) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val is24Hour = DateFormat.is24HourFormat(LocalContext.current)
    val hourRange = if (is24Hour) 0..23 else 1..12
    val initialHour = if (is24Hour) initial.hour else ((initial.hour + 11) % 12) + 1
    val digits = NumberFormat.getIntegerInstance(locale).apply { minimumIntegerDigits = 2 }
    var hour by rememberSaveable { mutableStateOf(digits.format(initialHour)) }
    var minute by rememberSaveable { mutableStateOf(digits.format(initial.minute)) }
    var afternoon by rememberSaveable { mutableStateOf(initial.hour >= 12) }
    val selectedHour = hour.toIntOrNull()?.takeIf { it in hourRange }
    val selectedMinute = minute.toIntOrNull()?.takeIf { it in 0..59 }
    AlertDialog(
        onDismissRequest = close,
        modifier = Modifier.safeDrawingPadding().padding(horizontal = 12.dp).widthIn(max = 360.dp),
        title = { Text(label) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = hour,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) hour = it },
                    label = { Text("Hour") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = hour.isNotEmpty() && selectedHour == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                OutlinedTextField(
                    value = minute,
                    onValueChange = { if (it.length <= 2 && it.all(Char::isDigit)) minute = it },
                    label = { Text("Minute") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = minute.isNotEmpty() && selectedMinute == null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                if (!is24Hour) {
                    Column(Modifier.selectableGroup()) {
                        DateFormatSymbols.getInstance(locale).amPmStrings.forEachIndexed { index, period ->
                            val isAfternoon = index == 1
                            Row(
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
                                    .selectable(
                                        selected = afternoon == isAfternoon,
                                        role = Role.RadioButton,
                                        onClick = { afternoon = isAfternoon },
                                    ),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                RadioButton(selected = afternoon == isAfternoon, onClick = null)
                                Text(period)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (selectedHour != null && selectedMinute != null) {
                        val periodOffset = if (afternoon) 12 else 0
                        val hourOfDay = if (is24Hour) selectedHour else selectedHour % 12 + periodOffset
                        confirm(LocalTime.of(hourOfDay, selectedMinute))
                    }
                },
                enabled = selectedHour != null && selectedMinute != null,
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text("OK")
            }
        },
        dismissButton = {
            TextButton(onClick = close, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel") }
        },
        properties = PickerDialogProperties,
    )
}

@Composable
private fun PickerField(
    label: String,
    value: String?,
    enabled: Boolean,
    open: () -> Unit,
) {
    OutlinedButton(
        onClick = open,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (value != null) Text(value, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

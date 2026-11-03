package com.jot.app

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.jot.app.capture.*
import com.jot.app.core.*
import com.jot.app.data.*
import com.jot.app.platform.*
import com.jot.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.UUID

class JotController(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = JotStore.get(context)
    private val androidActions = AndroidActions(context, store)
    private val mutable = MutableStateFlow(JotUiState())
    val state: StateFlow<JotUiState> = mutable.asStateFlow()
    val notes = store.observeNotes().stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())
    private val queue = ArrayDeque<ActionRecord>()
    private var draftJob: Job? = null
    private var formJob: Job? = null
    private var baseAction: ActionRecord? = null
    private var changingMessage = false

    init {
        scope.launch { VoiceSignals.events.collect { event ->
            when (event) {
                is VoiceEvent.Utterance -> handleSpeech(event.text)
                is VoiceEvent.Failure -> { update { copy(error = event.message, busy = false, stage = if (stage == Stage.CAPTURE) Stage.NOTE else stage) } }
                VoiceEvent.Wake -> newNote(true, captureAlreadyRunning = true)
            }
        } }
        scope.launch { store.draft()?.takeIf { it.text.isNotBlank() }?.let { recovered ->
            if (state.value.stage == Stage.HOME) mutable.value = JotUiState(stage = Stage.NOTE, note = recovered, text = recovered.text)
        } }
    }

    private fun update(change: JotUiState.() -> JotUiState) { mutable.value = state.value.change() }
    private fun launch(block: suspend () -> Unit) { scope.launch { try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) { update { copy(error = e.message ?: "Try again.", busy = false) } } } }
    fun notice(text: String) { update { copy(error = text, busy = false) } }
    fun dismissMessage() { update { copy(message = null) } }

    fun newNote(voice: Boolean = false, captureAlreadyRunning: Boolean = false) {
        if (state.value.busy) { if (captureAlreadyRunning) VoiceService.send(context, VoiceService.RESUME_WAKE); return }
        update { copy(busy = true) }
        launch {
        draftJob?.cancel()
        state.value.note?.takeIf { it.draft && state.value.text.isNotBlank() }?.let { store.saveNote(it.copy(text = state.value.text, updatedAt = System.currentTimeMillis())) }
        queue.clear(); changingMessage = false
        val now = System.currentTimeMillis()
        val note = NoteRecord(UUID.randomUUID().toString(), "", now, now, draft = true)
        store.saveNote(note)
        mutable.value = JotUiState(stage = if (voice) Stage.CAPTURE else Stage.NOTE, note = note, voiceSession = voice)
        if (voice && !captureAlreadyRunning) VoiceService.send(context, VoiceService.CAPTURE)
    } }

    fun updateText(value: String) {
        update { copy(text = value, error = null) }
        draftJob?.cancel()
        val note = state.value.note ?: return
        if (note.draft) draftJob = scope.launch { delay(250); store.saveNote(note.copy(text = value, updatedAt = System.currentTimeMillis())) }
    }

    fun open(item: NoteItem) {
        if (state.value.busy) return
        VoiceService.send(context, VoiceService.RESUME_WAKE)
        queue.clear(); mutable.value = JotUiState(stage = Stage.NOTE, note = item.note, text = item.note.text, existingActions = item.actions)
    }
    fun openNote(id: String) { launch { store.note(id)?.let { open(NoteItem(it, store.actions(id))) } } }

    fun confirm() {
        if (state.value.busy) return
        update { copy(busy = true) }
        launch {
            val before = state.value
            val note = before.note ?: return@launch
            val text = before.text.trim()
            if (text.isEmpty()) { update { copy(error = "Enter a note.", busy = false) }; return@launch }
            draftJob?.cancelAndJoin()
            val saved = note.copy(text = text, updatedAt = System.currentTimeMillis(), draft = false)
            store.saveNote(saved)
            if (!note.draft) { finish("Saved"); return@launch }
            update { copy(note = saved, text = text, stage = Stage.ACTIONS, selected = emptySet(), busy = false, error = null) }
            if (before.voiceSession) ask("Add to an alarm, calendar, reminder, or message? You can choose more than one, or say done.")
        }
    }

    fun close() {
        if (state.value.busy) return
        val before = state.value
        update { copy(busy = true) }
        launch {
        draftJob?.cancelAndJoin()
        formJob?.cancelAndJoin()
        if (before.form != null && before.note != null && baseAction?.status == ActionStatus.PENDING) store.saveAction(record(before.form, before.note.id, false))
        if (before.note?.draft == true) store.deleteNote(before.note.id)
        queue.clear(); mutable.value = JotUiState()
        VoiceService.send(context, VoiceService.RESUME_WAKE)
    } }

    fun deleteNote() {
        if (state.value.busy) return
        update { copy(busy = true) }
        launch {
        val note = state.value.note ?: return@launch
        draftJob?.cancelAndJoin()
        store.actions(note.id).forEach { androidActions.cancel(it) }
        store.deleteNote(note.id); queue.clear(); mutable.value = JotUiState(message = "Deleted")
        VoiceService.send(context, VoiceService.RESUME_WAKE)
    } }
    fun toggleAction(kind: ActionKind) { update { copy(selected = if (kind in selected) selected - kind else selected + kind) } }

    fun continueActions() {
        if (state.value.busy) return
        if (state.value.stage == Stage.NOTE) {
            update { copy(busy = true) }
            launch { val current = state.value; val note = current.note ?: return@launch
                val saved = note.copy(text = current.text.trim(), updatedAt = System.currentTimeMillis(), draft = false)
                if (saved.text.isBlank()) { notice("Enter a note."); return@launch }
                draftJob?.cancelAndJoin(); store.saveNote(saved)
                update { copy(note = saved, stage = Stage.ACTIONS, selected = emptySet(), existingActions = emptyList(), busy = false) }
            }; return
        }
        update { copy(busy = true) }
        launch {
            val note = state.value.note ?: return@launch
            val selected = state.value.selected.toList()
            if (selected.isEmpty()) { finish("Note saved"); return@launch }
            queue.clear()
            for (kind in selected) {
                val action = ActionRecord(UUID.randomUUID().toString(), note.id, kind)
                store.saveAction(action); queue.addLast(action)
            }
            nextForm()
        }
    }

    private suspend fun nextForm() {
        if (queue.isEmpty()) { finish("Saved"); return }
        val action = queue.removeFirst()
        baseAction = action
        val note = state.value.note ?: return
        val suggestion = TimeParser.suggest(note.text)
        val start = action.atMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val end = action.endMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val date = start?.toLocalDate() ?: suggestion.date
        val time = start?.toLocalTime() ?: suggestion.time
        val defaultEnd = if (date != null && time != null) LocalDateTime.of(date, time).plusMinutes(30) else null
        val form = ActionForm(action.id, action.kind, date, time, end?.toLocalDate() ?: defaultEnd?.toLocalDate(), end?.toLocalTime() ?: defaultEnd?.toLocalTime(), action.body ?: note.text, action.recipient.orEmpty())
        update { copy(stage = Stage.DETAILS, form = form, busy = false, error = null, permission = null) }
        if (state.value.voiceSession) promptForm()
    }

    fun updateForm(form: ActionForm) {
        if (state.value.busy) return
        update { copy(form = form, error = null, permission = null) }
        val note = state.value.note ?: return
        formJob?.cancel()
        if (baseAction?.status == ActionStatus.PENDING) formJob = scope.launch { delay(200); store.saveAction(record(form, note.id, validate = false)) }
    }

    private fun record(form: ActionForm, noteId: String, validate: Boolean): ActionRecord {
        val now = ZonedDateTime.now()
        var date = form.date
        if (form.kind == ActionKind.ALARM && form.time != null) {
            date = now.toLocalDate()
            if (!form.time.isAfter(now.toLocalTime())) date = date.plusDays(1)
        }
        val at = if (date != null && form.time != null) runCatching { ScheduleTimes.resolve(date, form.time) }.getOrElse { if (validate) throw it else null } else null
        val end = if (form.endDate != null && form.endTime != null) runCatching { ScheduleTimes.resolve(form.endDate, form.endTime) }.getOrElse { if (validate) throw it else null } else null
        if (validate && form.kind != ActionKind.MESSAGE) {
            require(form.time != null) { "Choose a time." }
            require(at != null) { "Choose a date." }
            require(at > System.currentTimeMillis()) { "Choose a future date and time." }
            if (form.kind == ActionKind.CALENDAR) require(end != null && end > at) { "End must follow start." }
        }
        if (validate && form.kind == ActionKind.MESSAGE) require(form.body.isNotBlank()) { "Enter a message." }
        return ActionRecord(form.id, noteId, form.kind, atMillis = at, endMillis = end, body = form.body.trim(), recipient = form.recipient.trim().takeIf { it.isNotEmpty() })
    }

    fun saveAction(activity: Activity? = null, approximate: Boolean = false) {
        if (state.value.busy) return
        update { copy(busy = true, error = null) }
        launch {
            val before = state.value
            val note = before.note ?: return@launch
            val form = before.form ?: return@launch
            val action = record(form, note.id, validate = true)
            formJob?.cancelAndJoin()
            if (baseAction?.status != ActionStatus.SCHEDULED) store.saveAction(action)
            when (action.kind) {
                ActionKind.REMINDER -> when (val result = androidActions.schedule(note, action, approximate)) {
                    is ScheduleResult.Scheduled -> Unit
                    ScheduleResult.NeedsExactPermission -> { update { copy(permission = PermissionNeed.EXACT_ALARMS, busy = false) }; if (before.voiceSession) { speak("Open Jot to allow exact reminders, or say approximate time.", listen = true) }; return@launch }
                    ScheduleResult.NeedsNotifications -> { update { copy(permission = PermissionNeed.NOTIFICATIONS, busy = false) }; if (before.voiceSession) speak("Open Jot to allow reminder notifications."); return@launch }
                    is ScheduleResult.Failed -> { notice(result.message); return@launch }
                }
                ActionKind.MESSAGE -> Unit
                else -> if (activity != null && !activity.isFinishing) {
                    val result = androidActions.dispatch(activity, note, action)
                    if (result.isSuccess) store.saveAction(action.copy(status = ActionStatus.HANDED_OFF))
                    else { notice(result.exceptionOrNull()?.message ?: "No app can open this action."); return@launch }
                } else notifyPending(note)
            }
            nextForm()
        }
    }

    fun openAction(requested: ActionRecord, activity: Activity) {
        if (state.value.busy) return
        update { copy(busy = true) }
        launch {
        val action = store.action(requested.id) ?: run { notice("This action no longer exists."); return@launch }
        if (action.status == ActionStatus.CANCELLED) { notice("This action was cancelled."); return@launch }
        val note = store.note(action.noteId) ?: run { notice("This note no longer exists."); return@launch }
        if (action.kind == ActionKind.REMINDER || action.kind != ActionKind.MESSAGE && (action.atMillis == null || action.atMillis <= System.currentTimeMillis()) || action.kind == ActionKind.MESSAGE && action.body == null) {
            queue.clear(); queue.add(action)
            update { copy(note = note, text = note.text, voiceSession = false) }; nextForm(); return@launch
        }
        androidActions.dispatch(activity, note, action).fold(
            onSuccess = { store.saveAction(action.copy(status = ActionStatus.HANDED_OFF)); val actions = store.actions(note.id); update { copy(existingActions = actions, busy = false, message = "Opened ${if (action.kind == ActionKind.MESSAGE) "Messages" else if (action.kind == ActionKind.ALARM) "Clock" else "Calendar"}") } },
            onFailure = { notice(it.message ?: "No app can open this action.") }
        )
    } }

    private fun notifyPending(note: NoteRecord) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("jot_actions", "Pending actions", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val intent = Intent(context, MainActivity::class.java).putExtra(AndroidActions.EXTRA_NOTE_ID, note.id).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val pending = PendingIntent.getActivity(context, note.id.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        try { manager.notify(note.id.hashCode(), NotificationCompat.Builder(context,"jot_actions").setSmallIcon(R.drawable.ic_mic).setContentTitle("Finish in Jot").setContentText("Your note and actions are saved.").setContentIntent(pending).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build()) } catch (_: SecurityException) { }
    }

    private fun finish(message: String) {
        val voice = state.value.voiceSession
        queue.clear(); mutable.value = JotUiState(message = message)
        if (voice) speak(message)
    }
    private fun ask(text: String) = speak(text, listen = true)
    private fun speak(text: String, listen: Boolean = false) { VoiceService.send(context, if (listen) VoiceService.SPEAK_LISTEN else VoiceService.SPEAK, text) }

    private fun promptForm() {
        val form = state.value.form ?: return
        if (form.kind == ActionKind.MESSAGE) { ask("Message draft. ${form.body}. Say confirm or change it."); return }
        if (form.kind != ActionKind.ALARM && form.date == null) { ask("What date?"); return }
        if (form.time == null) { ask("What time? Include A M or P M."); return }
        val date = form.date?.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")).orEmpty()
        val time = form.time.format(DateTimeFormatter.ofPattern("h mm a"))
        val end = if (form.kind == ActionKind.CALENDAR && form.endTime != null) " Ends at ${form.endTime.format(DateTimeFormatter.ofPattern("h mm a"))}." else ""
        ask("${form.kind.name.lowercase().replaceFirstChar { it.uppercase() }}. ${if (form.kind == ActionKind.ALARM) "Next" else date} $time.$end Say confirm or change it.")
    }

    private fun handleSpeech(heard: String) {
        val text = heard.trim().replace(Regex("^(hey\\s+jot[,.]?\\s*)", RegexOption.IGNORE_CASE), "")
        if (text.isBlank()) { notice("I didn’t catch that. Try again."); return }
        when (state.value.stage) {
            Stage.CAPTURE -> { updateText(text); update { copy(stage = Stage.NOTE, voiceSession = true) }; ask("$text. Say confirm, change it, or cancel.") }
            Stage.NOTE -> when (ConversationCommands.review(text)) {
                ReviewCommand.CONFIRM -> confirm()
                ReviewCommand.CANCEL -> close()
                ReviewCommand.CHANGE -> { update { copy(stage = Stage.CAPTURE, text = "") }; VoiceService.send(context, VoiceService.SPEAK_LISTEN, "What should the note say?") }
                else -> ask("Say confirm, change it, or cancel.")
            }
            Stage.ACTIONS -> {
                if (ConversationCommands.review(text) == ReviewCommand.CANCEL) { close(); return }
                val actions = ConversationCommands.actions(text)
                if (actions == null) ask("Alarm, calendar, reminder, message, or done?")
                else { update { copy(selected = actions) }; continueActions() }
            }
            Stage.DETAILS -> {
                val form = state.value.form ?: return
                if (changingMessage) { changingMessage = false; updateForm(form.copy(body = text)); promptForm(); return }
                when (ConversationCommands.review(text)) {
                    ReviewCommand.CONFIRM -> saveAction()
                    ReviewCommand.CANCEL -> {
                        if (state.value.busy) return
                        update { copy(busy = true) }
                        launch { formJob?.cancelAndJoin(); baseAction?.takeIf { it.status == ActionStatus.PENDING }?.let { store.saveAction(it.copy(status = ActionStatus.CANCELLED)) }; nextForm() }
                    }
                    ReviewCommand.CHANGE -> {
                        if (form.kind == ActionKind.MESSAGE) { changingMessage = true; ask("What should the message say?") }
                        else { updateForm(form.copy(date = null, time = null, endDate = null, endTime = null)); promptForm() }
                    }
                    else -> {
                        if (text.lowercase().contains("approximate") && state.value.permission == PermissionNeed.EXACT_ALARMS) { saveAction(approximate = true); return }
                        val suggested = TimeParser.suggest(text)
                        if (suggested.date == null && suggested.time == null) { promptForm(); return }
                        val date = suggested.date ?: form.date
                        val time = suggested.time ?: form.time
                        val end = if (date != null && time != null) LocalDateTime.of(date,time).plusMinutes(30) else null
                        updateForm(form.copy(date = date, time = time, endDate = end?.toLocalDate(), endTime = end?.toLocalTime())); promptForm()
                    }
                }
            }
            Stage.HOME -> Unit
        }
    }
}

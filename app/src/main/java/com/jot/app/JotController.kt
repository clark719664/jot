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
import com.jot.app.companion.*
import com.jot.app.core.*
import com.jot.app.data.*
import com.jot.app.platform.*
import com.jot.app.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.time.*
import java.lang.ref.WeakReference
import java.util.UUID

private const val VOICE_WAIT_MS = 45_000L
private const val ONE_SEC_AFTER_MS = 2_000L
private const val ALMOST_AFTER_MS = 10_000L

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
    private var choosingContact = false
    private val contacts = Contacts(context)
    private var book = emptyList<Contact>()
    private var reveal: Intent? = null
    val companion = CompanionModel(context, scope)
    private var reading: Deferred<Reading>? = null
    private val readingKeys = mutableSetOf<String>()
    private var readingGeneration = 0
    private var settled: Reading? = null
    private var touched = false
    private var auto = false
    private var fresh = false
    private val done = mutableListOf<String>()
    private var foreground: WeakReference<Activity>? = null

    fun attach(activity: Activity) { foreground = WeakReference(activity) }
    fun detach(activity: Activity) { if (foreground?.get() === activity) foreground = null }
    private fun host(activity: Activity?) = (activity ?: foreground?.get())?.takeIf { !it.isFinishing && !it.isDestroyed }

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
        if (voice) scope.launch { loadContacts() }
        if (state.value.busy) { if (captureAlreadyRunning) VoiceService.send(context, VoiceService.RESUME_WAKE); return }
        update { copy(busy = true) }
        launch {
        draftJob?.cancel()
        state.value.note?.takeIf { it.draft && state.value.text.isNotBlank() }?.let { store.saveNote(it.copy(text = state.value.text, updatedAt = System.currentTimeMillis())) }
        queue.clear(); changingMessage = false; forget()
        companion.warm()
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
        queue.clear(); forget(); mutable.value = JotUiState(stage = Stage.NOTE, note = item.note, text = item.note.text, existingActions = item.actions)
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
            if (note.draft && !before.voiceSession && companion.state.value == CompanionState.Ready && CompanionText.asks(text)) {
                update { copy(thinking = true) }
                val result = read(text).await()
                if (state.value.note?.id != note.id || state.value.stage != Stage.NOTE) return@launch
                if (result.question) { answer(text); return@launch }
                update { copy(thinking = false) }
            }
            val saved = note.copy(text = text, updatedAt = System.currentTimeMillis(), draft = false)
            store.saveNote(saved)
            if (!note.draft) { finish("Saved"); return@launch }
            fresh = true
            update { copy(note = saved, text = text, stage = Stage.ACTIONS, selected = emptySet(), busy = false, error = null, thinking = true) }
            suggest(saved, before.voiceSession)
        }
    }

    private fun read(text: String, spoken: Boolean = false): Deferred<Reading> {
        val clean = text.trim()
        reading?.takeIf { clean in readingKeys }?.let { return it }
        reading?.cancel(); settled = null; readingKeys.clear(); readingKeys += clean
        val generation = ++readingGeneration
        return scope.async {
            val model = companion.read(clean)
            val note = if (spoken) CompanionText.fix(clean, model?.note) else clean
            CompanionText.settle(note, model).copy(note = note).also {
                if (generation == readingGeneration) { readingKeys += note; settled = it }
            }
        }.also { reading = it }
    }

    private fun forget() { reading?.cancel(); reading = null; readingKeys.clear(); readingGeneration++; settled = null; touched = false }

    private suspend fun loadContacts() { book = withContext(Dispatchers.IO) { contacts.all() } }

    /** Jot may have offered a message before it was allowed to look the person up. */
    fun contactsAllowed() {
        launch {
            loadContacts()
            val note = state.value.note ?: return@launch
            if (state.value.stage == Stage.ACTIONS) update { copy(plan = ActionKind.entries.associateWith { formFor(ActionRecord("", note.id, it), note) }) }
        }
    }

    private fun numberFor(recipient: String?): String? {
        val who = recipient?.trim().orEmpty()
        if (who.isEmpty()) return null
        if (who.none(Char::isLetter) && who.any(Char::isDigit)) return who.filter { it.isDigit() || it == '+' }
        return ContactMatch.find(who, book).singleOrNull()?.number
    }

    private suspend fun suggest(note: NoteRecord, voice: Boolean) {
        touched = false
        loadContacts()
        val pending = read(note.text)
        val result = when {
            voice && CompanionText.ready(note.text, book) -> CompanionText.settle(note.text, null)
            voice -> withTimeoutOrNull(VOICE_WAIT_MS) { pending.await() } ?: CompanionText.settle(note.text, null)
            else -> pending.await()
        }
        if (state.value.stage != Stage.ACTIONS || state.value.note?.id != note.id) return
        val plan = ActionKind.entries.associateWith { formFor(ActionRecord("", note.id, it), note) }
        update { copy(thinking = false, selected = if (touched) selected else result.actions, plan = plan) }
        val today = LocalDate.now()
        val repeat = JotLines.repeats(TimeParser.repeatDays(note.text))
        if (voice) ask(JotLines.offer(state.value.selected) { kind ->
            plan[kind]?.let { form ->
                if (kind == ActionKind.MESSAGE) JotLines.addressed(form.recipient, form.body)
                else JotLines.spokenWhen(kind, form.date, form.time, today)?.let { if (kind == ActionKind.ALARM && repeat != null) "$it $repeat" else it }
            }
        }.ifEmpty { JotLines.ANYTHING_ELSE })
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
        queue.clear(); forget(); choosingContact = false; reveal = null; mutable.value = JotUiState()
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
    fun toggleAction(kind: ActionKind) { touched = true; update { copy(selected = if (kind in selected) selected - kind else selected + kind) } }

    fun continueActions(activity: Activity? = null) {
        if (state.value.busy) return
        if (state.value.stage == Stage.NOTE) {
            update { copy(busy = true) }
            launch { val current = state.value; val note = current.note ?: return@launch
                val saved = note.copy(text = current.text.trim(), updatedAt = System.currentTimeMillis(), draft = false)
                if (saved.text.isBlank()) { notice("Enter a note."); return@launch }
                draftJob?.cancelAndJoin(); store.saveNote(saved); fresh = false
                update { copy(note = saved, stage = Stage.ACTIONS, selected = emptySet(), existingActions = emptyList(), busy = false, thinking = true) }
                suggest(saved, current.voiceSession)
            }; return
        }
        update { copy(busy = true) }
        launch {
            val current = state.value
            val kinds = ActionKind.entries.filter { it in current.selected }
            if (kinds.isEmpty()) { finish("Note saved"); return@launch }
            val note = current.note?.let { if (fresh) it.copy(actionOnly = true) else it } ?: return@launch
            if (fresh) { store.saveNote(note); update { copy(note = note) } }
            queue.clear(); done.clear(); auto = true
            for (kind in kinds) {
                val id = UUID.randomUUID().toString()
                val action = current.plan[kind]?.let { record(it.copy(id = id), note.id, validate = false) } ?: ActionRecord(id, note.id, kind)
                store.saveAction(action); queue.addLast(action)
            }
            nextForm(activity)
        }
    }

    private suspend fun nextForm(activity: Activity? = null) {
        val note = state.value.note ?: return
        while (queue.isNotEmpty()) {
            val action = queue.removeFirst()
            baseAction = action
            val form = formFor(action, note)
            if (auto) {
                val unaddressed = form.kind == ActionKind.MESSAGE && book.isNotEmpty() && numberFor(form.recipient) == null
                val ready = if (unaddressed) null else runCatching { record(form, note.id, validate = true) }.getOrNull()
                if (ready != null && perform(note, ready, form, activity, approximate = false)) continue
                if (state.value.stage == Stage.DETAILS && state.value.form?.id == form.id) return
            }
            update { copy(stage = Stage.DETAILS, form = form, busy = false, error = null, permission = null, thinking = false) }
            if (state.value.voiceSession) promptForm()
            return
        }
        val summary = done.joinToString(", ").lowercase().replaceFirstChar { it.uppercase() }.ifEmpty { "Saved" }
        auto = false; done.clear()
        val destination = reveal
        reveal = null
        finish(summary)
        destination?.let { intent -> host(activity)?.let { runCatching { it.startActivity(intent) } } }
    }

    private fun formFor(action: ActionRecord, note: NoteRecord): ActionForm {
        val suggestion = TimeParser.suggest(note.text)
        val start = action.atMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val end = action.endMillis?.let { Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()) }
        val date = start?.toLocalDate() ?: suggestion.date
        val time = start?.toLocalTime() ?: suggestion.time
        val defaultEnd = if (date != null && time != null) LocalDateTime.of(date, time).plusMinutes(30) else null
        val companionText = settled?.takeIf { note.text.trim() in readingKeys }?.let { if (action.kind == ActionKind.MESSAGE) it.message else it.title }
        val addressee = if (action.kind == ActionKind.MESSAGE) ContactMatch.address(note.text, book) else null
        val fallback = when (action.kind) {
            ActionKind.ALARM -> ""
            ActionKind.MESSAGE -> addressee?.let { ContactMatch.body(it.rest) } ?: note.text
            else -> note.text
        }
        val recipient = action.recipient ?: addressee?.matches?.singleOrNull()?.name.orEmpty()
        // "Text Sam that..." already holds the words to send, so the model has nothing to add.
        val words = if (addressee != null && CompanionText.startsWithMessage(note.text)) fallback else companionText ?: fallback
        return ActionForm(action.id, action.kind, date, time, end?.toLocalDate() ?: defaultEnd?.toLocalDate(), end?.toLocalTime() ?: defaultEnd?.toLocalTime(), action.body?.takeIf { it.isNotBlank() } ?: words, recipient)
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
            if (perform(note, action, form, activity, approximate)) nextForm(activity)
        }
    }

    /** Carries out one action. False when it stopped on its form for a permission or an error. */
    private suspend fun perform(note: NoteRecord, action: ActionRecord, form: ActionForm, activity: Activity?, approximate: Boolean): Boolean {
        val voice = state.value.voiceSession
        fun stop(permission: PermissionNeed? = null, error: String? = null): Boolean {
            update { copy(stage = Stage.DETAILS, form = form, permission = permission, error = error, busy = false, thinking = false) }
            return false
        }
        if (baseAction?.status != ActionStatus.SCHEDULED) store.saveAction(action)
        when (action.kind) {
            ActionKind.REMINDER -> when (val result = androidActions.schedule(note, action, approximate)) {
                is ScheduleResult.Scheduled -> done += "Reminder set"
                ScheduleResult.NeedsExactPermission -> { if (voice) speak(JotLines.ALLOW_EXACT, listen = true); return stop(PermissionNeed.EXACT_ALARMS) }
                ScheduleResult.NeedsNotifications -> { if (voice) speak(JotLines.ALLOW_NOTIFICATIONS); return stop(PermissionNeed.NOTIFICATIONS) }
                is ScheduleResult.Failed -> return stop(error = result.message)
            }
            ActionKind.MESSAGE -> {
                val host = host(activity)
                val number = numberFor(action.recipient)
                val sent = if (auto && number != null && androidActions.canSend()) androidActions.send(number, action.body?.takeIf { it.isNotBlank() } ?: note.text) else false
                if (number != null && sent != false) {
                    store.saveAction(action.copy(status = ActionStatus.DONE))
                    done += if (sent == true) "Message sent" else "Message sending"
                    reveal = androidActions.conversationView(number)
                } else if (auto && host != null) androidActions.dispatch(host, note, action.copy(recipient = number)).fold(
                    onSuccess = { store.saveAction(action.copy(status = it)); done += "Message ready" },
                    onFailure = { return stop(error = it.message ?: "No app can open this action.") },
                ) else done += "Draft saved"
            }
            else -> {
                val host = host(activity)
                val event = androidActions.addEvent(note, action)
                when {
                    event != null -> { store.saveAction(action.copy(status = ActionStatus.DONE)); done += "Event added"; reveal = androidActions.eventView(event, action) }
                    host != null -> androidActions.dispatch(host, note, action).fold(
                        onSuccess = {
                            store.saveAction(action.copy(status = it))
                            done += if (action.kind == ActionKind.ALARM) "Alarm set" else "Calendar opened"
                            if (action.kind == ActionKind.ALARM) reveal = androidActions.alarmsView()
                        },
                        onFailure = { return stop(error = it.message ?: "No app can open this action.") },
                    )
                    else -> { notifyPending(note); done += "Saved" }
                }
            }
        }
        return true
    }

    fun openAction(requested: ActionRecord, activity: Activity) {
        if (state.value.busy) return
        update { copy(busy = true) }
        launch {
        val action = store.action(requested.id) ?: run { notice("This action no longer exists."); return@launch }
        if (action.status == ActionStatus.CANCELLED) { notice("This action was cancelled."); return@launch }
        val note = store.note(action.noteId) ?: run { notice("This note no longer exists."); return@launch }
        if (action.kind == ActionKind.REMINDER || action.kind != ActionKind.MESSAGE && (action.atMillis == null || action.atMillis <= System.currentTimeMillis()) || action.kind == ActionKind.MESSAGE && action.body == null) {
            queue.clear(); queue.add(action); auto = false; done.clear()
            update { copy(note = note, text = note.text, voiceSession = false) }; nextForm(activity); return@launch
        }
        loadContacts()
        androidActions.dispatch(activity, note, action.copy(recipient = numberFor(action.recipient) ?: action.recipient)).fold(
            onSuccess = { status -> store.saveAction(action.copy(status = status)); val actions = store.actions(note.id); update { copy(existingActions = actions, busy = false, message = "Opened ${if (action.kind == ActionKind.MESSAGE) "Messages" else if (action.kind == ActionKind.ALARM) "Clock" else "Calendar"}") } },
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
        queue.clear(); choosingContact = false; mutable.value = JotUiState(message = message)
        if (voice) speak(JotLines.finished(message))
    }
    private fun ask(text: String) = speak(text, listen = true)
    private fun speak(text: String, listen: Boolean = false) { VoiceService.send(context, if (listen) VoiceService.SPEAK_LISTEN else VoiceService.SPEAK, text) }

    private fun promptForm() {
        val form = state.value.form ?: return
        if (form.kind == ActionKind.MESSAGE) {
            if (book.isNotEmpty() && numberFor(form.recipient) == null) {
                choosingContact = true
                val note = state.value.note?.text.orEmpty()
                ask(JotLines.whichOne(ContactMatch.address(note, book)?.matches.orEmpty().map { it.name }))
            } else if (form.body.isBlank()) {
                changingMessage = true
                ask(JotLines.NEW_MESSAGE)
            } else ask(JotLines.message(form.recipient, form.body))
            return
        }
        if (form.kind != ActionKind.ALARM && form.date == null) { ask(JotLines.WHAT_DAY); return }
        if (form.time == null) { ask(JotLines.WHAT_TIME); return }
        ask(JotLines.offer(setOf(form.kind)) { JotLines.spokenWhen(it, form.date, form.time, LocalDate.now()) })
    }

    private fun handleSpeech(heard: String) {
        val text = heard.trim().replace(Regex("^(?:hey\\s+)?jot\\b(?!\\s+(?:down|that|this|it)\\b)[,.]?\\s*", RegexOption.IGNORE_CASE), "")
        if (text.isBlank()) { if (state.value.voiceSession) ask(JotLines.MISSED_IT) else notice("I didn’t catch that. Try again."); return }
        when (state.value.stage) {
            Stage.CAPTURE -> {
                updateText(text); update { copy(stage = Stage.NOTE, voiceSession = true) }
                val pending = read(text, spoken = true)
                if (CompanionText.ready(text, book)) { confirm(); return }
                if (companion.state.value != CompanionState.Ready) { review(text, CompanionText.settle(text, null).actions); return }
                update { copy(thinking = true) }
                launch {
                    val filler = scope.launch {
                        delay(ONE_SEC_AFTER_MS); VoiceService.send(context, VoiceService.SPEAK_HOLD, JotLines.ONE_SEC)
                        delay(ALMOST_AFTER_MS); VoiceService.send(context, VoiceService.SPEAK_HOLD, JotLines.ALMOST_THERE)
                    }
                    val result = withTimeoutOrNull(VOICE_WAIT_MS) { pending.await() }
                    filler.cancel()
                    if (state.value.stage != Stage.NOTE) return@launch
                    val unchanged = state.value.text == text
                    val heard = result?.note?.takeIf { unchanged } ?: state.value.text
                    if (result?.question == true && unchanged) { answer(heard); return@launch }
                    if (heard != state.value.text) updateText(heard)
                    update { copy(thinking = false) }
                    if (result == null) scope.launch { lateFix(pending, heard) }
                    if (unchanged) review(heard, result?.actions ?: CompanionText.settle(heard, null).actions)
                }
            }
            Stage.NOTE -> when (ConversationCommands.review(text)) {
                ReviewCommand.CONFIRM -> confirm()
                ReviewCommand.CANCEL -> close()
                ReviewCommand.CHANGE -> redo()
                else -> {
                    val correction = ConversationCommands.correction(text)
                    if (correction != null) { update { copy(stage = Stage.CAPTURE, text = "") }; handleSpeech(correction) }
                    else if (ConversationCommands.review(text) == ReviewCommand.NO) redo()
                    else ask(JotLines.IS_THAT_RIGHT)
                }
            }
            Stage.ACTIONS -> {
                val command = ConversationCommands.review(text)
                if (fresh && state.value.selected.isNotEmpty() && command == ReviewCommand.CANCEL) { deleteNote(); return }
                if (fresh && state.value.selected.isNotEmpty() && command == ReviewCommand.NO) { retry(); return }
                if (command == ReviewCommand.CANCEL || command == ReviewCommand.NO) { update { copy(selected = emptySet()) }; continueActions(); return }
                if (command == ReviewCommand.CONFIRM && state.value.selected.isNotEmpty()) { continueActions(); return }
                if (command == ReviewCommand.CONFIRM) { ask(JotLines.WHICH_ONE); return }
                val actions = ConversationCommands.actions(text)
                if (actions != null) { update { copy(selected = actions) }; continueActions(); return }
                launch {
                    val picked = companion.read(text)?.actions.orEmpty()
                    if (state.value.stage != Stage.ACTIONS) return@launch
                    if (picked.isEmpty()) ask(JotLines.WHICH_ONE)
                    else { update { copy(selected = picked) }; continueActions() }
                }
            }
            Stage.DETAILS -> {
                val form = state.value.form ?: return
                if (changingMessage) { changingMessage = false; updateForm(form.copy(body = text)); promptForm(); return }
                if (choosingContact && ConversationCommands.review(text) != ReviewCommand.CANCEL) {
                    val found = ContactMatch.find(text, book)
                    when {
                        found.size == 1 -> { choosingContact = false; updateForm(form.copy(recipient = found.single().name)); promptForm() }
                        found.isEmpty() -> ask(JotLines.noContact(text.trimEnd('.', '?', '!')))
                        else -> ask(JotLines.whichOne(found.map { it.name }))
                    }
                    return
                }
                when (ConversationCommands.review(text)) {
                    ReviewCommand.CONFIRM -> saveAction()
                    ReviewCommand.CANCEL -> {
                        if (state.value.busy) return
                        update { copy(busy = true) }
                        launch { formJob?.cancelAndJoin(); baseAction?.takeIf { it.status == ActionStatus.PENDING }?.let { store.saveAction(it.copy(status = ActionStatus.CANCELLED)) }; nextForm(host(null)) }
                    }
                    ReviewCommand.CHANGE, ReviewCommand.NO -> {
                        if (form.kind == ActionKind.MESSAGE) { changingMessage = true; ask(JotLines.NEW_MESSAGE) }
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
            Stage.HOME, Stage.ANSWER -> Unit
        }
    }

    /** With an action on the table the note has no review of its own, so yes acts and anything else keeps it readable. */
    private fun review(heard: String, actions: Set<ActionKind>) { if (actions.isNotEmpty()) confirm() else ask(JotLines.readback(heard)) }

    private suspend fun lateFix(pending: Deferred<Reading>, heard: String) {
        val fixed = runCatching { pending.await() }.getOrNull()?.note ?: return
        if (state.value.stage == Stage.NOTE && state.value.text == heard) updateText(fixed)
    }

    private fun retry() {
        launch {
            val note = state.value.note ?: return@launch
            val draft = note.copy(draft = true)
            store.saveNote(draft)
            update { copy(note = draft, selected = emptySet()) }
            redo()
        }
    }

    private fun redo() {
        update { copy(stage = Stage.CAPTURE, text = "") }
        VoiceService.send(context, VoiceService.SPEAK_LISTEN, JotLines.WHAT_SHOULD_IT_SAY)
    }

    private suspend fun answer(question: String) {
        val draft = state.value.note
        draftJob?.cancelAndJoin()
        if (draft?.draft == true) store.deleteNote(draft.id)
        update { copy(stage = Stage.ANSWER, note = null, text = question, thinking = true, busy = false, answer = null, sources = emptyList()) }
        val saved = store.remembered()
        val matches = CompanionText.relevant(question, saved.map { it.note }).map { it.id }
        val context = saved.filter { it.note.id in matches }.sortedBy { matches.indexOf(it.note.id) }.ifEmpty { saved.take(10) }
        val reply = if (context.isEmpty()) CompanionText.NO_ANSWER else companion.answer(question, context.map { it.note }) ?: CompanionText.NO_ANSWER
        if (state.value.stage != Stage.ANSWER) return
        val sources = if (reply == CompanionText.NO_ANSWER) emptyList() else context.filter { it.note.id in matches }.take(3)
        update { copy(thinking = false, answer = reply, sources = sources) }
        if (state.value.voiceSession) speak(reply)
    }

    fun keepQuestion() {
        if (state.value.busy) return
        val text = state.value.text.trim()
        update { copy(busy = true) }
        launch {
            val now = System.currentTimeMillis()
            val note = NoteRecord(UUID.randomUUID().toString(), text, now, now, draft = true)
            store.saveNote(note)
            mutable.value = JotUiState(stage = Stage.NOTE, note = note, text = text)
        }
    }

    fun setCompanion(enabled: Boolean) { if (enabled) companion.enable() else companion.disable() }
}

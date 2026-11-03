package com.jot.app.ui

import com.jot.app.data.*
import java.time.LocalDate
import java.time.LocalTime

enum class Stage { HOME, CAPTURE, NOTE, ACTIONS, DETAILS }
enum class PermissionNeed { MICROPHONE, NOTIFICATIONS, EXACT_ALARMS }
data class ActionForm(
    val id: String,
    val kind: ActionKind,
    val date: LocalDate? = null,
    val time: LocalTime? = null,
    val endDate: LocalDate? = null,
    val endTime: LocalTime? = null,
    val body: String = "",
    val recipient: String = "",
)
data class JotUiState(
    val stage: Stage = Stage.HOME,
    val note: NoteRecord? = null,
    val text: String = "",
    val selected: Set<ActionKind> = emptySet(),
    val form: ActionForm? = null,
    val existingActions: List<ActionRecord> = emptyList(),
    val error: String? = null,
    val message: String? = null,
    val permission: PermissionNeed? = null,
    val voiceSession: Boolean = false,
    val busy: Boolean = false,
)
data class JotCallbacks(
    val onNew: () -> Unit,
    val onOpen: (NoteItem) -> Unit,
    val onTextChanged: (String) -> Unit,
    val onConfirm: () -> Unit,
    val onClose: () -> Unit,
    val onToggleAction: (ActionKind) -> Unit,
    val onContinue: () -> Unit,
    val onFormChanged: (ActionForm) -> Unit,
    val onSaveAction: () -> Unit,
    val onOpenAction: (ActionRecord) -> Unit,
    val onDeleteNote: () -> Unit,
    val onRequestPermission: () -> Unit,
    val onApproximate: () -> Unit,
    val onMic: () -> Unit,
    val onWakeToggle: (Boolean) -> Unit,
    val onDismissMessage: () -> Unit,
)

package com.jot.app

import android.Manifest
import android.app.AlarmManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.compose.runtime.getValue
import com.jot.app.capture.*
import com.jot.app.data.ActionKind
import com.jot.app.platform.AndroidActions
import com.jot.app.ui.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val controller get() = (application as JotApplication).controller
    private var pendingVoice: String? = null
    private var pendingContinue = false
    private var askedToMessage = false
    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.contactsAllowed()
        val command = pendingVoice
        pendingVoice = null
        if (pendingContinue) { pendingContinue = false; controller.continueActions(this); return@registerForActivityResult }
        if (command != null) {
            if (!hasMicrophone()) controller.notice("Allow microphone access to record.")
            else if (command == VoiceService.START_WAKE && !NotificationManagerCompat.from(this).areNotificationsEnabled()) controller.notice("Allow notifications to enable Hey Jot.")
            else performVoice(command)
        } else if (controller.state.value.permission == PermissionNeed.NOTIFICATIONS && NotificationManagerCompat.from(this).areNotificationsEnabled()) controller.saveAction(this)
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val notes by controller.notes.collectAsStateWithLifecycle()
            val state by controller.state.collectAsStateWithLifecycle()
            val voice by VoiceSignals.state.collectAsStateWithLifecycle()
            val companion by controller.companion.state.collectAsStateWithLifecycle()
            JotTheme {
                JotScreen(notes, state, voice, companion, JotCallbacks(
                    onNew = { controller.newNote() }, onOpen = controller::open,
                    onTextChanged = controller::updateText, onConfirm = controller::confirm,
                    onClose = controller::close, onToggleAction = controller::toggleAction,
                    onContinue = ::confirmActions, onFormChanged = controller::updateForm,
                    onSaveAction = { controller.saveAction(this) }, onOpenAction = { controller.openAction(it, this) },
                    onDeleteNote = controller::deleteNote, onRequestPermission = ::requestActionPermission,
                    onApproximate = { controller.saveAction(this, approximate = true) },
                    onMic = { beginVoice(VoiceService.CAPTURE) },
                    onWakeToggle = { enabled -> if (enabled) beginVoice(VoiceService.START_WAKE) else VoiceService.send(this, VoiceService.STOP) },
                    onDismissMessage = controller::dismissMessage,
                    onCompanionToggle = controller::setCompanion,
                    onKeepQuestion = controller::keepQuestion,
                ))
            }
        }
        openNotification(intent)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                controller.state.map { it.stage == Stage.ACTIONS && ActionKind.MESSAGE in it.selected }.distinctUntilChanged().collect { if (it) askToMessage() }
            }
        }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); openNotification(intent) }
    private fun openNotification(intent: Intent?) { intent?.getStringExtra(AndroidActions.EXTRA_NOTE_ID)?.let(controller::openNote) }
    override fun onResume() {
        super.onResume()
        controller.attach(this)
        when (controller.state.value.permission) {
            PermissionNeed.EXACT_ALARMS -> if (Build.VERSION.SDK_INT < 31 || getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) controller.saveAction(this)
            PermissionNeed.NOTIFICATIONS -> if (NotificationManagerCompat.from(this).areNotificationsEnabled()) controller.saveAction(this)
            else -> Unit
        }
    }
    override fun onStop() { controller.detach(this); super.onStop() }
    private fun confirmActions() {
        val state = controller.state.value
        val calendar = arrayOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
        if (state.stage == Stage.ACTIONS && ActionKind.CALENDAR in state.selected && calendar.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            pendingContinue = true
            permissions.launch(calendar)
        } else controller.continueActions(this)
    }
    private fun askToMessage() {
        val missing = arrayOf(Manifest.permission.READ_CONTACTS, Manifest.permission.SEND_SMS).filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (askedToMessage || missing.isEmpty()) return
        askedToMessage = true
        permissions.launch(missing.toTypedArray())
    }
    private fun hasMicrophone() = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    private fun beginVoice(command: String) {
        val missing = mutableListOf<String>()
        if (!hasMicrophone()) missing += Manifest.permission.RECORD_AUDIO
        if (Build.VERSION.SDK_INT >= 33 && !NotificationManagerCompat.from(this).areNotificationsEnabled()) missing += Manifest.permission.POST_NOTIFICATIONS
        if (missing.isNotEmpty()) { pendingVoice = command; permissions.launch(missing.toTypedArray()) }
        else performVoice(command)
    }
    private fun performVoice(command: String) {
        try { if (command == VoiceService.CAPTURE) controller.newNote(voice = true) else VoiceService.send(this, command) }
        catch (e: Exception) { controller.notice(e.message ?: "Voice could not start.") }
    }
    private fun requestActionPermission() {
        try { when (controller.state.value.permission) {
            PermissionNeed.MICROPHONE -> beginVoice(VoiceService.CAPTURE)
            PermissionNeed.NOTIFICATIONS -> if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this,Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permissions.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS)) else startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            PermissionNeed.EXACT_ALARMS -> if (Build.VERSION.SDK_INT >= 31) startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")))
            null -> Unit
        } } catch (_: Exception) { controller.notice("Open Android settings to allow this permission.") }
    }
}

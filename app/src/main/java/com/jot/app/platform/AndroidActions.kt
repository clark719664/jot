package com.jot.app.platform

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.util.Log
import com.jot.app.MainActivity
import com.jot.app.R
import com.jot.app.data.ActionKind
import com.jot.app.data.ActionRecord
import com.jot.app.data.ActionStatus
import com.jot.app.data.JotStore
import com.jot.app.data.NoteRecord
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed class ScheduleResult {
    data class Scheduled(val exact: Boolean) : ScheduleResult()
    data object NeedsExactPermission : ScheduleResult()
    data object NeedsNotifications : ScheduleResult()
    data class Failed(val message: String) : ScheduleResult()
}

class AndroidActions(context: Context, private val store: JotStore = JotStore.get(context)) {
    private val app = context.applicationContext
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    suspend fun schedule(
        note: NoteRecord,
        action: ActionRecord,
        allowApproximate: Boolean = false,
    ): ScheduleResult = reminderLock.withLock {
        withContext(NonCancellable) {
            scheduleReminder(note, action, allowApproximate)
        }
    }

    private suspend fun scheduleReminder(
        note: NoteRecord,
        action: ActionRecord,
        allowApproximate: Boolean,
    ): ScheduleResult {
        if (action.kind != ActionKind.REMINDER || action.noteId != note.id || note.draft) {
            return ScheduleResult.Failed("Save the note before scheduling its reminder.")
        }
        val whenMillis = action.atMillis ?: return ScheduleResult.Failed("Choose a reminder time.")
        if (whenMillis <= System.currentTimeMillis()) {
            return ScheduleResult.Failed("Choose a reminder time in the future.")
        }
        val saved = store.action(action.id)
        if (saved != null && (saved.noteId != note.id || saved.kind != ActionKind.REMINDER)) {
            return ScheduleResult.Failed("This reminder belongs to a different action.")
        }
        if (saved?.status == ActionStatus.CANCELLED || saved?.status == ActionStatus.HANDED_OFF) {
            return ScheduleResult.Failed("This action is already closed.")
        }
        if (!notificationsAllowed()) return ScheduleResult.NeedsNotifications
        val exact = canScheduleExact()
        if (!exact && !allowApproximate) return ScheduleResult.NeedsExactPermission

        val previous = saved?.takeIf { it.status == ActionStatus.SCHEDULED }
        val wasDelivered = previous != null && store.wasDelivered(action.id)
        val pending = reminderIntent(action.id, PendingIntent.FLAG_UPDATE_CURRENT)
            ?: return ScheduleResult.Failed("Android could not create the reminder.")
        try {
            registerAlarm(whenMillis, pending, exact)
        } catch (_: SecurityException) {
            if (previous == null) pending.cancel()
            return if (exact && !canScheduleExact()) ScheduleResult.NeedsExactPermission
            else ScheduleResult.Failed("Android could not schedule the reminder.")
        } catch (_: RuntimeException) {
            if (previous == null) pending.cancel()
            return ScheduleResult.Failed("Android could not schedule the reminder.")
        }
        try {
            store.saveScheduledReminder(action.copy(status = ActionStatus.SCHEDULED, exact = exact))
        } catch (_: RuntimeException) {
            alarms.cancel(pending)
            val oldTime = previous?.atMillis
            if (oldTime != null && !wasDelivered) {
                try {
                    registerAlarm(oldTime.coerceAtLeast(System.currentTimeMillis()), pending, previous.exact)
                } catch (_: RuntimeException) {
                    Log.w(LOG_TAG, "The previous reminder could not be restored.")
                }
            } else {
                pending.cancel()
            }
            return ScheduleResult.Failed("Jot could not save the reminder.")
        }
        if (wasDelivered) notifications.cancel(action.id, NOTIFICATION_ID)
        return ScheduleResult.Scheduled(exact)
    }

    fun dispatch(activity: Activity, note: NoteRecord, action: ActionRecord): Result<Unit> {
        return runCatching {
            require(action.noteId == note.id && !note.draft) { "Save the note before opening another app." }
            val text = action.body?.takeIf { it.isNotBlank() } ?: note.text
            val intent = when (action.kind) {
                ActionKind.ALARM -> clockIntent(action, text)
                ActionKind.CALENDAR -> calendarIntent(action, text)
                ActionKind.MESSAGE -> messageIntent(action, text)
                ActionKind.REMINDER -> error("Schedule this reminder in Jot.")
            }
            try {
                activity.startActivity(intent)
            } catch (_: ActivityNotFoundException) {
                val name = when (action.kind) {
                    ActionKind.ALARM -> "clock"
                    ActionKind.CALENDAR -> "calendar"
                    else -> "messaging"
                }
                error("No compatible $name app is available.")
            } catch (_: SecurityException) {
                error("Android could not open the selected app.")
            }
        }
    }

    suspend fun cancel(action: ActionRecord): Unit = reminderLock.withLock {
        withContext(NonCancellable) {
            val saved = store.action(action.id) ?: return@withContext
            if (saved.status == ActionStatus.HANDED_OFF || saved.status == ActionStatus.CANCELLED) {
                return@withContext
            }
            store.saveAction(saved.copy(status = ActionStatus.CANCELLED))
            if (saved.kind == ActionKind.REMINDER) {
                reminderIntent(saved.id, PendingIntent.FLAG_NO_CREATE)?.let { pending ->
                    alarms.cancel(pending)
                    pending.cancel()
                }
                notifications.cancel(saved.id, NOTIFICATION_ID)
            }
        }
    }

    internal suspend fun restore() = reminderLock.withLock {
        for (action in store.undeliveredReminders()) {
            val whenMillis = action.atMillis ?: continue
            if (action.exact && !canScheduleExact()) continue
            val pending = reminderIntent(action.id, PendingIntent.FLAG_UPDATE_CURRENT) ?: continue
            try {
                registerAlarm(whenMillis.coerceAtLeast(System.currentTimeMillis()), pending, action.exact)
            } catch (_: RuntimeException) {
                Log.w(LOG_TAG, "A reminder could not be restored.")
            }
        }
    }

    internal suspend fun deliver(id: String) = reminderLock.withLock {
        val action = store.action(id) ?: return@withLock
        if (action.kind != ActionKind.REMINDER || action.status != ActionStatus.SCHEDULED ||
            action.atMillis == null || action.atMillis > System.currentTimeMillis() || store.wasDelivered(id)
        ) return@withLock
        val note = store.note(action.noteId) ?: return@withLock
        if (!notificationsAllowed()) return@withLock

        val openNote = Intent(app, MainActivity::class.java)
            .setData(identityUri(id))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_NOTE_ID, note.id)
        val content = PendingIntent.getActivity(
            app, 0, openNote, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = action.body?.takeIf { it.isNotBlank() } ?: note.text
        val notification = Notification.Builder(app, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jot reminder")
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(content)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .build()
        notifications.notify(id, NOTIFICATION_ID, notification)
        store.recordDelivery(id)
    }

    private fun clockIntent(action: ActionRecord, text: String): Intent {
        val now = ZonedDateTime.now()
        val time = Instant.ofEpochMilli(futureStart(action)).atZone(now.zone)
        val clockTime = time.toLocalTime().withSecond(0).withNano(0)
        val today = now.toLocalDate()
        val todayAtTime = today.atTime(clockTime).atZone(now.zone)
        val next = if (todayAtTime.isAfter(now)) todayAtTime
        else today.plusDays(1).atTime(clockTime).atZone(now.zone)
        require(time.toInstant().epochSecond / 60 == next.toInstant().epochSecond / 60) {
            "Clock can open an alarm for the next occurrence of that time. For a later date, use a reminder."
        }
        return Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, time.hour)
            .putExtra(AlarmClock.EXTRA_MINUTES, time.minute)
            .putExtra(AlarmClock.EXTRA_MESSAGE, text)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
    }

    private fun calendarIntent(action: ActionRecord, text: String): Intent {
        val start = futureStart(action)
        val end = action.endMillis ?: Math.addExact(start, 30 * 60_000L)
        require(end > start) { "Choose an end time after the start." }
        return Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, text)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end)
    }

    private fun messageIntent(action: ActionRecord, text: String): Intent {
        val recipient = action.recipient.orEmpty().trim()
        require(recipient.isEmpty() || phoneInput.matches(recipient)) { "Enter a phone number." }
        val number = recipient.filter { it in '0'..'9' || it == '+' }
        require(recipient.isEmpty() || number.any { it in '0'..'9' }) { "Enter a phone number." }
        return Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
            .putExtra("sms_body", text)
    }

    private fun futureStart(action: ActionRecord): Long {
        val start = requireNotNull(action.atMillis) { "Choose a date and time." }
        require(start > System.currentTimeMillis()) { "Choose a time in the future." }
        return start
    }

    private fun canScheduleExact(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    private fun registerAlarm(whenMillis: Long, pending: PendingIntent, exact: Boolean) {
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, whenMillis, pending)
    }

    private fun notificationsAllowed(): Boolean {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Reminders", NotificationManager.IMPORTANCE_DEFAULT),
        )
        return (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            notifications.areNotificationsEnabled() &&
            (notifications.getNotificationChannel(CHANNEL_ID)?.importance ?: NotificationManager.IMPORTANCE_NONE) >
            NotificationManager.IMPORTANCE_NONE
    }

    private fun reminderIntent(id: String, flags: Int): PendingIntent? {
        val intent = Intent(app, ReminderReceiver::class.java).setData(identityUri(id))
        return PendingIntent.getBroadcast(app, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val EXTRA_NOTE_ID = "noteId"
        private const val CHANNEL_ID = "jot_reminders"
        private const val NOTIFICATION_ID = 1
        private val reminderLock = Mutex()
        private val phoneInput = Regex("""\+?[0-9() .-]+""")
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.data?.lastPathSegment ?: return
        runAsync(context) { deliver(id) }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
            -> runAsync(context) { restore() }
        }
    }
}

private const val LOG_TAG = "JotReminders"

private fun identityUri(id: String): Uri =
    Uri.Builder().scheme("jot").authority("reminder").appendPath(id).build()

private fun BroadcastReceiver.runAsync(context: Context, block: suspend AndroidActions.() -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
        try {
            AndroidActions(context).block()
        } catch (_: RuntimeException) {
            Log.w(LOG_TAG, "A reminder operation could not finish.")
        } finally {
            pending.finish()
        }
    }
}

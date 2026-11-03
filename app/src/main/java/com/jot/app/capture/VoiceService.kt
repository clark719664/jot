package com.jot.app.capture

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.tts.TextToSpeech
import com.jot.app.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class VoiceService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val executor = Executors.newSingleThreadExecutor()
    private val audioDispatcher = executor.asCoroutineDispatcher()
    private lateinit var audio: OfflineAudio
    private lateinit var speech: OfflineSpeech
    private lateinit var voice: NaturalVoice
    private var operation: Job? = null
    private var wakeEnabled = false
    private var wakeRequested = false
    private var wakeLock: PowerManager.WakeLock? = null
    private var wakeLockRenewal: Job? = null
    private var closing = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = OfflineAudio(assets)
        speech = OfflineSpeech(this)
        voice = NaturalVoice(this, scope)
        active = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (val command = intent?.action) {
            START_WAKE, CAPTURE -> {
                if (closing) return START_NOT_STICKY
                try {
                    requireMicrophone()
                    startForeground(NOTIFICATION_ID, notification())
                    handle(command, null)
                } catch (error: Exception) {
                    failAndStop(error)
                }
            }
            STOP -> stopVoice()
            else -> stopVoice()
        }
        return START_NOT_STICKY
    }

    private fun handle(command: String, text: String?) {
        if (closing) return
        if (command == STOP) {
            stopVoice()
            return
        }
        if (command == START_WAKE && wakeRequested) return
        if (command == START_WAKE) wakeRequested = true
        val previous = operation
        previous?.cancel()
        operation = scope.launch {
            previous?.join()
            try {
                when (command) {
                    START_WAKE -> restoreWake()
                    CAPTURE -> capture()
                    RESUME_WAKE -> restoreWake()
                    SPEAK, SPEAK_LISTEN, SPEAK_HOLD -> {
                        publish(speaking = true)
                        val line = text.orEmpty()
                        val spoken = voice.speak(line) || speech.speak(line.take(TextToSpeech.getMaxSpeechInputLength()))
                        if (!spoken) report("No offline English speech voice is available, or playback was interrupted.")
                        when {
                            spoken && command == SPEAK_LISTEN -> capture()
                            command == SPEAK_HOLD -> { publish(); delay(CONTROLLER_TIMEOUT_MS); restoreWake() }
                            else -> restoreWake()
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failAndStop(error)
            } catch (error: LinkageError) {
                failAndStop(error)
            }
        }
    }

    private suspend fun restoreWake() {
        if (!wakeRequested) {
            stopVoice()
            return
        }
        // This loop also handles sessions ending through silence, without growing the call stack.
        while (wakeRequested) {
            requireMicrophone()
            publish(loading = !wakeEnabled)
            val text = withContext(audioDispatcher) {
                audio.listen(
                    onListening = {
                        withContext(Dispatchers.Main) {
                            wakeEnabled = true
                            acquireWakeLock()
                            publish()
                        }
                    },
                    onWake = {
                        VoiceSignals.emit(VoiceEvent.Wake)
                        tick()
                    },
                    onRecording = { withContext(Dispatchers.Main) { publish(capturing = true) } },
                )
            }
            deliver(text)
        }
    }

    private suspend fun capture() {
        voice.warm()
        collectUtterance()
        restoreWake()
    }

    private suspend fun collectUtterance() {
        requireMicrophone()
        publish(loading = true)
        val text = withContext(audioDispatcher) {
            audio.capture {
                withContext(Dispatchers.Main) { publish(capturing = true) }
            }
        }
        deliver(text)
    }

    private suspend fun deliver(text: String) {
        publish()
        if (text.isBlank()) {
            report("I didn’t catch any speech. Try again.")
        } else {
            VoiceSignals.emit(VoiceEvent.Utterance(text))
            // The conversation controller takes over with its next command.
            delay(CONTROLLER_TIMEOUT_MS)
        }
    }

    private fun tick() {
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(WAKE_TICK_MS, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun publish(capturing: Boolean = false, speaking: Boolean = false, loading: Boolean = false) {
        VoiceSignals.update(VoiceEngineState(wakeEnabled, capturing, speaking, loading))
    }

    private fun report(message: String) {
        VoiceSignals.update(VoiceEngineState(listening = wakeEnabled, error = message))
        VoiceSignals.emit(VoiceEvent.Failure(message))
    }

    private fun failAndStop(error: Throwable) {
        val message = when (error) {
            is SecurityException -> "Microphone permission is required for voice capture."
            is LinkageError -> "The offline voice engine could not load on this device."
            else -> "Voice capture could not continue. Check microphone access and try again."
        }
        android.util.Log.w("JotVoice", message, error)
        stopVoice(message)
        VoiceSignals.emit(VoiceEvent.Failure(message))
    }

    private fun stopVoice(error: String? = null) {
        closing = true
        wakeRequested = false
        wakeEnabled = false
        operation?.cancel()
        speech.stop()
        releaseWakeLock()
        VoiceSignals.update(VoiceEngineState(error = error))
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (active === this) active = null
        val supervisor = scope.coroutineContext[Job]
        scope.cancel()
        speech.close()
        voice.close()
        releaseWakeLock()
        CoroutineScope(Dispatchers.Main.immediate).launch {
            try {
                // A cancelled audio job must close its stream before we release its model.
                supervisor?.join()
                withContext(audioDispatcher) { audio.close() }
            } finally {
                audioDispatcher.close()
            }
        }
        if (!closing) VoiceSignals.update(VoiceEngineState())
        super.onDestroy()
    }

    private fun acquireWakeLock() {
        if (wakeLockRenewal?.isActive == true) return
        val lock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jot:WakeListener")
            .apply {
                setReferenceCounted(false)
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
        wakeLock = lock
        wakeLockRenewal = scope.launch {
            try {
                while (true) {
                    delay(WAKE_LOCK_RENEW_MS)
                    lock.acquire(WAKE_LOCK_TIMEOUT_MS)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failAndStop(error)
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLockRenewal?.cancel()
        wakeLockRenewal = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun requireMicrophone() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO is required")
        }
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Jot voice", NotificationManager.IMPORTANCE_LOW),
        )
        val open = Intent().setClassName(this, "com.jot.app.MainActivity")
        val stop = Intent(this, VoiceService::class.java).setAction(STOP)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Jot voice is active")
            .setContentText("Open Jot or stop microphone access.")
            .setContentIntent(PendingIntent.getActivity(this, 100, open, PendingIntent.FLAG_IMMUTABLE))
            .addAction(Notification.Action.Builder(
                R.drawable.ic_mic, "Stop", PendingIntent.getService(this, 101, stop, PendingIntent.FLAG_IMMUTABLE),
            ).build())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .build()
    }

    companion object {
        const val START_WAKE = "com.jot.app.voice.START_WAKE"
        const val CAPTURE = "com.jot.app.voice.CAPTURE"
        const val STOP = "com.jot.app.voice.STOP"
        const val SPEAK = "com.jot.app.voice.SPEAK"
        const val SPEAK_LISTEN = "com.jot.app.voice.SPEAK_LISTEN"
        const val SPEAK_HOLD = "com.jot.app.voice.SPEAK_HOLD"
        const val RESUME_WAKE = "com.jot.app.voice.RESUME_WAKE"
        private const val CHANNEL_ID = "jot_voice"
        private const val NOTIFICATION_ID = 100
        private const val WAKE_TICK_MS = 40L
        // Long enough for the companion to read a note and answer before Jot speaks again.
        private const val CONTROLLER_TIMEOUT_MS = 75_000L
        private const val WAKE_LOCK_TIMEOUT_MS = 120_000L
        private const val WAKE_LOCK_RENEW_MS = 60_000L
        private val main = Handler(Looper.getMainLooper())
        private var active: VoiceService? = null

        fun send(context: Context, command: String, text: String? = null) {
            val app = context.applicationContext
            val intent = Intent(app, VoiceService::class.java).setAction(command)
            val dispatch = Runnable {
                val existing = active
                when {
                    existing != null && !existing.closing -> existing.handle(command, text)
                    command == STOP -> app.stopService(intent)
                    command == START_WAKE || command == CAPTURE -> {
                        try {
                            if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                throw SecurityException("RECORD_AUDIO is required")
                            }
                            app.startForegroundService(intent)
                        } catch (_: SecurityException) {
                            rejected("Microphone permission is required for voice capture.")
                        } catch (_: IllegalStateException) {
                            rejected("Open Jot to start the microphone.")
                        }
                    }
                    command == SPEAK_LISTEN -> rejected("The voice session ended. Tap the microphone to try again.")
                }
            }
            if (Looper.myLooper() == Looper.getMainLooper()) dispatch.run() else main.post(dispatch)
        }

        private fun rejected(message: String) {
            VoiceSignals.update(VoiceEngineState(error = message))
            VoiceSignals.emit(VoiceEvent.Failure(message))
        }
    }
}

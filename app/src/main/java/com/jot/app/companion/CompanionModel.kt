package com.jot.app.companion

import android.annotation.SuppressLint
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import com.jot.app.core.CompanionText
import com.jot.app.core.Reading
import com.jot.app.data.NoteRecord
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.security.MessageDigest

sealed interface CompanionState {
    data object Off : CompanionState
    data class Downloading(val progress: Float? = null, val waitingForWifi: Boolean = false) : CompanionState
    data object Ready : CompanionState
    data object Failed : CompanionState
}

class CompanionModel(private val context: Context, private val scope: CoroutineScope) {
    private val prefs = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
    private val downloads = context.getSystemService(DownloadManager::class.java)
    private val folder get() = File(context.getExternalFilesDir(null) ?: context.filesDir, FOLDER)
    private val model get() = File(folder, MODEL)
    private val part get() = File(folder, PART)
    private val mutable = MutableStateFlow<CompanionState>(CompanionState.Off)
    val state: StateFlow<CompanionState> = mutable.asStateFlow()
    @OptIn(ExperimentalCoroutinesApi::class)
    private val worker = Dispatchers.IO.limitedParallelism(1)
    private var engine: Engine? = null
    private var idle: Job? = null
    private var watch: Job? = null
    internal var timeoutMs = TIMEOUT_MS

    init { if (prefs.getBoolean(KEY_ON, false)) resume() }

    fun enable() {
        prefs.edit().putBoolean(KEY_ON, true).apply()
        if (state.value == CompanionState.Failed) prefs.edit().remove(KEY_DOWNLOAD).apply()
        resume()
    }

    fun disable() {
        prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it >= 0 }?.let { runCatching { downloads.remove(it) } }
        prefs.edit().clear().apply()
        watch?.cancel()
        mutable.value = CompanionState.Off
        scope.launch(worker) { close(); folder.deleteRecursively() }
    }

    fun warm() {
        if (state.value != CompanionState.Ready) return
        scope.launch(worker) { runCatching { engine() }.onFailure { Log.w(TAG, "Companion could not load.", it) }; rest() }
    }

    suspend fun read(note: String): Reading? =
        respond(CompanionText.READ, note, READ_TOKENS, CompanionText.examples)?.let(CompanionText::parse)

    suspend fun answer(question: String, notes: List<NoteRecord>): String? =
        respond(CompanionText.notesContext(notes), question, ANSWER_TOKENS)?.trim()?.trim('"')?.takeIf { it.isNotBlank() }

    private fun resume() {
        watch?.cancel()
        watch = scope.launch(Dispatchers.IO) {
            if (model.exists()) { verify(); return@launch }
            val id = prefs.getLong(KEY_DOWNLOAD, -1).takeIf { it >= 0 } ?: enqueue() ?: run { fail(); return@launch }
            while (isActive) {
                val status = query(id)
                when (status?.state) {
                    DownloadManager.STATUS_SUCCESSFUL -> { withContext(Dispatchers.IO) { part.renameTo(model) }; verify(); return@launch }
                    DownloadManager.STATUS_FAILED -> { fail(); return@launch }
                    null -> { disable(); return@launch }
                    else -> mutable.value = CompanionState.Downloading(status.progress, status.waitingForWifi)
                }
                delay(1000)
            }
        }
    }

    private fun enqueue(): Long? = runCatching {
        folder.mkdirs(); part.delete()
        val request = DownloadManager.Request(Uri.parse(URL))
            .setTitle("Jot companion")
            .setAllowedOverMetered(false)
            .setAllowedOverRoaming(false)
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
            .setDestinationInExternalFilesDir(context, FOLDER, PART)
        downloads.enqueue(request).also { prefs.edit().putLong(KEY_DOWNLOAD, it).apply() }
    }.onFailure { Log.w(TAG, "Companion download could not start.", it) }.getOrNull()

    private class Status(val state: Int, val progress: Float?, val waitingForWifi: Boolean)

    private fun query(id: Long): Status? = downloads.query(DownloadManager.Query().setFilterById(id))?.use { cursor ->
        if (!cursor.moveToFirst()) return null
        val done = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
        val reason = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
        val state = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
        val waiting = state == DownloadManager.STATUS_PAUSED &&
            (reason == DownloadManager.PAUSED_QUEUED_FOR_WIFI || reason == DownloadManager.PAUSED_WAITING_FOR_NETWORK)
        Status(state, (done.toFloat() / SIZE).coerceIn(0f, 1f), waiting)
    }

    private suspend fun verify() {
        if (prefs.getBoolean(KEY_VERIFIED, false) && model.length() == SIZE) { mutable.value = CompanionState.Ready; return }
        mutable.value = CompanionState.Downloading(1f)
        val ok = withContext(Dispatchers.IO) { model.length() == SIZE && sha256(model) == SHA256 }
        if (!ok) { model.delete(); fail(); return }
        prefs.edit().putBoolean(KEY_VERIFIED, true).remove(KEY_DOWNLOAD).apply()
        mutable.value = CompanionState.Ready
    }

    private fun fail() {
        prefs.edit().remove(KEY_DOWNLOAD).apply()
        part.delete()
        mutable.value = CompanionState.Failed
    }

    private suspend fun respond(system: String, input: String, maxTokens: Int, examples: List<Pair<String, String>> = emptyList()): String? {
        if (state.value != CompanionState.Ready) return null
        return withContext(worker) {
            idle?.cancel()
            try {
                engine().createConversation(ConversationConfig(
                    systemInstruction = Contents.of(system),
                    initialMessages = examples.flatMap { (asked, replied) -> listOf(Message.user(asked), Message.model(replied)) },
                    samplerConfig = SamplerConfig(1, 1.0, 1.0, 0),
                    maxOutputToken = maxTokens,
                )).use { conversation ->
                    val watchdog = scope.launch { delay(timeoutMs); conversation.cancelProcess() }
                    val started = System.nanoTime()
                    try {
                        val reply = conversation.sendMessage(input)
                        reply.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }
                            .also {
                                Log.i(TAG, "Companion replied in ${(System.nanoTime() - started) / 1_000_000} ms.")
                                if (prefs.getString(KEY_BACKEND, null) == TRYING_GPU) prefs.edit().putString(KEY_BACKEND, "gpu").apply()
                                if (Log.isLoggable(TAG, Log.DEBUG)) Log.d(TAG, "$input -> $it")
                            }
                    } finally { watchdog.cancel() }
                }
            } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Log.w(TAG, "Companion could not respond.", e); null
            } finally { rest() }
        }
    }

    // The GPU attempt is recorded synchronously and cleared by the first reply, so a native crash in
    // loading or in that reply falls back to CPU next launch.
    @SuppressLint("ApplySharedPref")
    private fun engine(): Engine {
        engine?.let { return it }
        val cache = File(context.cacheDir, FOLDER).apply { mkdirs() }.path
        val gpu = Build.HARDWARE != "ranchu" && when (prefs.getString(KEY_BACKEND, null)) { "cpu", TRYING_GPU -> false; else -> true }
        if (gpu) {
            if (prefs.getString(KEY_BACKEND, null) != "gpu") prefs.edit().putString(KEY_BACKEND, TRYING_GPU).commit()
            runCatching { load(Backend.GPU(), cache) }.onSuccess { return it }
                .onFailure { Log.i(TAG, "Companion GPU is unavailable; using CPU.", it) }
        }
        return load(Backend.CPU(), cache).also { prefs.edit().putString(KEY_BACKEND, "cpu").apply() }
    }

    private fun load(backend: Backend, cache: String): Engine {
        val started = System.nanoTime()
        return Engine(EngineConfig(modelPath = model.path, backend = backend, maxNumTokens = 2048, cacheDir = cache)).also {
            it.initialize(); engine = it
            Log.i(TAG, "Companion loaded on ${backend.name} in ${(System.nanoTime() - started) / 1_000_000} ms.")
        }
    }

    private fun rest() {
        idle?.cancel()
        idle = scope.launch { delay(IDLE_MS); withContext(worker) { close() } }
    }

    private fun close() { engine?.close(); engine = null }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(1 shl 20).use { input ->
            val buffer = ByteArray(1 shl 20)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SIZE = 2_588_147_712L
        private const val SHA256 = "181938105e0eefd105961417e8da75903eacda102c4fce9ce90f50b97139a63c"
        private const val URL = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/c658da437244f072bf2f1cabc916b60d66d97663/gemma-4-E2B-it.litertlm"
        private const val FOLDER = "companion"
        private const val MODEL = "gemma-4-E2B-it.litertlm"
        private const val PART = "$MODEL.part"
        private const val KEY_ON = "on"
        private const val KEY_DOWNLOAD = "download"
        private const val KEY_VERIFIED = "verified"
        private const val KEY_BACKEND = "backend"
        private const val TRYING_GPU = "trying-gpu"
        private const val TIMEOUT_MS = 60_000L
        private const val READ_TOKENS = 256
        private const val ANSWER_TOKENS = 80
        private const val IDLE_MS = 120_000L
        private const val TAG = "JotCompanion"
    }
}

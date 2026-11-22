package com.jot.app.companion

import android.app.DownloadManager
import android.content.Context
import android.content.SharedPreferences
import com.jot.app.core.CompanionText
import com.jot.app.core.Reading
import com.jot.app.data.NoteRecord
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class CompanionState { Off, SigningIn, Ready, InvalidToken, SignInFailed }

class CompanionModel internal constructor(
    context: Context,
    private val scope: CoroutineScope,
    private val relay: Relay,
    prefs: SharedPreferences,
    pendingPrefs: SharedPreferences = context.getSharedPreferences("companion_pending_signin", Context.MODE_PRIVATE),
) {
    constructor(context: Context, scope: CoroutineScope) : this(
        context, scope, CompanionRelay(), context.getSharedPreferences("companion_relay", Context.MODE_PRIVATE),
    )

    private class Session(val token: String, val lifetime: Job = Job())
    private val credentials = DeviceTokenStore(context, prefs)
    @Volatile private var session = credentials.read()?.let { Session(it) }
    private val mutable = MutableStateFlow(if (session == null) CompanionState.Off else CompanionState.Ready)
    val state: StateFlow<CompanionState> = mutable.asStateFlow()
    private val details = MutableStateFlow<CompanionAccount?>(null)
    val account: StateFlow<CompanionAccount?> = details.asStateFlow()
    private val feedback = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val messages = feedback.asSharedFlow()
    private var refresh: Job? = null
    private val signIn = BrowserSignIn(scope, relay, DeviceTokenStore(context, pendingPrefs), { mutable.value = it }, ::accept)

    init {
        if (session == null && signIn.pending) mutable.value = CompanionState.SigningIn
        if (session != null && signIn.pending) signIn.cancel()
        scope.launch(Dispatchers.IO) { removeLegacyModel(context) }
    }

    fun startSignIn(open: (String) -> Boolean) {
        if (session == null) signIn.start(open)
    }

    fun completeSignIn(receipt: String) { if (session == null) signIn.returned(receipt) }

    fun cancelSignIn() {
        if (session != null) return
        signIn.cancel()
        mutable.value = CompanionState.Off
    }

    fun setForeground(value: Boolean) {
        signIn.setForeground(value)
        if (value) refreshAccount()
    }

    private fun accept(token: String): Boolean {
        if (!credentials.write(token)) return false
        session = Session(token)
        mutable.value = CompanionState.Ready
        refreshAccount()
        return true
    }

    fun refreshAccount() {
        val active = session ?: return
        refresh?.cancel()
        refresh = scope.launch {
            val result = relay.account(active.token)
            if (session !== active) return@launch
            if (result.status == 401) clear(CompanionState.InvalidToken)
            else if (result.details != null) details.value = result.details
        }
    }

    fun disconnect() {
        val active = session
        clear(CompanionState.Off)
        if (active != null) scope.launch {
            if (!relay.revoke(active.token)) feedback.emit("Signed out on this phone. The server couldn’t confirm device removal.")
        }
    }

    private fun clear(state: CompanionState) {
        refresh?.cancel()
        signIn.cancel()
        session?.lifetime?.cancel()
        session = null
        credentials.clear()
        details.value = null
        mutable.value = state
    }

    suspend fun read(note: String): Reading? =
        respond { relay.read(it, note) }?.let(CompanionText::parse)

    suspend fun answer(question: String, notes: List<NoteRecord>): String? {
        if (notes.isEmpty()) return null
        return respond { relay.answer(it, question, notes) }
            ?.trim('"')?.takeIf(String::isNotBlank)
    }

    internal suspend fun <T> withDeviceCredential(requestWithToken: suspend (String) -> T): T? {
        val connection = session ?: return null
        val reply = coroutineScope {
            val request = async(start = CoroutineStart.LAZY) { requestWithToken(connection.token) }
            val handle = connection.lifetime.invokeOnCompletion { request.cancel() }
            try {
                request.await()
            } catch (e: CancellationException) {
                currentCoroutineContext().ensureActive()
                null
            } finally {
                handle.dispose()
            }
        }
        return reply.takeIf { session === connection }
    }

    private suspend fun respond(requestWithToken: suspend (String) -> RelayReply): String? {
        val reply = withDeviceCredential(requestWithToken) ?: return null
        if (reply.status == 401 || reply.status == 403) {
            clear(CompanionState.InvalidToken)
        } else {
            when (reply.code) {
                "daily_quota" -> feedback.tryEmit("You’ve used today’s AI calls.")
                "monthly_budget" -> feedback.tryEmit("Jot has reached its shared AI spending limit.")
                "rate_limit", "concurrency_limit" -> feedback.tryEmit("AI is busy. Try again in a moment.")
            }
            refreshAccount()
        }
        return reply.text
    }

    /** Remove only files owned by the previous on-device companion. */
    private fun removeLegacyModel(context: Context) {
        val legacy = context.getSharedPreferences("companion", Context.MODE_PRIVATE)
        val download = legacy.getLong("download", -1L)
        if (download < 0 || runCatching {
                context.getSystemService(DownloadManager::class.java).remove(download)
            }.isSuccess) {
            if (legacy.all.isNotEmpty()) legacy.edit().clear().apply()
        }
        listOfNotNull(context.filesDir, context.getExternalFilesDir(null)).forEach { root ->
            val folder = File(root, "companion")
            File(folder, "gemma-4-E2B-it.litertlm").delete()
            File(folder, "gemma-4-E2B-it.litertlm.part").delete()
            folder.delete()
        }
        File(context.cacheDir, "companion").deleteRecursively()
    }
}

package com.jot.app.companion

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Main-thread owner of the browser handoff. Only the encrypted store retains a pending exchange. */
internal class BrowserSignIn(
    private val scope: CoroutineScope,
    private val relay: Relay,
    private val pendingStore: DeviceTokenStore,
    private val state: (CompanionState) -> Unit,
    private val accept: (String) -> Boolean,
) {
    private class Pending(
        val pollToken: String,
        val expiresAt: Long,
        var receipt: String? = null,
        var nextReceipt: String? = null,
    )

    private var flow = restore()
    private var startJob: Job? = null
    private var pollJob: Job? = null
    private var expiryJob: Job? = null
    private var foreground = false
    private var redeeming = false
    private var generation = 0L
    val pending get() = flow != null || startJob?.isActive == true

    init { flow?.let(::watchExpiry) }

    fun start(open: (String) -> Boolean) {
        if (pending) return
        state(CompanionState.SigningIn)
        val current = ++generation
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val start = relay.startSignIn()
            if (generation != current) return@launch
            if (start == null || !secret.matches(start.pollToken) || !validExpiry(start.expiresAt)) {
                fail()
                return@launch
            }
            val attempt = Pending(start.pollToken, start.expiresAt)
            if (!persist(attempt)) { fail(); return@launch }
            flow = attempt
            watchExpiry(attempt)
            if (!open(start.browserUrl)) fail()
        }
        startJob = job
        job.start()
    }

    fun returned(receipt: String) {
        val attempt = flow ?: return
        if (!secret.matches(receipt)) return
        if (attempt.receipt == null) attempt.receipt = receipt
        else if (attempt.receipt != receipt) attempt.nextReceipt = receipt
        if (!persist(attempt)) { fail(); return }
        pollIfReady()
    }

    fun setForeground(value: Boolean) {
        foreground = value
        if (value) pollIfReady()
    }

    fun cancel() {
        generation++
        flow = null
        startJob?.cancel()
        pollJob?.cancel()
        expiryJob?.cancel()
        startJob = null
        pollJob = null
        expiryJob = null
        redeeming = false
        pendingStore.clear()
    }

    private fun pollIfReady() {
        val attempt = flow ?: return
        if (pollJob?.isActive == true || !foreground) return
        if (!validExpiry(attempt.expiresAt)) { fail(); return }
        if (attempt.receipt == null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (flow === attempt && foreground) {
                if (!validExpiry(attempt.expiresAt)) { fail(); return@launch }
                val receipt = attempt.receipt ?: return@launch
                redeeming = true
                val reply = relay.pollSignIn(attempt.pollToken, receipt)
                if (flow !== attempt) return@launch
                redeeming = false
                when (reply.status) {
                    200 -> {
                        if (reply.token != null && accept(reply.token)) cancel() else fail()
                        return@launch
                    }
                    400, 403, 410 -> {
                        // An unrelated return link must not discard this browser sign-in.
                        attempt.receipt = attempt.nextReceipt
                        attempt.nextReceipt = null
                        if (!persist(attempt)) { fail(); return@launch }
                    }
                    0, 202, 429, in 500..599 -> {
                        if (!validExpiry(attempt.expiresAt)) { fail(); return@launch }
                        if (!foreground) return@launch
                        delay(if (reply.status == 429) 6_000 else 3_000)
                    }
                    else -> { fail(); return@launch }
                }
            }
            if (flow === attempt && !validExpiry(attempt.expiresAt)) fail()
        }
        pollJob = job
        job.start()
    }

    private fun watchExpiry(attempt: Pending) {
        expiryJob?.cancel()
        expiryJob = scope.launch {
            delay((attempt.expiresAt - System.currentTimeMillis()).coerceAtLeast(0))
            if (flow === attempt && !redeeming) fail()
        }
    }

    private fun persist(attempt: Pending): Boolean = pendingStore.write(
        listOf(attempt.expiresAt.toString(), attempt.pollToken, attempt.receipt.orEmpty(), attempt.nextReceipt.orEmpty())
            .joinToString(".")
    )

    private fun restore(): Pending? {
        val stored = pendingStore.read() ?: return null
        val parts = stored.split('.')
        val expiry = parts.firstOrNull()?.toLongOrNull()
        if (parts.size != 4 || expiry == null || !validExpiry(expiry) || !secret.matches(parts[1]) ||
            parts.drop(2).any { it.isNotEmpty() && !secret.matches(it) }) {
            pendingStore.clear()
            return null
        }
        return Pending(parts[1], expiry, parts[2].ifEmpty { null }, parts[3].ifEmpty { null })
    }

    private fun validExpiry(expiresAt: Long): Boolean {
        val now = System.currentTimeMillis()
        return expiresAt > now && expiresAt <= now + 30 * 60_000L
    }

    private fun fail() {
        cancel()
        state(CompanionState.SignInFailed)
    }

    private companion object { val secret = Regex("[A-Za-z0-9_-]{43}") }
}

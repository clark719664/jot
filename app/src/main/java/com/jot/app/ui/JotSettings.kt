package com.jot.app.ui

import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import com.jot.app.capture.VoiceEngineState
import com.jot.app.companion.CompanionAccount
import com.jot.app.companion.CompanionState
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun JotSettings(
    state: JotUiState,
    voice: VoiceEngineState,
    companion: CompanionState,
    account: CompanionAccount?,
    billingBusy: Boolean,
    callbacks: JotCallbacks,
    onClose: () -> Unit,
) {
    var showAccount by rememberSaveable { mutableStateOf(false) }
    val colors = MaterialTheme.colorScheme
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = colors.surface, tonalElevation = 0.dp,
        contentWindowInsets = { WindowInsets.safeDrawing },
    ) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SheetHeader(if (showAccount) "AI" else "Settings", Stage.HOME, true, onClose)
            if (showAccount) {
                AiAccount(companion, account, billingBusy, callbacks)
            } else {
                val enabled = !voice.loading && !voice.capturing && !voice.speaking
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp)
                        .toggleable(voice.listening, enabled = enabled, role = Role.Switch, onValueChange = callbacks.onWakeToggle),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Hey Jot", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Switch(checked = voice.listening, onCheckedChange = null, enabled = enabled)
                }
                HorizontalDivider(color = colors.outlineVariant)
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button) { showAccount = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("AI", Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                    Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = colors.onSurfaceVariant)
                }
            }
            Feedback(state, voice, callbacks)
        }
    }
}

@Composable
private fun AiAccount(companion: CompanionState, account: CompanionAccount?, billingBusy: Boolean, callbacks: JotCallbacks) {
    val colors = MaterialTheme.colorScheme
    when (companion) {
        CompanionState.SigningIn -> {
            Text("Finish signing in in your browser, then choose Return to Jot.", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = callbacks.onCancelSignIn) { Text("Cancel") }
        }
        CompanionState.Ready -> {
            if (account == null) {
                TextButton(onClick = callbacks.onRefreshCompanion) { Text("Refresh plan") }
            } else {
                AiPlan(account, billingBusy, callbacks.onRefreshCompanion, callbacks.onPurchase, callbacks.onSetRenewal)
            }
            HorizontalDivider(color = colors.outlineVariant)
            TextButton(onClick = callbacks.onDisconnectCompanion, enabled = !billingBusy) { Text("Sign out") }
        }
        CompanionState.Off, CompanionState.InvalidToken, CompanionState.SignInFailed -> {
            val error = when (companion) {
                CompanionState.InvalidToken -> "Your session expired. Sign in again."
                CompanionState.SignInFailed -> "Sign-in didn’t finish. Try again."
                else -> null
            }
            if (error != null) Text(error, Modifier.semantics { liveRegion = LiveRegionMode.Polite }, color = colors.error)
            Text("5 free AI calls per day.", style = MaterialTheme.typography.titleMedium)
            Text(
                "AI sends note text, questions, and relevant saved notes through Jot’s server to OpenAI.",
                style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
            )
            PrimaryAction("Sign in", true, Modifier.fillMaxWidth(), callbacks.onSignInCompanion)
        }
    }
}

@Composable
internal fun AiPlan(
    account: CompanionAccount,
    busy: Boolean,
    onRefresh: () -> Unit,
    onCard: () -> Unit,
    onSetRenewal: (Boolean) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val context = LocalContext.current
    val end = account.periodEnd?.let { DateFormat.getMediumDateFormat(context).format(Date(it * 1000L)) }
    var confirmRenewal by rememberSaveable(account.renews) { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (account.paid) "Paid plan" else "Free plan", style = MaterialTheme.typography.titleMedium)
            val usage = when {
                account.paid -> "100 AI calls a day"
                account.freeCallsRemaining != null -> "${account.freeCallsRemaining} of 5 daily AI calls left"
                else -> "5 AI calls per day."
            }
            Text(usage, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            if (!account.paid && account.freeCallsRemaining == 0) {
                val reset = LocalDate.now(ZoneOffset.UTC).plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
                val whenLocal = if (DateUtils.isToday(reset)) "today" else "tomorrow"
                val time = DateFormat.getTimeFormat(context).format(Date(reset))
                Text("Resets $whenLocal at $time.", style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            }
        }
        IconButton(onClick = onRefresh, enabled = !busy) { Icon(Icons.Outlined.Refresh, "Refresh plan", tint = colors.primary) }
    }
    if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().semantics { contentDescription = "Updating subscription" })
    if (!account.paid && account.billingAvailable) {
        Text(
            "$4.99/month for 100 AI calls a day. AI can pause if Jot reaches its shared monthly limit.",
            style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
        )
        PrimaryAction(if (account.canManageBilling) "Retry card payment" else "Pay by card", !busy, Modifier.fillMaxWidth(), onCard)
    }
    if (account.canManageBilling) {
        val detail = when {
            account.renews && end != null -> "Renews $end"
            !account.renews && end != null -> "Subscription ends $end"
            account.renews -> "Renews monthly"
            else -> "Renewal canceled"
        }
        Text(detail, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
        TextButton(onClick = { confirmRenewal = true }, enabled = !busy) {
            Text(if (account.renews) "Cancel renewal" else "Resume renewal")
        }
    }
    if (confirmRenewal && account.canManageBilling) {
        val resume = !account.renews
        val explanation = if (resume) {
            if (end == null) "Your plan will renew for $4.99/month." else "Your plan will renew for $4.99/month on $end."
        } else {
            if (end == null) "Renewal will stop at the end of this billing period." else "Your subscription will end on $end."
        }
        AlertDialog(
            onDismissRequest = { confirmRenewal = false },
            title = { Text(if (resume) "Resume renewal?" else "Cancel renewal?") },
            text = { Text(explanation) },
            dismissButton = { TextButton(onClick = { confirmRenewal = false }) { Text("Back") } },
            confirmButton = {
                TextButton(onClick = { confirmRenewal = false; onSetRenewal(resume) }, enabled = !busy) {
                    Text(if (resume) "Resume" else "Cancel renewal")
                }
            },
        )
    }
}

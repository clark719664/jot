package com.jot.app.billing

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import com.jot.app.companion.CompanionModel
import com.stripe.android.PaymentConfiguration
import com.stripe.android.paymentsheet.PaymentSheet
import com.stripe.android.paymentsheet.PaymentSheetResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal class NativeBilling(
    private val activity: ComponentActivity,
    private val companion: CompanionModel,
    private val notice: (String) -> Unit,
) {
    private val relay = CardBillingRelay()
    private val working = MutableStateFlow(false)
    val busy = working.asStateFlow()
    private var refresh: Job? = null

    // Register before STARTED so Android can deliver a result after activity recreation.
    private val sheet = PaymentSheet.Builder { result ->
        working.value = false
        when (result) {
            is PaymentSheetResult.Completed -> awaitConfirmation()
            is PaymentSheetResult.Canceled -> companion.refreshAccount()
            is PaymentSheetResult.Failed -> {
                companion.refreshAccount()
                notice("Payment couldn’t finish. Check your plan before trying again.")
            }
        }
    }.build(activity)

    fun purchase() = runBilling {
        val result = companion.withDeviceCredential(relay::purchase)
        when (result) {
            is PurchaseReply.Invoice -> {
                if (!activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return@runBilling false
                PaymentConfiguration.init(activity.applicationContext, result.publishableKey)
                val config = PaymentSheet.Configuration.Builder("Jot")
                    .allowsDelayedPaymentMethods(false)
                    .link(PaymentSheet.LinkConfiguration(PaymentSheet.LinkConfiguration.Display.Never))
                    .build()
                sheet.presentWithPaymentIntent(result.paymentIntentClientSecret, config)
                true
            }
            PurchaseReply.Pending -> {
                awaitConfirmation()
                false
            }
            else -> {
                companion.refreshAccount()
                notice("Couldn’t open payment. Try again in a moment.")
                false
            }
        }
    }

    fun setRenewal(renews: Boolean) = runBilling {
        val updated = companion.withDeviceCredential { token -> relay.setRenewal(token, renews) }
        companion.refreshAccount()
        notice(when {
            updated != true -> "Couldn’t change renewal. Refresh your plan and try again."
            renews -> "Monthly renewal is on."
            else -> "Monthly renewal is off."
        })
        false
    }

    private fun runBilling(action: suspend () -> Boolean) {
        if (!working.compareAndSet(false, true)) return
        activity.lifecycleScope.launch {
            var sheetShowing = false
            try {
                sheetShowing = action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                notice("Billing couldn’t finish. Refresh your plan and try again.")
            } finally {
                if (!sheetShowing) working.value = false
            }
        }
    }

    private fun awaitConfirmation() {
        companion.refreshAccount()
        notice("Checking payment status…")
        refresh?.cancel()
        refresh = activity.lifecycleScope.launch {
            // Stripe's webhook can arrive after the payment screen closes.
            for (wait in listOf(3_000L, 10_000L, 20_000L)) {
                delay(wait)
                if (companion.account.value?.paid != false) break
                companion.refreshAccount()
            }
        }
    }
}

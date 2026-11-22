package com.jot.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jot.app.companion.CompanionAccount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiPlanTest {
    @get:Rule val compose = createComposeRule()
    private val free = CompanionAccount(
        paid = false, freeCallsRemaining = 0, billingAvailable = true, canManageBilling = false,
        renews = false, periodEnd = null,
    )
    private var cards = 0
    private val renewals = mutableListOf<Boolean>()

    private fun show(account: CompanionAccount, busy: Boolean = false) {
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
                JotTheme {
                    Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                        AiPlan(account, busy, {}, { cards++ }, { renewals += it })
                    }
                }
            }
        }
    }

    @Test fun purchaseShowsPriceAndSharedCapBeforeOpeningCardSheet() {
        show(free)
        compose.onNodeWithText("0 of 5 daily AI calls left").assertIsDisplayed()
        compose.onNodeWithText("Resets", substring = true).assertExists()
        compose.onNodeWithText("$4.99/month", substring = true).assertIsDisplayed()
        compose.onNodeWithText("shared monthly limit", substring = true).assertExists()
        compose.onNodeWithText("Pay by card").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, cards) }
    }

    @Test fun unconfiguredCheckoutLeavesQuotaAndRefresh() {
        show(free.copy(freeCallsRemaining = 3, billingAvailable = false))
        compose.onNodeWithText("3 of 5 daily AI calls left").assertIsDisplayed()
        compose.onNodeWithContentDescription("Refresh plan").assertIsDisplayed()
        compose.onNodeWithText("Pay by card").assertDoesNotExist()
    }

    @Test fun paidPlanCanCancelAtPeriodEndWhenCheckoutIsClosed() {
        show(free.copy(paid = true, freeCallsRemaining = null, canManageBilling = true, billingAvailable = false, renews = true, periodEnd = 1_900_000_000L))
        compose.onNodeWithText("100 AI calls a day").assertIsDisplayed()
        compose.onNodeWithText("Cancel renewal").performScrollTo().performClick()
        compose.onNodeWithText("Your subscription will end on", substring = true).assertIsDisplayed()
        compose.runOnIdle { assertEquals(emptyList<Boolean>(), renewals) }
        compose.onNode(hasText("Cancel renewal") and hasAnyAncestor(isDialog())).performClick()
        compose.runOnIdle { assertEquals(listOf(false), renewals) }
    }

    @Test fun paidPlanCanResumeOnlyAfterConfirmation() {
        show(free.copy(paid = true, freeCallsRemaining = null, canManageBilling = true, renews = false, periodEnd = 1_900_000_000L))
        compose.onNodeWithText("Resume renewal").performScrollTo().performClick()
        compose.onNodeWithText("Your plan will renew for $4.99/month", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Resume").performClick()
        compose.runOnIdle { assertEquals(listOf(true), renewals) }
    }

    @Test fun inProgressPaymentDisablesCardButton() {
        show(free, busy = true)
        compose.onNodeWithText("Pay by card").assertIsNotEnabled()
    }
}

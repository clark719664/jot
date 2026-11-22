package com.jot.app.billing

import com.google.gson.JsonObject
import com.jot.app.companion.CompanionRelay

internal sealed interface PurchaseReply {
    class Invoice(val publishableKey: String, val paymentIntentClientSecret: String) : PurchaseReply
    object Pending : PurchaseReply
    object Failed : PurchaseReply
}

internal class CardBillingRelay(
    private val post: suspend (String, String, JsonObject) -> Pair<Int, JsonObject?>,
) {
    constructor() : this(CompanionRelay()::billingRequest)

    suspend fun purchase(token: String): PurchaseReply {
        val (status, body) = post("billing/subscribe", token, JsonObject())
        if (status == 409) return PurchaseReply.Pending
        if (status != 200 || body == null) return PurchaseReply.Failed
        val key = body.string("publishableKey") ?: return PurchaseReply.Failed
        val secret = body.string("paymentIntentClientSecret") ?: return PurchaseReply.Failed
        if (!publicKeyFormat.matches(key) || !invoiceSecretFormat.matches(secret)) return PurchaseReply.Failed
        return PurchaseReply.Invoice(key, secret)
    }

    suspend fun setRenewal(token: String, renews: Boolean): Boolean {
        val (status, body) = post("billing/renewal", token, JsonObject().apply { addProperty("renews", renews) })
        val value = body?.get("renews")
        return status == 200 && value != null && value.isJsonPrimitive &&
            value.asJsonPrimitive.isBoolean && value.asBoolean == renews
    }

    private fun JsonObject.string(name: String) = get(name)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString

    private companion object {
        val publicKeyFormat = Regex("pk_(test|live)_[A-Za-z0-9_]{1,480}")
        val invoiceSecretFormat = Regex("pi_[A-Za-z0-9]{1,128}_secret_[A-Za-z0-9_-]{1,350}")
    }
}

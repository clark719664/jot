# Jot AI subscriptions

Jot’s direct-distribution Android app offers a $4.99 USD monthly AI subscription. Free accounts receive five AI calls each UTC day and paid accounts 100. The shared monthly service limit applies to both plans. Local notes and actions remain free. The relay accepts account and AI requests with payment configuration absent.

## Configure the merchant

Use the merchant that receives PC Cast donations. In Payhip, tap **Account → Settings → Payment Details** to see whether Stripe is connected. If you have multiple Stripe accounts, confirm a previous donation in the account you select. Use a separate Worker and database for the sandbox. [Payhip payment settings](https://help.payhip.com/article/65-connecting-your-stripe-account)

1. Open Stripe’s product catalog in your phone browser. Tap **Add product**, enter **Jot AI**, choose a flat recurring price of **USD 4.99 every month**, and save. Copy the product ID (`prod_…`) and price ID (`price_…`). [Open product catalog](https://dashboard.stripe.com/products) · [Stripe product setup](https://docs.stripe.com/products-prices/manage-prices)
2. Open **API keys**. Copy the publishable key, then tap **Create restricted key**. Start with zero permissions, name the key **Jot AI relay**, and use the permissions below. Copy the resulting `rk_…` once into a password manager or the Worker secret field. [Open API keys](https://dashboard.stripe.com/apikeys) · [Restricted key setup](https://docs.stripe.com/keys/restricted-api-keys)
3. Open **Webhooks**. Tap **Create an event destination**, then **Your account** and **Snapshot**. Choose version `2026-11-18.endive` and the events listed below, tap **Continue**, and choose **Webhook endpoint**. Use `https://jot-companion.clark719664.workers.dev/billing/webhook` for production. After creation, reveal the destination signing secret (`whsec_…`). [Open webhooks](https://dashboard.stripe.com/webhooks) · [Stripe webhook setup](https://docs.stripe.com/webhooks)
4. In Cloudflare, open **Workers & Pages**, tap **jot-companion**, then **Settings → Variables and Secrets → Add**. Choose **Secret** and enter each of the five names and values below for the same merchant and mode. Tap **Deploy** to apply them. [Open Cloudflare](https://dash.cloudflare.com/) · [Worker secret setup](https://developers.cloudflare.com/workers/configuration/secrets/)

### Restricted key permissions

| Access | Card subscriptions |
| --- | --- |
| Write | Customers, Subscriptions |
| Read | Prices, Products, Invoices, Payment Intents, Charges and Refunds, Payment Disputes |

Leave other resources and connected-account access at **None**. The relay reads invoice payments as part of Invoices. If sandbox testing returns a permission error, open **API keys → Jot AI relay → View request logs** for the resource that needs access. [Stripe resource names](https://docs.stripe.com/stripe-apps/reference/permissions) · [Check restricted permissions](https://docs.stripe.com/keys/restricted-api-keys)

### Worker bindings

| Binding | Value |
| --- | --- |
| `STRIPE_SECRET_KEY` | Restricted server-side key (`rk_…`). |
| `STRIPE_PUBLISHABLE_KEY` | Publishable key for Android PaymentSheet. |
| `STRIPE_PRODUCT_ID` | Active Jot AI subscription product. |
| `STRIPE_PRICE_ID` | Active fixed-price monthly USD price for that product, 499 cents per licensed unit. |
| `STRIPE_WEBHOOK_SECRET` | Signing secret for this Worker's HTTPS webhook endpoint. |

### Webhook events

- `customer.subscription.created`, `customer.subscription.updated`, `customer.subscription.deleted`, `customer.subscription.paused`, `customer.subscription.resumed`
- `invoice.paid`, `invoice.payment_failed`, `invoice.marked_uncollectible`, `invoice.voided`
- `charge.refunded`, `charge.dispute.created`, `charge.dispute.closed`

The app requests an incomplete subscription on the authenticated account and confirms its invoice through PaymentSheet. The webhook retrieves the customer’s current subscription, invoice and settled charge before granting a paid allowance. Turning off renewal keeps paid access through the funded period. Processing payments, failed payments, refunds and open disputes can change the allowance. A failed webhook reconciliation returns HTTP 503 for delivery retry. [Stripe subscription creation](https://docs.stripe.com/api/subscriptions/create) · [Stripe webhook delivery](https://docs.stripe.com/webhooks) · [Stripe invoice payments](https://docs.stripe.com/api/invoice-payment/list)

Before enabling purchases for customers, exercise first payment, declined payment, renewal, cancellation, resumption and a refund in the merchant's sandbox. Verify that the Worker receives the events and that the same account on a second device sees the correct allowance. Move production to its own live bindings and live webhook destination after verifying merchant ownership. Never reuse sandbox customer records in a live database.

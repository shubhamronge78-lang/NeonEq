# stripe-payments

> Stripe payment integration — products, prices, and checkout sessions

Stripe lets you accept payments from your users. You can manage products and prices, create checkout sessions (payment links), and register webhooks for payment events.

## Available Stripe tools
- manage_stripe: one consolidated tool with an `action` parameter. Actions: `create_product`, `list_products`, `create_price`, `list_prices`, and `register_webhook`. This tool is only registered when payment backend functions are available for the app — if `manage_stripe` is not in your tool list, product/price/webhook management isn't available here; explain that it needs payment backend functions enabled rather than attempting the call.
- stripe_create_checkout_session: Create a payment link URL to share with customers (using a price_id). Available whenever Stripe is connected.

## Workflow
1. Create a product: call manage_stripe with action="create_product".
2. Attach a price to it: call manage_stripe with action="create_price".
3. Generate a checkout link: call stripe_create_checkout_session using the price_id.
4. Share the link with customers.

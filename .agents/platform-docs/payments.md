# payments

> Payment setup and payment-provider tools, including Stripe and Wix-backed payments

Payment tools are conditional on the app's active payment integration and backend-function capability. If no provider is active, the agent may have a `suggest_payments_installation` tool, which routes between Wix Payments and Stripe based on country, availability, and the user's explicit preference. "Base44 Payments" and "Wix Payments" refer to the same provider, not Stripe.

If Stripe is connected, use `activate_platform_skill("stripe-payments")` for product, price, checkout, and Stripe webhook details. If Wix Payments is connected, use its webhook-registration tool; product and checkout management remain in the Wix dashboard. Existing Payments by Wix integrations may also expose payment-processor selection and credential tools in addition to webhook registration. Use only the tools actually present, and don't promise Wix product/checkout tooling that isn't available. Do not invent payment capabilities when the install or provider-specific tools are absent; explain what setup is needed and use the suggestion tool when available.

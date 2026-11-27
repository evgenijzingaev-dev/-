# ChatGPT connection (experimental)

First-stage connectivity test only. The Android application uses the documented local personal-project Sign in with ChatGPT flow (system browser, PKCE, nonce, loopback redirect, verified ID token, consent scope, Android Keystore protected access token). No API key or private ChatGPT endpoint. No catalog or personal food information is sent by the test. The current menu engine is unchanged. A single account is supported; token renewal is not implemented in this stage, so reconnect after expiry. Disconnect removes local credentials; revoke the authorization in ChatGPT account settings if desired.

Sources: https://developers.openai.com/siwc/token-sharing-open-source/sign-in and https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference .

Acceptance: install signed build, open Settings > ChatGPT connection, sign in via official browser and approve plan usage; return, press test, verify a completed model response. Verify denial and expired session fail closed. No claim of live success until tested on Android.

## ChatGPT-first architecture (experimental)

The main screen is now a conversational assistant, not a recipe form. ChatGPT selects bounded read-only local tools for the basket, catalog and package quote. Prices and nutrition are local data; the model does not claim unverified totals or availability. The legacy meal planner remains a separate explicit offline fallback. The existing catalog sync is unchanged.

The app sends the user's prompt and necessary tool results to OpenAI through authorized plan usage, not to a separate SmartBasket server. Recent chat turns live in Activity memory only. This stage cannot write the basket, update the store, start a sync, place orders, or inherit ChatGPT's existing chat history. Next steps: typed action proposals with local validation and user confirmation, durable local context, token renewal, and end-to-end Android tests. Treat the draft as unverified until the OAuth flow and actual model tool calls succeed on Android.

## Basket proposals

The model can propose a complete replacement shopping list using validated quantities and units. Local code computes package costs from a fresh snapshot, preserves matching at-home amounts, marks missing prices, and displays a confirmation dialog with the budget. No automatic basket replacement or order. The prior read-only limitation above refers to the first commit; this extension is not yet tested with a live account.

## Local context

Recent 24 messages persist in device-local preferences (12 sent per request), plus an editable 4,000-character food/shopping profile. ChatGPT can propose a profile update only after a user asks to remember/forget something; confirmation is required. New chat clears history, not profile; clear-all removes both locally, not remote account data. Days, people, budget, shipping and calorie target are included in requests and saved. No access to existing ChatGPT account memory. Local storage is not separately encrypted. Live account behavior still requires Android testing.

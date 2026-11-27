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

## Generated dishes and iterative optimization

ChatGPT publishes structured dishes with serving count, ingredients, preparation and optional whole-ingredient estimated nutrition. Local arithmetic derives per-serving totals; actual cached product data and generic reference data take precedence over model estimates. Missing values remain unknown and estimates are labelled. The Recipes screen shows generated cards instead of the static recipe list; users can save up to 60 locally. Static recipes still exist only for the explicit offline planner. Quote tools return up to five locally ranked price alternatives; basket proposals return validated per-item costs, budget status and available nutrition, allowing the model to revise its selection. No globally optimal guarantee; no live inference verification yet.

## Session renewal (0.11.0)

Renew on demand before expiry using the issued client ID and official OAuth refresh grant. The replacement access/refresh pair is committed together under Android Keystore encryption. Refreshes, sign-in and sign-out are serialized in this single-process app. Invalid grants require a new login; transient network failures do not trigger a paid fallback. Existing 0.10.2 sessions lack a refresh token and require one new sign-in. Sign-out attempts the discovered official revocation endpoint, removes local credentials, retains the client/subject registration, and reports unconfirmed revocation. Only one registered account is supported in this version. A real Android login and inference test is still required.

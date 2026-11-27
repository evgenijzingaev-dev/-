# ChatGPT connection (experimental)

First-stage connectivity test only. The Android application uses the documented local personal-project Sign in with ChatGPT flow (system browser, PKCE, nonce, loopback redirect, verified ID token, consent scope, Android Keystore protected access token). No API key or private ChatGPT endpoint. No catalog or personal food information is sent by the test. The current menu engine is unchanged. A single account is supported; token renewal is not implemented in this stage, so reconnect after expiry. Disconnect removes local credentials; revoke the authorization in ChatGPT account settings if desired.

Sources: https://developers.openai.com/siwc/token-sharing-open-source/sign-in and https://developers.openai.com/siwc/token-sharing-open-source/models-and-inference .

Acceptance: install signed build, open Settings > ChatGPT connection, sign in via official browser and approve plan usage; return, press test, verify a completed model response. Verify denial and expired session fail closed. No claim of live success until tested on Android.

# SoundCloud integration: limits and next steps

## Current implementation

- The app screen uses the public SoundCloud catalog for track/playlist search, trending charts, playlist tracks, related tracks and playback.
- Requests run on the IO dispatcher, stale search results are ignored, result errors are shown with a retry action, expired public `client_id` values are rediscovered once, and transient network/429/5xx responses receive bounded retries.
- The screen now exposes a recommendations tab and selectable chart regions. The region value is sent using SoundCloud's web-player `api-v2` chart query and has not yet been live-verified in this environment.

## Account features are not safely available yet

SoundCloud's current [API guide](https://developers.soundcloud.com/docs/api/guide) documents OAuth 2.1 with PKCE and account-scoped APIs for likes, comments and user feeds. It also says an API app must be registered, current clients require a `client_secret`, and user tokens must be refreshed. The current built-in module only scrapes the public web player's transient `client_id`; it does not authenticate a SoundCloud user. Therefore login, user's likes, posting comments, followed-feed recommendations and account-aware region/availability checks must not be simulated with that public key.

A production implementation needs an approved SoundCloud developer app and a secure token-exchange design. Do not embed the confidential `client_secret` in the APK or repository. A backend token broker would need security/privacy review, or SoundCloud must offer a supported native public-client flow. Once that is resolved, add OAuth consent/PKCE + refresh/revoke, encrypted token storage, read-only likes/feed first, then explicit-write likes/comments with consent and rate-limit handling.

## Remaining validation

1. Run a live opt-in smoke test for search, playlist paging, charts, region filtering, related tracks and playback; current network test is opt-in and was skipped in this run.
2. Confirm the public `api-v2` chart-region parameter still works for each offered region; if not, remove unavailable regions rather than silently returning global charts.
3. Test offline, 401 key rotation, 429 retry, 403/private/DRM tracks, long mixes, app background/resume and rapid query changes on a real Android device.
4. Add automated coroutine/ViewModel tests for stale-request suppression and visible error/retry states; unit tests currently cover module retry/key rotation/region query, not network playback or visual smoothness.
5. Keep account login, comments and likes explicitly marked unavailable until official OAuth credentials and a safe token architecture are in place.

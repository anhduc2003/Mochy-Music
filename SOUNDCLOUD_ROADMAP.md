# SoundCloud: verified capabilities and remaining integration

## Fixes after v8.4.4

- SoundCloud Home/Explore entries follow the module switch. A disabled module does not begin catalog/comment requests; re-enabling reloads the current view. Old queue items cannot start a new SoundCloud network resolution while disabled. Existing library/history records are retained.
- Live read-only probes on 2026-11-20 reproduced HTTP 400 from `/charts` with all tested `region` values (`soundcloud:regions:VN`, `soundcloud:regions:US`, `VN`, `vn`). Global charts return 200 without that parameter. `country=VN` returned the same leading IDs as global, so it is not a verified replacement.
- Removed unsupported chart-region parameters and country chips. **Nhạc Việt** searches `nhạc Việt`; automatic Home recommendations, track search, related recommendations and playlist search keep only streamable entries with Vietnamese public metadata evidence (VN metadata, `vi` language, Vietnamese-specific characters or tags/genre). Explicit foreign country metadata excludes a result; entries without evidence are omitted. This is metadata curation, not a verified physical-location or licensing guarantee.
- Public track metadata includes optional `likes_count`/`favoritings_count` and `comment_count`; these now reach the screen. Missing counts remain absent rather than being invented as zero.
- Public comment reads work with `threaded=0&filter_replies=0`; omitting `threaded` reproduced 400. Menus show author, text and timestamp, with pages of 50 comments and a load-more action. Loading a later page preserves existing rows on failure, supports retry, deduplicates IDs and stops on a repeated page. Offsets are computed locally; response URLs are never followed with credentials.
- Nested playlist/related views retain a stack of browse states. Back restores the original playlist/search; retry reloads the same view rather than replacing its parent snapshot. Android system back follows the same behavior as the toolbar.
- Media resolution preserves existing query parameters, retains alternative URLs with the same quality preset, validates HTTPS URLs and recognizes extensionless HLS from its protocol metadata. Repeated songs in a playlist have unique Compose keys.
- Malformed successful catalog/comment responses are surfaced as errors rather than valid empty results. Error/retry content is in the scrollable list and does not cover search/navigation controls.
- Direct SoundCloud playback now uses `playbackPicks`, honoring the selected quality and global data-saver cap instead of always defaulting to best quality.
- Search, global charts, related tracks, track detail and comment endpoint shapes were checked against live SoundCloud. Mock HTTP tests protect the region/comment parameter corrections. These checks do not prove Android playback or visual performance.

## After v8.4.10: artwork, account, comment composer

- The module now has its own artwork: a square icon in the modules list and the module page's header, and a wide banner behind the module page. Both ship in `app/src/main/res/drawable-nodpi/`.
- The SoundCloud page opens with a card carrying that icon, the module's state and the account, so signing in is reachable from the page itself, not only from Settings.
- Browsing UI is calmer while it loads and when it has nothing to show: skeleton rows instead of a bare spinner, a distinct card for an error with retry, and an empty state that names what to do next. Tabs use the same pill shape as the rest of the app.
- Comments moved from an alert dialog to the player's own bottom sheet: avatar, author, relative time, the comment body and, when it is a timed comment, where in the track it sits. The sheet has a composer, a send action, an inline error and a retry, and it scrolls independently of the player.
- A comment's `created_at` and its `timestamp` are now read as two different things. `timestamp` is the position inside the upload (previously shown as if it were the comment's age); the sheet shows `created_at` as "3 giờ trước" and keeps `timestamp` as a "tại 1:25" chip.
- Posting uses api-v2's own write shape: `POST /tracks/{id}/comments` with `body` in the query string plus the account's `Authorization: OAuth …` header. Writes are never retried automatically, so a transient 5xx cannot post the same comment twice.
- Stability: the song cache is bounded; a network that does not answer during an account check no longer signs the account out; a short-lived JWT is renewed with the web refresh token before asking for a new sign-in; a 401/403 that survives both renewals marks the session expired; client-id discovery also reads the key when it is inlined in the home page instead of a bundle.

## Sign-in references checked in public GitHub projects

- [`kosoymiki/soundcloud-mcp-server`](https://github.com/kosoymiki/soundcloud-mcp-server) — the web-session path: reads `oauth_token` and `oauth_refresh_token` from the browser's cookies and refreshes with `POST https://secure.soundcloud.com/oauth/token`, sending `grant_type=refresh_token`, the refresh token and the `client_id` from the access token's JWT claim. Its README says cookies last about an hour and that a refresh rotates the token the web client holds.
- [`rodyatotu/soundya`](https://github.com/rodyatotu/soundya) — the same two cookies; keeping `oauth_refresh_token` is what renews the login day to day.
- [`music-assistant/SoundcloudPy`](https://github.com/music-assistant/SoundcloudPy) and its [support issue #6877](https://github.com/music-assistant/support/issues/6877) — the counter-example: a manually copied `oauth_token` alone expires within about a day and every request turns into 401, because nothing refreshes it.
- [`KlugesBuch1/soundcloud-protocol`](https://github.com/KlugesBuch1/soundcloud-protocol) — measured api-v2 shapes: account routes take `Authorization: OAuth …`, `/me` needs the user token, and a comment write puts `body` in the query string.
- [`mostafaalagamy/Metrolist`](https://github.com/mostafaalagamy/Metrolist) — the Android pattern Mochy already uses elsewhere: read the session cookie out of `WebView`'s `CookieManager` after the page signs in.
- SoundCloud's official [API guide](https://developers.soundcloud.com/docs/api/guide) (OAuth 2.1 + PKCE) — the clean route, but it needs a registered app and a client secret; registration is Artist Pro-gated, and [`soundcloud/api` issue #313](https://github.com/soundcloud/api/issues/313) documents how the connect screen's provider popups are awkward inside WebViews. It cannot ship in an open-source APK without embedding a secret.

Mochy follows the web-session path with the refresh half implemented: it stores both cookies, reads `client_id` and `exp` from the JWT, and refreshes once on a 401 before asking for a new sign-in. That is the difference from the Music Assistant report, where users re-copy a token every day.

## Account login and actions

Browsing, search, playback and reading public comments work without an account. Signing in is optional and happens on SoundCloud's own page inside Mochy's sign-in browser, opened from the module page, the SoundCloud page's card, or the player's comment sheet. The module keeps the session the SoundCloud web player itself uses: the `oauth_token` (the `Authorization: OAuth …` header or its cookie) and the `oauth_refresh_token` cookie, never a password. It uses them only for account calls: `/me` for the account check, a comment write, and the token endpoint that renews the short-lived access token. No SoundCloud developer client secret is embedded in the app.

That session is a browser session, not a token issued to a registered application. SoundCloud can end it at any time; a 401/403 on `/me` or a write turns the account row into "sign in again" instead of retrying silently. Reading comments, likes and counts stays available signed out. Liking/unliking and a following feed are not implemented: they would need the same session, and this patch does not claim them.

## Validation for this patch

- SoundCloud module: 49 test cases, 0 failures/errors, 1 opt-in live test skipped (48 executed). The new account tests cover session capture from a header and a cookie, rejection of another site's header, `/me` verification, an offline check keeping the session, a refused check expiring it once, sign-out, the comment write's query shape and OAuth header, a refused write, `created_at` versus the track position, JWT reading without newer Android helpers, and the refresh exchange (including a refused refresh signing the account out).
- FOSS app: 244 tests, 0 failures/errors. Includes deterministic coroutine tests for module toggles, late-response suppression, nested playlist/related back navigation after retry, and comment pagination failure/retry/deduplication.
- `./gradlew :app:compileArm64FossDebugKotlin` and `:app:testUniversalFossDebugUnitTest` pass on this machine's Android SDK 37.
- Live comment pagination returned HTTP 200 for offsets 0 and 2 with distinct IDs and next offsets 2 and 4; each page contained 2 raw comments. No response URL or client ID was exposed.
- Live read-only probes independently verified chart, search, related, track detail and comment request shapes; no account tokens were used.
- No Android device was connected (`adb devices -l` empty); no on-device playback/performance claim is made.
- No registered SoundCloud Developer application is available or needed for this patch: the account is the user's own web session, and the write uses api-v2's own comment shape. The official registered-app flow (client id/secret, PKCE exchange) remains unimplemented and is not required for what shipped.

## Remaining device validation

- Toggle off/on from settings while a search or comment load is pending; confirm hidden UI, ignored old results and fresh load on enable.
- Test direct routes and old queue entries while disabled; retain library/history instead of deleting user data.
- Verify rapid queries, back navigation from related/playlist, airplane mode, rate limiting, private/DRM/deleted tracks, long mixes, background/resume and actual playback on Android.
- Sign in on a device with a real SoundCloud account: the page loads inside the sign-in sheet, the session is captured (request header or `oauth_token` cookie), `/me` shows the username, a comment posts and appears in the thread, and signing out returns the module to browsing-only without switching it off.
- Post from the player's comment sheet while a song is playing, and with airplane mode on, to confirm the send button's busy state, the inline error and the retry path.

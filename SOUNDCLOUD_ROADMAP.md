# SoundCloud: verified capabilities and remaining integration

## Fixes after v8.4.4

- SoundCloud Home/Explore entries follow the module switch, independently of transient server/account failures. A disabled module does not begin catalog/comment requests; re-enabling reloads the current view. Old queue items cannot start a new SoundCloud network resolution while disabled. Existing library/history records are retained.
- Live read-only probes on 2026-11-20 reproduced HTTP 400 from `/charts` with all tested `region` values (`soundcloud:regions:VN`, `soundcloud:regions:US`, `VN`, `vn`). Global charts return 200 without that parameter. `country=VN` returned the same leading IDs as global, so it is not a verified replacement.
- Removed unsupported chart-region parameters and country chips. The **Nhạc Việt Nam** option searches `nhạc Việt`; it is explicitly keyword discovery, not a verified geographical chart, uploader-country filter, or license bypass.
- Public track metadata includes optional `likes_count`/`favoritings_count` and `comment_count`; these now reach the screen. Missing counts remain absent rather than being invented as zero.
- Public comment reads work with `threaded=0&filter_replies=0`; omitting `threaded` reproduced 400. Menus show author, text and timestamp, with pages of 50 comments and a load-more action. Loading a later page preserves existing rows on failure, supports retry, deduplicates IDs and stops on a repeated page. Offsets are computed locally; response URLs are never followed with credentials.
- Nested playlist/related views retain a stack of browse states. Back restores the original playlist/search; retry reloads the same view rather than replacing its parent snapshot. Android system back follows the same behavior as the toolbar.
- Media resolution preserves existing query parameters, retains alternative URLs with the same quality preset, validates HTTPS URLs and recognizes extensionless HLS from its protocol metadata. Repeated songs in a playlist have unique Compose keys.
- Malformed successful catalog/comment responses are surfaced as errors rather than valid empty results. Error/retry content is in the scrollable list and does not cover search/navigation controls.
- Direct SoundCloud playback now uses `playbackPicks`, honoring the selected quality and global data-saver cap instead of always defaulting to best quality.
- Search, global charts, related tracks, track detail and comment endpoint shapes were checked against live SoundCloud. Mock HTTP tests protect the region/comment parameter corrections. These checks do not prove Android playback or visual performance.

## Account login and actions

The current module browses the public catalog. It does not authenticate a user. **Reading public comments and engagement totals works without login; signing in, liking/unliking, posting comments, private likes and following-feed recommendations are separate account operations.** The public web-player `client_id` is not a user session.

SoundCloud's [API guide](https://developers.soundcloud.com/docs/api/guide) documents registered apps, OAuth 2.1 with PKCE, token exchange/refresh and account endpoints. This checkout has no SoundCloud registered-app client ID/redirect configuration or token-exchange service. Do not embed confidential client secrets in the APK or repository. An approved app and secure token-exchange design are needed before native account operations can be completed. The UI links to the canonical SoundCloud website for those interactions in the meantime and does not claim the app is signed in.

## Validation for this patch

- SoundCloud module: 34 test cases, 0 failures/errors, 1 opt-in live test skipped (33 executed).
- FOSS app: 229 tests, 0 failures/errors. Includes deterministic coroutine tests for module toggles, late-response suppression, nested playlist/related back navigation after retry, and comment pagination failure/retry/deduplication.
- Live comment pagination returned HTTP 200 for offsets 0 and 2 with distinct IDs and next offsets 2 and 4; each page contained 2 raw comments. No response URL or client ID was exposed.
- Live read-only probes independently verified chart, search, related, track detail and comment request shapes; no account tokens were used.
- No Android device was connected (`adb devices -l` empty); no on-device playback/performance claim is made.
- The user confirmed no registered SoundCloud Developer application is available. Native account login/write actions remain pending that configuration.

## Remaining device validation

- Toggle off/on from settings while a search or comment load is pending; confirm hidden UI, ignored old results and fresh load on enable.
- Test direct routes and old queue entries while disabled; retain library/history instead of deleting user data.
- Verify rapid queries, back navigation from related/playlist, airplane mode, rate limiting, private/DRM/deleted tracks, long mixes, background/resume and actual playback on Android.
- Test complete account consent, refresh, revoke and encrypted token storage only after registered OAuth configuration is supplied.

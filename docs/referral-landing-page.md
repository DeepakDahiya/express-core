# Referral Landing Page Spec

**Audience:** Frontend developer building `https://browser.express/refer`.
**Owner:** [Android team contact]
**Status:** Android client-side already implemented. Waiting on landing page + backend endpoints.

---

## 1. Goal

Today our referral flow loses some installs because Google Play's Install Referrer API doesn't deliver the referral code 100% of the time (sideloads, transient Play Store failures, privacy-restricted regions, etc.). The Android app already has fallback logic for two extra signals — but those signals only exist if a landing page on `browser.express` captures them before the user reaches the Play Store.

**This spec describes that landing page.**

After it ships, our referral flow will have **three independent attribution signals** instead of one, raising attribution from ~75–85% to ~95%+.

---

## 2. The complete user flow (what we're building)

```
1. Existing user shares: https://browser.express/refer?code=XXX
2. Friend taps the link on their phone
3. Browser opens https://browser.express/refer?code=XXX  ← YOU ARE HERE
4. Landing page does three things in parallel:
     (a) POSTs fingerprint to backend     → backend stores (IP+UA+...) → code, 30 min TTL
     (b) Writes "express-ref:XXX" to clipboard
     (c) Redirects to Play Store with referrer=referral_code=XXX
5. Friend installs from Play Store
6. App launches:
     - Primary signal:  Play passes referrer to app via Install Referrer API → attributed
     - Fallback 1:      If Play loses it, app POSTs its own fingerprint to backend.
                        Backend matches against (a) and returns code.
     - Fallback 2:      If both above fail, app reads clipboard, finds "express-ref:XXX".
```

The landing page itself never needs to know whether attribution succeeds — it's fire-and-forget. The app handles matching.

---

## 3. Inbound URL contract

The landing page is reached at:

```
https://browser.express/refer?code=<CODE>
```

**Query parameters:**

| Param  | Required | Format                          | Notes |
|--------|----------|---------------------------------|-------|
| `code` | Yes      | `[A-Za-z0-9_-]{4,64}`           | The referral code. Treat as opaque; do not validate beyond shape. |

**If `code` is missing or malformed:**
- Skip steps (a) and (b).
- Redirect straight to Play Store with no `referrer` param.
- Optionally show an error message ("This referral link is invalid") for ~1 sec before redirect, but don't block.

**If `code` is present but suspicious (e.g., 200 chars, contains HTML):** treat as missing — same fallback.

---

## 4. What the landing page must do

Execute these three actions **in this order**, but do not block the redirect waiting for (a) or (b) to finish.

### (a) POST fingerprint to backend — non-blocking

```
POST https://api.browser.express/v1/referral/click
Content-Type: application/json

{
  "code": "<CODE>",
  "userAgent": "<navigator.userAgent>",
  "language": "<navigator.language>",
  "languages": ["<navigator.languages[]>"],
  "screenWidth": <screen.width>,
  "screenHeight": <screen.height>,
  "devicePixelRatio": <window.devicePixelRatio>,
  "colorDepth": <screen.colorDepth>,
  "timezone": "<Intl.DateTimeFormat().resolvedOptions().timeZone>",
  "timezoneOffset": <new Date().getTimezoneOffset()>,
  "platform": "<navigator.platform>",
  "referrer": "<document.referrer>",
  "clickedAt": <Date.now()>
}
```

**Match-critical fields** (the backend's match algorithm relies on these because both web *and* the Android app can derive them): `language`, `timezone`, `timezoneOffset`, `screenWidth`, `screenHeight`, `devicePixelRatio`. **Don't drop any of these** without coordinating with backend.

**Diagnostic-only fields** (web-only, useful for debugging / bot filtering / scoring tie-breakers — not used to match across web→app): `userAgent`, `languages`, `colorDepth`, `platform`, `referrer`, `clickedAt`.

The Android app posts a parallel JSON to `/v1/referral/fingerprint-match` with the same camelCase field names for the match-critical fields, plus Android-only diagnostic fields (`deviceId`, `osVersion`, `sdkInt`, `manufacturer`, `model`, `firstInstallTime`, `matchAt`). Backend can match on the shared field names directly.

- **The server reads the request IP from headers** (X-Forwarded-For etc.) — **do not** include IP in the JSON body. The browser cannot accurately know its public IP, and including a guess will hurt match accuracy.
- Use `fetch(..., {keepalive: true})` so the request survives the redirect.
- **Do not await.** Fire it and move on.
- Ignore the response.

**Why this matters:** the backend keeps `(IP, UA, language, screen, timezone, ...) → code` in a short-lived store (~30 min). When the app launches after install and fails to get a Play referrer, it sends a similar fingerprint and the backend matches it back.

### (b) Write the referral token to clipboard — best effort

```js
await navigator.clipboard.writeText(`express-ref:${code}`);
```

**Constraints:**
- The Clipboard API requires either a user gesture or `document.hasFocus()` on most browsers. On a redirect-on-load page, this often fails silently — that's acceptable.
- **If you want this to succeed reliably**, show a single tap target ("Continue to install") and trigger writes inside that click handler. Acceptable, but discuss with us before adding a tap step — the redirect page is currently zero-click.
- Wrap in try/catch and swallow errors. **Never block the redirect on this.**

**Token format is fixed:** `express-ref:<CODE>` exactly. The Android app matches the regex `express-ref:([A-Za-z0-9_-]{4,64})`. Don't change the prefix.

### (c) Redirect to Play Store

```
https://play.google.com/store/apps/details?id=<APP_PACKAGE_NAME>&referrer=referral_code%3D<CODE>
```

**Critical details:**

- `<APP_PACKAGE_NAME>` — confirm with the Android team. **Do not guess.**
- The `referrer` query parameter's *value* is `referral_code=<CODE>`, **URL-encoded**. So `=` becomes `%3D`. If you also want extra fields (recommended for analytics), URL-encode `&` as `%26`:
  ```
  referrer=referral_code%3DABC123%26source%3Dweb%26ts%3D1714000000
  ```
  Play decodes this once and passes `referral_code=ABC123&source=web&ts=1714000000` to the app.
- The Android app currently parses only `referral_code` from this string. Other fields are ignored client-side but logged server-side, so feel free to add them.

**Use a proper redirect, not just `window.location =`:**

For best UX:
1. Server-side: respond with `302` to the Play Store URL after step (a) is enqueued. Fastest, no flash.
2. If you must do it client-side: `<meta http-equiv="refresh" content="0;url=...">` plus `window.location.replace(...)` as a fallback in case clipboard write delays things.

---

## 5. Already-installed users (Android App Links)

When a user already has the app installed, tapping `https://browser.express/refer?code=XXX` should open **the app**, not the landing page. The app already has a deep-link handler for this — but Android only routes the link to the app if `browser.express` is verified as an App Link.

**You must publish a Digital Asset Links file:**

```
https://browser.express/.well-known/assetlinks.json
```

Content (confirm `package_name` and `sha256_cert_fingerprints` with the Android team):

```json
[
  {
    "relation": ["delegate_permission/common.handle_all_urls"],
    "target": {
      "namespace": "android_app",
      "package_name": "<APP_PACKAGE_NAME>",
      "sha256_cert_fingerprints": ["<APP_SIGNING_CERT_SHA256>"]
    }
  }
]
```

**Verify after publishing:** https://digitalassetlinks.googleapis.com/v1/statements:list?source.web.site=https://browser.express&relation=delegate_permission/common.handle_all_urls

If verification succeeds, Android skips the landing page entirely for installed users. The landing page is only hit by users without the app — which is exactly when we want to capture the fingerprint and redirect to Play Store.

---

## 6. Backend endpoints (NOT frontend work, but you need to know they exist)

These two endpoints are owned by the backend team. The landing page calls (1); the Android app calls (2). Listed here only so you can integration-test.

### (1) `POST /v1/referral/click` — called by landing page

Request body: see §4(a).
Server reads request IP from headers.
Server stores: `key=hash(ip, ua_prefix, language)` → `{code, ip, ua, lang, tz, screen, ts}` with 30-min TTL (Redis or similar).
Returns: `{ "ok": true }` (status 200). Don't fail the response if storage fails — landing page already moved on.

### (2) `POST /v1/referral/fingerprint-match` — called by the Android app, NOT the landing page

The app POSTs a fingerprint. The backend matches it against entries from (1) within the last 30 min. Out of scope for this doc — backend team has the spec.

---

## 7. Page UX

Keep the page essentially invisible:
- No login wall. No sign-up. No marketing copy.
- A logo + a "Redirecting to Google Play…" line is fine.
- Total time on page should be **under 1 second** for users with the app uninstalled.
- Mobile-first. Don't ship a desktop hero — desktop visitors should also redirect to Play Store (Play handles the desktop case by showing the listing).
- Server-render whatever you can — every client-side roundtrip delays the redirect.

---

## 8. Edge cases & required behavior

| Case | Behavior |
|------|----------|
| `?code` missing or malformed | Redirect to Play Store with **no** `referrer` param. Skip fingerprint POST. |
| User on iOS | Same flow. Redirect to Play Store works (Play shows the Android listing on iOS too — that's fine, this is an Android-only product). If we add iOS later we'll spec the App Store flow separately. |
| User has app installed (App Link verified) | Android intercepts the URL — landing page never loads. Nothing to do client-side. |
| User has app installed (App Link **not** verified) | Page loads, POSTs fingerprint, redirects to Play Store — Play opens the app instead of installing. Android's `onNewIntent` handler picks up the deep link. Still works. |
| User clicks back during redirect | Acceptable; the fingerprint POST already fired. |
| User clears browser data after click but before install | Fingerprint match degrades (no cookie, but IP+UA usually still matches). Accept the loss. |
| User on VPN / shared NAT (corporate, public wifi) | Multiple users behind one IP → fingerprint match accuracy drops. Accept the loss; Play referrer is still primary. |
| Clipboard write blocked by browser | Silent failure, no user-facing error. Other two signals still work. |
| Backend `/v1/referral/click` returns 5xx | Silent failure, no user-facing error. Play referrer is still primary. |
| `code` contains `<`, `>`, quotes, etc. | Treat as malformed (see row 1). Sanitize before reflecting in HTML. Even though `code` shape is restricted, defense in depth. |

---

## 9. Test checklist

Before shipping, verify each manually:

- [ ] Visit `https://browser.express/refer?code=TESTCODE1` on Android Chrome (app NOT installed).
- [ ] Network tab shows `POST /v1/referral/click` fired with the correct body.
- [ ] Page redirects to `play.google.com/store/apps/details?id=<APP_PACKAGE_NAME>&referrer=referral_code%3DTESTCODE1`. Verify URL exactly.
- [ ] Total time-on-page < 1.5s on a mid-tier Android.
- [ ] Visit `https://browser.express/refer` (no code) → redirects to Play Store with **no** `referrer` param.
- [ ] Visit `https://browser.express/refer?code=` → same as above.
- [ ] Visit `https://browser.express/refer?code=<script>alert(1)</script>` → no XSS, redirects with no referrer.
- [ ] After user gesture, clipboard contains `express-ref:TESTCODE1`. (Test in browser devtools — read clipboard from console.)
- [ ] Publish `assetlinks.json`, then verify with the Google API URL in §5.
- [ ] After App Link verification, tapping the link with the app installed opens the app directly. (Android team will confirm the app receives the code via the deep-link path.)
- [ ] Backend team confirms entries land in the click store with the right IP and UA.

---

## 10. Open questions for you to confirm before starting

1. What's the existing share flow generating the link? Confirm callers pass `?code=` (not `?ref=` or some other name) so we don't have a mismatch.
2. Is `api.browser.express` the right backend host, or are we using a different domain for the referral API? Confirm with backend team.
3. Will the landing page be a static HTML file, an SSR route in an existing app, or a Cloudflare Worker? (Affects how server-side IP reading works.)
4. Should we also support `https://browser.express/refer/<CODE>` (path-based) as an alias? Not required — `?code=` is the canonical form — but cheap to add if we want short-link aesthetics.

---

## 11. What you don't need to build

- Authentication / login.
- Tracking pixels (we use the fingerprint endpoint + PostHog from the app).
- A "share to friends" widget (that's in-app).
- Anything iOS-specific.
- A page for users who already have the app — App Links handle that path.

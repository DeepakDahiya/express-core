# Referral Backend API Spec

**Audience:** Backend developer.
**Companion to:** `docs/referral-landing-page.md` (frontend) and the Android app (already implemented in `ReferralHelper.java`).

---

## 1. Goal

Close the gap left by Google Play's Install Referrer API. The Android app already has fallback logic that depends on two new server endpoints. This doc specifies them.

---

## 2. Summary of changes

| Endpoint | Status | Caller | Purpose |
|----------|--------|--------|---------|
| `POST /v1/referral/click` | **NEW** | Landing page (browser.express/refer) | Stores a click fingerprint for ~30 min |
| `POST /v1/referral/fingerprint-match` | **NEW** | Android app on first launch | Looks up the click by fingerprint, returns referral code |
| `POST /v1/referral/track` | **MODIFIED** | Android app | Add a new optional `source` field |
| `GET /v1/referral/code` | unchanged | Android app | Returns the current user's own referral code |

---

## 2b. Attribution guards (2026-08-26)

Fixes for "referrals not counted" / "my count went down":

- `POST /v1/referral/track` accepts an optional `Authorization: Bearer <access_token>`; the app sends it whenever it has a session. A referrer's own code is rejected with **HTTP 422 `{status:"self_referral"}`** before any row/reward exists (the app also refuses its own JWT `referralCode` locally, `ReferralHelper.attribute`). Previously this was tracked, rewarded, then flagged and clawed back at `/link` — the visible "+1 … −1".
- `POST /v1/referral/fingerprint-match` now takes `firstInstallTime` (the app already sends it) and only considers clicks from the **caller's IP** that happened **before the install and within 24 h** of it, and only matches when screen/DPR agree. The old fallback to "recent clicks from any IP" attributed organic installs to strangers' clicks. The app runs fingerprint/clipboard fallbacks only within 7 days of install.
- `POST /v1/auth/verify-phone` accepts the guest session's `Authorization` (+ `x-refresh`) header; the guest account is kept as the verified account (same referral code), or its referral state is merged into an existing account with the guest code kept as an alias (`previousReferralCodes`). `/track` resolves alias codes.

## 3. `POST /v1/referral/click` (NEW)

Called by the landing page when a friend clicks `https://browser.express/refer?code=XXX`. Stores the fingerprint so the Android app can recover the code post-install if Play Store loses the referrer.

### Request

```http
POST /v1/referral/click
Content-Type: application/json

{
  "code": "ABC123",
  "userAgent": "Mozilla/5.0 (Linux; Android 14; ...) ...",
  "language": "en-US",
  "languages": ["en-US", "en"],
  "screenWidth": 412,
  "screenHeight": 915,
  "devicePixelRatio": 2.625,
  "colorDepth": 24,
  "timezone": "Asia/Kolkata",
  "timezoneOffset": -330,
  "platform": "Linux armv8l",
  "referrer": "https://twitter.com/...",
  "clickedAt": 1714000000000
}
```

**Server reads request IP from headers** (`X-Forwarded-For` first if behind a proxy/CDN, then `CF-Connecting-IP` for Cloudflare, then `req.ip`). The body never contains IP — don't trust it if it does.

### Response

```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "ok": true }
```

Always 200. Fire-and-forget from landing page — never fail user flow on a storage error. If validation fails (missing `code`, etc.), still return 200 and log server-side.

### Storage

Store each click with a TTL of **30 minutes**. Choose a store that supports atomic TTL: Redis, DynamoDB with TTL, or Cloudflare KV with `expirationTtl`.

**Storage key:** any unique id — UUID, `Snowflake`, or `hash(ip + ua + clickedAt)`. Don't make it the IP alone (multiple users behind one NAT would overwrite each other).

**Indexed lookup key:** `IP` (so the match endpoint can find candidates fast). Implementation: maintain a secondary index `ip → list<click_id>` with the same 30-min TTL, OR store all clicks in a single hash bucket keyed by IP.

**Stored record shape:**

```json
{
  "id": "<click_id>",
  "code": "ABC123",
  "ip": "203.0.113.45",
  "userAgent": "...",
  "language": "en-US",
  "screenWidth": 412,
  "screenHeight": 915,
  "devicePixelRatio": 2.625,
  "timezone": "Asia/Kolkata",
  "timezoneOffset": -330,
  "clickedAt": 1714000000000,
  "storedAt": 1714000000123
}
```

### Validation

- `code`: must match `^[A-Za-z0-9_-]{4,64}$`. If not, log warn but still return 200 (don't reveal validation rules to clients).
- All other fields: optional, accept whatever shape comes in. Log unexpected types but don't reject.

### Rate limiting

- Per-IP: 60 clicks / hour. Above this, return 200 but skip storage. Prevents one malicious actor from poisoning the click cache.
- Per-`code`: 1000 clicks / hour. Above this, log alert and skip storage — likely a spam campaign.

---

## 4. `POST /v1/referral/fingerprint-match` (NEW)

Called by the Android app on first launch when Play's Install Referrer didn't deliver a code. Server takes the request IP + body fingerprint, finds matching candidates from the click cache, returns the best match.

### Request

```http
POST /v1/referral/fingerprint-match
Content-Type: application/json

{
  "deviceId": "<android_id>",
  "language": "en-IN",
  "timezone": "Asia/Kolkata",
  "timezoneOffset": -330,
  "screenWidth": 1080,
  "screenHeight": 2400,
  "devicePixelRatio": 2.625,
  "platform": "android",
  "osVersion": "14",
  "sdkInt": 34,
  "manufacturer": "Xiaomi",
  "model": "M2102J20SG",
  "firstInstallTime": 1714000000000,
  "matchAt": 1714000020000
}
```

**Server reads request IP from headers** (same as `/click`).

### Response

```http
HTTP/1.1 200 OK
Content-Type: application/json

{ "referralCode": "ABC123" }
```

Or, on no match:

```json
{ "referralCode": "" }
```

Or `{}` — both are accepted by the Android client (`resp.optString("referralCode", "")`).

Always return 200 unless infrastructure is down. Do not 404 on no-match — that's a normal outcome.

### Match algorithm

```
1. candidates = clicks_by_ip[request_ip]   // list of clicks within last 30 min
2. If len(candidates) == 0: return ""

3. For each candidate, compute score = sum of:
     +3 if language exact match           // "en-IN" == "en-IN"
     +2 if language base match             // "en-IN" → "en"
     +3 if timezone exact match            // "Asia/Kolkata" == "Asia/Kolkata"
     +2 if timezoneOffset exact match
     +2 if screen dimensions match (w*h within ±5%)   // device-class identifier
     +1 if devicePixelRatio match (within ±0.1)

   Max score: 13. Min for "confident": 7. Tunable.

4. Sort by score desc, then by clickedAt desc (recency tie-breaker).
5. If best score >= threshold AND
      no second candidate scores within 1 of the best (ambiguous):
         return best.code
   Else:
         return ""
```

**Why the ambiguity check matters:** if two clicks at the same IP have nearly identical scores, returning either is arbitrary. Better to skip the match than misattribute. The Android app emits a PostHog event either way, so you'll see the rate of "ambiguous" matches and can tune.

### Match invalidation

**Critical: each click can be matched at most once.** Once a candidate is returned to an app, mark it consumed (delete it or flag `consumed=true`) so a different device on the same IP doesn't get the same code.

### Validation & rate limiting

- Per-`deviceId`: 5 calls / 24 hours. The app only calls once per install, so anything beyond 1–2 is suspicious.
- Per-IP: 100 calls / hour. Same shared-NAT concern as click endpoint.
- Reject obvious bad input (empty body, missing all match fields) with 200 + empty response. No 4xx — keeps the app's error-handling path simple.

### Logging

For each call, log:
- request IP (hashed if privacy policy requires)
- candidates_count (how many clicks matched the IP)
- best_score, second_best_score
- result: `matched | no_match | ambiguous | rate_limited | error`

You'll need this to tune the match threshold over the first few weeks.

---

## 5. `POST /v1/referral/track` (MODIFIED)

Already exists. The Android app now sends an additional `source` field. Existing clients still send the old shape — keep the field optional.

### Request (current → new)

```diff
 POST /v1/referral/track
 Content-Type: application/json

 {
   "referralCode": "ABC123",
-  "deviceId": "<android_id>"
+  "deviceId": "<android_id>",
+  "source": "install_referrer"     // optional, see values below
 }
```

### `source` values

The app sends one of:

| Value | Meaning |
|-------|---------|
| `install_referrer` | Captured via Google Play Install Referrer API. The intended primary path. |
| `deep_link` | Captured via VIEW intent on `browser.express/refer` (App Link verified, app already installed) |
| `fingerprint` | Recovered via `/v1/referral/fingerprint-match` after Install Referrer failed |
| `clipboard` | Last-resort recovery via `express-ref:CODE` clipboard token |
| `debug` | A tester used the debug-pref override (treat as test data, do not count) |

Keep `source` in your tracking table. The breakdown will tell you exactly how much each fallback contributes — that's the metric that justifies keeping the fallback infra.

### Response

Unchanged. Existing 2xx behavior is fine.

---

## 5b. `POST /v1/referral/usage` (NEW, 2026-09-09) — stage 2 / payout basis

Stage 1 (`/track`, `/link`) proves an install and an account. Referrers are
paid per referral, so stage 2 records what the referred person then *does*
and marks the referral **activated** on the first premium-feature use. The
app side is `referral/ReferralUsageTracker.java`; the server side is
documented in `browser-express-node/docs/referral-system.md` §3d.

### Request

```
POST /v1/referral/usage
Authorization: Bearer <access_token>
Content-Type: application/json

{
  "reportId":   "<uuid per batch; a retry reuses it>",
  "deviceId":   "<same id as /track and /link>",
  "appVersion": "1.4.0",
  "usage": {
    "youtubeVisits":    { "count": 2, "firstAt": 1757415600000, "lastAt": 1757419200000 },
    "youtubePageViews": { "count": 9, "firstAt": …, "lastAt": … },
    "pipUses":          { "count": 1, "firstAt": …, "lastAt": … },
    "bgPlayUses":       { "count": 0 }
  }
}
```

Counts are **deltas** accumulated on the device since the last successful
report; timestamps are epoch ms. Only counters with `count > 0` need to be
sent.

### What the app counts

| Counter | Recorded where | Rule |
|---------|----------------|------|
| `youtubeVisits` | `BraveActivity.recordYouTubeVisitIfNeeded` (active-tab committed navigation) | a YouTube navigation ≥ 30 min after the previous one |
| `youtubePageViews` | same | every committed youtube.com / youtu.be navigation, incl. same-document (video-to-video) |
| `pipUses` | `BraveActivity.recordYouTubeBackgroundPlayUsed` with `YT_FEATURE_EXPLORED_PIP_BG_PLAY` | confirmed PiP entry (framework callback), not the golden-button tap |
| `bgPlayUses` | same, with `YT_FEATURE_EXPLORED_BG_PLAY` | media session still playing on a YouTube tab after the app left the foreground |

PiP / background play are deduped per foreground session (one use per
session), matching the existing PostHog `YT_FEATURE_EXPLORED_*` events.

### Batching / delivery

- Counters go to SharedPreferences first (`EXPRESS_REFERRAL_USAGE_PENDING`),
  so a use recorded while the app is being frozen (PiP entry) survives.
- Premium uses flush **immediately**; visits flush every 15 min; everything
  pending is also flushed on app background, app start, and login.
- The batch being sent moves to `EXPRESS_REFERRAL_USAGE_INFLIGHT` under a
  stable `reportId`. A lost response retries with the same id and the server
  dedups, so nothing double counts; events that arrive mid-send start a new
  pending batch.
- Backoff on network/5xx: 5 min doubling to 6 h. Batch dropped after 40
  attempts or on HTTP 400; kept (no backoff) on 401/403 until the next login.
- Nothing is sent without a session token; counters simply wait.

### Response

`200 { success, applied, duplicate, referred, activated, activatedNow, totals }`

- `duplicate: true` — same `reportId` already applied (safe to clear locally)
- `referred` — this user is a referred user (linked, or device-matched)
- `activatedNow` — this batch activated the referral (first premium use)

### Server effects

- `user_feature_usage` doc per user (source of truth, every signed-in user).
- `referrals.activatedAt` / `activationFeature` stamped once (server time).
- PostHog `referral_referred_user_activated` on the referred person and
  `referral_referrer_stats.activated_referral_count` on the referrer.
- Admin: leaderboard "Activated" column, `/v1/admin/referrals/payout-report`.

## 6. Data model recommendation

Two stores:

### Hot store (Redis or KV) — clicks awaiting match

```
key:    click:<click_id>           value: <click record JSON>          TTL: 30 min
key:    click_ip:<ip>              value: SET of click_ids             TTL: 30 min
```

Lookup pattern: `SMEMBERS click_ip:<ip>` → batch `GET click:<id>` for each.

### Warm store (Postgres / DynamoDB) — attributed installs (long-term)

Existing `referrals` (or whatever you call it) table — add a `source` column:

```sql
ALTER TABLE referrals
  ADD COLUMN source VARCHAR(32);
```

When `/v1/referral/track` fires, write `source` alongside the existing fields. This lets you slice analytics by attribution channel.

---

## 7. Observability

The Android app already emits PostHog events for every attribution branch. The backend only needs to instrument *its own* visibility:

- **`/click` rate** — should roughly track app install rate. A drop indicates landing page broken or share URL change rolled back.
- **`/fingerprint-match` outcomes** — `matched` / `no_match` / `ambiguous` ratio. Tells you Install Referrer's gap size.
- **Dedup events** — when the same `referralCode` arrives via Install Referrer *and* fingerprint match within minutes, log it. Should be rare (the app's `attribute()` is single-funnel) but worth a counter to confirm.

---

## 8. Privacy / abuse considerations

- **IP retention:** the click cache holds IPs for 30 min. If your privacy policy requires shorter retention, lower the TTL to 15 min — match accuracy drops slightly but it's defensible.
- **Hashing:** for the warm store, store `hash(ip)` not raw IP. The 30-min hot cache requires raw IP for fast lookup, so hashing there isn't useful.
- **Abuse scenarios to defend:**
  - **Click farms** — one IP submitting thousands of clicks with random codes. Mitigated by per-IP rate limit + the consume-on-match invalidation.
  - **Code stuffing** — the same valid code submitted from many IPs (someone trying to claim someone else's referrals). Mitigated by per-`code` rate limit + the existing fraud detection on `/v1/referral/track`.
  - **Replay** — same fingerprint posted to `/fingerprint-match` repeatedly to drain the cache. Mitigated by per-`deviceId` rate limit (5/day).

---

## 9. Rollout order

1. **Deploy `/v1/referral/click` and `/v1/referral/fingerprint-match`.** Until both exist, the landing page and Android fallback paths just log failures — no user-facing breakage.
2. **Add the `source` column** to the warm store and update `/v1/referral/track` to read it. Backwards-compatible.
3. **Frontend deploys the landing page.** Now `/click` starts receiving traffic.
4. **Android updates ship.** Now `/fingerprint-match` starts receiving traffic from clients that didn't get a Play referrer.
5. **Tune the match threshold** based on the first few thousand match attempts. Ship the threshold as config, not a constant.

---

## 10. Test checklist

- [ ] Hit `/click` with a valid payload → returns 200, click stored, secondary index updated.
- [ ] Same payload hit again from the same IP → no overwrite (both clicks stored, ip index has both).
- [ ] Wait 31 min → both records gone (TTL works).
- [ ] Hit `/fingerprint-match` from same IP within 30 min with a payload that scores high → returns the right `referralCode`.
- [ ] Hit `/fingerprint-match` again with the same fingerprint → returns `""` (consumed).
- [ ] Hit `/fingerprint-match` from an unrelated IP → returns `""`.
- [ ] Two clicks at the same IP with nearly identical fingerprints → match returns `""` (ambiguous), logged accordingly.
- [ ] Per-IP rate limit on `/click` triggers at 61st request within an hour.
- [ ] Track endpoint accepts both old (no `source`) and new (`source: "install_referrer"`) bodies.

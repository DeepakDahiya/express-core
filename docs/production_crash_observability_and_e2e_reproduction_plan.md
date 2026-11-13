# Production Crash Observability and E2E Reproduction Plan

Last reviewed: 2026-05-31

## Problem

Customers are reporting production crashes and bugs that we cannot reproduce.
Google Play Console reports are not enough because stack traces are often
obfuscated, native crashes may not be symbolicated, and browser failures are
spread across the Android UI process, Chromium native code, renderer processes,
GPU/utility processes, and feature-specific Java/C++ code.

We need a production system that:

* Captures crashes even when the process dies before it can talk to a server.
* Preserves enough pre-crash context to make bugs reproducible.
* Deobfuscates Java/Kotlin stack traces and symbolicates native Chromium stacks.
* Captures non-fatal browser failures such as renderer crashes and ANRs.
* Converts the top production failures into Appium scenarios that can run on
  hundreds of real devices through BrowserStack or another device farm.
* Respects browser privacy constraints and avoids collecting browsing data by
  default.

## Executive recommendation

Use a layered system instead of relying on one SDK:

1. Make symbol handling a release gate. Every shipped Android artifact must
   archive and upload its R8/ProGuard mapping, native debug symbols, Breakpad
   symbols, build metadata, and source revision before it can be published.
2. Use Chromium's crash reporting path as the primary native crash capture
   mechanism. Do not replace it with a generic Android SDK native handler until
   we prove that the signal handlers and minidump generation do not conflict.
3. Persist crash context locally before a crash: crash keys, a bounded
   breadcrumb journal, feature flags, device metadata, session metadata, and
   renderer/tab state. Upload it on the next launch or by a constrained
   background worker.
4. Add our crash ingestion and processing backend, or use a managed provider in
   our account that accepts Breakpad/Crashpad minidumps and debug files. The
   server must group, symbolicate, alert, and expose reports by release, device,
   OS, ABI, and feature.
5. Keep our Play Console app and, if approved, our Firebase Crashlytics project
   as secondary signal sources for Android vitals, Java exceptions, ANRs, and
   store-facing health, not as the only source of truth.
6. Build an Appium 2 test harness with BrowserStack App Automate as the main
   real-device grid. Add Firebase Test Lab as a complementary broad crawler for
   Robo and instrumentation coverage.

## Goals

* Symbolicated native and Java stack traces for stable, beta, nightly, and
  internal release channels.
* Crash reports survive process death, offline devices, app restart, and flaky
  networks.
* A single crash issue should show stack, release, device/OS distribution,
  breadcrumbs, recent feature states, and reproduction status.
* Renderer crashes, ANRs, low-memory deaths, startup crash loops, and fatal
  UI-process crashes are tracked separately.
* Top crash clusters automatically create or update engineering tickets.
* The top production crash and bug clusters feed a real-device test matrix.
* E2E tests run on PRs, nightly builds, release candidates, and targeted
  reproduction campaigns.

## Non-goals

* Do not log full browsing history, page contents, cookies, request bodies,
  account data, passwords, wallet data, seed phrases, or private headers.
* Do not attempt network upload inside a signal handler or uncaught exception
  handler after the process is already crashing.
* Do not rely only on Google Play Console. It is useful for Android vitals but
  not sufficient for Chromium multi-process diagnosis.
* Do not treat real-device E2E tests as a replacement for unit, component,
  instrumentation, browser, and fuzz tests.

## Current repo signal

This repo already contains Android native symbol tooling in
`tools/android/generate_breakpad_symbols.py`. That script wraps Chromium's
`components/crash/content/tools/generate_breakpad_symbols.py`, extracts native
libraries from an APK or AAB, and emits Breakpad-compatible symbols using
`dump_syms`.

The production plan should extend this existing Chromium/Breakpad path instead
of starting with a separate crash SDK that may not understand Chromium's
multi-process model.

## Existing Brave crash infrastructure

This plan is **not** greenfield. Brave already ships a production crash pipeline
built on Chromium's Crashpad, configured through a small set of `chromium_src`
overrides and patches. Any new work must extend this pipeline rather than stand
up a parallel one, and several "open decisions" below are in fact already
decided by what is in the tree today.

Because this product is a fork, inherited Google and Brave destinations are not
valid production defaults. Every crash, symbol, analytics, and user-facing crash
setting must be audited and either pointed at our infrastructure, disabled, or
explicitly accepted as an external dependency by product/security. The default
rule is simple: **production crash data for our browser must not be sent to
Google or Brave-owned app accounts, vendor projects, or inherited endpoints.**
Google-managed services such as Play Console, Firebase, or Firebase Test Lab are
allowed only when they are our projects/accounts and product/security explicitly
accept the data sharing; they remain secondary to the crash backend we own.

### What already exists

| Concern | Where | Behavior today |
| --- | --- | --- |
| Native crash handler | Chromium Crashpad (upstream); `chromium_src/components/crash/core/app/crashpad.cc` | Crashpad is the in-process/out-of-process native handler for browser, renderer, GPU, and utility processes. The Brave override only adds two **Windows** VPN process types (`brave-vpn-helper`, `brave-vpn-wireguard-service`) to the set Crashpad initializes. There is no Android-specific override. |
| Upload endpoint | `chromium_src/components/crash/core/app/crash_reporter_client.cc` | `GetUploadUrl()` returns `https://cr.brave.com` for `OFFICIAL_BUILD`, and an empty string otherwise (non-official builds do not upload). This **must be overridden** to our crash endpoint before any production build is shipped. |
| Upload consent | `patches/chrome-app-chrome_crash_reporter_client.cc.patch` | `GetCollectStatsConsent()` is patched so `OFFICIAL_BUILD` is treated like Google Chrome branding, enabling the consent/upload code path for official Brave builds. |
| Client identity | `chromium_src/third_party/crashpad/crashpad/client/settings.{h,cc}` | Brave wraps `Settings::GetClientID()` and **zeroes the Crashpad client ID** (`InitializeToZero`). Uploaded crashes carry no stable per-install identifier by design. |
| `chrome://crashes` UI | `patches/components-crash-core-browser-resources-crashes.ts.patch` | The Google-internal `goto.google.com/crash/<id>` deep link is removed; the page shows only the raw upload id text. |
| Native symbol generation | `tools/android/generate_breakpad_symbols.py` | Wraps Chromium's `dump_syms` flow for APK/AAB native libraries (see above). |

### Fork override requirements

These overrides are required before a public production rollout:

| Area | When needed | Required action |
| --- | --- | --- |
| Crashpad upload URL | Before any official build can be published | Replace Brave's `https://cr.brave.com` upload URL with our crash ingestion endpoint. Keep non-official builds disabled unless an internal endpoint is explicitly configured. |
| Crash consent and policy | Before adding breadcrumbs, Java reports, ANR/OOM reports, or non-fatals | Reuse the existing Chromium/Brave consent gate or replace it with our own equivalent. Do not add a second uploader that bypasses the user's crash-reporting preference. |
| Java exception reporter | Before relying on Java crash coverage | Audit Brave's existing `BravePureJavaExceptionReporter` path and point its minidump/job upload flow at our endpoint. Extend it for non-fatals only after ownership and consent are correct. |
| Symbol upload | Before Play rollout or internal dogfood | Upload R8 mappings, native debug symbols, and Breakpad symbols to our Play Console app and our crash backend. Do not rely on Brave's symbol store. |
| Product strings and settings | Before public UI review | Replace Brave/Chrome crash-reporting text, privacy-policy links, settings labels, and `chrome://crashes` wording with our product name and support flow. |
| Analytics/P3A/PostHog | Before using breadcrumbs or feature non-fatals | Decide which inherited analytics paths remain enabled. Crash diagnostics must go through our crash backend; product analytics must have separate consent and ownership. |
| Package/app IDs | Before symbol upload automation | Ensure artifact manifests, Play Console uploads, crash release IDs, and backend grouping use our package names/version codes, not Brave's. |
| Internal test-crash builds | Before release gate enforcement | Provide an internal endpoint/channel so test Java/native crashes prove that our endpoint, our symbols, and our deobfuscation pipeline work end to end. |

### What this means for the plan

* **Backend ownership is the first fork decision.** Brave's `cr.brave.com`
  proves that Crashpad upload already exists, but it is not our destination.
  Phase 1's "basic ingestion endpoint for minidumps" should be reframed as
  *"stand up or select our crash backend, then override Brave/Google upload paths
  to use it."* If we temporarily use a managed vendor, it still needs to be our
  account, our symbols, our retention policy, and our access controls.
* **Crash upload is official-build + consent gated today.** Non-official builds
  upload nowhere (`GetUploadUrl()` returns empty), and official builds depend on
  the patched `GetCollectStatsConsent()`. Any breadcrumb, crash-key,
  Java-exception, non-fatal, or ApplicationExitInfo work must respect the same
  consent gate, or a reviewed replacement gate, and ship through one owned data
  path.
* **There is no stable client ID — by design.** Because `GetClientID` is zeroed,
  the per-device correlation this plan assumes in several places does not exist
  today:
  * "Repeated renderer crashes for the same tab/session" and "repeated exits
    during startup" (crash-loop detection) cannot rely on a server-side stable
    device id; they must be derived from a **session-scoped** id carried in
    crash keys / breadcrumbs, not a persistent install id.
  * Rate limiting "by install ID hash" (ingestion API section) has no input to
    hash. Either drop that dimension or introduce an explicit, privacy-reviewed
    ephemeral id — which is a policy change to the current zero-id stance, not a
    free implementation detail.
  * "Affected-user count" in alerts and tickets is an estimate, not a distinct
    count, unless a privacy-reviewed identifier is added.
* **Android Java/Kotlin fatal crashes have an inherited path.** The repo includes
  `BravePureJavaExceptionReporter`, which extends Chromium's
  `ChromePureJavaExceptionReporter` and schedules minidump upload. The plan
  should audit and redirect that existing path, then add non-fatal reporting and
  richer Java context as new work.
* **The symbol pipeline must feed our backend.** Phase 0's symbol-store and
  release-gate work should target the backend we own, in addition to our Play
  Console app, rather than Brave's crash infrastructure.

### Audit checklist before Phase 1

1. Confirm whether official Android builds actually initialize Crashpad and
   upload today (verify on a release-like build, not just by code reading).
2. Choose our crash destination: first-party backend or managed vendor account.
   Document grouping, native symbolication, Java deobfuscation, retention,
   dashboards, alerting, and access controls.
3. Confirm whether Android R8/ProGuard mapping and Breakpad/native symbols are
   already uploaded for shipped Android builds, and replace any Brave/Google
   destination with our Play Console app and our crash backend.
4. Document the consent surface end to end, including the
   `p3a_crash_reporting_message_shown` onboarding pref
   (`OnboardingPrefManager.java`) and how it relates to
   `GetCollectStatsConsent()`.
5. Decide explicitly whether to keep the zero client ID. This single decision
   gates crash-loop detection fidelity, affected-user counts, and per-install
   rate limiting throughout the rest of this plan.
6. Search for inherited Brave/Google crash-reporting URLs, product strings,
   privacy links, symbol-upload scripts, and analytics endpoints. Override or
   disable each one before public release.

## Architecture

```text
Android app
  |
  |-- Crashpad/Breakpad minidumps for native/browser/renderer crashes
  |-- Java uncaught exception and non-fatal reports
  |-- ApplicationExitInfo for previous ANR, native crash, low memory, and
  |   abnormal exits on Android 11+
  |-- Bounded local breadcrumb/session journal
  |-- Renderer crash and WebContents failure telemetry
  |
  v
Local pending report store
  |
  |-- Upload early on next app launch
  |-- Retry with WorkManager when network is available
  |-- Mark report sent only after server acknowledgement
  |
  v
Crash ingestion API
  |
  |-- Raw report object storage
  |-- Async symbolication/deobfuscation workers
  |-- Grouping, deduplication, and regression detection
  |
  v
Dashboards, alerts, tickets, and reproduction packs
  |
  v
Appium scenarios on BrowserStack/Firebase Test Lab
```

## Production crash capture

### Capture classes

Track these as separate report types. They have different owners, severity, and
reproduction strategies.

| Type | Example | Capture path | Must include |
| --- | --- | --- | --- |
| Native fatal crash | SIGSEGV in C++ | Crashpad/Breakpad minidump | Native stack, crash keys, build ID, ABI |
| Java fatal crash | Uncaught Java/Kotlin exception | Java crash handler or Crashlytics | Deobfuscated stack, thread, Activity state |
| ANR | Main thread blocked | Android vitals, Crashlytics, ApplicationExitInfo | Trace, foreground state, operation in progress |
| Low-memory kill | OS kills process | ApplicationExitInfo, startup detector | Memory class, tab count, foreground/background |
| Startup crash loop | App crashes before usable UI | Early pending-report uploader | Last successful startup stage |
| Renderer crash | Tab shows crash page, app survives | Chromium renderer/WebContents hooks | URL policy-safe origin hash, tab state, renderer exit |
| GPU/utility crash | Browser continues with degraded feature | Chromium process crash hooks | Process type, feature in use |
| Non-fatal bug | Feature exception, failed invariant | Explicit report API | Component, error code, breadcrumbs |

### Local persistence model

The critical rule is that crash-time code should not depend on the network.
Crash capture must write the report to local app-private storage first, then
upload later.

Implement a local pending report store:

* `pending_crashes/`: minidumps and metadata JSON.
* `pending_events/`: non-fatal reports and ANR/OOM exit records.
* `sessions/`: bounded breadcrumb journals for current and previous sessions.
* `upload_state/`: retry counters, first-seen time, last-attempt time, and
  server report ID.

Report upload behavior:

* Initialize crash reporting before expensive browser startup work.
* On every launch, scan pending reports before restoring tabs.
* Upload only when the current crash-reporting consent and enterprise policy
  state permits upload.
* Upload with a short foreground budget first, then schedule WorkManager
  retries with network constraints and exponential backoff.
* Delete local reports only after a 2xx server acknowledgement.
* Keep a maximum disk budget, for example 10 to 25 MB, and drop oldest
  non-fatal reports first.
* Keep at least one fatal crash from the previous version even if the budget is
  exceeded.

### Breadcrumb journal

Crashes are often unreproducible because the stack only shows the final failure.
Store a bounded, privacy-safe sequence of events leading to the crash.

Recommended journal design:

* One journal per app session, capped by count and bytes.
* Append small structured events, not free-form logs.
* Flush at important state transitions and periodically in the background.
* Keep the last two sessions so a crash on startup can include the previous
  session's shutdown path.
* Never store full URL, page title, page content, query string, cookies,
  headers, form values, wallet data, or account identifiers by default.

Recommended breadcrumb fields:

```json
{
  "time_ms": 1720000000000,
  "session_id": "random-uuid",
  "event": "navigation_commit",
  "component": "browser",
  "tab_count": 4,
  "foreground": true,
  "network_type": "wifi",
  "privacy_url": {
    "scheme": "https",
    "registered_domain_hash": "sha256-rotating-salt",
    "is_internal_page": false
  },
  "feature_flags_hash": "sha256",
  "extra": {
    "transition": "typed",
    "http_status_class": "2xx"
  }
}
```

Good breadcrumb events:

* App/process lifecycle: application start, Activity create/resume/pause,
  background, foreground, shutdown requested.
* Startup stages: library load, native initialized, profile loaded, first
  Activity shown, tabs restored, first navigation.
* Tab and navigation: tab created/closed/switched, navigation started,
  committed, failed, SSL error shown, renderer gone.
* Media and UI: fullscreen enter/exit, picture-in-picture enter/exit, Android
  permission prompt, file chooser, share sheet.
* Storage and memory: low-memory callback, trim-memory level, tab discard,
  cache clear, profile data clear.
* Network: connectivity change, captive portal state, proxy/VPN state if
  available without PII.
* Feature states: enabled/disabled state for features likely involved in the
  crash, represented as booleans or hashes.
* Errors: caught Java exception, C++ error code, failed invariant, rejected
  promise from app-owned WebUI code.

### Crash keys

Crash keys are small key/value pairs attached directly to the minidump. They
must be bounded and scrubbed.

Required crash keys:

* App version name, version code, channel, install source.
* Git revision, Chromium revision, build ID, GN args hash.
* ABI, Android SDK, manufacturer, model, memory class, locale.
* Process type: browser, renderer, GPU, utility, network, crashpad handler.
* Current Activity and high-level UI mode.
* Startup stage.
* Tab count and active tab type: normal, incognito, custom tab, internal page.
* Feature flag snapshot hash and top relevant feature booleans.
* Last navigation status class, not full URL.
* Session ID and breadcrumb file ID.

Do not put sensitive values in crash keys. Many crash systems display keys in
plain text to anyone with access to the crash dashboard.

### Java and native handling

Native crashes:

* Keep Chromium's native minidump path as the primary source.
* Generate Breakpad symbols for every APK/AAB using
  `tools/android/generate_breakpad_symbols.py`.
* Keep unstripped native libraries or debug symbol archives for every release.
* Upload native debug symbols to our Play Console app for Android vitals.
* Upload Breakpad symbols to our crash backend symbol store.

Java/Kotlin crashes:

* Archive `mapping.txt` for every release artifact.
* Upload mapping files to our Play Console app.
* Upload mapping files to our Crashlytics project or our selected crash backend
  if used.
* Gate release publication on successful mapping upload.
* Audit the inherited `BravePureJavaExceptionReporter` fatal-crash path and
  redirect it to our backend.
* Add a Java non-fatal report API for caught exceptions and feature failures.

Crashlytics:

* Use Crashlytics only from our Firebase project as a secondary dashboard for
  Java fatal crashes, non-fatals, ANRs, and Android ecosystem reporting if it
  passes privacy review.
* Avoid enabling Crashlytics NDK as the primary native crash handler until we
  test interaction with Chromium's crash handler in release-like builds.
* If Crashlytics is used, set custom keys for release, channel, process type,
  startup stage, and feature flags. Keep keys privacy-safe.

### ANR, low-memory, and abnormal exits

Android 11 introduced `ApplicationExitInfo`, which lets an app inspect
historical process exits. Use it at the next startup to create reports for
exits that did not produce an ordinary crash report.

Capture:

* `REASON_ANR`: attach available trace data, foreground state, startup stage,
  and the last main-thread breadcrumb.
* `REASON_LOW_MEMORY`: attach memory class, trim-memory breadcrumbs, tab count,
  renderer count, and foreground/background state.
* `REASON_CRASH_NATIVE` and `REASON_CRASH`: link to any matching minidump or
  Java fatal report.
* Repeated exits during startup: mark as startup crash loop and upload before
  doing normal tab restore.

For older Android versions, keep our Play Console app's Android vitals and our
Crashlytics project, if approved, as fallback ANR sources. A lightweight
main-thread watchdog can be added to beta and nightly channels first, then moved
to stable only if overhead is proven acceptable.

### Renderer and tab crashes

A Chromium browser can have a severe user-visible bug without the Android app
process crashing. Track renderer and tab failures explicitly.

Required events:

* Renderer process gone/crashed/killed.
* Visible tab crash page shown.
* GPU process restart or fallback.
* WebContents navigation failure after a renderer crash.
* Repeated renderer crashes for the same tab/session.

Each renderer crash report should include process type, app version, device,
OS, ABI, renderer exit reason, foreground state, tab count, memory pressure, and
privacy-safe origin hash. It should not include the full URL unless the user
manually submits feedback and explicitly consents.

## Feature instrumentation

Native and Java crashes are captured generically, but the highest-frequency
*bugs* in a browser are silent functional failures that never crash the
process: a feature script no-ops, a state machine sticks, a callback never
fires. These do not show up in any of the capture classes above unless the
owning feature explicitly reports them. This section defines how a high-risk
feature opts in, and names the first candidate.

### How a feature opts in

A feature integrates with observability through three existing mechanisms — it
does **not** add its own telemetry channel:

1. **Breadcrumbs** for normal state transitions (enter/exit, attempt/succeed),
   so that a later crash or non-fatal carries the feature's recent context.
2. **The non-fatal report API** ("Non-fatal bug" capture class) for a failed
   invariant or an unexpected error code, sampled to control volume.
3. **Crash keys** for a small, bounded snapshot of feature state (enabled flags,
   current mode) attached to any crash that occurs while the feature is active.

Rules:

* Reuse the breadcrumb event schema; do not invent per-feature log formats.
* Emit a non-fatal only on a genuine failure or broken invariant, not on every
  normal branch. Sample aggressively.
* Keep everything privacy-safe: no full URLs, page content, or user data —
  origin hashes and booleans only.
* Remove or sanitize existing ad-hoc debug logs before adding production
  collection. In particular, JavaScript logs that include `location.href` must
  not flow into logcat, BrowserStack artifacts, or crash attachments.
* Replace ad-hoc `LOG(ERROR)` debugging and any parallel analytics emission with
  the shared path, so there is one source of truth (see reconciliation below).

### Selecting high-risk features

Prioritize features that:

* Inject or depend on script running inside a frequently-changing third-party
  page (breaks silently on every site redesign).
* Drive multi-process or async state machines (renderer ↔ browser, media,
  fullscreen, PiP) where a dropped callback leaves a stuck state.
* Fire-and-forget work whose result is currently discarded.

### First candidate: YouTube script injector

`browser/android/youtube_script_injector/youtube_script_injector_tab_helper.cc`
is the model case. It is a `WebContentsObserver` that injects JavaScript into
YouTube pages for background playback, in-app PiP, autoplay control, and
fullscreen. Its real failure surface is silent:

* Scripts are injected with `base::NullCallback()`
  (`ExecuteJavaScriptIfYouTube`, and the comment-removal injection), so if a
  YouTube DOM change makes a script throw or no-op, **nothing is recorded** and
  the feature silently stops working.
* The fullscreen/PiP path is an async state machine
  (`SetFullscreenRequested`, `HasFullscreenBeenRequested`,
  `OnFullscreenScriptComplete`, `TriggerYouTubePiP`) that can get stuck if a
  callback never arrives or a renderer dies mid-sequence.
* It already carries ad-hoc `LOG(ERROR) << "BravePiPDebug ..."` debugging,
  JavaScript `console.error` calls that can include `location.href`, and a
  separate PostHog analytics emission. None of these should become production
  crash diagnostics as-is.

Signals this feature should emit once the non-fatal API and breadcrumbs exist:

| Signal | Type | Notes |
| --- | --- | --- |
| `yt_inject_attempted` / `yt_inject_succeeded` / `yt_inject_failed` | breadcrumb + non-fatal on failure | Stop using `NullCallback()` for the scripts that matter; pass a result callback and report a non-fatal with the script id + error class on failure. |
| `yt_pip_state_change`, `yt_fullscreen_state_change` | breadcrumb | Already partially mirrors the generic PiP/fullscreen breadcrumbs; tag with the feature so transitions are attributable. |
| `yt_state_stuck` | non-fatal | Emit if a fullscreen/PiP request has no completion callback within a timeout, or `IsRenderFrameLive()` is false at a point that should be live. |
| Crash key `yt_features` | crash key | Booleans for the relevant `features.cc` flags + current PiP/fullscreen mode, attached while a YouTube tab is active. |
| Remove `location.href` debug output | cleanup | Replace URL-bearing debug strings with origin category, hashed registered domain, or no URL before enabling automated artifact collection. |

### Reconciling existing telemetry

There are currently up to three overlapping signal paths: `BravePiPDebug`
`LOG(ERROR)` lines, the PostHog events added recently, and (after this plan) the
crash backend. Decide ownership explicitly:

* **Crash backend** owns failures and invariants developers act on (the
  non-fatals and crash keys above).
* **PostHog/analytics** owns product/usage metrics, under its own consent, only
  if this fork intentionally keeps PostHog or replaces it with an owned
  analytics destination.
* **`LOG(ERROR)` debug lines** are removed or downgraded once the structured
  signals exist; they are not a production observability mechanism.

Do not let the same failure fan out to all three with inconsistent consent,
mixed Brave/vendor ownership, and no single source of truth.

## Symbol and release artifact pipeline

### Release artifact manifest

Every release artifact should produce a manifest similar to:

```json
{
  "release_id": "android-stable-1.78.123-456789",
  "channel": "stable",
  "version_name": "1.78.123",
  "version_code": 456789,
  "package_name": "com.example.browser",
  "git_sha": "abcdef123456",
  "chromium_revision": "1234567",
  "build_time_utc": "2026-05-31T00:00:00Z",
  "abis": ["arm64-v8a", "armeabi-v7a"],
  "artifact": "app-release.aab",
  "r8_mapping": "mapping.txt",
  "play_native_symbols": "native-debug-symbols.zip",
  "breakpad_symbols": "breakpad-symbols.tar.zst",
  "unstripped_libs_archive": "lib.unstripped.tar.zst",
  "gn_args_hash": "sha256",
  "feature_flags_hash": "sha256"
}
```

Store the manifest and all referenced artifacts in immutable object storage.
Keep them for at least the maximum supported version lifetime plus one year.

### CI release gate

A release job must fail if any step below fails:

1. Build APK/AAB.
2. Generate R8/ProGuard mapping.
3. Generate native debug symbols for Play Console.
4. Generate Breakpad symbols with `tools/android/generate_breakpad_symbols.py`.
5. Archive unstripped libraries or equivalent debug files.
6. Upload mapping and native symbols to our Play Console app.
7. Upload mapping and Breakpad/native debug files to our crash backend.
8. Run a test Java crash and a test native crash on an internal build.
9. Verify both reports appear symbolicated in the backend.
10. Save the release artifact manifest.

Target operational metrics:

* Unsymbolicated fatal crash rate below 1%.
* Mapping upload success rate 100% for published releases.
* Native symbol upload success rate 100% for published releases.
* Test crash symbolication verified before store rollout.

## Crash ingestion backend

### Required API behavior

The ingestion API should accept:

* Minidump upload plus metadata.
* Java fatal and non-fatal JSON reports.
* ANR and ApplicationExitInfo reports.
* Renderer crash reports.
* Breadcrumb/session journal attachments.
* User feedback reports with explicit consent and optional URL attachment.

The API must:

* Return quickly after persisting raw data.
* Process symbolication asynchronously.
* Deduplicate retries using report UUID and release ID.
* Reject oversize reports.
* Enforce schema versioning.
* Strip or quarantine disallowed fields.
* Rate limit by app version, IP, report UUID, report type, and session or
  ephemeral identifier only if that identifier passes privacy review.

### Processing pipeline

1. Ingest raw report to immutable object storage.
2. Validate schema and privacy rules.
3. Resolve release manifest from version, version code, channel, ABI, and build
   ID.
4. Symbolicate native frames using Breakpad/native symbols.
5. Deobfuscate Java frames using mapping files.
6. Generate grouping fingerprint from exception type, top frames, process type,
   signal, and release.
7. Attach breadcrumbs, crash keys, and device dimensions.
8. Update crash cluster, regression, and alert tables.
9. Create or update engineering ticket when thresholds are crossed.
10. Emit a reproduction pack for Appium test generation.

### Suggested storage model

Minimum tables or collections:

* `release_builds`: immutable release manifest and symbol availability.
* `reports_raw`: raw object pointer, received time, schema version, checksum.
* `crash_reports`: normalized report, type, stack, device, release, grouping.
* `crash_groups`: fingerprint, title, owner, status, first/last seen, trend.
* `breadcrumbs`: bounded decoded event list or object pointer.
* `symbols`: symbol file metadata, upload status, verification status.
* `test_runs`: Appium/Firebase run metadata tied to crash groups.
* `test_artifacts`: screenshots, videos, logcat, Appium logs, network logs.

### Grouping rules

Group crashes by stable technical signature, not by device alone.

For native crashes:

* Signal and exception code.
* Process type.
* Top 5 symbolicated application/Chromium frames after removing crash-handler
  frames.
* Crashing thread.
* Build ID and ABI.

For Java crashes:

* Exception class.
* Top deobfuscated application/Chromium frames.
* Thread name.
* Process type.

For ANRs:

* Main-thread top blocked frames.
* Lock owner thread if available.
* Foreground/background state.
* Startup stage or current operation.

For renderer crashes:

* Renderer exit reason.
* Top native stack if minidump exists.
* Memory pressure state.
* Feature and page category.

## Privacy and security policy

Crash observability for a browser has stricter requirements than a normal app.

Default automatic reports may include:

* App version, channel, build ID, ABI.
* Android SDK, manufacturer, model, memory class, locale.
* Process type, startup stage, Activity name.
* Symbolicated stack traces.
* Privacy-safe breadcrumbs.
* Feature flag state or hash.
* Aggregated navigation category and status class.

Default automatic reports must not include:

* Full URL, query string, fragment, page title, page content, DOM, screenshots,
  cookies, auth headers, request bodies, saved passwords, autofill values,
  wallet data, seed phrases, account identifiers, email, phone, or contact
  data.

Optional user-submitted reports may include more context only with clear,
explicit consent:

* Current URL.
* Screenshot.
* User description.
* Additional logs.

Security requirements:

* Encrypt report upload with TLS.
* Encrypt raw minidumps and attachments at rest.
* Restrict raw minidump access to a small debugging group because minidumps can
  contain memory.
* Keep a privacy scrubber before indexing or displaying reports broadly.
* Apply retention windows by data type. For example, raw minidumps for 30 to 90
  days, symbolicated summaries for longer.
* Keep audit logs for raw report access.
* Maintain a deletion path for user-submitted reports.

## Dashboards, alerts, and triage

### Primary stability metrics

Track:

* Fatal crash-free sessions.
* Fatal crashes per 10,000 sessions.
* Fatal crashes per 10,000 foreground hours.
* ANRs per 10,000 sessions.
* Renderer crashes per 1,000 page loads.
* Low-memory exits per 10,000 sessions.
* Startup crash rate.
* Report upload success rate.
* Median time from crash to uploaded report.
* Unsymbolicated report rate.

Break down every metric by:

* App version and channel.
* Android version.
* Device model and manufacturer.
* ABI.
* Country/locale when allowed.
* Install source.
* Feature flag cohort.

### Alert rules

Create alerts for:

* New fatal crash group in beta or stable with at least 5 reports or affected
  sessions. Use distinct affected-user counts only if the identifier decision
  allows them.
* Any startup crash group in stable with at least 3 reports or affected
  sessions.
* Crash-free sessions drop by more than 0.2 percentage points version over
  version.
* Any crash group affecting more than 0.1% of sessions on the latest stable.
* ANR rate doubles on the latest release.
* Unsymbolicated crash rate above 1%.
* Report upload success rate below 95%.
* Renderer crash rate increases more than 25% after a release.

### Ticket workflow

For each crash group, generate a ticket with:

* Title from top frame and exception/signal.
* Severity and affected-session count. Include affected-user count only if a
  privacy-reviewed identifier exists.
* First seen, last seen, app versions, rollout percentage.
* Top devices, Android versions, ABIs, and feature cohorts.
* Symbolicated stack.
* Breadcrumb summary.
* Links to raw reports with restricted access.
* Suggested owner based on top frame path.
* Reproduction pack link.
* BrowserStack/Firebase test runs linked to the cluster.

Ticket status should feed back into the crash dashboard:

* New.
* Triaged.
* Reproducing.
* Fix in progress.
* Fixed in version.
* Monitoring rollout.
* Closed as duplicate.

## Real-device E2E reproduction

### Tool choice

Use BrowserStack App Automate with Appium 2 as the main real-device grid:

* BrowserStack provides hosted real Android devices, parallel execution, session
  artifacts, and Appium support.
* Appium gives us a portable test API and works with the UiAutomator2 Android
  driver.
* BrowserStack Local can cover staging/internal services if required.

Use Firebase Test Lab as a complementary system:

* Run Robo crawls on release candidates to discover crashes without writing a
  test for every flow.
* Run instrumentation tests across a smaller Google device matrix.
* Compare Firebase/Android vitals signals with our own crash backend.

Evaluate managed crash vendors separately:

* Sentry, Backtrace, Bugsnag, and Firebase Crashlytics can reduce backend work.
* The deciding criteria are Crashpad/Breakpad minidump support, symbol file
  support, privacy controls, raw dump access controls, cost at expected volume,
  and integration risk with Chromium's crash handler.
* If a vendor is used, it must be our account/project and must receive data only
  through our approved consent path.
* If no vendor fits Chromium's native pipeline, fork-ownership, and privacy
  constraints, build a small first-party ingestion service and use managed
  infrastructure only for storage, queues, and dashboards.

### Device matrix

The real-device matrix should be generated from production crash data, not from
developer preference.

Always include:

* Top 20 devices by active users.
* Top 20 devices by crash count.
* Top 10 devices by crash rate with enough sample size.
* Oldest supported Android version.
* Latest stable Android version.
* Latest Android beta only for nightly/pre-release validation.
* Low-memory devices.
* arm64-v8a and armeabi-v7a if both are shipped.
* Devices with known OEM customizations that affect backgrounding, WebView,
  permissions, or media.

Run levels:

| Level | Trigger | Scope |
| --- | --- | --- |
| PR smoke | Pull request | 5 to 10 virtual or real devices, critical flows |
| Nightly | Main branch nightly | 50 to 100 real devices, broad smoke and Robo |
| Release candidate | Store rollout candidate | 100 to 300 real devices, full regression suite |
| Targeted reproduction | New crash cluster | Devices/OS/features from the crash group |
| Hotfix validation | Fix branch | Crash-specific matrix plus top stable devices |

### Test harness design

Use Appium 2 with UiAutomator2 and a page-object style structure.

Recommended repo layout when implemented:

```text
test/e2e/appium/
  package.json
  appium.config.js
  browserstack.yml
  src/
    driver/
    pages/
    flows/
    fixtures/
    utils/
  tests/
    smoke/
    regression/
    crash_repro/
    release/
```

Required harness features:

* Test runs tagged with release ID, Git SHA, app version, device, OS, and crash
  group when applicable.
* Stable selectors. Prefer resource IDs and accessibility IDs over text.
* First-run setup helper.
* Test profile reset helper.
* URL/navigation helper with local test pages.
* Log collection helper for logcat, app logs, Appium logs, BrowserStack video,
  and screenshots.
* Crash detector that fails the test when the app dies, a renderer crash page
  appears, or a fatal report is generated locally.
* Retry only at the infrastructure layer, not inside test assertions.
* Quarantine mechanism for known flaky tests.

### Required flows

Start with these flows because they exercise browser-specific crash surfaces:

* Cold start and first-run.
* Warm start and tab restore.
* Open typed URL, search query, and internal page.
* Multiple tabs, tab switcher, close/restore tab.
* Background and foreground during page load.
* Rotate device during page load and video playback.
* Fullscreen video and picture-in-picture.
* Download file and open downloads UI.
* Android permissions: camera, microphone, location, notifications, storage.
* Clear browsing data.
* Private/incognito window.
* Settings pages touched by recent releases.
* Adblock/shields on pages with ads and trackers.
* Heavy page load with low-memory pressure.
* Offline, slow network, and network switch.
* External intent handling: open link from another app, share URL.
* Crash recovery after native test crash in internal builds.

### Reproduction packs

For every high-priority crash group, generate a reproduction pack:

```json
{
  "crash_group_id": "CRASH-2026-000123",
  "release_id": "android-stable-1.78.123-456789",
  "signature": "SIGSEGV content::RenderFrameHostImpl::...",
  "process_type": "browser",
  "top_devices": [
    {"model": "Pixel 8", "android": "15", "abi": "arm64-v8a"},
    {"model": "Galaxy A13", "android": "13", "abi": "arm64-v8a"}
  ],
  "feature_flags": {
    "pip": true,
    "fullscreen_video": true
  },
  "breadcrumb_summary": [
    "cold_start",
    "tabs_restored",
    "navigation_commit:https_origin_hash",
    "fullscreen_enter",
    "pip_enter",
    "background"
  ],
  "candidate_appium_flow": "video_pip_background_restore",
  "test_data": {
    "url_category": "video",
    "network": "wifi",
    "orientation": "landscape"
  }
}
```

The reproduction pack should produce:

* A generated or hand-authored Appium test case.
* BrowserStack device list.
* Required feature flags.
* Test data and local test pages.
* Links back to crash reports and tickets.

## Implementation phases

### Phase 0: Symbolication rescue

Target duration: 1 week.

Deliverables:

* Archive R8/ProGuard mapping for every build variant we ship.
* Archive native debug symbols and Breakpad symbols for every APK/AAB.
* Override inherited Brave/Google crash destinations with our crash endpoint.
* Upload mapping and native symbols to our Play Console app for current
  production and all future builds.
* Add CI release gate for symbol generation and upload.
* Add an internal Java crash and native crash smoke test to verify
  symbolication.

Acceptance criteria:

* A Java test crash from an internal release is deobfuscated.
* A native test crash from an internal release is symbolicated.
* Both test crashes arrive in our backend, not Brave's or Google's backend.
* A release cannot proceed if mapping or symbols are missing.

### Phase 1: First-party pending crash reports

Target duration: 2 to 3 weeks.

Deliverables:

* Local pending report store.
* Early startup uploader.
* Our ingestion endpoint for minidumps and JSON reports, or an owned managed
  vendor project.
* Our Breakpad/native symbol store.
* Async symbolication worker or verified vendor symbolication in our project.
* Minimal dashboard showing crash groups by version, device, OS, and top frame.
* Crash keys for release, process type, startup stage, feature flags, and
  device metadata.

Acceptance criteria:

* Fatal reports are uploaded after next launch if the app crashes offline.
* Upload happens only when crash-reporting consent and policy permit it.
* Upload goes to our configured endpoint.
* Reports retry after network returns.
* Native and Java reports are grouped and symbolicated.
* Uploading a report cannot block normal startup for more than a small fixed
  budget.

### Phase 2: Breadcrumbs, ANR/OOM, renderer failures

Target duration: 2 to 4 weeks.

Deliverables:

* Privacy-safe breadcrumb journal.
* ApplicationExitInfo capture on Android 11+.
* Renderer crash and tab crash reporting.
* Low-memory and trim-memory breadcrumbs.
* Crash-loop detector.
* Alert rules and ticket creation.
* Privacy scrubber and raw-report access controls.

Acceptance criteria:

* A crash report shows the last meaningful app/navigation/media events without
  full URLs or sensitive data.
* ANR and low-memory process exits produce reports on supported Android
  versions.
* Renderer crashes appear as first-class production issues.
* Raw minidump access is restricted and audited.

### Phase 3: Real-device automation

Target duration: 3 to 5 weeks.

Deliverables:

* Appium 2 test harness.
* BrowserStack project configuration.
* CI job for PR smoke tests.
* Nightly real-device matrix.
* Release-candidate real-device matrix.
* Firebase Test Lab Robo run for release candidates.
* Artifact collection and dashboard links.

Acceptance criteria:

* PR smoke suite runs on at least 5 devices.
* Nightly suite runs on at least 50 real devices.
* Release-candidate suite can run on at least 100 real devices.
* Test artifacts include video, screenshots, Appium logs, and logcat.
* A known internal test crash fails the Appium run and links to a crash report.

### Phase 4: Production-to-test feedback loop

Target duration: ongoing after Phase 3.

Deliverables:

* Crash-group reproduction packs.
* Automatic targeted BrowserStack matrix from top crash dimensions.
* Ticket updates with latest reproduction status.
* Flake tracking and quarantine workflow.
* Weekly stability review.

Acceptance criteria:

* Top new stable crash groups have a reproduction pack within one business day.
* Fixed crash groups get a targeted regression test before close.
* Release signoff includes crash dashboard status and device-farm results.

## Step-by-step execution plan

The phases above define *what* and *when*. This section breaks each phase into
ordered, individually shippable tasks with dependencies and rough sizing, so an
engineer can pick up the next unblocked task without re-deriving scope. It stays
lighter on per-file detail; exact classes/overrides are settled in each task's
design step.

Sizing is t-shirt effort for one engineer: **S** ≈ 1–2 days, **M** ≈ 3–5 days,
**L** ≈ 1–2 weeks. "Dep" lists task IDs that must land first. Tasks within a
phase are roughly ordered, but anything with disjoint deps can run in parallel.

### Phase 0 — Symbolication rescue

| ID | Task | Size | Dep |
| --- | --- | --- | --- |
| P0.1 | **Inventory inherited crash pipeline.** Run the "Audit checklist before Phase 1": confirm official Android builds init Crashpad, identify every Google/Brave upload destination, and document what each destination groups/symbolicates/retains. Output: one-page findings doc. | S | — |
| P0.2 | Archive R8/ProGuard `mapping.txt` for every shipped build variant as an immutable CI artifact. | S | — |
| P0.3 | Archive native debug symbols and Breakpad symbols per APK/AAB via `tools/android/generate_breakpad_symbols.py`; store unstripped libs. | M | — |
| P0.4 | Upload `mapping.txt` + native debug symbols to our Play Console app for current production and all future builds. | S | P0.2, P0.3 |
| P0.5 | Override Crashpad/Java upload destinations to our crash endpoint and upload Breakpad/native symbols to our backend or owned vendor project. | M | P0.1, P0.3 |
| P0.6 | Add CI **release gate**: fail the build if mapping/symbol generation or upload fails (the 10-step gate in "CI release gate"). | M | P0.4, P0.5 |
| P0.7 | Add internal test-crash smoke (one Java, one native) and verify both symbolicate end to end before rollout. | M | P0.5, P0.6 |

Exit: a release cannot proceed without mapping + symbols, and a test crash is
proven symbolicated in both our Play Console app and our crash backend.

### Phase 1 — First-party pending crash reports

| ID | Task | Size | Dep |
| --- | --- | --- | --- |
| P1.1 | **Client-ID decision** (see Open decisions): keep zeroed Crashpad ID, or introduce a privacy-reviewed session/ephemeral id. Blocks crash-loop and affected-user work. | S | P0.1 |
| P1.2 | Design the local pending report store layout (`pending_crashes/`, `pending_events/`, `sessions/`, `upload_state/`) with disk budget + eviction precedence. | S | — |
| P1.3 | Implement the pending report store (write path) and wire Crashpad minidump output into it. | M | P1.2 |
| P1.4 | Implement the **early startup uploader**: scan pending reports before tab restore, fixed foreground budget, then hand off. | M | P1.3 |
| P1.5 | Add WorkManager retry with network constraints + exponential backoff; check consent/policy before upload; delete only on 2xx ack from our endpoint. | M | P1.4 |
| P1.6 | Stand up or confirm our ingestion path for minidump + JSON, dedup by report UUID, schema versioning, oversize rejection, and privacy scrubbing. | L | P0.1, P0.5 |
| P1.7 | Stand up / confirm the Breakpad + native symbol store the backend symbolicates against. | M | P0.5, P1.6 |
| P1.8 | Async symbolication worker (native frames) + grouping fingerprint v1, or verified equivalent in our vendor project. | L | P1.7 |
| P1.9 | Add crash keys: release, channel, process type, startup stage, feature-flag hash, device metadata, session id. | M | P1.1, P1.3 |
| P1.10 | Minimal dashboard: crash groups by version/device/OS/top-frame. | M | P1.8 |

Exit: an offline crash uploads on next launch, retries after network returns,
and appears grouped + symbolicated without blocking startup beyond the budget.

### Phase 2 — Breadcrumbs, ANR/OOM, renderer failures

| ID | Task | Size | Dep |
| --- | --- | --- | --- |
| P2.1 | Design + implement the privacy-safe breadcrumb journal (bounded by count/bytes, last two sessions, structured events only). | L | P1.3 |
| P2.2 | Specify the `privacy_url` hashing scheme concretely (salt source, rotation, truncation) and get privacy review. | S | P1.1 |
| P2.3 | Instrument breadcrumb events: lifecycle, startup stages, tab/navigation, media/UI, storage/memory, network, feature states, errors. | M | P2.1, P2.2 |
| P2.4 | Capture `ApplicationExitInfo` at next startup (Android 11+): ANR, low-memory, native/Java crash linkage. | M | P1.4 |
| P2.5 | Crash-loop detector using the P1.1 session id; upload before normal tab restore. | M | P1.1, P1.4 |
| P2.6 | Renderer / tab / GPU crash reporting via Chromium WebContents hooks (privacy-safe origin hash, exit reason, tab + memory state). | L | P1.6 |
| P2.7 | Privacy scrubber + restricted, audited raw-minidump access before broad indexing. | M | P1.6 |
| P2.8 | Alert rules (the "Alert rules" list) + automatic ticket creation/update on threshold crossing. | M | P1.10 |
| P2.9 | Audit and extend the inherited `BravePureJavaExceptionReporter` path for our endpoint, mapping/deobfuscation, Java fatal metadata, and a new non-fatal API for caught exceptions / failed invariants. | L | P1.6, P1.9 |
| P2.10 | **Feature instrumentation pilot: YouTube script injector.** Wire the non-fatal API + breadcrumbs + crash keys per "Feature instrumentation"; replace `NullCallback()` on key scripts with result reporting; remove/sanitize URL-bearing `BravePiPDebug` and `location.href` logs; reconcile PostHog ownership. Serves as the template for other features. | M | P2.1, P2.9 |

Exit: reports carry last-state breadcrumbs without sensitive data; ANR/OOM/
renderer crashes are first-class; raw access is restricted and audited; one
high-risk feature (YouTube injector) reports silent failures end to end.

### Phase 3 — Real-device automation

| ID | Task | Size | Dep |
| --- | --- | --- | --- |
| P3.1 | Stand up the Appium 2 + UiAutomator2 harness skeleton (repo layout, driver, page objects, fixtures, utils). | L | — |
| P3.2 | BrowserStack App Automate project config + credentials/secrets in CI. | M | P3.1 |
| P3.3 | Harness features: stable selectors, first-run/profile-reset helpers, log/artifact collection, infra-layer-only retries, flaky quarantine. | M | P3.1 |
| P3.4 | Crash detector that fails a test on app death / renderer crash page / local fatal report. | M | P3.3, P1.3 |
| P3.5 | Author the "Required flows" smoke suite. | L | P3.3 |
| P3.6 | CI job: PR smoke on 5–10 devices. | M | P3.2, P3.5 |
| P3.7 | Nightly matrix: 50–100 real devices, broad smoke. | M | P3.6 |
| P3.8 | Release-candidate matrix: 100–300 devices, full regression. | M | P3.7 |
| P3.9 | Firebase Test Lab Robo crawl on release candidates; compare signals with our backend. | M | P3.2 |

Exit: PR/nightly/RC suites run on real devices with full artifacts, and a known
internal test crash fails the run and links to a crash report.

### Phase 4 — Production-to-test feedback loop (ongoing)

| ID | Task | Size | Dep |
| --- | --- | --- | --- |
| P4.1 | Reproduction-pack generator from crash-group dimensions (the repro-pack JSON). | M | P2.8, P3.5 |
| P4.2 | Auto-generate a targeted BrowserStack matrix from top crash devices/OS/features. | M | P4.1, P3.8 |
| P4.3 | Feed reproduction status back into tickets + crash dashboard. | M | P4.1, P2.8 |
| P4.4 | Flake tracking + quarantine workflow operationalized. | S | P3.3 |
| P4.5 | Weekly stability review; release signoff includes dashboard + device-farm status. | S | P2.8, P3.8 |

Exit: top new stable crash groups get a repro pack within one business day, and
fixed groups get a targeted regression test before close.

### Critical path and parallelism

* **Critical path:** P0.1 → P0.5 → P1.6 → P1.8 → P2.8 → P4.1/P4.3. The backend
  audit/extension (P1.6) and native symbolication (P1.8) gate most downstream
  value; start P0.1 immediately because everything keys off knowing what
  the inherited Google/Brave pipeline currently does and what must be
  overridden.
* **Parallelizable now:** P0.2/P0.3 (symbols), P3.1 (Appium skeleton), and P1.2
  (store design) have no cross-deps and can start day one with separate owners.
* **Decision gates:** P1.1 (client-ID) blocks P2.5 and affected-user accuracy;
  P2.2 (URL hashing) blocks breadcrumb instrumentation. Resolve both early —
  they are policy decisions, not code, and they stall otherwise-ready work.

## Open decisions

* Whether to use a managed crash backend or build first-party ingestion. The
  answer must be our backend or our managed-vendor project; Brave's
  `cr.brave.com` is an inherited implementation reference, not an acceptable
  production destination.
* Whether to keep the zeroed Crashpad client ID or introduce a privacy-reviewed
  ephemeral identifier. This gates crash-loop detection, affected-user counts,
  and per-install rate limiting.
* Whether Crashlytics is acceptable under the product privacy policy. If yes,
  it must be our Firebase project and must not replace the owned Crashpad path
  without explicit approval.
* Whether user-facing crash reporting is opt-in, opt-out, or channel-specific
  (today: official-build + `GetCollectStatsConsent()` gated; non-official builds
  do not upload).
* Retention period for raw minidumps.
* Exact event names and schema ownership.
* Which issue tracker owns automatic crash tickets.
* BrowserStack concurrency and budget.
* Minimum Android versions and ABI set for the device matrix.

## Risks and mitigations

| Risk | Mitigation |
| --- | --- |
| Native crash handler conflict | Keep Chromium crash handler primary; test any SDK native handler in release-like builds before stable. |
| Sensitive data in dumps | Restrict raw dump access, scrub indexed fields, use short retention, and require privacy review. |
| Missing symbols | Make symbol upload a release gate and run test-crash verification. |
| Upload drains startup or battery | Upload with a fixed startup budget and WorkManager constraints. |
| Huge report volume | Sample non-fatals, deduplicate retries, group on server, and cap local disk. |
| Flaky E2E suite | Stable selectors, artifact-rich failure reports, quarantine, and no assertion-level retries. |
| Device-farm cost | Generate matrix from production impact and use tiers by PR/nightly/release. |
| Crash loops prevent upload | Run a minimal uploader before heavy browser initialization. |

## Definition of done

The observability and reproduction system is ready when:

* Every shipped Android artifact has archived mapping, native debug files,
  Breakpad symbols, and a release manifest.
* Fatal native and Java crashes are symbolicated in our backend.
* Our Play Console Android vitals are also deobfuscated/symbolicated.
* Reports survive offline crashes and upload on next launch.
* Breadcrumbs explain the last user-visible browser states without leaking
  sensitive browsing data.
* ANR, OOM, renderer crash, and startup crash-loop reports exist.
* Crash groups produce alerts and tickets with owners.
* Appium tests run on BrowserStack real devices from CI.
* Firebase Test Lab or equivalent broad crawler runs against release
  candidates.
* Top production crash groups are linked to reproduction packs and targeted
  regression tests.

## References

Official references checked while writing this plan:

* Chromium crash reports:
  https://www.chromium.org/developers/crash-reports/
* Play Console deobfuscation and native symbolication:
  https://support.google.com/googleplay/android-developer/answer/9848633
* Firebase Crashlytics Android custom keys, logs, and non-fatals:
  https://firebase.google.com/docs/crashlytics/customize-crash-reports?platform=android
* Firebase Crashlytics Android API, including unsent report APIs:
  https://firebase.google.com/docs/reference/android/com/google/firebase/crashlytics/FirebaseCrashlytics
* Firebase Crashlytics NDK reports and symbol upload:
  https://firebase.google.com/docs/crashlytics/ndk-reports
* Android `ApplicationExitInfo`:
  https://developer.android.com/reference/android/app/ApplicationExitInfo
* BrowserStack App Automate with Appium:
  https://www.browserstack.com/docs/app-automate/appium
* BrowserStack App Automate debugging logs:
  https://www.browserstack.com/docs/app-automate/appium/debug-failed-tests
* Appium documentation:
  https://appium.io/docs/en/latest/
* Firebase Test Lab:
  https://firebase.google.com/docs/test-lab

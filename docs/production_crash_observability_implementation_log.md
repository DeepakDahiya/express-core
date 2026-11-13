# Production Crash Observability Implementation Log

Last updated: 2026-06-03

This document records the implementation changes made from the production crash
observability plan. Each entry names the code surface changed, why it changed,
and which patch artifact captures it.

## Implemented changes

### 1. Fork-owned native crash upload URL

Files:

* `components/crash_reporting/buildflags.gni`
* `chromium_src/components/crash/core/app/crash_reporter_client.cc`
* `patches/components-crash-core-app-BUILD.gn.patch`

What changed:

* Added `browser_express_crash_upload_url` and
  `browser_express_crash_product` GN args.
* Official builds now assert that `browser_express_crash_upload_url` is set.
* Brave and Google crash endpoints are rejected by GN assertions.
* The native Crashpad upload URL now comes from
  `BROWSER_EXPRESS_CRASH_UPLOAD_URL` instead of Brave's endpoint.

Patch artifacts:

* `docs/production_crash_observability_patches/01-crash-buildflags.patch`
* `docs/production_crash_observability_patches/02-native-crash-client.patch`
* `docs/production_crash_observability_patches/03-crash-core-build-patch-file.patch`

### 2. Fork-owned Android Java minidump upload URL

Files:

* `components/minidump_uploader/android/java/src/.../util/BraveHttpURLConnectionFactoryImpl.java`
* `components/minidump_uploader/android/javatests/src/.../BraveHttpURLConnectionFactoryImplTest.java`

What changed:

* Removed the hardcoded Brave minidump upload URL.
* The Java uploader now appends `product`, `version`, and a zeroed `guid`
  to the configured fork-owned upload URL.
* Empty upload URL disables Java minidump upload instead of sending reports to
  inherited Brave or Google infrastructure.
* Added tests for URL construction and disabled-upload behavior.

Patch artifacts:

* `docs/production_crash_observability_patches/07-minidump-http-factory.patch`
* `docs/production_crash_observability_patches/08-minidump-http-factory-tests.patch`

### 3. Generated Java minidump config from GN args

Files:

* `components/minidump_uploader/BUILD.gn`
* `components/minidump_uploader/android/java/templates/BraveMinidumpUploaderConfig.template`
* `components/minidump_uploader/sources.gni`
* `patches/components-minidump_uploader-BUILD.gn.patch`

What changed:

* Replaced the checked-in Java config class with a generated Java config
  srcjar.
* Native and Java crash upload paths now use the same GN values:
  `browser_express_crash_upload_url` and `browser_express_crash_product`.
* Updated the Chromium minidump uploader patch to import Brave sources once,
  append Brave Java sources/deps, and append the generated config srcjar.

Patch artifacts:

* `docs/production_crash_observability_patches/04-minidump-build-target.patch`
* `docs/production_crash_observability_patches/05-minidump-config-template.patch`
* `docs/production_crash_observability_patches/06-minidump-sources.patch`
* `docs/production_crash_observability_patches/09-minidump-build-patch-file.patch`

### 4. Internal Java/native crash verification hooks

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`
* `browser/android/browser_express_crash_verifier.cc`
* `android/java/org/chromium/chrome/browser/settings/developer/BraveQAPreferences.java`
* `android/java/res/xml/qa_preferences.xml`
* `android/brave_java_sources.gni`
* `build/android/config.gni`
* `browser/android/BUILD.gn`

What changed:

* Added an internal Java helper that intentionally triggers Java or native
  crashes for production crash pipeline verification.
* Java crash verification records a privacy-safe `qa_java` breadcrumb before
  throwing an uncaught `RuntimeException`.
* Native crash verification sets a Browser Express-owned native crash key
  named `browser-express-crash-verifier` before calling
  `base::ImmediateCrash()`.
* Native crash verification also records a privacy-safe `qa_native`
  breadcrumb before entering native code.
* Added QA-code-gated preferences for "Force Java crash" and
  "Force native crash", each behind a confirmation dialog.
* Wired the new Java class into Brave's Android Java sources, JNI header
  generation, and native Android browser process source set.

Patch artifacts:

* `docs/production_crash_observability_patches/10-crash-verifier-java.patch`
* `docs/production_crash_observability_patches/11-crash-verifier-native.patch`
* `docs/production_crash_observability_patches/12-crash-verifier-qa-ui.patch`
* `docs/production_crash_observability_patches/13-crash-verifier-build-wiring.patch`

### 5. Fork-owned crash breadcrumbs and Android last-exit signal

Files:

* `components/crash/android/crash_keys_android.h`
* `components/crash/android/crash_keys_android.cc`
* `components/crash/android/java/src/org/chromium/components/crash/CrashKeys.java`
* `patches/components-crash-android-crash-keys.patch`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashBreadcrumbs.java`
* `android/java/org/chromium/chrome/browser/BraveApplicationImplBase.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`
* `android/brave_java_sources.gni`

What changed:

* Added two Browser Express-owned Android crash keys:
  `browser_express_breadcrumbs` and `browser_express_exit_info`.
* Added a Chromium patch override for the crash-key extension so this fork
  keeps the new keys when Chromium/Brave patches are re-applied.
* Aligned `CrashKeys.java` key ordering with the generated `CrashKeyIndex`
  values while adding the new keys, keeping `installed_modules` at the alias
  index after the small crash-key array.
* Added a privacy-safe breadcrumb ring that stores at most eight short tokens
  and publishes a 63-character-or-shorter value to
  `browser_express_breadcrumbs`.
* Added startup capture for Android `ApplicationExitInfo` on Android 11+.
  The crash key stores only compact buckets: process role (`b`, `c`, `o`),
  reason, importance bucket, and age bucket.
* Wired breadcrumbs to browser-process application startup, foreground/
  background state changes, memory-trim callbacks, configuration changes, and
  internal QA crash verification.
* Explicitly avoids URL, page title, account, install, raw process-name, stack,
  exception-message, and device-identifier data in these crash keys.

Patch artifacts:

* `docs/production_crash_observability_patches/10-crash-verifier-java.patch`
  (updated to use the Browser Express breadcrumb key)
* `docs/production_crash_observability_patches/14-crash-key-extension.patch`
* `docs/production_crash_observability_patches/15-crash-breadcrumbs-java.patch`
* `docs/production_crash_observability_patches/16-crash-breadcrumb-lifecycle-wiring.patch`
* `docs/production_crash_observability_patches/17-crash-key-patch-file.patch`
* `docs/production_crash_observability_patches/18-implementation-log.patch`

### 6. Local pending crash metadata store and early upload enqueue

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `android/java/org/chromium/chrome/browser/BraveApplicationImplBase.java`
* `android/brave_java_sources.gni`

What changed:

* Added a Browser Express-owned cache layout:
  `Browser Express Crash Reports/pending_crashes`,
  `pending_events`, `sessions`, and `upload_state`.
* Added a startup background scan that imports Crashpad minidumps through
  Chromium's existing `CrashFileManager` and writes metadata JSON for
  minidumps that are ready to upload or still need logcat processing.
* Metadata is local-only and stores bounded crash state: file name, local crash
  id when available, upload attempt number, file size, timestamps, state, and
  upload transport. It does not store URLs, titles, accounts, device IDs, raw
  process names, exception messages, or stack text.
* When ready minidumps already exist, the startup scan enqueues Chromium's
  existing `MinidumpUploadServiceImpl.scheduleUploadJob()` instead of adding a
  parallel uploader. This preserves existing crash-reporting consent, policy,
  sampling, network, and retry checks while moving enqueue earlier in app
  startup.
* Stale metadata is deleted when the corresponding minidump is no longer
  pending.

Patch artifacts:

* `docs/production_crash_observability_patches/19-pending-crash-store-java.patch`
* `docs/production_crash_observability_patches/20-pending-crash-store-wiring.patch`
* `docs/production_crash_observability_patches/21-implementation-log.patch`

### 7. First-party JSON event upload path

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`

What changed:

* Added `BrowserExpressPendingCrashReportStore.recordNonFatal(...)` as the
  first privacy-safe API for feature code to enqueue non-fatal production
  events. The API accepts only short enum-like tokens for component, code, and
  severity.
* Added Android 11+ last-exit event capture for reportable
  `ApplicationExitInfo` reasons such as ANR, Java/native crash, low memory,
  excessive resource use, initialization failure, signal, dependency death,
  freezer, other, and unknown.
* Added a JSON event uploader for files in `pending_events/`. It POSTs to the
  same configured fork-owned crash URL used by minidumps, with
  `product`, `version`, zero GUID, and `report_type=browser_express_event`
  query parameters.
* Upload is gated by the existing Chromium crash-reporting permission manager:
  user consent, enterprise policy, crash sampling, network constraints, and
  the existing force-upload test switch.
* JSON event files are deleted only after a 2xx response. Failed uploads keep
  retry state in `upload_state/` and are retried up to three times on future
  startup scans or explicit non-fatal records.
* Pending event storage is capped at 20 files and each event file is capped at
  16 KB. Oldest events are dropped first.
* Uploaded JSON intentionally excludes URLs, page titles, account identifiers,
  device identifiers, raw process names, exception messages, and stack text.

Patch artifacts:

* `docs/production_crash_observability_patches/22-json-event-upload-store.patch`
* `docs/production_crash_observability_patches/23-implementation-log.patch`

### 8. Dedicated JSON event retry job

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressJsonEventUploadJobService.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressJsonEventUploadJobServiceImpl.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `android/brave_java_sources.gni`
* `android/java/AndroidManifest.xml`

What changed:

* Added a manifest-declared, non-exported JobScheduler service for retrying
  pending Browser Express JSON crash events.
* Added a split-compatible base-module service stub and a chrome-module
  implementation that runs uploads on `BEST_EFFORT_MAY_BLOCK` and calls
  `jobFinished(..., reschedule)` when complete.
* Pending JSON events now schedule this retry job whenever events remain after
  a startup scan or immediate non-fatal upload attempt.
* The retry job requires unmetered network and uses exponential backoff with a
  30-minute initial backoff.
* `onStopJob()` asks JobScheduler to retry if pending JSON event files still
  exist.

Patch artifacts:

* `docs/production_crash_observability_patches/25-json-event-retry-service.patch`
* `docs/production_crash_observability_patches/26-json-event-retry-store.patch`
* `docs/production_crash_observability_patches/27-json-event-retry-wiring.patch`
* `docs/production_crash_observability_patches/28-implementation-log.patch`
* `docs/production_crash_observability_patches/29-file-tracking.patch`

### 9. Feature instrumentation pilot: YouTube PiP/fullscreen

Files:

* `android/java/org/chromium/chrome/browser/app/BraveActivity.java`
* `android/java/org/chromium/chrome/browser/toolbar/bottom/BrowsingModeBottomToolbarCoordinator.java`
* `android/java/org/chromium/chrome/browser/toolbar/top/BraveToolbarLayoutImpl.java`

What changed:

* Added Browser Express breadcrumbs for the user-visible YouTube PiP/fullscreen
  path: bottom toolbar PiP click, back-press PiP request, top toolbar
  fullscreen request, PiP trigger, PiP entry, PiP exit, and PiP restore.
* Added privacy-safe non-fatal event enqueue calls for explicit failure or
  suspicious states:
  `pip_bottom_no_tab`, `pip_bottom_unavailable`, `pip_back_no_tab`,
  `pip_back_unavailable`, `pip_no_activity`, `pip_intro_no_activity`,
  `full_top_no_tab`, `full_top_unavailable`, `full_os_disabled`,
  `pip_origin_missing`, `pip_restore_missing`, and
  `pip_mode_change_error`.
* All new crash-observability payload values are fixed enum tokens. No URL,
  title, account, device id, raw exception text, or page data is sent through
  the Browser Express pending-event API.
* The instrumentation is intentionally placed in `chrome_java` call sites
  instead of the standalone YouTube native-helper Java target to avoid a GN
  dependency cycle while still covering the production UI paths.

Patch artifacts:

* `docs/production_crash_observability_patches/30-youtube-pip-feature-instrumentation.patch`
* `docs/production_crash_observability_patches/31-implementation-log.patch`
* `docs/production_crash_observability_patches/32-file-tracking.patch`

### 10. Release gate and crash symbol upload helpers

Files:

* `script/browser_express_crash_release_gate.py`
* `script/browser_express_upload_crash_symbols.py`
* `script/test/test_browser_express_crash_release_gate.py`

What changed:

* Added a Browser Express crash release gate script for CI/release jobs. It
  validates required crash-reporting files, required Brave patch overrides,
  fork-owned crash GN args, forbidden inherited Brave/Google crash endpoints,
  optional generated `args.gn`, optional Android symbol archives, and the
  production crash file-tracking doc.
* Added a Browser Express crash-symbol uploader for Android symbol archives.
  It streams a multipart upload to an owned HTTPS endpoint, attaches fixed
  product/version/channel metadata, supports bearer-token auth through an
  environment variable, has a dry-run mode, and refuses inherited Brave/Google
  crash endpoints.
* Added focused unit tests for the release-gate checks and symbol-uploader
  validation paths. Tests use temporary files and do not perform network
  uploads.

Release usage:

```sh
python3 script/browser_express_crash_release_gate.py \
  --out-dir ../out/android_Release_arm64 \
  --require-official-config \
  --require-symbols \
  --check-tracking-doc
```

```sh
BROWSER_EXPRESS_SYMBOL_UPLOAD_URL="https://<owned-symbol-endpoint>/..." \
BROWSER_EXPRESS_SYMBOL_UPLOAD_TOKEN="<secret>" \
python3 script/browser_express_upload_crash_symbols.py \
  --symbols-archive ../out/android_Release_arm64/dist/Defaultarm64.tar \
  --version <version> \
  --channel release
```

Patch artifacts:

* `docs/production_crash_observability_patches/33-release-gate-script.patch`
* `docs/production_crash_observability_patches/34-symbol-upload-script.patch`
* `docs/production_crash_observability_patches/35-release-gate-tests.patch`
* `docs/production_crash_observability_patches/36-implementation-log.patch`
* `docs/production_crash_observability_patches/37-file-tracking.patch`

### 11. Backend crash ingestion API contract

Files:

* `docs/browser_express_crash_backend_api.md`
* `docs/README.md`

What changed:

* Added a backend developer API contract for implementing Browser Express crash
  ingestion on the existing backend infrastructure.
* Documented the current Android client request shapes:
  gzip-compressed multipart minidump uploads and JSON
  `browser_express_event` uploads to the same configured crash URL.
* Documented required response codes, report-id response body behavior, JSON
  event schemas, symbol upload contract, storage layout, database model,
  privacy filters, rate limits, symbolication workflow, monitoring, retention,
  and production readiness checklist.
* Linked the backend API document from the docs index.

Patch artifacts:

* `docs/production_crash_observability_patches/38-crash-backend-api-doc.patch`
* `docs/production_crash_observability_patches/39-docs-readme.patch`
* `docs/production_crash_observability_patches/40-implementation-log.patch`
* `docs/production_crash_observability_patches/41-file-tracking.patch`

### 12. Production backend endpoint wiring

Files:

* `components/crash_reporting/buildflags.gni`
* `script/browser_express_crash_release_gate.py`
* `script/browser_express_upload_crash_symbols.py`
* `script/test/test_browser_express_crash_release_gate.py`

What changed:

* Set the default Browser Express crash upload URL to the production backend
  endpoint:
  `https://api.browser.express/crash/android/upload`.
* Kept local development overridable through `args.gn`, for example
  `http://localhost:4000/crash/android/upload`.
* Updated the release gate to require the Browser Express production crash
  endpoint as the repo default while still rejecting inherited Brave/Google
  crash endpoints.
* Updated the symbol upload helper to default to
  `https://api.browser.express/crash/android/symbols`, while still allowing CI
  to override with `BROWSER_EXPRESS_SYMBOL_UPLOAD_URL`.
* Added tests covering official builds that rely on the repo default endpoint
  and symbol upload dry-runs that rely on the backend default symbol endpoint.

Patch artifacts:

* `docs/production_crash_observability_patches/42-production-crash-endpoint-config.patch`
* `docs/production_crash_observability_patches/43-release-gate-production-endpoints.patch`
* `docs/production_crash_observability_patches/44-release-gate-production-tests.patch`
* `docs/production_crash_observability_patches/45-implementation-log.patch`
* `docs/production_crash_observability_patches/46-file-tracking.patch`
* `docs/production_crash_observability_patches/47-symbol-upload-production-endpoint.patch`

### 13. Applied native Crashpad build wiring in active Chromium tree

Files:

* `components/crash/core/app/BUILD.gn` (top-level Chromium file outside
  `//brave`)
* `patches/components-crash-core-app-BUILD.gn.patch`

What changed:

* Applied the existing Browser Express crash core build override to the active
  top-level Chromium `BUILD.gn` used by GN generation in this checkout.
* `//components/crash/core/app:lib` now imports
  `//brave/components/crash_reporting/buildflags.gni` in the active tree.
* The native crash reporter client compile now receives
  `BROWSER_EXPRESS_CRASH_UPLOAD_URL="$browser_express_crash_upload_url"`.
* This fixes official Android builds where the Brave source override compiled
  with the fallback empty `BROWSER_EXPRESS_CRASH_UPLOAD_URL` and failed the
  static assertion in
  `brave/chromium_src/components/crash/core/app/crash_reporter_client.cc`.
* The Brave patch override remains required so this build wiring is preserved
  whenever Chromium patches are re-applied.
* Regenerated `patches/components-crash-core-app-BUILD.gn.patch` with:

```sh
npm run update_patches -- components/crash/core/app/BUILD.gn
```

Patch artifacts:

* `docs/production_crash_observability_patches/48-native-crash-core-build-applied.patch`
* `docs/production_crash_observability_patches/49-implementation-log-native-crash-build-wiring.patch`
* `docs/production_crash_observability_patches/50-file-tracking-native-crash-build-wiring.patch`

### 14. Persisted previous-session feature journal

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressFeatureJournal.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashBreadcrumbs.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `android/brave_java_sources.gni`
* `docs/browser_express_crash_backend_api.md`

What changed:

* Added a Browser Express-owned feature journal that persists bounded
  privacy-safe breadcrumb tokens into
  `Browser Express Crash Reports/sessions/current_session.json`.
* On browser-process startup, the previous `current_session.json` is rotated
  to `previous_session.json` before the new session records `app_create`.
* The journal stores at most 32 short enum tokens, each limited to lowercase
  letters, digits, and underscores. It does not store URLs, titles, accounts,
  device IDs, process names, exception text, stack text, page data, or
  free-form strings.
* Existing `BrowserExpressCrashBreadcrumbs.recordEvent(...)` now mirrors the
  same safe tokens into the persisted feature journal.
* Android 11+ `android_exit` JSON events now attach an optional
  `previous_session` object with only event tokens, event count, and a
  bucketed `last_seen_age`.
* Updated the backend API contract to document the optional
  `previous_session` payload and validation rules.

Patch artifacts:

* `docs/production_crash_observability_patches/51-feature-journal-java.patch`
* `docs/production_crash_observability_patches/52-feature-journal-breadcrumbs-wiring.patch`
* `docs/production_crash_observability_patches/53-feature-journal-event-store-wiring.patch`
* `docs/production_crash_observability_patches/54-feature-journal-sources-wiring.patch`
* `docs/production_crash_observability_patches/55-backend-api-previous-session-journal.patch`
* `docs/production_crash_observability_patches/56-implementation-log-feature-journal.patch`
* `docs/production_crash_observability_patches/57-file-tracking-feature-journal.patch`

### 15. Startup crash-loop signal on Android exits

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `docs/browser_express_crash_backend_api.md`

What changed:

* Added a privacy-safe startup-loop detector that uses Android 11+
  `ApplicationExitInfo` on startup.
* The detector tracks repeated launch-time crash-like exits within a bounded
  10-minute window. It only considers ANR, Java crash, native crash,
  dependency death, excessive resource usage, initialization failure, low
  memory, and signal exits.
* Existing `android_exit` JSON events now attach an optional `startup_loop`
  object once two or more loop-like exits are seen in the active window.
* The `startup_loop` payload contains only `schema`, a count bucket (`2`,
  `3`, or `4p`), and a bucketed `window_age`. It does not include exact
  timestamps, install IDs, account identifiers, device identifiers, raw process
  names, exception text, stack text, or free-form strings.
* Updated the backend API contract to document `startup_loop` validation and
  test coverage.

Patch artifacts:

* `docs/production_crash_observability_patches/58-startup-crash-loop-detector.patch`
* `docs/production_crash_observability_patches/59-backend-api-startup-crash-loop.patch`
* `docs/production_crash_observability_patches/60-implementation-log-startup-crash-loop.patch`
* `docs/production_crash_observability_patches/61-file-tracking-startup-crash-loop.patch`

### 16. Renderer/tab crash signal

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressTabCrashObserver.java`
* `android/java/org/chromium/chrome/browser/app/BraveActivity.java`
* `android/brave_java_sources.gni`
* `docs/browser_express_crash_backend_api.md`

What changed:

* Added a Browser Express-owned `TabModelSelectorTabObserver` that observes all
  tabs owned by the activity and receives `TabObserver.onCrash(...)` callbacks.
* A tab renderer crash now records a `tab_crash` breadcrumb for native/Java
  crash context and enqueues a privacy-safe JSON event:
  `event_type=non_fatal`, `component=renderer`, `code=tab_crash`,
  `severity=error`.
* The event uses the already implemented backend-compatible `non_fatal`
  schema, so no new backend endpoint or event type is required.
* The activity registers the observer after post-inflation startup and destroys
  it during `onDestroyInternal()` to avoid leaking tab observers.
* The payload intentionally excludes URL, title, account, device id, tab id,
  exception text, stack text, and page content.

Patch artifacts:

* `docs/production_crash_observability_patches/62-renderer-tab-crash-observer.patch`
* `docs/production_crash_observability_patches/63-renderer-tab-crash-activity-wiring.patch`
* `docs/production_crash_observability_patches/64-renderer-tab-crash-source-wiring.patch`
* `docs/production_crash_observability_patches/65-backend-api-renderer-tab-crash.patch`
* `docs/production_crash_observability_patches/66-implementation-log-renderer-tab-crash.patch`
* `docs/production_crash_observability_patches/67-file-tracking-renderer-tab-crash.patch`

### 17. Crash-loop fix for pre-native minidump scan

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `android/java/org/chromium/chrome/browser/app/BraveActivity.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressFeatureJournal.java`

What changed:

* Fixed a startup crash loop found during QA crash testing. The previous
  startup scan called `CrashFileManager.getMinidumpsSansLogcat()` from
  `Application.onCreate`, before the native crash MIME writer JNI was
  available. With a pending Crashpad minidump, this threw
  `UnsatisfiedLinkError` on every relaunch.
* `BrowserExpressPendingCrashReportStore.initialize()` now runs only the safe
  JSON event and Android-exit scan during pre-native application startup.
* Minidump import, metadata refresh, and upload-job enqueue now run after
  `BraveActivity.finishNativeInitialization()` calls
  `BrowserExpressPendingCrashReportStore.onNativeInitialized()`.
* The minidump scan also catches `UnsatisfiedLinkError` defensively, so a JNI
  availability problem cannot create a repeat startup crash loop.
* Serialized Browser Express feature-journal atomic writes to avoid concurrent
  background writes racing on the same `current_session.json.tmp` file and
  producing noisy commit warnings during startup.

Patch artifacts:

* `docs/production_crash_observability_patches/68-pre-native-minidump-scan-guard.patch`
* `docs/production_crash_observability_patches/69-post-native-minidump-scan-wiring.patch`
* `docs/production_crash_observability_patches/70-feature-journal-write-lock.patch`
* `docs/production_crash_observability_patches/71-implementation-log-crash-loop-fix.patch`
* `docs/production_crash_observability_patches/72-file-tracking-crash-loop-fix.patch`

### 18. Force Java crash termination hardening

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`

What changed:

* Hardened the QA "Force Java crash" action so it always terminates the app
  process after creating the Java crash report.
* The verifier now sends the intentional
  `BrowserExpressCrashVerifierJavaCrash` exception through the current default
  uncaught-exception handler, preserving Chromium's pure-Java crash-report
  creation path.
* If the installed handler reports the exception and returns without a parent
  process-killing handler, the verifier explicitly kills the current process
  and exits. This prevents the UI from briefly blanking and returning to the
  app without a real process death during QA validation.

Patch artifacts:

* `docs/production_crash_observability_patches/73-force-java-crash-termination.patch`
* `docs/production_crash_observability_patches/74-implementation-log-force-java-crash.patch`
* `docs/production_crash_observability_patches/75-file-tracking-force-java-crash.patch`

### 19. Deterministic QA app crash trigger

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`
* `android/java/org/chromium/chrome/browser/settings/developer/BraveQAPreferences.java`
* `android/java/res/xml/qa_preferences.xml`

What changed:

* Added a QA-only "Force app crash" preference for validating process-death,
  relaunch, Android-exit capture, and pending upload behavior when the Java
  exception verifier is not terminating reliably on a device.
* The new trigger records the `qa_app` breadcrumb and sends `SIGABRT` to the
  current process from Java, followed by kill/exit fallbacks.
* This is intentionally separate from "Force Java crash" so later work can
  debug the Java exception reporter path without blocking crash-pipeline
  validation.

Patch artifacts:

* `docs/production_crash_observability_patches/76-force-app-crash-trigger.patch`
* `docs/production_crash_observability_patches/77-force-app-crash-qa-wiring.patch`
* `docs/production_crash_observability_patches/78-force-app-crash-qa-xml.patch`
* `docs/production_crash_observability_patches/79-implementation-log-force-app-crash.patch`
* `docs/production_crash_observability_patches/80-file-tracking-force-app-crash.patch`

### 20. Immediate QA app crash trigger

Files:

* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`
* `android/java/org/chromium/chrome/browser/settings/developer/BraveQAPreferences.java`

What changed:

* Changed the QA-only "Force app crash" preference to terminate immediately
  when tapped, bypassing the confirmation dialog path. Device testing showed
  the confirmation/fragment path could return to the previous settings page
  without executing the process-death verifier.
* Added a `BECrashVerifier` log line before termination so testers can confirm
  whether the verifier method was reached.
* Added `Runtime.getRuntime().halt(10)` as the final fallback in the
  app-crash verifier only.

Patch artifacts:

* `docs/production_crash_observability_patches/81-immediate-force-app-crash-trigger.patch`
* `docs/production_crash_observability_patches/82-immediate-force-app-crash-qa-wiring.patch`
* `docs/production_crash_observability_patches/83-implementation-log-immediate-app-crash.patch`
* `docs/production_crash_observability_patches/84-file-tracking-immediate-app-crash.patch`

## Validation

Focused validation run:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  brave/components/minidump_uploader:junit
```

Result:

* Build succeeded.
* GN regenerated successfully.
* Generated target
  `//brave/components/minidump_uploader:brave_minidump_uploader_config_java`
  built successfully.
* The minidump uploader Java target compiled, ran bytecode rewrite, and
  validated successfully.

Additional checks:

* `git diff --check` passed for the crash/minidump implementation files.
* Search over the touched crash/minidump override paths found no live
  `cr.brave.com` or `clients2.google.com/cr/report` upload URL outside tests.
* Crash verifier native and Java compile validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  brave/browser/android:android_browser_process \
  chrome/android:chrome_java__compile_java
```

Result:

* Native file `browser_express_crash_verifier.cc` compiled into
  `obj/brave/browser/android/android_browser_process/browser_express_crash_verifier.o`.
* `chrome/android:chrome_java__compile_java` completed successfully, validating
  the Java source and generated JNI symbol references.
* The broader `chrome/android:chrome_java` target was attempted and failed on
  an unrelated existing ErrorProne warning in
  `chrome/browser/ui/android/toolbar/java/src/.../ToolbarPhone.java`
  (`mIsHomeButtonEnabled` unused).

Crash breadcrumb and Android crash-key validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  brave/browser/android:android_browser_process \
  chrome/android:chrome_java__compile_java
```

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  components/crash/android:java__compile_java \
  components/crash/android:crash_android
```

Result:

* Both focused builds succeeded.
* Generated `CrashKeyIndex` now includes
  `BROWSER_EXPRESS_BREADCRUMBS = 3`,
  `BROWSER_EXPRESS_EXIT_INFO = 4`, and `NUM_SMALL_KEYS = 5`.
* The crash component Java jar contains the new crash-key constants and key
  names.
* The native `crash_keys_android.o` object contains
  `browser_express_breadcrumbs` and `browser_express_exit_info`.

Pending crash metadata store validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` included
  `BrowserExpressPendingCrashReportStore.java`, validating the startup wiring,
  JSON metadata writer, and minidump uploader references.

JSON event upload validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the JSON uploader, the
  `ApplicationExitInfo` event capture, generated crash endpoint config access,
  and the public non-fatal event enqueue API.

Dedicated JSON retry job validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java \
  chrome/android:base_module_java__compile_java
```

Result:

* Build succeeded.
* `base_module_java__compile_java` validated the manifest-facing
  `BrowserExpressJsonEventUploadJobService` stub.
* `chrome_java__compile_java` validated
  `BrowserExpressJsonEventUploadJobServiceImpl` and the pending-store retry
  scheduling logic.

Feature instrumentation pilot validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java \
  chrome/android:base_module_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the YouTube PiP/fullscreen call-site
  imports and the calls into `BrowserExpressCrashBreadcrumbs` and
  `BrowserExpressPendingCrashReportStore`.
* Endpoint scan over the three touched feature files found no Brave, Google,
  Firebase, or other third-party crash ingestion URLs.

Release gate and crash symbol helper validation succeeded:

```sh
python3 -m py_compile \
  script/browser_express_crash_release_gate.py \
  script/browser_express_upload_crash_symbols.py \
  script/test/test_browser_express_crash_release_gate.py
```

```sh
python3 -m unittest script/test/test_browser_express_crash_release_gate.py
```

```sh
python3 script/browser_express_crash_release_gate.py --check-tracking-doc
```

Result:

* Python compilation succeeded.
* Unit tests passed: 6 tests.
* The workspace release gate passed in non-release mode with tracking-doc
  validation enabled.

Backend API documentation validation succeeded:

```sh
git diff --check -- docs/browser_express_crash_backend_api.md docs/README.md \
  docs/production_crash_observability_implementation_log.md \
  docs/production_crash_observability_file_tracking.md
```

Result:

* Markdown/doc diffs passed whitespace validation.

Production backend endpoint wiring validation succeeded:

```sh
python3 -m py_compile \
  script/browser_express_crash_release_gate.py \
  script/browser_express_upload_crash_symbols.py \
  script/test/test_browser_express_crash_release_gate.py
```

```sh
python3 -m unittest script/test/test_browser_express_crash_release_gate.py
```

```sh
python3 script/browser_express_crash_release_gate.py --check-tracking-doc
```

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  gn gen ../out/android_Release_arm64
```

Result:

* Python compilation succeeded.
* Unit tests passed: 9 tests.
* The release gate passed with tracking-doc validation enabled.
* Official Android arm64 GN generation succeeded, confirming the previous
  official-build failure on an empty `browser_express_crash_upload_url` is
  resolved.
* The generated Ninja action for
  `brave_minidump_uploader_config_java` passes
  `BROWSER_EXPRESS_CRASH_UPLOAD_URL="https://api.browser.express/crash/android/upload"`.

Native Crashpad production endpoint build wiring validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  gn gen ../out/android_Release_arm64
```

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  gn desc ../out/android_Release_arm64 //components/crash/core/app:lib defines
```

```sh
autoninja -C ../out/android_Release_arm64 components/crash/core/app:lib
```

Result:

* Official Android arm64 GN generation succeeded after applying the active
  Chromium `BUILD.gn` wiring.
* `gn desc` confirmed
  `BROWSER_EXPRESS_CRASH_UPLOAD_URL="https://api.browser.express/crash/android/upload"`
  on `//components/crash/core/app:lib`.
* The focused crash reporter target built successfully, including
  `obj/components/crash/core/app/lib/crash_reporter_client.o`.

Previous-session feature journal validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* GN regenerated successfully after adding
  `BrowserExpressFeatureJournal.java` to `android/brave_java_sources.gni`.
* `chrome_java__compile_java` validated the new journal class, breadcrumb
  mirroring, and optional `previous_session` object on Android exit JSON
  events.

Startup crash-loop signal validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the startup-loop state tracking,
  optional `startup_loop` object construction, and backend-facing Android exit
  event payload code.

Renderer/tab crash signal validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated
  `BrowserExpressTabCrashObserver.java`, activity registration/cleanup, source
  list wiring, and the call into the existing Browser Express non-fatal JSON
  event upload path.

Crash-loop fix validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the pre-native JSON-only scan, the
  post-native minidump scan hook, and feature-journal write locking.

Force Java crash termination hardening validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the explicit process-termination
  fallback in `BrowserExpressCrashVerifier.forceJavaCrash()`.

Deterministic QA app crash trigger validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the new `forceAppCrash()` verifier
  method, QA preference wiring, and XML preference entry.

Immediate QA app crash trigger validation succeeded:

```sh
PYTHONPATH=/home/deepakdahiyagh/Desktop/browser-express/src/brave/script \
  autoninja -C ../out/android_Debug_arm64 \
  chrome/android:chrome_java__compile_java
```

Result:

* Build succeeded.
* `chrome_java__compile_java` validated the immediate QA preference click
  handler and `forceAppCrash()` verifier fallback.

## Required production configuration

Before shipping official builds, set:

```gn
browser_express_crash_upload_url = "https://api.browser.express/crash/android/upload"
browser_express_crash_product = "BrowserExpress_Android"
```

The repo default now points to the production Browser Express crash backend.
Local development can override it in `args.gn`, for example:

```gn
browser_express_crash_upload_url = "http://localhost:4000/crash/android/upload"
```

Do not set this URL to Brave or Google crash ingestion. The GN assertions and
release gate are intended to fail fast if inherited endpoints are accidentally
used.

## Still pending

* Wire the release gate and symbol uploader into the real CI/release pipeline
  using `https://api.browser.express/crash/android/upload` and
  `https://api.browser.express/crash/android/symbols`.
* Extend feature instrumentation beyond the initial YouTube PiP/fullscreen
  pilot.
* Convert ANR/OOM/ApplicationExitInfo signals into full pending reports, not
  only crash-key context.
* Add Appium/BrowserStack reproduction runs.

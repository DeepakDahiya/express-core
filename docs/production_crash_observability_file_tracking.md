# Production Crash Observability File Tracking

Last updated: 2026-06-03

This file tracks the files changed for the Browser Express production crash
observability implementation and the patch artifacts generated for those
changes.

## Implementation files updated in `//brave`

* `android/brave_java_sources.gni`
* `android/java/AndroidManifest.xml`
* `android/java/org/chromium/chrome/browser/BraveApplicationImplBase.java`
* `android/java/org/chromium/chrome/browser/app/BraveActivity.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashBreadcrumbs.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressFeatureJournal.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressJsonEventUploadJobService.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressJsonEventUploadJobServiceImpl.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressCrashVerifier.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressPendingCrashReportStore.java`
* `android/java/org/chromium/chrome/browser/crash/BrowserExpressTabCrashObserver.java`
* `android/java/org/chromium/chrome/browser/settings/developer/BraveQAPreferences.java`
* `android/java/res/xml/qa_preferences.xml`
* `android/java/org/chromium/chrome/browser/toolbar/bottom/BrowsingModeBottomToolbarCoordinator.java`
* `android/java/org/chromium/chrome/browser/toolbar/top/BraveToolbarLayoutImpl.java`
* `browser/android/BUILD.gn`
* `browser/android/browser_express_crash_verifier.cc`
* `build/android/config.gni`
* `chromium_src/components/crash/core/app/crash_reporter_client.cc`
* `components/crash_reporting/buildflags.gni`
* `components/minidump_uploader/BUILD.gn`
* `components/minidump_uploader/android/java/src/org/chromium/components/minidump_uploader/util/BraveHttpURLConnectionFactoryImpl.java`
* `components/minidump_uploader/android/java/templates/BraveMinidumpUploaderConfig.template`
* `components/minidump_uploader/android/javatests/src/org/chromium/components/minidump_uploader/BraveHttpURLConnectionFactoryImplTest.java`
* `components/minidump_uploader/sources.gni`
* `docs/browser_express_crash_backend_api.md`
* `docs/README.md`
* `docs/production_crash_observability_and_e2e_reproduction_plan.md`
* `docs/production_crash_observability_implementation_log.md`
* `docs/production_crash_observability_file_tracking.md`
* `script/browser_express_crash_release_gate.py`
* `script/browser_express_upload_crash_symbols.py`
* `script/test/test_browser_express_crash_release_gate.py`

## Chromium files updated outside `//brave`

These are top-level Chromium files that require Brave patch overrides to avoid
losing Browser Express crash-observability changes during patch application.

* `components/crash/android/crash_keys_android.cc`
* `components/crash/android/crash_keys_android.h`
* `components/crash/android/java/src/org/chromium/components/crash/CrashKeys.java`
* `components/crash/core/app/BUILD.gn`

## Brave patch override files

These files live in `//brave/patches` and are part of the implementation, not
just documentation artifacts.

* `patches/components-crash-android-crash-keys.patch`
* `patches/components-crash-core-app-BUILD.gn.patch`
* `patches/components-minidump_uploader-BUILD.gn.patch`

## Generated patch artifacts

These artifacts live under `docs/production_crash_observability_patches/`.

* `01-crash-buildflags.patch`
* `02-native-crash-client.patch`
* `03-crash-core-build-patch-file.patch`
* `04-minidump-build-target.patch`
* `05-minidump-config-template.patch`
* `06-minidump-sources.patch`
* `07-minidump-http-factory.patch`
* `08-minidump-http-factory-tests.patch`
* `09-minidump-build-patch-file.patch`
* `10-crash-verifier-java.patch`
* `11-crash-verifier-native.patch`
* `12-crash-verifier-qa-ui.patch`
* `13-crash-verifier-build-wiring.patch`
* `14-crash-key-extension.patch`
* `15-crash-breadcrumbs-java.patch`
* `16-crash-breadcrumb-lifecycle-wiring.patch`
* `17-crash-key-patch-file.patch`
* `18-implementation-log.patch`
* `19-pending-crash-store-java.patch`
* `20-pending-crash-store-wiring.patch`
* `21-implementation-log.patch`
* `22-json-event-upload-store.patch`
* `23-implementation-log.patch`
* `24-file-tracking.patch`
* `25-json-event-retry-service.patch`
* `26-json-event-retry-store.patch`
* `27-json-event-retry-wiring.patch`
* `28-implementation-log.patch`
* `29-file-tracking.patch`
* `30-youtube-pip-feature-instrumentation.patch`
* `31-implementation-log.patch`
* `32-file-tracking.patch`
* `33-release-gate-script.patch`
* `34-symbol-upload-script.patch`
* `35-release-gate-tests.patch`
* `36-implementation-log.patch`
* `37-file-tracking.patch`
* `38-crash-backend-api-doc.patch`
* `39-docs-readme.patch`
* `40-implementation-log.patch`
* `41-file-tracking.patch`
* `42-production-crash-endpoint-config.patch`
* `43-release-gate-production-endpoints.patch`
* `44-release-gate-production-tests.patch`
* `45-implementation-log.patch`
* `46-file-tracking.patch`
* `47-symbol-upload-production-endpoint.patch`
* `48-native-crash-core-build-applied.patch`
* `49-implementation-log-native-crash-build-wiring.patch`
* `50-file-tracking-native-crash-build-wiring.patch`
* `51-feature-journal-java.patch`
* `52-feature-journal-breadcrumbs-wiring.patch`
* `53-feature-journal-event-store-wiring.patch`
* `54-feature-journal-sources-wiring.patch`
* `55-backend-api-previous-session-journal.patch`
* `56-implementation-log-feature-journal.patch`
* `57-file-tracking-feature-journal.patch`
* `58-startup-crash-loop-detector.patch`
* `59-backend-api-startup-crash-loop.patch`
* `60-implementation-log-startup-crash-loop.patch`
* `61-file-tracking-startup-crash-loop.patch`
* `62-renderer-tab-crash-observer.patch`
* `63-renderer-tab-crash-activity-wiring.patch`
* `64-renderer-tab-crash-source-wiring.patch`
* `65-backend-api-renderer-tab-crash.patch`
* `66-implementation-log-renderer-tab-crash.patch`
* `67-file-tracking-renderer-tab-crash.patch`
* `68-pre-native-minidump-scan-guard.patch`
* `69-post-native-minidump-scan-wiring.patch`
* `70-feature-journal-write-lock.patch`
* `71-implementation-log-crash-loop-fix.patch`
* `72-file-tracking-crash-loop-fix.patch`
* `73-force-java-crash-termination.patch`
* `74-implementation-log-force-java-crash.patch`
* `75-file-tracking-force-java-crash.patch`
* `76-force-app-crash-trigger.patch`
* `77-force-app-crash-qa-wiring.patch`
* `78-force-app-crash-qa-xml.patch`
* `79-implementation-log-force-app-crash.patch`
* `80-file-tracking-force-app-crash.patch`
* `81-immediate-force-app-crash-trigger.patch`
* `82-immediate-force-app-crash-qa-wiring.patch`
* `83-implementation-log-immediate-app-crash.patch`
* `84-file-tracking-immediate-app-crash.patch`

## Known unrelated dirty files

These files appear in the workspace but are not part of the crash observability
implementation tracked above.

* `package.json`
* `docs/referral-backend-api.md`
* `docs/referral-landing-page.md`

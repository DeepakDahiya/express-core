# Brave docs

This directory contains documentation for the Brave Browser. For additional resources outside this repository, go to:

 * [brave-browser/wiki](https://github.com/brave/brave-browser/wiki)

> [!IMPORTANT]
> If you add new documents, please also add a link to them in the Document
> Index below.

## Creating Documentation

### Guidelines

*   Markdown documents must follow the
    [Markdown Style
    Guide](https://chromium.googlesource.com/chromium/src/+/HEAD/styleguide/markdown/markdown.md).

### Previewing changes

#### Locally using [md_browser](https://chromium.googlesource.com/chromium/src/+/refs/heads/main/tools/md_browser/)

```bash
# in src/brave/
npm run docs
```

This is only an estimate. The **github** view may differ.

## Document Index

### Checking Out and Patching
*   [Upgrading Chromium](chromium_version_upgrade.md) Upgrading `brave-core` to
    a newer Chromium version.
*   [Patching with `plaster`](plaster.md) A patching engine for semantical
    patching of upstream Chromium.
*   [WebUI Overriding](webui_overriding.md)
*   [DevTools Frontend Patching](devtools_frontend_patching.md) Managing
    changes to the upstream project.

### General Development
*   [`gni` notes](gni_sources.md) - Brief notes on the use of `source.gni`
    files in our code base.
*   [Rust notes](rust.md) - General recommendations on integrating rust code
    into `brave-core`
*   [Adapting Chromium tests to the Brave Codebase](adapting_chromium_tests.md) -
    Suggestions on how to make chromium test work on our test targets.
*   [Production Crash Observability and E2E Reproduction Plan](production_crash_observability_and_e2e_reproduction_plan.md) -
    Plan for production crash logging, symbolication, triage, and real-device
    Appium reproduction.
*   [Browser Express Crash Backend API](browser_express_crash_backend_api.md) -
    Backend API contract for Browser Express crash ingestion, JSON crash
    events, symbol upload, storage, privacy controls, and release readiness.
*   [Browser Express India Phone Auth API](browser_express_india_phone_auth_api.md) -
    Backend API contract for India manual auth, phone signup, OTP verification,
    and server-side generate-username blocking.
*   [Production Crash Observability Implementation Log](production_crash_observability_implementation_log.md) -
    Implementation record for the crash reporting overrides and validation
    patch artifacts.
*   [Ship a File to All Clients](ship_a_file_to_all_clients.md) - How to ship a
    file to all clients via component updater.
*   [Git Configuration](git_configuration.md) - General recommendations for
    setting up and optimizing your Git environment.

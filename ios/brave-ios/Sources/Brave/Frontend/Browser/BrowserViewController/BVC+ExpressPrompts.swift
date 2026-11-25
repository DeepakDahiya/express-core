// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import Preferences
import Shared
import SwiftUI
import UIKit
import Web
import os.log

/// The small things Android does around the new tab page: going back to it after time away,
/// the default-browser and notification prompts after comment scrolls, and the update check.
extension BrowserViewController {
  /// Android's `Page Visited` (a new address, not private) and `YouTube Visited`. Only the
  /// domain, the site and search terms are sent, never the address.
  func expressCapturePageVisit(_ url: URL?) {
    guard let url, url.scheme == "http" || url.scheme == "https", !InternalURL.isValid(url: url)
    else { return }
    let query = profile.searchEngines.queryForSearchURL(url, forType: .standard)
    let props = ExpressAnalytics.pageProperties(url, searchQuery: query)
    if url.host?.lowercased().contains("youtube.com") == true {
      ExpressAnalytics.shared.capture("YouTube Visited", props)
    }
    guard url != Self.expressLastVisitedURL else { return }
    Self.expressLastVisitedURL = url
    ExpressAnalytics.shared.capture("Page Visited", props)
  }

  @MainActor static var expressLastVisitedURL: URL?
  @MainActor static var expressHomeVisited = false

  func installExpressPrompts() {
    // The YouTube coordinator is lazy, but its foreground check (the background-play rating,
    // Android's `onResume`) has to run from launch, not only once a YouTube page has loaded.
    _ = expressYouTube
    #if DEBUG
    // `-ExpressDebugPush '{"type":"comment_reply",…}'`: acts as if that notification was tapped,
    // 3 s after launch (the simulator doesn't deliver taps on banners).
    if let json = UserDefaults.standard.string(forKey: "ExpressDebugPush"),
      let data = json.data(using: .utf8),
      let userInfo = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
      let payload = ExpressPushPayload(userInfo: userInfo)
    {
      Task { @MainActor [weak self] in
        try? await Task.sleep(nanoseconds: 3_000_000_000)
        Logger.module.info("Express debug push: opening \(payload.type, privacy: .public)")
        ExpressAnalytics.shared.capture("Push Opened", ["type": payload.type])
        self?.openExpressNotification(payload)
      }
    }
    #endif
    // Android's `App Launched` (with the person properties) and `App Installed`.
    ExpressAnalytics.shared.start(personProperties: [
      "theme_setting": {
        switch DefaultTheme(rawValue: Preferences.General.themeNormalMode.value) {
        case .light: return "light"
        case .dark: return "dark"
        default: return "system"
        }
      }(),
      "is_default_browser": DefaultBrowserHelper.isBraveLikelyDefaultBrowser(),
    ])
    expressNewTabReturn.perform = { [weak self] in self?.returnToExpressNewTabPage() }
    expressNewTabReturn.start()
    let center = NotificationCenter.default
    // Android keeps the screen on while the app is in front (`FLAG_KEEP_SCREEN_ON`, added in
    // `onResume` and cleared in `onPause`), for reading and watching without it dimming.
    UIApplication.shared.isIdleTimerDisabled = UIApplication.shared.applicationState == .active
    expressPromptObservers.append(
      center.addObserver(
        forName: UIApplication.willResignActiveNotification,
        object: nil,
        queue: .main
      ) { _ in
        MainActor.assumeIsolated { UIApplication.shared.isIdleTimerDisabled = false }
      }
    )
    expressPromptObservers.append(
      center.addObserver(forName: .expressNewTabPageDidAppear, object: nil, queue: .main) {
        [weak self] _ in
        MainActor.assumeIsolated {
          // Android's `Home Visited`, about once per launch.
          if !Self.expressHomeVisited {
            Self.expressHomeVisited = true
            ExpressAnalytics.shared.capture("Home Visited")
          }
          self?.maybeShowExpressNewTabPrompts()
        }
      }
    )
    expressPromptObservers.append(
      center.addObserver(
        forName: UIApplication.didBecomeActiveNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated {
          UIApplication.shared.isIdleTimerDisabled = true
          ExpressReferralTracker.shared.checkClipboard()
          // Android's `Default Browser Selected`, once per install.
          if !UserDefaults.standard.bool(forKey: "express.analytics.default_browser_sent"),
            DefaultBrowserHelper.isBraveLikelyDefaultBrowser()
          {
            UserDefaults.standard.set(true, forKey: "express.analytics.default_browser_sent")
            ExpressAnalytics.shared.capture("Default Browser Selected")
          }
          self?.maybeShowExpressNewTabPrompts()
          self?.checkExpressAppStoreUpdate()
        }
      }
    )
  }

  private func isExpressNewTabPage(_ tab: some TabState) -> Bool {
    tab.visibleURL.flatMap { InternalURL($0) }?.isAboutHomeURL == true
  }

  /// The new tab page, or the tab that already shows it.
  func returnToExpressNewTabPage() {
    guard !privateBrowsingManager.isPrivateBrowsing,
      presentedViewController == nil,
      !topToolbar.inOverlayMode,
      !expressYouTube.isPictureInPictureActive
    else { return }
    if let selected = tabManager.selectedTab, isExpressNewTabPage(selected) { return }
    if let existing = tabManager.tabsForCurrentMode.first(where: { isExpressNewTabPage($0) }) {
      tabManager.selectTab(existing)
    } else {
      openBlankNewTab(attemptLocationFieldFocus: false, isPrivate: false)
    }
  }

  /// One prompt at a time, only on the new tab page with nothing over it (Android's
  /// `showNtpPromptsIfOnNtp`).
  func maybeShowExpressNewTabPrompts() {
    Task { @MainActor [weak self] in
      // Appearing, becoming active and a sheet closing tend to arrive together.
      try? await Task.sleep(for: .milliseconds(600))
      guard let self, self.view.window != nil, self.presentedViewController == nil,
        !self.privateBrowsingManager.isPrivateBrowsing, !self.topToolbar.inOverlayMode,
        let tab = self.tabManager.selectedTab, self.isExpressNewTabPage(tab)
      else { return }
      if ExpressDefaultBrowserPrompt.isDue {
        self.presentExpressDefaultBrowserPrompt()
      } else if ExpressCommentScrolls.count >= ExpressCommentScrolls.notificationPromptScrolls {
        // Asked once, whatever the server's push switch says (Android asks regardless).
        ExpressPushManager.shared.promptFromNewTabPage()
      }
    }
  }

  private func presentExpressDefaultBrowserPrompt() {
    ExpressDefaultBrowserPrompt.shownThisLaunch = true
    ExpressDefaultBrowserPrompt.noteShown()
    ExpressDefaultBrowserPrompt.isShowing = true
    final class Box { weak var controller: UIViewController? }
    let box = Box()
    let host = UIHostingController(
      rootView: ExpressDefaultBrowserSheet(onNext: {
        ExpressDefaultBrowserPrompt.noteNextTap()
        box.controller?.dismiss(animated: true) {
          if let url = URL(string: UIApplication.openSettingsURLString) {
            UIApplication.shared.open(url)
          }
        }
      })
      // "Next" or a swipe down: either way it's gone.
      .onDisappear { ExpressDefaultBrowserPrompt.isShowing = false }
    )
    box.controller = host
    host.sizingOptions = .preferredContentSize
    if let sheet = host.sheetPresentationController {
      sheet.detents = [
        .custom(identifier: .init("express.default.browser")) { [weak host] context in
          let fitted =
            host?.view.systemLayoutSizeFitting(
              CGSize(width: UIScreen.main.bounds.width, height: 0),
              withHorizontalFittingPriority: .required,
              verticalFittingPriority: .fittingSizeLevel
            ).height ?? 300
          return min(max(fitted, 260), context.maximumDetentValue)
        }
      ]
      sheet.prefersGrabberVisible = true
      sheet.preferredCornerRadius = 24
    }
    present(host, animated: true)
  }

  /// Once a day: a newer App Store release, offered with "Update" and "Not now".
  func checkExpressAppStoreUpdate() {
    Task { @MainActor [weak self] in
      guard let release = await ExpressAppStoreUpdate.checkIfDue(), let self,
        self.presentedViewController == nil
      else { return }
      let alert = UIAlertController(
        title: "Update available",
        message: "Express Browser \(release.version) is on the App Store.",
        preferredStyle: .alert
      )
      alert.addAction(
        UIAlertAction(title: "Not now", style: .cancel) { _ in ExpressAppStoreUpdate.skip(release) }
      )
      alert.addAction(
        UIAlertAction(title: "Update", style: .default) { _ in
          UIApplication.shared.open(release.storeURL)
        }
      )
      self.present(alert, animated: true)
    }
  }
}

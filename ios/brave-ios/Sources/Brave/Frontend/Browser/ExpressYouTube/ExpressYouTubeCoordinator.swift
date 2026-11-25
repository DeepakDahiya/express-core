// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVKit
import ExpressCore
import StoreKit
import SwiftUI
import UIKit
import Web
import os.log

/// Express Browser's YouTube behaviour in the app, after the Android app's toolbar,
/// `BraveActivity` PiP handling and `ReferralUsageTracker`:
/// - the access sheet on YouTube page loads (at most once per cooldown, every load once access
///   has run out) and from Profile;
/// - picture in picture: YouTube home comes up while the video floats, and the watch page comes
///   back when the video is expanded;
/// - background play noticed when the app goes to the background with a YouTube video playing;
/// - the one-time background-play tip and its "Congratulations". Like Android's lock-phone
///   tutorial, the tip waits until the watch page's video can go into picture in picture, comes
///   up 300 ms later, holds back the pill's preview cards while it's up, and closes when a page
///   starts loading, YouTube moves to another page or another tab is selected;
/// - after the third background play, Apple's review prompt where Android asks for a rating;
/// - after the second, the Golden YouTube shortcut sheet (`ExpressGoldenShortcut`), which adds a
///   Home Screen quick action where Android pins a launcher icon;
/// - the home tutorial's "YT Intro Tutorial Video Clicked";
/// - usage reported for referrals (also at launch and on going to the background), and referral
///   links that open the app.
@MainActor
final class ExpressYouTubeCoordinator {
  private weak var browser: BrowserViewController?

  private weak var pipOriginTab: (any TabState)?
  private var pipStartedAt: Date?

  /// A video is floating in picture in picture (coming back then returns to its tab).
  var isPictureInPictureActive: Bool { pipOriginTab != nil }
  private var lastSheetShownAt: Date?
  private var backgroundCheck: Task<Void, Never>?
  private var observers: [NSObjectProtocol] = []
  private weak var coachHost: UIViewController?

  private static let tipShownKey = "express.youtube.bg_tip_shown"
  private static let tipCompletedKey = "express.youtube.bg_tip_completed"
  private var tipAwaitingReturn = false

  /// The coach card on screen, if any.
  private var coachKind: ExpressYouTubeCoachCard.Kind?
  /// The tab and page the "Go to home" tip is on.
  private weak var tipTab: (any TabState)?
  private var tipPage: String?
  /// The 300 ms wait before the tip.
  private var tipTask: Task<Void, Never>?
  /// Tabs whose watch page can go into picture in picture (the script's `pipAvailable`), with
  /// the page.
  private var pipPages: [UUID: String] = [:]
  /// Tab switches and page loads starting, for the tip.
  private let tabWatcher = ExpressYouTubeTabWatcher()
  private weak var watchedTab: (any TabState)?

  /// Counts trips to the foreground. Each one counts at most one background play for the rating
  /// prompt (Android's `mBgPlayFiredThisForeground`, cleared in `onResume`).
  private var foregroundStint = 0
  private var countedStint = -1

  private static let introVideoClickedKey = "express.youtube.intro_tutorial_video_clicked"
  /// The path of the selected tab's last YouTube page, nil after another site.
  private var lastPathForIntro: String?

  /// Referral usage goes out once per launch, whatever the number of windows.
  private static var sentUsageAtLaunch = false

  init(browser: BrowserViewController) {
    self.browser = browser
    // Android's `ReferralUsageTracker.flushAsync(…, "startup")`.
    if !Self.sentUsageAtLaunch {
      Self.sentUsageAtLaunch = true
      sendPendingUsage()
    }
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) {
        [weak self] _ in
        MainActor.assumeIsolated { self?.appDidEnterBackground() }
      }
    )
    observers.append(
      center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) {
        [weak self] _ in
        MainActor.assumeIsolated { self?.appWillEnterForeground() }
      }
    )
    // The browser has just set the app's quick actions; the Golden YouTube one goes back, and
    // again each time the app stops being active, before the Home Screen can show the menu.
    ExpressGoldenShortcut.restoreQuickAction()
    observers.append(
      center.addObserver(forName: UIApplication.willResignActiveNotification, object: nil, queue: .main) { _ in
        MainActor.assumeIsolated { ExpressGoldenShortcut.restoreQuickAction() }
      }
    )
    // Android dismisses the tip from its tab-provider observer and `onPageLoadStarted`.
    tabWatcher.onSelect = { [weak self] selected, previous in
      MainActor.assumeIsolated { self?.selectedTabChanged(to: selected, from: previous) }
    }
    tabWatcher.onRemove = { [weak self] tab in
      MainActor.assumeIsolated { _ = self?.pipPages.removeValue(forKey: tab.id) }
    }
    tabWatcher.onStartNavigation = { [weak self] tab in
      MainActor.assumeIsolated { self?.navigationStarted(in: tab) }
    }
    browser.tabManager.addDelegate(tabWatcher)
    watch(browser.tabManager.selectedTab)
    #if DEBUG
    // `-ExpressResetBackgroundPlayTip YES`: the background-play tip shows again on the next
    // watch page.
    if UserDefaults.standard.bool(forKey: "ExpressResetBackgroundPlayTip") {
      UserDefaults.standard.removeObject(forKey: Self.tipShownKey)
      UserDefaults.standard.removeObject(forKey: Self.tipCompletedKey)
    }
    #endif
  }

  deinit {
    for observer in observers { NotificationCenter.default.removeObserver(observer) }
  }

  static func isYouTube(_ url: URL?) -> Bool {
    guard let host = url?.host?.lowercased() else { return false }
    return host == "youtube.com" || host.hasSuffix(".youtube.com")
  }

  // MARK: Messages from the page

  func handle(tab: any TabState, event: String, body: [String: Any]) {
    switch event {
    case "pageView":
      noteIntroTutorialPageView(in: tab, path: body["path"] as? String)
      closeTipIfPageChanged(in: tab, url: body["url"] as? String)
      guard !tab.isPrivate else { return }
      ExpressYouTubeUsage.shared.notePageView()
    case "pipAvailable":
      pipAvailabilityChanged(
        in: tab,
        available: body["available"] as? Bool ?? false,
        url: body["url"] as? String
      )
    case "pipEnter":
      pictureInPictureStarted(in: tab)
    case "pipLeave":
      pictureInPictureEnded(in: tab, stillPlaying: body["playing"] as? Bool ?? false)
    default:
      break
    }
  }

  // MARK: Page loads: the access sheet

  /// A full page load finished (YouTube's in-page navigation doesn't count, as on Android).
  func pageDidLoad(in tab: any TabState) {
    // Another site in between: the next watch page wasn't opened from YouTube home.
    if tab === browser?.tabManager.selectedTab, !Self.isYouTube(tab.visibleURL) {
      lastPathForIntro = nil
    }
    guard let browser, tab === browser.tabManager.selectedTab, !tab.isPrivate,
      Self.isYouTube(tab.visibleURL)
    else { return }
    // Android's `showYouTubePromptsIfEligible`: the rating and the shortcut prompt are offered
    // when the sheet isn't shown.
    if !showAccessSheetIfDue() {
      showBackgroundPlayPromptsIfDue()
    }
  }

  /// The access sheet on a YouTube page load; false when it isn't due.
  private func showAccessSheetIfDue() -> Bool {
    // Signed out, the sheet never shows (the account is what has access). Unlike Android, the
    // server's switch is respected.
    guard ExpressAccount.shared.validAccessToken != nil,
      ExpressRemoteConfigStore.current.enableYouTubePremium
    else { return false }
    let blocked = ExpressYouTubeAccessModel.cachedIsBlocked
    if !blocked, let last = lastSheetShownAt {
      let cooldown = TimeInterval(ExpressRemoteConfigStore.current.youtubePremiumCooldownMs) / 1000
      if Date().timeIntervalSince(last) < cooldown { return false }
    }
    return presentAccessSheet(source: .youtubePage)
  }

  /// False when something else is already on screen.
  @discardableResult
  func presentAccessSheet(source: ExpressYouTubeAccessModel.Source, from presenter: UIViewController? = nil) -> Bool {
    guard let browser else { return false }
    let presenter = presenter ?? browser
    guard presenter.presentedViewController == nil else { return false }
    lastSheetShownAt = Date()
    let model = ExpressYouTubeAccessModel(source: source)
    final class Box { weak var controller: UIViewController? }
    let box = Box()
    let host = UIHostingController(
      rootView: ExpressYouTubeAccessSheet(
        model: model,
        onShare: { text in
          guard let controller = box.controller else { return }
          let activity = UIActivityViewController(activityItems: [text], applicationActivities: nil)
          activity.popoverPresentationController?.sourceView = controller.view
          controller.present(activity, animated: true)
        },
        onClose: { box.controller?.dismiss(animated: true) },
        onLeaveYouTube: { [weak browser] in
          box.controller?.dismiss(animated: true) {
            browser?.openBlankNewTab(attemptLocationFieldFocus: false, isPrivate: false)
          }
        }
      )
    )
    box.controller = host
    // Once loaded and blocked on a YouTube page it can't be swiped away; from Profile it closes
    // with its button only, as on Android.
    host.isModalInPresentation = source == .profile || ExpressYouTubeAccessModel.cachedIsBlocked
    // The sheet takes the height of what it shows.
    host.sizingOptions = .preferredContentSize
    host.view.backgroundColor = ExpressYouTubeColors.sheet
    if let sheet = host.sheetPresentationController {
      sheet.detents = [.custom(identifier: .init("express.youtube.access")) { [weak host] context in
        let fitted = host?.view.systemLayoutSizeFitting(
          CGSize(width: context.containerTraitCollection.horizontalSizeClass == .regular ? 500 : UIScreen.main.bounds.width, height: 0),
          withHorizontalFittingPriority: .required,
          verticalFittingPriority: .fittingSizeLevel
        ).height ?? 420
        return min(max(fitted, 300), context.maximumDetentValue)
      }]
      sheet.preferredCornerRadius = 28
      sheet.prefersGrabberVisible = false
    }
    presenter.present(host, animated: true)
    return true
  }

  // MARK: Picture in picture

  /// The address bar's gold button (Android's `kYoutubePipTrigger`): lets the page's video float.
  /// Scripts the app runs count as a tap, which picture in picture needs.
  /// `completion` hears whether the page asked its video to float.
  func requestPictureInPicture(fromButton: Bool = true, completion: ((Bool) -> Void)? = nil) {
    guard let tab = browser?.tabManager.selectedTab else {
      completion?(false)
      return
    }
    if fromButton { ExpressAnalytics.shared.capture("YT Feature Explored (PIP)") }
    let script = """
      const v = document.querySelector('video');
      if (!v) return 'no-video';
      v.removeAttribute('disablepictureinpicture');
      v.disablePictureInPicture = false;
      if (v.paused) { try { await v.play(); } catch (e) {} }
      const webkitPiP = () => {
        if (v.webkitSupportsPresentationMode && v.webkitSupportsPresentationMode('picture-in-picture')) {
          v.webkitSetPresentationMode('picture-in-picture');
          return true;
        }
        return false;
      };
      if (document.pictureInPictureEnabled && v.requestPictureInPicture) {
        try { await v.requestPictureInPicture(); return 'standard'; }
        catch (e) { return webkitPiP() ? 'webkit' : ('failed: ' + e.name); }
      }
      return webkitPiP() ? 'webkit' : 'unsupported';
      """
    Task { @MainActor in
      let result = try? await tab.callAsyncJavaScript(script, arguments: [:], in: nil, contentWorld: .page)
      Logger.module.info("Express PiP request: \(String(describing: result), privacy: .public)")
      let outcome = result as? String
      completion?(outcome == "standard" || outcome == "webkit")
    }
  }

  /// Back floats this tab's video: a YouTube watch page whose video can go into picture in
  /// picture. Back stays usable there even as the tab's first page, as on Android, which
  /// doesn't gate its Back on history.
  func backFloatsVideo(in tab: any TabState) -> Bool {
    Self.isYouTube(tab.visibleURL) && tab.visibleURL?.path == "/watch" && pipPages[tab.id] != nil
      && AVPictureInPictureController.isPictureInPictureSupported()
  }

  /// Android's Back on a YouTube watch page (`BraveActivity.handleYouTubeBackPress`): instead of
  /// going back, the video floats and the YouTube home tab comes up (`pictureInPictureStarted`),
  /// with the watch tab kept alive behind it. False, having done nothing, when the page has no
  /// video that can float, so the caller goes back as usual; `fallback` runs if the page then
  /// refuses picture in picture, so Back always does something.
  func handleBackOnWatchPage(fallback: @escaping () -> Void) -> Bool {
    guard let tab = browser?.tabManager.selectedTab, backFloatsVideo(in: tab) else { return false }
    Logger.module.info("Express YouTube: Back on a watch page floats the video")
    requestPictureInPicture(fromButton: false) { started in
      if !started { fallback() }
    }
    return true
  }

  /// Android shows the YouTube home feed while the video floats, keeping the watch tab alive.
  private func pictureInPictureStarted(in tab: any TabState) {
    if !tab.isPrivate {
      ExpressYouTubeUsage.shared.record(.pipUses)
      // Android counts picture in picture as a background play too.
      countBackgroundPlay(stint: foregroundStint)
    }
    ExpressAnalytics.shared.capture("YT Feature Explored (PIP + Background Play)")
    pipOriginTab = tab
    pipStartedAt = Date()
    guard let browser, tab === browser.tabManager.selectedTab else { return }
    let home = URL(string: "https://m.youtube.com/")!
    if let existing = browser.tabManager.allTabs.first(where: {
      $0 !== tab && $0.isPrivate == tab.isPrivate && $0.visibleURL?.host == "m.youtube.com"
        && ($0.visibleURL?.path ?? "/") == "/"
    }) {
      browser.tabManager.selectTab(existing)
    } else {
      browser.tabManager.addTabAndSelect(URLRequest(url: home), afterTab: tab, isPrivate: tab.isPrivate)
    }
  }

  /// Expanded (still playing): back to the watch page, unless the reader has since opened
  /// another video. Closed (paused): stay where they are.
  private func pictureInPictureEnded(in tab: any TabState, stillPlaying: Bool) {
    defer {
      pipOriginTab = nil
      pipStartedAt = nil
    }
    guard stillPlaying, let browser, let origin = pipOriginTab, origin === tab else { return }
    if let selected = browser.tabManager.selectedTab, selected !== origin,
      selected.visibleURL?.path == "/watch", Self.isYouTube(selected.visibleURL)
    {
      return
    }
    browser.tabManager.selectTab(origin)
  }

  // MARK: Background play

  /// If a YouTube video is still playing a moment after the app goes to the background, that's
  /// background play: counted for referrals, and it completes the tip.
  private func appDidEnterBackground() {
    // Android's `ReferralUsageTracker.flushAsync(…, "pause")`: what's pending goes now.
    sendPendingUsage()
    backgroundCheck?.cancel()
    if tipAwaitingReturn { ExpressAnalytics.shared.capture("YT Background Play Tutorial Backgrounded") }
    guard let tab = browser?.tabManager.selectedTab, !tab.isPrivate, Self.isYouTube(tab.visibleURL)
    else { return }
    let stint = foregroundStint
    backgroundCheck = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 1_500_000_000)
      guard !Task.isCancelled,
        let playing = try? await tab.callAsyncJavaScript(
          "const v = document.querySelector('video'); return !!(v && !v.paused && !v.ended && v.currentTime > 0);",
          arguments: [:],
          in: nil,
          contentWorld: .page
        ) as? Bool,
        playing
      else { return }
      ExpressYouTubeUsage.shared.record(.bgPlayUses)
      ExpressAnalytics.shared.capture("YT Feature Explored (Background Play)")
      self?.countBackgroundPlay(stint: stint)
      if self?.tipAwaitingReturn == true {
        UserDefaults.standard.set(true, forKey: Self.tipCompletedKey)
      }
    }
  }

  /// The page can't always be asked while the app is away, so finish the check first.
  private func appWillEnterForeground() {
    foregroundStint += 1
    let check = backgroundCheck
    Task { [weak self] in
      await check?.value
      guard let self else { return }
      if self.tipAwaitingReturn, UserDefaults.standard.bool(forKey: Self.tipCompletedKey) {
        self.tipAwaitingReturn = false
        ExpressAnalytics.shared.capture("YT Background Play Tutorial Congrats Shown")
        self.showCoachCard(.congratulations)
        return
      }
      // Android checks for the rating and the shortcut prompt each time the app comes back
      // (`onResume`). Wait for the app to be active and for the new tab page's own prompts to go
      // first.
      try? await Task.sleep(for: .seconds(1))
      self.showBackgroundPlayPromptsIfDue()
    }
  }

  /// Referral usage still pending, in a background task so the send can finish once the app has
  /// left the screen. `flush()` does nothing when nothing is pending, signed out or in a dry run.
  private func sendPendingUsage() {
    let backgroundTask = ExpressBackgroundTask(name: "Express referral usage")
    Task {
      await ExpressYouTubeUsage.shared.flush()
      backgroundTask.end()
    }
  }

  /// One background play towards the rating, at most one per trip to the foreground (Android's
  /// `recordPipBackgroundPlayUsed`).
  private func countBackgroundPlay(stint: Int) {
    guard countedStint != stint else { return }
    countedStint = stint
    ExpressBackgroundPlayRating.recordUse()
  }

  /// Android's `showBackgroundPlaybackPromptsIfEligible`: the rating first, and the Golden
  /// YouTube shortcut prompt only when the rating wasn't asked for, so the two never collide.
  private func showBackgroundPlayPromptsIfDue() {
    // A forced shortcut prompt (Debug) goes ahead of the rating, so it's sure to show.
    if !ExpressGoldenShortcut.isForced, requestBackgroundPlayRatingIfDue() { return }
    guard let browser else { return }
    ExpressGoldenShortcut.presentPromptIfDue(from: browser)
  }

  /// Apple's review prompt where Android shows its rating dialog. iOS decides whether it really
  /// shows (and limits how often), so asking counts as handled. False when it isn't due.
  private func requestBackgroundPlayRatingIfDue() -> Bool {
    guard let browser, UIApplication.shared.applicationState == .active,
      browser.presentedViewController == nil,
      let scene = browser.view.window?.windowScene, scene.activationState == .foregroundActive,
      ExpressBackgroundPlayRating.isDue
    else { return false }
    ExpressBackgroundPlayRating.recordRequested()
    Logger.module.info("Express background play rating: asking for an App Store review")
    AppStore.requestReview(in: scene)
    return true
  }

  // MARK: Background-play tip

  /// The page's video can, or can no longer, go into picture in picture. Android only offers its
  /// tutorial once it can (`updateYouTubePipButtonVisibility`).
  private func pipAvailabilityChanged(in tab: any TabState, available: Bool, url: String?) {
    // Back's enabled state follows it (Back floats the video even with no history).
    defer {
      if let browser, tab === browser.tabManager.selectedTab {
        browser.updateBackForwardActionStatus(for: tab)
      }
    }
    guard available else {
      if pipPages[tab.id] != nil { Logger.module.info("Express YouTube: PiP no longer available") }
      pipPages[tab.id] = nil
      return
    }
    if pipPages[tab.id] == nil { Logger.module.info("Express YouTube: PiP available on this page") }
    pipPages[tab.id] = Self.pageKey(url) ?? ""
    if tab === browser?.tabManager.selectedTab {
      scheduleBackgroundPlayTip(for: tab)
    }
  }

  /// Android's `maybeShowLockPhoneTutorialDialog`: the tip comes up 300 ms after the watch page
  /// can go into picture in picture, if it still can and is still in front. The pill's preview
  /// cards are held back from now on, as Android's per-page flag does.
  private func scheduleBackgroundPlayTip(for tab: any TabState) {
    guard tipTask == nil, coachKind == nil, !tab.isPrivate,
      !UserDefaults.standard.bool(forKey: Self.tipShownKey)
    else { return }
    browser?.expressCommentsPillModel.holdsPreviews = true
    tipTask = Task { [weak self, weak tab] in
      try? await Task.sleep(nanoseconds: 300_000_000)
      guard let self, !Task.isCancelled else { return }
      self.tipTask = nil
      guard let tab, tab === self.browser?.tabManager.selectedTab,
        let page = self.pipPages[tab.id],
        Self.isYouTube(tab.visibleURL), tab.visibleURL?.path == "/watch",
        AVPictureInPictureController.isPictureInPictureSupported(),
        UIApplication.shared.applicationState == .active
      else {
        Logger.module.info(
          "Express background-play tip skipped (PiP supported: \(AVPictureInPictureController.isPictureInPictureSupported(), privacy: .public))"
        )
        self.releasePreviewHold()
        return
      }
      self.showBackgroundPlayTipIfNeeded(in: tab, page: page)
    }
  }

  /// Once per install, on the first watch page that can play on its own: Android counts down and
  /// sends the app home itself; iPhone apps can't, so this asks the reader to swipe up instead.
  private func showBackgroundPlayTipIfNeeded(in tab: any TabState, page: String) {
    let defaults = UserDefaults.standard
    guard !defaults.bool(forKey: Self.tipShownKey) else {
      releasePreviewHold()
      return
    }
    defaults.set(true, forKey: Self.tipShownKey)
    tipAwaitingReturn = true
    ExpressAnalytics.shared.capture("YT Background Play Tutorial Shown", ["shown_count": 1])
    showCoachCard(.goHome)
    tipTab = tab
    tipPage = page
  }

  /// Android's `dismissLockPhoneTutorialDialog`, for a page load starting or a tab switch. The
  /// "Congratulations" card stays (Android's is a separate dialog). Closing doesn't end the
  /// tutorial: going home with the video playing still earns the "Congratulations", as after
  /// Close.
  private func closeBackgroundPlayTip() {
    tipTask?.cancel()
    tipTask = nil
    if coachKind == .goHome {
      Logger.module.info("Express background-play tip closed by a navigation or tab switch")
      removeCoachCard()
    } else {
      releasePreviewHold()
    }
  }

  /// YouTube moved to another page in the tip's tab without a load (another video, home): iOS's
  /// tip stays until closed, so it goes here too. The same video again (YouTube re-sends page
  /// views and rewrites other parameters) keeps it.
  private func closeTipIfPageChanged(in tab: any TabState, url: String?) {
    guard coachKind == .goHome, let tipTab, tab === tipTab,
      let page = Self.pageKey(url), page != tipPage
    else { return }
    closeBackgroundPlayTip()
  }

  /// A page started loading (not YouTube's in-page moves): Android's `onPageLoadStarted`.
  private func navigationStarted(in tab: any TabState) {
    pipPages[tab.id] = nil
    guard tab === browser?.tabManager.selectedTab else { return }
    closeBackgroundPlayTip()
  }

  /// Another tab came to the front: the tip goes, and the new tab's watch page may get it (Android
  /// re-checks the new tab's picture-in-picture state on a switch).
  private func selectedTabChanged(to selected: (any TabState)?, from previous: (any TabState)?) {
    guard selected !== previous else { return }
    watch(selected)
    closeBackgroundPlayTip()
    if let selected, pipPages[selected.id] != nil {
      scheduleBackgroundPlayTip(for: selected)
    }
  }

  /// Follows page loads in the selected tab only.
  private func watch(_ tab: (any TabState)?) {
    if let watchedTab, watchedTab !== tab {
      watchedTab.removeObserver(tabWatcher)
    }
    watchedTab = tab
    tab?.addObserver(tabWatcher)
  }

  /// Preview cards may show again once neither the tip nor its wait is up.
  private func releasePreviewHold() {
    guard coachKind != .goHome, tipTask == nil else { return }
    browser?.expressCommentsPillModel.holdsPreviews = false
  }

  /// A YouTube page for the tip: host, path and the video (`v`), so YouTube rewriting other
  /// parameters (start time, tracking) isn't another page.
  private static func pageKey(_ urlString: String?) -> String? {
    guard let urlString, let components = URLComponents(string: urlString) else { return nil }
    let video = components.queryItems?.first(where: { $0.name == "v" })?.value
    return (components.host ?? "") + components.path + (video.map { "?v=\($0)" } ?? "")
  }

  // MARK: Intro tutorial

  /// Android's `maybeFireYtIntroTutorialVideoClicked`: once per install, the first move in the
  /// selected tab from YouTube home (path "" or "/") straight to a watch page ("/watch…") is the
  /// tap on the video the home tutorial highlights. YouTube's in-page navigation counts, and
  /// private tabs too, as on Android; the event has no properties of its own.
  private func noteIntroTutorialPageView(in tab: any TabState, path: String?) {
    guard let path, tab === browser?.tabManager.selectedTab else { return }
    let previous = lastPathForIntro
    lastPathForIntro = path
    let defaults = UserDefaults.standard
    guard !defaults.bool(forKey: Self.introVideoClickedKey),
      previous == "" || previous == "/",
      path.hasPrefix("/watch")
    else { return }
    defaults.set(true, forKey: Self.introVideoClickedKey)
    ExpressAnalytics.shared.capture("YT Intro Tutorial Video Clicked")
  }

  private func showCoachCard(_ kind: ExpressYouTubeCoachCard.Kind) {
    guard let browser else { return }
    removeCoachCard()
    coachKind = kind
    if kind == .goHome {
      browser.expressCommentsPillModel.holdsPreviews = true
    }
    let host = UIHostingController(rootView: ExpressYouTubeCoachCard(kind: kind) { [weak self] in
      if kind == .congratulations {
        ExpressAnalytics.shared.capture("YT Background Play Tutorial Completed")
      }
      self?.removeCoachCard()
    })
    host.view.backgroundColor = .clear
    browser.addChild(host)
    browser.view.addSubview(host.view)
    host.view.translatesAutoresizingMaskIntoConstraints = false
    // A fixed width and the card's own height, so the whole card takes taps.
    let width = browser.view.safeAreaLayoutGuide.layoutFrame.width - 32
    let height = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude)).height
    NSLayoutConstraint.activate([
      host.view.leadingAnchor.constraint(equalTo: browser.view.safeAreaLayoutGuide.leadingAnchor, constant: 16),
      host.view.trailingAnchor.constraint(equalTo: browser.view.safeAreaLayoutGuide.trailingAnchor, constant: -16),
      host.view.topAnchor.constraint(equalTo: browser.view.safeAreaLayoutGuide.topAnchor, constant: 64),
      host.view.heightAnchor.constraint(equalToConstant: ceil(height)),
    ])
    host.didMove(toParent: browser)
    coachHost = host
  }

  private func removeCoachCard() {
    coachHost?.view.removeFromSuperview()
    coachHost?.removeFromParent()
    coachHost = nil
    coachKind = nil
    tipTab = nil
    tipPage = nil
    releasePreviewHold()
  }

  // MARK: Referral links

  /// A `browser.express/refer?code=…` link opened the app: attribute the install.
  func handleIncoming(_ url: URL) {
    if let code = ExpressReferralLink.code(in: url) {
      ExpressAnalytics.shared.capture("referral_app_deep_link_received", ["code": code])
    }
    ExpressReferralTracker.shared.handleIncoming(url)
  }
}

/// Tab switches (`TabManagerDelegate`) and page loads starting in the watched tab (`TabObserver`),
/// passed on to the coordinator. The tab manager and tabs hold it weakly; the coordinator keeps it.
private final class ExpressYouTubeTabWatcher: NSObject, TabManagerDelegate, TabObserver {
  var onSelect: ((_ selected: (any TabState)?, _ previous: (any TabState)?) -> Void)?
  var onRemove: ((any TabState) -> Void)?
  var onStartNavigation: ((any TabState) -> Void)?

  func tabManager(
    _ tabManager: TabManager,
    didSelectedTabChange selected: (any TabState)?,
    previous: (any TabState)?
  ) {
    onSelect?(selected, previous)
  }

  func tabManager(_ tabManager: TabManager, didAddTab tab: some TabState) {}

  func tabManager(_ tabManager: TabManager, didRemoveTab tab: some TabState) {
    onRemove?(tab)
  }

  func tabManagerDidRestoreTabs(_ tabManager: TabManager) {}

  func tabDidStartNavigation(_ tab: some TabState) {
    onStartNavigation?(tab)
  }
}

/// Time to finish one piece of work after the app leaves the screen (Android sends on `onPause`
/// while its process is still warm). Ended when the work is done, or when iOS runs out of time.
@MainActor
private final class ExpressBackgroundTask {
  private var identifier = UIBackgroundTaskIdentifier.invalid

  init(name: String) {
    identifier = UIApplication.shared.beginBackgroundTask(withName: name) { [weak self] in
      self?.end()
    }
  }

  func end() {
    guard identifier != .invalid else { return }
    UIApplication.shared.endBackgroundTask(identifier)
    identifier = .invalid
  }
}

// MARK: - Rating after background play

/// Android's rating prompt for background-play users (`GoldenYouTubeBackgroundPlaybackRating
/// DialogFragment`, with the counters and rules in `GoldenYouTubeHomeScreenShortcutUtil`). It's
/// due after the third background play while the server's `enableYouTubePremium` is on and the
/// default-browser sheet is neither showing nor due, at most once in 24 hours, until handled.
/// Android shows its own five stars and sends only a 5 to the store; App Store guideline 5.6.1
/// doesn't allow that filter, so iOS calls Apple's review prompt directly. Android sends no
/// analytics for this prompt, so neither does iOS.
@MainActor
enum ExpressBackgroundPlayRating {
  static let requiredUses = 3
  static let cooldown: TimeInterval = 24 * 60 * 60
  private static let useCountKey = "express.youtube.bg_play_use_count"
  private static let handledKey = "express.youtube.bg_play_rating_handled"
  private static let lastShownKey = "express.youtube.bg_play_rating_last_shown"

  /// Background plays so far, picture in picture included.
  static var useCount: Int { UserDefaults.standard.integer(forKey: useCountKey) }

  static func recordUse() {
    let count = useCount + 1
    UserDefaults.standard.set(count, forKey: useCountKey)
    Logger.module.info("Express background play: recorded use \(count, privacy: .public)")
  }

  static var isDue: Bool {
    if let reason = blockReason() {
      // Android logs the same reasons (`blockPrompt`).
      Logger.module.debug("Express rating prompt blocked: \(reason, privacy: .public)")
      return false
    }
    return true
  }

  static func recordRequested() {
    let defaults = UserDefaults.standard
    defaults.set(true, forKey: handledKey)
    defaults.set(Date().timeIntervalSince1970, forKey: lastShownKey)
  }

  private static func blockReason() -> String? {
    let defaults = UserDefaults.standard
    #if DEBUG
    // `-ExpressForceBackgroundPlayRating YES`: due on every check, whatever the server switch
    // (live has `enableYouTubePremium` off), the count, the 24 h wait or an earlier request. Only
    // a default-browser sheet on screen still holds it back.
    if defaults.bool(forKey: "ExpressForceBackgroundPlayRating") {
      return ExpressDefaultBrowserPrompt.isShowing ? "bottom_sheet_visible" : nil
    }
    #endif
    if !ExpressRemoteConfigStore.current.enableYouTubePremium { return "youtube_premium_disabled" }
    if ExpressDefaultBrowserPrompt.isShowing { return "bottom_sheet_visible" }
    if ExpressDefaultBrowserPrompt.isDue { return "default_browser_dialog_eligible" }
    if defaults.bool(forKey: handledKey) { return "handled" }
    if useCount < requiredUses { return "use_count_\(useCount)" }
    let lastShown = defaults.double(forKey: lastShownKey)
    if Date().timeIntervalSince1970 - lastShown <= cooldown { return "cooldown" }
    return nil
  }
}

// MARK: - Coach cards

/// Android's background-play tutorial cards (navy, gold accents), as a banner over the page.
struct ExpressYouTubeCoachCard: View {
  enum Kind {
    case goHome
    case congratulations
  }

  let kind: Kind
  var onClose: () -> Void

  var body: some View {
    HStack(alignment: .top, spacing: 12) {
      Circle()
        .fill(Color(red: 0xD4 / 255, green: 0xAF / 255, blue: 0x37 / 255))
        .frame(width: 10, height: 10)
        .padding(.top, 6)
      VStack(alignment: .leading, spacing: 4) {
        Text(kind == .goHome ? "Go to home" : "Congratulations")
          .font(.system(size: 17, weight: .bold))
          .foregroundColor(Color(red: 0xD4 / 255, green: 0xAF / 255, blue: 0x37 / 255))
        Text(
          kind == .goHome
            ? "Swipe up to go home — the video keeps playing in the background."
            : "You have unlocked premium background play"
        )
        .font(.system(size: 14))
        .foregroundColor(.white.opacity(0.9))
        .fixedSize(horizontal: false, vertical: true)
      }
      Spacer(minLength: 0)
      Button(action: onClose) {
        Text("Close")
          .font(.system(size: 14, weight: .semibold))
          .foregroundColor(.white)
          .padding(.horizontal, 12)
          .frame(height: 32)
          .background(Capsule().fill(.white.opacity(0.15)))
      }
      .buttonStyle(.plain)
    }
    .padding(16)
    .background(
      RoundedRectangle(cornerRadius: 16)
        .fill(Color(red: 0x1A / 255, green: 0x1A / 255, blue: 0x2E / 255))
        .shadow(color: .black.opacity(0.3), radius: 10, y: 4)
    )
    .accessibilityElement(children: .combine)
  }
}

// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveShared
import ExpressCore
import Preferences
import SwiftUI
import UIKit
import os.log

// The Golden YouTube shortcut, ported from the Android app's `GoldenYouTubeHomeScreenShortcutUtil`
// and `GoldenYouTubeHomeScreenShortcutDialogFragment`. Android pins a launcher icon
// (`browser_express_golden_youtube`) that opens YouTube in Express. iPhone apps can't add icons
// to the Home Screen, so the sheet adds a Home Screen quick action instead: "YouTube" in the menu
// that opens when the app icon is touched and held.

// MARK: - Rules and the quick action

/// Android's rules (`shouldShowPrompt`): due after the second background play (the count the
/// rating uses, picture in picture included) while the server's `enableYouTubePremium` is on and
/// the default-browser sheet is neither showing nor due, at most three times and more than 24
/// hours apart, until the shortcut is there. When the rating is due too, the rating goes first
/// (`showBackgroundPlaybackPromptsIfEligible`).
///
/// Android sends no analytics for this sheet or for its shortcut, so neither does iOS. Android
/// never removes a pinned shortcut either (not on sign-out, not when the server switch goes off),
/// so the quick action stays once added.
@MainActor
enum ExpressGoldenShortcut {
  static let requiredUses = 2
  static let maxPrompts = 3
  static let cooldown: TimeInterval = 24 * 60 * 60
  private static let addedKey = "express.youtube.golden_shortcut_added"
  private static let shownCountKey = "express.youtube.golden_shortcut_prompt_shown_count"
  private static let lastShownKey = "express.youtube.golden_shortcut_prompt_last_shown"

  /// `<bundle id>.golden-youtube`, handled by `QuickActions`.
  static var type: String { ShortcutType.goldenYouTube.type }

  /// The quick action was added (Android's `isShortcutPinned`).
  static var isAdded: Bool { UserDefaults.standard.bool(forKey: addedKey) }

  /// `-ExpressForceGoldenShortcutPrompt YES` (Debug builds only): the sheet on every foreground and
  /// YouTube page load, whatever the server switch, the count, the limit of three, the 24 h wait
  /// or an earlier add. Only a default-browser sheet on screen still holds it back. It also goes
  /// ahead of the rating, so it's sure to show.
  static var isForced: Bool {
    #if DEBUG
    return UserDefaults.standard.bool(forKey: "ExpressForceGoldenShortcutPrompt")
    #else
    return false
    #endif
  }

  static var isPromptDue: Bool {
    if let reason = blockReason() {
      // Android logs the same reasons (`blockPrompt`).
      Logger.module.debug("Express golden shortcut prompt blocked: \(reason, privacy: .public)")
      return false
    }
    return true
  }

  private static func blockReason() -> String? {
    if isForced {
      return ExpressDefaultBrowserPrompt.isShowing ? "bottom_sheet_visible" : nil
    }
    let defaults = UserDefaults.standard
    if !ExpressRemoteConfigStore.current.enableYouTubePremium { return "youtube_premium_disabled" }
    if ExpressDefaultBrowserPrompt.isShowing { return "bottom_sheet_visible" }
    if ExpressDefaultBrowserPrompt.isDue { return "default_browser_dialog_eligible" }
    // Android's `isAddToHomeIntentSupported`: there's no Home Screen menu on a Mac or Vision Pro.
    if ProcessInfo.processInfo.isiOSAppOnMac || ProcessInfo.processInfo.isiOSAppOnVisionOS {
      return "add_to_home_not_supported"
    }
    if isAdded { return "shortcut_pinned" }
    let uses = ExpressBackgroundPlayRating.useCount
    if uses < requiredUses { return "use_count_\(uses)" }
    if defaults.integer(forKey: shownCountKey) >= maxPrompts { return "max_prompt_count" }
    let lastShown = defaults.double(forKey: lastShownKey)
    if Date().timeIntervalSince1970 - lastShown <= cooldown { return "cooldown" }
    return nil
  }

  /// Android's `recordPromptShown`.
  static func recordPromptShown() {
    let defaults = UserDefaults.standard
    defaults.set(Date().timeIntervalSince1970, forKey: lastShownKey)
    defaults.set(defaults.integer(forKey: shownCountKey) + 1, forKey: shownCountKey)
  }

  /// Android's `requestPinShortcut`: adds the quick action.
  static func add() {
    UserDefaults.standard.set(true, forKey: addedKey)
    restoreQuickAction()
    Logger.module.info("Express golden shortcut: quick action added")
  }

  /// The browser sets the app's quick actions itself at launch (and when private-only browsing is
  /// switched), which drops this one. It goes back then, and whenever the app stops being active,
  /// before the Home Screen can show the menu.
  static func restoreQuickAction() {
    guard isAdded else { return }
    var items = UIApplication.shared.shortcutItems ?? []
    guard !items.contains(where: { $0.type == type }) else { return }
    // First, so it sits next to the icon.
    items.insert(
      UIApplicationShortcutItem(
        type: type,
        localizedTitle: "YouTube",
        localizedSubtitle: nil,
        icon: UIApplicationShortcutIcon(systemImageName: "play.rectangle.fill"),
        userInfo: nil
      ),
      at: 0
    )
    UIApplication.shared.shortcutItems = items
  }

  /// The quick action: the new tab page's golden YouTube, through Google sign-in as Android's
  /// shortcut is (`DEFAULT_YOUTUBE_URL`), in a new tab. Private only when the browser is set to
  /// private browsing only.
  static func open(in browser: BrowserViewController) {
    // Coming back after time away would otherwise land on the new tab page.
    browser.expressNewTabReturn.suppress()
    browser.popToBVC(isAnimated: false)
    browser.openURLInNewTab(
      ExpressNTPSites.youTube.url,
      isPrivate: Preferences.Privacy.privateBrowsingOnly.value,
      isPrivileged: false
    )
  }

  /// Android's `showIfNeeded`: the sheet when it's due and nothing else is on screen.
  @discardableResult
  static func presentPromptIfDue(from browser: BrowserViewController) -> Bool {
    guard UIApplication.shared.applicationState == .active, browser.view.window != nil,
      browser.presentedViewController == nil, !browser.topToolbar.inOverlayMode,
      isPromptDue
    else { return false }
    recordPromptShown()
    Logger.module.info("Express golden shortcut: showing the prompt")
    final class Box { weak var controller: UIViewController? }
    let box = Box()
    let host = UIHostingController(
      rootView: ExpressGoldenShortcutSheet(
        onAdd: { Self.add() },
        onResize: {
          // The confirmation is taller than the buttons it replaces. After this update, so the
          // sheet measures the new content.
          Task { @MainActor in
            guard let sheet = box.controller?.sheetPresentationController else { return }
            sheet.animateChanges { sheet.invalidateDetents() }
          }
        },
        onClose: { box.controller?.dismiss(animated: true) }
      )
    )
    box.controller = host
    // The sheet takes the height of what it shows, and can be swiped away, as on Android.
    host.sizingOptions = .preferredContentSize
    host.view.backgroundColor = ExpressYouTubeColors.goldenSheet
    if let sheet = host.sheetPresentationController {
      sheet.detents = [
        .custom(identifier: .init("express.youtube.golden_shortcut")) { [weak host] context in
          let fitted =
            host?.view.systemLayoutSizeFitting(
              CGSize(
                width: context.containerTraitCollection.horizontalSizeClass == .regular
                  ? 500 : UIScreen.main.bounds.width,
                height: 0
              ),
              withHorizontalFittingPriority: .required,
              verticalFittingPriority: .fittingSizeLevel
            ).height ?? 460
          return min(max(fitted, 300), context.maximumDetentValue)
        }
      ]
      sheet.preferredCornerRadius = 30
      sheet.prefersGrabberVisible = false
    }
    browser.present(host, animated: true)
    return true
  }
}

// MARK: - Sheet

/// Android's `golden_youtube_shortcut_*` colours (light, then `values-night`).
extension ExpressYouTubeColors {
  static let goldenSheet = UIColor(expressLight: 0xFFFFFF, expressDark: 0x101113)
  static let goldenHandle = UIColor(expressLight: 0xB9B9B9, expressDark: 0xB9B9B9)
  static let goldenText = UIColor(expressLight: 0x202124, expressDark: 0xFFFFFF)
  static let goldenBody = UIColor(expressLight: 0x5F6368, expressDark: 0x92939A)
  static let goldenFeature = UIColor(expressLight: 0xF6F7F9, expressDark: 0x15171A)
  static let goldenFeatureStroke = UIColor(expressLight: 0xE4E5E9, expressDark: 0x2B2D33)
  static let goldenBadgeRed = UIColor(expressLight: 0xFFE8E8, expressDark: 0x241719)
  static let goldenBadgePurple = UIColor(expressLight: 0xF3E8FF, expressDark: 0x251733)
  static let goldenFeatureTitle = UIColor(expressLight: 0x202124, expressDark: 0xF1F1F3)
  static let goldenFeatureBody = UIColor(expressLight: 0x5F6368, expressDark: 0x909199)
  static let goldenLater = UIColor(expressLight: 0x5F6368, expressDark: 0xA8A8AC)
}

/// Android's `fragment_golden_youtube_home_screen_shortcut_dialog.xml`: a gift, "You've Unlocked
/// Premium YouTube ✦", the ad-free and background-play card, "Add to Home Screen" and "Maybe
/// later". Android then shows the launcher's own pin dialog; iOS says where the quick action is.
struct ExpressGoldenShortcutSheet: View {
  var onAdd: () -> Void
  /// The content changed height.
  var onResize: () -> Void
  var onClose: () -> Void

  @State private var added = false

  static let howToUse = "Touch and hold the Express Browser icon on your Home Screen, then tap YouTube."

  /// Android's `golden_youtube_shortcut_cta_bg`.
  private static let buttonGradient = LinearGradient(
    colors: [Color(expressARGB: 0xFFFF_2929), Color(expressARGB: 0xFF9B_1EC8)],
    startPoint: .leading,
    endPoint: .trailing
  )
  /// Android's `GoldenYouTubeGradientTextView`.
  private static let titleGradient = LinearGradient(
    colors: [Color(expressARGB: 0xFFFF_2D2D), Color(expressARGB: 0xFFC4_2BDF)],
    startPoint: .leading,
    endPoint: .trailing
  )

  var body: some View {
    VStack(spacing: 0) {
      Capsule()
        .fill(Color(ExpressYouTubeColors.goldenHandle))
        .frame(width: 54, height: 5)
        .padding(.top, 10)
        .accessibilityHidden(true)
      header
        .frame(maxWidth: .infinity)
        .frame(height: 132)
        .clipped()
        .padding(.top, 8)
        .accessibilityHidden(true)
      VStack(spacing: 0) {
        VStack(spacing: 3) {
          Text("You've Unlocked")
            .font(.system(size: 17, weight: .bold))
            .foregroundColor(Color(ExpressYouTubeColors.goldenText))
          Text("Premium YouTube ✦")
            .font(.system(size: 20, weight: .bold))
            .foregroundStyle(Self.titleGradient)
        }
        .multilineTextAlignment(.center)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("You've Unlocked Premium YouTube")
        .accessibilityAddTraits(.isHeader)
        Text("Add YouTube Premium to your home screen for a faster, uninterrupted experience.")
          .font(.system(size: 13))
          .foregroundColor(Color(ExpressYouTubeColors.goldenBody))
          .multilineTextAlignment(.center)
          .lineSpacing(1)
          .fixedSize(horizontal: false, vertical: true)
          .padding(.horizontal, 12)
          .padding(.top, 9)
        featureCard
          .padding(.top, 14)
        if added {
          confirmation
            .transition(.opacity)
        } else {
          buttons
            .transition(.opacity)
        }
      }
      .padding(.horizontal, 20)
      .padding(.top, 8)
      .padding(.bottom, 12)
    }
    .frame(maxHeight: .infinity, alignment: .top)
    .background(Color(ExpressYouTubeColors.goldenSheet).ignoresSafeArea())
    .onChange(of: added) { _, _ in onResize() }
  }

  /// Android's gift picture once it's in the asset catalog as `express-golden-youtube-gift`
  /// (`golden_youtube_shortcut_gift.png`); until then, a gift in the button's colours.
  @ViewBuilder private var header: some View {
    if let gift = UIImage(named: "express-golden-youtube-gift", in: .module, compatibleWith: nil) {
      Image(uiImage: gift)
        .resizable()
        .scaledToFill()
    } else {
      ZStack {
        RadialGradient(
          colors: [Color(expressARGB: 0x40FF_2929), Color(expressARGB: 0x009B_1EC8)],
          center: .center,
          startRadius: 0,
          endRadius: 90
        )
        Image(systemName: "gift.fill")
          .font(.system(size: 64))
          .foregroundStyle(Self.buttonGradient)
      }
    }
  }

  private var featureCard: some View {
    HStack(spacing: 0) {
      feature(title: "Ad-free", detail: "No interruptions", badge: ExpressYouTubeColors.goldenBadgeRed) {
        ZStack {
          Text("Ads")
            .font(.system(size: 10, weight: .bold))
            .foregroundColor(Color(ExpressYouTubeColors.goldenText))
          Image(systemName: "nosign")
            .font(.system(size: 32, weight: .medium))
            .foregroundColor(Color(expressARGB: 0xFFFF_4545))
        }
      }
      Rectangle()
        .fill(Color(ExpressYouTubeColors.goldenFeatureStroke))
        .frame(width: 1, height: 46)
        .padding(.leading, 8)
        .padding(.trailing, 10)
      feature(
        title: "Background Play",
        detail: "Play while you multitask",
        badge: ExpressYouTubeColors.goldenBadgePurple
      ) {
        Image(systemName: "headphones")
          .font(.system(size: 22, weight: .semibold))
          .foregroundColor(Color(expressARGB: 0xFF9D_35FF))
      }
    }
    .padding(10)
    .frame(minHeight: 72)
    .background(card)
  }

  private func feature<Icon: View>(
    title: String,
    detail: String,
    badge: UIColor,
    @ViewBuilder icon: () -> Icon
  ) -> some View {
    let iconView = icon()
    return HStack(spacing: 10) {
      RoundedRectangle(cornerRadius: 14)
        .fill(Color(badge))
        .frame(width: 46, height: 46)
        .overlay(iconView)
      VStack(alignment: .leading, spacing: 4) {
        Text(title)
          .font(.system(size: 12, weight: .bold))
          .foregroundColor(Color(ExpressYouTubeColors.goldenFeatureTitle))
        Text(detail)
          .font(.system(size: 11))
          .foregroundColor(Color(ExpressYouTubeColors.goldenFeatureBody))
      }
      .fixedSize(horizontal: false, vertical: true)
      Spacer(minLength: 0)
    }
    .frame(maxWidth: .infinity)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(title), \(detail)")
  }

  private var buttons: some View {
    VStack(spacing: 0) {
      Button {
        onAdd()
        UIAccessibility.post(notification: .announcement, argument: Self.howToUse)
        withAnimation(.easeOut(duration: 0.2)) { added = true }
      } label: {
        HStack(spacing: 8) {
          Image(systemName: "plus.circle")
            .font(.system(size: 18, weight: .semibold))
            .accessibilityHidden(true)
          Text("Add to Home Screen")
            .font(.system(size: 15, weight: .bold))
        }
        .foregroundColor(.white)
        .padding(.horizontal, 14)
        .frame(maxWidth: .infinity, minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 18).fill(Self.buttonGradient))
      }
      .buttonStyle(.plain)
      .padding(.top, 16)
      Button(action: onClose) {
        Text("Maybe later")
          .font(.system(size: 13))
          .foregroundColor(Color(ExpressYouTubeColors.goldenLater))
          .frame(maxWidth: .infinity, minHeight: 36)
          .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .padding(.top, 6)
    }
  }

  /// Where the quick action is, then "Got it".
  private var confirmation: some View {
    VStack(spacing: 0) {
      HStack(alignment: .top, spacing: 10) {
        Image(systemName: "checkmark.circle.fill")
          .font(.system(size: 20))
          .foregroundStyle(Self.buttonGradient)
          .accessibilityHidden(true)
        VStack(alignment: .leading, spacing: 3) {
          Text("YouTube shortcut added")
            .font(.system(size: 13, weight: .bold))
            .foregroundColor(Color(ExpressYouTubeColors.goldenFeatureTitle))
          Text(Self.howToUse)
            .font(.system(size: 12))
            .foregroundColor(Color(ExpressYouTubeColors.goldenFeatureBody))
            .fixedSize(horizontal: false, vertical: true)
        }
        Spacer(minLength: 0)
      }
      .padding(12)
      .background(card)
      .accessibilityElement(children: .combine)
      .padding(.top, 16)
      Button(action: onClose) {
        Text("Got it")
          .font(.system(size: 15, weight: .bold))
          .foregroundColor(.white)
          .frame(maxWidth: .infinity, minHeight: 48)
          .background(RoundedRectangle(cornerRadius: 18).fill(Self.buttonGradient))
      }
      .buttonStyle(.plain)
      .padding(.top, 12)
    }
  }

  private var card: some View {
    RoundedRectangle(cornerRadius: 16)
      .fill(Color(ExpressYouTubeColors.goldenFeature))
      .overlay(
        RoundedRectangle(cornerRadius: 16)
          .stroke(Color(ExpressYouTubeColors.goldenFeatureStroke), lineWidth: 1)
      )
  }
}

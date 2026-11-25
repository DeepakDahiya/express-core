// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveShared
import BraveShields
import Combine
import Foundation
import Preferences
import Shared
import Strings
import SwiftUI
import UIKit
import Web

/// Colours from Android's `browser_express_privacy_*` (light) and `values-night` (dark).
enum ExpressPrivacyColors {
  static let background = UIColor(expressLight: 0xF2F5F7, expressDark: 0x111111)
  static let title = UIColor(expressLight: 0x0A1730, expressDark: 0xFFFFFF)
  static let section = UIColor(expressLight: 0x6D7686, expressDark: 0xFFFFFF)
  static let caption = UIColor(expressLight: 0x858C99, expressDark: 0xFFFFFF)
  static let divider = UIColor(expressLight: 0x0A1730, expressDark: 0xFFFFFF, alpha: 0.08)
  static let icon = UIColor(expressLight: 0x577DFF, expressDark: 0x8EB4FF)
  static let chevron = UIColor(expressLight: 0x8A93A2, expressDark: 0xFFFFFF)
  static let summaryStart = Color(expressARGB: 0xFF57_7DFF)
  static let summaryEnd = Color(expressARGB: 0xFF6F_3BED)
}

// MARK: - Sites protected

/// How many different sites had something blocked, kept on this device (Android's
/// `ExpressShieldsStats` "sites protected"). Counted from when this was added; private tabs are
/// never recorded.
enum ExpressProtectedSites {
  private static let key = "express.privacy.protected_sites"

  static var count: Int {
    (UserDefaults.standard.stringArray(forKey: key) ?? []).count
  }

  /// Records the page's site when something was blocked on it.
  @MainActor static func record(tab: some TabState) {
    guard !tab.isPrivate, let url = tab.visibleURL,
      let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https",
      (tab.contentBlocker?.stats.total ?? 0) > 0
    else { return }
    let site = url.baseDomain ?? url.host ?? ""
    guard !site.isEmpty else { return }
    var sites = Set(UserDefaults.standard.stringArray(forKey: key) ?? [])
    // Written only when a new site is added.
    if sites.insert(site).inserted {
      UserDefaults.standard.set(Array(sites), forKey: key)
    }
  }
}

// MARK: - Clear on exit

/// "Clear data when I close Express". Like Android it takes effect at the next launch: history,
/// cache, cookies and site data, and recent searches go as the browser starts (passwords and
/// downloads are left alone).
enum ExpressClearOnExit {
  static let isEnabled = Preferences.Option<Bool>(key: "express.privacy.clear_on_exit", default: false)
}

// MARK: - Screen

/// Privacy & Security (Android's `PrivacySettingsFragment`, Figma "Trust/Privacy settings"):
/// what the ad blocker has done, and the protections behind it. The toggles are the reader's
/// choices, applied to Brave Shields; they don't confirm anything is blocked on a given site.
///
/// Differences from Android, where iPhone works differently:
/// - One "Block trackers & ads" switch: Brave's iPhone filter covers both, and WebKit always
///   blocks cross-site cookies (Android's separate "Block trackers" switch).
/// - Global Privacy Control in place of "Do Not Track", which iPhone Brave doesn't send.
/// - "Website data" in place of site permissions, which iPhone Brave doesn't have.
struct ExpressPrivacySettingsView: View {
  @ObservedObject var settings: AdvancedShieldsSettings
  /// The default search engine's name, read again whenever the screen shows.
  var currentSearchEngine: () -> String
  var onClearBrowsingData: () -> Void
  var onWebsiteData: () -> Void
  var onSearchEngine: () -> Void
  var onAllShieldsSettings: () -> Void

  @State private var blocked = 0
  @State private var dataSaved = ""
  @State private var sites = 0
  @State private var isGPCOn = ShieldPreferences.enableGPC.value
  @State private var isClearOnExitOn = ExpressClearOnExit.isEnabled.value
  @State private var searchEngineName = ""

  private var isBlockingAds: Bool { settings.adBlockAndTrackingPreventionLevel != .disabled }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 0) {
        summary
          .padding(.horizontal, 16)
          .padding(.top, 16)

        sectionTitle("Protection").padding(.top, 20)
        toggleRow(
          "Block trackers & ads",
          symbol: "eye.slash",
          isOn: Binding(
            get: { isBlockingAds },
            set: { on in
              // A stricter level set elsewhere reads as on and is left alone.
              if on, !isBlockingAds {
                settings.adBlockAndTrackingPreventionLevel = .standard
              } else if !on {
                settings.adBlockAndTrackingPreventionLevel = .disabled
              }
            }
          )
        )
        divider
        toggleRow(
          "Always use secure connections",
          symbol: "lock",
          isOn: Binding(
            get: { settings.httpsUpgradeLevel.isEnabled },
            set: { on in
              if on, !settings.httpsUpgradeLevel.isEnabled {
                settings.httpsUpgradeLevel = .standard
              } else if !on {
                settings.httpsUpgradeLevel = .disabled
              }
            }
          )
        )
        divider
        toggleRow(
          "Block fingerprinting",
          symbol: "touchid",
          isOn: $settings.isBlockFingerprintingEnabled
        )
        caption(
          isBlockingAds
            ? "Trackers and ads are blocked on every site you visit."
            : "Trackers and ads are not blocked."
        )

        sectionTitle("Data")
        toggleRow(
          "Clear data when I close Express",
          symbol: "clock.arrow.circlepath",
          isOn: Binding(
            get: { isClearOnExitOn },
            set: { on in
              isClearOnExitOn = on
              ExpressClearOnExit.isEnabled.value = on
            }
          )
        )
        divider
        linkRow("Clear browsing data", symbol: "trash", action: onClearBrowsingData)
        divider
        linkRow("Website data", symbol: "externaldrive", action: onWebsiteData)
        caption(
          isClearOnExitOn
            ? "Your history is removed when you close the app."
            : "Your history is kept until you clear it."
        )

        sectionTitle("General")
        toggleRow(
          "Send Global Privacy Control",
          symbol: "hand.raised",
          isOn: Binding(
            get: { isGPCOn },
            set: { on in
              isGPCOn = on
              ShieldPreferences.enableGPC.value = on
            }
          )
        )
        divider
        linkRow("Search engine", symbol: "magnifyingglass", detail: searchEngineName, action: onSearchEngine)
        divider
        linkRow("All Shields settings", symbol: "slider.horizontal.3", action: onAllShieldsSettings)
      }
      .padding(.bottom, 21)
    }
    .background(Color(ExpressPrivacyColors.background).ignoresSafeArea())
    .navigationTitle("Privacy & Security")
    .onAppear(perform: refreshStats)
    .onReceive(
      NotificationCenter.default.publisher(
        for: Notification.Name(BraveGlobalShieldStats.didUpdateNotification)
      )
    ) { _ in refreshStats() }
  }

  private func refreshStats() {
    let shared = BraveGlobalShieldStats.shared
    blocked = max(0, shared.adblock + shared.trackingProtection)
    dataSaved = shared.dataSaved
    sites = ExpressProtectedSites.count
    isGPCOn = ShieldPreferences.enableGPC.value
    searchEngineName = currentSearchEngine()
  }

  // MARK: Summary

  private var summary: some View {
    VStack(spacing: 0) {
      HStack {
        Text("PRIVACY SUMMARY")
          .font(.system(size: 10, weight: .medium))
          .tracking(1.2)
          .accessibilityAddTraits(.isHeader)
        Spacer()
        Image(systemName: "checkmark.shield.fill")
          .font(.system(size: 16))
          .accessibilityHidden(true)
      }
      HStack(alignment: .top, spacing: 0) {
        stat(Self.format(blocked), "Trackers & ads blocked")
        statDivider
        stat(dataSaved.isEmpty ? "—" : dataSaved, "Data saved")
        statDivider
        stat(Self.format(sites), "Sites protected")
      }
      .padding(.top, 4)
      Text(
        isBlockingAds
          ? "Protections are on by default for every site" : "Protections are turned off"
      )
      .font(.system(size: 11))
      .multilineTextAlignment(.center)
      .frame(maxWidth: .infinity)
      .padding(.top, 24)
    }
    .foregroundColor(.white)
    .padding(.horizontal, 20)
    .padding(.top, 18)
    .padding(.bottom, 11)
    .frame(minHeight: 140)
    .background(
      RoundedRectangle(cornerRadius: 20)
        .fill(
          LinearGradient(
            colors: [ExpressPrivacyColors.summaryStart, ExpressPrivacyColors.summaryEnd],
            startPoint: .top,
            endPoint: .bottom
          )
        )
    )
  }

  private func stat(_ value: String, _ label: String) -> some View {
    VStack(spacing: 5) {
      Text(value)
        .font(.system(size: 26, weight: .bold))
        .lineLimit(1)
        .minimumScaleFactor(0.6)
      Text(label)
        .font(.system(size: 11, weight: .medium))
        .multilineTextAlignment(.center)
        .padding(.horizontal, 4)
    }
    .frame(maxWidth: .infinity)
    .accessibilityElement(children: .combine)
  }

  private var statDivider: some View {
    Rectangle()
      .fill(Color.white)
      .frame(width: 1, height: 44)
      .padding(.top, 8)
  }

  /// Counts from 100,000 up are shortened ("128K") so the columns hold them.
  private static func format(_ count: Int) -> String {
    count < 100_000
      ? count.formatted()
      : count.formatted(.number.notation(.compactName).precision(.fractionLength(0...1)))
  }

  // MARK: Rows

  private func sectionTitle(_ title: String) -> some View {
    Text(title)
      .font(.system(size: 16, weight: .medium))
      .foregroundColor(Color(ExpressPrivacyColors.section))
      .padding(.horizontal, 16)
      .padding(.bottom, 9)
      .accessibilityAddTraits(.isHeader)
  }

  private func caption(_ text: String) -> some View {
    Text(text)
      .font(.system(size: 13))
      .foregroundColor(Color(ExpressPrivacyColors.caption))
      .padding(.horizontal, 16)
      .padding(.top, 12)
      .padding(.bottom, 16)
  }

  private var divider: some View {
    Rectangle()
      .fill(Color(ExpressPrivacyColors.divider))
      .frame(height: 1)
      .padding(.leading, 72)
  }

  private func rowIcon(_ symbol: String) -> some View {
    Image(systemName: symbol)
      .font(.system(size: 18))
      .foregroundColor(Color(ExpressPrivacyColors.icon))
      .frame(width: 24, height: 24)
      .accessibilityHidden(true)
  }

  private func toggleRow(_ title: String, symbol: String, isOn: Binding<Bool>) -> some View {
    HStack(spacing: 0) {
      rowIcon(symbol)
      Toggle(title, isOn: isOn)
        .font(.system(size: 15))
        .foregroundColor(Color(ExpressPrivacyColors.title))
        .tint(ExpressTrustColors.toggleOn)
        .padding(.leading, 32)
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 8)
    .frame(minHeight: 56)
  }

  private func linkRow(
    _ title: String,
    symbol: String,
    detail: String? = nil,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 0) {
        rowIcon(symbol)
        Text(title)
          .font(.system(size: 15))
          .foregroundColor(Color(ExpressPrivacyColors.title))
          .padding(.leading, 32)
        Spacer(minLength: 12)
        if let detail, !detail.isEmpty {
          Text(detail)
            .font(.system(size: 13))
            .foregroundColor(Color(ExpressPrivacyColors.caption))
            .lineLimit(1)
            .padding(.trailing, 8)
        }
        Image(systemName: "chevron.right")
          .font(.system(size: 12, weight: .semibold))
          .foregroundColor(Color(ExpressPrivacyColors.chevron))
      }
      .padding(.horizontal, 16)
      .padding(.vertical, 8)
      .frame(minHeight: 56)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }
}

// MARK: - Screen with its navigation

/// Builds the screen, with the rows that open other screens wired to its navigation stack.
/// Used from the trust sheet's Advanced Controls and from Settings.
enum ExpressPrivacySettingsScreen {
  @MainActor static func make(
    settings: AdvancedShieldsSettings,
    profile: LegacyBrowserProfile,
    privateBrowsingManager: PrivateBrowsingManager,
    openURL: @escaping (URL) -> Void
  ) -> UIViewController {
    final class Box { weak var controller: UIViewController? }
    let box = Box()
    let host = UIHostingController(
      rootView: ExpressPrivacySettingsView(
        settings: settings,
        currentSearchEngine: { [weak profile] in
          profile?.searchEngines.defaultEngine(forType: .standard)?.displayName ?? ""
        },
        onClearBrowsingData: {
          let controller = UIHostingController(
            rootView: Form { ClearDataSectionView(settings: settings) }
          )
          controller.title = "Clear browsing data"
          box.controller?.navigationController?.pushViewController(controller, animated: true)
        },
        onWebsiteData: {
          box.controller?.present(UIHostingController(rootView: ManageWebsiteDataView()), animated: true)
        },
        onSearchEngine: {
          let controller = SearchSettingsViewController(
            profile: profile,
            privateBrowsingManager: privateBrowsingManager
          )
          box.controller?.navigationController?.pushViewController(controller, animated: true)
        },
        onAllShieldsSettings: {
          var view = AdvancedShieldsSettingsView(settings: settings)
          view.openURLAction = openURL
          let controller = UIHostingController(rootView: view)
          controller.title = Strings.braveShieldsAndPrivacySettingsTitle
          box.controller?.navigationController?.pushViewController(controller, animated: true)
        }
      )
    )
    host.title = "Privacy & Security"
    box.controller = host
    return host
  }
}

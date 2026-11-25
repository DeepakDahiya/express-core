// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveShields
import Favicon
import SwiftUI
import UIKit
import Web

/// Colours from Android's `browser_express_trust_*` (light) and `values-night` (dark).
enum ExpressTrustColors {
  static let sheet = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let handle = UIColor(expressLight: 0xD1D1D9, expressDark: 0x45464D)
  static let host = UIColor(expressLight: 0x0F0F12, expressDark: 0xFFFFFF)
  static let count = UIColor(expressLight: 0x333333, expressDark: 0xFFFFFF)
  /// The dark design has no divider above Advanced Controls.
  static let divider = UIColor { $0.userInterfaceStyle == .dark ? .clear : UIColor(expressLight: 0xE5E7EB, expressDark: 0xE5E7EB) }
  static let advanced = UIColor(expressLight: 0xF3F3F3, expressDark: 0x000000)
  static let advancedText = UIColor(expressLight: 0x000000, expressDark: 0xFFFFFF)
  static let advancedChevron = UIColor(expressLight: 0x7D8591, expressDark: 0xFFFFFF)
  static let toggleOn = Color(expressARGB: 0xFF57_7DFF)
}

/// The site trust sheet behind the marker (Android's `SiteTrustSheet`): the site, what was
/// blocked on this page (live), the site's trust status, blocking ads on this site only, and
/// the way into the privacy settings.
@MainActor
final class ExpressTrustSheetModel: ObservableObject {
  let url: URL
  let host: String
  let status: ExpressTrustStatus
  let isPrivate: Bool
  @Published private(set) var blockedCount: Int
  @Published private(set) var isBlockingAds: Bool

  private weak var tab: (any TabState)?
  private let onShieldsChanged: () -> Void
  private var previousStatsHandler: ((TPPageStats) -> Void)?

  init(tab: some TabState, url: URL, status: ExpressTrustStatus, onShieldsChanged: @escaping () -> Void) {
    self.tab = tab
    self.url = url
    self.host = url.host ?? url.absoluteString
    self.status = status
    self.isPrivate = tab.isPrivate
    self.onShieldsChanged = onShieldsChanged
    self.blockedCount = tab.contentBlocker?.stats.total ?? 0
    self.isBlockingAds = Self.readBlockingAds(tab: tab, url: url)
  }

  /// Follows the page's blocked count while the sheet is open. The content blocker has one
  /// listener slot (the Shields panel's), so the previous listener keeps being called and is
  /// put back afterwards.
  func startObserving() {
    guard let blocker = tab?.contentBlocker else { return }
    previousStatsHandler = blocker.statsDidChange
    let previous = previousStatsHandler
    blocker.statsDidChange = { [weak self] stats in
      previous?(stats)
      Task { @MainActor in self?.blockedCount = stats.total }
    }
    blockedCount = blocker.stats.total
  }

  func stopObserving() {
    tab?.contentBlocker?.statsDidChange = previousStatsHandler
    previousStatsHandler = nil
  }

  private static func readBlockingAds(tab: some TabState, url: URL) -> Bool {
    guard let helper = tab.braveShieldsHelper else { return false }
    return helper.isBraveShieldsEnabled(for: url)
      && helper.shieldLevel(for: url, considerAllShieldsOption: true) != .disabled
  }

  /// Blocks or allows ads on this site only (Android's `setBlockingAdsOnSite`), then reloads
  /// the site's tabs so the page loads under the new choice.
  func setBlockingAds(_ on: Bool) {
    guard let tab, let helper = tab.braveShieldsHelper else { return }
    if on, !helper.isBraveShieldsEnabled(for: url) {
      helper.setBraveShieldsEnabled(true, for: url)
    }
    let global = ShieldPreferences.blockAdsAndTrackingLevel
    helper.setShieldLevel(on ? (global == .disabled ? .standard : global) : .disabled, for: url)
    isBlockingAds = Self.readBlockingAds(tab: tab, url: url)
    onShieldsChanged()
  }
}

struct ExpressTrustSheetView: View {
  @ObservedObject var model: ExpressTrustSheetModel
  var onAdvanced: () -> Void

  var body: some View {
    VStack(spacing: 0) {
      Capsule()
        .fill(Color(ExpressTrustColors.handle))
        .frame(width: 40, height: 4)
        .accessibilityHidden(true)

      HStack(spacing: 8) {
        FaviconImage(url: model.url, isPrivateBrowsing: model.isPrivate)
          .frame(width: 20, height: 20)
          .accessibilityHidden(true)
        Text(model.host)
          .font(.system(size: 18, weight: .medium))
          .tracking(-0.36)
          .foregroundColor(Color(ExpressTrustColors.host))
          .lineLimit(1)
          .truncationMode(.middle)
      }
      .padding(.top, 16)

      VStack(spacing: 0) {
        Text(model.blockedCount.formatted())
          .font(.system(size: 24, weight: .bold))
          .contentTransition(.numericText())
          .animation(.default, value: model.blockedCount)
        Text("trackers, ads and more blocked")
          .font(.system(size: 16))
          .multilineTextAlignment(.center)
      }
      .foregroundColor(Color(ExpressTrustColors.count))
      .padding(.top, 24)
      .accessibilityElement(children: .combine)

      HStack(spacing: 8) {
        Circle()
          .fill(model.status.discColor)
          .frame(width: 32, height: 32)
          .overlay(ExpressTrustGlyphView(glyph: .disc(model.status), size: 24))
          .accessibilityElement()
          .accessibilityLabel(model.status.label)
        Toggle(
          "Block ads on this site",
          isOn: Binding(get: { model.isBlockingAds }, set: { model.setBlockingAds($0) })
        )
        .font(.system(size: 14, weight: .medium))
        .tracking(-0.28)
        .foregroundColor(Color(ExpressTrustColors.host))
        .tint(ExpressTrustColors.toggleOn)
      }
      .frame(minHeight: 44)
      .padding(.top, 16)

      Rectangle()
        .fill(Color(ExpressTrustColors.divider))
        .frame(height: 1)
        .padding(.top, 16)

      Button(action: onAdvanced) {
        HStack {
          Text("Advanced Controls")
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(Color(ExpressTrustColors.advancedText))
          Spacer()
          Image(systemName: "chevron.right")
            .font(.system(size: 13, weight: .semibold))
            .foregroundColor(Color(ExpressTrustColors.advancedChevron))
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 12)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 8).fill(Color(ExpressTrustColors.advanced)))
        .contentShape(Rectangle())
      }
      .buttonStyle(.plain)
      .padding(.top, 16)
    }
    .padding(.horizontal, 20)
    .padding(.top, 12)
    .padding(.bottom, 22)
    .frame(maxWidth: .infinity)
    .background(Color(ExpressTrustColors.sheet).ignoresSafeArea())
    .onAppear { model.startObserving() }
    .onDisappear { model.stopObserving() }
  }
}

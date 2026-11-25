// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import Shared
import ExpressCore
import SwiftUI
import UIKit

// The Profile, ported from the Android app's `BrowserExpressProfilePreferences`: who you are,
// your Karma, Taste and Cred (counting up from what you last saw), and a few links.

// MARK: - Colours (Android light / dark)

extension UIColor {
  /// A colour from Android `#AARRGGBB` values for light and dark.
  convenience init(expressLightARGB light: UInt32, darkARGB dark: UInt32) {
    func color(_ argb: UInt32) -> UIColor {
      UIColor(
        red: CGFloat((argb >> 16) & 0xFF) / 255,
        green: CGFloat((argb >> 8) & 0xFF) / 255,
        blue: CGFloat(argb & 0xFF) / 255,
        alpha: CGFloat((argb >> 24) & 0xFF) / 255
      )
    }
    self.init { $0.userInterfaceStyle == .dark ? color(dark) : color(light) }
  }
}

enum ExpressProfileColors {
  static let page = UIColor(expressLight: 0xE8E7EF, expressDark: 0x121318)
  static let card = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let cardEdge = UIColor(expressLightARGB: 0xB3DB_E0EF, darkARGB: 0x993A_3D45)
  static let avatarRing = UIColor(expressLight: 0xDBE0EF, expressDark: 0x3A3D45)
  static let editPill = UIColor(expressLight: 0xFFFFFF, expressDark: 0x31343B)
  static let textPrimary = UIColor(expressLight: 0x1F212E, expressDark: 0xF4F5F7)
  static let textSecondary = UIColor(expressLight: 0x676767, expressDark: 0xA8ABB5)
  static let textMeta = UIColor(expressLight: 0x80808C, expressDark: 0xA8ABB5)
  static let menuIcon = UIColor(expressLight: 0x676767, expressDark: 0xA8ABB5)
  static let divider = UIColor(expressLight: 0xEEF0F8, expressDark: 0x2A2C33)
  static let chevron = UIColor(expressLight: 0xC7C7DB, expressDark: 0x5A5D66)
  static let danger = UIColor(expressLight: 0xE61A33, expressDark: 0xFF5C6E)
  static let link = UIColor(expressLight: 0x4A6FF0, expressDark: 0x6EA8FF)
  static let success = UIColor(expressLight: 0x1E8E50, expressDark: 0x4CC38A)
  static let toastBackground = UIColor(expressLight: 0x1F212E, expressDark: 0xF4F5F7)
  static let toastText = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1F212E)
  static let toastCheck = UIColor(expressLight: 0x4CC38A, expressDark: 0x1E8E50)
}

/// One of the three scores, with its colours and copy.
struct ExpressScoreKind {
  let eyebrow: String
  let label: String
  let description: String
  let symbol: String
  let accent: UIColor
  let tint: UIColor
  let wash: UIColor

  static let all: [ExpressScoreKind] = [
    ExpressScoreKind(
      eyebrow: "You give",
      label: "Karma",
      description: "You like others’ posts.",
      symbol: "heart.circle",
      accent: UIColor(expressLight: 0xD93A5A, expressDark: 0xFF7A92),
      tint: UIColor(expressLightARGB: 0x24FF_5C7A, darkARGB: 0x33FF_5C7A),
      wash: UIColor(expressLightARGB: 0x1AFF_5C7A, darkARGB: 0x22FF_5C7A)
    ),
    ExpressScoreKind(
      eyebrow: "You spot it first",
      label: "Taste",
      description: "Others like what you liked first.",
      symbol: "diamond",
      accent: UIColor(expressLight: 0x946800, expressDark: 0xF2B227),
      tint: UIColor(expressLightARGB: 0x24F2_B227, darkARGB: 0x33F2_B227),
      wash: UIColor(expressLightARGB: 0x1AF2_B227, darkARGB: 0x22F2_B227)
    ),
    ExpressScoreKind(
      eyebrow: "You create",
      label: "Cred",
      description: "Others like or reply to your content.",
      symbol: "crown",
      accent: UIColor(expressLight: 0x4A6FF0, expressDark: 0x6EA8FF),
      tint: UIColor(expressLightARGB: 0x2457_7DFF, darkARGB: 0x3357_7DFF),
      wash: UIColor(expressLightARGB: 0x1A57_7DFF, darkARGB: 0x2257_7DFF)
    ),
  ]
}

enum ExpressScoreFormat {
  private static let formatter: NumberFormatter = {
    let formatter = NumberFormatter()
    formatter.numberStyle = .decimal
    formatter.maximumFractionDigits = 0
    return formatter
  }()

  static func string(_ value: Int) -> String {
    formatter.string(from: NSNumber(value: value)) ?? "\(value)"
  }
}

// MARK: - Screen

struct ExpressProfileView: View {
  @ObservedObject var model: ExpressProfileModel
  var onClose: () -> Void
  var onOpenSettings: () -> Void
  var onOpenURL: (URL) -> Void
  /// Profile's Referral row: the YouTube access sheet.
  var onOpenReferral: () -> Void = {}

  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var displayed: ExpressScores?
  @State private var countUp: CountUp?
  @State private var isEditing = false
  @State private var showToast = false
  @State private var confirmingLogout = false
  @State private var addingPhone = false
  @State private var notificationsOn = ExpressPushManager.shared.isUserEnabled

  /// The return-visit count-up: each card counts from `from` to `to` in turn.
  struct CountUp: Equatable {
    let start = Date()
    let from: ExpressScores
    let to: ExpressScores
  }

  private static let youTubeSignInURL = URL(
    string:
      "https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fm.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Dm%26hl%3Den-GB%26next%3D%252F&hl=en-GB"
  )!

  var body: some View {
    ScrollView {
      VStack(spacing: 0) {
        navBar
        identityRow
          .padding(.horizontal, 20)
          .padding(.top, 8)
        scoreCards
          .padding(.horizontal, 16)
          .padding(.top, 28)
        if model.loadFailed && model.me == nil {
          Button("Couldn’t load your scores. Tap to try again.") {
            Task { await model.refresh() }
          }
          .font(.system(size: 13))
          .foregroundColor(Color(ExpressProfileColors.link))
          .padding(.top, 12)
        }
        menuCard
          .padding(.horizontal, 16)
          .padding(.top, 24)
      }
      .padding(.bottom, 24)
    }
    .background(Color(ExpressProfileColors.page).ignoresSafeArea())
    .overlay(alignment: .bottom) {
      if showToast {
        ExpressProfileToast()
          .padding(.bottom, 36)
          .transition(.opacity.combined(with: .offset(y: 8)))
      }
    }
    .task {
      if ExpressAccount.shared.validAccessToken != nil {
        ExpressAnalytics.shared.capture("Profile Viewed")
      }
      await load()
    }
    .onChange(of: model.me) { _, me in bind(me) }
    .sheet(isPresented: $isEditing) {
      ExpressEditProfileView(model: model) { saved in
        isEditing = false
        if saved {
          ExpressAnalytics.shared.capture("Profile Updated")
          flashToast()
        }
      }
      .interactiveDismissDisabled()
    }
    .alert("Log out?", isPresented: $confirmingLogout) {
      Button("Cancel", role: .cancel) {}
      Button("Log out", role: .destructive) {
        Task {
          await model.signOut()
          onClose()
        }
      }
    } message: {
      Text("You’ll need to sign in again to get back to this account.")
    }
    .fullScreenCover(isPresented: $addingPhone) {
      // Verifying upgrades this account (the server keeps its comments and Karma).
      ExpressSignInView(
        isRequired: false,
        onSkip: { addingPhone = false },
        onFinish: {
          addingPhone = false
          Task { await model.refresh() }
        }
      )
    }
  }

  // MARK: Parts

  private var navBar: some View {
    ZStack {
      Text("Profile")
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(Color(ExpressProfileColors.textPrimary))
        .accessibilityAddTraits(.isHeader)
      HStack {
        Button(action: onClose) {
          Image(systemName: "arrow.left")
            .font(.system(size: 20, weight: .medium))
            .frame(width: 44, height: 44)
        }
        .accessibilityLabel("Back")
        Spacer()
        Menu {
          Button("Settings", action: onOpenSettings)
          Button("Version \(AppInfo.appVersion)") {}
            .disabled(true)
        } label: {
          Image(systemName: "ellipsis")
            .font(.system(size: 20, weight: .semibold))
            .rotationEffect(.degrees(90))
            .frame(width: 44, height: 44)
        }
        .accessibilityLabel("More options")
      }
      .foregroundColor(Color(ExpressProfileColors.textPrimary))
    }
    .frame(height: 48)
    .padding(.horizontal, 12)
  }

  private var identityRow: some View {
    let name = model.me?.name ?? model.me?.username ?? model.claims?.name ?? model.claims?.username ?? ""
    let handle = model.me?.username ?? model.claims?.username
    return HStack(spacing: 14) {
      ExpressAvatarView(url: model.avatarURL, size: 64)
        .padding(1)
        .overlay(Circle().stroke(Color(ExpressProfileColors.avatarRing), lineWidth: 1))
        .accessibilityHidden(true)
      VStack(alignment: .leading, spacing: 3) {
        Text(name)
          .font(.system(size: 21, weight: .bold))
          .tracking(-0.42)
          .foregroundColor(Color(ExpressProfileColors.textPrimary))
          .lineLimit(1)
        if let handle {
          Text("@\(handle)")
            .font(.system(size: 14))
            .foregroundColor(Color(ExpressProfileColors.textMeta))
            .lineLimit(1)
        }
      }
      .frame(maxWidth: .infinity, alignment: .leading)
      Button {
        isEditing = true
      } label: {
        HStack(spacing: 6) {
          Image(systemName: "pencil").font(.system(size: 12, weight: .bold))
          Text("Edit").font(.system(size: 13, weight: .semibold))
        }
        .foregroundColor(Color(ExpressProfileColors.textPrimary))
        .padding(.horizontal, 14)
        .frame(height: 34)
        .background(Capsule().fill(Color(ExpressProfileColors.editPill)))
      }
      .buttonStyle(.plain)
      .accessibilityLabel("Edit profile")
    }
  }

  private var scoreCards: some View {
    TimelineView(.animation(paused: countUp == nil)) { timeline in
      VStack(spacing: 8) {
        ForEach(0..<3, id: \.self) { index in
          let state = cardState(index, at: timeline.date)
          ExpressScoreCard(
            kind: ExpressScoreKind.all[index],
            value: state.value,
            gain: state.gain,
            chipProgress: state.chip,
            chipLeaving: state.chipLeaving,
            pulse: state.pulse
          )
        }
      }
    }
  }

  private var menuCard: some View {
    VStack(spacing: 0) {
      if !model.hasPhone {
        menuRow("Add your phone number", symbol: "iphone", color: ExpressProfileColors.link) {
          addingPhone = true
        }
        divider
      }
      if ExpressPushManager.shared.isFeatureEnabled {
        // Android's "notify me about likes and replies" switch.
        HStack(spacing: 14) {
          Image(systemName: "bell")
            .font(.system(size: 17))
            .foregroundColor(Color(ExpressProfileColors.menuIcon))
            .frame(width: 20)
          Toggle("Likes & replies", isOn: $notificationsOn)
            .font(.system(size: 15, weight: .medium))
            .foregroundColor(Color(ExpressProfileColors.textPrimary))
            .tint(Color(ExpressProfileColors.link))
        }
        .padding(.horizontal, 16)
        .frame(height: 54)
        .onChange(of: notificationsOn) { _, on in
          ExpressPushManager.shared.isUserEnabled = on
          if on { ExpressPushManager.shared.promptAfterFirstComment() }
        }
        divider
      }
      menuRow("YouTube", symbol: "play.rectangle", color: ExpressProfileColors.menuIcon) {
        if ExpressAccount.shared.validAccessToken != nil {
          ExpressAnalytics.shared.capture("YTP Premium Clicked on Quicklink")
        }
        onOpenURL(Self.youTubeSignInURL)
      }
      divider
      menuRow("Referral", symbol: "gift", color: ExpressProfileColors.menuIcon) {
        if ExpressAccount.shared.validAccessToken != nil {
          ExpressAnalytics.shared.capture(
            "Referral Clicked in Profile",
            ["referral_code": ExpressAccount.shared.claims?.referralCode ?? ""]
          )
        }
        onOpenReferral()
      }
      divider
      menuRow(
        "Logout",
        symbol: "rectangle.portrait.and.arrow.right",
        color: ExpressProfileColors.danger,
        labelColor: ExpressProfileColors.danger
      ) {
        confirmingLogout = true
      }
    }
    .background(cardBackground)
    .clipShape(RoundedRectangle(cornerRadius: 18))
  }

  private var divider: some View {
    Rectangle()
      .fill(Color(ExpressProfileColors.divider))
      .frame(height: 1)
      .padding(.leading, 56)
  }

  private func menuRow(
    _ title: String,
    symbol: String,
    color: UIColor,
    labelColor: UIColor = ExpressProfileColors.textPrimary,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      HStack(spacing: 14) {
        Image(systemName: symbol)
          .font(.system(size: 17))
          .foregroundColor(Color(color))
          .frame(width: 20)
        Text(title)
          .font(.system(size: 15, weight: .medium))
          .foregroundColor(Color(labelColor))
          .lineLimit(1)
          .frame(maxWidth: .infinity, alignment: .leading)
        Image(systemName: "chevron.right")
          .font(.system(size: 13, weight: .semibold))
          .foregroundColor(Color(ExpressProfileColors.chevron))
      }
      .padding(.horizontal, 16)
      .frame(height: 54)
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
  }

  private var cardBackground: some View {
    RoundedRectangle(cornerRadius: 18)
      .fill(Color(ExpressProfileColors.card))
      .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(ExpressProfileColors.cardEdge), lineWidth: 1))
  }

  // MARK: Data and the count-up

  private func load() async {
    if let me = model.me { bind(me) }
    await model.refresh()
  }

  /// From the scores on screen, else the ones last seen, to the new ones. A first visit, or
  /// nothing having gone up, just shows them. The server is told they've been seen.
  private func bind(_ me: ExpressMe?) {
    guard let me else { return }
    if me.scoresSeen != me.scores {
      model.markScoresSeen(me.scores)
    }
    guard countUp == nil else { return }
    let from = displayed ?? me.scoresSeen
    displayed = me.scores
    guard let from, (0..<3).contains(where: { me.scores[$0] > from[$0] }) else { return }
    countUp = CountUp(from: from, to: me.scores)
    let duration: UInt64 = reduceMotion ? 1_500_000_000 : 3_240_000_000
    Task {
      try? await Task.sleep(nanoseconds: duration)
      countUp = nil
    }
  }

  private struct CardState {
    var value: Int?
    var gain: Int?
    var chip: CGFloat = 0
    var chipLeaving: CGFloat = 0
    var pulse: CGFloat = 0
  }

  /// Card `index` starts at 300 + 260·index ms: the chip comes in over 250 ms, the number
  /// counts over 750 ms from 120 ms later (the tile and wash pulse with it), and the chip
  /// leaves from 2600 + 120·index ms over 400 ms.
  private func cardState(_ index: Int, at date: Date) -> CardState {
    guard let countUp else {
      return CardState(value: displayed?[index])
    }
    let from = countUp.from[index]
    let to = countUp.to[index]
    guard to > from else { return CardState(value: to) }
    let gain = to - from
    if reduceMotion {
      return CardState(value: to, gain: gain, chip: 1)
    }
    let t = date.timeIntervalSince(countUp.start)
    let start = 0.3 + 0.26 * Double(index)
    let chipIn = easeOut(ramp(t, start, 0.25))
    let count = easeOut(ramp(t, start + 0.12, 0.75))
    let leaving = ramp(t, 2.6 + 0.12 * Double(index), 0.4)
    let value = from + Int((Double(gain) * Double(count)).rounded())
    let pulse = count > 0 && count < 1 ? sin(.pi * count) : 0
    return CardState(value: value, gain: gain, chip: chipIn, chipLeaving: leaving, pulse: pulse)
  }

  private func ramp(_ t: Double, _ start: Double, _ length: Double) -> CGFloat {
    CGFloat(max(0, min(1, (t - start) / length)))
  }

  private func easeOut(_ t: CGFloat) -> CGFloat {
    1 - pow(1 - t, 3)
  }

  private func flashToast() {
    withAnimation(.easeOut(duration: 0.29)) { showToast = true }
    Task {
      try? await Task.sleep(nanoseconds: 2_040_000_000)
      withAnimation(.easeIn(duration: 0.36)) { showToast = false }
    }
  }
}

// MARK: - Score card

private struct ExpressScoreCard: View {
  let kind: ExpressScoreKind
  let value: Int?
  let gain: Int?
  let chipProgress: CGFloat
  let chipLeaving: CGFloat
  let pulse: CGFloat

  var body: some View {
    VStack(alignment: .leading, spacing: 10) {
      HStack(spacing: 0) {
        RoundedRectangle(cornerRadius: 12)
          .fill(Color(kind.tint))
          .frame(width: 40, height: 40)
          .overlay(
            Image(systemName: kind.symbol)
              .font(.system(size: 18, weight: .medium))
              .foregroundColor(Color(kind.accent))
          )
          .scaleEffect(1 + 0.08 * pulse)
          .accessibilityHidden(true)
        VStack(alignment: .leading, spacing: 1) {
          Text(kind.eyebrow.uppercased())
            .font(.system(size: 10.5, weight: .semibold))
            .tracking(0.84)
            .foregroundColor(Color(kind.accent))
          Text(kind.label)
            .font(.system(size: 16, weight: .semibold))
            .tracking(-0.16)
            .foregroundColor(Color(ExpressProfileColors.textPrimary))
        }
        .padding(.leading, 14)
        .frame(maxWidth: .infinity, alignment: .leading)
        if let gain, chipProgress > 0 {
          Text("+\(ExpressScoreFormat.string(gain))")
            .font(.system(size: 11, weight: .semibold).monospacedDigit())
            .foregroundColor(Color(kind.accent))
            .padding(.horizontal, 7)
            .frame(height: 20)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color(kind.tint)))
            .scaleEffect((0.85 + 0.15 * chipProgress) * (1 - 0.1 * chipLeaving))
            .offset(y: 6 * (1 - chipProgress) - 6 * chipLeaving)
            .opacity(Double(chipProgress * (1 - chipLeaving)))
            .padding(.leading, 8)
            .accessibilityHidden(true)
        }
        ZStack(alignment: .trailing) {
          if let value {
            Text(ExpressScoreFormat.string(value))
              .font(.system(size: 20, weight: .semibold).monospacedDigit())
              .foregroundColor(Color(ExpressProfileColors.textPrimary))
          } else {
            RoundedRectangle(cornerRadius: 4)
              .fill(Color(ExpressProfileColors.divider))
              .frame(width: 36, height: 14)
          }
        }
        .frame(minWidth: 40, alignment: .trailing)
        .padding(.leading, 8)
      }
      Text(kind.description)
        .font(.system(size: 13))
        .lineSpacing(3)
        .foregroundColor(Color(ExpressProfileColors.textSecondary))
        .padding(.leading, 54)
        .fixedSize(horizontal: false, vertical: true)
    }
    .padding(16)
    .background(
      RoundedRectangle(cornerRadius: 18)
        .fill(Color(ExpressProfileColors.card))
        .overlay(alignment: .topTrailing) {
          // A soft glow behind the score's corner that pulses as it counts.
          RadialGradient(
            colors: [Color(kind.wash), Color(kind.wash).opacity(0)],
            center: .center,
            startRadius: 0,
            endRadius: 110
          )
          .frame(width: 240, height: 240)
          .scaleEffect(1 + 0.12 * pulse)
          .opacity(0.7 + 0.3 * pulse)
          .offset(x: 120 - 40, y: -120 + 10)
          .allowsHitTesting(false)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).stroke(Color(ExpressProfileColors.cardEdge), lineWidth: 1))
    )
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(kind.label): \(value.map(ExpressScoreFormat.string) ?? "loading"). \(kind.description)")
  }
}

// MARK: - Toast

/// "Profile updated", shown after Edit profile saves.
private struct ExpressProfileToast: View {
  var body: some View {
    HStack(spacing: 8) {
      Image(systemName: "checkmark")
        .font(.system(size: 13, weight: .bold))
        .foregroundColor(Color(ExpressProfileColors.toastCheck))
      Text("Profile updated")
        .font(.system(size: 14, weight: .medium))
        .foregroundColor(Color(ExpressProfileColors.toastText))
    }
    .padding(.horizontal, 16)
    .frame(height: 40)
    .background(Capsule().fill(Color(ExpressProfileColors.toastBackground)))
    .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
    .accessibilityElement(children: .combine)
    .accessibilityAddTraits(.updatesFrequently)
  }
}

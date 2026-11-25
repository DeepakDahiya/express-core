// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SwiftUI
import UIKit
import os.log

// The YouTube access sheet, ported from the Android app's `YouTubePremiumBottomSheetFragment`:
// how much free YouTube access is left, the friends referred and days earned, and a button to
// share the referral link. Opened on YouTube pages (with a cooldown) and from Profile.

enum ExpressYouTubeColors {
  static let sheet = UIColor(expressLight: 0xFFFFFF, expressDark: 0x0F0F0F)
  static let handle = UIColor(expressLight: 0xD1D1D9, expressDark: 0x59595E)
  static let text = UIColor(expressLight: 0x0F0F12, expressDark: 0xFFFFFF)
  static let muted = UIColor(expressLight: 0x6B6E78, expressDark: 0xAAAAB0)
  static let card = UIColor(expressLight: 0xF5F5F8, expressDark: 0x1B1B1C)
  static let cardStroke = UIColor(expressLight: 0xE5E5ED, expressDark: 0x29292B)
  static let accent = UIColor(expressLight: 0xE21D2E, expressDark: 0xFF4E45)
  static let button = UIColor(expressLight: 0x0F0F12, expressDark: 0xFFFFFF)
  static let buttonText = UIColor(expressLight: 0xFFFFFF, expressDark: 0x0F0F0F)
  static let ringTrack = UIColor(expressLight: 0xE0E0E8, expressDark: 0x38383D)
  static let star = UIColor(expressLight: 0xC78F1F, expressDark: 0xF5C24A)
}

// MARK: - Model

@MainActor
final class ExpressYouTubeAccessModel: ObservableObject {
  enum Source {
    /// Opened by a YouTube page load.
    case youtubePage
    /// Opened from Profile's Referral row.
    case profile
  }

  @Published private(set) var access: ExpressYouTubeAccess?
  @Published private(set) var isLoading = true
  @Published private(set) var fetchedAt = Date()
  @Published var shareFailed = false

  let source: Source

  private static let cachedSecondsKey = "express.yt_access.seconds"
  private static let cachedAtKey = "express.yt_access.cached_at"
  static let blockedKey = "express.yt_access.blocked"

  init(source: Source) {
    self.source = source
  }

  /// Whether the last fetch said access had run out (used before the next fetch).
  static var cachedIsBlocked: Bool {
    UserDefaults.standard.bool(forKey: blockedKey)
  }

  func load() async {
    isLoading = true
    defer { isLoading = false }
    guard let token = ExpressAccount.shared.validAccessToken else {
      access = Self.cachedAccess()
      return
    }
    do {
      let fetched = try await ExpressAPIClient.shared.fetchYouTubeAccess(accessToken: token)
      access = fetched
      fetchedAt = Date()
      let defaults = UserDefaults.standard
      defaults.set(fetched.secondsRemaining, forKey: Self.cachedSecondsKey)
      defaults.set(Date().timeIntervalSince1970, forKey: Self.cachedAtKey)
      defaults.set(fetched.isBlocked, forKey: Self.blockedKey)
    } catch {
      Logger.module.error("Express YouTube access failed: \(error.localizedDescription, privacy: .public)")
      access = Self.cachedAccess()
      fetchedAt = Date()
    }
  }

  /// Android's offline fallback: the cached time left (counted down since it was cached), 30
  /// days per referral, and the cached blocked flag. Unlike Android, nothing made up.
  private static func cachedAccess() -> ExpressYouTubeAccess {
    let defaults = UserDefaults.standard
    let cachedAt = defaults.double(forKey: cachedAtKey)
    let elapsed = cachedAt > 0 ? Int(Date().timeIntervalSince1970 - cachedAt) : 0
    let seconds = max(0, defaults.integer(forKey: cachedSecondsKey) - elapsed)
    return ExpressYouTubeAccess(
      referralCount: 0,
      secondsRemaining: seconds,
      isBlocked: defaults.bool(forKey: blockedKey),
      referralCode: nil,
      newReferrals: false,
      rewardDaysPerReferral: 30,
      daysEarned: 0
    )
  }

  func secondsLeft(at date: Date) -> Int {
    guard let access else { return 0 }
    return max(0, access.secondsRemaining - Int(date.timeIntervalSince(fetchedAt)))
  }

  /// The code from the account's token, else the access response, else `/v1/referral/code`.
  func referralShareText() async -> String? {
    var code = ExpressAccount.shared.claims?.referralCode.flatMap { $0.isEmpty ? nil : $0 }
    if code == nil { code = access?.referralCode }
    if code == nil, let token = ExpressAccount.shared.validAccessToken {
      code = await ExpressAPIClient.shared.fetchReferralCode(accessToken: token)
    }
    return code.flatMap(ExpressReferralLink.shareText(code:))
  }
}

// MARK: - Sheet

struct ExpressYouTubeAccessSheet: View {
  @ObservedObject var model: ExpressYouTubeAccessModel
  /// Shows the system share sheet with this text.
  var onShare: (String) -> Void
  var onClose: () -> Void
  /// Leaves YouTube (only offered when access has run out on a YouTube page).
  var onLeaveYouTube: () -> Void

  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @State private var countUp: CGFloat = 1
  @State private var confettiStart: Date?
  @State private var isSharing = false

  private var isBlocked: Bool { model.access?.isBlocked ?? false }
  /// On YouTube with access gone, the sheet stays until the reader leaves YouTube.
  private var isLocked: Bool { model.source == .youtubePage && isBlocked }

  var body: some View {
    ZStack(alignment: .topTrailing) {
      content
      cornerControl
        .padding(.top, 12)
        .padding(.trailing, 20)
    }
    .frame(maxHeight: .infinity, alignment: .top)
    .background(Color(ExpressYouTubeColors.sheet).ignoresSafeArea())
    .overlay {
      if let confettiStart {
        ExpressConfettiView(start: confettiStart).allowsHitTesting(false)
      }
    }
    .task {
      await model.load()
      if model.access?.newReferrals == true {
        countUp = 0
        if !reduceMotion { confettiStart = Date() }
        withAnimation(.easeOut(duration: 1.5)) { countUp = 1 }
      }
    }
  }

  private var content: some View {
    VStack(spacing: 0) {
      Capsule()
        .fill(Color(ExpressYouTubeColors.handle))
        .frame(width: 40, height: 4)
        .opacity(isLocked ? 0 : 1)
      brandRow
        .padding(.top, 16)
      Text("Keep YouTube free for longer")
        .font(.system(size: 19, weight: .bold))
        .foregroundColor(Color(ExpressYouTubeColors.text))
        .multilineTextAlignment(.center)
        .padding(.top, 16)
      let days = model.access?.rewardDaysPerReferral ?? 30
      Text("Invite friends — each one adds \(days) \(days == 1 ? "day" : "days")")
        .font(.system(size: 13, weight: .medium))
        .foregroundColor(Color(ExpressYouTubeColors.muted))
        .multilineTextAlignment(.center)
        .padding(.top, 16)

      if model.isLoading {
        ProgressView()
          .tint(Color(ExpressYouTubeColors.accent))
          .frame(height: 160)
      } else {
        // Android hides the countdown until the reader has been on YouTube here.
        if ExpressYouTubeUsage.shared.totalVisits > 0 || isBlocked {
          countdownCard
            .padding(.top, 16)
        }
        statsRow
          .padding(.top, 16)
      }

      Button {
        share()
      } label: {
        Group {
          if isSharing {
            ProgressView().tint(Color(ExpressYouTubeColors.buttonText))
          } else {
            Text(model.source == .profile ? "Refer to unlock now" : "Refer now to extend")
          }
        }
        .font(.system(size: 15, weight: .bold))
        .foregroundColor(Color(ExpressYouTubeColors.buttonText))
        .frame(maxWidth: .infinity)
        .padding(.vertical, 15)
        .background(RoundedRectangle(cornerRadius: 26).fill(Color(ExpressYouTubeColors.button)))
      }
      .buttonStyle(.plain)
      .disabled(isSharing)
      .padding(.top, 16)

      if model.shareFailed {
        Text("Couldn’t get your referral link. Try again.")
          .font(.system(size: 12))
          .foregroundColor(Color(ExpressYouTubeColors.accent))
          .padding(.top, 8)
      }
      if isLocked {
        // Android leaves no way out here; this keeps the reader in the app, off YouTube.
        Button("Leave YouTube", action: onLeaveYouTube)
          .font(.system(size: 14, weight: .semibold))
          .foregroundColor(Color(ExpressYouTubeColors.muted))
          .frame(height: 44)
          .padding(.top, 4)
      }
    }
    .padding(.horizontal, 20)
    .padding(.top, 12)
    .padding(.bottom, 22)
    .background(alignment: .top) {
      if colorScheme == .dark {
        RadialGradient(
          colors: [
            Color(expressARGB: 0xA3FF_1A59),
            Color(expressARGB: 0x69C7_2EC7),
            Color(expressARGB: 0x007D_3BED),
          ],
          center: .center,
          startRadius: 0,
          endRadius: 125
        )
        .frame(width: 250, height: 250)
        .scaleEffect(x: 1, y: 0.56)
        .offset(y: -80)
        .allowsHitTesting(false)
      }
    }
  }

  private var brandRow: some View {
    HStack(spacing: 8) {
      RoundedRectangle(cornerRadius: 7)
        .fill(Color(red: 1, green: 0, blue: 0))
        .frame(width: 36, height: 26)
        .overlay(Image(systemName: "play.fill").font(.system(size: 12)).foregroundColor(.white))
      Text("YouTube")
        .font(.system(size: 21, weight: .bold))
        .tracking(-0.42)
        .foregroundColor(Color(ExpressYouTubeColors.text))
      Image(systemName: "sparkle")
        .font(.system(size: 13, weight: .semibold))
        .foregroundColor(Color(ExpressYouTubeColors.star))
    }
    .accessibilityElement(children: .combine)
  }

  private var countdownCard: some View {
    TimelineView(.periodic(from: .now, by: 1)) { context in
      let seconds = model.secondsLeft(at: context.date)
      let ended = isBlocked || seconds == 0
      VStack(spacing: 8) {
        Text(ended ? "Your free access has ended" : "Your free access ends in")
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(ExpressYouTubeColors.muted))
        HStack(spacing: 0) {
          // Days count up with the stats when new friends joined.
          timeGroup(ended ? 0 : Int((CGFloat(seconds / 86_400) * countUp).rounded()), unit: "DAY")
          colon
          timeGroup(ended ? 0 : seconds % 86_400 / 3600, unit: "HRS")
          colon
          timeGroup(ended ? 0 : seconds % 3600 / 60, unit: "MIN")
          colon
          timeGroup(ended ? 0 : seconds % 60, unit: "SEC")
        }
      }
      .frame(maxWidth: .infinity)
      .padding(.horizontal, 16)
      .padding(.vertical, 14)
      .background(card)
      .accessibilityElement(children: .ignore)
      .accessibilityLabel(
        ended
          ? "Your free access has ended"
          : "Your free access ends in \(seconds / 86_400) days, \(seconds % 86_400 / 3600) hours, \(seconds % 3600 / 60) minutes"
      )
    }
  }

  private func timeGroup(_ value: Int, unit: String) -> some View {
    VStack(spacing: 1) {
      Text(String(format: "%02d", value))
        .font(.system(size: 28, weight: .bold).monospacedDigit())
        .foregroundColor(Color(ExpressYouTubeColors.accent))
        .contentTransition(.numericText())
      Text(unit)
        .font(.system(size: 10, weight: .medium))
        .foregroundColor(Color(ExpressYouTubeColors.muted))
    }
    .frame(minWidth: 44)
  }

  private var colon: some View {
    Text(":")
      .font(.system(size: 24, weight: .bold))
      .foregroundColor(Color(ExpressYouTubeColors.muted).opacity(0.5))
      .padding(.horizontal, 8)
      .padding(.bottom, 14)
  }

  private var statsRow: some View {
    HStack(spacing: 12) {
      statCard(value: model.access?.referralCount ?? 0, label: "Friends referred")
      statCard(value: model.access?.daysEarned ?? 0, label: "Days earned")
    }
    .frame(height: 75)
  }

  private func statCard(value: Int, label: String) -> some View {
    VStack(spacing: 3) {
      Text("\(Int((CGFloat(value) * countUp).rounded()))")
        .font(.system(size: 24, weight: .bold).monospacedDigit())
        .foregroundColor(Color(ExpressYouTubeColors.text))
        .contentTransition(.numericText())
      Text(label)
        .font(.system(size: 12, weight: .medium))
        .foregroundColor(Color(ExpressYouTubeColors.muted))
    }
    .frame(maxWidth: .infinity, maxHeight: .infinity)
    .background(card)
    .accessibilityElement(children: .combine)
  }

  private var card: some View {
    RoundedRectangle(cornerRadius: 16)
      .fill(Color(ExpressYouTubeColors.card))
      .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(ExpressYouTubeColors.cardStroke), lineWidth: 1))
  }

  /// A 10-second ring that closes the sheet on a YouTube page; a close button from Profile;
  /// nothing when access has run out on YouTube.
  @ViewBuilder private var cornerControl: some View {
    if isLocked || model.isLoading && model.source == .youtubePage {
      EmptyView()
    } else if model.source == .youtubePage {
      ExpressAutoCloseRing(duration: 10, onFinish: onClose)
    } else {
      Button(action: onClose) {
        Image(systemName: "xmark")
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(Color(ExpressYouTubeColors.muted))
          .frame(width: 40, height: 40)
      }
      .accessibilityLabel("Close")
    }
  }

  private func share() {
    if ExpressAccount.shared.validAccessToken != nil {
      let code = ExpressAccount.shared.claims?.referralCode
      ExpressAnalytics.shared.capture("Referral Initiated", ["referral_code": code])
      ExpressAnalytics.shared.capture(
        "Referral Source",
        ["source": model.source == .profile ? "profile_referral_button" : "popup_on_yt", "referral_code": code]
      )
    }
    isSharing = true
    model.shareFailed = false
    Task {
      let text = await model.referralShareText()
      isSharing = false
      if let text {
        onShare(text)
      } else {
        model.shareFailed = true
      }
    }
  }
}

/// The ring that counts down to the sheet closing itself, with the seconds left inside.
private struct ExpressAutoCloseRing: View {
  let duration: Double
  var onFinish: () -> Void

  @State private var start = Date()
  @State private var finished = false

  var body: some View {
    TimelineView(.animation) { context in
      let elapsed = context.date.timeIntervalSince(start)
      let progress = max(0, 1 - elapsed / duration)
      ZStack {
        Circle().stroke(Color(ExpressYouTubeColors.ringTrack), lineWidth: 1.8)
        Circle()
          .trim(from: 0, to: progress)
          .stroke(Color(ExpressYouTubeColors.accent), style: StrokeStyle(lineWidth: 1.8, lineCap: .round))
          .rotationEffect(.degrees(-90))
        Text("\(Int(ceil(max(0, duration - elapsed))))s")
          .font(.system(size: 12, weight: .bold).monospacedDigit())
          .foregroundColor(Color(ExpressYouTubeColors.text))
      }
      .frame(width: 40, height: 40)
      .onChange(of: progress <= 0) { _, done in
        if done && !finished {
          finished = true
          onFinish()
        }
      }
    }
    .accessibilityLabel("Closes in a few seconds")
  }
}

/// Android's confetti: 100 squares in seven colours, falling.
private struct ExpressConfettiView: View {
  let start: Date

  private struct Piece {
    let x: CGFloat
    let y: CGFloat
    let size: CGFloat
    let color: Color
    let vx: CGFloat
    let vy: CGFloat
    let spin: Double
  }

  @State private var pieces: [Piece] = (0..<100).map { _ in
    let colors: [Color] = [.red, .green, .blue, .yellow, .cyan, Color(red: 1, green: 0, blue: 1), .orange]
    return Piece(
      x: CGFloat.random(in: 0...1),
      y: CGFloat.random(in: -0.3...0),
      size: CGFloat.random(in: 5...12),
      color: colors.randomElement()!,
      vx: CGFloat.random(in: -40...40),
      vy: CGFloat.random(in: 20...160),
      spin: Double.random(in: -6...6)
    )
  }

  var body: some View {
    TimelineView(.animation) { context in
      let t = context.date.timeIntervalSince(start)
      Canvas { canvas, size in
        guard t < 3.5 else { return }
        for piece in pieces {
          // Gravity of 0.25 px per frame², as on Android (at 60 frames a second).
          let x = piece.x * size.width + piece.vx * t
          let y = piece.y * size.height + piece.vy * t + 450 * t * t
          guard y < size.height + 20 else { continue }
          var square = canvas
          square.translateBy(x: x, y: y)
          square.rotate(by: .radians(piece.spin * t))
          square.fill(
            Path(CGRect(x: -piece.size / 2, y: -piece.size / 2, width: piece.size, height: piece.size)),
            with: .color(piece.color)
          )
        }
      }
    }
  }
}

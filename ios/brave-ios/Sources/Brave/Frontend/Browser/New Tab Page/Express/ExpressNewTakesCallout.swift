// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SwiftUI

/// "12 new takes since you last looked" on a feed card (Android's `NewTakesCallout`): the
/// count, chips for the formats they came in (video, photo, voice, text), and the reactions on
/// them. Centred over a photo or video, or in a panel under a text post's caption, where the
/// title is a size smaller.
struct ExpressNewTakesCallout: View {
  let takes: ExpressPost.NewTakes
  let inPanel: Bool

  private static let sub = Color(expressARGB: 0xC7FF_FFFF)
  private static let chipFill = Color(expressARGB: 0x29FF_FFFF)
  private static let chipRing = Color(expressARGB: 0x4DFF_FFFF)
  private static let quality = Color(expressARGB: 0xE6FF_FFFF)

  private enum Format: CaseIterable {
    case video, photo, voice, text
  }

  private func count(_ format: Format) -> Int {
    switch format {
    case .video: return takes.video
    case .photo: return takes.photo
    case .voice: return takes.voice
    case .text: return takes.text
    }
  }

  private func label(_ format: Format, _ n: Int) -> String {
    switch format {
    case .video: return n == 1 ? "1 video" : "\(n) videos"
    case .photo: return n == 1 ? "1 photo" : "\(n) photos"
    case .voice: return "\(n) voice"
    case .text: return "\(n) text"
    }
  }

  private var title: String {
    if takes.count > 99 { return "99+ new takes" }
    return takes.count == 1 ? "1 new take" : "\(takes.count) new takes"
  }

  private var formats: [Format] { Format.allCases.filter { count($0) > 0 } }

  var body: some View {
    let titleSize: CGFloat = inPanel ? 22 : 24
    VStack(spacing: 0) {
      Text(title)
        .font(.system(size: titleSize, weight: .bold))
        .tracking(-0.02 * titleSize)
        .foregroundColor(.white)
        .lineLimit(1)
        .minimumScaleFactor(0.8)
      Text("since you last looked")
        .font(.system(size: 12, weight: .medium))
        .foregroundColor(Self.sub)
        .padding(.top, 2)
      if !formats.isEmpty {
        chips.padding(.top, 9)
      }
      if takes.reactionsTotal > 0 {
        reactionsLine.padding(.top, 9)
      }
    }
    .multilineTextAlignment(.center)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel(
      "\(title) since you last looked, "
        + formats.map { label($0, count($0)) }.joined(separator: ", ")
    )
  }

  /// With all four formats the chips drop their words to fit narrow media.
  private var chips: some View {
    let compact = formats.count == Format.allCases.count
    return HStack(spacing: 6) {
      ForEach(formats, id: \.self) { format in
        HStack(spacing: 5) {
          icon(format)
          Text(compact ? "\(count(format))" : label(format, count(format)))
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(.white)
            .lineLimit(1)
        }
        .padding(.horizontal, compact ? 12 : 10)
        .frame(height: 28)
        .background(
          Capsule()
            .fill(Self.chipFill)
            .overlay(Capsule().strokeBorder(Self.chipRing, lineWidth: 0.5))
        )
      }
    }
  }

  @ViewBuilder private func icon(_ format: Format) -> some View {
    switch format {
    case .video:
      Image(systemName: "video.fill").font(.system(size: 11)).foregroundColor(.white)
    case .photo:
      Image(systemName: "photo.fill").font(.system(size: 11)).foregroundColor(.white)
    case .voice:
      Image(systemName: "waveform").font(.system(size: 11, weight: .semibold))
        .foregroundColor(.white)
    case .text:
      Text("Aa").font(.system(size: 10, weight: .bold)).foregroundColor(.white)
    }
  }

  /// Up to three emoji, then "2.4K reactions on these" with the number in bold white.
  private var reactionsLine: some View {
    HStack(spacing: 6) {
      if !takes.topEmoji.isEmpty {
        HStack(spacing: -2) {
          ForEach(Array(takes.topEmoji.prefix(3).enumerated()), id: \.offset) { _, emoji in
            Text(emoji).font(.system(size: 13)).frame(width: 16, height: 16)
          }
        }
      }
      let number = ExpressNumberFormat.compact(takes.reactionsTotal)
      (Text(number).fontWeight(.bold).foregroundColor(.white)
        + Text(takes.reactionsTotal == 1 ? " reaction on these" : " reactions on these")
        .foregroundColor(Self.quality))
        .font(.system(size: 12, weight: .medium))
        .lineLimit(1)
    }
  }
}

/// The callout over a card's photo or video: a dim with a soft darker middle, a fade at the
/// bottom, and the callout centred 30 pt above the bottom edge.
struct ExpressNewTakesMediaOverlay: View {
  let takes: ExpressPost.NewTakes

  var body: some View {
    ZStack(alignment: .bottom) {
      Color(expressARGB: 0x990A_0C16)
      EllipticalGradient(
        colors: [Color(expressARGB: 0x470A_0C16), Color(expressARGB: 0x000A_0C16)],
        center: UnitPoint(x: 0.5, y: 0.45),
        startRadiusFraction: 0,
        endRadiusFraction: 1.15
      )
      LinearGradient(
        colors: [Color(expressARGB: 0x0006_080B), Color(expressARGB: 0x5906_080B)],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: 56)
      ExpressNewTakesCallout(takes: takes, inPanel: false)
        .padding(.horizontal, 12)
        .padding(.bottom, 30)
    }
    .allowsHitTesting(false)
  }
}

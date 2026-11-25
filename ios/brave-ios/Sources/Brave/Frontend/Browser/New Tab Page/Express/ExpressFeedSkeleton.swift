// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// The feed's first load: placeholder post cards under the header until the first page arrives
/// (Android's `skeleton_shimmer` in `ntp_header.xml`, shown by
/// `PostListAdapter.HeaderViewHolder.showShimmer`). Android's placeholders are list rows
/// (`shimmer_skeleton_item.xml`: a 50 dp circle beside text bars); these take the shape of the
/// feed's own cards (`ExpressPostCardView`), in Android's placeholder colour and shimmer.
struct ExpressFeedSkeleton: View {
  /// A photo or video card, a text card, then another photo or video card: about a phone screen
  /// below the header, as Android's ten rows are.
  private static let cardsHaveMedia = [true, false, true]
  /// The feed's spacing between cards (`ExpressFeedSectionProvider`).
  private static let cardSpacing: CGFloat = 4

  var body: some View {
    VStack(spacing: Self.cardSpacing) {
      ForEach(Self.cardsHaveMedia.indices, id: \.self) { index in
        ExpressFeedSkeletonCard(hasMedia: Self.cardsHaveMedia[index])
      }
    }
    .frame(maxWidth: .infinity)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Loading posts")
  }

  /// The skeleton's height at `width`, for the feed's size estimate.
  static func height(width: CGFloat) -> CGFloat {
    let cards = cardsHaveMedia.reduce(CGFloat(0)) {
      $0 + ExpressFeedSkeletonCard.height(width: width, hasMedia: $1)
    }
    return cards + cardSpacing * CGFloat(cardsHaveMedia.count - 1)
  }
}

/// One placeholder card, laid out as `ExpressPostCardView`: the author row, the caption, the
/// media of a photo or video post, and the reactions rail.
private struct ExpressFeedSkeletonCard: View {
  let hasMedia: Bool

  /// Android's placeholder colour (`wallet_secondary_text_color`, also `circle_shimmer`'s fill).
  private static let bone = Color(UIColor(expressLight: 0x686978, expressDark: 0x84889C))
  private static let padding: CGFloat = 10
  private static let avatarSize: CGFloat = 16
  private static let captionTop: CGFloat = 10
  private static let lineHeight: CGFloat = 12
  private static let lineSpacing: CGFloat = 6
  private static let mediaTop: CGFloat = 12
  private static let railHeight: CGFloat = 32

  /// The caption's lines, as shares of the card's width.
  private static func captionLines(hasMedia: Bool) -> [CGFloat] {
    hasMedia ? [1, 0.55] : [1, 1, 0.7]
  }

  /// The rail sits closer under media, as on the real card.
  private static func railTop(hasMedia: Bool) -> CGFloat {
    hasMedia ? 8 : 16
  }

  var body: some View {
    let lines = Self.captionLines(hasMedia: hasMedia)
    VStack(alignment: .leading, spacing: 0) {
      HStack(spacing: 6) {
        Circle()
          .fill(Self.bone)
          .frame(width: Self.avatarSize, height: Self.avatarSize)
        bar(width: 96, height: 10)
        bar(width: 36, height: 8)
          .padding(.leading, 2)
      }
      VStack(alignment: .leading, spacing: Self.lineSpacing) {
        ForEach(lines.indices, id: \.self) { index in
          line(share: lines[index])
        }
      }
      .padding(.top, Self.captionTop)
      if hasMedia {
        RoundedRectangle(cornerRadius: 12)
          .fill(Self.bone)
          .aspectRatio(16 / 9, contentMode: .fit)
          .frame(maxWidth: .infinity)
          .padding(.top, Self.mediaTop)
      }
      RoundedRectangle(cornerRadius: 12)
        .fill(Self.bone)
        .frame(height: Self.railHeight)
        .padding(.top, Self.railTop(hasMedia: hasMedia))
    }
    .modifier(ExpressFeedShimmer())
    .padding(Self.padding)
    .frame(maxWidth: .infinity, alignment: .leading)
    .background(RoundedRectangle(cornerRadius: 4).fill(Color(ExpressColors.postCard)))
  }

  private func bar(width: CGFloat, height: CGFloat) -> some View {
    RoundedRectangle(cornerRadius: 3)
      .fill(Self.bone)
      .frame(width: width, height: height)
  }

  /// A caption line `share` of the card's width.
  private func line(share: CGFloat) -> some View {
    GeometryReader { proxy in
      RoundedRectangle(cornerRadius: 3)
        .fill(Self.bone)
        .frame(width: proxy.size.width * share)
    }
    .frame(height: Self.lineHeight)
  }

  static func height(width: CGFloat, hasMedia: Bool) -> CGFloat {
    let lines = CGFloat(captionLines(hasMedia: hasMedia).count)
    var height = padding * 2 + avatarSize + captionTop
    height += lines * lineHeight + (lines - 1) * lineSpacing
    if hasMedia {
      height += mediaTop + (width - padding * 2) * 9 / 16
    }
    return height + railTop(hasMedia: hasMedia) + railHeight
  }
}

/// Android's `ShimmerFrameLayout` defaults (`Shimmer.java`): the placeholders at 30 %, rising to
/// full strength in a band as wide as the view (dropoff 0.5), tilted 20° and sweeping left to
/// right once a second. With Reduce Motion on they stay still at 30 %.
private struct ExpressFeedShimmer: ViewModifier {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  /// `shimmer_base_alpha` and `animationDuration`.
  private static let baseAlpha = 0.3
  private static let duration: TimeInterval = 1

  @ViewBuilder
  func body(content: Content) -> some View {
    if reduceMotion {
      content.opacity(Self.baseAlpha)
    } else {
      content.mask {
        TimelineView(.animation) { context in
          let phase =
            context.date.timeIntervalSinceReferenceDate
            .truncatingRemainder(dividingBy: Self.duration) / Self.duration
          ExpressFeedShimmerMask(phase: phase, baseAlpha: Self.baseAlpha)
        }
      }
    }
  }
}

/// The shimmer's strength at `phase` (0 to 1): the base everywhere, plus the band, which travels
/// from a width left of the view to a width right of it, so it loops unseen.
private struct ExpressFeedShimmerMask: View {
  let phase: Double
  let baseAlpha: Double

  var body: some View {
    GeometryReader { proxy in
      let width = proxy.size.width
      Color.black.opacity(baseAlpha)
        .overlay {
          LinearGradient(
            stops: [
              .init(color: .clear, location: 0.25),
              .init(color: .black, location: 0.5),
              .init(color: .clear, location: 0.75),
            ],
            startPoint: .leading,
            endPoint: .trailing
          )
          // Tall enough to cover the view once tilted.
          .frame(width: width, height: proxy.size.height + width * 2)
          .rotationEffect(.degrees(20))
          .offset(x: CGFloat(phase * 2 - 1) * width)
        }
    }
  }
}

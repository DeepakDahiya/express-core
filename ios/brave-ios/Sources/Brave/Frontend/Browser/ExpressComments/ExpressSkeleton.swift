// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// The rows shown while comments load, instead of a spinner: Android's `shimmer_skeleton_item`
// rows in a `ShimmerFrameLayout` (`CommentListFragment`, `ReplyListFragment` and
// `CommentReelFragment`).

extension ExpressColors {
  /// The skeleton's circle and bars (Android's `wallet_secondary_text_color`).
  static let skeleton = UIColor(expressLight: 0x686978, expressDark: 0x84889C)
}

/// A column of skeleton rows under one shimmer, like Android's, which sizes the column to the
/// screen. Static with Reduce Motion on.
struct ExpressSkeletonList: View {
  var rows: Int

  /// One row with its 8 pt above and below.
  static let rowHeight: CGFloat = 66

  /// Enough rows to fill `height` (the space the list has).
  static func rows(toFill height: CGFloat) -> Int {
    guard height.isFinite, height > 0 else { return 6 }
    return max(1, Int((height / rowHeight).rounded(.up)))
  }

  var body: some View {
    VStack(spacing: 0) {
      ForEach(0..<max(rows, 1), id: \.self) { _ in
        ExpressSkeletonRow()
      }
    }
    .expressShimmer()
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Loading comments")
  }
}

/// One row (Android's `shimmer_skeleton_item`): a 50 pt circle, two bars beside it (180 and
/// 120 pt) and two short ones at the trailing edge (60 and 40 pt).
struct ExpressSkeletonRow: View {
  var body: some View {
    HStack(alignment: .top, spacing: 0) {
      Circle()
        .frame(width: 50, height: 50)
      VStack(alignment: .leading, spacing: 4) {
        Rectangle()
          .frame(maxWidth: 180)
          .frame(height: 19)
        Rectangle()
          .frame(maxWidth: 120)
          .frame(height: 16)
      }
      .padding(.leading, 16)
      .padding(.trailing, 8)
      // Takes its full width before the spacer gets any (narrow phones shorten the bars).
      .layoutPriority(1)
      Spacer(minLength: 0)
      VStack(alignment: .trailing, spacing: 4) {
        Rectangle()
          .frame(width: 60, height: 19)
        Rectangle()
          .frame(width: 40, height: 19)
      }
    }
    .foregroundStyle(Color(ExpressColors.skeleton))
    .padding(.vertical, 8)
  }
}

extension View {
  /// Android's `ShimmerFrameLayout` defaults: the content at 30 % with a full-strength band,
  /// tilted 20°, sweeping left to right once a second. With Reduce Motion on it stays still at
  /// 30 %.
  func expressShimmer() -> some View {
    modifier(ExpressShimmer())
  }
}

private struct ExpressShimmer: ViewModifier {
  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  /// Android's `shimmer_base_alpha` and `animationDuration`.
  private static let baseAlpha = 0.3
  private static let duration = 1.0

  func body(content: Content) -> some View {
    if reduceMotion {
      content.opacity(Self.baseAlpha)
    } else {
      content.mask {
        TimelineView(.animation) { timeline in
          let phase =
            timeline.date.timeIntervalSinceReferenceDate
            .truncatingRemainder(dividingBy: Self.duration) / Self.duration
          ExpressShimmerMask(phase: phase, baseAlpha: Self.baseAlpha)
        }
      }
    }
  }
}

/// The shimmer's alpha at `phase` (0...1): the base everywhere, rising to full in a band as wide
/// as the view (Android's dropoff 0.5), which travels from one width left of the view to one
/// width right of it.
private struct ExpressShimmerMask: View {
  let phase: Double
  let baseAlpha: Double

  var body: some View {
    GeometryReader { proxy in
      let width = proxy.size.width
      let height = proxy.size.height
      ZStack {
        Color.black.opacity(baseAlpha)
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
        .frame(width: width, height: height + width * 2)
        .rotationEffect(.degrees(20))
        .offset(x: (phase * 2 - 1) * width)
      }
      .frame(width: width, height: height)
    }
  }
}

// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import ExpressCore
import SwiftUI
import UIKit

/// Express Browser colours, taken from the Android app's `browser_express_colors.xml`
/// (light) and `values-night` (dark).
enum ExpressColors {
  static let feedBackground = UIColor(expressLight: 0xE8E7EF, expressDark: 0x121318)
  static let postCard = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let postAuthor = UIColor(expressLight: 0x676767, expressDark: 0xA8ABB5)
  static let postTime = UIColor(expressLight: 0x999999, expressDark: 0xA8ABB5)
  static let postCaption = UIColor(expressLight: 0x111111, expressDark: 0xF4F5F7)
  static let postMore = UIColor(expressLight: 0x1A6EFF, expressDark: 0x6EA8FF)
  static let postRail = UIColor(expressLight: 0xF6F6F6, expressDark: 0x232428)
  static let postRailText = UIColor(expressLight: 0x666666, expressDark: 0xFFFFFF, alpha: 0.98)
  static let postRailXP = UIColor(expressLight: 0x747480, expressDark: 0xFFFFFF, alpha: 0.72)
  static let postRailChevron = UIColor(expressLight: 0x666666, expressDark: 0x666666)
  static let avatarPlaceholder = UIColor(expressLight: 0xCEC6FF, expressDark: 0xCEC6FF)
  static let newTakesPanel = UIColor(expressLight: 0x63646D, expressDark: 0x0D0F17)
  static let ntpField = UIColor(expressLight: 0xD0D3E2, expressDark: 0x313747)
  static let ntpOnField = UIColor(expressLight: 0x1A1B20, expressDark: 0xE2E3E8)
}

extension UIColor {
  convenience init(expressLight light: UInt32, expressDark dark: UInt32, alpha: CGFloat = 1) {
    func rgb(_ hex: UInt32) -> UIColor {
      UIColor(
        red: CGFloat((hex >> 16) & 0xFF) / 255,
        green: CGFloat((hex >> 8) & 0xFF) / 255,
        blue: CGFloat(hex & 0xFF) / 255,
        alpha: alpha
      )
    }
    self.init { $0.userInterfaceStyle == .dark ? rgb(dark) : rgb(light) }
  }
}

/// Compact counts: 999, 1.2K, 3.4M.
enum ExpressNumberFormat {
  static func compact(_ value: Int) -> String {
    switch value {
    case ..<1000: return "\(value)"
    case ..<1_000_000: return trimmed(Double(value) / 1000) + "K"
    default: return trimmed(Double(value) / 1_000_000) + "M"
    }
  }

  private static func trimmed(_ value: Double) -> String {
    let rounded = (value * 10).rounded() / 10
    return rounded == rounded.rounded() ? String(Int(rounded)) : String(format: "%.1f", rounded)
  }
}

// MARK: - Post card

struct ExpressPostCardView: View {
  let post: ExpressPost
  let isHidden: Bool
  let isExpanded: Bool
  /// "12 new takes" shows until the reader opens them.
  var showsNewTakes = true
  /// The media shape (server's or probed); 16:9 until known.
  var mediaAspectRatio: CGFloat? = nil
  /// The feed's shared video player; video posts autoplay through it.
  var videos: ExpressFeedVideoPlayback? = nil
  var onOpen: () -> Void
  /// The reaction rail: always the comments.
  var onOpenRail: (() -> Void)? = nil
  var onHide: () -> Void
  var onUndo: () -> Void
  var onToggleExpanded: () -> Void

  private var newTakes: ExpressPost.NewTakes? {
    showsNewTakes ? post.newTakes : nil
  }

  var body: some View {
    if isHidden {
      hiddenRow
    } else {
      card
    }
  }

  private var hiddenRow: some View {
    HStack {
      Text("Post hidden")
        .font(.system(size: 13))
        .foregroundColor(Color(ExpressColors.postAuthor))
      Spacer()
      Button("Undo", action: onUndo)
        .font(.system(size: 13, weight: .medium))
        .foregroundColor(Color(ExpressColors.postMore))
        .frame(minHeight: 48)
        .padding(.horizontal, 12)
    }
    .padding(.leading, 14)
    .frame(minHeight: 48)
    .background(RoundedRectangle(cornerRadius: 4).fill(Color(ExpressColors.postCard)))
  }

  private var card: some View {
    let voice = ExpressFeedVoice.of(post)
    // Android's rail sits on the media when the media ends the card; a voice row ends it instead.
    let railFollowsMedia = post.hasMedia && voice == nil
    return ZStack(alignment: .topTrailing) {
      VStack(alignment: .leading, spacing: 0) {
        authorRow
          .padding(.trailing, 26)
        if let caption = post.caption {
          captionView(caption)
            .padding(.top, 10)
        }
        // On a photo or video the callout sits on the media; on a text post it's a panel.
        if let newTakes, !post.hasMedia {
          newTakesPanel(newTakes)
            .padding(.top, 10)
        }
        if post.hasMedia {
          mediaView
            .padding(.top, 12)
        }
        // A voice post's player, under the media if it has any (Android's `post_audio`).
        if let voice {
          ExpressFeedVoicePostRow(voice: voice, feed: videos)
            .id(voice.url)
            .padding(.top, 12)
        }
        rail
          .padding(.top, railFollowsMedia ? 8 : (newTakes == nil ? 16 : 10))
          .contentShape(Rectangle())
          .onTapGesture { (onOpenRail ?? onOpen)() }
      }
      .padding(10)
      .frame(maxWidth: .infinity, alignment: .leading)
      .background(RoundedRectangle(cornerRadius: 4).fill(Color(ExpressColors.postCard)))
      .contentShape(Rectangle())
      .onTapGesture(perform: onOpen)

      Menu {
        Button("Not interested", action: onHide)
      } label: {
        Image(systemName: "ellipsis")
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(Color(ExpressColors.postTime))
          .frame(width: 44, height: 40)
          .contentShape(Rectangle())
      }
      .accessibilityLabel("More options")
    }
  }

  private var authorRow: some View {
    HStack(spacing: 6) {
      avatar
      Text(post.authorName)
        .font(.system(size: 12))
        .foregroundColor(Color(ExpressColors.postAuthor))
        .lineLimit(1)
      if let date = post.createdDate {
        Text(ExpressRelativeTime.string(from: date))
          .font(.system(size: 11))
          .foregroundColor(Color(ExpressColors.postTime))
          .padding(.leading, 2)
          .lineLimit(1)
      }
    }
  }

  @ViewBuilder private var avatar: some View {
    let placeholder = Circle().fill(Color(ExpressColors.avatarPlaceholder))
    if let url = post.avatarURL {
      WebImageReader(url: url) { image in
        if let image {
          Image(uiImage: image).resizable().scaledToFill()
        } else {
          placeholder
        }
      }
      .frame(width: 16, height: 16)
      .clipShape(Circle())
    } else {
      placeholder.frame(width: 16, height: 16)
    }
  }

  /// Captions are cut at 6 lines with "More", like Android.
  private func captionView(_ caption: String) -> some View {
    ExpressFeedCaption(
      caption: caption,
      isExpanded: isExpanded,
      onToggle: onToggleExpanded,
      onOpen: onOpen
    )
  }

  private func newTakesPanel(_ newTakes: ExpressPost.NewTakes) -> some View {
    ExpressNewTakesCallout(takes: newTakes, inPanel: true)
      .padding(EdgeInsets(top: 12, leading: 16, bottom: 14, trailing: 12))
      .frame(maxWidth: .infinity)
      .background(RoundedRectangle(cornerRadius: 12).fill(Color(ExpressColors.newTakesPanel)))
  }

  private var mediaView: some View {
    // Fixed-shape frame with the media as an overlay, so the image can't resize the card.
    Color(ExpressColors.postRail)
      .aspectRatio(mediaAspectRatio ?? post.mediaAspectRatio ?? (16 / 9), contentMode: .fit)
      .frame(maxWidth: .infinity)
      .overlay {
        // The image doubles as the video's poster.
        if let imageURL = post.mediaImageURL {
          WebImageReader(url: imageURL) { image in
            if let image {
              Image(uiImage: image).resizable().scaledToFill()
            } else {
              Color.clear
            }
          }
        }
      }
      .overlay {
        if let videoURL = post.mediaVideoURL {
          if let videos {
            ExpressFeedVideoOverlay(
              playback: videos,
              postID: post.id,
              videoURL: videoURL,
              showsPlayMark: newTakes == nil
            )
          } else if newTakes == nil {
            ExpressFeedPlayIcon()
          }
        }
      }
      .overlay {
        if let newTakes {
          ExpressNewTakesMediaOverlay(takes: newTakes)
        }
      }
      .clipShape(RoundedRectangle(cornerRadius: 12))
  }

  /// "N reactions" (the comment count, as on Android), the top emoji, XP and a chevron.
  /// While new takes show it steps back: smaller, unfilled and faded, emoji desaturated.
  private var rail: some View {
    let quiet = newTakes != nil
    return HStack(spacing: 8) {
      Text(
        post.commentCount > 0
          ? "\(ExpressNumberFormat.compact(post.commentCount)) reactions"
          : "Start the conversation"
      )
      .font(.system(size: quiet ? 11 : 12, weight: quiet ? .medium : .semibold))
      .foregroundColor(Color(quiet ? ExpressColors.postAuthor : ExpressColors.postRailText))
      .lineLimit(1)
      if post.reactionCount > 0, !post.topReactionEmoji.isEmpty {
        HStack(spacing: quiet ? -4 : -5) {
          ForEach(Array(post.topReactionEmoji.enumerated()), id: \.offset) { _, emoji in
            Text(emoji)
              .font(.system(size: quiet ? 10 : 12))
              .frame(width: quiet ? 12 : 16, height: quiet ? 12 : 16)
          }
        }
        .saturation(quiet ? 0.4 : 1)
        .opacity(0.86)
      }
      if post.reactionCount > 0 {
        Text("\(ExpressNumberFormat.compact(post.reactionCount)) XP")
          .font(.system(size: quiet ? 10 : 11, weight: .medium))
          .foregroundColor(Color(quiet ? ExpressColors.postAuthor : ExpressColors.postRailXP))
          .lineLimit(1)
      }
      Spacer(minLength: 0)
      Image(systemName: "chevron.right")
        .font(.system(size: quiet ? 10 : 12, weight: .semibold))
        .foregroundColor(Color(quiet ? ExpressColors.postAuthor : ExpressColors.postRailChevron))
        .frame(width: quiet ? 20 : 24, height: quiet ? 20 : 24)
        .opacity(0.9)
    }
    .padding(EdgeInsets(top: quiet ? 0 : 4, leading: 10, bottom: quiet ? 0 : 4, trailing: 5))
    .frame(minHeight: quiet ? 20 : nil)
    .background(
      RoundedRectangle(cornerRadius: 12).fill(quiet ? Color.clear : Color(ExpressColors.postRail))
    )
    .opacity(quiet ? 0.65 : 1)
  }
}

// MARK: - Caption

/// A post's caption, cut at 6 lines (Android's `bindCaption`/`collapseCaption`). Whether the cut
/// loses anything is measured, not guessed: the caption is laid out again, hidden and at the
/// same width, once in full and once at 6 lines, and "More" shows only when the full one is
/// taller. A tap on a long caption opens or folds it, as on Android; on one that fits, the tap
/// opens the post like the rest of the card.
private struct ExpressFeedCaption: View {
  static let maxLines = 6

  let caption: String
  let isExpanded: Bool
  /// Opens or folds the caption (the card's `onToggleExpanded`, which sends the expand event).
  var onToggle: () -> Void
  var onOpen: () -> Void

  @State private var fullHeight: CGFloat = 0
  @State private var cutHeight: CGFloat = 0

  /// More than 6 lines at this width (with half a point for rounding).
  private var isLong: Bool { fullHeight > cutHeight + 0.5 }

  var body: some View {
    VStack(alignment: .leading, spacing: 4) {
      text
        .lineLimit(isExpanded ? nil : Self.maxLines)
        .fixedSize(horizontal: false, vertical: true)
        // The whole width, so the hidden copies wrap exactly like the shown text.
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(alignment: .topLeading) { measurements }
        .contentShape(Rectangle())
        .onTapGesture {
          if isLong { onToggle() } else { onOpen() }
        }
      if isLong {
        Button(isExpanded ? "Less" : "More", action: onToggle)
          .font(.system(size: 14, weight: .medium))
          .foregroundColor(Color(ExpressColors.postMore))
      }
    }
  }

  private var text: Text {
    Text(caption)
      .font(.system(size: 14))
      .foregroundColor(Color(ExpressColors.postCaption))
  }

  /// The caption at 6 lines and in full, never drawn; a background doesn't change the card's size.
  private var measurements: some View {
    ZStack(alignment: .topLeading) {
      text
        .lineLimit(Self.maxLines)
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { cutHeight = $0 }
      text
        .fixedSize(horizontal: false, vertical: true)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
    }
    .hidden()
    .allowsHitTesting(false)
    .accessibilityHidden(true)
  }
}

// MARK: - Video

/// Android's 64 pt play mark, shown while a video isn't playing.
struct ExpressFeedPlayIcon: View {
  var body: some View {
    Image(systemName: "play.circle.fill")
      .font(.system(size: 64))
      .foregroundStyle(.white.opacity(0.92))
      .shadow(radius: 6)
      .accessibilityLabel("Video")
      .allowsHitTesting(false)
  }
}

/// The playing video over the poster, the play mark while it isn't playing, and the speaker
/// button. Reports the video box's place on screen so the feed can pick the video to play.
private struct ExpressFeedVideoOverlay: View {
  @ObservedObject var playback: ExpressFeedVideoPlayback
  let postID: String
  let videoURL: URL
  /// Hidden while the "new takes" callout covers the video.
  var showsPlayMark = true

  private var isPlayingHere: Bool {
    playback.playingID == postID && playback.isPlaying
  }

  var body: some View {
    ZStack {
      ExpressFeedPlayerView(playback: playback, postID: postID)
        .opacity(playback.playingID == postID ? 1 : 0)
      if !isPlayingHere, showsPlayMark {
        ExpressFeedPlayIcon()
      }
    }
    .overlay(alignment: .topTrailing) {
      if playback.playingID == postID {
        Button {
          playback.toggleSound()
        } label: {
          Image(systemName: playback.isSoundOn ? "speaker.wave.2.fill" : "speaker.slash.fill")
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(.white)
            .frame(width: 28, height: 28)
            .background(Circle().fill(Color.black.opacity(0.5)))
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .padding(2)
        .accessibilityLabel(playback.isSoundOn ? "Mute videos" : "Unmute videos")
      }
    }
    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
      playback.report(postID, url: videoURL, frame: frame)
    }
    .onAppear { ExpressFeedVideoCards.appeared(postID, in: playback) }
    .onDisappear { ExpressFeedVideoCards.disappeared(postID, in: playback) }
  }
}

/// How many cards show each post's video, per feed player. When a refreshed first page moves a
/// post to a cell that updates before the cell it left, the card there appears (and reports
/// where the video is) before the old one disappears, which mustn't drop the video then.
@MainActor
private enum ExpressFeedVideoCards {
  private struct Key: Hashable {
    let playback: ObjectIdentifier
    let postID: String
  }

  private static var shown: [Key: Int] = [:]

  static func appeared(_ postID: String, in playback: ExpressFeedVideoPlayback) {
    shown[Key(playback: ObjectIdentifier(playback), postID: postID), default: 0] += 1
  }

  /// The video stops being a candidate once no card shows it.
  static func disappeared(_ postID: String, in playback: ExpressFeedVideoPlayback) {
    let key = Key(playback: ObjectIdentifier(playback), postID: postID)
    let remaining = (shown[key] ?? 1) - 1
    shown[key] = remaining > 0 ? remaining : nil
    if remaining <= 0 {
      playback.remove(postID)
    }
  }
}

// MARK: - Footer

struct ExpressFeedFooterView: View {
  let state: ExpressFeedDataSource.LoadState
  let hasPosts: Bool
  var onRetry: () -> Void

  var body: some View {
    Group {
      switch state {
      case .loading where !hasPosts:
        // The first page loads under placeholder cards (Android's header skeleton); later
        // pages under the spinner.
        ExpressFeedSkeleton()
      case .loading:
        ProgressView().tint(Color(ExpressColors.postMore))
      case .failed:
        Button(action: onRetry) {
          VStack(spacing: 4) {
            Text("Couldn’t load posts")
              .foregroundColor(Color(ExpressColors.postAuthor))
            Text("Tap to try again")
              .fontWeight(.bold)
              .foregroundColor(Color(ExpressColors.postMore))
          }
          .font(.system(size: 14))
        }
        .buttonStyle(.plain)
      case .endReached where !hasPosts:
        Text("No posts yet")
          .font(.system(size: 14))
          .foregroundColor(Color(ExpressColors.postAuthor))
      default:
        Color.clear
      }
    }
    .frame(maxWidth: .infinity, minHeight: 72)
  }
}

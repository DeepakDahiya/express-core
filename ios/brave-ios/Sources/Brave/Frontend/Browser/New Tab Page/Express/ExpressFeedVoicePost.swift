// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SwiftUI
import UIKit

// Voice posts in the new tab page feed, after the Android app's `post_audio` row in
// `browser_express_post.xml` and `PostListAdapter.bindAudio`/`playAudio`/`pauseAudio`.

extension ExpressColors {
  static let postAudioAccent = UIColor(expressLight: 0x577DFF, expressDark: 0x577DFF)
  static let postAudioStroke = UIColor(expressLight: 0xE7E7E7, expressDark: 0x3A3D45)
  /// Android has no night value for the time, so it stays #747480 in both.
  static let postAudioTime = UIColor(expressLight: 0x747480, expressDark: 0x747480)
}

/// A voice post's audio, as the feed card plays it.
struct ExpressFeedVoice: Hashable {
  let url: URL
  /// The server's length; 0 when it doesn't know.
  let durationMs: Int

  /// The post's voice note (Android's `Post.getAudioUrl`), or nil for any other post.
  @MainActor static func of(_ post: ExpressPost) -> ExpressFeedVoice? {
    if let audio = post.audio {
      return ExpressFeedVoice(url: audio.url, durationMs: audio.durationMs)
    }
    #if DEBUG
    return debugSamples[post.id]
    #else
    return nil
    #endif
  }

  #if DEBUG
  /// Debug builds: posts turned into voice posts by `-ExpressSampleVoicePostURL`.
  @MainActor static var debugSamples: [String: ExpressFeedVoice] = [:]

  /// `-ExpressSampleVoicePostURL <url>`: the sample voice post's audio. An http(s) or file URL,
  /// or a plain absolute path.
  static var debugSampleURL: URL? {
    guard
      let raw = UserDefaults.standard.string(forKey: "ExpressSampleVoicePostURL")?
        .trimmingCharacters(in: .whitespacesAndNewlines),
      !raw.isEmpty
    else { return nil }
    return raw.hasPrefix("/") ? URL(fileURLWithPath: raw) : URL(string: raw)
  }
  #endif
}

// MARK: - Player row

/// A voice post's player (Android's 64 dp `post_audio`, Figma "Express/Audio player"): play/pause,
/// a seek bar and the time, which shows the length until the post has played some, then the
/// place. One voice note plays at a time, app-wide (`ExpressAudioPlayback`), and feed videos go
/// silent while it does. Like each Android card's own player, the row keeps its place when another
/// voice post takes over, and a scrub before playing only sets where it will start.
struct ExpressFeedVoicePostRow: View {
  let voice: ExpressFeedVoice
  /// The feed's playback, which pauses the post once it scrolls off or the feed is covered.
  var feed: ExpressFeedVideoPlayback?

  @ObservedObject private var playback = ExpressAudioPlayback.shared
  /// Where this post stands while it isn't the note in the player.
  @State private var heldPosition: TimeInterval = 0
  /// The real length, once the player has read it.
  @State private var learnedDuration: TimeInterval = 0
  /// The seek bar's place (0…1) while a finger moves it.
  @State private var scrubFraction: Double?

  private var isCurrent: Bool { playback.currentURL == voice.url }
  private var isPlaying: Bool { isCurrent && playback.isPlaying }

  /// Android's `audioDurationMs()`: the player's length once known, else the server's.
  private var total: TimeInterval {
    if isCurrent, playback.duration > 0 { return playback.duration }
    if learnedDuration > 0 { return learnedDuration }
    return Double(voice.durationMs) / 1000
  }

  private var position: TimeInterval { isCurrent ? playback.position : heldPosition }

  private var fraction: Double {
    if let scrubFraction { return scrubFraction }
    guard total > 0 else { return 0 }
    return min(max(position / total, 0), 1)
  }

  /// The place while under way, else the length; the scrubbed place while dragging.
  private var timeText: String {
    let shown = scrubFraction.map { $0 * total } ?? (position > 0 ? position : total)
    return ExpressDraftFactory.durationText(milliseconds: Int(max(0, shown) * 1000))
  }

  var body: some View {
    HStack(spacing: 0) {
      Button(action: togglePlay) {
        ExpressFeedVoiceButtonGlyph(isPlaying: isPlaying)
          .frame(width: 43, height: 43)
          .contentShape(Circle())
      }
      .buttonStyle(.plain)
      .accessibilityLabel(isPlaying ? "Pause audio" : "Play audio")

      seekBar
        .padding(.horizontal, 10)

      Text(timeText)
        .font(.system(size: 12, weight: .semibold).monospacedDigit())
        .foregroundColor(Color(ExpressColors.postAudioTime))
        .lineLimit(1)
        .fixedSize()
    }
    .padding(.horizontal, 12)
    .frame(maxWidth: .infinity)
    .frame(height: 64)
    .background(
      RoundedRectangle(cornerRadius: 16)
        .fill(Color(ExpressColors.postCard))
        .overlay(
          RoundedRectangle(cornerRadius: 16)
            .strokeBorder(Color(ExpressColors.postAudioStroke), lineWidth: 1)
        )
    )
    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
      feed?.reportVoice(voice.url, frame: frame)
    }
    .onDisappear { feed?.removeVoice(voice.url) }
    .onChange(of: playback.position) { _, newValue in
      if isCurrent { heldPosition = newValue }
    }
    .onChange(of: playback.duration) { _, newValue in
      if isCurrent, newValue > 0 { learnedDuration = newValue }
    }
  }

  /// Track 4 pt, inset 8 pt each side so the 16 pt thumb fits at the ends (Android's SeekBar
  /// padding).
  private var seekBar: some View {
    GeometryReader { proxy in
      let inset: CGFloat = 8
      let track = max(proxy.size.width - inset * 2, 1)
      let accent = Color(ExpressColors.postAudioAccent)
      ZStack(alignment: .leading) {
        RoundedRectangle(cornerRadius: 2)
          .fill(Color(ExpressColors.postAudioStroke))
          .frame(width: track, height: 4)
        RoundedRectangle(cornerRadius: 2)
          .fill(accent)
          .frame(width: track * fraction, height: 4)
        Circle()
          .fill(accent)
          .frame(width: 16, height: 16)
          .offset(x: track * fraction - 8)
      }
      .padding(.horizontal, inset)
      .frame(width: proxy.size.width, height: proxy.size.height)
      .overlay {
        ExpressFeedScrubArea(
          inset: inset,
          onChanged: { scrubFraction = $0 },
          onEnded: { finishScrub(at: $0) }
        )
      }
      .contentShape(Rectangle())
      // A tap on the bar jumps there, and doesn't open the post.
      .gesture(
        SpatialTapGesture().onEnded { value in
          finishScrub(at: Double(min(max((value.location.x - inset) / track, 0), 1)))
        }
      )
    }
    .frame(height: 32)
    .accessibilityElement()
    .accessibilityLabel("Audio position")
    .accessibilityValue(timeText)
    .accessibilityAdjustableAction { direction in
      let step = direction == .increment ? 0.1 : -0.1
      finishScrub(at: min(max(fraction + step, 0), 1))
    }
  }

  private func togglePlay() {
    if isCurrent {
      playback.toggle(voice.url)
    } else {
      // Pick up where this post was, or from the top once it had played to the end.
      let start = total > 0 && heldPosition < total ? heldPosition : 0
      playback.toggle(voice.url, startingAt: start)
    }
  }

  /// Android's `onStopTrackingTouch`: the player moves there, without starting. Nil: cancelled.
  private func finishScrub(at target: Double?) {
    scrubFraction = nil
    guard let target else { return }
    if isCurrent, playback.duration > 0 {
      playback.seek(voice.url, to: target)
    } else if !isCurrent, total > 0 {
      heldPosition = target * total
    }
  }
}

/// Android's play and pause discs (`ic_browser_express_post_audio_play` and `_pause`), drawn from
/// the same 43.15-unit paths.
private struct ExpressFeedVoiceButtonGlyph: View {
  let isPlaying: Bool

  var body: some View {
    let accent = Color(ExpressColors.postAudioAccent)
    let isPlaying = self.isPlaying
    return Canvas { context, size in
      let side: CGFloat = 43.1492
      let scale = min(size.width, size.height) / side
      context.fill(
        Path(ellipseIn: CGRect(x: 0, y: 0, width: side * scale, height: side * scale)),
        with: .color(accent)
      )
      var mark = Path()
      if isPlaying {
        mark.addRect(CGRect(x: 15.5, y: 14, width: 4.2, height: 15.15))
        mark.addRect(CGRect(x: 23.45, y: 14, width: 4.2, height: 15.15))
      } else {
        mark.move(to: CGPoint(x: 30.4253, y: 21.5745))
        mark.addLine(to: CGPoint(x: 17.1487, y: 29.2398))
        mark.addLine(to: CGPoint(x: 17.1487, y: 13.9093))
        mark.closeSubpath()
      }
      context.fill(mark.applying(CGAffineTransform(scaleX: scale, y: scale)), with: .color(.white))
    }
    .accessibilityHidden(true)
  }
}

/// The seek bar's drag area. A drag only starts sideways, so a vertical swipe that starts on the
/// bar still scrolls the feed, as Android's SeekBar does in a list. It reports the place along
/// the track (0…1), and nil when the drag is cancelled.
private struct ExpressFeedScrubArea: UIViewRepresentable {
  /// The track's distance from each side of the area.
  var inset: CGFloat
  var onChanged: (Double) -> Void
  var onEnded: (Double?) -> Void

  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeUIView(context: Context) -> UIView {
    let view = UIView()
    view.backgroundColor = .clear
    let pan = UIPanGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.panned(_:))
    )
    pan.delegate = context.coordinator
    view.addGestureRecognizer(pan)
    let tap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.tapped(_:))
    )
    view.addGestureRecognizer(tap)
    return view
  }

  func updateUIView(_ view: UIView, context: Context) {
    context.coordinator.inset = inset
    context.coordinator.onChanged = onChanged
    context.coordinator.onEnded = onEnded
  }

  @MainActor
  final class Coordinator: NSObject, UIGestureRecognizerDelegate {
    var inset: CGFloat = 0
    var onChanged: (Double) -> Void = { _ in }
    var onEnded: (Double?) -> Void = { _ in }

    private func fraction(_ recognizer: UIGestureRecognizer) -> Double {
      guard let view = recognizer.view else { return 0 }
      let width = max(view.bounds.width - inset * 2, 1)
      let x = recognizer.location(in: view).x - inset
      return Double(min(max(x / width, 0), 1))
    }

    @objc func panned(_ recognizer: UIPanGestureRecognizer) {
      switch recognizer.state {
      case .began, .changed:
        onChanged(fraction(recognizer))
      case .ended:
        onEnded(fraction(recognizer))
      case .cancelled, .failed:
        onEnded(nil)
      default:
        break
      }
    }

    @objc func tapped(_ recognizer: UITapGestureRecognizer) {
      guard recognizer.state == .ended else { return }
      onEnded(fraction(recognizer))
    }

    func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
      guard let pan = recognizer as? UIPanGestureRecognizer else { return true }
      let velocity = pan.velocity(in: pan.view)
      return abs(velocity.x) > abs(velocity.y)
    }
  }
}

// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import ExpressCore
import SwiftUI
import UIKit

/// What the full-screen player needs from a feed card.
struct ExpressPostVideoItem {
  let postID: String
  let videoURL: URL
  let author: String
  let caption: String?
  let commentCount: Int
  let reactionCount: Int
  let topReactionEmoji: [String]
  /// What ⋯ → Share sends: the original post, else the post's link, else the video.
  let shareURL: URL
  /// Where the card was (seconds), when it was playing with sound; otherwise it starts over.
  let startSeconds: Double?

  init(post: ExpressPost, videoURL: URL, startSeconds: Double?) {
    postID = post.id
    self.videoURL = videoURL
    author = post.authorName
    caption = post.caption
    commentCount = post.commentCount
    reactionCount = post.reactionCount
    topReactionEmoji = post.topReactionEmoji
    shareURL = post.subPost?.url ?? post.url ?? videoURL
    self.startSeconds = startSeconds
  }
}

/// The player behind the full-screen viewer.
@MainActor
final class ExpressPostVideoModel: ObservableObject {
  static let speeds: [Float] = [1, 1.25, 1.5, 2, 0.5, 0.75]

  let player: AVPlayer
  @Published private(set) var isPlaying = false
  /// Seconds.
  @Published private(set) var position: Double = 0
  @Published private(set) var duration: Double = 0
  @Published private(set) var buffered: Double = 0
  @Published private(set) var isMuted = false
  @Published private(set) var speedIndex = 0
  /// "2×" while the reader holds the right half of the video.
  @Published private(set) var isHoldingFast = false
  @Published var isScrubbing = false

  private var timeObserver: Any?
  private var statusObservation: NSKeyValueObservation?
  private var endObserver: NSObjectProtocol?
  private var reachedEnd = false
  /// For the feed's `video` events (surface "viewer").
  private let postID: String?
  private var stretchStart: (since: Date, audible: Bool)?

  init(url: URL, startSeconds: Double?, postID: String? = nil) {
    self.postID = postID
    let item = AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url))
    player = AVPlayer(playerItem: item)
    if let startSeconds, startSeconds.isFinite, startSeconds > 0 {
      player.seek(
        to: CMTime(seconds: startSeconds, preferredTimescale: 600),
        toleranceBefore: .zero,
        toleranceAfter: .zero
      )
    }
    timeObserver = player.addPeriodicTimeObserver(
      forInterval: CMTime(seconds: 0.25, preferredTimescale: 600),
      queue: .main
    ) { [weak self] time in
      MainActor.assumeIsolated { self?.tick(time) }
    }
    statusObservation = player.observe(\.timeControlStatus, options: [.initial, .new]) {
      [weak self] player, _ in
      let playing = player.timeControlStatus != .paused
      DispatchQueue.main.async { self?.playingChanged(playing) }
    }
    endObserver = NotificationCenter.default.addObserver(
      forName: AVPlayerItem.didPlayToEndTimeNotification,
      object: item,
      queue: .main
    ) { [weak self] _ in
      MainActor.assumeIsolated { self?.reachedEnd = true }
    }
  }

  func start() {
    try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    play()
  }

  private func playingChanged(_ playing: Bool) {
    isPlaying = playing
    if playing {
      if stretchStart == nil { stretchStart = (Date(), !isMuted) }
    } else {
      endStretch()
    }
  }

  /// One stretch of watching ends: pausing, closing, or a sound change.
  private func endStretch() {
    guard let start = stretchStart, let postID else { return }
    stretchStart = nil
    let watched = Int64(Date().timeIntervalSince(start.since) * 1000)
    ExpressFeedEvents.shared.record(
      .video,
      postID: postID,
      session: nil,
      surface: "viewer",
      watchMs: watched,
      audibleMs: start.audible ? watched : 0,
      durationMs: duration > 0 ? Int64(duration * 1000) : nil
    )
  }

  func stop() {
    endStretch()
    player.pause()
    if let timeObserver { player.removeTimeObserver(timeObserver) }
    timeObserver = nil
    statusObservation = nil
    if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    endObserver = nil
  }

  func togglePlayback() {
    isPlaying ? player.pause() : play()
  }

  func toggleMute() {
    endStretch()
    isMuted.toggle()
    player.isMuted = isMuted
    if isPlaying { stretchStart = (Date(), !isMuted) }
  }

  func nextSpeed() {
    speedIndex = (speedIndex + 1) % Self.speeds.count
    if isPlaying { player.rate = currentRate }
  }

  var speedLabel: String {
    let formatter = NumberFormatter()
    formatter.minimumFractionDigits = 0
    formatter.maximumFractionDigits = 2
    let speed = formatter.string(from: NSNumber(value: Self.speeds[speedIndex])) ?? "1"
    return "\(speed)×"
  }

  func setHoldingFast(_ holding: Bool) {
    guard holding != isHoldingFast else { return }
    isHoldingFast = holding
    if isPlaying || holding { player.rate = currentRate }
  }

  /// While dragging the timeline: shows the time without moving the video yet.
  func scrub(to fraction: Double) {
    isScrubbing = true
    position = max(0, min(1, fraction)) * duration
  }

  func finishScrub(at fraction: Double) {
    let target = max(0, min(1, fraction)) * duration
    position = target
    reachedEnd = false
    player.seek(
      to: CMTime(seconds: target, preferredTimescale: 600),
      toleranceBefore: .zero,
      toleranceAfter: .zero
    ) { [weak self] _ in
      DispatchQueue.main.async { self?.isScrubbing = false }
    }
  }

  private var currentRate: Float { isHoldingFast ? 2 : Self.speeds[speedIndex] }

  private func play() {
    if reachedEnd {
      reachedEnd = false
      player.seek(to: .zero)
    }
    player.playImmediately(atRate: currentRate)
  }

  private func tick(_ time: CMTime) {
    if let item = player.currentItem {
      let total = item.duration.seconds
      if total.isFinite, total > 0 { duration = total }
      if let range = item.loadedTimeRanges.last?.timeRangeValue {
        let end = (range.start + range.duration).seconds
        if end.isFinite { buffered = end }
      }
    }
    if !isScrubbing, time.seconds.isFinite { position = max(0, time.seconds) }
  }

  static func format(_ seconds: Double) -> String {
    let total = Int(max(0, seconds.isFinite ? seconds : 0))
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let secs = total % 60
    return hours > 0
      ? String(format: "%d:%02d:%02d", hours, minutes, secs)
      : String(format: "%d:%02d", minutes, secs)
  }
}

/// A feed post's video full screen (Android's `PostVideoViewerFragment`, Figma "Express/
/// Full-screen Video"): close and ⋯ on top, the video, the author and caption, a timeline with
/// play/pause, time, mute, speed and full screen, and the post's reaction rail.
///
/// It opens over the post's comments, so closing it, tapping the rail, or swiping the video up
/// or down all land the reader on them.
struct ExpressPostVideoViewer: View {
  let item: ExpressPostVideoItem
  /// Back to the comments under it.
  var onClose: () -> Void
  @State private var didReportClose = false
  @State private var openedAt = Date()
  var onShare: (URL) -> Void

  @StateObject private var model: ExpressPostVideoModel
  @State private var captionExpanded = false
  @State private var isFullscreen = false

  private static let background = Color(expressARGB: 0xFF06_080A)
  private static let caption = Color(expressARGB: 0xE0FF_FFFF)
  private static let time = Color(expressARGB: 0xDBFF_FFFF)
  private static let track = Color(expressARGB: 0x47FF_FFFF)
  private static let bufferedTrack = Color(expressARGB: 0x4DFF_FFFF)
  private static let scrim = Color(expressARGB: 0xB300_0000)
  private static let railLine = Color(expressARGB: 0xFF3A_3A3A)
  private static let railText = Color(expressARGB: 0xFAFF_FFFF)
  private static let railXP = Color(expressARGB: 0xB8FF_FFFF)

  init(
    item: ExpressPostVideoItem,
    onClose: @escaping () -> Void,
    onShare: @escaping (URL) -> Void
  ) {
    self.item = item
    self.onClose = onClose
    self.onShare = onShare
    _model = StateObject(wrappedValue: ExpressPostVideoModel(url: item.videoURL, startSeconds: item.startSeconds, postID: item.postID))
  }

  var body: some View {
    ZStack {
      Self.background.ignoresSafeArea()
      ExpressPostVideoLayer(player: model.player)
        .ignoresSafeArea()
        .accessibilityHidden(true)
      touchLayer
        .ignoresSafeArea()
      VStack(spacing: 0) {
        topBar
        if model.isHoldingFast {
          Text("2×")
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(.white)
            .padding(.horizontal, 12)
            .frame(height: 28)
            .background(Capsule().fill(Color.black.opacity(0.55)))
            .padding(.top, 16)
            .allowsHitTesting(false)
        }
        Spacer(minLength: 0)
        bottom
      }
    }
    .statusBarHidden(isFullscreen)
    .onAppear { model.start() }
    .onDisappear {
      if !didReportClose { reportClose(exit: "other") }
      model.stop()
      if isFullscreen { setLandscape(false) }
    }
  }

  // MARK: Gestures on the video

  /// Tap plays or pauses; holding the right half plays at 2×; a vertical swipe goes to the
  /// comments.
  private var touchLayer: some View {
    HStack(spacing: 0) {
      Color.clear.contentShape(Rectangle())
        .onTapGesture { model.togglePlayback() }
      Color.clear.contentShape(Rectangle())
        .onTapGesture { model.togglePlayback() }
        .onLongPressGesture(minimumDuration: 0.35, maximumDistance: 24) {
          model.setHoldingFast(true)
        } onPressingChanged: { pressing in
          if !pressing { model.setHoldingFast(false) }
        }
    }
    .simultaneousGesture(
      DragGesture(minimumDistance: 30).onEnded { value in
        let vertical = value.translation.height
        if abs(vertical) > 80, abs(vertical) > abs(value.translation.width) * 1.5 {
          close(exit: "swipe")
        }
      }
    )
  }

  /// Android's `Post Video Viewer Closed`: how it was left and how long it was watched.
  private func reportClose(exit: String) {
    didReportClose = true
    ExpressAnalytics.shared.capture(
      "Post Video Viewer Closed",
      [
        "post_id": item.postID, "exit": exit,
        "watch_ms": Int64(Date().timeIntervalSince(openedAt) * 1000),
        "duration_ms": model.duration > 0 ? Int64(model.duration * 1000) : 0,
      ]
    )
  }

  private func close(exit: String) {
    reportClose(exit: exit)
    onClose()
  }

  // MARK: Top

  private var topBar: some View {
    HStack(spacing: 8) {
      iconButton("xmark", label: "Close") { close(exit: "close") }
      Text("Video")
        .font(.system(size: 16))
        .foregroundColor(.white)
        .frame(maxWidth: .infinity, alignment: .leading)
      Menu {
        Button {
          ExpressFeedEvents.shared.record(.share, postID: item.postID, session: nil, surface: "viewer")
          ExpressAnalytics.shared.capture("Post Shared", ["post_id": item.postID, "surface": "viewer"])
          onShare(item.shareURL)
        } label: {
          Label("Share", systemImage: "square.and.arrow.up")
        }
      } label: {
        Image(systemName: "ellipsis")
          .font(.system(size: 18, weight: .semibold))
          .foregroundColor(.white)
          .rotationEffect(.degrees(90))
          .frame(width: 44, height: 44)
          .contentShape(Rectangle())
      }
      .accessibilityLabel("More options")
    }
    .padding(.horizontal, 6)
    .padding(.top, 16)
  }

  // MARK: Bottom

  private var bottom: some View {
    VStack(spacing: 0) {
      if !isFullscreen {
        context
      }
      controls
        .padding(.top, 9)
      if !isFullscreen {
        rail
      }
    }
    .background(alignment: .top) {
      // Keeps the author and caption readable over bright video.
      if !isFullscreen {
        LinearGradient(
          stops: [
            .init(color: .clear, location: 0),
            .init(color: Color.black.opacity(0.45), location: 0.3),
            .init(color: Color.black.opacity(0.6), location: 1),
          ],
          startPoint: .top,
          endPoint: .bottom
        )
        .padding(.top, -48)
        .allowsHitTesting(false)
      }
    }
  }

  private var context: some View {
    VStack(alignment: .leading, spacing: 0) {
      if !item.author.isEmpty {
        Text(item.author)
          .font(.system(size: 14, weight: .semibold))
          .foregroundColor(.white)
          .lineLimit(1)
      }
      if let caption = item.caption, !caption.isEmpty {
        ScrollView {
          Text(caption)
            .font(.system(size: 14))
            .foregroundColor(Self.caption)
            .lineLimit(captionExpanded ? nil : 2)
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { captionExpanded.toggle() }
        }
        .scrollDisabled(!captionExpanded)
        .frame(maxHeight: captionExpanded ? 200 : 40)
        .fixedSize(horizontal: false, vertical: !captionExpanded)
        .padding(.top, 9)
      }
    }
    .frame(maxWidth: .infinity, alignment: .leading)
    .padding(.horizontal, 20)
    .shadow(color: .black.opacity(0.5), radius: 4)
  }

  private var controls: some View {
    VStack(spacing: 0) {
      ExpressVideoTimeline(model: model, track: Self.track, buffered: Self.bufferedTrack)
        .frame(height: 32)
        .padding(.horizontal, 20)
      HStack(spacing: 0) {
        iconButton(
          model.isPlaying ? "pause.fill" : "play.fill",
          label: model.isPlaying ? "Pause video" : "Play video",
          action: model.togglePlayback
        )
        Text(
          "\(ExpressPostVideoModel.format(model.position)) / \(ExpressPostVideoModel.format(model.duration))"
        )
        .font(.system(size: 13).monospacedDigit())
        .foregroundColor(Self.time)
        .lineLimit(1)
        .padding(.leading, 8)
        .frame(maxWidth: .infinity, alignment: .leading)
        iconButton(
          model.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill",
          label: model.isMuted ? "Unmute" : "Mute",
          action: model.toggleMute
        )
        Button(action: model.nextSpeed) {
          Text(model.speedLabel)
            .font(.system(size: 14))
            .foregroundColor(.white)
            .frame(width: 54, height: 44)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Playback speed \(model.speedLabel)")
        iconButton(
          isFullscreen
            ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
          label: isFullscreen ? "Exit full screen" : "Full screen"
        ) {
          isFullscreen.toggle()
          setLandscape(isFullscreen)
        }
      }
      .frame(height: 44)
      .padding(.horizontal, 8)
      .padding(.bottom, 10)
    }
    .background(
      LinearGradient(
        stops: [
          .init(color: .clear, location: 0),
          .init(color: .clear, location: 0.21),
          .init(color: Self.scrim, location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .ignoresSafeArea(edges: isFullscreen ? .bottom : [])
    )
  }

  /// "N reactions", the top emoji, XP and a chevron; it opens the comments.
  private var rail: some View {
    Button {
      close(exit: "rail")
    } label: {
      HStack(spacing: 8) {
        Text(
          item.commentCount > 0
            ? "\(ExpressNumberFormat.compact(item.commentCount)) reactions"
            : "Start the conversation"
        )
        .font(.system(size: 12, weight: .semibold))
        .foregroundColor(Self.railText)
        .lineLimit(1)
        if item.reactionCount > 0, !item.topReactionEmoji.isEmpty {
          HStack(spacing: -5) {
            ForEach(Array(item.topReactionEmoji.prefix(3).enumerated()), id: \.offset) { _, emoji in
              Text(emoji).font(.system(size: 12)).frame(width: 16, height: 16)
            }
          }
          .opacity(0.86)
        }
        if item.reactionCount > 0 {
          Text("\(ExpressNumberFormat.compact(item.reactionCount)) XP")
            .font(.system(size: 11, weight: .medium))
            .foregroundColor(Self.railXP)
            .lineLimit(1)
        }
        Spacer(minLength: 0)
        Image(systemName: "chevron.right")
          .font(.system(size: 11, weight: .bold))
          .foregroundColor(.white)
          .frame(width: 24, height: 24)
          .overlay(Circle().stroke(Color.white.opacity(0.8), lineWidth: 1))
      }
      .padding(.leading, 14)
      .padding(.trailing, 10)
      .frame(height: 56)
      .background(
        LinearGradient(
          colors: [Color(expressARGB: 0xE811_1111), Color(expressARGB: 0xFF06_080B)],
          startPoint: .top,
          endPoint: .bottom
        )
        .overlay(alignment: .top) { Self.railLine.frame(height: 1) }
        .ignoresSafeArea(edges: .bottom)
      )
      .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .accessibilityLabel("Open comments")
  }

  private func iconButton(_ symbol: String, label: String, action: @escaping () -> Void)
    -> some View
  {
    Button(action: action) {
      Image(systemName: symbol)
        .font(.system(size: 18, weight: .semibold))
        .foregroundColor(.white)
        .frame(width: 44, height: 44)
        .contentShape(Rectangle())
    }
    .accessibilityLabel(label)
  }

  /// Landscape with only the video and its controls, or back to portrait.
  private func setLandscape(_ landscape: Bool) {
    guard
      let scene = UIApplication.shared.connectedScenes
        .compactMap({ $0 as? UIWindowScene })
        .first(where: { $0.activationState == .foregroundActive })
    else { return }
    scene.requestGeometryUpdate(.iOS(interfaceOrientations: landscape ? .landscapeRight : .portrait))
  }
}

/// The timeline: played, buffered and the rest, with a thumb to drag.
private struct ExpressVideoTimeline: View {
  @ObservedObject var model: ExpressPostVideoModel
  let track: Color
  let buffered: Color

  var body: some View {
    GeometryReader { proxy in
      let width = max(1, proxy.size.width)
      let duration = max(model.duration, 0.001)
      let played = CGFloat(min(1, model.position / duration))
      let loaded = CGFloat(min(1, model.buffered / duration))
      ZStack(alignment: .leading) {
        Capsule().fill(track).frame(height: 3)
        Capsule().fill(buffered).frame(width: width * loaded, height: 3)
        Capsule().fill(Color.white).frame(width: width * played, height: 3)
        Circle()
          .fill(Color.white)
          .frame(width: model.isScrubbing ? 16 : 12, height: model.isScrubbing ? 16 : 12)
          .offset(x: width * played - (model.isScrubbing ? 8 : 6))
      }
      .frame(maxHeight: .infinity)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 0)
          .onChanged { model.scrub(to: Double($0.location.x / width)) }
          .onEnded { model.finishScrub(at: Double($0.location.x / width)) }
      )
    }
    .accessibilityElement()
    .accessibilityLabel("Video position")
    .accessibilityValue(ExpressPostVideoModel.format(model.position))
  }
}

/// The player, fitted inside the screen.
private struct ExpressPostVideoLayer: UIViewRepresentable {
  let player: AVPlayer

  func makeUIView(context: Context) -> PlayerView {
    let view = PlayerView()
    view.backgroundColor = .clear
    view.isUserInteractionEnabled = false
    view.playerLayer.videoGravity = .resizeAspect
    view.playerLayer.player = player
    return view
  }

  func updateUIView(_ view: PlayerView, context: Context) {
    if view.playerLayer.player !== player { view.playerLayer.player = player }
  }

  final class PlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
  }
}

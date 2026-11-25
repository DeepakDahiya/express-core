// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import Combine
import ExpressCore
import SwiftUI
import UIKit

/// Muted inline autoplay for video comments in the list (YouTube comments and replies), after
/// Android's `CommentListFragment`: the video more than 60% on screen with the largest share
/// plays, looping and muted, one at a time; it's checked 100 ms after scrolling moves.
///
/// One sound at a time: a voice note starting (or recording) stops the video at once, and it
/// comes back muted once the note stops; turning the sound on pauses a voice note. While the
/// app isn't active or a document is open over the comments (`ExpressCommentsMediaFocus`), the
/// video pauses in place and carries on after.
///
/// The reel's text pages use it too, but pick the video by page instead of by what's on screen
/// (`choose(_:url:)`): the first video card on the current page plays, muted and looping, as
/// Android's `CommentReelFragment.attemptAutoplay` does for landscape and square videos.
@MainActor
final class ExpressListVideoAutoplay: ObservableObject {
  @Published private(set) var currentID: String?
  @Published private(set) var isMuted = true
  @Published private(set) var isPlaying = false

  /// Off while the list is hidden or something covers it.
  var isEnabled = true {
    didSet { if isEnabled != oldValue { schedule() } }
  }

  /// Something of the screen's own covers it (the reel's sheets, the replies screen): the video
  /// pauses in place and carries on after, like the app going inactive.
  var isHeld = false {
    didSet { if isHeld != oldValue { evaluateNow() } }
  }

  private struct Choice: Equatable {
    let id: String
    let url: URL
  }

  /// Set once `choose(_:url:)` is used: the caller picks the video, frames and the viewport
  /// play no part.
  private var choosesByPage = false
  private var chosen: Choice?

  private var frames: [String: (url: URL, frame: CGRect)] = [:]
  private var viewport: CGRect = .zero
  private(set) var player: AVQueuePlayer?
  private var looper: AVPlayerLooper?
  private var statusObservation: NSKeyValueObservation?
  private var pending: Task<Void, Never>?

  /// Mirrors of `ExpressCommentsMediaFocus` and the voice-note player.
  private var isAppActive = true
  private var isCovered = false
  private var isRecording = false
  private var isVoicePlaying = false
  private var cancellables: Set<AnyCancellable> = []

  init() {
    let focus = ExpressCommentsMediaFocus.shared
    isAppActive = focus.isAppActive
    isCovered = focus.isCovered
    isRecording = focus.isRecording
    isVoicePlaying = ExpressAudioPlayback.shared.isPlaying
    // `@Published` sends before the value changes, so each sink keeps the value it's given.
    focus.$isAppActive.removeDuplicates().dropFirst()
      .sink { [weak self] active in
        MainActor.assumeIsolated {
          self?.isAppActive = active
          self?.evaluateNow()
        }
      }
      .store(in: &cancellables)
    focus.$isCovered.removeDuplicates().dropFirst()
      .sink { [weak self] covered in
        MainActor.assumeIsolated {
          self?.isCovered = covered
          self?.evaluateNow()
        }
      }
      .store(in: &cancellables)
    focus.$isRecording.removeDuplicates().dropFirst()
      .sink { [weak self] recording in
        MainActor.assumeIsolated {
          self?.isRecording = recording
          if recording { self?.evaluateNow() } else { self?.schedule() }
        }
      }
      .store(in: &cancellables)
    // Android's `playAudio` stops the list's video as the note starts, not at the next scroll.
    // A note ending waits out the debounce: switching notes stops one just before the next
    // starts, and the video shouldn't start in between.
    ExpressAudioPlayback.shared.$isPlaying.removeDuplicates().dropFirst()
      .sink { [weak self] playing in
        MainActor.assumeIsolated {
          self?.isVoicePlaying = playing
          if playing { self?.evaluateNow() } else { self?.schedule() }
        }
      }
      .store(in: &cancellables)
  }

  func report(_ id: String, url: URL, frame: CGRect) {
    frames[id] = (url, frame)
    if !choosesByPage { schedule() }
  }

  func remove(_ id: String) {
    frames[id] = nil
    if !choosesByPage { schedule() }
  }

  /// Plays this video (none for nil), whatever share of it is on screen: the reel's current text
  /// page names its first video card, and nil when it has none or a video page is current.
  /// A different choice starts from the beginning, muted; the same one carries on.
  func choose(_ id: String?, url: URL?) {
    choosesByPage = true
    let choice = id.flatMap { id in url.map { Choice(id: id, url: $0) } }
    guard choice != chosen else { return }
    chosen = choice
    evaluateNow()
  }

  func setViewport(_ rect: CGRect) {
    viewport = rect
    schedule()
  }

  func toggleMute() {
    isMuted.toggle()
    player?.isMuted = isMuted
    if !isMuted {
      // One sound at a time: a voice note pauses (keeping its place) for the video's sound.
      ExpressAudioPlayback.shared.pause()
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
    }
  }

  func stop() {
    statusObservation = nil
    looper?.disableLooping()
    looper = nil
    player?.pause()
    player = nil
    currentID = nil
    isPlaying = false
  }

  private func schedule() {
    pending?.cancel()
    pending = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 100_000_000)
      guard !Task.isCancelled else { return }
      self?.evaluate()
    }
  }

  /// Sound and hold changes act at once rather than after the scroll debounce.
  private func evaluateNow() {
    pending?.cancel()
    pending = nil
    evaluate()
  }

  private func evaluate() {
    guard isEnabled, choosesByPage || !viewport.isEmpty, !isVoicePlaying, !isRecording else {
      stop()
      return
    }
    // Away, a document is open over the comments, or the screen's own cover is up: hold the
    // video in place (the system would pause it in the background anyway, leaving the play
    // mark out of step).
    guard isAppActive, !isCovered, !isHeld else {
      player?.pause()
      return
    }
    guard let best = choosesByPage ? chosen : mostShown() else {
      stop()
      return
    }
    guard best.id != currentID else {
      // Still the chosen one: carry on after a hold (a no-op while it plays).
      player?.play()
      return
    }
    stop()
    let queue = AVQueuePlayer()
    queue.isMuted = true
    isMuted = true
    looper = AVPlayerLooper(
      player: queue,
      templateItem: AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: best.url))
    )
    statusObservation = queue.observe(\.timeControlStatus, options: [.initial, .new]) { [weak self] player, _ in
      let playing = player.timeControlStatus == .playing
      DispatchQueue.main.async { self?.isPlaying = playing }
    }
    try? AVAudioSession.sharedInstance().setCategory(.playback, options: [.mixWithOthers])
    player = queue
    currentID = best.id
    queue.play()
  }

  /// The list's pick: the video more than 60% on screen with the largest share.
  private func mostShown() -> Choice? {
    var best: (choice: Choice, share: CGFloat)?
    for (id, entry) in frames {
      let frame = entry.frame
      guard frame.width > 0, frame.height > 0 else { continue }
      let shown = frame.intersection(viewport)
      guard !shown.isNull else { continue }
      let share = (shown.width * shown.height) / (frame.width * frame.height)
      if share > 0.6, share > (best?.share ?? 0) {
        best = (Choice(id: id, url: entry.url), share)
      }
    }
    return best?.choice
  }
}

/// A video comment card in a list or on a reel text page: the poster with a play mark, playing
/// muted in place when it's the chosen one, with Android's 28 pt sound button. A tap opens the
/// full-screen viewer.
struct ExpressInlineCommentVideo: View {
  let comment: ExpressComment
  let videoURL: URL
  @ObservedObject var autoplay: ExpressListVideoAutoplay
  var onOpen: (URL) -> Void

  private var isCurrent: Bool { autoplay.currentID == comment.id }

  var body: some View {
    Color(white: 0.13)
      .aspectRatio(comment.mediaCardAspectRatio, contentMode: .fit)
      .frame(maxWidth: .infinity)
      .overlay {
        if let poster = comment.imageURL {
          ExpressAnimatedImage(url: poster)
        }
      }
      .overlay {
        if isCurrent {
          ExpressPlayerLayerView(player: autoplay.player)
        }
      }
      .overlay {
        if !(isCurrent && autoplay.isPlaying) {
          Image(systemName: "play.circle.fill")
            .font(.system(size: 44))
            .foregroundStyle(.white.opacity(0.9))
            .shadow(radius: 4)
        }
      }
      .overlay(alignment: .bottomTrailing) {
        if isCurrent {
          Button {
            autoplay.toggleMute()
          } label: {
            Image(systemName: autoplay.isMuted ? "speaker.slash.fill" : "speaker.wave.2.fill")
              .font(.system(size: 12, weight: .semibold))
              .foregroundColor(.white)
              .frame(width: 28, height: 28)
              .background(Circle().fill(Color.black.opacity(0.5)))
          }
          .buttonStyle(.plain)
          .padding(8)
          .accessibilityLabel(autoplay.isMuted ? "Turn sound on" : "Turn sound off")
        }
      }
      .clipShape(RoundedRectangle(cornerRadius: 12))
      .contentShape(Rectangle())
      .onTapGesture {
        autoplay.stop()
        // The full-screen viewer plays with sound: a voice note pauses first, as the source
        // chip's viewer does (Android's `SourceChip.openViewer`).
        ExpressAudioPlayback.shared.pause()
        onOpen(videoURL)
      }
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
        autoplay.report(comment.id, url: videoURL, frame: frame)
      }
      .onDisappear { autoplay.remove(comment.id) }
      .accessibilityElement(children: .contain)
      .accessibilityLabel("Video")
  }
}

/// An `AVPlayerLayer` for a player that may change.
struct ExpressPlayerLayerView: UIViewRepresentable {
  let player: AVPlayer?

  func makeUIView(context: Context) -> LayerView {
    let view = LayerView()
    view.isUserInteractionEnabled = false
    view.playerLayer.videoGravity = .resizeAspectFill
    return view
  }

  func updateUIView(_ view: LayerView, context: Context) {
    if view.playerLayer.player !== player { view.playerLayer.player = player }
  }

  final class LayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
  }
}

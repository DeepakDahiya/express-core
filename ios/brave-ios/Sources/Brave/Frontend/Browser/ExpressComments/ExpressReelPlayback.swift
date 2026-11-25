// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import Combine
import ExpressCore
import SwiftUI
import UIKit

/// Video playback for the comments reel, after Android's `GlobalVideoPlaybackManager`: one video
/// plays at a time, with sound and looping; the pages either side stay prepared (paused, keeping
/// their position); everything else is released.
///
/// One sound at a time: the video starting pauses a voice note, and a voice note starting (or a
/// recording) pauses the video, which then shows paused until it's played again or the page
/// changes. It also holds while the app isn't active and while a document is open over the
/// comments (`ExpressCommentsMediaFocus`), and picks up again after.
@MainActor
final class ExpressReelPlayback: ObservableObject {
  private final class Entry {
    let player: AVQueuePlayer
    let looper: AVPlayerLooper

    init(url: URL) {
      let item = AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url))
      player = AVQueuePlayer()
      player.isMuted = false
      looper = AVPlayerLooper(player: player, templateItem: item)
    }
  }

  @Published private(set) var currentID: String?
  /// The current video shows paused, with its play button: the reader paused it, a voice note
  /// took over, or the system stopped it.
  @Published private(set) var isPaused = false
  @Published private(set) var isFastForwarding = false
  /// Playback position of the current video, 0...1.
  @Published private(set) var progress: Double = 0
  /// Bumped whenever players are created or dropped, so player views pick theirs up.
  @Published private(set) var generation = 0

  /// Something is open over the reel (a sheet, the replies screen), so nothing plays.
  var isBlocked = false {
    didSet { if isBlocked != oldValue { applyPlayState() } }
  }

  /// Mirrors of `ExpressCommentsMediaFocus`: the app is active, nothing covers the comments,
  /// no voice note is recording.
  private var isAppActive = true
  private var isCovered = false
  private var isRecording = false
  private var cancellables: Set<AnyCancellable> = []
  /// Notices the system pausing the current video (a call, headphones unplugged).
  private var rateObservation: NSKeyValueObservation?

  /// Nothing may play now, whatever the reader wants.
  private var isHeld: Bool {
    isBlocked || !isAppActive || isCovered || isRecording
  }

  init() {
    let focus = ExpressCommentsMediaFocus.shared
    isAppActive = focus.isAppActive
    isCovered = focus.isCovered
    isRecording = focus.isRecording
    // `@Published` sends before the value changes, so each sink keeps the value it's given.
    focus.$isAppActive.removeDuplicates().dropFirst()
      .sink { [weak self] active in
        MainActor.assumeIsolated {
          guard let self else { return }
          // Away: the video pauses. Back: the current page's video picks up again, unless the
          // reader had paused it or something still covers the reel.
          self.isAppActive = active
          self.applyPlayState()
        }
      }
      .store(in: &cancellables)
    focus.$isCovered.removeDuplicates().dropFirst()
      .sink { [weak self] covered in
        MainActor.assumeIsolated {
          guard let self else { return }
          self.isCovered = covered
          self.applyPlayState()
        }
      }
      .store(in: &cancellables)
    focus.$isRecording.removeDuplicates().dropFirst()
      .sink { [weak self] recording in
        MainActor.assumeIsolated {
          guard let self else { return }
          if recording { self.yieldToOtherSound() }
          self.isRecording = recording
          self.applyPlayState()
        }
      }
      .store(in: &cancellables)
    ExpressAudioPlayback.shared.$isPlaying.removeDuplicates().dropFirst()
      .sink { [weak self] playing in
        MainActor.assumeIsolated {
          if playing { self?.yieldToOtherSound() }
        }
      }
      .store(in: &cancellables)
  }

  private var entries: [String: Entry] = [:]

  deinit {
    if let (player, token) = timeObserver {
      player.removeTimeObserver(token)
    }
  }
  private var urls: [String: URL] = [:]
  private var timeObserver: (player: AVPlayer, token: Any)?

  func player(for id: String) -> AVPlayer? {
    entries[id]?.player
  }

  /// Makes `id` the playing video and keeps `neighbours` prepared. Pass nil on a text page.
  func activate(_ id: String?, url: URL?, neighbours: [(id: String, url: URL)]) {
    if let id, let url { urls[id] = url }
    for neighbour in neighbours { urls[neighbour.id] = neighbour.url }

    let keep = Set(([id].compactMap { $0 }) + neighbours.map(\.id))
    var changed = false
    for (key, entry) in entries where !keep.contains(key) {
      entry.player.pause()
      entries[key] = nil
      changed = true
    }
    for key in keep where entries[key] == nil {
      guard let url = urls[key] else { continue }
      entries[key] = Entry(url: url)
      changed = true
    }
    if changed { generation += 1 }

    if currentID != id {
      if let previous = currentID { entries[previous]?.player.pause() }
      currentID = id
      isPaused = false
      isFastForwarding = false
      progress = 0
      observeCurrent()
    }
    applyPlayState()
  }

  func togglePlayPause() {
    guard currentID != nil else { return }
    isPaused.toggle()
    applyPlayState()
  }

  /// Hold-to-fast-forward: 2× while held, on a playing video only. Returns whether it started.
  @discardableResult
  func beginFastForward() -> Bool {
    guard let player = currentPlayer, !isPaused, !isHeld else { return false }
    isFastForwarding = true
    player.rate = 2
    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    return true
  }

  func endFastForward() {
    guard isFastForwarding else { return }
    isFastForwarding = false
    applyPlayState()
  }

  func seek(to fraction: Double) {
    guard let player = currentPlayer, let item = player.currentItem else { return }
    let duration = item.duration.seconds
    guard duration.isFinite, duration > 0 else { return }
    progress = fraction
    player.seek(
      to: CMTime(seconds: duration * min(max(fraction, 0), 1), preferredTimescale: 600),
      toleranceBefore: .zero,
      toleranceAfter: .zero
    )
  }

  /// Stops and releases every player (the comments closed).
  func releaseAll() {
    removeTimeObserver()
    rateObservation = nil
    for entry in entries.values { entry.player.pause() }
    entries.removeAll()
    currentID = nil
    generation += 1
  }

  private var currentPlayer: AVQueuePlayer? {
    currentID.flatMap { entries[$0]?.player }
  }

  private func applyPlayState() {
    guard let player = currentPlayer else { return }
    if isPaused || isHeld {
      player.pause()
      isFastForwarding = false
    } else {
      // One sound at a time: the video takes over from a voice note (Android's `play` pauses
      // the audio), which keeps its place.
      let audio = ExpressAudioPlayback.shared
      if audio.isPlaying { audio.pause() }
      // Video pages play with sound, even with the ringer switch off.
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
      try? AVAudioSession.sharedInstance().setActive(true)
      player.rate = isFastForwarding ? 2 : 1
    }
  }

  /// A voice note started playing or recording: a playing video pauses and shows paused
  /// (Android's `playAudio` and `startRecording` stop the video). A held video is left alone;
  /// it plays again (and pauses the note) when the hold ends, as Android's autoplay does.
  private func yieldToOtherSound() {
    guard let player = currentPlayer, !isPaused, !isHeld else { return }
    isPaused = true
    isFastForwarding = false
    player.pause()
  }

  /// The current video stopped while it should be playing: the system paused it (a call,
  /// headphones unplugged). It shows paused, so the play button is there to start it again.
  private func rateChanged(_ player: AVPlayer) {
    guard player === currentPlayer, player.rate == 0, !isPaused, !isHeld,
      UIApplication.shared.applicationState == .active
    else { return }
    isPaused = true
    isFastForwarding = false
  }

  private func observeCurrent() {
    removeTimeObserver()
    rateObservation = nil
    guard let player = currentPlayer else { return }
    rateObservation = player.observe(\.rate, options: [.new]) { [weak self] player, _ in
      DispatchQueue.main.async {
        MainActor.assumeIsolated { self?.rateChanged(player) }
      }
    }
    let token = player.addPeriodicTimeObserver(
      forInterval: CMTime(value: 1, timescale: 4),
      queue: .main
    ) { [weak self, weak player] time in
      guard let player, let duration = player.currentItem?.duration.seconds,
        duration.isFinite, duration > 0
      else { return }
      MainActor.assumeIsolated {
        self?.progress = min(max(time.seconds / duration, 0), 1)
      }
    }
    timeObserver = (player, token)
  }

  private func removeTimeObserver() {
    if let (player, token) = timeObserver {
      player.removeTimeObserver(token)
    }
    timeObserver = nil
  }
}

// MARK: - Player view

/// Shows one reel's player, and turns taps into play/pause and a held press into 2×.
struct ExpressReelPlayerView: UIViewRepresentable {
  @ObservedObject var playback: ExpressReelPlayback
  let commentID: String
  /// Fill the page for tall videos (width/height ≤ 0.62), otherwise fit with black bars.
  let fills: Bool

  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeUIView(context: Context) -> PlayerView {
    let view = PlayerView()
    view.backgroundColor = .clear
    let tap = UITapGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.tapped)
    )
    let hold = UILongPressGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.held(_:))
    )
    hold.minimumPressDuration = 0.3
    view.addGestureRecognizer(tap)
    view.addGestureRecognizer(hold)
    return view
  }

  func updateUIView(_ view: PlayerView, context: Context) {
    context.coordinator.playback = playback
    _ = playback.generation
    let player = playback.player(for: commentID)
    if view.playerLayer.player !== player {
      view.playerLayer.player = player
    }
    view.playerLayer.videoGravity = fills ? .resizeAspectFill : .resizeAspect
  }

  final class PlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
  }

  @MainActor
  final class Coordinator: NSObject {
    weak var playback: ExpressReelPlayback?
    private var heldThisTouch = false

    @objc func tapped() {
      // A press that fast-forwarded doesn't also toggle playback when it lifts.
      if heldThisTouch { return }
      playback?.togglePlayPause()
    }

    @objc func held(_ recognizer: UILongPressGestureRecognizer) {
      switch recognizer.state {
      case .began:
        heldThisTouch = playback?.beginFastForward() ?? false
      case .ended, .cancelled, .failed:
        playback?.endFastForward()
        DispatchQueue.main.async { self.heldThisTouch = false }
      default:
        break
      }
    }
  }
}

// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import Combine
import ExpressCore
import SwiftUI
import UIKit
import os.log

/// Video autoplay in the new tab page feed, after the Android app's `PostListAdapter` and
/// `GlobalVideoPlaybackManager`:
/// - videos loop, and start muted;
/// - the one that plays is the video at least 50% on screen with the largest visible share
///   (within 5%, the one nearer the middle of the screen wins); all others pause;
/// - the check runs at most every 100 ms while cards move, and once more when they stop;
/// - a card that scrolls off releases the player and keeps its position for when it returns;
/// - nothing plays while the new tab page is hidden, the comments sheet is over it, or the app
///   is in the background;
/// - one feed-wide sound setting, off by default and kept for the app session; only the playing
///   card is audible, and only while no voice note plays (Android's `mPlayingAudio`: videos keep
///   going, silent);
/// - a voice post pauses when its row scrolls off, the comments sheet covers the feed or the app
///   goes to the background, and stops when the new tab page goes;
/// - the header's tutorial video is one more candidate, chosen by the same rules (Android's
///   `updatePlayingVideo` starts its loop at the header). It keeps its own muted player and
///   isn't counted in the feed events.
///
/// Cards report where their video box is with `report(_:url:frame:)`; one shared player is
/// handed to whichever card is playing. Videos outside the cards (the tutorial) report with
/// `reportExternal(_:frame:setPlaying:)` and are told when to play.
@MainActor
final class ExpressFeedVideoPlayback: ObservableObject {
  @Published private(set) var playingID: String?
  /// Whether the shared player is actually running (not loading or stalled).
  @Published private(set) var isPlaying = false
  /// Kept for the app session, like Android's `sSoundOn`.
  @Published private(set) var isSoundOn = false
  /// Bumped when the shared player changes hands, so player views pick it up.
  @Published private(set) var generation = 0

  /// A voice note is playing, so the playing card stays silent.
  private var isVoicePlaying = false {
    didSet {
      guard isVoicePlaying != oldValue else { return }
      applySound()
      // Being heard or not ends one watching stretch and starts the next, as on Android.
      if isPlaying, let id = playerPostID {
        telemetry.videoStarted(postID: id, audible: isAudible, durationMs: currentDurationMs)
      }
    }
  }

  /// Whether the playing card is heard.
  private var isAudible: Bool { isSoundOn && !isVoicePlaying }

  /// True while the comments sheet (or anything else) covers the feed.
  var isCovered = false {
    didSet { if isCovered != oldValue { scheduleEvaluation(immediately: true) } }
  }
  /// True while the new tab page is on screen.
  var isPageVisible = false {
    didSet {
      guard isPageVisible != oldValue else { return }
      if isPageVisible { telemetry.pageAppeared() }
      scheduleEvaluation(immediately: true)
      // Leaving the new tab page ends the feed session (after its last stretches close).
      if !isPageVisible { telemetry.endSession() }
    }
  }

  /// Impressions and video watching, for the feed events.
  let telemetry = ExpressFeedTelemetry()

  private struct Candidate {
    var url: URL
    var frame: CGRect
  }

  private var candidates: [String: Candidate] = [:]

  /// A video with its own player (the tutorial), started and paused through `setPlaying`.
  private struct ExternalCandidate {
    var frame: CGRect
    var setPlaying: (Bool) -> Void
  }

  private var externals: [String: ExternalCandidate] = [:]
  /// The external video told to play, if one is.
  private var playingExternalID: String?

  private enum Winner {
    case post(id: String, url: URL)
    case external(id: String)
  }

  private var viewport: CGRect = .zero
  private var positions: [String: CMTime] = [:]
  private var player: AVQueuePlayer?
  private var looper: AVPlayerLooper?
  private var playerPostID: String?
  private var statusObservation: NSKeyValueObservation?
  private var lastEvaluation = Date.distantPast
  private var pendingEvaluation: Task<Void, Never>?
  private var observers: [NSObjectProtocol] = []
  private var voiceCancellable: AnyCancellable?

  /// Where each voice post's row is, in window coordinates.
  private var voiceFrames: [URL: CGRect] = [:]
  /// When a row left; for a moment it may only be moving to another cell.
  private var voiceGoneAt: [URL: Date] = [:]
  /// Voice posts the feed has shown, so it only pauses its own notes and not a comment's.
  private var voiceURLs: Set<URL> = []

  init() {
    voiceCancellable = ExpressAudioPlayback.shared.$isPlaying
      .removeDuplicates()
      .receive(on: RunLoop.main)
      .sink { [weak self] playing in
        MainActor.assumeIsolated { self?.isVoicePlaying = playing }
      }
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(
        forName: UIApplication.didEnterBackgroundNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.scheduleEvaluation(immediately: true) }
      }
    )
    observers.append(
      center.addObserver(
        forName: UIApplication.didBecomeActiveNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.scheduleEvaluation(immediately: true) }
      }
    )
  }

  deinit {
    for observer in observers { NotificationCenter.default.removeObserver(observer) }
  }

  /// The shared player, if `postID` is the card holding it.
  func player(for postID: String) -> AVPlayer? {
    playerPostID == postID ? player : nil
  }

  /// Where the full-screen viewer picks up (seconds): the card's place only when it was
  /// playing with sound, as on Android; nil starts the video over.
  func handoffSeconds(for postID: String) -> Double? {
    guard isAudible, playerPostID == postID, let player else { return nil }
    let seconds = player.currentTime().seconds
    return seconds.isFinite ? seconds : nil
  }

  // MARK: Inputs

  /// A card's video box, in window coordinates.
  func report(_ postID: String, url: URL, frame: CGRect) {
    candidates[postID] = Candidate(url: url, frame: frame)
    scheduleEvaluation()
  }

  /// The card left the screen (or was reused for another post).
  func remove(_ postID: String) {
    guard candidates.removeValue(forKey: postID) != nil else { return }
    scheduleEvaluation(immediately: true)
  }

  /// A video outside the cards (the header's tutorial video), in window coordinates. It plays
  /// only while it's the video picked; `setPlaying` starts and pauses its own player.
  func reportExternal(_ id: String, frame: CGRect, setPlaying: @escaping (Bool) -> Void) {
    externals[id] = ExternalCandidate(frame: frame, setPlaying: setPlaying)
    scheduleEvaluation()
  }

  /// The external video left the screen; it's paused if it was playing.
  func removeExternal(_ id: String) {
    guard let removed = externals.removeValue(forKey: id) else { return }
    if playingExternalID == id {
      playingExternalID = nil
      removed.setPlaying(false)
    }
    scheduleEvaluation(immediately: true)
  }

  /// The part of the window the feed shows, in window coordinates.
  func setViewport(_ rect: CGRect) {
    guard rect != viewport else { return }
    viewport = rect
    scheduleEvaluation()
  }

  func toggleSound() {
    isSoundOn.toggle()
    applySound()
    ExpressAnalytics.shared.capture("Feed Sound Toggled", ["sound_on": isSoundOn])
    // A sound change ends the watching stretch; a new one starts with the new sound.
    if isPlaying, let id = playerPostID {
      telemetry.videoStarted(postID: id, audible: isAudible, durationMs: currentDurationMs)
    }
  }

  /// A voice post's row, in window coordinates.
  func reportVoice(_ url: URL, frame: CGRect) {
    voiceURLs.insert(url)
    voiceFrames[url] = frame
    voiceGoneAt[url] = nil
    scheduleEvaluation()
  }

  /// The row left the screen, or its cell went to another post. The check waits a moment, so a
  /// row that only moved to another cell (the feed reloading) keeps playing.
  func removeVoice(_ url: URL) {
    guard voiceFrames.removeValue(forKey: url) != nil else { return }
    voiceGoneAt[url] = Date()
    Task { [weak self] in
      try? await Task.sleep(nanoseconds: 300_000_000)
      self?.checkVoicePost()
    }
  }

  private var currentDurationMs: Int64 {
    let seconds = player?.currentItem?.duration.seconds ?? 0
    return seconds.isFinite && seconds > 0 ? Int64(seconds * 1000) : 0
  }

  private func playingChanged(_ playing: Bool) {
    isPlaying = playing
    if playing, let id = playerPostID {
      telemetry.videoStarted(postID: id, audible: isAudible, durationMs: currentDurationMs)
    } else if !playing {
      telemetry.videoStopped()
    }
  }

  // MARK: Choosing

  /// At most every 100 ms, with a trailing check once things stop moving.
  private func scheduleEvaluation(immediately: Bool = false) {
    if immediately {
      pendingEvaluation?.cancel()
      pendingEvaluation = nil
      evaluate()
      return
    }
    guard pendingEvaluation == nil else { return }
    let wait = max(0, 0.1 - Date().timeIntervalSince(lastEvaluation))
    pendingEvaluation = Task { [weak self] in
      try? await Task.sleep(nanoseconds: UInt64(wait * 1_000_000_000))
      guard !Task.isCancelled else { return }
      self?.pendingEvaluation = nil
      self?.evaluate()
    }
  }

  private func evaluate() {
    lastEvaluation = Date()
    checkVoicePost()
    let canPlay =
      isPageVisible && !isCovered && UIApplication.shared.applicationState != .background
    telemetry.setActive(canPlay)
    guard canPlay, let winner = pickWinner() else {
      stop()
      setPlayingExternal(nil)
      return
    }
    switch winner {
    case .post(let id, let url):
      setPlayingExternal(nil)
      activate(id, url: url)
    case .external(let id):
      // Like Android's one playback slot: the tutorial playing stops the feed's video.
      stop()
      setPlayingExternal(id)
    }
  }

  private func setPlayingExternal(_ id: String?) {
    guard playingExternalID != id else { return }
    if let previous = playingExternalID {
      externals[previous]?.setPlaying(false)
    }
    playingExternalID = id
    if let id {
      externals[id]?.setPlaying(true)
    }
    Logger.module.info("Express feed video: tutorial \(id == nil ? "paused" : "playing", privacy: .public)")
  }

  /// The part of the window the feed shows (the whole window until the feed has said).
  private var screen: CGRect {
    viewport.isEmpty
      ? (UIApplication.shared.connectedScenes.compactMap { ($0 as? UIWindowScene)?.keyWindow }.first?.bounds ?? .zero)
      : viewport
  }

  private func pickWinner() -> Winner? {
    let screen = self.screen
    guard !screen.isEmpty else { return nil }
    var best: (winner: Winner, share: CGFloat, distance: CGFloat)?
    func consider(_ winner: Winner, frame: CGRect) {
      guard frame.width > 0, frame.height > 0 else { return }
      let visible = frame.intersection(screen)
      guard !visible.isNull else { return }
      let share = (visible.width * visible.height) / (frame.width * frame.height)
      guard share >= 0.5 else { return }
      let distance = abs(frame.midY - screen.midY)
      if let current = best {
        if share > current.share + 0.05
          || (abs(share - current.share) <= 0.05 && distance < current.distance)
        {
          best = (winner, share, distance)
        }
      } else {
        best = (winner, share, distance)
      }
    }
    for (id, candidate) in candidates {
      consider(.post(id: id, url: candidate.url), frame: candidate.frame)
    }
    for (id, external) in externals {
      consider(.external(id: id), frame: external.frame)
    }
    return best?.winner
  }

  /// Android's `pauseAudio` triggers: a voice post stops talking when its row scrolls off, or the
  /// comments sheet or the background takes the window; leaving the new tab page stops it.
  private func checkVoicePost() {
    let audio = ExpressAudioPlayback.shared
    guard let url = audio.currentURL, voiceURLs.contains(url) else { return }
    guard isPageVisible else {
      audio.stop()
      return
    }
    guard audio.isPlaying else { return }
    let onScreen: Bool
    if let frame = voiceFrames[url] {
      let screen = self.screen
      onScreen = screen.isEmpty || frame.intersects(screen)
    } else if let goneAt = voiceGoneAt[url] {
      onScreen = Date().timeIntervalSince(goneAt) < 0.25
    } else {
      onScreen = false
    }
    if isCovered || UIApplication.shared.applicationState == .background || !onScreen {
      audio.pause()
    }
  }

  // MARK: The shared player

  private func activate(_ postID: String, url: URL) {
    if playerPostID != postID {
      releasePlayer()
      let item = AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url))
      let queue = AVQueuePlayer()
      queue.isMuted = !isAudible
      // Muted autoplay mixes with whatever else is playing; sound on takes over.
      queue.automaticallyWaitsToMinimizeStalling = true
      let looper = AVPlayerLooper(player: queue, templateItem: item)
      if let position = positions[postID] {
        queue.seek(to: position, toleranceBefore: .zero, toleranceAfter: .zero)
      }
      player = queue
      self.looper = looper
      playerPostID = postID
      playingID = postID
      generation += 1
      statusObservation = queue.observe(\.timeControlStatus, options: [.initial, .new]) {
        [weak self] player, _ in
        let playing = player.timeControlStatus == .playing
        DispatchQueue.main.async { self?.playingChanged(playing) }
      }
    }
    applySound()
    player?.play()
  }

  private func stop() {
    guard player != nil else { return }
    releasePlayer()
    playingID = nil
    generation += 1
  }

  /// Keeps the position for when the card comes back, then lets the player go.
  private func releasePlayer() {
    telemetry.videoStopped()
    if let player, let id = playerPostID {
      positions[id] = player.currentTime()
      player.pause()
    }
    statusObservation = nil
    looper?.disableLooping()
    looper = nil
    player = nil
    playerPostID = nil
    isPlaying = false
  }

  private func applySound() {
    player?.isMuted = !isAudible
    // A voice note that's playing owns the audio session.
    guard !isVoicePlaying else { return }
    let session = AVAudioSession.sharedInstance()
    if isSoundOn, player != nil {
      try? session.setCategory(.playback, mode: .moviePlayback)
    } else {
      try? session.setCategory(.playback, options: [.mixWithOthers])
    }
  }
}

// MARK: - Player view

/// Shows the shared player while `postID` holds it; clear otherwise, so the poster shows.
struct ExpressFeedPlayerView: UIViewRepresentable {
  @ObservedObject var playback: ExpressFeedVideoPlayback
  let postID: String

  func makeUIView(context: Context) -> PlayerView {
    let view = PlayerView()
    view.backgroundColor = .clear
    view.isUserInteractionEnabled = false
    view.playerLayer.videoGravity = .resizeAspectFill
    return view
  }

  func updateUIView(_ view: PlayerView, context: Context) {
    _ = playback.generation
    let player = playback.player(for: postID)
    if view.playerLayer.player !== player {
      view.playerLayer.player = player
    }
  }

  final class PlayerView: UIView {
    override class var layerClass: AnyClass { AVPlayerLayer.self }
    var playerLayer: AVPlayerLayer { layer as! AVPlayerLayer }
  }
}

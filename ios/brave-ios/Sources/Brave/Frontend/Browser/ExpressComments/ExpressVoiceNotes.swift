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

// Voice notes, ported from Android's `AudioCommentRecorder`, `AudioWaveformView` and the
// voice-note rows of `CommentListAdapter`.

extension ExpressColors {
  static let recordingDot = UIColor(expressLight: 0xE5484D, expressDark: 0xE5484D)
  static let waveformLit = UIColor(expressLight: 0x202124, expressDark: 0xFFFFFF)
  static let voiceRowFill = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let voiceRowStroke = UIColor(expressLight: 0xE7E7E7, expressDark: 0x3A3D45)
  static let voiceTrack = UIColor(expressLight: 0xE7E7E7, expressDark: 0x3A3D45)
  static let voiceTimer = UIColor(expressLight: 0x747480, expressDark: 0xA8ABB5)
  static let draftCardStroke = UIColor(expressLight: 0xE5E5EB, expressDark: 0x3A3D45)
}

// MARK: - Recording

/// Records a voice note: AAC in MPEG-4 at 44.1 kHz and 64 kbps, 0.5 s to 60 s, with a live
/// level for the waveform every 80 ms.
@MainActor
final class ExpressVoiceRecorder: ObservableObject {
  static let maxDuration: TimeInterval = 60
  static let minDuration: TimeInterval = 0.5
  static let barCount = 36

  enum Outcome {
    case kept(URL, durationMs: Int)
    case tooShort
    case cancelled
  }

  @Published private(set) var isRecording = false
  @Published private(set) var elapsed: TimeInterval = 0
  /// The last 36 levels, newest last, each 0.12…1.
  @Published private(set) var levels: [CGFloat] = Array(repeating: 0.12, count: barCount)

  /// Called when the recording stops without the reader: at 60 s the note is kept; when the app
  /// goes to the background, a call comes in, the comments close or a document opens over them,
  /// it's dropped (`.cancelled`, so the owner can say "Recording cancelled" as Android does).
  var onAutoStop: ((Outcome) -> Void)?

  /// The recorder that has the microphone, if any (only one can).
  private static weak var active: ExpressVoiceRecorder?

  /// Drops the recording in progress, if there is one (Android's sheet `onPause`: "A recording
  /// in flight cannot survive the sheet going away").
  static func cancelActive() {
    guard let recorder = active, recorder.isRecording else { return }
    Logger.module.info("Express voice note: recording dropped")
    let outcome = recorder.finish(keep: false)
    recorder.onAutoStop?(outcome)
  }

  private var recorder: AVAudioRecorder?
  private var startedAt: Date?
  private var timer: Timer?

  enum StartError: Error {
    case denied
    case unavailable
  }

  /// Asks for the microphone the first time, then starts.
  func start() async throws {
    guard !isRecording else { return }
    #if DEBUG
    // `-ExpressFakeMicrophone YES`: a generated tone stands in for the microphone, for
    // simulators whose audio input never starts.
    if UserDefaults.standard.bool(forKey: "ExpressFakeMicrophone") {
      ExpressAudioPlayback.shared.stop()
      fakeRecordingURL = ExpressDraftFactory.directory
        .appendingPathComponent("voice_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
      Logger.module.info("Express voice note: recording (fake microphone)")
      beginTicking()
      return
    }
    #endif
    let granted = await AVAudioApplication.requestRecordPermission()
    guard granted else {
      Logger.module.info("Express voice note: microphone permission denied")
      throw StartError.denied
    }
    ExpressAudioPlayback.shared.stop()
    // Comment videos stop before the microphone opens (Android's `startRecording` releases
    // them), so their sound isn't recorded and they don't take the audio session back.
    ExpressCommentsMediaFocus.shared.recordingChanged(true)
    let url = ExpressDraftFactory.directory
      .appendingPathComponent("voice_\(Int(Date().timeIntervalSince1970 * 1000)).m4a")
    // Audio start-up can block while the system brings the input up, so it runs off the main
    // thread and the screen keeps responding.
    let recorder: AVAudioRecorder
    do {
      recorder = try await Task.detached(priority: .userInitiated) { () throws -> AVAudioRecorder in
        let session = AVAudioSession.sharedInstance()
        do {
          try session.setCategory(.playAndRecord, mode: .default, options: [.defaultToSpeaker])
          try session.setActive(true)
        } catch {
          Logger.module.error("Express voice note: audio session failed: \(error.localizedDescription, privacy: .public)")
          throw StartError.unavailable
        }
        let settings: [String: Any] = [
          AVFormatIDKey: kAudioFormatMPEG4AAC,
          AVSampleRateKey: 44_100,
          AVNumberOfChannelsKey: 1,
          AVEncoderBitRateKey: 64_000,
        ]
        guard let recorder = try? AVAudioRecorder(url: url, settings: settings) else {
          Logger.module.error("Express voice note: recorder couldn't be created")
          throw StartError.unavailable
        }
        recorder.isMeteringEnabled = true
        guard recorder.record(forDuration: 60) else {
          Logger.module.error("Express voice note: recording didn't start")
          throw StartError.unavailable
        }
        return recorder
      }.value
    } catch {
      ExpressCommentsMediaFocus.shared.recordingChanged(false)
      throw error
    }
    // The app went to the background while the microphone was starting: don't record there.
    guard UIApplication.shared.applicationState != .background else {
      recorder.stop()
      recorder.deleteRecording()
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
      ExpressCommentsMediaFocus.shared.recordingChanged(false)
      Logger.module.info("Express voice note: app left while the microphone started")
      throw StartError.unavailable
    }
    Logger.module.info("Express voice note: recording")
    self.recorder = recorder
    beginTicking()
  }

  private func beginTicking() {
    startedAt = Date()
    elapsed = 0
    levels = Array(repeating: 0.12, count: Self.barCount)
    isRecording = true
    Self.active = self
    ExpressCommentsMediaFocus.shared.recordingChanged(true)
    UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
    timer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
      MainActor.assumeIsolated { self?.tick() }
    }
  }

  private func tick() {
    guard let startedAt else { return }
    elapsed = min(Self.maxDuration, Date().timeIntervalSince(startedAt))
    // Android: sqrt(maxAmplitude / 32767). The meter's peak in dB maps to the same 0…1.
    let linear: Double
    if let recorder {
      recorder.updateMeters()
      linear = pow(10, Double(recorder.peakPower(forChannel: 0)) / 20)
    } else {
      linear = Self.fakeAmplitude(at: elapsed)
    }
    let level = max(0.12, CGFloat(sqrt(min(max(linear, 0), 1))))
    levels.removeFirst()
    levels.append(level)
    if elapsed >= Self.maxDuration {
      onAutoStop?(finish(keep: true))
    }
  }

  /// Stops; keeps the file when asked and long enough.
  @discardableResult
  func finish(keep: Bool) -> Outcome {
    timer?.invalidate()
    timer = nil
    guard let startedAt, let url = recorder?.url ?? fakeRecordingURL else { return .cancelled }
    let duration = min(Self.maxDuration, Date().timeIntervalSince(startedAt))
    if let recorder {
      recorder.stop()
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
    } else if keep, duration >= Self.minDuration {
      Self.writeFakeRecording(to: url, duration: duration)
    }
    recorder = nil
    fakeRecordingURL = nil
    self.startedAt = nil
    isRecording = false
    if Self.active === self { Self.active = nil }
    ExpressCommentsMediaFocus.shared.recordingChanged(false)
    guard keep else {
      try? FileManager.default.removeItem(at: url)
      return .cancelled
    }
    guard duration >= Self.minDuration else {
      try? FileManager.default.removeItem(at: url)
      return .tooShort
    }
    return .kept(url, durationMs: Int(duration * 1000))
  }

  // MARK: Fake microphone (Debug)

  /// Where the fake microphone's note goes; nil unless `-ExpressFakeMicrophone YES` is recording.
  private var fakeRecordingURL: URL?

  /// A speech-like 0…1 envelope: syllables about 7 a second, phrases about every 2.5 s.
  private static func fakeAmplitude(at t: TimeInterval) -> Double {
    let syllable = abs(sin(t * 7 * .pi / 2))
    let phrase = 0.35 + 0.65 * abs(sin(t * .pi / 2.5))
    return syllable * phrase
  }

  /// Writes the fake note: a 220 Hz tone shaped by `fakeAmplitude`, in the recorder's format.
  private static func writeFakeRecording(to url: URL, duration: TimeInterval) {
    let settings: [String: Any] = [
      AVFormatIDKey: kAudioFormatMPEG4AAC,
      AVSampleRateKey: 44_100,
      AVNumberOfChannelsKey: 1,
      AVEncoderBitRateKey: 64_000,
    ]
    do {
      let file = try AVAudioFile(forWriting: url, settings: settings)
      let format = file.processingFormat
      let total = AVAudioFrameCount(duration * format.sampleRate)
      let chunk: AVAudioFrameCount = 4096
      var written: AVAudioFrameCount = 0
      while written < total {
        let count = min(chunk, total - written)
        guard let buffer = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: count),
          let samples = buffer.floatChannelData?[0]
        else { break }
        buffer.frameLength = count
        for i in 0..<Int(count) {
          let t = Double(Int(written) + i) / format.sampleRate
          samples[i] = Float(0.5 * fakeAmplitude(at: t) * sin(2 * .pi * 220 * t))
        }
        try file.write(from: buffer)
        written += count
      }
    } catch {
      Logger.module.error("Express voice note: fake recording failed: \(error.localizedDescription, privacy: .public)")
    }
  }

  var elapsedText: String {
    let seconds = Int(elapsed)
    return String(format: "%d:%02d", seconds / 60, seconds % 60)
  }
}

/// Android's waveform: bars 55% of their slot, rounded, centred; newest on the right.
struct ExpressWaveformView: View {
  let levels: [CGFloat]
  var color = Color(ExpressColors.waveformLit)

  var body: some View {
    Canvas { context, size in
      guard !levels.isEmpty else { return }
      let slot = size.width / CGFloat(levels.count)
      let barWidth = max(1, slot * 0.55)
      for (index, level) in levels.enumerated() {
        let height = max(barWidth, level * size.height)
        let rect = CGRect(
          x: CGFloat(index) * slot + (slot - barWidth) / 2,
          y: (size.height - height) / 2,
          width: barWidth,
          height: height
        )
        context.fill(Path(roundedRect: rect, cornerRadius: barWidth / 2), with: .color(color))
      }
    }
    .accessibilityHidden(true)
  }
}

// MARK: - Playback

/// Plays one voice note at a time, app-wide (Android's global audio rule). At the end it rewinds.
@MainActor
final class ExpressAudioPlayback: ObservableObject {
  static let shared = ExpressAudioPlayback()

  @Published private(set) var currentURL: URL?
  @Published private(set) var isPlaying = false
  @Published private(set) var position: TimeInterval = 0
  @Published private(set) var duration: TimeInterval = 0

  /// The comment row (`ExpressVoiceNoteRow`) that last played or paused the current note; nil
  /// when it was started elsewhere (a feed voice post). Only that row going away stops it.
  private(set) var rowOwner: UUID?

  private var player: AVPlayer?
  private var timeObserver: Any?
  private var endObserver: NSObjectProtocol?
  /// Notices the system pausing the note (a call, headphones unplugged).
  private var rateObservation: NSKeyValueObservation?
  /// Bumped for every new player, so late callbacks from an old one are ignored.
  private var generation = 0
  /// Waits for a note that starts part-way in to be ready to seek.
  private var startObservation: NSKeyValueObservation?
  /// True until that first seek lands; the player still reads 0 meanwhile.
  private var isSeekingToStart = false

  /// Plays or pauses `url`. A note that isn't the current one starts at `start` seconds (a feed
  /// voice post keeps its place while another one plays, as each Android card keeps its player).
  func toggle(_ url: URL, startingAt start: TimeInterval = 0) {
    if currentURL == url, let player {
      if isPlaying {
        player.pause()
        isPlaying = false
      } else {
        activateSession()
        player.play()
        isPlaying = true
      }
      return
    }
    stop()
    activateSession()
    let item = AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url))
    let player = AVPlayer(playerItem: item)
    self.player = player
    currentURL = url
    position = max(0, start)
    duration = 0
    timeObserver = player.addPeriodicTimeObserver(
      forInterval: CMTime(value: 60, timescale: 1000),
      queue: .main
    ) { [weak self] time in
      MainActor.assumeIsolated {
        guard let self else { return }
        if !self.isSeekingToStart {
          self.position = time.seconds.isFinite ? time.seconds : 0
        }
        if let seconds = self.player?.currentItem?.duration.seconds, seconds.isFinite, seconds > 0 {
          self.duration = seconds
        }
      }
    }
    endObserver = NotificationCenter.default.addObserver(
      forName: .AVPlayerItemDidPlayToEndTime,
      object: item,
      queue: .main
    ) { [weak self] _ in
      MainActor.assumeIsolated {
        self?.player?.seek(to: .zero)
        self?.position = 0
        self?.isPlaying = false
      }
    }
    let rateGeneration = generation
    rateObservation = player.observe(\.rate, options: [.new]) { [weak self] _, _ in
      DispatchQueue.main.async {
        MainActor.assumeIsolated { self?.rateChanged(generation: rateGeneration) }
      }
    }
    isPlaying = true
    guard start > 0 else {
      player.play()
      return
    }
    // An item can't seek until it's ready: wait for that, seek, then play (unless paused since).
    let generation = self.generation
    isSeekingToStart = true
    startObservation = item.observe(\.status, options: [.initial, .new]) { [weak self] item, _ in
      let status = item.status
      guard status != .unknown else { return }
      DispatchQueue.main.async {
        MainActor.assumeIsolated {
          self?.beginPartWay(generation: generation, at: start, ready: status == .readyToPlay)
        }
      }
    }
  }

  private func beginPartWay(generation: Int, at start: TimeInterval, ready: Bool) {
    guard generation == self.generation, startObservation != nil, let player else { return }
    startObservation = nil
    guard ready else {
      // It failed to load; let the player report that the usual way.
      isSeekingToStart = false
      if isPlaying { player.play() }
      return
    }
    player.seek(
      to: CMTime(seconds: start, preferredTimescale: 600),
      toleranceBefore: .zero,
      toleranceAfter: .zero
    ) { [weak self] _ in
      DispatchQueue.main.async {
        MainActor.assumeIsolated {
          guard let self, generation == self.generation else { return }
          self.isSeekingToStart = false
          if self.isPlaying { self.player?.play() }
        }
      }
    }
  }

  /// The player stopped by itself while the note should be playing: the system paused it (a
  /// call, headphones unplugged). The rows show it paused, so play starts it again.
  private func rateChanged(generation: Int) {
    guard generation == self.generation, let player, player.rate == 0, isPlaying,
      !isSeekingToStart
    else { return }
    isPlaying = false
  }

  /// `row` played or paused `url`: it owns the note now.
  func claim(_ url: URL, row: UUID) {
    if currentURL == url { rowOwner = row }
  }

  /// The row that owns the note scrolled off screen: the note pauses and keeps its place.
  func rowLeftScreen(_ url: URL, row: UUID) {
    guard currentURL == url, rowOwner == row else { return }
    pause()
  }

  /// The row that owns the note went away (Android releases a recycled row's player).
  func rowWentAway(_ url: URL, row: UUID) {
    guard currentURL == url, rowOwner == row else { return }
    stop()
  }

  func seek(_ url: URL, to fraction: Double) {
    guard currentURL == url, let player, duration > 0 else { return }
    let target = duration * min(max(fraction, 0), 1)
    position = target
    player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
  }

  /// Pauses the note that's playing and keeps its place (Android pauses a voice post when the
  /// feed loses the window or the post scrolls away).
  func pause() {
    guard isPlaying else { return }
    player?.pause()
    isPlaying = false
  }

  func stop() {
    if let timeObserver { player?.removeTimeObserver(timeObserver) }
    if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
    timeObserver = nil
    endObserver = nil
    startObservation = nil
    rateObservation = nil
    isSeekingToStart = false
    generation += 1
    player?.pause()
    player = nil
    currentURL = nil
    rowOwner = nil
    isPlaying = false
    position = 0
  }

  private func activateSession() {
    try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
    try? AVAudioSession.sharedInstance().setActive(true)
  }
}

/// A voice note's player row: play/pause, a seek bar and the time (Android's 64 dp row). Shows
/// the total until it plays, then the position. A note this row started pauses when the row
/// scrolls off screen and stops when the row goes away (Android's recycled row releases its
/// player).
struct ExpressVoiceNoteRow: View {
  let url: URL
  let durationMs: Int
  /// Draft previews count down the time left, as Android's preview does.
  var countsDown = false

  @ObservedObject private var playback = ExpressAudioPlayback.shared
  @State private var dragFraction: Double?
  /// This row, as the owner of the note it plays.
  @State private var rowID = UUID()
  @State private var isOnScreen = true

  private var isCurrent: Bool { playback.currentURL == url }

  private var total: TimeInterval {
    if isCurrent, playback.duration > 0 { return playback.duration }
    return Double(durationMs) / 1000
  }

  private var fraction: Double {
    if let dragFraction { return dragFraction }
    guard isCurrent, total > 0 else { return 0 }
    return min(1, playback.position / total)
  }

  private var timeText: String {
    let shown: TimeInterval
    if countsDown {
      shown = total - (isCurrent ? playback.position : 0)
    } else {
      shown = isCurrent && (playback.isPlaying || playback.position > 0) ? playback.position : total
    }
    return ExpressDraftFactory.durationText(milliseconds: Int(max(0, shown) * 1000))
  }

  var body: some View {
    HStack(spacing: 10) {
      Button {
        playback.toggle(url)
        playback.claim(url, row: rowID)
      } label: {
        Circle()
          .fill(Color(ExpressColors.accent))
          .frame(width: 43, height: 43)
          .overlay(
            Image(systemName: isCurrent && playback.isPlaying ? "pause.fill" : "play.fill")
              .font(.system(size: 17, weight: .semibold))
              .foregroundColor(.white)
          )
      }
      .buttonStyle(.plain)
      .accessibilityLabel(isCurrent && playback.isPlaying ? "Pause voice message" : "Play voice message")

      GeometryReader { proxy in
        ZStack(alignment: .leading) {
          RoundedRectangle(cornerRadius: 2).fill(Color(ExpressColors.voiceTrack)).frame(height: 4)
          RoundedRectangle(cornerRadius: 2)
            .fill(Color(ExpressColors.accent))
            .frame(width: proxy.size.width * fraction, height: 4)
          Circle()
            .fill(Color(ExpressColors.accent))
            .frame(width: 16, height: 16)
            .offset(x: proxy.size.width * fraction - 8)
        }
        .frame(maxHeight: .infinity)
        .contentShape(Rectangle())
        .gesture(
          DragGesture(minimumDistance: 0)
            .onChanged { value in
              dragFraction = min(max(value.location.x / max(proxy.size.width, 1), 0), 1)
            }
            .onEnded { value in
              let target = min(max(value.location.x / max(proxy.size.width, 1), 0), 1)
              if isCurrent {
                playback.seek(url, to: target)
              } else {
                // A note that isn't loaded can't seek yet; it starts there instead.
                playback.toggle(url, startingAt: target * total)
                playback.claim(url, row: rowID)
              }
              dragFraction = nil
            }
        )
      }
      .frame(height: 24)
      .accessibilityHidden(true)

      Text(timeText)
        .font(.system(size: 12, weight: .semibold).monospacedDigit())
        .foregroundColor(Color(ExpressColors.voiceTimer))
    }
    .padding(.horizontal, 12)
    .frame(height: 64)
    .background(
      RoundedRectangle(cornerRadius: 16)
        .fill(Color(ExpressColors.voiceRowFill))
        .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(ExpressColors.voiceRowStroke), lineWidth: 1))
    )
    .accessibilityElement(children: .contain)
    .accessibilityLabel("Voice message, \(ExpressDraftFactory.durationText(milliseconds: Int(total * 1000)))")
    .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
      guard !frame.isEmpty else { return }
      let onScreen = frame.intersects(Self.screenBounds)
      guard onScreen != isOnScreen else { return }
      isOnScreen = onScreen
      if !onScreen { playback.rowLeftScreen(url, row: rowID) }
    }
    .onDisappear { playback.rowWentAway(url, row: rowID) }
  }

  /// The window the row is measured against (its `.global` space).
  private static var screenBounds: CGRect {
    UIApplication.shared.connectedScenes
      .compactMap { ($0 as? UIWindowScene)?.keyWindow }
      .first?.bounds ?? UIScreen.main.bounds
  }
}

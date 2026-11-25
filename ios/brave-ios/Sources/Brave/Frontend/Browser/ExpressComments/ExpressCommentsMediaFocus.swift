// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import Combine
import ObjectiveC
import SafariServices
import SwiftUI
import UIKit
import os.log

/// The comments' app-wide sound rules, after Android's `GlobalVideoPlaybackManager` holds and
/// the comments sheet's `onPause`/`onResume`:
/// - while the app isn't active, comment videos hold, and pick up again when it's back;
/// - going to the background pauses the voice note and drops a recording in progress;
/// - something open over the comments (a document) holds their videos and pauses the voice note;
/// - while a voice note records, no comment video plays;
/// - closing the comments stops their voice note and drops a recording.
///
/// The reel (`ExpressReelPlayback`) and list videos (`ExpressListVideoAutoplay`) follow these
/// states; the web view's own media (YouTube background play) is never touched.
@MainActor
final class ExpressCommentsMediaFocus: ObservableObject {
  static let shared = ExpressCommentsMediaFocus()

  /// The app is active: not behind Control Center, the app switcher or the home screen.
  @Published private(set) var isAppActive: Bool
  /// Something is open over the comments (a document's browser).
  @Published private(set) var isCovered = false
  /// A voice note is recording (or its microphone is starting).
  @Published private(set) var isRecording = false

  private var covers: Set<UUID> = []
  private var observers: [NSObjectProtocol] = []

  private init() {
    isAppActive = UIApplication.shared.applicationState == .active
    let center = NotificationCenter.default
    observers.append(
      center.addObserver(
        forName: UIApplication.willResignActiveNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.isAppActive = false }
      }
    )
    observers.append(
      center.addObserver(
        forName: UIApplication.didEnterBackgroundNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.enteredBackground() }
      }
    )
    observers.append(
      center.addObserver(
        forName: UIApplication.didBecomeActiveNotification,
        object: nil,
        queue: .main
      ) { [weak self] _ in
        MainActor.assumeIsolated { self?.isAppActive = true }
      }
    )
    observers.append(
      center.addObserver(
        forName: AVAudioSession.interruptionNotification,
        object: nil,
        queue: .main
      ) { note in
        let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt
        guard raw.flatMap(AVAudioSession.InterruptionType.init(rawValue:)) == .began else { return }
        // A call took the microphone: the recording can't carry on.
        MainActor.assumeIsolated { ExpressVoiceRecorder.cancelActive() }
      }
    )
  }

  /// The app went to the background: background audio is declared for YouTube, so a comment's
  /// voice note and recording would otherwise carry on (Android's sheet `onPause`).
  private func enteredBackground() {
    isAppActive = false
    ExpressAudioPlayback.shared.pause()
    ExpressVoiceRecorder.cancelActive()
  }

  /// The comments closed, by any route: their voice note stops and a recording is dropped
  /// (Android's `releaseAllResources` and sheet `onPause`). A feed voice post that the comments
  /// only paused keeps its place.
  func commentsClosed() {
    covers.removeAll()
    if isCovered { isCovered = false }
    ExpressVoiceRecorder.cancelActive()
    // Nothing records once the comments are gone, even if a composer went without finishing.
    if isRecording { isRecording = false }
    let audio = ExpressAudioPlayback.shared
    if audio.isPlaying || audio.rowOwner != nil {
      audio.stop()
    }
  }

  /// Something opens over the comments: their videos hold until the returned cover ends (or is
  /// let go). As Android's custom tab pauses the sheet, the voice note pauses and a recording
  /// is dropped.
  func beginCover() -> ExpressCommentsCover {
    let cover = ExpressCommentsCover()
    covers.insert(cover.id)
    if !isCovered { isCovered = true }
    ExpressAudioPlayback.shared.pause()
    ExpressVoiceRecorder.cancelActive()
    return cover
  }

  fileprivate func endCover(_ id: UUID) {
    guard covers.remove(id) != nil, covers.isEmpty else { return }
    isCovered = false
  }

  /// Called by `ExpressVoiceRecorder` as the microphone opens and closes.
  func recordingChanged(_ recording: Bool) {
    if isRecording != recording { isRecording = recording }
  }
}

/// One thing open over the comments. `end()` uncovers them; a cover that is let go without
/// ending ends by itself.
final class ExpressCommentsCover {
  let id = UUID()

  @MainActor func end() {
    ExpressCommentsMediaFocus.shared.endCover(id)
  }

  deinit {
    let id = self.id
    DispatchQueue.main.async {
      MainActor.assumeIsolated { ExpressCommentsMediaFocus.shared.endCover(id) }
    }
  }
}

/// Covers the comments while `isActive` (Quick Look showing a file over them): their videos
/// hold and a voice note pauses, then the videos pick up again.
struct ExpressCommentsCoverWhile: ViewModifier {
  let isActive: Bool
  @State private var cover: ExpressCommentsCover?

  func body(content: Content) -> some View {
    content
      .onChange(of: isActive, initial: true) { _, active in
        if active, cover == nil {
          cover = ExpressCommentsMediaFocus.shared.beginCover()
        } else if !active, let cover {
          cover.end()
          self.cover = nil
        }
      }
  }
}

// MARK: - Documents

/// Opens a posted document in an in-app Safari view over the comments, as Android opens a custom
/// tab over the sheet: closing it lands the reader back on the same comment. The comments'
/// videos hold while it's up and pick up again after.
@MainActor
enum ExpressDocumentBrowser {
  /// Presents `url` over `presenter` (or whatever it is already presenting). Returns false for a
  /// link the Safari view can't show (anything but http and https).
  @discardableResult
  static func present(_ url: URL, over presenter: UIViewController) -> Bool {
    guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
      return false
    }
    var top = presenter
    while let next = top.presentedViewController, !next.isBeingDismissed {
      top = next
    }
    let configuration = SFSafariViewController.Configuration()
    configuration.entersReaderIfAvailable = false
    let safari = SFSafariViewController(url: url, configuration: configuration)
    safari.dismissButtonStyle = .close
    // Over the comments rather than instead of them, so the sheet underneath stays as it was.
    safari.modalPresentationStyle = .overFullScreen
    safari.modalPresentationCapturesStatusBarAppearance = true
    let session = ExpressDocumentSession(cover: ExpressCommentsMediaFocus.shared.beginCover())
    safari.delegate = session
    // The session lives as long as the Safari view; letting it go ends the cover.
    objc_setAssociatedObject(safari, &expressDocumentSessionKey, session, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    Logger.module.info("Express document: opening over the comments")
    top.present(safari, animated: true)
    return true
  }
}

private var expressDocumentSessionKey: UInt8 = 0

private final class ExpressDocumentSession: NSObject, SFSafariViewControllerDelegate {
  let cover: ExpressCommentsCover

  init(cover: ExpressCommentsCover) {
    self.cover = cover
  }

  /// Close (or the edge swipe): the comments pick up at once, while the Safari view slides away.
  func safariViewControllerDidFinish(_ controller: SFSafariViewController) {
    MainActor.assumeIsolated { cover.end() }
  }
}

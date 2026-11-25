// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Combine
import ExpressCore
import SwiftUI
import UIKit
import os.log

// Sending comments (Android's `CommentUploadManager` and `TakeBanner`): every send, text alone
// too, is a job that shows as the reader's own "You" card until the server has it. Each file goes
// to storage on its own presigned PUT, then the comment is created with the uploaded keys.
// Uploads carry on while the reader moves around the sheet; a retry resends only what didn't
// arrive, and a failed send keeps its card (Retry, Cancel) until one of them is tapped.

extension ExpressColors {
  static let outgoingStroke = UIColor(expressLight: 0xB8BAC7, expressDark: 0x4A4D57)
  /// "You" on the reader's own sending or just-posted card.
  static let outgoingAuthor = UIColor(expressLight: 0x6B6B7A, expressDark: 0xB4B7C2)
  static let failedStroke = UIColor(expressLight: 0xE05961, expressDark: 0xE05961)
  static let progressTrack = UIColor(expressLight: 0xDBDEE5, expressDark: 0x3A3D45)
  static let retryFill = UIColor(expressLight: 0xF5E5E8, expressDark: 0x3B2329)
  static let retryText = UIColor(expressLight: 0xD13840, expressDark: 0xFF7373)
  static let cancelFill = UIColor(expressLight: 0xEEF0F6, expressDark: 0x2C2F38)
  static let cancelText = UIColor(expressLight: 0x61667A, expressDark: 0xC7CCDB)
  static let bannerTitle = UIColor(expressLight: 0x1F212E, expressDark: 0xF4F5F7)
  static let bannerBody = UIColor(expressLight: 0x80808C, expressDark: 0xA8ABB5)
  static let bannerAction = UIColor(expressLight: 0x577DFF, expressDark: 0x6EA8FF)
  static let bannerTrack = UIColor(expressLight: 0xE6EAF5, expressDark: 0x3A3D45)
  static let postedDot = UIColor(expressLight: 0x2FBF71, expressDark: 0x2FBF71)
  static let failedDot = UIColor(expressLight: 0xE61A33, expressDark: 0xE61A33)
}

extension ExpressCommentParent {
  /// Which list a comment lands in.
  var listKey: String {
    switch self {
    case .page(let url): return "page:\(url.absoluteString)"
    case .post(let id): return "post:\(id)"
    case .comment(let id, _): return "comment:\(id)"
    }
  }

  var isReply: Bool {
    if case .comment = self { return true }
    return false
  }
}

@MainActor
final class ExpressCommentUploads: ObservableObject {
  static let shared = ExpressCommentUploads()

  enum State: Equatable {
    case uploading
    case failed(String?)
    case posted
    case cancelled
  }

  final class Job: ObservableObject, Identifiable {
    let id = UUID()
    let parent: ExpressCommentParent
    let content: String
    let items: [ExpressDraftItem]
    /// "#tag" or the topic, for "Added to …".
    let topic: String?
    let startedAt = Date()
    @Published var state: State = .uploading
    /// 0…1 while files upload; negative until known.
    @Published var progress: Double = -1
    @Published var bannerHidden = false
    var posted: ExpressComment?
    var uploadedKeys: [UUID: String] = [:]
    var task: Task<Void, Never>?
    /// Sends so far, retries included.
    var attempts = 0

    init(parent: ExpressCommentParent, content: String, items: [ExpressDraftItem], topic: String?) {
      self.parent = parent
      self.content = content
      self.items = items
      self.topic = topic
    }

    var hasFiles: Bool { !items.isEmpty }
    var hasVideo: Bool { items.contains { $0.kind == .video } }

    var isFailed: Bool {
      if case .failed = state { return true }
      return false
    }

    /// Still a card of its own: on its way up, or failed and waiting for Retry or Cancel.
    var isOutgoing: Bool { state == .uploading || isFailed }

    /// One portrait video: a reel.
    var isReel: Bool {
      items.count == 1 && items[0].kind == .video && items[0].height > items[0].width
    }

    var statusText: String {
      switch state {
      case .uploading:
        if hasVideo, progress >= 0 { return "Uploading \(Int(progress * 100))%" }
        if items.count == 1, items[0].kind == .audio { return "Uploading…" }
        return "Sending…"
      case .failed: return "Upload failed"
      case .posted, .cancelled: return "now"
      }
    }
  }

  @Published private(set) var jobs: [Job] = []
  /// Comments posted in the last 3 s, which wear "You" and the accent outline (Android's
  /// `POSTED_HIGHLIGHT_MS`).
  @Published private(set) var justPostedIDs: Set<String> = []
  /// A comment arrived on the server: (list key, comment). Sent on the main actor, straight after
  /// its job turns `.posted`, so a screen swaps the card for the comment in one update.
  let posted = PassthroughSubject<(String, ExpressComment), Never>()
  /// A cancelled upload hands its text and files back to the comment box: (list key, text, files).
  let restored = PassthroughSubject<(String, String, [ExpressDraftItem]), Never>()
  /// A send began or was retried: (list key, job). The screen showing that list brings the card
  /// into view (Android's `addToVisiblePage` / `repackAround`).
  let focusRequested = PassthroughSubject<(String, Job), Never>()
  /// A one-off line about the reader's sends, such as "Username … created": the comments screen
  /// that is showing says it (`ExpressCommentNoticeToasts`), or the browser once the comments
  /// have closed.
  let notices = PassthroughSubject<ExpressCommentNotice, Never>()

  func jobs(for listKey: String) -> [Job] {
    jobs.filter { $0.parent.listKey == listKey }
  }

  /// A send made the reader an account: Android's "Username … created. You can edit this in
  /// Profile." (`CommentListFragment` / `ReplyListFragment` after a post that returned tokens).
  func announceAccountCreated(username: String) {
    guard !username.isEmpty else { return }
    Logger.module.info("Express comments: a send created the account")
    notices.send(ExpressCommentNotice(message: "Username \(username) created. You can edit this in Profile."))
  }

  /// Keeps the tokens a send came back with. A refresh token along with them means the send made
  /// a new account, as Android checks (`newRefreshToken` not empty), and that's announced with
  /// the username in the new token.
  func storeTokens(_ tokens: ExpressGuestTokens?) {
    guard let tokens else { return }
    ExpressAccount.shared.store(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
    guard tokens.refreshToken?.isEmpty == false,
      let username = ExpressJWT(token: tokens.accessToken)?.username
    else { return }
    announceAccountCreated(username: username)
  }

  func start(content: String, items: [ExpressDraftItem], parent: ExpressCommentParent, topic: String?) {
    let job = Job(parent: parent, content: content, items: items, topic: topic)
    jobs.append(job)
    run(job)
    focusRequested.send((parent.listKey, job))
  }

  func retry(_ job: Job) {
    guard job.isFailed else { return }
    setState(job, .uploading)
    job.progress = -1
    job.bannerHidden = false
    run(job)
    focusRequested.send((job.parent.listKey, job))
  }

  /// Stops an upload, or gives up on a failed one; the text and files go back into the box.
  func cancel(_ job: Job) {
    guard job.isOutgoing else { return }
    setState(job, .cancelled)
    job.task?.cancel()
    jobs.removeAll { $0.id == job.id }
    restored.send((job.parent.listKey, job.content, job.items))
  }

  func remove(_ job: Job) {
    job.task?.cancel()
    jobs.removeAll { $0.id == job.id }
  }

  /// Job states are published by the job itself; screens that list the jobs (the reel packs
  /// their cards, the banner picks one) hear about it here too.
  private func setState(_ job: Job, _ state: State) {
    objectWillChange.send()
    job.state = state
  }

  /// × or a swipe on the banner, or a failed one stepping aside: the banner then shows the next
  /// job, if any. An upload stays hidden until it posts or fails.
  func hideBanner(_ job: Job) {
    objectWillChange.send()
    job.bannerHidden = true
  }

  private func run(_ job: Job) {
    job.attempts += 1
    job.task = Task { [weak self] in
      await self?.upload(job)
    }
  }

  private func upload(_ job: Job) async {
    let account = ExpressAccount.shared
    let client = ExpressAPIClient.shared
    var token = account.validAccessToken
    let total = max(1, job.items.reduce(Int64(0)) { $0 + $1.size })

    if ExpressDebug.isDryRun {
      Logger.module.info(
        "Express dry run: skipped presign, upload and POST comment (\(job.items.count, privacy: .public) files)"
      )
      // Files climb to 100% over a few seconds (the % and Cancel can be watched); text alone
      // shows "Sending…" briefly. `-ExpressFakeUploadFailure` stops it part way, as a dropped
      // connection would.
      let fails = ExpressDebug.fakesUploadFailure(attempt: job.attempts)
      let steps = job.hasFiles ? 20 : 1
      let stepNanos = UInt64((job.hasFiles ? ExpressDebug.dryRunUploadSeconds : 1.2) / Double(steps) * 1e9)
      for step in 1...steps {
        try? await Task.sleep(nanoseconds: stepNanos)
        guard !Task.isCancelled, job.state == .uploading else { return }
        let fraction = Double(step) / Double(steps)
        if job.hasFiles { job.progress = fraction }
        if fails, fraction >= 0.45 {
          Logger.module.info("Express dry run: faked an upload failure (try \(job.attempts, privacy: .public))")
          fail(job, message: nil, error: "Debug: fake upload failure")
          return
        }
      }
      let profile = ExpressProfileModel.shared
      if ExpressDebug.fakesAccountCreated, let username = profile.me?.username ?? account.claims?.username {
        // `-ExpressFakeAccountCreated YES`: as if the post had come back with a new account.
        announceAccountCreated(username: username)
      }
      if let comment = ExpressComment.local(
        id: "dryrun_\(UUID().uuidString.prefix(12))",
        content: job.content,
        userID: account.claims?.userID,
        username: profile.me?.username ?? account.claims?.username,
        avatar: profile.avatarURL?.absoluteString,
        attachments: job.items.map(\.asAttachment)
      ) {
        finish(job, with: comment)
      } else {
        fail(job, message: nil, error: "Dry run: couldn't make the local comment")
      }
      return
    }

    do {
      var uploaded: [ExpressUploadedAttachment] = []
      var done: Int64 = 0
      for item in job.items {
        try Task.checkCancellation()
        if let key = job.uploadedKeys[item.id] {
          uploaded.append(Self.attachment(item, key: key))
          done += item.size
          continue
        }
        let signed: ExpressPresignedUpload
        do {
          signed = try await client.presignAttachment(
            kind: item.kind,
            mime: item.mime,
            size: item.size,
            name: item.name,
            accessToken: token
          )
        } catch is ExpressPresignUnavailable {
          // An older server: one file can still go through the multipart route.
          guard job.items.count == 1 else {
            throw ExpressAPIError.server("Couldn’t post your comment")
          }
          let result = try await client.postCommentWithMedia(
            job.content,
            to: job.parent,
            file: item.fileURL,
            kind: item.kind,
            mime: item.mime,
            name: item.name,
            width: item.width,
            height: item.height,
            durationMs: item.durationMs,
            accessToken: token
          )
          storeTokens(result.tokens)
          finish(job, with: result.comment)
          return
        }
        if let tokens = signed.tokens {
          token = tokens.accessToken
          storeTokens(tokens)
        }
        let before = done
        try await client.uploadAttachment(item.fileURL, to: signed) { sent in
          Task { @MainActor in
            guard job.state == .uploading else { return }
            job.progress = min(1, Double(before + sent) / Double(total))
          }
        }
        done += item.size
        job.uploadedKeys[item.id] = signed.key
        uploaded.append(Self.attachment(item, key: signed.key))
      }
      try Task.checkCancellation()
      job.progress = 1
      let result = try await client.postComment(
        job.content,
        to: job.parent,
        attachments: uploaded,
        accessToken: token
      )
      storeTokens(result.tokens)
      finish(job, with: result.comment)
    } catch {
      guard job.state == .uploading, !(error is CancellationError) else { return }
      Logger.module.error("Express comment upload failed: \(error.localizedDescription, privacy: .public)")
      if case ExpressAPIError.server(let message) = error {
        fail(job, message: message, error: error.localizedDescription)
      } else {
        fail(job, message: nil, error: error.localizedDescription)
      }
    }
  }

  /// The card turns red with Retry (and Cancel), and the banner says so for a few seconds.
  private func fail(_ job: Job, message: String?, error: String) {
    setState(job, .failed(message))
    job.bannerHidden = false
    capture(job, error: error)
  }

  private static func attachment(_ item: ExpressDraftItem, key: String) -> ExpressUploadedAttachment {
    ExpressUploadedAttachment(
      key: key,
      kind: item.kind,
      name: item.name,
      width: item.width,
      height: item.height,
      durationMs: item.durationMs
    )
  }

  private func capture(_ job: Job, error: String?) {
    let parentType: String
    switch job.parent {
    case .page: parentType = "page"
    case .post: parentType = "post"
    case .comment: parentType = "comment"
    }
    ExpressAnalytics.shared.capture(
      error == nil ? "Comment Upload Completed" : "Comment Upload Failed",
      [
        "parent_type": parentType, "files": job.items.count,
        "kinds": job.items.map(\.kind.rawValue).joined(separator: ","),
        "bytes": job.items.reduce(Int64(0)) { $0 + $1.size },
        "duration_ms": Int64(Date().timeIntervalSince(job.startedAt) * 1000), "error": error,
      ]
    )
  }

  private func finish(_ job: Job, with comment: ExpressComment) {
    var comment = comment
    Self.copyMediaSize(from: job, into: &comment)
    capture(job, error: nil)
    job.posted = comment
    setState(job, .posted)
    job.bannerHidden = false
    justPostedIDs.insert(comment.id)
    posted.send((job.parent.listKey, comment))
    ExpressPushManager.shared.promptAfterFirstComment()
    let id = comment.id
    // "You" and the accent outline stay 3 s (Android's `POSTED_HIGHLIGHT_MS`).
    Task { @MainActor [weak self] in
      try? await Task.sleep(nanoseconds: 3_000_000_000)
      self?.justPostedIDs.remove(id)
    }
    // The "posted" banner shows for 4 s.
    Task { @MainActor [weak self] in
      try? await Task.sleep(nanoseconds: 4_000_000_000)
      self?.jobs.removeAll { $0.id == job.id }
    }
  }

  /// The server's copy comes without a media size, and the reel only probes sizes when it loads.
  /// A lone photo or video's size is known from the draft, so a portrait take lands on its own
  /// full-screen page straight away (Android's `updateTemporaryComment` carries it over too).
  private static func copyMediaSize(from job: Job, into comment: inout ExpressComment) {
    guard comment.mediaWidth <= 0 || comment.mediaHeight <= 0, job.items.count == 1,
      let item = job.items.first, item.isVisual, item.width > 0, item.height > 0
    else { return }
    comment.mediaWidth = item.width
    comment.mediaHeight = item.height
  }
}

// MARK: - Notices

/// One line for the reader about a send (`ExpressCommentUploads.notices`). The first place to
/// claim it shows it, so it shows once wherever the reader is.
@MainActor
final class ExpressCommentNotice {
  let message: String
  private(set) var isClaimed = false

  init(message: String) {
    self.message = message
  }

  /// True for the first caller only, which then shows it.
  func claim() -> Bool {
    guard !isClaimed else { return false }
    isClaimed = true
    return true
  }
}

/// Shows the sends' notices ("Username … created") as toasts on this comments screen while it's
/// the one on show. Goes inside the screen's `expressToastHost`.
struct ExpressCommentNoticeToasts: ViewModifier {
  @Environment(\.expressToast) private var toast
  @State private var isShowing = false

  func body(content: Content) -> some View {
    content
      .onAppear { isShowing = true }
      .onDisappear { isShowing = false }
      .onReceive(ExpressCommentUploads.shared.notices) { notice in
        guard isShowing, notice.claim() else { return }
        // Android's `Toast.LENGTH_SHORT`.
        toast(notice.message)
      }
  }
}

// MARK: - Placeholder card

/// A comment on its way up (Android's optimistic card): "You", its files, the status, and Cancel
/// while a video uploads, or Retry and Cancel once it failed. Cancel puts the text and files back
/// in the comment box. The list screens and the reel's text pages both show it.
struct ExpressOutgoingCommentCard: View {
  @ObservedObject var job: ExpressCommentUploads.Job

  private var isFailed: Bool { job.isFailed }

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      HStack(spacing: 6) {
        Text("You")
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(ExpressColors.outgoingAuthor))
        Text(job.statusText)
          .font(.system(size: 11))
          .foregroundColor(Color(isFailed ? ExpressColors.failedStroke : UIColor(expressLight: 0x9499A8, expressDark: 0x8E93A3)))
        Spacer(minLength: 0)
        actionPills
      }
      // The pills keep the row one height whichever show (the reel packs this card by height).
      .frame(minHeight: 28)
      if !job.content.isEmpty {
        Text(job.content)
          .font(.system(size: 13))
          .foregroundColor(Color(ExpressColors.commentBody))
          .lineLimit(4)
      }
      if job.hasFiles {
        ScrollView(.horizontal, showsIndicators: false) {
          HStack(spacing: 8) {
            ForEach(job.items) { item in
              ExpressDraftThumbnail(item: item, size: CGSize(width: 88, height: 56))
            }
          }
        }
        .frame(height: 56)
      }
      // Only files have an upload to follow; text alone just says "Sending…".
      if job.state == .uploading, job.hasFiles {
        GeometryReader { proxy in
          ZStack(alignment: .leading) {
            Capsule().fill(Color(ExpressColors.progressTrack))
            Capsule()
              .fill(Color(ExpressColors.accent))
              .frame(width: proxy.size.width * max(0.03, job.progress))
          }
        }
        .frame(height: 4)
        .padding(.top, 2)
      }
    }
    .padding(12)
    .background(RoundedRectangle(cornerRadius: 8).fill(Color(ExpressColors.commentCard)))
    .overlay(
      RoundedRectangle(cornerRadius: 8)
        .stroke(
          Color(isFailed ? ExpressColors.failedStroke : ExpressColors.outgoingStroke),
          lineWidth: isFailed ? 2 : 1
        )
    )
    .opacity(job.state == .uploading ? 0.55 : (isFailed ? 0.82 : 1))
    .accessibilityElement(children: .contain)
  }

  @ViewBuilder private var actionPills: some View {
    if isFailed {
      // Android keeps Retry on a failed card; Cancel is there too so a failed take can be taken
      // back into the box instead of waiting on the card for good.
      HStack(spacing: 6) {
        pill("Cancel", fill: ExpressColors.cancelFill, text: ExpressColors.cancelText, hint: "Puts it back in the comment box") {
          ExpressCommentUploads.shared.cancel(job)
        }
        pill("Retry", fill: ExpressColors.retryFill, text: ExpressColors.retryText, hint: "Sends it again") {
          ExpressCommentUploads.shared.retry(job)
        }
      }
    } else if job.state == .uploading, job.hasVideo {
      pill("Cancel", fill: ExpressColors.cancelFill, text: ExpressColors.cancelText, hint: "Stops the upload and puts it back in the comment box") {
        ExpressCommentUploads.shared.cancel(job)
      }
    }
  }

  private func pill(
    _ title: String,
    fill: UIColor,
    text: UIColor,
    hint: String,
    action: @escaping () -> Void
  ) -> some View {
    Button(action: action) {
      Text(title)
        .font(.system(size: 11, weight: .semibold))
        .foregroundColor(Color(text))
        .padding(.horizontal, 16)
        .frame(minWidth: 72, minHeight: 28)
        .background(Capsule().fill(Color(fill)))
        .contentShape(Capsule())
    }
    .buttonStyle(.plain)
    .accessibilityHint(hint)
  }
}

/// A draft file's small picture: its thumbnail, or a labelled tile for voice notes and documents.
struct ExpressDraftThumbnail: View {
  let item: ExpressDraftItem
  var size = CGSize(width: 44, height: 44)

  var body: some View {
    let shape = RoundedRectangle(cornerRadius: 8)
    ZStack {
      if let thumbnail = item.thumbnail {
        Image(uiImage: thumbnail).resizable().scaledToFill()
        if item.kind == .video {
          Image(systemName: "play.circle.fill")
            .font(.system(size: min(size.width, size.height) * 0.45))
            .foregroundStyle(.white.opacity(0.9))
        }
      } else {
        Color(UIColor(expressLight: 0xF2F5FC, expressDark: 0x3A3F52))
        VStack(spacing: 3) {
          Image(systemName: item.kind == .audio ? "waveform" : "doc.fill")
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.accent))
          if size.width > 60 {
            Text(item.kind == .audio ? "Voice note" : ExpressCommentFilesView.typeLabel(name: item.name, mime: item.mime))
              .font(.system(size: 9, weight: .semibold))
              .foregroundColor(Color(ExpressColors.tileMeta))
              .lineLimit(1)
          }
        }
      }
    }
    .frame(width: size.width, height: size.height)
    .clipShape(shape)
    .overlay(shape.stroke(Color(ExpressColors.tileStroke), lineWidth: 1))
    .accessibilityLabel(item.label)
  }
}

// MARK: - Banner

/// The status banner at the top of the comments (Android's `TakeBanner`): uploading with
/// progress and Cancel, posted with View, or failed with Retry. Swipe or × to dismiss. Text alone
/// posts without an upload to follow, so it only shows once posted or failed (as on Android).
struct ExpressUploadBanner: View {
  let listKey: String
  /// "View" on a posted banner: brings the posted comment into view.
  var onView: ((ExpressCommentUploads.Job) -> Void)? = nil
  @ObservedObject private var uploads = ExpressCommentUploads.shared

  var body: some View {
    let job = uploads.jobs(for: listKey).last {
      !$0.bannerHidden && $0.state != .cancelled && !($0.state == .uploading && !$0.hasFiles)
    }
    ZStack {
      if let job {
        ExpressUploadBannerContent(job: job, onView: onView)
          .transition(.move(edge: .top).combined(with: .opacity))
      }
    }
    .animation(.easeOut(duration: 0.3), value: job?.id)
  }
}

private struct ExpressUploadBannerContent: View {
  @ObservedObject var job: ExpressCommentUploads.Job
  var onView: ((ExpressCommentUploads.Job) -> Void)?

  var body: some View {
    VStack(spacing: 6) {
      HStack(spacing: 10) {
        Circle().fill(Color(dotColor)).frame(width: 8, height: 8)
        VStack(alignment: .leading, spacing: 2) {
          Text(title)
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.bannerTitle))
          Text(subtitle)
            .font(.system(size: 12))
            .foregroundColor(Color(ExpressColors.bannerBody))
            .lineLimit(2)
        }
        Spacer(minLength: 0)
        if case .failed = job.state {
          Button("Retry") { ExpressCommentUploads.shared.retry(job) }
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.bannerAction))
            .frame(minHeight: 44)
        } else if job.state == .uploading {
          // Stops the upload; the text and files go back into the comment box.
          Button("Cancel") { ExpressCommentUploads.shared.cancel(job) }
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.bannerAction))
            .frame(minHeight: 44)
            .accessibilityHint("Stops the upload and puts it back in the comment box")
        } else if job.state == .posted, let onView {
          Button("View") { onView(job) }
            .font(.system(size: 14, weight: .semibold))
            .foregroundColor(Color(ExpressColors.bannerAction))
            .frame(minHeight: 44)
        }
        Button {
          ExpressCommentUploads.shared.hideBanner(job)
        } label: {
          Image(systemName: "xmark")
            .font(.system(size: 12, weight: .semibold))
            .foregroundColor(Color(ExpressColors.bannerBody))
            .frame(width: 28, height: 44)
        }
        .accessibilityLabel("Dismiss")
      }
      if job.state == .uploading {
        GeometryReader { proxy in
          ZStack(alignment: .leading) {
            Capsule().fill(Color(ExpressColors.bannerTrack))
            Capsule()
              .fill(Color(ExpressColors.accent))
              .frame(width: proxy.size.width * max(0, job.progress))
          }
        }
        .frame(height: 3)
      }
    }
    .padding(.horizontal, 14)
    .padding(.vertical, 6)
    .background(
      RoundedRectangle(cornerRadius: 14)
        .fill(Color(ExpressColors.commentCard))
        .shadow(color: .black.opacity(0.18), radius: 8, y: 3)
    )
    .padding(.horizontal, 12)
    .padding(.top, 6)
    .gesture(DragGesture(minimumDistance: 12).onEnded { _ in ExpressCommentUploads.shared.hideBanner(job) })
    .accessibilityElement(children: .combine)
    .task(id: job.state) {
      // A failed banner steps aside after 5 s; the card keeps its Retry.
      if case .failed = job.state {
        try? await Task.sleep(nanoseconds: 5_000_000_000)
        if case .failed = job.state { ExpressCommentUploads.shared.hideBanner(job) }
      }
    }
  }

  private var dotColor: UIColor {
    switch job.state {
    case .posted: return ExpressColors.postedDot
    case .failed: return ExpressColors.failedDot
    default: return ExpressColors.accent
    }
  }

  private var title: String {
    switch job.state {
    case .uploading, .cancelled:
      // Android says "photo" for anything that isn't a reel; name what's actually going up.
      if job.isReel { return "Uploading your reel" }
      let kinds = Set(job.items.map(\.kind))
      if kinds == [.audio] { return "Uploading your voice note" }
      if kinds == [.video] { return "Uploading your video" }
      if kinds == [.document] { return "Uploading your file" }
      if kinds.isSubset(of: [.image, .gif]) { return "Uploading your photo" }
      return "Uploading your take"
    case .posted:
      return job.isReel ? "Reel posted" : "Comment posted"
    case .failed:
      return "Couldn’t post your take"
    }
  }

  private var subtitle: String {
    switch job.state {
    case .uploading, .cancelled:
      let percent = max(0, Int(job.progress * 100))
      return "\(percent)% · You can keep scrolling"
    case .posted:
      if job.parent.isReply { return "Your reply is now in the thread" }
      return job.topic.map { "Added to \($0)" } ?? "Added to the conversation"
    case .failed(let message):
      return message ?? "Tap to retry"
    }
  }
}

// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import Combine
import PhotosUI
import ExpressCore
import SwiftUI
import UIKit
import os.log

// The comments sheet, ported from the Android app's comments bottom sheet. Page and post
// comments open in the reel pager (`ExpressCommentsReel.swift`); YouTube comments and every
// replies level use the list here, with the comment being answered shown at the top.

// MARK: - Colours (Android `browser_express_colors.xml`, light / dark)

extension ExpressColors {
  static let commentsBackground = UIColor(expressLight: 0xE8E7EF, expressDark: 0x121318)
  static let commentCard = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let commentAuthor = UIColor(expressLight: 0x676767, expressDark: 0xA8ABB5)
  static let commentTime = UIColor(expressLight: 0x999999, expressDark: 0xA8ABB5)
  static let commentBody = UIColor(expressLight: 0x111111, expressDark: 0xF4F5F7)
  static let commentMore = UIColor(expressLight: 0x1A6EFF, expressDark: 0x6EA8FF)
  /// The card's ⋮ (`ic_browser_express_comment_more`, the same grey in both themes).
  static let commentOverflow = UIColor(expressLight: 0x808080, expressDark: 0x808080)
  static let reactionStroke = UIColor(expressLight: 0xCACACA, expressDark: 0x3A3D45)
  static let reactionCount = UIColor(expressLight: 0x80808C, expressDark: 0xA8ABB5)
  static let myReactionFill = UIColor(expressLight: 0xEBF0FF, expressDark: 0x31343B)
  static let myReactionCount = UIColor(expressLight: 0x000000, expressDark: 0xF4F5F7)
  static let chipFill = UIColor(expressLight: 0xE8E7EF, expressDark: 0x31343B)
  static let chipStroke = UIColor(expressLight: 0xC9C9C9, expressDark: 0x3A3D45)
  /// Behind a chip thumbnail while it loads (`browser_express_comments_chip_thumb`).
  static let chipThumb = UIColor(expressLight: 0xBFE3FA, expressDark: 0xBFE3FA)
  static let composerBar = UIColor(expressLight: 0xEAEDF7, expressDark: 0x31343B)
  static let composerField = UIColor(expressLight: 0xFFFFFF, expressDark: 0x232428)
  static let composerStroke = UIColor(expressLight: 0xDEDEDE, expressDark: 0x3A3D45)
  static let accent = UIColor(expressLight: 0x577DFF, expressDark: 0x577DFF)
  static let emptyTitle = UIColor(expressLight: 0x1A1C26, expressDark: 0xF4F5F7)
  static let emptyBody = UIColor(expressLight: 0x5C6175, expressDark: 0xA8ABB5)
}

// MARK: - Model

/// Loads and changes the comments for one screen of the sheet (the top level, or the replies
/// to one comment).
@MainActor
final class ExpressCommentsListModel: ObservableObject {
  enum Mode {
    case root(ExpressCommentsTarget)
    case replies(to: ExpressComment, root: ExpressCommentsTarget)
  }

  enum LoadState: Equatable {
    case loading
    case loaded
    case failed(String)
  }

  let mode: Mode
  /// On a replies screen: the comments above the one being answered, root first (for YouTube
  /// interactions, which copy the whole thread into Express).
  var ancestors: [ExpressComment] = []
  @Published private(set) var comments: [ExpressComment] = []
  @Published private(set) var state: LoadState = .loading
  @Published private(set) var hashtag: String?
  /// The server's top-level comment count (it can be above the 100 loaded), plus what was posted
  /// here since; nil when the server didn't say (YouTube, replies).
  @Published private(set) var serverCommentCount: Int?
  /// Every emoji on the post's or page's comments, from the server; nil when it didn't say.
  @Published private(set) var serverReactionCount: Int?
  @Published private(set) var isPosting = false
  @Published var errorMessage: String?
  /// The latest reaction the reader added (not removed), for the burst and "Karma earned".
  @Published private(set) var reactionAdded: ReactionAdded?
  /// Signed out in India: commenting needs a phone sign-in first (Android's `authRequired`).
  @Published var needsSignIn = false

  /// Makes sure there's an account to write with. Outside India that's a guest account; in India
  /// the reader is asked to sign in, and this returns false.
  private func ensureAccount() async -> Bool {
    if account.validAccessToken != nil { return true }
    if ExpressAccount.isIndia {
      needsSignIn = true
      return false
    }
    return await account.ensureGuestAccount()
  }

  /// `ensureAccount` for a comment or reply. Android posts a signed-out reader's comment without
  /// a token and the server makes the account, answering with its tokens and "Username …
  /// created"; here the guest account is made just before the send, so making it is announced
  /// the same way (tokens a send comes back with are announced by `storeTokens`).
  private func ensureAccountForSend() async -> Bool {
    let hadAccount = account.validAccessToken != nil
    guard await ensureAccount() else { return false }
    if !hadAccount, let username = account.claims?.username {
      ExpressCommentUploads.shared.announceAccountCreated(username: username)
    }
    return true
  }

  struct ReactionAdded: Equatable {
    let id = UUID()
    let commentID: String
    let emoji: String
  }

  /// Called with the top-level comment count, to keep the pill badge in step.
  var onCountChange: ((Int) -> Void)?
  /// Open at this comment (from a notification), once it has loaded.
  var focusCommentID: String?
  /// A post's author, age and media for the chip at the top, when the feed had the post.
  var postSource: ExpressCommentsPostSource?

  private let client = ExpressAPIClient.shared
  private let account = ExpressAccount.shared
  private var didReportPostOpened = false
  private var uploadsSubscription: AnyCancellable?

  init(mode: Mode) {
    self.mode = mode
    // Sent comments arrive from the upload manager, even after the box has moved on. It sends on
    // the main actor right as the job turns `.posted`; taking it then (not a turn later) swaps the
    // "You" card for the comment in one update, so the reel keeps the reader on it.
    uploadsSubscription = ExpressCommentUploads.shared.posted
      .sink { [weak self] key, comment in
        guard let self, key == self.parent?.listKey else { return }
        self.insertPosted(comment)
      }
  }

  /// Where this screen's comments are posted.
  var parent: ExpressCommentParent? {
    switch mode {
    case .root(.post(let id, _)):
      return .post(id: id)
    case .root(let target):
      return target.pageKeyURL.map { .page($0) }
    case .replies(let comment, let root):
      return .comment(id: ExpressYouTubeIDs.shared.expressID(for: comment), pageURL: root.pageKeyURL)
    }
  }

  /// The comment this replies screen answers, while it exists only on YouTube.
  private var youTubeOnlyParent: ExpressComment? {
    guard case .replies(let comment, _) = mode, ExpressYouTubeIDs.shared.isYouTubeOnly(comment)
    else { return nil }
    return comment
  }

  private var youTubeVideoID: String? {
    if case .youtube(let id) = rootTarget { return id }
    return nil
  }

  /// Android's `Commented` (sent as the comment goes out, like Android's sheet path). Android
  /// includes the text and the page address.
  private func captureCommented(_ content: String, attachments: [ExpressDraftItem]) {
    var props: [String: Any?] = [
      "content": content,
      "attachments": attachments.count,
      "attachment_kinds": attachments.map(\.kind.rawValue).joined(separator: ","),
      "from_take_sheet": false,
    ]
    if let parent {
      switch parent {
      case .post(let id):
        props["type"] = "post"
        props["post_id"] = id
      case .page(let url):
        props["type"] = "page"
        props["url"] = url.absoluteString
      case .comment(let id, _):
        props["type"] = "comment"
        props["comment_id"] = id
      }
    }
    ExpressAnalytics.shared.capture("Commented", props)
  }

  /// "Added to …" on the posted banner when there's no hashtag.
  private var topicName: String? {
    switch rootTarget {
    case .post(_, let title): return title
    case .page(let url): return url.host
    case .youtube: return "this video"
    }
  }

  private func insertPosted(_ comment: ExpressComment) {
    guard !comments.contains(where: { $0.id == comment.id }) else { return }
    comments.insert(comment, at: 0)
    state = .loaded
    countPosted()
  }

  /// One more top-level comment: the pill goes up by one from the server's total, as Android's
  /// `incrementPageCommentCount` does (the loaded list stops at 100, so its size can be lower).
  private func countPosted() {
    guard !isReplies else { return }
    if let total = serverCommentCount { serverCommentCount = total + 1 }
    onCountChange?(serverCommentCount ?? comments.count)
  }

  /// Sends the box (Android's `sendComment`): text and files alike go to the upload manager,
  /// which shows a "You" card at once and swaps in the comment when the server has it; a failed
  /// send keeps its card with Retry. A reply to a comment only on YouTube still posts here.
  func send(_ text: String, attachments: [ExpressDraftItem]) async -> Bool {
    let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
    if youTubeOnlyParent != nil {
      guard attachments.isEmpty else {
        // Android drops the files here; say so instead.
        errorMessage = "Replies to YouTube comments can only be text for now."
        return false
      }
      return await post(text)
    }
    guard !content.isEmpty || !attachments.isEmpty, !isPosting else { return false }
    // Held while the account is made, so a second tap doesn't send it twice.
    isPosting = true
    defer { isPosting = false }
    guard let parent, await ensureAccountForSend() else { return false }
    captureCommented(content, attachments: attachments)
    ExpressCommentUploads.shared.start(
      content: content,
      items: attachments,
      parent: parent,
      topic: hashtag.map { "#\($0)" } ?? topicName
    )
    return true
  }

  var rootTarget: ExpressCommentsTarget {
    switch mode {
    case .root(let target): return target
    case .replies(_, let root): return root
    }
  }

  var isReplies: Bool {
    if case .replies = mode { return true }
    return false
  }

  func load() async {
    if comments.isEmpty { state = .loading }
    if let videoID = youTubeVideoID {
      await loadYouTube(videoID: videoID)
      return
    }
    do {
      let response: ExpressCommentsResponse
      switch mode {
      case .root(let target):
        response = try await client.fetchComments(
          for: target,
          accessToken: account.validAccessToken
        )
        hashtag = response.hashtag
        serverCommentCount = response.commentCount
        serverReactionCount = response.reactionCount
        if let count = response.commentCount { onCountChange?(count) }
        if case .post(let id, _) = target, !didReportPostOpened,
          let token = account.validAccessToken
        {
          didReportPostOpened = true
          Task { await client.markPostOpened(id: id, accessToken: token) }
        }
      case .replies(let parent, _):
        response = try await client.fetchComments(
          for: nil,
          repliesTo: parent.id,
          accessToken: account.validAccessToken
        )
      }
      comments = response.comments
      #if DEBUG
      // `-ExpressSampleNewComments YES`: the first three comments show NEW, to check clearing.
      if !isReplies, UserDefaults.standard.bool(forKey: "ExpressSampleNewComments") {
        for i in comments.indices.prefix(3) { comments[i].isNew = true }
      }
      #endif
      state = .loaded
    } catch {
      Logger.module.error("Express comments load failed: \(error.localizedDescription, privacy: .public)")
      state = .failed(error.localizedDescription)
    }
  }

  /// YouTube pages: YouTube's own comments with Express's copies and Express-only comments
  /// (Android's merge). A failed side counts as empty, so this never shows an error.
  private func loadYouTube(videoID: String) async {
    let token = account.validAccessToken
    switch mode {
    case .root:
      comments = await ExpressYouTubeComments.topLevel(videoID: videoID, accessToken: token)
    case .replies(let parent, _):
      let ids = ExpressYouTubeIDs.shared
      comments = await ExpressYouTubeComments.replies(
        to: parent,
        expressID: ids.isYouTubeOnly(parent) ? nil : ids.expressID(for: parent),
        accessToken: token
      )
    }
    state = .loaded
  }

  /// Replying to a comment that exists only on YouTube: `yt_interact` copies the thread into
  /// Express and adds the reply. Unlike Android, the reply shows in the list straight away.
  private func replyOnYouTube(_ content: String, to parentComment: ExpressComment, videoID: String) async -> Bool {
    guard await ensureAccountForSend() else { return false }
    do {
      let result = try await ExpressYouTubeComments.interact(
        pageURL: ExpressYouTubeComments.watchURL(videoID: videoID),
        ancestors: ancestors + [parentComment],
        interaction: .reply(content),
        accessToken: account.validAccessToken
      )
      ExpressCommentUploads.shared.storeTokens(result.tokens)
      ExpressYouTubeIDs.shared.record(result.resolvedIDs)
      let profile = ExpressProfileModel.shared
      if let reply = ExpressComment.local(
        id: result.targetID ?? "local_\(UUID().uuidString.prefix(12))",
        content: content,
        userID: account.claims?.userID,
        username: profile.me?.username ?? account.claims?.username,
        avatar: profile.avatarURL?.absoluteString,
        attachments: []
      ) {
        comments.insert(reply, at: 0)
      }
      state = .loaded
      return true
    } catch {
      Logger.module.error("Express YouTube reply failed: \(error.localizedDescription, privacy: .public)")
      errorMessage = "Couldn’t post your reply. \(error.localizedDescription)"
      return false
    }
  }

  /// Posts a comment or reply straight away, without a "You" card: only replies to a comment that
  /// exists only on YouTube come here now (`send` hands everything else to the upload manager).
  /// Returns true on success; on failure the text is kept and an error shown.
  func post(_ text: String) async -> Bool {
    let content = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !content.isEmpty, !isPosting else { return false }
    isPosting = true
    defer { isPosting = false }
    captureCommented(content, attachments: [])
    if let parentComment = youTubeOnlyParent, let videoID = youTubeVideoID {
      return await replyOnYouTube(content, to: parentComment, videoID: videoID)
    }
    guard let parent else { return false }
    guard await ensureAccountForSend() else { return false }
    do {
      let result = try await client.postComment(
        content,
        to: parent,
        accessToken: account.validAccessToken
      )
      ExpressCommentUploads.shared.storeTokens(result.tokens)
      comments.insert(result.comment, at: 0)
      state = .loaded
      countPosted()
      // "Someone replied to you" makes sense now: ask about notifications, once.
      ExpressPushManager.shared.promptAfterFirstComment()
      return true
    } catch {
      Logger.module.error("Express comment post failed: \(error.localizedDescription, privacy: .public)")
      errorMessage = "Couldn’t post your comment. \(error.localizedDescription)"
      return false
    }
  }

  /// Fills in video sizes probed after loading (the API usually leaves them out).
  func applyVideoSizes(_ sizes: [String: CGSize]) {
    var updated = comments
    var changed = false
    for (id, size) in sizes {
      guard let index = updated.firstIndex(where: { $0.id == id }) else { continue }
      updated[index].mediaWidth = Int(size.width)
      updated[index].mediaHeight = Int(size.height)
      changed = true
    }
    if changed { comments = updated }
  }

  /// The reader has paged past these comments, so their NEW tags go (Android's
  /// `markNewTakesSeenBefore`). Only on this device; the server measures NEW per open.
  func markNewSeen(_ ids: Set<String>) {
    guard !ids.isEmpty, comments.contains(where: { $0.isNew && ids.contains($0.id) }) else { return }
    comments = comments.map { comment in
      guard comment.isNew, ids.contains(comment.id) else { return comment }
      var seen = comment
      seen.isNew = false
      return seen
    }
  }

  /// Sets or removes the user's reaction (tapping your own reaction removes it), updating the
  /// list first and replacing it with the server's numbers, or reverting on failure.
  func react(to comment: ExpressComment, with emoji: String) async {
    if account.validAccessToken == nil, ExpressAccount.isIndia {
      needsSignIn = true
      return
    }
    guard let index = comments.firstIndex(where: { $0.id == comment.id }) else { return }
    let previous = comments[index]
    let newReaction: String? =
      previous.myReaction.map { ExpressReaction.same($0, emoji) } == true ? nil : emoji
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
    comments[index].applyReaction(newReaction)
    ExpressAnalytics.shared.capture("Reaction Given", ["comment_id": comment.id, "emoji": newReaction])
    if let newReaction {
      reactionAdded = ReactionAdded(commentID: comment.id, emoji: newReaction)
    }
    // Debug builds launched with `-ExpressDryRun YES` keep the change on screen only.
    if ExpressDebug.isDryRun { return }
    _ = await ensureAccount()
    if ExpressYouTubeIDs.shared.isYouTubeOnly(comment), let videoID = youTubeVideoID {
      await reactOnYouTube(comment, emoji: newReaction, videoID: videoID, previous: previous)
      return
    }
    do {
      let result = try await client.setReaction(
        commentID: ExpressYouTubeIDs.shared.expressID(for: comment),
        emoji: newReaction,
        accessToken: account.validAccessToken
      )
      if let tokens = result.tokens {
        account.store(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
      }
      if let i = comments.firstIndex(where: { $0.id == comment.id }) {
        comments[i].reactions = result.reactions
        comments[i].reactionCount = result.reactionCount
        comments[i].myReaction = result.myReaction
      }
    } catch {
      if let i = comments.firstIndex(where: { $0.id == comment.id }) {
        comments[i] = previous
      }
      errorMessage = "Couldn’t save your reaction"
    }
  }

  /// A reaction on a comment that exists only on YouTube goes through `yt_interact`, with the
  /// thread above it (root first).
  private func reactOnYouTube(
    _ comment: ExpressComment,
    emoji: String?,
    videoID: String,
    previous: ExpressComment
  ) async {
    var chain: [ExpressComment] = []
    if case .replies(let parentComment, _) = mode { chain = ancestors + [parentComment] }
    do {
      let result = try await ExpressYouTubeComments.interact(
        pageURL: ExpressYouTubeComments.watchURL(videoID: videoID),
        ancestors: chain + [comment],
        interaction: .reaction(emoji),
        accessToken: account.validAccessToken
      )
      if let tokens = result.tokens {
        account.store(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
      }
      ExpressYouTubeIDs.shared.record(result.resolvedIDs)
      if let i = comments.firstIndex(where: { $0.id == comment.id }) {
        comments[i].reactions = result.reactions
        comments[i].reactionCount = result.reactionCount ?? result.reactions.reduce(0) { $0 + $1.count }
        comments[i].myReaction = result.myReaction
      }
    } catch {
      Logger.module.error("Express YouTube reaction failed: \(error.localizedDescription, privacy: .public)")
      if let i = comments.firstIndex(where: { $0.id == comment.id }) {
        comments[i] = previous
      }
      errorMessage = "Couldn’t save your reaction"
    }
  }
}

/// YouTube comments copied into Express (YouTube id → Express id), for this app session, so
/// later actions use the Express id (Android swaps the id on the comment itself).
@MainActor
final class ExpressYouTubeIDs {
  static let shared = ExpressYouTubeIDs()
  private var resolved: [String: String] = [:]

  func record(_ ids: [String: String]) {
    resolved.merge(ids) { _, new in new }
  }

  /// The id to send to Express for this comment.
  func expressID(for comment: ExpressComment) -> String {
    resolved[comment.id] ?? comment.id
  }

  func isYouTubeOnly(_ comment: ExpressComment) -> Bool {
    comment.isYouTubeOnly && resolved[comment.id] == nil
  }
}

// MARK: - Sheet

/// The comments sheet presented from the comments pill or a feed card.
struct ExpressCommentsSheetView: View {
  let target: ExpressCommentsTarget
  var pageTitle: String? = nil
  var onCountChange: (Int) -> Void = { _ in }
  var onClose: () -> Void = {}

  @StateObject private var rootModel: ExpressCommentsListModel
  @State private var path: [ExpressComment] = []

  init(
    target: ExpressCommentsTarget,
    pageTitle: String? = nil,
    postSource: ExpressCommentsPostSource? = nil,
    focusCommentID: String? = nil,
    onCountChange: @escaping (Int) -> Void = { _ in },
    onClose: @escaping () -> Void = {}
  ) {
    self.target = target
    self.pageTitle = pageTitle
    self.onCountChange = onCountChange
    self.onClose = onClose
    _rootModel = StateObject(
      wrappedValue: {
        let model = ExpressCommentsListModel(mode: .root(target))
        model.onCountChange = onCountChange
        model.focusCommentID = focusCommentID
        model.postSource = postSource
        return model
      }()
    )
  }

  var body: some View {
    NavigationStack(path: $path) {
      Group {
        if target.usesReelPager {
          ExpressCommentsReelScreen(
            model: rootModel,
            pageTitle: pageTitle,
            onClose: onClose,
            onOpenReplies: { path.append($0) }
          )
        } else {
          ExpressCommentsScreen(
            model: rootModel,
            pageTitle: pageTitle,
            onClose: onClose,
            onOpenReplies: { path.append($0) }
          )
        }
      }
      .onChange(of: path.count) { oldCount, newCount in
        // Android's `Clicked to View Replies` (top level) / `…Reply2Reply` (deeper).
        guard newCount > oldCount, let comment = path.last else { return }
        ExpressAnalytics.shared.capture(
          newCount == 1 ? "Clicked to View Replies" : "Clicked to View Reply2Reply",
          ["comment_id": comment.id]
        )
      }
      .navigationDestination(for: ExpressComment.self) { comment in
        ExpressRepliesScreen(
          parent: comment,
          root: target,
          ancestors: Array(path.prefix(while: { $0.id != comment.id })),
          pageTitle: pageTitle,
          onOpenReplies: { path.append($0) }
        )
      }
    }
  }
}

/// Holds the model for a replies screen.
private struct ExpressRepliesScreen: View {
  @StateObject private var model: ExpressCommentsListModel
  let pageTitle: String?
  let onOpenReplies: (ExpressComment) -> Void

  init(
    parent: ExpressComment,
    root: ExpressCommentsTarget,
    ancestors: [ExpressComment],
    pageTitle: String?,
    onOpenReplies: @escaping (ExpressComment) -> Void
  ) {
    _model = StateObject(
      wrappedValue: {
        let model = ExpressCommentsListModel(mode: .replies(to: parent, root: root))
        model.ancestors = ancestors
        return model
      }()
    )
    self.pageTitle = pageTitle
    self.onOpenReplies = onOpenReplies
  }

  var body: some View {
    ExpressCommentsScreen(model: model, pageTitle: pageTitle, onClose: nil, onOpenReplies: onOpenReplies)
  }
}

/// One level of the sheet: header chip, the comments, and the composer.
private struct ExpressCommentsScreen: View {
  @ObservedObject var model: ExpressCommentsListModel
  let pageTitle: String?
  let onClose: (() -> Void)?
  /// Pushes the replies to a comment (its Reply link and ⋮ → Reply).
  let onOpenReplies: (ExpressComment) -> Void

  @State private var draft = ""
  @State private var reactingTo: ExpressComment?
  @State private var expandedIDs: Set<String> = []
  @State private var videoViewer: ExpressVideoItem?
  @StateObject private var karma = ExpressKarmaModel()
  @ObservedObject private var uploads = ExpressCommentUploads.shared
  @StateObject private var autoplay = ExpressListVideoAutoplay()
  @StateObject private var take = ExpressTakeSheetModel(isEnabled: false)
  @StateObject private var depth = ExpressListDepthTracker()
  /// The list's height, for how many skeleton rows fill it.
  @State private var viewportHeight: CGFloat = 0
  @State private var didFocusFirstReply = false
  /// The comment whose replies this screen opened last; coming back lands on it.
  @State private var lastOpenedRepliesID: String?
  /// The comment wearing the brief landing highlight.
  @State private var flashID: String?
  @FocusState private var composerFocused: Bool

  /// Comments still on their way up, or failed and waiting on Retry or Cancel, newest first.
  private var outgoing: [ExpressCommentUploads.Job] {
    guard let key = model.parent?.listKey else { return [] }
    return uploads.jobs(for: key).filter(\.isOutgoing).reversed()
  }

  /// Opens a comment's replies, remembering it for the way back.
  private func openReplies(_ comment: ExpressComment) {
    lastOpenedRepliesID = comment.id
    onOpenReplies(comment)
  }

  /// Scrolls to a comment and lights it up for a second (Android's `scrollToCommentId`, which
  /// flashes the row yellow; here the accent at low opacity, which sits better on these cards).
  private func land(on id: String, reader: ScrollViewProxy) {
    DispatchQueue.main.async {
      withAnimation { reader.scrollTo(id, anchor: .top) }
      flashID = id
      Task { @MainActor in
        try? await Task.sleep(nanoseconds: 1_000_000_000)
        guard flashID == id else { return }
        withAnimation(.easeOut(duration: 0.35)) { flashID = nil }
      }
    }
  }

  private var title: String {
    if model.isReplies { return "Replies" }
    if let tag = model.hashtag { return "#\(tag)" }
    return "Comments"
  }

  var body: some View {
    ScrollViewReader { reader in
      ScrollView {
        LazyVStack(spacing: 4) {
          ExpressSourceChip(
            model: model,
            pageTitle: pageTitle,
            onOpenVideo: { videoViewer = ExpressVideoItem(url: $0) }
          )
          .padding(.horizontal, 16)
          .padding(.bottom, 8)
          content
        }
        .padding(.top, 8)
        .padding(.bottom, 16)
      }
      .safeAreaInset(edge: .top, spacing: 0) { depthRow }
      .expressCountsCommentScrolls()
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
        autoplay.setViewport(frame)
        if abs(frame.height - viewportHeight) > 1 { viewportHeight = frame.height }
      }
      // Where the composer starts: a card above it is in view.
      .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).maxY - $0.safeAreaInsets.bottom } action: {
        depth.setVisibleBottom($0)
      }
      .onAppear {
        autoplay.isEnabled = true
        // Android: only the top level opens "Add your take"; replies use the plain box.
        take.isEnabled = !model.isReplies
        focusComposerForFirstReply()
        // Back from a comment's replies: land on that comment (Android's
        // `getLastOpenedRepliesForCommentId` in `onResume`).
        if let id = lastOpenedRepliesID {
          lastOpenedRepliesID = nil
          if model.comments.contains(where: { $0.id == id }) { land(on: id, reader: reader) }
        }
      }
      .overlay { ExpressTakeBackdrop(take: take, overReel: false) }
      .onDisappear { autoplay.isEnabled = false }
      .onChange(of: videoViewer?.id) { _, viewer in autoplay.isEnabled = viewer == nil }
      .overlay(alignment: .top) {
        if let key = model.parent?.listKey {
          ExpressUploadBanner(listKey: key) { job in
            // "View": the posted comment, else the top of the list.
            if let id = job.posted?.id ?? model.comments.first?.id {
              withAnimation { reader.scrollTo(id, anchor: .top) }
            }
          }
        }
      }
      .onReceive(ExpressCommentUploads.shared.focusRequested) { key, job in
        // A send (or a retry): its "You" card, at the top, comes into view.
        guard key == model.parent?.listKey else { return }
        DispatchQueue.main.async { withAnimation { reader.scrollTo(job.id, anchor: .top) } }
      }
      .onChange(of: model.comments.count) {
        // From a notification: scroll to that comment once it's there, and light it up.
        guard let focus = model.focusCommentID, model.comments.contains(where: { $0.id == focus })
        else { return }
        model.focusCommentID = nil
        land(on: focus, reader: reader)
      }
    }
    .refreshable { await model.load() }
    .background(Color(ExpressColors.commentsBackground).ignoresSafeArea())
    .navigationTitle(title)
    .navigationBarTitleDisplayMode(.inline)
    // The title and the depth row read as one flat header, as on Android.
    .toolbarBackground(.hidden, for: .navigationBar)
    .toolbar {
      if let onClose {
        ToolbarItem(placement: .topBarTrailing) {
          Button(action: onClose) {
            Image(systemName: "xmark").font(.system(size: 15, weight: .semibold))
          }
          .accessibilityLabel("Close")
        }
      }
    }
    // "Username … created" after a send that made the reader an account.
    .modifier(ExpressCommentNoticeToasts())
    // Inside the composer's inset, so toasts sit above the box.
    .expressToastHost(error: $model.errorMessage)
    .safeAreaInset(edge: .bottom) {
      ExpressCommentComposer(
        model: model,
        draft: $draft,
        focused: $composerFocused,
        karma: karma,
        take: take,
        takeTitle: pageTitle
      )
    }
    .onChange(of: model.reactionAdded) { _, event in
      if event != nil { karma.play(onReel: nil) }
    }
    .task { if model.comments.isEmpty { await model.load() } }
    .sheet(item: $reactingTo) { comment in
      ExpressReactionPicker(comment: comment) { emoji in
        reactingTo = nil
        Task { await model.react(to: comment, with: emoji) }
      }
      .presentationDetents([.medium, .large])
    }
    .fullScreenCover(item: $videoViewer) { item in
      ExpressVideoViewer(url: item.url)
    }
    .expressSignInPrompt($model.needsSignIn)
  }

  /// "15 / 87" under the title (Android's `comments_depth_row`): the last comment in view and
  /// how many have loaded. It keeps its place, empty, until there are comments (Android's
  /// `INVISIBLE`).
  private var depthRow: some View {
    let total = model.comments.count
    return ExpressCommentsDepthRow(
      seen: depth.seen(in: model.comments),
      total: total,
      isReplies: model.isReplies
    )
    .opacity(total > 0 ? 1 : 0)
    .accessibilityHidden(total == 0)
    .padding(.top, 2)
    .padding(.horizontal, 16)
    // Under the (clear) navigation bar too, so cards scrolling up stay out from behind the title.
    .background(Color(ExpressColors.commentsBackground).ignoresSafeArea(edges: .top))
  }

  /// Reply on a comment with no replies yet opens straight to typing; with replies the keyboard
  /// stays down (Android's `CommentListAdapter` reply click → `showKeyboardWithFocus`). Once per
  /// screen, so coming back from deeper replies doesn't raise it again.
  private func focusComposerForFirstReply() {
    guard !didFocusFirstReply, case .replies(let parent, _) = model.mode, parent.commentCount == 0
    else { return }
    didFocusFirstReply = true
    Task { @MainActor in
      // A field can't take focus while the push is still sliding in.
      try? await Task.sleep(nanoseconds: 350_000_000)
      composerFocused = true
    }
  }

  @ViewBuilder private var content: some View {
    switch model.state {
    case .loading where model.comments.isEmpty:
      ExpressSkeletonList(rows: ExpressSkeletonList.rows(toFill: viewportHeight))
        .padding(.horizontal, 16)
    case .failed where model.comments.isEmpty:
      VStack(spacing: 8) {
        Text("Couldn’t load comments")
          .font(.system(size: 14))
          .foregroundColor(Color(ExpressColors.commentAuthor))
        Button("Tap to try again") { Task { await model.load() } }
          .font(.system(size: 14, weight: .bold))
          .foregroundColor(Color(ExpressColors.commentMore))
      }
      .frame(maxWidth: .infinity, minHeight: 200)
    default:
      if model.comments.isEmpty && outgoing.isEmpty {
        ExpressCommentsEmptyState(isReplies: model.isReplies, isPost: model.rootTarget.isPost) {
          if take.isEnabled {
            take.open(source: "take_button", picker: false, model: model, fallbackTitle: pageTitle)
          } else {
            composerFocused = true
          }
        }
          .padding(.horizontal, 28)
          .padding(.top, 12)
      } else {
        ForEach(outgoing) { job in
          ExpressOutgoingCommentCard(job: job)
            .padding(.horizontal, 16)
        }
        ForEach(model.comments) { comment in
          ExpressCommentCard(
            comment: comment,
            isExpanded: expandedIDs.contains(comment.id),
            onExpand: { expandedIDs.insert(comment.id) },
            onReact: { emoji in Task { await model.react(to: comment, with: emoji) } },
            onPickReaction: { reactingTo = comment },
            onOpenVideo: { videoViewer = ExpressVideoItem(url: $0) },
            autoplay: autoplay,
            burstEvent: model.reactionAdded,
            onReply: { openReplies(comment) },
            isJustPosted: uploads.justPostedIDs.contains(comment.id)
          )
          .overlay { ExpressCommentFlash(isOn: flashID == comment.id) }
          .padding(.horizontal, 16)
          .onGeometryChange(for: CGFloat.self) { $0.frame(in: .global).minY } action: {
            depth.setTop($0, for: comment.id)
          }
        }
      }
    }
  }

}

/// Which cards of a list have come into view, for the depth row: Android's
/// `findLastVisibleItemPosition`, where a card counts once its top is above the composer. Cards
/// report their tops as they move; only a card crossing that line publishes.
@MainActor
final class ExpressListDepthTracker: ObservableObject {
  @Published private(set) var reachedIDs: Set<String> = []
  /// Card tops and the line, in global coordinates. A card the lazy list let go keeps its last
  /// top, which was far above or below by then.
  private var tops: [String: CGFloat] = [:]
  private var visibleBottom: CGFloat = 0

  func setVisibleBottom(_ bottom: CGFloat) {
    guard bottom != visibleBottom else { return }
    visibleBottom = bottom
    let reached = Set(tops.filter { $0.value < bottom }.keys)
    if reached != reachedIDs { reachedIDs = reached }
  }

  func setTop(_ top: CGFloat, for id: String) {
    tops[id] = top
    guard visibleBottom > 0 else { return }
    let isReached = top < visibleBottom
    guard isReached != reachedIDs.contains(id) else { return }
    if isReached {
      reachedIDs.insert(id)
    } else {
      reachedIDs.remove(id)
    }
  }

  /// The last comment in view, counting from 1, kept within 1...count (Android's
  /// `CommentListFragment.showListDepth`); 0 with no comments.
  func seen(in comments: [ExpressComment]) -> Int {
    guard !comments.isEmpty else { return 0 }
    let last = comments.lastIndex { reachedIDs.contains($0.id) } ?? 0
    return max(1, min(comments.count, last + 1))
  }
}

/// The comment box at the bottom of the sheet (Android's `CommentComposerController`): the
/// reader's picture, the text field with a paperclip, and one round button that records a voice
/// note while the box is empty and sends otherwise. Attached files show above the row.
struct ExpressCommentComposer: View {
  @ObservedObject var model: ExpressCommentsListModel
  @Binding var draft: String
  var focused: FocusState<Bool>.Binding
  var karma: ExpressKarmaModel? = nil
  var onPosted: () -> Void = {}
  /// Files are attached or a voice note is recording (the reel keeps the box up meanwhile).
  var onBusyChange: (Bool) -> Void = { _ in }
  /// "Add your take" on the top level (Android's `TakeComposer`); disabled on replies.
  @ObservedObject var take: ExpressTakeSheetModel = ExpressTakeSheetModel(isEnabled: false)
  /// The topic card's title when the comments have no hashtag (the post's caption or page title).
  var takeTitle: String? = nil
  /// The reader's own picture (Android's `ProfileAvatar.selfUrl`), or the placeholder when
  /// signed out.
  @ObservedObject private var profile = ExpressProfileModel.shared
  @StateObject private var recorder = ExpressVoiceRecorder()

  @State private var items: [ExpressDraftItem] = []
  @State private var pendingPicks = 0
  @State private var toast: String?
  @State private var showingAttach = false
  @State private var pickingPhotos = false
  @State private var pickingVideos = false
  @State private var photoPicks: [PhotosPickerItem] = []
  @State private var videoPicks: [PhotosPickerItem] = []
  @State private var showingCamera = false
  @State private var showingDocuments = false
  @State private var showingGIFs = false
  @State private var pendingChoice: ExpressAttachChoice?
  @State private var pressStartedAt: Date?
  /// True while a finger is on the mic; resets by itself if the system cancels the touch
  /// (the microphone permission prompt does).
  @GestureState private var isPressingMic = false
  @State private var toastTask: Task<Void, Never>?
  @State private var confirmingTakeDiscard = false

  private var trimmed: String { draft.trimmingCharacters(in: .whitespacesAndNewlines) }
  private var canSend: Bool { !trimmed.isEmpty || !items.isEmpty }
  private var isBusy: Bool { !items.isEmpty || recorder.isRecording || pendingPicks > 0 }

  var body: some View {
    VStack(spacing: 8) {
      if take.isOpen {
        ExpressTakeHeader(
          take: take,
          // With the keyboard up and a file picked there's no room for the card (Android squeezes).
          showsTopic: !(focused.wrappedValue && !items.isEmpty),
          onClose: requestTakeClose
        )
      }
      if let toast {
        Text(toast)
          .font(.system(size: 11, weight: .medium))
          .foregroundColor(Color(ExpressColors.cancelText))
          .padding(.horizontal, 14)
          .frame(minHeight: 32)
          .background(
            RoundedRectangle(cornerRadius: 12)
              .fill(Color(ExpressColors.commentCard))
              .overlay(RoundedRectangle(cornerRadius: 12).stroke(Color(UIColor(expressLight: 0xD1D4E0, expressDark: 0x3A3D45)), lineWidth: 1))
          )
          .transition(.opacity)
      }
      if !recorder.isRecording {
        draftPreview
      }
      HStack(alignment: .bottom, spacing: 8) {
        leadingSlot
        if recorder.isRecording {
          recordingBar
        } else {
          field
        }
        actionButton
      }
      if take.isOpen && take.isPickerShown {
        ExpressTakePicker(
          onChoose: { choice in
            take.isPickerShown = false
            let kinds: [ExpressAttachChoice: String] = [
              .photo: "photo", .camera: "camera", .video: "video", .voice: "voice", .gif: "gif", .document: "document",
            ]
            ExpressAnalytics.shared.capture("Comment Attachment Chosen", ["kind": kinds[choice], "in_take_sheet": true])
            pendingChoice = choice
            openPendingChoice()
          },
          onClose: {
            take.isPickerShown = false
            focused.wrappedValue = true
          }
        )
        .transition(.move(edge: .bottom).combined(with: .opacity))
      }
    }
    .padding(.horizontal, take.isOpen ? 16 : 12)
    .padding(.top, take.isOpen ? 0 : 8)
    .padding(.bottom, take.isOpen ? 12 : 8)
    .background(boxBackground)
    .animation(.easeInOut(duration: 0.18), value: items.count)
    .animation(
      reduceMotion ? .linear(duration: 0.15) : .timingCurve(0.215, 0.61, 0.355, 1, duration: 0.5),
      value: take.isOpen
    )
    .animation(.timingCurve(0.215, 0.61, 0.355, 1, duration: 0.4).delay(0.15), value: take.isPickerShown)
    .onChange(of: take.isOpen) { _, open in
      // Opened from the box or "Add your take": straight to typing, unless the picker is up.
      if open, !take.isPickerShown {
        DispatchQueue.main.async { focused.wrappedValue = true }
      }
    }
    .onChange(of: take.isPickerShown) { _, shown in
      if shown { focused.wrappedValue = false }
    }
    .onChange(of: focused.wrappedValue) { _, isFocused in
      if isFocused, take.isPickerShown { take.isPickerShown = false }
    }
    .onChange(of: take.closeRequest) { requestTakeClose() }
    .alert("Discard your take?", isPresented: $confirmingTakeDiscard) {
      Button("Discard", role: .destructive) { discardTake() }
      Button("Keep editing", role: .cancel) {}
    }
    .animation(.easeInOut(duration: 0.18), value: recorder.isRecording)
    .onChange(of: isBusy) { _, busy in onBusyChange(busy) }
    .onAppear { recorder.onAutoStop = { handle($0) } }
    .onDisappear {
      // A recording doesn't survive the box going away.
      if recorder.isRecording { recorder.finish(keep: false) }
    }
    .onReceive(ExpressCommentUploads.shared.restored) { key, text, files in
      guard key == model.parent?.listKey else { return }
      draft = text
      items = files
      // Android's line for a video; the rest say what came back.
      let kept: String
      if files.contains(where: { $0.kind == .video }) {
        kept = "video"
      } else if files.isEmpty {
        kept = "comment"
      } else if files.count > 1 {
        kept = "files"
      } else {
        switch files[0].kind {
        case .image: kept = "photo"
        case .gif: kept = "GIF"
        case .audio: kept = "voice note"
        default: kept = "file"
        }
      }
      show("Upload cancelled · \(kept) kept in draft", for: 5)
    }
    .sheet(isPresented: $showingAttach, onDismiss: openPendingChoice) {
      ExpressAttachSheet(
        onChoose: { choice in
          let kinds: [ExpressAttachChoice: String] = [
            .photo: "photo", .camera: "camera", .video: "video", .voice: "voice", .gif: "gif", .document: "document",
          ]
          ExpressAnalytics.shared.capture(
            "Comment Attachment Chosen",
            ["kind": kinds[choice], "in_take_sheet": false]
          )
          pendingChoice = choice
          showingAttach = false
        },
        onClose: { showingAttach = false }
      )
      .presentationDetents([.height(330)])
      .presentationDragIndicator(.visible)
    }
    .photosPicker(
      isPresented: $pickingPhotos,
      selection: $photoPicks,
      maxSelectionCount: max(1, ExpressDraftLimits.maxFiles - items.count),
      matching: .images
    )
    .onChange(of: photoPicks) { _, picks in loadPicks(picks) { photoPicks = [] } }
    .background(
      Color.clear
        .photosPicker(
          isPresented: $pickingVideos,
          selection: $videoPicks,
          maxSelectionCount: max(1, ExpressDraftLimits.maxFiles - items.count),
          matching: .videos
        )
        .onChange(of: videoPicks) { _, picks in loadPicks(picks) { videoPicks = [] } }
    )
    .fullScreenCover(isPresented: $showingCamera) {
      ExpressCameraPicker { image in
        showingCamera = false
        guard let image else { return }
        if let item = try? ExpressDraftFactory.photo(image, name: nil) { add([item]) }
      }
      .ignoresSafeArea()
    }
    .sheet(isPresented: $showingDocuments) {
      ExpressDocumentPicker { urls in
        showingDocuments = false
        var picked: [ExpressDraftItem] = []
        for url in urls {
          do {
            picked.append(try ExpressDraftFactory.document(copying: url))
          } catch {
            show(error.localizedDescription)
          }
        }
        add(picked)
      }
      .ignoresSafeArea()
    }
    .sheet(isPresented: $showingGIFs) {
      ExpressGIFPicker(
        onPicked: { item in
          showingGIFs = false
          add([item])
        },
        onClose: { showingGIFs = false }
      )
      .presentationDetents([.fraction(0.75)])
      .presentationDragIndicator(.visible)
    }
  }

  // MARK: Pieces

  @ViewBuilder private var leadingSlot: some View {
    if let compactItem = compactThumbnailItem {
      // Several files (or one document): the first one's picture, tap to remove files.
      Menu {
        ForEach(items) { item in
          Button("Remove \(item.label)", role: .destructive) { remove(item) }
        }
        Button("Remove all", role: .destructive) { items = [] }
      } label: {
        ExpressDraftThumbnail(item: compactItem)
          .overlay(
            RoundedRectangle(cornerRadius: 8)
              .fill(Color.black.opacity(0.5))
              .overlay(Image(systemName: "pencil").font(.system(size: 18, weight: .semibold)).foregroundColor(.white))
          )
      }
      .accessibilityLabel(items.count == 1 ? "1 file attached" : "\(items.count) files attached")
    } else {
      Group {
        if let avatarURL = profile.avatarURL {
          ExpressAvatarView(url: avatarURL, size: 44)
        } else {
          Circle()
            .fill(Color(ExpressColors.avatarPlaceholder))
            .frame(width: 44, height: 44)
            .overlay(Image(systemName: "person.fill").foregroundColor(.white))
        }
      }
      .overlay {
        if let karma { ExpressKarmaAvatarEffect(karma: karma, size: 44) }
      }
      .accessibilityHidden(true)
    }
  }

  /// Compact mode: more than one file, or a lone document.
  private var compactThumbnailItem: ExpressDraftItem? {
    guard items.count > 1 || items.first?.kind == .document else { return nil }
    return items.first(where: \.isVisual) ?? items.first
  }

  private var field: some View {
    TextField(placeholder, text: $draft, axis: .vertical)
      .font(.system(size: 15))
      .foregroundColor(Color(ExpressColors.commentBody))
      .lineLimit(1...5)
      .textInputAutocapitalization(.sentences)
      .focused(focused)
      .padding(.leading, 14)
      .padding(.trailing, 36)
      .padding(.vertical, 11)
      .frame(minHeight: 44)
      .background(
        RoundedRectangle(cornerRadius: 22)
          .fill(Color(ExpressColors.composerField))
          .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color(ExpressColors.composerStroke), lineWidth: 1))
      )
      .overlay {
        // On the top level the box opens "Add your take" first.
        if take.interceptsBox {
          Color.clear
            .contentShape(Rectangle())
            .onTapGesture { openTake(source: "text_box", picker: false) }
            .accessibilityElement()
            .accessibilityLabel("Add your take")
            .accessibilityAddTraits(.isButton)
        }
      }
      .overlay(alignment: .bottomTrailing) {
        Button {
          if take.interceptsBox {
            openTake(source: "attach", picker: true)
          } else if take.isOpen {
            take.isPickerShown = true
          } else {
            focused.wrappedValue = false
            showingAttach = true
          }
        } label: {
          Image(systemName: "paperclip")
            .font(.system(size: 18, weight: .medium))
            .foregroundColor(Color(ExpressColors.commentBody))
            .frame(width: 36, height: 42)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Attach")
      }
  }

  private var recordingBar: some View {
    HStack(spacing: 10) {
      Circle().fill(Color(ExpressColors.recordingDot)).frame(width: 10, height: 10)
      Text(recorder.elapsedText)
        .font(.system(size: 13, weight: .medium).monospacedDigit())
        .foregroundColor(Color(ExpressColors.commentBody))
        .frame(minWidth: 40, alignment: .leading)
      ExpressWaveformView(levels: recorder.levels)
        .frame(height: 28)
      Button {
        handle(recorder.finish(keep: false))
      } label: {
        Image(systemName: "xmark")
          .font(.system(size: 14, weight: .semibold))
          .foregroundColor(Color(ExpressColors.commentAuthor))
          .frame(width: 32, height: 32)
      }
      .accessibilityLabel("Discard voice note")
      Button {
        handle(recorder.finish(keep: true))
      } label: {
        Image(systemName: "checkmark")
          .font(.system(size: 15, weight: .bold))
          .foregroundColor(Color(ExpressColors.accent))
          .frame(width: 32, height: 32)
      }
      .accessibilityLabel("Keep voice note")
    }
    .padding(.leading, 16)
    .padding(.trailing, 8)
    .frame(minHeight: 44)
    .background(
      RoundedRectangle(cornerRadius: 22)
        .fill(Color(ExpressColors.composerField))
        .overlay(RoundedRectangle(cornerRadius: 22).stroke(Color(ExpressColors.composerStroke), lineWidth: 1))
    )
  }

  @ViewBuilder private var actionButton: some View {
    let circle = ZStack {
      Circle()
        .fill(Color(ExpressColors.accent))
        .shadow(color: Color(ExpressColors.accent).opacity(0.3), radius: 3, y: 1)
      if model.isPosting {
        ProgressView().tint(.white)
      } else {
        Image(systemName: canSend ? "paperplane.fill" : "mic.fill").foregroundColor(.white)
      }
    }
    .frame(width: 44, height: 44)
    .overlay(alignment: .topTrailing) {
      if items.count > 1 || compactThumbnailItem != nil {
        Text("\(items.count)")
          .font(.system(size: 10, weight: .medium))
          .foregroundColor(.white)
          .padding(.horizontal, 4)
          .frame(minWidth: 18, minHeight: 18)
          .background(Capsule().fill(Color(UIColor(expressRGB: 0x14171F))))
          .offset(x: 2, y: -2)
      }
    }

    if take.interceptsBox && !canSend && !recorder.isRecording {
      Button { openTake(source: "mic", picker: false) } label: { circle }
        .accessibilityLabel("Add your take")
    } else if canSend || recorder.isRecording {
      Button(action: canSend ? send : { handle(recorder.finish(keep: true)) }) { circle }
        .disabled(model.isPosting)
        .accessibilityLabel(canSend ? "Send" : "Keep voice note")
    } else {
      // Press to record: let go after a hold to keep it, or tap to record hands-free.
      circle
        .contentShape(Circle())
        .gesture(
          DragGesture(minimumDistance: 0)
            .updating($isPressingMic) { _, pressing, _ in pressing = true }
        )
        .onChange(of: isPressingMic) { _, pressing in
          if pressing {
            pressStartedAt = Date()
            startRecording()
          } else {
            let held = pressStartedAt.map { Date().timeIntervalSince($0) } ?? 0
            pressStartedAt = nil
            if held >= 0.4, recorder.isRecording {
              handle(recorder.finish(keep: true))
            }
          }
        }
        .accessibilityElement()
        .accessibilityLabel("Record a voice note")
        .accessibilityAddTraits(.isButton)
        .accessibilityAction { startRecording() }
    }
  }

  @ViewBuilder private var draftPreview: some View {
    if items.count == 1, let item = items.first {
      if item.kind == .audio {
        ExpressVoiceNoteRow(url: item.fileURL, durationMs: item.durationMs, countsDown: true)
          .padding(.trailing, 24)
          .padding(.top, 4)
          .overlay(alignment: .topTrailing) { removeButton(item, label: "Remove voice note") }
      } else if item.isVisual {
        HStack(alignment: .top, spacing: 16) {
          ExpressDraftThumbnail(item: item, size: CGSize(width: 154, height: 154))
            .overlay(alignment: .topTrailing) {
              removeButton(item, label: item.kind == .video ? "Remove video" : (item.kind == .gif ? "Remove GIF" : "Remove photo"))
            }
          VStack(alignment: .leading, spacing: 10) {
            Text(item.name ?? item.label)
              .font(.system(size: 13, weight: .semibold))
              .foregroundColor(Color(ExpressColors.commentBody))
              .lineLimit(1)
              .truncationMode(.middle)
            Text(metaText(item))
              .font(.system(size: 11))
              .foregroundColor(Color(ExpressColors.draftMeta))
            Text(item.kind == .video ? "Uploads when you tap send" : "Ready to post")
              .font(.system(size: 11))
              .foregroundColor(Color(ExpressColors.draftMeta))
          }
          .padding(.top, 18)
          Spacer(minLength: 0)
        }
        .frame(height: 160, alignment: .top)
      }
    } else if pendingPicks > 0 && items.isEmpty {
      ProgressView().frame(height: 44)
    }
  }

  private func removeButton(_ item: ExpressDraftItem, label: String) -> some View {
    Button {
      remove(item)
    } label: {
      Circle()
        .fill(Color(UIColor(expressLight: 0x1E212B, expressDark: 0x1E212B)).opacity(0.94))
        .frame(width: 28, height: 28)
        .overlay(Image(systemName: "xmark").font(.system(size: 11, weight: .bold)).foregroundColor(.white))
        .frame(width: 44, height: 44)
        .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .accessibilityLabel(label)
  }

  private func metaText(_ item: ExpressDraftItem) -> String {
    let size = ExpressDraftFactory.sizeText(item.size)
    switch item.kind {
    case .video: return "\(ExpressDraftFactory.durationText(milliseconds: item.durationMs)) · \(size)"
    case .gif: return "GIF · \(size)"
    default: return "Photo · \(size)"
    }
  }

  private var placeholder: String {
    if !items.isEmpty { return "Add a caption…" }
    if take.isOpen { return "Add your take…" }
    if model.comments.isEmpty {
      return model.isReplies ? "Write the first reply…" : "Write the first comment…"
    }
    return model.isReplies ? "Add a reply" : "Add a comment"
  }

  // MARK: Actions

  private func send() {
    let text = draft
    let files = items
    Task {
      if await model.send(text, attachments: files) {
        // Drop focus first: a text view that resigns afterwards writes its text back into
        // the binding, which left the posted comment in the box, ready to send twice.
        focused.wrappedValue = false
        DispatchQueue.main.async { draft = "" }
        items = []
        take.close()
        onPosted()
      }
    }
  }

  // MARK: Add your take

  @Environment(\.accessibilityReduceMotion) private var reduceMotion

  /// The bar, or the take sheet's card when it's open.
  @ViewBuilder private var boxBackground: some View {
    if take.isOpen {
      UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24)
        .fill(Color(ExpressColors.takeSheet))
        .shadow(color: .black.opacity(0.18), radius: 12, y: -2)
        .ignoresSafeArea(edges: .bottom)
    } else {
      Color(ExpressColors.composerBar).ignoresSafeArea(edges: .bottom)
    }
  }

  private func openTake(source: String, picker: Bool) {
    take.open(source: source, picker: picker, model: model, fallbackTitle: takeTitle)
  }

  /// × or the backdrop: closes, asking first when something is typed, picked or recording.
  private func requestTakeClose() {
    guard take.isOpen else { return }
    if canSend || recorder.isRecording {
      confirmingTakeDiscard = true
    } else {
      focused.wrappedValue = false
      take.close()
    }
  }

  private func discardTake() {
    if recorder.isRecording { recorder.finish(keep: false) }
    ExpressAudioPlayback.shared.stop()
    items = []
    focused.wrappedValue = false
    DispatchQueue.main.async { draft = "" }
    var postID: String?
    if case .post(let id, _) = model.rootTarget { postID = id }
    ExpressAnalytics.shared.capture("Take Discarded", ["post_id": postID])
    take.close()
  }

  private func openPendingChoice() {
    guard let choice = pendingChoice else { return }
    pendingChoice = nil
    switch choice {
    case .photo: pickingPhotos = true
    case .video: pickingVideos = true
    case .camera: openCamera()
    case .voice: startRecording()
    case .gif: showingGIFs = true
    case .document: showingDocuments = true
    }
  }

  private func openCamera() {
    guard ExpressCameraPicker.isAvailable else {
      show("Couldn’t open the camera.")
      return
    }
    Task {
      if await ExpressCameraPicker.requestAccess() {
        showingCamera = true
      } else {
        show("Allow camera access to take a photo.")
      }
    }
  }

  private func startRecording() {
    guard !recorder.isRecording else { return }
    focused.wrappedValue = false
    Task {
      do {
        try await recorder.start()
      } catch ExpressVoiceRecorder.StartError.denied {
        pressStartedAt = nil
        show("Microphone access is needed to record a voice message")
      } catch {
        pressStartedAt = nil
        show("Microphone is unavailable right now")
      }
    }
  }

  private func handle(_ outcome: ExpressVoiceRecorder.Outcome) {
    switch outcome {
    case .kept(let url, let durationMs):
      add([ExpressDraftFactory.voiceNote(at: url, durationMs: durationMs)])
    case .tooShort:
      show("Hold the mic to record")
    case .cancelled:
      show("Recording cancelled")
    }
  }

  private func loadPicks(_ picks: [PhotosPickerItem], reset: @escaping () -> Void) {
    guard !picks.isEmpty else { return }
    reset()
    pendingPicks += picks.count
    Task {
      var loaded: [ExpressDraftItem] = []
      for pick in picks {
        do {
          loaded.append(try await ExpressDraftFactory.item(from: pick))
        } catch {
          show(error.localizedDescription)
        }
        pendingPicks -= 1
      }
      add(loaded)
    }
  }

  private func add(_ new: [ExpressDraftItem]) {
    guard !new.isEmpty else { return }
    let room = ExpressDraftLimits.maxFiles - items.count
    if new.count > room { show(ExpressDraftLimits.tooMany) }
    items.append(contentsOf: new.prefix(max(0, room)))
  }

  private func remove(_ item: ExpressDraftItem) {
    if ExpressAudioPlayback.shared.currentURL == item.fileURL { ExpressAudioPlayback.shared.stop() }
    items.removeAll { $0.id == item.id }
  }

  private func show(_ message: String, for seconds: Double = 2.5) {
    toastTask?.cancel()
    withAnimation { toast = message }
    toastTask = Task {
      try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
      guard !Task.isCancelled else { return }
      withAnimation { toast = nil }
    }
  }
}

// MARK: - Pieces

/// The chip under the comments title naming what the comments are on (Android's `SourceChip`,
/// Figma "Source Post Chips"): the feed post or the page on the comments page, the comment
/// being answered on a replies page. A photo or a video puts a 40 pt thumbnail beside the
/// author line (a video's with a play mark) that opens it full screen; a voice note puts a
/// player under the author line. The text line shows whenever there is text.
struct ExpressSourceChip: View {
  @ObservedObject var model: ExpressCommentsListModel
  let pageTitle: String?
  /// Plays a comment's video in the host's viewer, which also pauses the host's own videos.
  /// Without one the chip uses its own.
  var onOpenVideo: ((URL) -> Void)? = nil

  @State private var viewer: Viewer?

  /// What the chip shows (Android's `SourceChip.Source`); any of it may be missing.
  struct Source {
    var author = ""
    var avatarURL: URL?
    /// Off for a web page, which has its host where a person would be.
    var showsAvatar = true
    var createdDate: Date?
    var text: String?
    /// A photo, or a video's still.
    var imageURL: URL?
    /// Puts the play mark on the thumbnail.
    var isVideo = false
    /// The video the thumbnail opens; nil when it opens nothing (a YouTube page's).
    var videoURL: URL?
    var audioURL: URL?
    var audioDurationMs = 0
    /// A feed post, whose video opens in the post viewer.
    var post: ExpressPost?

    /// The comment a replies page answers (Android's `Source.forComment`).
    static func forComment(_ comment: ExpressComment) -> Source {
      var source = Source(
        author: comment.authorName,
        avatarURL: comment.avatarURL,
        createdDate: comment.createdDate,
        text: comment.content.isEmpty ? comment.documentName : comment.content
      )
      if let videoURL = comment.videoURL {
        source.isVideo = true
        source.videoURL = videoURL
      } else if let imageURL = comment.imageURL {
        source.imageURL = imageURL
      }
      source.audioURL = comment.audioURL
      source.audioDurationMs = comment.audioDurationMs
      return source
    }

    /// A feed post (Android's `Source.forPost`).
    static func forPost(_ post: ExpressCommentsPostSource) -> Source {
      Source(
        author: post.author ?? "",
        avatarURL: post.avatarURL,
        createdDate: post.createdDate,
        text: post.text,
        imageURL: post.imageURL,
        isVideo: post.videoURL != nil,
        videoURL: post.videoURL,
        audioURL: post.audioURL,
        audioDurationMs: post.audioDurationMs,
        post: post.post
      )
    }

    /// A web page: its host where the author goes, its title as the text, and the video's
    /// thumbnail when the page is one (Android's `Source.forPage`).
    static func forPage(host: String?, title: String?, thumbnail: URL?) -> Source {
      Source(
        author: host ?? "",
        showsAvatar: false,
        text: title,
        imageURL: thumbnail,
        isVideo: thumbnail != nil
      )
    }

    /// The text on one line.
    var shownText: String? {
      let flat = (text ?? "").replacingOccurrences(of: "\n", with: " ")
        .trimmingCharacters(in: .whitespaces)
      return flat.isEmpty ? nil : flat
    }

    var hasThumbnail: Bool { imageURL != nil || isVideo }

    /// The thumbnail opens something full screen.
    var opensMedia: Bool { isVideo ? videoURL != nil : imageURL != nil }

    var isEmpty: Bool {
      author.isEmpty && shownText == nil && !hasThumbnail && audioURL == nil
    }
  }

  /// What a tapped thumbnail shows full screen.
  private enum Viewer: Identifiable {
    case image(URL)
    case video(URL)
    case postVideo(ExpressPostVideoItem)

    var id: String {
      switch self {
      case .image(let url): return "image:\(url.absoluteString)"
      case .video(let url): return "video:\(url.absoluteString)"
      case .postVideo(let item): return "post:\(item.postID)"
      }
    }
  }

  private var source: Source {
    if case .replies(let parent, _) = model.mode { return .forComment(parent) }
    switch model.rootTarget {
    case .page(let url):
      return .forPage(
        host: url.host.map { $0.hasPrefix("www.") ? String($0.dropFirst(4)) : $0 },
        title: pageTitle,
        thumbnail: nil
      )
    case .youtube(let videoID):
      return .forPage(
        host: "youtube.com",
        title: pageTitle,
        thumbnail: URL(string: "https://i.ytimg.com/vi/\(videoID)/mqdefault.jpg")
      )
    case .post(_, let title):
      if let post = model.postSource { return .forPost(post) }
      // Not in the feed (a notification or a link): just "Post" and the caption.
      return Source(author: "Post", showsAvatar: false, text: title)
    }
  }

  var body: some View {
    let source = self.source
    Group {
      if !source.isEmpty {
        HStack(alignment: .center, spacing: 12) {
          if source.hasThumbnail {
            thumbnail(source)
          }
          VStack(alignment: .leading, spacing: 0) {
            authorLine(source)
            if let text = source.shownText {
              Text(text)
                .font(.system(size: 13))
                .foregroundColor(Color(ExpressColors.commentBody))
                .lineLimit(1)
                .frame(height: 20, alignment: .leading)
                .padding(.top, 4)
            }
            if let audioURL = source.audioURL {
              ExpressVoiceNoteRow(url: audioURL, durationMs: source.audioDurationMs)
                .padding(.top, 8)
            }
          }
          .frame(maxWidth: .infinity, alignment: .leading)
        }
        .padding(10)
        .background(
          RoundedRectangle(cornerRadius: 10)
            .fill(Color(ExpressColors.chipFill))
            .overlay(RoundedRectangle(cornerRadius: 10).stroke(Color(ExpressColors.chipStroke), lineWidth: 1))
        )
      }
    }
    .fullScreenCover(item: $viewer) { viewer in
      switch viewer {
      case .image(let url):
        ExpressImageViewer(url: url)
      case .video(let url):
        ExpressVideoViewer(url: url)
      case .postVideo(let item):
        ExpressPostVideoViewer(
          item: item,
          onClose: { self.viewer = nil },
          onShare: { ExpressShareSheet.present([$0]) }
        )
        .environment(\.colorScheme, .dark)
      }
    }
  }

  /// Figma "Express/Author": avatar and name on the left, the age on the right.
  private func authorLine(_ source: Source) -> some View {
    HStack(spacing: 6) {
      if source.showsAvatar {
        ExpressAvatarView(url: source.avatarURL, size: 16)
      }
      Text(source.author)
        .font(.system(size: 12))
        .foregroundColor(Color(ExpressColors.commentAuthor))
        .lineLimit(1)
        .frame(maxWidth: .infinity, alignment: .leading)
      if let date = source.createdDate {
        Text(ExpressRelativeTime.string(from: date))
          .font(.system(size: 11))
          .foregroundColor(Color(ExpressColors.commentTime))
          .lineLimit(1)
      }
    }
    .frame(height: 16)
  }

  /// The 40 pt photo or video still with 3 pt corners; a video's has a play mark.
  @ViewBuilder private func thumbnail(_ source: Source) -> some View {
    let still = ZStack {
      Color(ExpressColors.chipThumb)
      if let imageURL = source.imageURL {
        // A still even for a GIF: 40 pt is too small to watch it move.
        WebImageReader(url: imageURL) { image in
          if let image {
            Image(uiImage: image).resizable().scaledToFill()
          } else {
            Color.clear
          }
        }
      } else if let videoURL = source.videoURL {
        // A video with no still of its own shows its opening frame.
        ExpressVideoStill(url: videoURL)
      }
      if source.isVideo {
        Circle()
          .fill(Color.black.opacity(0.6))
          .frame(width: 20, height: 20)
          .overlay(
            Image(systemName: "play.fill")
              .font(.system(size: 8))
              .foregroundColor(.white)
              .offset(x: 1)
          )
      }
    }
    .frame(width: 40, height: 40)
    .clipShape(RoundedRectangle(cornerRadius: 3))

    if source.opensMedia {
      Button {
        open(source)
      } label: {
        still
      }
      .buttonStyle(.plain)
      .accessibilityLabel(source.isVideo ? "Play video" : "Open photo")
    } else {
      still.accessibilityHidden(true)
    }
  }

  /// Opens the media full screen the way a comment card's media does; a voice note that was
  /// playing pauses first (Android's `SourceChip.openViewer`).
  private func open(_ source: Source) {
    let audio = ExpressAudioPlayback.shared
    if audio.isPlaying, let playing = audio.currentURL { audio.toggle(playing) }
    if source.isVideo, let videoURL = source.videoURL {
      if let post = source.post {
        // A feed post's video opens in the post viewer, as it does from the feed.
        viewer = .postVideo(ExpressPostVideoItem(post: post, videoURL: videoURL, startSeconds: nil))
      } else if let onOpenVideo {
        onOpenVideo(videoURL)
      } else {
        viewer = .video(videoURL)
      }
    } else if let imageURL = source.imageURL {
      viewer = .image(imageURL)
    }
  }
}

/// A comment card: author row, text (cut with "More"), media, and the actions row (replies,
/// reaction button and chips). The list cuts text at 150 characters, the reel at 18 words.
struct ExpressCommentCard: View {
  enum TextCut {
    case characters
    case pagerWords
  }

  let comment: ExpressComment
  var textCut: TextCut = .characters
  var isExpanded: Bool
  var onExpand: () -> Void
  var onReact: (String) -> Void
  var onPickReaction: () -> Void
  var onOpenVideo: ((URL) -> Void)? = nil
  /// Muted autoplay (the YouTube and replies lists, and the reel's text pages); nil shows a
  /// poster to tap.
  var autoplay: ExpressListVideoAutoplay? = nil
  /// The latest added reaction; the card bursts when it's for this comment.
  var burstEvent: ExpressCommentsListModel.ReactionAdded? = nil
  /// Opens this comment's replies (the Reply link and ⋮ → Reply). Without it the link pushes the
  /// comment onto the enclosing navigation path itself.
  var onReply: (() -> Void)? = nil
  /// The reader's comment that just arrived on the server: "You" and an accent outline for a few
  /// seconds (Android's `setJustPosted`, Figma "08 · Posted").
  var isJustPosted = false

  @StateObject private var burst = ExpressEmojiBurstEngine()
  @State private var viewingImage: ExpressImageItem?
  @State private var burstOrigins: [String: CGRect] = [:]
  @State private var cardSize: CGSize = .zero
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.expressToast) private var toast

  private static let space = "expressCommentCard"

  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      authorRow
      if !comment.content.isEmpty {
        bodyText
      }
      media
      actionsRow
    }
    .padding(12)
    .frame(maxWidth: .infinity, alignment: .leading)
    .coordinateSpace(.named(Self.space))
    .onGeometryChange(for: CGSize.self) { $0.size } action: { cardSize = $0 }
    .background(RoundedRectangle(cornerRadius: 4).fill(Color(ExpressColors.commentCard)))
    .overlay { ExpressEmojiBurstView(engine: burst) }
    .clipShape(RoundedRectangle(cornerRadius: 4))
    // Android's posted outline: 2 pt of the accent, inside the card so it never changes size.
    .overlay {
      RoundedRectangle(cornerRadius: 4)
        .strokeBorder(Color(ExpressColors.accent), lineWidth: 2)
        .opacity(isJustPosted ? 1 : 0)
        .allowsHitTesting(false)
    }
    .animation(.easeOut(duration: 0.3), value: isJustPosted)
    .onChange(of: burstEvent) { _, event in playBurst(event) }
    .fullScreenCover(item: $viewingImage) { item in
      ExpressImageViewer(url: item.url)
    }
    // A long press offers the same as ⋮.
    .contextMenu { menuItems }
  }

  /// Android's ⋮ menu (`CommentListAdapter.showOverflowMenu`), in its order: Reply, Share, and
  /// "Copy text" only when there's text.
  @ViewBuilder private var menuItems: some View {
    if let onReply {
      Button(action: onReply) {
        Label("Reply", systemImage: "arrowshape.turn.up.left")
      }
    }
    // A comment only on YouTube has no Express link yet (Android shares a broken one).
    if ExpressCommentShare.link(for: comment) != nil {
      Button {
        ExpressCommentShare.share(comment)
      } label: {
        Label("Share", systemImage: "square.and.arrow.up")
      }
    }
    if ExpressCommentCopy.canCopy(comment) {
      Button {
        ExpressCommentCopy.copy(comment, toast: toast)
      } label: {
        Label("Copy text", systemImage: "doc.on.doc")
      }
    }
  }

  /// Bursts from the chip that now shows the reader's emoji (or the react button), once the
  /// chips have laid out again.
  private func playBurst(_ event: ExpressCommentsListModel.ReactionAdded?) {
    guard let event, event.commentID == comment.id else { return }
    Task { @MainActor in
      try? await Task.sleep(nanoseconds: 60_000_000)
      let frame = burstOrigins[ExpressEmojiFamily.normalize(event.emoji)] ?? burstOrigins["react"]
      burst.play(
        event.emoji,
        origin: frame.map { CGPoint(x: $0.midX, y: $0.midY) },
        in: cardSize,
        fullscreen: false,
        reduceMotion: reduceMotion
      )
    }
  }

  private var authorRow: some View {
    HStack(spacing: 6) {
      ExpressAvatarView(url: comment.avatarURL, size: 16)
      Text(isJustPosted ? "You" : comment.authorName)
        .font(.system(size: 12, weight: isJustPosted ? .medium : .regular))
        .foregroundColor(Color(isJustPosted ? ExpressColors.outgoingAuthor : ExpressColors.commentAuthor))
        .lineLimit(1)
      if comment.isNew {
        Text("NEW")
          .font(.system(size: 9.5, weight: .semibold))
          .foregroundColor(.white)
          .padding(.horizontal, 6)
          .frame(height: 16)
          .background(Capsule().fill(Color(ExpressColors.accent)))
      }
      if let date = comment.createdDate {
        Text(ExpressRelativeTime.string(from: date))
          .font(.system(size: 11))
          .foregroundColor(Color(ExpressColors.commentTime))
      }
    }
  }

  /// The text as shown, and whether it was cut.
  private var shownText: (text: String, isCut: Bool) {
    let full = comment.content
    guard !isExpanded else { return (full, false) }
    switch textCut {
    case .characters:
      return full.count > 150 ? (String(full.prefix(150)) + "...", true) : (full, false)
    case .pagerWords:
      return ExpressCommentPacker.clampedText(full).map { ($0, true) } ?? (full, false)
    }
  }

  @ViewBuilder private var bodyText: some View {
    let shown = shownText
    VStack(alignment: .leading, spacing: 2) {
      Text(shown.text)
        .font(.system(size: 13))
        .lineSpacing(4)
        .foregroundColor(Color(ExpressColors.commentBody))
        .fixedSize(horizontal: false, vertical: true)
      if shown.isCut {
        Button("More", action: onExpand)
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(ExpressColors.commentMore))
      }
    }
  }

  @ViewBuilder private var media: some View {
    if comment.attachments.count > 1 {
      ExpressCommentFilesView(
        comment: comment,
        onOpenImage: { viewingImage = ExpressImageItem(url: $0) },
        onOpenVideo: onOpenVideo
      )
    } else if let videoURL = comment.videoURL, let autoplay {
      ExpressInlineCommentVideo(
        comment: comment,
        videoURL: videoURL,
        autoplay: autoplay,
        onOpen: { onOpenVideo?($0) }
      )
    } else if let videoURL = comment.videoURL {
      // The poster (sent as the image) under a play button; a tap plays the video.
      Color(white: 0.13)
        .aspectRatio(comment.mediaCardAspectRatio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .overlay {
          if let poster = comment.imageURL {
            WebImageReader(url: poster) { image in
              if let image {
                Image(uiImage: image).resizable().scaledToFill()
              } else {
                Color.clear
              }
            }
          }
        }
        .overlay {
          Image(systemName: "play.circle.fill")
            .font(.system(size: 44))
            .foregroundStyle(.white.opacity(0.9))
            .shadow(radius: 4)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture { onOpenVideo?(videoURL) }
        .accessibilityElement()
        .accessibilityLabel("Video")
        .accessibilityAddTraits(.isButton)
    } else if let imageURL = comment.imageURL {
      // A fixed-shape frame with the image as an overlay, so tall images are cropped to the
      // card shape instead of growing the card (Android: 16:9, 4:3 or 1:1).
      Color(white: 0.13)
        .aspectRatio(comment.mediaCardAspectRatio, contentMode: .fit)
        .frame(maxWidth: .infinity)
        .overlay {
          // Animates GIFs; plain photos show as they are.
          ExpressAnimatedImage(url: imageURL)
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .contentShape(Rectangle())
        .onTapGesture { viewingImage = ExpressImageItem(url: imageURL) }
        .accessibilityElement()
        .accessibilityLabel("Photo")
        .accessibilityAddTraits(.isButton)
    } else {
      // A voice note or a document.
      ExpressCommentFilesView(
        comment: comment,
        onOpenImage: { viewingImage = ExpressImageItem(url: $0) },
        onOpenVideo: onOpenVideo
      )
    }
  }

  private var actionsRow: some View {
    let shown = orderedChips.prefix(3)
    let shownTotal = shown.reduce(0) { $0 + $1.count }
    let remaining = comment.reactionCount - shownTotal
    return HStack(spacing: 6) {
      replyLink
      Spacer(minLength: 0)
      Button(action: onPickReaction) {
        Image(systemName: "face.smiling")
          .font(.system(size: 18))
          .foregroundColor(Color(ExpressColors.commentTime))
          .frame(width: 24, height: 24)
      }
      .accessibilityLabel("React")
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
        burstOrigins["react"] = $0
      }
      ForEach(Array(shown), id: \.emoji) { reaction in
        reactionChip(reaction)
          .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
            burstOrigins[ExpressEmojiFamily.normalize(reaction.emoji)] = $0
          }
      }
      if remaining > 0 {
        Text("+\(ExpressNumberFormat.compact(remaining))")
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(ExpressColors.reactionCount))
      }
      // Android's `btn_comment_more`: a 24 dp ⋮ at the end of the row.
      Menu {
        menuItems
      } label: {
        Image(systemName: "ellipsis")
          .font(.system(size: 13, weight: .bold))
          .rotationEffect(.degrees(90))
          .foregroundColor(Color(ExpressColors.commentOverflow))
          .frame(width: 24, height: 24)
          .contentShape(Rectangle())
      }
      .accessibilityLabel("More options")
    }
    .frame(minHeight: 24)
  }

  /// "Reply", "1 Reply", "N Replies": opens the replies, as ⋮ → Reply does.
  @ViewBuilder private var replyLink: some View {
    let label = Text(replyLabel)
      .font(.system(size: 12, weight: .medium))
      .foregroundColor(Color(ExpressColors.commentTime))
    if let onReply {
      Button(action: onReply) { label }
        .buttonStyle(.plain)
    } else {
      NavigationLink(value: comment) { label }
        .buttonStyle(.plain)
    }
  }

  private var replyLabel: String {
    switch comment.commentCount {
    case 0: return "Reply"
    case 1: return "1 Reply"
    default: return "\(comment.commentCount) Replies"
    }
  }

  /// The user's own reaction first, then the rest by count.
  private var orderedChips: [ExpressReaction] {
    guard let mine = comment.myReaction else { return comment.reactions }
    let own = comment.reactions.filter { ExpressReaction.same($0.emoji, mine) }
    let others = comment.reactions.filter { !ExpressReaction.same($0.emoji, mine) }
    return own + others
  }

  private func reactionChip(_ reaction: ExpressReaction) -> some View {
    let isMine = comment.myReaction.map { ExpressReaction.same($0, reaction.emoji) } ?? false
    return Button {
      onReact(reaction.emoji)
    } label: {
      HStack(spacing: 3) {
        Text(reaction.emoji).font(.system(size: isMine ? 14 : 12))
        Text(ExpressNumberFormat.compact(reaction.count))
          .font(.system(size: 12, weight: .medium))
          .foregroundColor(Color(isMine ? ExpressColors.myReactionCount : ExpressColors.reactionCount))
      }
      .padding(.horizontal, isMine ? 7 : 4)
      .padding(.vertical, isMine ? 3 : 2)
      .background(
        RoundedRectangle(cornerRadius: isMine ? 6 : 4)
          .fill(isMine ? Color(ExpressColors.myReactionFill) : Color.clear)
          .overlay(
            RoundedRectangle(cornerRadius: isMine ? 6 : 4)
              .stroke(
                isMine ? Color(ExpressColors.accent).opacity(0.55) : Color(ExpressColors.reactionStroke),
                lineWidth: isMine ? 1 : 0.5
              )
          )
      )
    }
    .buttonStyle(.plain)
    .accessibilityLabel("\(reaction.emoji) \(reaction.count)\(isMine ? ", your reaction" : "")")
  }
}

/// A small round avatar with Android's placeholder colour.
struct ExpressAvatarView: View {
  let url: URL?
  let size: CGFloat

  var body: some View {
    Group {
      if let url {
        WebImageReader(url: url) { image in
          if let image {
            Image(uiImage: image).resizable().scaledToFill()
          } else {
            Color(ExpressColors.avatarPlaceholder)
          }
        }
      } else {
        Color(ExpressColors.avatarPlaceholder)
      }
    }
    .frame(width: size, height: size)
    .clipShape(Circle())
  }
}

extension ExpressCommentsTarget {
  /// A feed post's comments (Android's `isForPost`), as opposed to a page's or a video's.
  var isPost: Bool {
    if case .post = self { return true }
    return false
  }
}

extension ExpressColors {
  /// "Your voice starts the thread" (Android: #D16E738A / #D1A8ABB5).
  static let emptyCue = UIColor(expressLight: 0x6E738A, expressDark: 0xA8ABB5, alpha: 0xD1 / 255)
}

/// The brief highlight on a comment the list lands on (from a notification, or back from its
/// replies): the accent, faint, over the card.
struct ExpressCommentFlash: View {
  let isOn: Bool

  var body: some View {
    RoundedRectangle(cornerRadius: 4)
      .fill(Color(ExpressColors.accent).opacity(isOn ? 0.2 : 0))
      .allowsHitTesting(false)
      .accessibilityHidden(true)
  }
}

/// Android's `browser_express_comments_empty_participants`: three faint placeholder faces,
/// 17 pt across, 13 pt apart, each with a 1 pt ring.
private struct ExpressEmptyParticipants: View {
  @Environment(\.colorScheme) private var colorScheme

  private var fills: [Color] {
    if colorScheme == .dark {
      return Array(repeating: Color(UIColor(expressRGB: 0x31343B)), count: 3)
    }
    let light: [UInt32] = [0xB8BDD1, 0xC7BDDB, 0xADC7BF]
    return light.map { Color(UIColor(expressRGB: $0)) }
  }

  var body: some View {
    let ring = Color(UIColor(expressLight: 0xFBFBFE, expressDark: 0x3A3D45))
    ZStack(alignment: .leading) {
      ForEach(0..<3, id: \.self) { index in
        Circle()
          .fill(fills[index].opacity(0x94 / 255.0))
          .overlay(Circle().stroke(ring, lineWidth: 1))
          .frame(width: 17, height: 17)
          .offset(x: 0.5 + 13 * CGFloat(index))
      }
    }
    .frame(width: 44, height: 18, alignment: .leading)
    .accessibilityHidden(true)
  }
}

/// "Start the conversation" (or "No replies yet") from Android's empty state.
struct ExpressCommentsEmptyState: View {
  let isReplies: Bool
  /// A feed post's comments say "this post"; a page's or a video's "this page" (Android).
  var isPost = false
  var onWrite: () -> Void

  private var invitation: String {
    if isReplies { return "Be the first to reply to this comment." }
    return "Be the first to share a thought, question or reaction to this \(isPost ? "post" : "page")."
  }

  var body: some View {
    VStack(spacing: 12) {
      Circle()
        .fill(Color(ExpressColors.myReactionFill))
        .frame(width: 64, height: 64)
        .overlay(
          Image(systemName: "bubble.left.and.bubble.right.fill")
            .font(.system(size: 26))
            .foregroundColor(Color(ExpressColors.accent))
        )
      Text(isReplies ? "NO REPLIES YET" : "NO COMMENTS YET")
        .font(.system(size: 10, weight: .semibold))
        .tracking(0.8)
        .foregroundColor(Color(ExpressColors.emptyBody))
      Text("Start the conversation")
        .font(.system(size: 20, weight: .medium))
        .foregroundColor(Color(ExpressColors.emptyTitle))
      Text(invitation)
      .font(.system(size: 13))
      .lineSpacing(6)
      .multilineTextAlignment(.center)
      .foregroundColor(Color(ExpressColors.emptyBody))
      HStack(spacing: 8) {
        ExpressEmptyParticipants()
        Text("Your voice starts the thread")
          .font(.system(size: 11, weight: .medium))
          .foregroundColor(Color(ExpressColors.emptyCue))
      }
      Button(action: onWrite) {
        Text(isReplies ? "Write the first reply" : "Write the first comment")
          .font(.system(size: 15, weight: .semibold))
          .foregroundColor(.white)
          .frame(width: 230, height: 44)
          .background(
            Capsule().fill(
              LinearGradient(
                colors: [
                  Color(red: 0x40 / 255, green: 0x66 / 255, blue: 0xF2 / 255),
                  Color(red: 0x6E / 255, green: 0x54 / 255, blue: 0xF0 / 255),
                ],
                startPoint: .leading,
                endPoint: .trailing
              )
            )
          )
      }
      .padding(.top, 4)
      Text(isReplies ? "Your reply will appear here instantly." : "Your comment will appear here instantly.")
        .font(.system(size: 10))
        .foregroundColor(Color(ExpressColors.emptyBody))
    }
    .padding(24)
    .frame(maxWidth: .infinity, minHeight: 374)
    .background(
      RoundedRectangle(cornerRadius: 24)
        .fill(Color(ExpressColors.commentCard).opacity(0.96))
        .overlay(RoundedRectangle(cornerRadius: 24).stroke(Color(ExpressColors.chipStroke).opacity(0.8), lineWidth: 1))
    )
  }
}

// MARK: - Sharing

/// Shares a comment the way Android's `CommentListAdapter.shareComment` does: logs
/// `Comment/Reply Share Clicked` (one event for comments and replies, with `comment_id`) and
/// shares Android's message around the comment's link instead of the bare link.
@MainActor
enum ExpressCommentShare {
  /// The comment's page on the web; nil for a comment only on YouTube so far, which has no
  /// Express link yet.
  static func link(for comment: ExpressComment) -> URL? {
    let ids = ExpressYouTubeIDs.shared
    guard !ids.isYouTubeOnly(comment) else { return nil }
    return URL(string: "https://browser.express/view?id=\(ids.expressID(for: comment))")
  }

  /// Android's share text, word for word.
  static func message(for link: URL) -> String {
    "People say the craziest stuff! 👀 Check this out 👇\n\n\(link.absoluteString)\n\n"
      + "Dive in—it’s where everyone’s talking about everything, nonstop."
  }

  static func share(_ comment: ExpressComment) {
    guard let link = link(for: comment) else { return }
    ExpressAnalytics.shared.capture(
      "Comment/Reply Share Clicked",
      ["comment_id": ExpressYouTubeIDs.shared.expressID(for: comment)]
    )
    ExpressShareSheet.present([message(for: link)])
  }
}

/// "Copy text" from a comment's ⋮ menu (Android's `CommentListAdapter.showOverflowMenu`): only
/// offered when the comment has text, and confirmed with a short "Comment copied".
@MainActor
enum ExpressCommentCopy {
  static func canCopy(_ comment: ExpressComment) -> Bool {
    !comment.content.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  static func copy(_ comment: ExpressComment, toast: ExpressToastAction) {
    UIPasteboard.general.string = comment.content
    toast("Comment copied", showsCheck: true)
  }
}

/// Whether the share sheet is up. It's presented in UIKit, over the SwiftUI sheet, so the reel
/// wouldn't otherwise know it's covered; it pauses for it as for its own covers.
@MainActor
final class ExpressShareSheetState: ObservableObject {
  static let shared = ExpressShareSheetState()
  @Published fileprivate(set) var isPresented = false
}

/// The system share sheet over whatever is on screen (the comments sheet, a full-screen viewer).
@MainActor
enum ExpressShareSheet {
  static func present(_ items: [Any]) {
    let scenes = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }
    let scene = scenes.first { $0.activationState == .foregroundActive } ?? scenes.first
    guard var top = scene?.keyWindow?.rootViewController else { return }
    while let presented = top.presentedViewController, !presented.isBeingDismissed {
      top = presented
    }
    let share = UIActivityViewController(activityItems: items, applicationActivities: nil)
    // Called once the sheet has gone, whether something was shared or not.
    share.completionWithItemsHandler = { _, _, _, _ in
      Task { @MainActor in ExpressShareSheetState.shared.isPresented = false }
    }
    ExpressShareSheetState.shared.isPresented = true
    // iPad shows it as a popover; anchor it mid-screen.
    if let popover = share.popoverPresentationController {
      popover.sourceView = top.view
      popover.sourceRect = CGRect(x: top.view.bounds.midX, y: top.view.bounds.midY, width: 0, height: 0)
      popover.permittedArrowDirections = []
    }
    top.present(share, animated: true)
    // A presentation that never happened (something else mid-transition) mustn't leave the reel
    // paused.
    Task { @MainActor [weak share] in
      try? await Task.sleep(nanoseconds: 1_000_000_000)
      if share?.presentingViewController == nil { ExpressShareSheetState.shared.isPresented = false }
    }
  }
}

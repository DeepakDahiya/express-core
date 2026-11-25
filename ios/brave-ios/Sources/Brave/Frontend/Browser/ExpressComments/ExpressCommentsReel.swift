// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVKit
import BraveUI
import ExpressCore
import SwiftUI
import UIKit

// The comments "reel" for pages and posts, ported from the Android app's `CommentReelFragment`:
// a vertical pager where comments are packed into pages of cards and each portrait video gets a
// full-screen page that plays with sound. YouTube comments keep the list.

extension ExpressCommentsTarget {
  /// Page and post comments use the reel pager, as on Android.
  var usesReelPager: Bool {
    if case .youtube = self { return false }
    return true
  }
}

extension Color {
  /// A colour from Android's `#AARRGGBB` notation.
  init(expressARGB argb: UInt32) {
    self.init(
      .sRGB,
      red: Double((argb >> 16) & 0xFF) / 255,
      green: Double((argb >> 8) & 0xFF) / 255,
      blue: Double(argb & 0xFF) / 255,
      opacity: Double((argb >> 24) & 0xFF) / 255
    )
  }
}

extension ExpressColors {
  static let reelHeaderTitle = UIColor(expressLight: 0x333333, expressDark: 0xF4F5F7)
  static let reelHeaderIcon = UIColor(expressLight: 0x000000, expressDark: 0xF4F5F7)
  static let reelProgressTrack = UIColor(expressLight: 0xC7C7DB, expressDark: 0x31343B)
  static let reelProgressLabel = UIColor(expressLight: 0x63667A, expressDark: 0xA8ABB5)
}

/// A video to show full screen from an inline card.
struct ExpressVideoItem: Identifiable {
  let url: URL
  var id: String { url.absoluteString }
}

// MARK: - Pager model

@MainActor
final class ExpressReelPager: ObservableObject {
  struct Layout: Equatable {
    var size: CGSize = .zero
    var headerHeight: CGFloat = 0
    var composerBarHeight: CGFloat = 0
    var bottomInset: CGFloat = 0

    /// The composer as it rests over the page, including the home indicator area under it.
    var composerHeight: CGFloat { composerBarHeight + bottomInset }
    var rowsHeight: CGFloat { max(0, size.height - headerHeight - composerHeight - 8) }
    var peekHeight: CGFloat { (size.height * ExpressCommentPacker.peekFraction).rounded() }
    var isUsable: Bool { size.width > 0 && size.height > 0 && headerHeight > 0 }
  }

  private struct PackKey: Equatable {
    var layout: Layout
    var comments: [String]
  }

  @Published private(set) var slides: [ExpressCommentSlide] = []
  @Published var currentSlideID: String?
  @Published var expandedIDs: Set<String> = []
  /// The reader's position on the page they left, for the stream counter's animation.
  @Published private(set) var previousSeen = 0

  private var packedKey: PackKey?
  private let measurer = ExpressCommentRowMeasurer()

  var currentIndex: Int? {
    slides.firstIndex { $0.id == currentSlideID } ?? (slides.isEmpty ? nil : 0)
  }

  var currentSlide: ExpressCommentSlide? {
    currentIndex.map { slides[$0] }
  }

  /// The id a sending comment's "You" card has on the pages.
  nonisolated static func rowID(for job: ExpressCommentUploads.Job) -> String {
    "outgoing:" + job.id.uuidString
  }

  nonisolated static func isOutgoingRow(_ id: String) -> Bool { id.hasPrefix("outgoing:") }

  /// Comments on pages 0 through `index` (sending cards aside), clamped to 1...total.
  func seen(through index: Int?, total: Int) -> Int {
    guard let index, total > 0, !slides.isEmpty else { return 0 }
    let count = slides.prefix(index + 1).reduce(0) {
      $0 + $1.commentIDs.filter { !Self.isOutgoingRow($0) }.count
    }
    return min(max(count, 1), total)
  }

  func pageChanged(from oldID: String?, total: Int) {
    let oldIndex = slides.firstIndex { $0.id == oldID }
    previousSeen = seen(through: oldIndex, total: total)
  }

  /// The comments on every page before the current one, which the reader has moved past.
  var commentIDsBeforeCurrent: Set<String> {
    guard let index = currentIndex, index > 0 else { return [] }
    return Set(slides.prefix(index).flatMap(\.commentIDs))
  }

  /// The page holding this comment opens first (from a notification), on the first pack.
  var focusCommentID: String?

  /// Re-packs when the comments, the sending cards, their video sizes or the page geometry
  /// changed. Sending cards come first, as Android puts a sent comment at the top of the list.
  /// The reader stays on the page holding the first card they were looking at (or, when that was
  /// a sending card that is now posted, `renamed` says which comment it became; when it's gone,
  /// the page in the same place), or goes to the page holding `anchor` (a new send, a retry, the
  /// banner's View).
  func pack(
    _ comments: [ExpressComment],
    outgoing: [ExpressCommentUploads.Job] = [],
    layout: Layout,
    anchor requested: String? = nil,
    renamed: [String: String] = [:]
  ) {
    guard layout.isUsable else { return }
    if comments.isEmpty && outgoing.isEmpty {
      // The last sending card went (cancelled): back to the empty state.
      packedKey = nil
      if !slides.isEmpty {
        slides = []
        currentSlideID = nil
      }
      return
    }
    let key = PackKey(
      layout: layout,
      comments: outgoing.map { "\(Self.rowID(for: $0)):\($0.isFailed ? "failed" : "sending")" }
        + comments.map { "\($0.id):\($0.mediaWidth)x\($0.mediaHeight)" }
    )
    if key == packedKey {
      // Nothing moved; only bring the asked-for card into view.
      if let requested, let slide = slides.first(where: { $0.commentIDs.contains(requested) }),
        slide.id != currentSlideID
      {
        var transaction = Transaction()
        transaction.disablesAnimations = true
        withTransaction(transaction) { currentSlideID = slide.id }
      }
      return
    }
    packedKey = key

    let byID = Dictionary(comments.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
    let jobs = Dictionary(outgoing.map { (Self.rowID(for: $0), $0) }, uniquingKeysWith: { first, _ in first })
    let width = layout.size.width - 32
    let previousIndex = currentIndex
    let anchor = currentSlide?.commentIDs.first.map { renamed[$0] ?? $0 }
    let items =
      outgoing.map { ExpressCommentPacker.Item(id: Self.rowID(for: $0), isPortraitVideo: false) }
      + comments.map { ExpressCommentPacker.Item(id: $0.id, isPortraitVideo: $0.isPortraitVideo) }
    let newSlides = ExpressCommentPacker.pack(
      items,
      rowsHeight: layout.rowsHeight,
      peekHeight: layout.peekHeight
    ) { [measurer, expandedIDs] id in
      if let job = jobs[id] {
        return measurer.height(ofOutgoing: job, width: width) + 4
      }
      guard let comment = byID[id] else { return 0 }
      return measurer.height(of: comment, width: width, expanded: expandedIDs.contains(id)) + 4
    }

    let target: String?
    if let requested, let slide = newSlides.first(where: { $0.commentIDs.contains(requested) }) {
      target = slide.id
    } else if let focus = focusCommentID {
      // The asked-for comment, else (it can sit past the loaded ones) the oldest NEW one, else
      // the top: Android's `openingAnchor`. The request is used up.
      focusCommentID = nil
      let opening =
        byID[focus] != nil
        ? focus
        : comments.filter(\.isNew).min {
          ($0.createdDate ?? .distantFuture) < ($1.createdDate ?? .distantFuture)
        }?.id
      target =
        opening.flatMap { id in newSlides.first { $0.commentIDs.contains(id) }?.id }
        ?? newSlides.first?.id
    } else if let anchor, let slide = newSlides.first(where: { $0.commentIDs.contains(anchor) }) {
      target = slide.id
    } else if let previousIndex, !newSlides.isEmpty {
      // The card the reader was on went (a cancelled send): the page in the same place.
      target = newSlides[min(previousIndex, newSlides.count - 1)].id
    } else {
      target = newSlides.first?.id
    }
    var transaction = Transaction()
    transaction.disablesAnimations = true
    withTransaction(transaction) {
      slides = newSlides
      currentSlideID = target
    }
  }
}

/// Measures text-page cards offscreen at the width they're drawn, like Android's
/// `CommentRowMeasurer`, so packed pages match what's shown.
@MainActor
final class ExpressCommentRowMeasurer {
  private var cache: [String: CGFloat] = [:]
  private lazy var host: UIHostingController<AnyView> = {
    let host = UIHostingController(rootView: AnyView(EmptyView()))
    host.sizingOptions = []
    return host
  }()

  func height(of comment: ExpressComment, width: CGFloat, expanded: Bool) -> CGFloat {
    let key =
      "\(comment.id)|\(Int(width))|\(expanded)|\(comment.mediaWidth)x\(comment.mediaHeight)|\(comment.content.hashValue)"
    if let height = cache[key] { return height }
    host.rootView = AnyView(
      ExpressCommentCard(
        comment: comment,
        textCut: .pagerWords,
        isExpanded: expanded,
        onExpand: {},
        onReact: { _ in },
        onPickReaction: {}
      )
      .frame(width: width)
    )
    let size = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    let height = ceil(size.height)
    cache[key] = height
    return height
  }

  /// A sending comment's "You" card. Sending and failed differ (the progress line, the pills), so
  /// each is measured once.
  func height(ofOutgoing job: ExpressCommentUploads.Job, width: CGFloat) -> CGFloat {
    let key = "outgoing|\(job.id)|\(Int(width))|\(job.isFailed)"
    if let height = cache[key] { return height }
    host.rootView = AnyView(ExpressOutgoingCommentCard(job: job).frame(width: width))
    let size = host.sizeThatFits(in: CGSize(width: width, height: .greatestFiniteMagnitude))
    let height = ceil(size.height)
    cache[key] = height
    return height
  }
}

// MARK: - Screen

/// The reel for page and post comments.
struct ExpressCommentsReelScreen: View {
  @ObservedObject var model: ExpressCommentsListModel
  let pageTitle: String?
  let onClose: () -> Void
  var onOpenReplies: (ExpressComment) -> Void

  @StateObject private var pager = ExpressReelPager()
  @StateObject private var playback = ExpressReelPlayback()
  /// The current text page's first video card, muted (landscape and square videos never get a
  /// page of their own).
  @StateObject private var inlineVideos = ExpressListVideoAutoplay()
  @State private var layout = ExpressReelPager.Layout()
  @State private var readyToPack = false
  @State private var draft = ""
  @State private var composerRequested = false
  @State private var viewedNewTakes: Set<String> = []
  @StateObject private var take = ExpressTakeSheetModel(isEnabled: true)
  /// The box holds files or is recording: it stays up over reels.
  @State private var composerBusy = false
  @State private var reactingTo: ExpressComment?
  @State private var videoViewer: ExpressVideoItem?
  @State private var isVisible = true
  @StateObject private var karma = ExpressKarmaModel()
  @ObservedObject private var uploads = ExpressCommentUploads.shared
  /// The share sheet covers the reel like its own covers do.
  @ObservedObject private var shareSheet = ExpressShareSheetState.shared
  /// Videos whose size has been looked up, so newly arrived comments are probed once.
  @State private var probedVideoIDs: Set<String> = []
  @FocusState private var composerFocused: Bool

  private var title: String {
    model.hashtag.map { "#\($0)" } ?? "Comments"
  }

  /// The reader's sends still on their way up, or failed and waiting on Retry or Cancel, newest
  /// first: each a "You" card at the front of the pages.
  private var outgoing: [ExpressCommentUploads.Job] {
    guard let key = model.parent?.listKey else { return [] }
    return uploads.jobs(for: key).filter(\.isOutgoing).reversed()
  }

  /// Sends that are posted now, by card id: the comment each became.
  private var renamedSends: [String: String] {
    guard let key = model.parent?.listKey else { return [:] }
    var renamed: [String: String] = [:]
    for job in uploads.jobs(for: key) {
      if let posted = job.posted { renamed[ExpressReelPager.rowID(for: job)] = posted.id }
    }
    return renamed
  }

  private var onReel: Bool {
    pager.currentSlide?.isReel ?? false
  }

  private var composerHidden: Bool {
    onReel && !composerFocused && !composerRequested && !composerBusy && !take.isOpen
      && draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
  }

  private var commentsByID: [String: ExpressComment] {
    Dictionary(model.comments.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
  }

  var body: some View {
    ZStack(alignment: .top) {
      // Pages fill the sheet whatever its insets: a top inset briefly appears after the replies
      // screen pops, and resizing the pager would leave it between pages.
      pagerLayer
        .ignoresSafeArea(.all, edges: [.top, .bottom])
        // Behind the take sheet a reel is blurred (Android: 16 dp).
        .blur(radius: take.isOpen && onReel ? 16 : 0)
      header
        .ignoresSafeArea(.container, edges: .top)
        .opacity(onReel ? 0 : 1)
        .allowsHitTesting(!onReel)
        .animation(.easeInOut(duration: 0.15), value: onReel)
        .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { layout.headerHeight = $0 }
      ExpressTakeBackdrop(take: take, overReel: onReel)
      VStack(spacing: 0) {
        Spacer(minLength: 0)
        composer
      }
      if let key = model.parent?.listKey {
        // At the top of the sheet, over the header, as Android places it: below the header it
        // would sit on the first card, which is where the reader's "You" card is.
        ExpressUploadBanner(listKey: key) { job in
          // "View": the page with the posted comment (Android's `repackAround(postedId)`).
          repack(anchor: job.posted?.id)
        }
      }
    }
    // "Username … created" after a send that made the reader an account.
    .modifier(ExpressCommentNoticeToasts())
    // Above the resting comment bar (on a video page, over the caption).
    .expressToastHost(error: $model.errorMessage, bottomPadding: layout.composerBarHeight + 12)
    .background(Color(ExpressColors.commentsBackground).ignoresSafeArea())
    .toolbar(.hidden, for: .navigationBar)
    .task { await start() }
    .onChange(of: packSignature) { repack() }
    .onChange(of: layout) { repack() }
    .onChange(of: readyToPack) { repack() }
    .onChange(of: pager.currentSlideID) { oldValue, _ in
      pager.pageChanged(from: oldValue, total: model.comments.count)
      reportNewTakesViewed()
      model.markNewSeen(pager.commentIDsBeforeCurrent)
      updatePlayback()
    }
    .onChange(of: pager.slides) { updatePlayback() }
    .onChange(of: isBlocked) {
      playback.isBlocked = isBlocked
      inlineVideos.isHeld = isBlocked
    }
    .onReceive(ExpressCommentUploads.shared.focusRequested) { key, job in
      // A send (or a retry): its "You" card goes in at the front and the reel goes to it
      // (Android's `addNewComment` / `repackAround`).
      guard key == model.parent?.listKey else { return }
      repack(anchor: ExpressReelPager.rowID(for: job))
    }
    .onChange(of: model.comments.count) {
      // A comment that arrived after loading (the reader's own, posted) gets its video size too,
      // if it came without one, so a portrait take still gets its own page.
      guard readyToPack else { return }
      Task { await probeVideoSizes() }
    }
    .onChange(of: composerFocused) { if !composerFocused { composerRequested = false } }
    .onChange(of: model.reactionAdded) { _, event in
      // On a video page the reader's picture slides out from the pill; elsewhere the comment
      // bar's avatar shimmers.
      guard let event else { return }
      let onReel = pager.slides.contains { $0.reelCommentID == event.commentID }
      karma.play(onReel: onReel ? event.commentID : nil)
    }
    .onAppear {
      isVisible = true
      playback.isBlocked = isBlocked
      inlineVideos.isHeld = isBlocked
    }
    .onDisappear {
      // The replies screen was pushed, or the sheet closed.
      isVisible = false
      playback.isBlocked = true
      inlineVideos.isHeld = true
    }
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

  private var isBlocked: Bool {
    !isVisible || reactingTo != nil || videoViewer != nil || take.isOpen || shareSheet.isPresented
  }

  private var packSignature: [String] {
    outgoing.map { "\(ExpressReelPager.rowID(for: $0)):\($0.isFailed)" }
      + model.comments.map { "\($0.id):\($0.mediaWidth)x\($0.mediaHeight)" }
  }

  /// Sending cards by their id on the pages.
  private var outgoingByRowID: [String: ExpressCommentUploads.Job] {
    Dictionary(outgoing.map { (ExpressReelPager.rowID(for: $0), $0) }, uniquingKeysWith: { first, _ in first })
  }

  // MARK: Pager

  @ViewBuilder private var pagerLayer: some View {
    GeometryReader { proxy in
      let size = proxy.size
      Group {
        if pager.slides.isEmpty {
          placeholder
            .padding(.top, layout.headerHeight)
            .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        } else {
          ScrollViewReader { reader in
            ScrollView(.vertical) {
              LazyVStack(spacing: 0) {
                ForEach(Array(pager.slides.enumerated()), id: \.element.id) { index, slide in
                  page(slide, index: index)
                    .frame(width: size.width, height: size.height)
                    .clipped()
                }
              }
              .scrollTargetLayout()
            }
            .scrollTargetBehavior(.paging)
            .scrollPosition(id: $pager.currentSlideID)
            .modifier(
              ExpressReelPageSync(
                pageHeight: size.height,
                slideIDs: pager.slides.map(\.id),
                currentID: $pager.currentSlideID
              )
            )
            .scrollIndicators(.hidden)
            .expressCountsCommentScrolls()
            .onChange(of: size) {
              // Paging doesn't re-snap when the pages change height; put the page back.
              if let id = pager.currentSlideID {
                var transaction = Transaction()
                transaction.disablesAnimations = true
                withTransaction(transaction) { reader.scrollTo(id, anchor: .top) }
              }
            }
          }
        }
      }
      .onAppear { updateSize(size) }
      .onChange(of: size) { updateSize(size) }
    }
  }

  @ViewBuilder private func page(_ slide: ExpressCommentSlide, index: Int) -> some View {
    let byID = commentsByID
    let jobs = outgoingByRowID
    switch slide {
    case .text(let ids, let peekID):
      ExpressReelTextPage(
        rows: ids.compactMap { id -> ExpressReelRow? in
          if let job = jobs[id] { return .outgoing(job) }
          return byID[id].map { .comment($0) }
        },
        peekReel: peekID.flatMap { byID[$0] },
        layout: layout,
        expandedIDs: $pager.expandedIDs,
        justPostedIDs: uploads.justPostedIDs,
        onReact: { comment, emoji in Task { await model.react(to: comment, with: emoji) } },
        onPickReaction: { reactingTo = $0 },
        onOpenVideo: { videoViewer = ExpressVideoItem(url: $0) },
        autoplay: inlineVideos,
        onReply: onOpenReplies,
        burstEvent: model.reactionAdded
      )
    case .reel(let id):
      if let comment = byID[id] {
        ExpressReelVideoPage(
          comment: comment,
          title: title,
          isCurrent: slide.id == pager.currentSlideID,
          showsTakeLabel: reelOrdinal(of: index) % 3 == 0,
          seen: pager.seen(through: index, total: model.comments.count),
          previousSeen: pager.previousSeen,
          total: model.comments.count,
          bottomInset: layout.bottomInset,
          pageHeight: layout.size.height,
          burstEvent: model.reactionAdded,
          karma: karma,
          playback: playback,
          onClose: close,
          onReact: { emoji in Task { await model.react(to: comment, with: emoji) } },
          onPickReaction: { reactingTo = comment },
          onReply: { onOpenReplies(comment) },
          onAddTake: requestComposer
        )
      } else {
        Color(expressARGB: 0xFF20_2124)
      }
    }
  }

  /// The position of the page at `index` among the video pages.
  private func reelOrdinal(of index: Int) -> Int {
    pager.slides.prefix(index).filter(\.isReel).count
  }

  /// Skeleton rows under the header while there's nothing to page through yet (Android's
  /// `CommentReelFragment` shimmer, which also covers the wait for video sizes).
  private var skeleton: some View {
    ExpressSkeletonList(rows: ExpressSkeletonList.rows(toFill: layout.size.height - layout.headerHeight))
      .padding(.horizontal, 16)
      .padding(.top, 8)
  }

  @ViewBuilder private var placeholder: some View {
    switch model.state {
    case .loading:
      skeleton
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
      if model.comments.isEmpty {
        ExpressCommentsEmptyState(isReplies: false, isPost: model.rootTarget.isPost, onWrite: requestComposer)
          .padding(.horizontal, 28)
          .padding(.top, 8)
      } else {
        // Comments are in, waiting for the first pack.
        skeleton
      }
    }
  }

  // MARK: Header

  private var header: some View {
    let total = model.comments.count
    let seen = pager.seen(through: pager.currentIndex, total: total)
    return VStack(alignment: .leading, spacing: 0) {
      HStack(spacing: 8) {
        Button(action: close) {
          Image(systemName: "arrow.left")
            .font(.system(size: 20, weight: .medium))
            .foregroundColor(Color(ExpressColors.reelHeaderIcon))
            .frame(width: 24, height: 24)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Close comments")
        Text(title)
          .font(.system(size: 16))
          .foregroundColor(Color(ExpressColors.reelHeaderTitle))
          .lineLimit(1)
        Spacer(minLength: 0)
      }
      .padding(.top, 20)
      if total > 0 {
        ExpressCommentsDepthRow(seen: seen, total: total)
          .padding(.top, 2)
      }
      ExpressSourceChip(
        model: model,
        pageTitle: pageTitle,
        onOpenVideo: { videoViewer = ExpressVideoItem(url: $0) }
      )
      .padding(.top, 10)
      .padding(.bottom, 8)
    }
    .padding(.horizontal, 16)
    .background(Color(ExpressColors.commentsBackground))
  }

  // MARK: Composer

  private var composer: some View {
    ExpressCommentComposer(
      model: model,
      draft: $draft,
      focused: $composerFocused,
      karma: karma,
      // A send's "You" card is brought into view by `focusRequested`, posted or not.
      onBusyChange: { composerBusy = $0 },
      take: take,
      takeTitle: takeTitle
    )
    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { height in
      // Ignore growth while typing or while the take sheet is open (its header and topic card
      // aren't part of the bar); pages pack against the resting composer.
      if !composerFocused, draft.isEmpty, !composerBusy, !take.isOpen {
        layout.composerBarHeight = height
      }
    }
    .offset(y: composerHidden ? layout.composerHeight : 0)
    .opacity(composerHidden ? 0 : 1)
    .allowsHitTesting(!composerHidden)
    .animation(.easeInOut(duration: 0.18), value: composerHidden)
  }

  /// Android's `New Take Viewed`: once per new take, when its page settles.
  private func reportNewTakesViewed() {
    guard let ids = pager.currentSlide?.commentIDs else { return }
    var postID: String?
    if case .post(let id, _) = model.rootTarget { postID = id }
    for id in ids where !viewedNewTakes.contains(id) {
      guard let index = model.comments.firstIndex(where: { $0.id == id }), model.comments[index].isNew
      else { continue }
      viewedNewTakes.insert(id)
      ExpressAnalytics.shared.capture(
        "New Take Viewed",
        ["take_id": id, "position": index + 1, "post_id": postID]
      )
    }
  }

  /// The topic card's title without a hashtag: the post's caption, else the page title.
  private var takeTitle: String? {
    if case .post(_, let title) = model.rootTarget, let title { return title }
    return pageTitle
  }

  private func requestComposer() {
    // Android: "Add your take" (and the empty state's button) open the take sheet.
    take.open(source: "take_button", picker: false, model: model, fallbackTitle: takeTitle)
  }

  private func close() {
    composerFocused = false
    playback.releaseAll()
    inlineVideos.choose(nil, url: nil)
    onClose()
  }

  // MARK: Loading, packing, playback

  private func start() async {
    if model.comments.isEmpty {
      await model.load()
    }
    // Like Android, the first pack waits up to 2 s for video sizes; later ones re-pack.
    await withTaskGroup(of: Bool.self) { group in
      group.addTask { await probeVideoSizes(); return true }
      group.addTask {
        try? await Task.sleep(nanoseconds: 2_000_000_000)
        return false
      }
      let probeFinishedFirst = await group.next() ?? false
      readyToPack = true
      repack()
      if probeFinishedFirst {
        group.cancelAll()
      }
    }
  }

  private func probeVideoSizes() async {
    let unknown = model.comments.compactMap { comment -> (String, URL)? in
      guard let url = comment.videoURL, comment.mediaWidth == 0 || comment.mediaHeight == 0,
        !probedVideoIDs.contains(comment.id)
      else { return nil }
      return (comment.id, url)
    }
    guard !unknown.isEmpty else { return }
    probedVideoIDs.formUnion(unknown.map(\.0))
    var cached: [String: CGSize] = [:]
    var toProbe: [(String, URL)] = []
    for (id, url) in unknown {
      if let size = await ExpressVideoDimensions.shared.cachedSize(for: url) {
        cached[id] = size
      } else {
        toProbe.append((id, url))
      }
    }
    if !cached.isEmpty { model.applyVideoSizes(cached) }
    guard !toProbe.isEmpty else { return }
    await withTaskGroup(of: (String, CGSize?).self) { group in
      var next = 0
      while next < min(6, toProbe.count) {
        let (id, url) = toProbe[next]
        next += 1
        group.addTask { (id, await ExpressVideoDimensions.shared.size(for: url)) }
      }
      while let (id, size) = await group.next() {
        if let size { model.applyVideoSizes([id: size]) }
        if next < toProbe.count {
          let (id, url) = toProbe[next]
          next += 1
          group.addTask { (id, await ExpressVideoDimensions.shared.size(for: url)) }
        }
      }
    }
  }

  private func updateSize(_ size: CGSize) {
    layout.size = size
    if layout.bottomInset == 0 {
      layout.bottomInset =
        UIApplication.shared.connectedScenes
        .compactMap { ($0 as? UIWindowScene)?.keyWindow }
        .first?.safeAreaInsets.bottom ?? 0
    }
  }

  /// Packs the comments and the reader's sending cards; `anchor` brings that card's page into view.
  private func repack(anchor: String? = nil) {
    guard readyToPack else { return }
    if pager.focusCommentID == nil, let focus = model.focusCommentID {
      model.focusCommentID = nil
      pager.focusCommentID = focus
    }
    pager.pack(
      model.comments,
      outgoing: outgoing,
      layout: layout,
      anchor: anchor,
      renamed: renamedSends
    )
  }

  private func updatePlayback() {
    guard let index = pager.currentIndex else {
      playback.activate(nil, url: nil, neighbours: [])
      inlineVideos.choose(nil, url: nil)
      return
    }
    let byID = commentsByID
    // Android's `attemptAutoplay`: on a text page the first video card plays, muted and looping
    // (the page's sound button turns it up); leaving the page stops it. A comment with several
    // files shows tiles instead, which don't play.
    if case .text(let ids, _) = pager.slides[index],
      let first = ids.lazy.compactMap({ byID[$0] }).first(where: { $0.videoURL != nil && $0.attachments.count <= 1 })
    {
      inlineVideos.choose(first.id, url: first.videoURL)
    } else {
      inlineVideos.choose(nil, url: nil)
    }
    func video(at i: Int) -> (id: String, url: URL)? {
      guard pager.slides.indices.contains(i), let id = pager.slides[i].reelCommentID,
        let url = byID[id]?.videoURL
      else { return nil }
      return (id, url)
    }
    let neighbours = [index - 1, index + 1].compactMap(video(at:))
    let current = video(at: index)
    playback.activate(current?.id, url: current?.url, neighbours: neighbours)
  }
}

// MARK: - Depth row

/// "15 / 87" under the comments title: a 3 pt line and the count (Android's
/// `browser_express_comments_header` depth row, `CommentListFragment.showListDepth`). The reel's
/// header and the YouTube and replies lists both wear it.
struct ExpressCommentsDepthRow: View {
  let seen: Int
  let total: Int
  var isReplies = false

  var body: some View {
    HStack(spacing: 0) {
      GeometryReader { proxy in
        ZStack(alignment: .leading) {
          Capsule().fill(Color(ExpressColors.reelProgressTrack))
          Capsule()
            .fill(Color(ExpressColors.accent))
            .frame(width: proxy.size.width * CGFloat(seen) / CGFloat(max(total, 1)))
        }
        .frame(height: 3)
        .frame(maxHeight: .infinity)
      }
      Text("\(seen) / \(total)")
        .font(.system(size: 10, weight: .medium).monospacedDigit())
        .foregroundColor(Color(ExpressColors.reelProgressLabel))
        .frame(width: 56, alignment: .trailing)
    }
    .frame(height: 12)
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("\(isReplies ? "Reply" : "Comment") \(seen) of \(total)")
  }
}

// MARK: - Text page

/// `scrollPosition(id:)` can keep the previous page's id after a quick run of flicks, which left
/// the header and composer hidden on a text page (the pager still thought it was on a video).
/// Every page is the pager's full height, so once scrolling stops the page at the top is read
/// from the offset and the id corrected. iOS 18+; earlier systems keep the binding alone.
private struct ExpressReelPageSync: ViewModifier {
  let pageHeight: CGFloat
  let slideIDs: [String]
  @Binding var currentID: String?

  func body(content: Content) -> some View {
    if #available(iOS 18.0, *) {
      content.onScrollPhaseChange { _, newPhase, context in
        guard newPhase == .idle, pageHeight > 0, !slideIDs.isEmpty else { return }
        let geometry = context.geometry
        let offset = geometry.contentOffset.y + geometry.contentInsets.top
        let index = min(max(Int((offset / pageHeight).rounded()), 0), slideIDs.count - 1)
        if currentID != slideIDs[index] {
          currentID = slideIDs[index]
        }
      }
    } else {
      content
    }
  }
}

/// One card on a text page: a comment, or the reader's own on its way up.
private enum ExpressReelRow: Identifiable {
  case comment(ExpressComment)
  case outgoing(ExpressCommentUploads.Job)

  var id: String {
    switch self {
    case .comment(let comment): return comment.id
    case .outgoing(let job): return ExpressReelPager.rowID(for: job)
    }
  }
}

/// A page of comment cards, top-aligned under the header. A card's "More" expands it in place
/// and lets the page scroll. Before a video page, the bottom strip shows the top of that video.
/// The reader's sends show here as "You" cards (sending, or failed with Retry and Cancel), and a
/// comment that has just arrived wears "You" and the accent outline for a few seconds.
private struct ExpressReelTextPage: View {
  let rows: [ExpressReelRow]
  let peekReel: ExpressComment?
  let layout: ExpressReelPager.Layout
  @Binding var expandedIDs: Set<String>
  var justPostedIDs: Set<String> = []
  var onReact: (ExpressComment, String) -> Void
  var onPickReaction: (ExpressComment) -> Void
  var onOpenVideo: (URL) -> Void
  /// Plays the current page's first video card muted; a tap still opens the viewer.
  var autoplay: ExpressListVideoAutoplay? = nil
  /// A card's Reply link and ⋮ → Reply.
  var onReply: (ExpressComment) -> Void
  var burstEvent: ExpressCommentsListModel.ReactionAdded?

  var body: some View {
    let hasExpanded = rows.contains { row in
      if case .comment(let comment) = row { return expandedIDs.contains(comment.id) }
      return false
    }
    ZStack(alignment: .bottom) {
      Color(ExpressColors.commentsBackground)
      ScrollView {
        VStack(spacing: 4) {
          ForEach(rows) { row in
            switch row {
            case .comment(let comment):
              ExpressCommentCard(
                comment: comment,
                textCut: .pagerWords,
                isExpanded: expandedIDs.contains(comment.id),
                onExpand: { expandedIDs.insert(comment.id) },
                onReact: { onReact(comment, $0) },
                onPickReaction: { onPickReaction(comment) },
                onOpenVideo: onOpenVideo,
                autoplay: autoplay,
                burstEvent: burstEvent,
                onReply: { onReply(comment) },
                isJustPosted: justPostedIDs.contains(comment.id)
              )
              .padding(.horizontal, 16)
            case .outgoing(let job):
              ExpressOutgoingCommentCard(job: job)
                .padding(.horizontal, 16)
            }
          }
        }
        .padding(.top, layout.headerHeight + 8)
        .padding(.bottom, hasExpanded && peekReel != nil ? layout.peekHeight + 8 : 8)
        .frame(maxWidth: .infinity, alignment: .top)
        // Without bounce, a drag that starts at the text's end moves the pager instead
        // (Android's `SlideScrollHost` hand-off).
        .background(ExpressEnclosingScrollView { $0.bounces = false })
      }
      .scrollDisabled(!hasExpanded)
      .scrollBounceBehavior(.basedOnSize)
      .scrollIndicators(.hidden)
      .padding(.bottom, layout.composerHeight)
      if let peekReel {
        ExpressReelPeekStrip(comment: peekReel, pageSize: layout.size)
          .frame(height: layout.peekHeight)
          .padding(.bottom, layout.composerHeight)
      }
    }
  }
}

/// The top of the next video page, framed exactly as that page will show it: the server's
/// poster, or for a video without one (a fresh upload) its opening frame, as Android's strip
/// draws (`CommentVideoFrames`).
private struct ExpressReelPeekStrip: View {
  let comment: ExpressComment
  let pageSize: CGSize

  var body: some View {
    let shape = UnevenRoundedRectangle(topLeadingRadius: 16, topTrailingRadius: 16)
    let mode: ContentMode = comment.reelFillsPage ? .fill : .fit
    Color(expressARGB: 0xFF20_2124)
      .overlay(alignment: .top) {
        if let poster = comment.imageURL {
          WebImageReader(url: poster) { image in
            if let image {
              Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: mode)
            } else {
              Color.clear
            }
          }
          .frame(width: pageSize.width, height: pageSize.height)
          .clipped()
        } else if let video = comment.videoURL {
          ExpressVideoFirstFrame(url: video, contentMode: mode)
            .frame(width: pageSize.width, height: pageSize.height)
            .clipped()
        }
      }
      .clipShape(shape)
      .overlay(
        shape
          .stroke(Color(expressARGB: 0x33FF_FFFF), lineWidth: 1)
          .mask(LinearGradient(colors: [.white, .clear], startPoint: .top, endPoint: .center))
      )
      .accessibilityLabel("Next: video comment")
  }
}

// MARK: - Video page

extension ExpressComment {
  /// Tall videos fill the page; wider ones fit with black bars (Android: w/h ≤ 0.62 fills).
  var reelFillsPage: Bool {
    guard mediaWidth > 0, mediaHeight > 0 else { return true }
    return CGFloat(mediaWidth) / CGFloat(mediaHeight) <= 0.62
  }
}

private struct ExpressReelVideoPage: View {
  let comment: ExpressComment
  let title: String
  let isCurrent: Bool
  let showsTakeLabel: Bool
  let seen: Int
  let previousSeen: Int
  let total: Int
  let bottomInset: CGFloat
  let pageHeight: CGFloat
  var burstEvent: ExpressCommentsListModel.ReactionAdded?
  @ObservedObject var karma: ExpressKarmaModel
  @ObservedObject var playback: ExpressReelPlayback
  var onClose: () -> Void
  var onReact: (String) -> Void
  var onPickReaction: () -> Void
  var onReply: () -> Void
  var onAddTake: () -> Void

  @State private var captionExpanded = false
  @StateObject private var burst = ExpressEmojiBurstEngine()
  @State private var burstOrigins: [String: CGRect] = [:]
  @State private var pageSize: CGSize = .zero
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @Environment(\.expressToast) private var toast

  private static let space = "expressReelPage"

  /// The Karma run playing on this page, which folds the "Add your take" pill.
  private var karmaRunHere: UUID? {
    karma.run.flatMap { $0.reelCommentID == comment.id ? $0.id : nil }
  }

  private var isPlayingHere: Bool { playback.currentID == comment.id }

  var body: some View {
    ZStack {
      Color.black
      poster
      ExpressReelPlayerView(playback: playback, commentID: comment.id, fills: comment.reelFillsPage)
      scrims
      if isPlayingHere && playback.isPaused {
        playButton
      }
      if isPlayingHere && playback.isFastForwarding {
        fastForwardBadge
      }
    }
    .overlay(alignment: .topLeading) { topBar }
    .overlay(alignment: .topTrailing) {
      // Under the pill, which it slides out from behind.
      ExpressKarmaReelEffect(karma: karma, commentID: comment.id)
        .padding(.top, 29)
        .padding(.trailing, 60)
    }
    .overlay(alignment: .topTrailing) {
      ExpressReelTakeBubble(
        startsExpanded: showsTakeLabel,
        isCurrent: isCurrent,
        foldSignal: karmaRunHere,
        onTap: onAddTake
      )
      .padding(.top, 22)
      .padding(.trailing, 12)
    }
    .overlay(alignment: .bottomLeading) { commentBlock }
    .overlay(alignment: .bottomTrailing) { rail }
    .overlay(alignment: .bottom) {
      ExpressReelTimeline(playback: playback, isActive: isPlayingHere)
        .padding(.horizontal, 12)
        .padding(.bottom, 8 + bottomInset)
    }
    // The burst fills the whole page, over everything.
    .overlay { ExpressEmojiBurstView(engine: burst) }
    .coordinateSpace(.named(Self.space))
    .onGeometryChange(for: CGSize.self) { $0.size } action: { pageSize = $0 }
    .onChange(of: burstEvent) { _, event in playBurst(event) }
    .background(Color(expressARGB: 0xFF20_2124))
    .environment(\.colorScheme, .dark)
  }

  /// Pops from the rail chip that now shows the reader's emoji (or the add button) and fills the
  /// page, once the rail has laid out again.
  private func playBurst(_ event: ExpressCommentsListModel.ReactionAdded?) {
    guard let event, event.commentID == comment.id else { return }
    Task { @MainActor in
      try? await Task.sleep(nanoseconds: 60_000_000)
      let frame = burstOrigins[ExpressEmojiFamily.normalize(event.emoji)] ?? burstOrigins["react"]
      burst.play(
        event.emoji,
        origin: frame.map { CGPoint(x: $0.midX, y: $0.midY) },
        in: pageSize,
        fullscreen: true,
        reduceMotion: reduceMotion
      )
    }
  }

  /// The poster under the player. It's an overlay on a page-sized base: a filled image in a
  /// flexible frame would widen the page and push the overlays off screen.
  private var poster: some View {
    Color.clear
      .overlay {
        if let poster = comment.imageURL {
          WebImageReader(url: poster) { image in
            if let image {
              Image(uiImage: image)
                .resizable()
                .aspectRatio(contentMode: comment.reelFillsPage ? .fill : .fit)
            } else {
              Color.clear
            }
          }
        }
      }
      .clipped()
      .allowsHitTesting(false)
  }

  private var scrims: some View {
    ZStack {
      LinearGradient(
        stops: [
          .init(color: Color(expressARGB: 0x5211_1111), location: 0),
          .init(color: Color(expressARGB: 0x2911_1111), location: 0.386),
          .init(color: Color(expressARGB: 0x0211_1111), location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: 93)
      .frame(maxHeight: .infinity, alignment: .top)
      LinearGradient(
        stops: [
          .init(color: Color(expressARGB: 0x009B_9B9B), location: 0),
          .init(color: Color(expressARGB: 0x4011_1111), location: 0.4856),
          .init(color: Color(expressARGB: 0xB311_1111), location: 1),
        ],
        startPoint: .top,
        endPoint: .bottom
      )
      .frame(height: 108 + bottomInset)
      .frame(maxHeight: .infinity, alignment: .bottom)
      LinearGradient(
        stops: [
          .init(color: .clear, location: 0),
          .init(color: Color(expressARGB: 0x1A00_0000), location: 0.38),
          .init(color: Color(expressARGB: 0x5700_0000), location: 0.72),
          .init(color: Color(expressARGB: 0x8A00_0000), location: 1),
        ],
        startPoint: .leading,
        endPoint: .trailing
      )
      .mask(
        LinearGradient(
          stops: [
            .init(color: .clear, location: 0),
            .init(color: .white.opacity(0x9E / 255), location: 0.12),
            .init(color: .white, location: 0.24),
            .init(color: .white, location: 0.78),
            .init(color: .white.opacity(0x94 / 255), location: 0.92),
            .init(color: .clear, location: 1),
          ],
          startPoint: .top,
          endPoint: .bottom
        )
      )
      .frame(width: 154, height: 416)
      .offset(x: 57, y: -(36 + bottomInset))
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottomTrailing)
    }
    .allowsHitTesting(false)
  }

  private var playButton: some View {
    Circle()
      .fill(Color(ExpressColors.accent))
      .frame(width: 64, height: 64)
      .overlay(
        Image(systemName: "play.fill")
          .font(.system(size: 26))
          .foregroundColor(.white)
          .offset(x: 2)
      )
      .allowsHitTesting(false)
      .accessibilityLabel("Paused")
  }

  private var fastForwardBadge: some View {
    ExpressFastForwardBadge()
      .frame(maxHeight: .infinity, alignment: .top)
      .padding(.top, 96)
      .allowsHitTesting(false)
  }

  private var topBar: some View {
    VStack(alignment: .leading, spacing: 3) {
      HStack(spacing: 8) {
        Button(action: onClose) {
          Image(systemName: "arrow.left")
            .font(.system(size: 20, weight: .medium))
            .foregroundColor(.white)
            .frame(width: 24, height: 24)
            .contentShape(Rectangle())
        }
        .accessibilityLabel("Close comments")
        Text(title)
          .font(.system(size: 16))
          .foregroundColor(Color(expressARGB: 0xB3FF_FFFF))
          .lineLimit(1)
          .allowsHitTesting(false)
      }
      .frame(height: 48)
      // Only the title row leaves room for the "Add your take" pill; the bar sits below the
      // pill, so it runs the full width (wider than Android's, by request).
      .padding(.trailing, 170)
      ExpressReelStreamProgress(
        seen: seen,
        previousSeen: previousSeen,
        total: total,
        isCurrent: isCurrent
      )
      .padding(.leading, 32)
      .padding(.trailing, 16)
      .allowsHitTesting(false)
    }
    .padding(.top, 20)
    .padding(.leading, 16)
  }

  private var commentBlock: some View {
    VStack(alignment: .leading, spacing: 0) {
      if comment.isNew {
        Text("NEW")
          .font(.system(size: 10.5, weight: .bold))
          .tracking(0.4)
          .foregroundColor(.white)
          .padding(.horizontal, 8)
          .frame(height: 20)
          .background(RoundedRectangle(cornerRadius: 10).fill(Color(ExpressColors.accent)))
          .padding(.bottom, 8)
          .allowsHitTesting(false)
      }
      HStack(spacing: 6) {
        ExpressAvatarView(url: comment.avatarURL, size: 20)
        Text(comment.authorName)
          .font(.system(size: 12))
          .foregroundColor(Color(expressARGB: 0xEBFF_FFFF))
          .lineLimit(1)
          .frame(maxWidth: 200, alignment: .leading)
          .fixedSize(horizontal: true, vertical: false)
        if let date = comment.createdDate {
          Text(ExpressRelativeTime.string(from: date))
            .font(.system(size: 11))
            .foregroundColor(Color(expressARGB: 0xA8FF_FFFF))
        }
      }
      .frame(height: 20)
      .allowsHitTesting(false)
      if !comment.content.isEmpty {
        caption
          .padding(.top, 12)
      }
    }
    .padding(.leading, 12)
    .padding(.trailing, 122)
    .padding(.top, 14)
    .padding(.bottom, 37 + bottomInset)
  }

  @ViewBuilder private var caption: some View {
    let text = Text(comment.content)
      .font(.system(size: 15))
      .lineSpacing(5)
      .foregroundColor(Color(expressARGB: 0xF5FF_FFFF))
    if captionExpanded {
      ScrollView {
        text
          .fixedSize(horizontal: false, vertical: true)
          .frame(maxWidth: .infinity, alignment: .leading)
      }
      .frame(maxHeight: pageHeight * 0.4)
      .fixedSize(horizontal: false, vertical: true)
      .scrollBounceBehavior(.basedOnSize)
      .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { captionExpanded = false } }
    } else {
      text
        .lineLimit(1)
        .truncationMode(.tail)
        .onTapGesture { withAnimation(.easeInOut(duration: 0.2)) { captionExpanded = true } }
        .accessibilityAddTraits(.isButton)
        .accessibilityHint("Shows the whole comment")
    }
  }

  // MARK: Rail

  /// The top 3 reactions by count; the reader's own takes the third slot if it isn't there.
  private var railReactions: [ExpressReaction] {
    var top = Array(comment.reactions.prefix(3))
    if let mine = comment.myReaction,
      !top.contains(where: { ExpressReaction.same($0.emoji, mine) }),
      let own = comment.reactions.first(where: { ExpressReaction.same($0.emoji, mine) })
    {
      if top.count == 3 { top[2] = own } else { top.append(own) }
    }
    return top
  }

  private var rail: some View {
    VStack(alignment: .trailing, spacing: 0) {
      ForEach(Array(railReactions.enumerated()), id: \.element.emoji) { index, reaction in
        reactionChip(reaction)
          .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
            burstOrigins[ExpressEmojiFamily.normalize(reaction.emoji)] = $0
          }
          .padding(.top, index == 0 ? 0 : 8)
      }
      Button(action: onPickReaction) {
        Image(systemName: "face.smiling")
          .font(.system(size: 22))
          .foregroundColor(.white)
          .overlay(alignment: .topTrailing) {
            Image(systemName: "plus.circle.fill")
              .font(.system(size: 10, weight: .bold))
              .foregroundStyle(.white, Color(ExpressColors.accent))
              .offset(x: 4, y: -2)
          }
          .padding(.vertical, 2)
          .padding(.trailing, 5)
          .frame(width: 38.4, height: 28, alignment: .trailing)
      }
      .onGeometryChange(for: CGRect.self) { $0.frame(in: .named(Self.space)) } action: {
        burstOrigins["react"] = $0
      }
      .padding(.top, railReactions.isEmpty ? 0 : 8)
      .accessibilityLabel("Add reaction")
      RoundedRectangle(cornerRadius: 0.5)
        .fill(Color(expressARGB: 0xCCFF_FFFF))
        .frame(width: 28, height: 1)
        .padding(.top, 16)
        .padding(.trailing, 5)
      Button(action: onReply) {
        VStack(spacing: 2) {
          Image(systemName: "bubble.left")
            .font(.system(size: 21))
          Text(comment.commentCount > 0 ? ExpressNumberFormat.compact(comment.commentCount) : " ")
            .font(.system(size: 12, weight: .medium))
        }
        .foregroundColor(.white)
        .frame(width: 44, height: 46)
      }
      .padding(.top, 16)
      .accessibilityLabel(comment.commentCount == 1 ? "1 reply" : "\(comment.commentCount) replies")
      // Logs Android's share event and shares its message (`ExpressCommentShare`).
      if ExpressCommentShare.link(for: comment) != nil {
        Button {
          ExpressCommentShare.share(comment)
        } label: {
          Image(systemName: "paperplane")
            .font(.system(size: 21))
            .foregroundColor(.white)
            .frame(width: 44, height: 24)
        }
        .padding(.top, 12)
        .accessibilityLabel("Share")
      }
      // Android's ⋮ without Share, which has its own button here; "Copy text" only with text.
      Menu {
        Button(action: onReply) { Label("Reply", systemImage: "arrowshape.turn.up.left") }
        if ExpressCommentCopy.canCopy(comment) {
          Button {
            ExpressCommentCopy.copy(comment, toast: toast)
          } label: {
            Label("Copy text", systemImage: "doc.on.doc")
          }
        }
      } label: {
        Image(systemName: "ellipsis")
          .font(.system(size: 22, weight: .semibold))
          .foregroundColor(.white)
          .frame(width: 44, height: 32)
          .contentShape(Rectangle())
      }
      .padding(.top, 12)
      .accessibilityLabel("More options")
    }
    .frame(width: 44, alignment: .trailing)
    .padding(.trailing, 12)
    .padding(.bottom, 36 + bottomInset)
  }

  private func reactionChip(_ reaction: ExpressReaction) -> some View {
    let isMine = comment.myReaction.map { ExpressReaction.same($0, reaction.emoji) } ?? false
    return Button {
      onReact(reaction.emoji)
    } label: {
      VStack(spacing: 1) {
        Text(reaction.emoji).font(.system(size: 16))
        Text(ExpressNumberFormat.compact(reaction.count))
          .font(.system(size: 10, weight: .medium))
          .foregroundColor(isMine ? .white : Color(expressARGB: 0xB3FF_FFFF))
      }
      .frame(width: 38.4, height: 39.4)
      .background(
        RoundedRectangle(cornerRadius: 8)
          .fill(isMine ? Color(expressARGB: 0x3357_7DFF) : .clear)
          .overlay(
            RoundedRectangle(cornerRadius: 8)
              .stroke(
                isMine ? Color(ExpressColors.accent) : Color(expressARGB: 0x4DFF_FFFF),
                lineWidth: 0.7
              )
          )
      )
    }
    .buttonStyle(.plain)
    .accessibilityLabel("\(reaction.emoji) \(reaction.count)\(isMine ? ", your reaction" : "")")
  }
}

/// "38 / 214" under the title, played each time the reader arrives on a video page: fades in,
/// moves from the previous page's number to this one's, holds, then fades out.
private struct ExpressReelStreamProgress: View {
  let seen: Int
  let previousSeen: Int
  let total: Int
  let isCurrent: Bool

  @State private var shown = 0
  @State private var visible = false

  var body: some View {
    HStack(spacing: 8) {
      GeometryReader { proxy in
        let fraction = total > 0 ? max(CGFloat(shown) / CGFloat(total), 0.03) : 0
        ZStack(alignment: .leading) {
          RoundedRectangle(cornerRadius: 2).fill(Color(expressARGB: 0x47FF_FFFF))
          RoundedRectangle(cornerRadius: 2)
            .fill(Color(expressARGB: 0xEBFF_FFFF))
            .frame(width: proxy.size.width * fraction)
        }
        .frame(height: 3)
        .frame(maxHeight: .infinity)
      }
      Text("\(shown) / \(total)")
        .font(.system(size: 12, weight: .medium).monospacedDigit())
        .foregroundColor(Color(expressARGB: 0xE6FF_FFFF))
        .shadow(color: Color(expressARGB: 0x5900_0000), radius: 1.5, x: 0, y: 1)
        .contentTransition(.numericText())
        .fixedSize()
    }
    .frame(height: 16)
    .opacity(visible ? 1 : 0)
    .accessibilityHidden(true)
    .task(id: isCurrent) {
      guard isCurrent, total > 0 else {
        withAnimation(.easeOut(duration: 0.15)) { visible = false }
        return
      }
      shown = previousSeen > 0 ? previousSeen : seen
      withAnimation(.timingCurve(0.33, 1, 0.68, 1, duration: 0.25)) { visible = true }
      try? await Task.sleep(nanoseconds: 200_000_000)
      withAnimation(.easeInOut(duration: 0.4)) { shown = seen }
      try? await Task.sleep(nanoseconds: 2_100_000_000)
      guard !Task.isCancelled else { return }
      withAnimation(.timingCurve(0.65, 0, 0.35, 1, duration: 0.4)) { visible = false }
    }
  }
}

/// "Add your take": expanded on every third video page, folding to a round plus button 1.2 s
/// after the page settles.
private struct ExpressReelTakeBubble: View {
  let startsExpanded: Bool
  let isCurrent: Bool
  /// Changes when "Karma earned" plays here; the pill folds at once to make way.
  var foldSignal: UUID? = nil
  var onTap: () -> Void

  @State private var expanded = false

  var body: some View {
    Button(action: onTap) {
      HStack(spacing: 9) {
        Circle()
          .fill(.white)
          .frame(width: 30, height: 30)
          .overlay(
            Image(systemName: "plus")
              .font(.system(size: 15, weight: .bold))
              .foregroundColor(Color(expressARGB: 0xFF1B_1B22))
          )
        if expanded {
          Text("Add your take")
            .font(.system(size: 14, weight: .medium))
            .foregroundColor(.white)
            .lineLimit(1)
            .fixedSize()
            .padding(.trailing, 10)
            .transition(.opacity)
        }
      }
      .padding(3)
      .frame(height: 36)
      .background(
        Capsule()
          .fill(Color(expressARGB: 0x661E_2026))
          .overlay(Capsule().stroke(Color(expressARGB: 0x38FF_FFFF), lineWidth: 1))
      )
    }
    .buttonStyle(.plain)
    .accessibilityLabel("Add your take")
    .task(id: isCurrent) {
      expanded = startsExpanded
      guard isCurrent, startsExpanded else { return }
      try? await Task.sleep(nanoseconds: 1_200_000_000)
      guard !Task.isCancelled else { return }
      withAnimation(.timingCurve(0.645, 0.045, 0.355, 1, duration: 0.6)) { expanded = false }
    }
    .onChange(of: foldSignal) { _, signal in
      guard signal != nil, expanded else { return }
      withAnimation(.timingCurve(0.645, 0.045, 0.355, 1, duration: 0.6)) { expanded = false }
    }
  }
}

/// The thin timeline at the bottom of a video page; drag to seek (applied on release).
private struct ExpressReelTimeline: View {
  @ObservedObject var playback: ExpressReelPlayback
  let isActive: Bool

  @State private var dragFraction: Double?

  var body: some View {
    GeometryReader { proxy in
      let fraction = dragFraction ?? (isActive ? playback.progress : 0)
      ZStack(alignment: .leading) {
        RoundedRectangle(cornerRadius: 1).fill(Color(expressARGB: 0x47FF_FFFF)).frame(height: 2)
        RoundedRectangle(cornerRadius: 1)
          .fill(Color(expressARGB: 0xEBFF_FFFF))
          .frame(width: proxy.size.width * fraction, height: 2)
        if dragFraction != nil {
          Circle()
            .fill(Color(expressARGB: 0xEBFF_FFFF))
            .frame(width: 12, height: 12)
            .offset(x: proxy.size.width * fraction - 6)
        }
      }
      .frame(maxHeight: .infinity)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 0)
          .onChanged { value in
            guard isActive, proxy.size.width > 0 else { return }
            dragFraction = min(max(value.location.x / proxy.size.width, 0), 1)
          }
          .onEnded { _ in
            if let dragFraction { playback.seek(to: dragFraction) }
            dragFraction = nil
          }
      )
    }
    .frame(height: 20)
    .accessibilityElement()
    .accessibilityLabel("Video position")
    .accessibilityValue("\(Int((isActive ? playback.progress : 0) * 100)) percent")
  }
}

// MARK: - Fast forward

/// "2× ▶▶" while a hold plays a video at double speed (Android's
/// `browser_express_fast_forward_badge`). Media stays dark in both themes, so its colours are
/// fixed.
struct ExpressFastForwardBadge: View {
  var body: some View {
    HStack(spacing: 4) {
      Text("2×")
      Image(systemName: "forward.fill")
    }
    .font(.system(size: 14, weight: .medium))
    .foregroundColor(.white)
    .padding(.horizontal, 12)
    .padding(.vertical, 6)
    .background(RoundedRectangle(cornerRadius: 16).fill(Color(expressARGB: 0x9900_0000)))
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Playing at 2× speed")
  }
}

/// The system player with Android's hold-to-2× (`HoldToFastForward`, as `MediaViewerFragment`
/// uses it): a press resting 0.3 s on the right half of a playing video plays it at 2× with a
/// haptic tap, and letting go returns it to the speed it had. Taps still reach the player's own
/// controls; letting go of a hold isn't also a tap.
private struct ExpressHoldToFastForwardPlayer: UIViewControllerRepresentable {
  let player: AVPlayer
  @Binding var isFastForwarding: Bool

  func makeCoordinator() -> Coordinator { Coordinator() }

  func makeUIViewController(context: Context) -> AVPlayerViewController {
    let controller = AVPlayerViewController()
    controller.player = player
    let hold = UILongPressGestureRecognizer(
      target: context.coordinator,
      action: #selector(Coordinator.held(_:))
    )
    hold.minimumPressDuration = 0.3
    hold.delegate = context.coordinator
    controller.view.addGestureRecognizer(hold)
    return controller
  }

  func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
    if controller.player !== player { controller.player = player }
    context.coordinator.player = player
    context.coordinator.onChange = { isFastForwarding = $0 }
  }

  static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
    coordinator.end()
  }

  @MainActor
  final class Coordinator: NSObject, UIGestureRecognizerDelegate {
    weak var player: AVPlayer?
    var onChange: (Bool) -> Void = { _ in }
    private var isHolding = false
    private var rateBeforeHold: Float = 1

    @objc func held(_ recognizer: UILongPressGestureRecognizer) {
      switch recognizer.state {
      case .began:
        begin()
      case .ended, .cancelled, .failed:
        end()
      default:
        break
      }
    }

    private func begin() {
      guard let player, player.rate != 0 else { return }
      isHolding = true
      rateBeforeHold = player.rate
      player.rate = 2
      // The reel's hold haptic (`ExpressReelPlayback.beginFastForward`).
      UIImpactFeedbackGenerator(style: .heavy).impactOccurred()
      onChange(true)
    }

    /// Back to the speed it had, unless it stopped meanwhile.
    func end() {
      guard isHolding else { return }
      isHolding = false
      if let player, player.rate != 0 { player.rate = rateBeforeHold }
      onChange(false)
    }

    /// Only the right half, and only while the video plays.
    func gestureRecognizerShouldBegin(_ recognizer: UIGestureRecognizer) -> Bool {
      guard let view = recognizer.view, let player, player.rate != 0 else { return false }
      return recognizer.location(in: view).x >= view.bounds.width / 2
    }

    /// The player's taps (show controls, double-tap zoom) wait for a hold to fail, so a quick tap
    /// works as before and a hold isn't also a tap when it lifts.
    func gestureRecognizer(
      _ gestureRecognizer: UIGestureRecognizer,
      shouldBeRequiredToFailBy otherGestureRecognizer: UIGestureRecognizer
    ) -> Bool {
      otherGestureRecognizer is UITapGestureRecognizer
    }
  }
}

// MARK: - Full-screen video for inline cards

struct ExpressVideoViewer: View {
  let url: URL

  @Environment(\.dismiss) private var dismiss
  @State private var player: AVPlayer?
  @State private var isFastForwarding = false

  var body: some View {
    ZStack(alignment: .topLeading) {
      Color.black.ignoresSafeArea()
      if let player {
        ExpressHoldToFastForwardPlayer(player: player, isFastForwarding: $isFastForwarding)
          .ignoresSafeArea()
      }
      if isFastForwarding {
        // Android: centred, 76 dp from the top.
        ExpressFastForwardBadge()
          .frame(maxWidth: .infinity)
          .padding(.top, 76)
          .allowsHitTesting(false)
      }
      Button {
        dismiss()
      } label: {
        Image(systemName: "xmark")
          .font(.system(size: 16, weight: .semibold))
          .foregroundColor(.white)
          .frame(width: 40, height: 40)
          .background(Circle().fill(Color.black.opacity(0.5)))
      }
      .padding(16)
      .accessibilityLabel("Close video")
    }
    .onAppear {
      let player = AVPlayer(playerItem: AVPlayerItem(asset: ExpressMediaCache.shared.asset(for: url)))
      self.player = player
      try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback)
      // One sound at a time, as on Android: the full-screen video never plays over a voice note.
      ExpressAudioPlayback.shared.pause()
      player.play()
    }
    .onDisappear { player?.pause() }
  }
}

/// Reaches the UIKit scroll view behind the SwiftUI `ScrollView` it sits in.
struct ExpressEnclosingScrollView: UIViewRepresentable {
  var configure: (UIScrollView) -> Void

  func makeUIView(context: Context) -> FinderView {
    FinderView(configure: configure)
  }

  func updateUIView(_ view: FinderView, context: Context) {
    view.configure = configure
    view.apply()
  }

  final class FinderView: UIView {
    var configure: (UIScrollView) -> Void

    init(configure: @escaping (UIScrollView) -> Void) {
      self.configure = configure
      super.init(frame: .zero)
      isUserInteractionEnabled = false
    }

    required init?(coder: NSCoder) { fatalError() }

    override func didMoveToWindow() {
      super.didMoveToWindow()
      apply()
    }

    func apply() {
      var view = superview
      while let current = view {
        if let scrollView = current as? UIScrollView {
          configure(scrollView)
          return
        }
        view = current.superview
      }
    }
  }
}

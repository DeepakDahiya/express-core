// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Combine
import CoreGraphics
import ExpressCore
import Foundation
import os.log

/// Loads the Express feed (`GET /v1/post/feed`) for the new tab page. One instance is shared by
/// every tab's new tab page, so the posts survive the per-tab page being recreated.
///
/// Follows the Android app: the first page loads anonymously, then (outside India) a guest
/// account is created and the first page reloads with it. The next page loads when the user
/// is within 5 posts of the end; only one request runs at a time; a failed page waits for
/// "Tap to try again". Hidden posts are remembered locally (newest 500).
///
/// Android keeps the posts in each new tab page (`BraveNewTabPageLayout.mPosts`), so every page
/// it opens, or builds again after the tab went elsewhere, loads a fresh first page under its
/// skeleton. Here a newly opened new tab page, or any once the feed is 30 minutes old, loads the
/// first page again; the posts already loaded stay on screen until it arrives.
@MainActor
final class ExpressFeedDataSource: ObservableObject {
  enum LoadState: Equatable {
    case idle
    case loading
    case failed(String)
    case endReached
  }

  @Published private(set) var posts: [ExpressPost] = []
  @Published private(set) var state: LoadState = .idle
  /// Posts hidden during this showing of the page. They show "Post hidden / Undo" until the
  /// page is shown again, then they're dropped.
  @Published private(set) var recentlyHiddenIDs: Set<String> = []
  /// Posts whose caption the user expanded with "More".
  @Published private(set) var expandedIDs: Set<String> = []
  /// Posts whose "new takes" the reader opened; their callout stays hidden until the feed
  /// reloads.
  @Published private(set) var seenNewTakesIDs: Set<String> = []
  /// Video shapes read from the files when the feed leaves them out (often 0×0), clamped to
  /// 0.8...1.91 like Android, so the card takes the video's real shape.
  @Published private(set) var videoAspects: [String: CGFloat] = [:]

  /// The feed's one video player, shared by every card.
  let videos = ExpressFeedVideoPlayback()

  /// Asks for phone sign-in (India, signed out). Android does this once per launch.
  var onSignInRequired: (() -> Void)?
  private static var didPromptSignIn = false
  private var accountCancellable: AnyCancellable?
  private var loadedForUserID: String?

  private let client: ExpressAPIClient
  private let account: ExpressAccount
  private var nextCursor: String?
  private var hasLoadedFirstPage = false
  private var loadTask: Task<Void, Never>?
  /// A first page (the first one, or a reload) is loading.
  private var isLoadingFirstPage = false
  /// A reloaded first page is waiting for the comments sheet to close.
  private var isHoldingFirstPage = false
  /// When the first page last arrived, for the 30-minute rule.
  private var firstPageLoadedAt: Date?

  private static let hiddenPostsKey = "express.hidden-posts"
  private static let maxHiddenPosts = 500
  private var hiddenPosts: [String: Double]

  init(client: ExpressAPIClient = .shared, account: ExpressAccount = .shared) {
    self.client = client
    self.account = account
    hiddenPosts =
      UserDefaults.standard.dictionary(forKey: Self.hiddenPostsKey) as? [String: Double] ?? [:]
    // Like Android's launcher, refresh the server switches at most every 6 hours.
    Task { await ExpressRemoteConfigStore.refreshIfNeeded(client: client) }
    // A new account (sign-in, sign-out) gets its own feed. A reload waiting for the comments
    // sheet was asked for with the old account, so it starts over.
    accountCancellable = account.$accessToken
      .dropFirst()
      .receive(on: RunLoop.main)
      .sink { [weak self] _ in
        guard let self, self.hasLoadedFirstPage, self.loadTask == nil || self.isHoldingFirstPage
        else { return }
        if self.account.claims?.userID != self.loadedForUserID { self.loadFirstPage() }
      }
  }

  /// Called each time a new tab page appears; `isNewTab` the first time a tab's page does. A new
  /// page loads the first page again, as Android's do (`BraveNewTabPageLayout.loadPosts`), and so
  /// does any once the feed is 30 minutes old. Returns whether that reload runs under posts
  /// already on screen, so the page can show the top of the feed for it.
  @discardableResult
  func pageWillAppear(isNewTab: Bool) -> Bool {
    if !recentlyHiddenIDs.isEmpty {
      posts.removeAll { recentlyHiddenIDs.contains($0.id) }
      recentlyHiddenIDs = []
    }
    guard isNewTab || isStale else { return false }
    return reloadFirstPage()
  }

  /// The app came back to a new tab page that was already showing: the first page loads again
  /// once the feed is 30 minutes old. Returns whether that reload runs under posts on screen.
  @discardableResult
  func refreshIfStale() -> Bool {
    isStale ? reloadFirstPage() : false
  }

  /// Android's feed has no clock (each new tab page loads its own); 30 minutes, as the profile
  /// refreshes (`ExpressProfileModel.refreshIfStale`).
  private static var staleAfter: TimeInterval {
    #if DEBUG
    // `-ExpressFeedStaleSeconds 20`: a short wait, for testing.
    let override = UserDefaults.standard.double(forKey: "ExpressFeedStaleSeconds")
    if override > 0 { return override }
    #endif
    return 30 * 60
  }

  /// No first page yet, or the last one is older than `staleAfter`.
  private var isStale: Bool {
    guard let firstPageLoadedAt else { return true }
    return Date().timeIntervalSince(firstPageLoadedAt) > Self.staleAfter
  }

  /// Loads the first page again, unless one is already loading, or the comments sheet covers
  /// posts that would change under it (the next appearance asks again). Returns whether posts
  /// stay on screen meanwhile.
  private func reloadFirstPage() -> Bool {
    guard !isLoadingFirstPage, posts.isEmpty || !videos.isCovered else { return false }
    let keepsPosts = !posts.isEmpty
    loadFirstPage()
    return keepsPosts
  }

  /// The first page is loading with no posts to show yet: the feed shows its skeleton
  /// (Android's `PostListAdapter.showShimmer`).
  var showsSkeleton: Bool { posts.isEmpty && state == .loading }

  /// Loads the first page. Posts already on screen stay until it arrives and are swapped for
  /// it then; with none, the skeleton shows meanwhile.
  func loadFirstPage() {
    loadTask?.cancel()
    if posts.isEmpty {
      nextCursor = nil
      state = .loading
    } else if state == .loading {
      // The next page was loading; the new first page replaces it.
      state = .idle
    }
    isLoadingFirstPage = true
    isHoldingFirstPage = false
    loadTask = Task { [weak self] in
      guard let self else { return }
      await self.fetch(cursor: nil, replacing: true)
      // A newer first page took over.
      if Task.isCancelled { return }
      if self.account.validAccessToken == nil {
        if await self.account.ensureGuestAccount() {
          if Task.isCancelled { return }
          await self.fetch(cursor: nil, replacing: true)
          if Task.isCancelled { return }
        } else if ExpressAccount.isIndia, !Self.didPromptSignIn {
          Self.didPromptSignIn = true
          self.onSignInRequired?()
        }
      }
      self.loadedForUserID = self.account.claims?.userID
      self.isLoadingFirstPage = false
      self.loadTask = nil
    }
  }

  /// Loads the next page when the displayed post is within 5 of the end.
  func loadMoreIfNeeded(displayedIndex: Int) {
    guard hasLoadedFirstPage, loadTask == nil, nextCursor != nil else { return }
    guard state == .idle, displayedIndex >= posts.count - 5 else { return }
    loadNextPage()
  }

  /// "Tap to try again" in the footer.
  func retry() {
    if !hasLoadedFirstPage {
      loadFirstPage()
    } else if loadTask == nil, nextCursor != nil {
      loadNextPage()
    }
  }

  private func loadNextPage() {
    let cursor = nextCursor
    state = .loading
    loadTask = Task { [weak self] in
      await self?.fetch(cursor: cursor, replacing: false)
      // A first page that replaced this one owns `loadTask` now.
      if Task.isCancelled { return }
      self?.loadTask = nil
    }
  }

  private func fetch(cursor: String?, replacing: Bool) async {
    // With nothing on screen the skeleton shows (again for the guest account's first page).
    if replacing, posts.isEmpty { state = .loading }
    do {
      let page = try await client.fetchFeed(cursor: cursor, accessToken: account.validAccessToken)
      if Task.isCancelled { return }
      // A new first page doesn't change the list under the comments sheet.
      if replacing, !posts.isEmpty {
        await waitWhileCommentsCoverFeed()
        if Task.isCancelled { return }
      }
      let previous = posts
      var merged = replacing ? [] : posts
      var seen = Set(merged.map(\.id))
      for post in page.posts where !seen.contains(post.id) && hiddenPosts[post.id] == nil {
        merged.append(post)
        seen.insert(post.id)
      }
      #if DEBUG
      // `-ExpressSampleNewTakes YES`: sample "new takes" on the first photo/video post and the
      // first text post, to check the callout. Local only.
      if replacing, UserDefaults.standard.bool(forKey: "ExpressSampleNewTakes") {
        if let i = merged.firstIndex(where: { $0.hasMedia && $0.newTakes == nil }) {
          merged[i] = merged[i].withSampleNewTakes(textOnly: false, firstNewTakeID: nil)
        }
        if let i = merged.firstIndex(where: { !$0.hasMedia && $0.newTakes == nil }) {
          merged[i] = merged[i].withSampleNewTakes(textOnly: true, firstNewTakeID: nil)
        }
      }
      // `-ExpressSampleVoicePostURL <url>`: the first post without a photo, video or voice note
      // becomes a 12 s voice post playing that audio (an http(s) or file URL, or a plain path),
      // to check the player row. Local only.
      if replacing {
        ExpressFeedVoice.debugSamples = [:]
        if let url = ExpressFeedVoice.debugSampleURL,
          let post = merged.first(where: { !$0.hasMedia && $0.audio == nil })
        {
          ExpressFeedVoice.debugSamples[post.id] = ExpressFeedVoice(url: url, durationMs: 12_000)
          Logger.module.info("Express sample voice post: \(post.id, privacy: .public)")
        }
      }
      #endif
      posts = merged
      if replacing {
        // A fresh first page carries the server's own idea of what's new.
        seenNewTakesIDs = []
        // The old posts' "Post hidden / Undo" rows go with them.
        recentlyHiddenIDs = []
        firstPageLoadedAt = Date()
        releaseVideos(of: previous, keeping: merged)
      }
      nextCursor = page.nextCursor
      hasLoadedFirstPage = true
      state = page.nextCursor == nil ? .endReached : .idle
      if page.nextCursor == nil, !replacing {
        ExpressAnalytics.shared.capture("Feed End Reached", ["posts": posts.count])
      }
      probeVideoShapes(page.posts)
    } catch {
      if Task.isCancelled { return }
      Logger.module.error("Express feed load failed: \(error.localizedDescription, privacy: .public)")
      // A reload that fails leaves the posts on screen as they were, with their next page.
      if !replacing || posts.isEmpty {
        state = .failed(error.localizedDescription)
      }
      ExpressAnalytics.shared.capture(
        "Feed Load Failed",
        ["page": replacing ? "first" : "next", "error": error.localizedDescription]
      )
    }
  }

  /// Holds a reloaded first page while the comments sheet covers the feed, so the list doesn't
  /// change under it. Checked every quarter second, for at most 5 minutes in case the sheet
  /// never reports closing.
  private func waitWhileCommentsCoverFeed() async {
    guard videos.isCovered else { return }
    isHoldingFirstPage = true
    let deadline = Date().addingTimeInterval(5 * 60)
    while videos.isCovered, Date() < deadline {
      try? await Task.sleep(for: .milliseconds(250))
      // A newer first page took over (and reset the flag).
      if Task.isCancelled { return }
    }
    isHoldingFirstPage = false
  }

  /// Videos of the posts a new first page dropped stop being candidates to play: the others
  /// first and the playing one last, so none of them is picked to play in between. The cards
  /// that disappear in the reload would drop them too, but one at a time. Posts that stay keep
  /// theirs; their cards report again where they land.
  private func releaseVideos(of previous: [ExpressPost], keeping current: [ExpressPost]) {
    let kept = Set(current.map(\.id))
    let gone = Set(previous.filter { $0.mediaVideoURL != nil && !kept.contains($0.id) }.map(\.id))
    let playing = videos.playingID
    for id in gone where id != playing {
      videos.remove(id)
    }
    if let playing, gone.contains(playing) {
      videos.remove(playing)
    }
  }

  /// The card's media shape: the server's, else the probed video's.
  func mediaAspectRatio(for post: ExpressPost) -> CGFloat? {
    post.mediaAspectRatio ?? videoAspects[post.id]
  }

  private func probeVideoShapes(_ newPosts: [ExpressPost]) {
    let unknown = newPosts.compactMap { post -> (String, URL)? in
      guard post.mediaAspectRatio == nil, videoAspects[post.id] == nil,
        let url = post.mediaVideoURL
      else { return nil }
      return (post.id, url)
    }
    guard !unknown.isEmpty else { return }
    Task { [weak self] in
      var found: [String: CGFloat] = [:]
      await withTaskGroup(of: (String, CGSize?).self) { group in
        for (id, url) in unknown {
          group.addTask { (id, await ExpressVideoDimensions.shared.size(for: url)) }
        }
        for await (id, size) in group {
          if let size, size.width > 0, size.height > 0 {
            found[id] = min(max(size.width / size.height, 0.8), 1.91)
          }
        }
      }
      guard let self, !found.isEmpty else { return }
      self.videoAspects.merge(found) { _, new in new }
    }
  }

  // MARK: - Hide / expand

  func hide(_ post: ExpressPost) {
    hiddenPosts[post.id] = Date().timeIntervalSince1970 * 1000
    if hiddenPosts.count > Self.maxHiddenPosts {
      let oldest = hiddenPosts.sorted { $0.value < $1.value }
        .prefix(hiddenPosts.count - Self.maxHiddenPosts)
      for (id, _) in oldest { hiddenPosts.removeValue(forKey: id) }
    }
    UserDefaults.standard.set(hiddenPosts, forKey: Self.hiddenPostsKey)
    recentlyHiddenIDs.insert(post.id)
  }

  func undoHide(_ post: ExpressPost) {
    hiddenPosts.removeValue(forKey: post.id)
    UserDefaults.standard.set(hiddenPosts, forKey: Self.hiddenPostsKey)
    recentlyHiddenIDs.remove(post.id)
  }

  func markNewTakesSeen(_ post: ExpressPost) {
    seenNewTakesIDs.insert(post.id)
  }

  func toggleExpanded(_ post: ExpressPost) {
    if expandedIDs.contains(post.id) {
      expandedIDs.remove(post.id)
    } else {
      expandedIDs.insert(post.id)
    }
  }
}

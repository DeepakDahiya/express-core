// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import Combine
import ExpressCore
import SwiftUI
import UIKit

/// A collection view cell that hosts SwiftUI content at the full section width and sizes
/// itself to the content's height.
final class ExpressHostingCell: UICollectionViewCell, CollectionViewReusable {
  override func preferredLayoutAttributesFitting(
    _ layoutAttributes: UICollectionViewLayoutAttributes
  ) -> UICollectionViewLayoutAttributes {
    let attributes = layoutAttributes.copy() as! UICollectionViewLayoutAttributes
    attributes.size.height =
      systemLayoutSizeFitting(
        layoutAttributes.size,
        withHorizontalFittingPriority: .required,
        verticalFittingPriority: .fittingSizeLevel
      ).height
    return attributes
  }
}

/// Where the new tab page header's search box sits.
enum ExpressNTPSearchBoxLayout {
  /// The header's coordinate space, which a reported frame is in.
  static let coordinateSpace = "expressNTPHeader"
  /// Where `ExpressNTPHeaderView` puts the box (20 pt padding, the 54 pt logo, 24 pt padding,
  /// then the 72 pt capsule), used until the header reports its real frame.
  static let defaultTop: CGFloat = 20 + 54 + 24
  static let defaultHeight: CGFloat = 72
}

/// The header's search box as the address bar sees it (Android's `ntp_search_box` in
/// `BraveNewTabPageLayout`): where it sits in the header, so the address field can take over as
/// it scrolls under the toolbar, and how visible it is while the field does.
@MainActor
final class ExpressNTPSearchBox: ObservableObject {
  /// The box's frame in the header, once the header marks it with `expressNTPSearchBox()`.
  var reportedFrame: CGRect? {
    didSet {
      guard oldValue != reportedFrame else { return }
      // Reported from a SwiftUI geometry callback; the address bar answers by setting this
      // box's opacity, which mustn't publish in the middle of a view update.
      DispatchQueue.main.async { [weak self] in self?.onFrameChange?() }
    }
  }
  /// Called when the reported frame changes, so the address bar can follow it.
  var onFrameChange: (() -> Void)?
  /// Android's `setSearchBoxAlpha` (and its fade on focus): the box gives way to the address
  /// field. Only a header that marks its box with `expressNTPSearchBox()` fades.
  @Published private(set) var opacity: Double = 1

  func setOpacity(_ value: Double) {
    let value = min(1, max(0, value))
    if abs(value - opacity) > 0.001 { opacity = value }
  }

  /// The box's frame in a header laid out at `headerFrame`.
  func frame(inHeaderAt headerFrame: CGRect) -> CGRect {
    let box =
      reportedFrame
      ?? CGRect(
        x: 0,
        y: ExpressNTPSearchBoxLayout.defaultTop,
        width: headerFrame.width,
        height: ExpressNTPSearchBoxLayout.defaultHeight
      )
    return box.offsetBy(dx: headerFrame.minX, dy: headerFrame.minY)
  }
}

private struct ExpressNTPSearchBoxKey: EnvironmentKey {
  static let defaultValue: ExpressNTPSearchBox? = nil
}

extension EnvironmentValues {
  /// The new tab page header's search box tracker, set on the header by its section.
  var expressNTPSearchBox: ExpressNTPSearchBox? {
    get { self[ExpressNTPSearchBoxKey.self] }
    set { self[ExpressNTPSearchBoxKey.self] = newValue }
  }
}

extension View {
  /// Marks the new tab page header's search box: it reports its frame for the address bar's
  /// hand-off and fades while the address field takes over (Android `headerSearchBox()` and
  /// `applyHeaderSearchBoxAlpha`). Does nothing outside the new tab page header.
  func expressNTPSearchBox() -> some View {
    modifier(ExpressNTPSearchBoxMarker())
  }
}

private struct ExpressNTPSearchBoxMarker: ViewModifier {
  @Environment(\.expressNTPSearchBox) private var searchBox

  @ViewBuilder func body(content: Content) -> some View {
    if let searchBox {
      content.modifier(ExpressNTPSearchBoxTracking(searchBox: searchBox))
    } else {
      content
    }
  }
}

private struct ExpressNTPSearchBoxTracking: ViewModifier {
  @ObservedObject var searchBox: ExpressNTPSearchBox

  func body(content: Content) -> some View {
    content
      .opacity(searchBox.opacity)
      .onGeometryChange(for: CGRect.self) {
        $0.frame(in: .named(ExpressNTPSearchBoxLayout.coordinateSpace))
      } action: { frame in
        searchBox.reportedFrame = frame
      }
  }
}

/// The header at the top of the new tab page (Android's `ntp_header`): logo, search box,
/// sites and the tutorial video.
@MainActor
final class ExpressHeaderSectionProvider: NSObject, NTPObservableSectionProvider {
  var sectionDidChange: (() -> Void)?

  /// The header's search box, for the address bar's hand-off.
  let searchBox = ExpressNTPSearchBox()

  private let focusSearch: () -> Void
  private let voiceSearch: () -> Void
  private let openURL: (URL) -> Void
  private let showsVoiceSearch: () -> Bool
  private var tutorial: ExpressTutorialVideo?
  private var tutorialDismissed = false

  init(
    focusSearch: @escaping () -> Void,
    voiceSearch: @escaping () -> Void,
    showsVoiceSearch: @escaping () -> Bool,
    openURL: @escaping (URL) -> Void
  ) {
    self.focusSearch = focusSearch
    self.voiceSearch = voiceSearch
    self.showsVoiceSearch = showsVoiceSearch
    self.openURL = openURL
    super.init()
    loadTutorial()
  }

  /// Fetched each time a new tab page is set up, like Android; closing it lasts until then.
  private func loadTutorial() {
    Task { [weak self] in
      var video = try? await ExpressAPIClient.shared.fetchTutorialVideo()
      #if DEBUG
      // `-ExpressForceTutorialVideo YES`: show the server's video even while it's switched off.
      if video == nil, UserDefaults.standard.bool(forKey: "ExpressForceTutorialVideo") {
        video = try? await ExpressAPIClient.shared.fetchTutorialVideo(ignoringEnabled: true)
      }
      #endif
      guard let self, let video else { return }
      self.tutorial = video
      self.sectionDidChange?()
    }
  }

  private var shownTutorial: ExpressTutorialVideo? {
    tutorialDismissed ? nil : tutorial
  }

  var landscapeBehavior: NTPLandscapeSizingBehavior { .fullWidth }

  func registerCells(to collectionView: UICollectionView) {
    collectionView.register(ExpressHostingCell.self)
  }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int)
    -> Int
  {
    1
  }

  func collectionView(
    _ collectionView: UICollectionView,
    cellForItemAt indexPath: IndexPath
  ) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(for: indexPath) as ExpressHostingCell
    let openURL = self.openURL
    cell.contentConfiguration = UIHostingConfiguration {
      ExpressNTPHeaderView(
        sites: ExpressNTPSites.display,
        tutorial: shownTutorial,
        showsVoiceSearch: showsVoiceSearch(),
        onSearch: focusSearch,
        onVoiceSearch: voiceSearch,
        onOpenSite: { url in
          if url == ExpressNTPSites.youTube.url, ExpressAccount.shared.validAccessToken != nil {
            ExpressAnalytics.shared.capture("YTP Premium Clicked on Home")
          }
          openURL(url)
        },
        onTutorialTap: {
          ExpressAnalytics.shared.capture("Home Banner Clicked")
          openURL(ExpressNTPSites.youTube.url)
        },
        onDismissTutorial: { [weak self] in
          self?.tutorialDismissed = true
          self?.sectionDidChange?()
        }
      )
      .coordinateSpace(.named(ExpressNTPSearchBoxLayout.coordinateSpace))
      .environment(\.expressNTPSearchBox, searchBox)
    }
    .margins(.all, 0)
    return cell
  }

  /// The search box's frame in the collection view's content, from the header cell's layout;
  /// nil before the header is laid out.
  func searchBoxFrame(in collectionView: UICollectionView, section: Int) -> CGRect? {
    guard section < collectionView.numberOfSections,
      collectionView.numberOfItems(inSection: section) > 0,
      let header = collectionView.layoutAttributesForItem(at: IndexPath(item: 0, section: section))?
        .frame,
      header.width > 0
    else { return nil }
    return searchBox.frame(inHeaderAt: header)
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    sizeForItemAt indexPath: IndexPath
  ) -> CGSize {
    var size = fittingSizeForCollectionView(collectionView, section: indexPath.section)
    size.height = ExpressNTPHeaderView.height(hasSites: true, hasTutorial: shownTutorial != nil)
    return size
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    insetForSectionAt section: Int
  ) -> UIEdgeInsets {
    UIEdgeInsets(top: 0, left: 12, bottom: 8, right: 12)
  }
}

/// How a feed card opens.
enum ExpressPostOpening {
  /// The post's comments.
  case comments
  /// The comments at the first of the card's new takes.
  case newTakes(firstID: String?)
  /// The video full screen over the comments, from `startSeconds` when the card was audible.
  case video(URL, startSeconds: Double?)
}

/// The Express feed: one card per post, then a footer with the first load's skeleton, a
/// spinner or "Tap to try again".
@MainActor
final class ExpressFeedSectionProvider: NSObject, NTPObservableSectionProvider {
  var sectionDidChange: (() -> Void)?

  private let dataSource: ExpressFeedDataSource
  private let openURL: (URL) -> Void
  /// Opens a card's post. The index path is where the card is now, so the page can bring it
  /// to the top first (Android `PostListAdapter.openPost`).
  private let openPost: (ExpressPost, IndexPath, ExpressPostOpening) -> Void
  private var cancellable: AnyCancellable?
  /// The feed's section in the new tab page's collection view, for impressions.
  private var feedSection: Int?
  private weak var feedCollectionView: UICollectionView?

  init(
    dataSource: ExpressFeedDataSource,
    openURL: @escaping (URL) -> Void,
    openPost: @escaping (ExpressPost, IndexPath, ExpressPostOpening) -> Void
  ) {
    self.dataSource = dataSource
    self.openURL = openURL
    self.openPost = openPost
    super.init()
    // `objectWillChange` fires before the new values are set, so reload on the next run loop.
    cancellable = dataSource.objectWillChange
      .debounce(for: .milliseconds(30), scheduler: RunLoop.main)
      .sink { [weak self] _ in
        self?.sectionDidChange?()
      }
  }

  var landscapeBehavior: NTPLandscapeSizingBehavior { .fullWidth }

  func registerCells(to collectionView: UICollectionView) {
    collectionView.register(ExpressHostingCell.self)
  }

  func collectionView(_ collectionView: UICollectionView, numberOfItemsInSection section: Int)
    -> Int
  {
    dataSource.posts.count + 1  // + footer
  }

  func collectionView(
    _ collectionView: UICollectionView,
    cellForItemAt indexPath: IndexPath
  ) -> UICollectionViewCell {
    let cell = collectionView.dequeueReusableCell(for: indexPath) as ExpressHostingCell
    let dataSource = self.dataSource
    feedSection = indexPath.section
    let telemetry = dataSource.videos.telemetry
    if feedCollectionView !== collectionView {
      feedCollectionView = collectionView
      telemetry.onBecameActive = { [weak self] in
        guard let self, let collectionView = self.feedCollectionView else { return }
        self.updateImpressions(collectionView)
      }
    }
    if indexPath.item < dataSource.posts.count {
      let post = dataSource.posts[indexPath.item]
      let position = indexPath.item
      let openURL = self.openURL
      let openPost = self.openPost
      let cardIndexPath = indexPath
      /// Opens the post from this card, at the card's current place (a reload since this cell
      /// was set up may have moved it).
      let open: (ExpressPostOpening) -> Void = { opening in
        let item = dataSource.posts.firstIndex(where: { $0.id == post.id }) ?? cardIndexPath.item
        openPost(post, IndexPath(item: item, section: cardIndexPath.section), opening)
      }
      let showsNewTakes = post.newTakes != nil && !dataSource.seenNewTakesIDs.contains(post.id)
      telemetry.newTakesCounts[post.id] = showsNewTakes ? post.newTakes?.count : nil
      /// "12 new takes" showing: any tap opens the comments at the first of them, and the
      /// callout goes (Android's `openPost`). Returns false when there are none.
      let openNewTakes: () -> Bool = {
        guard showsNewTakes, let takes = post.newTakes else { return false }
        dataSource.markNewTakesSeen(post)
        ExpressAnalytics.shared.capture("New Takes Tapped", ["post_id": post.id, "count": takes.count])
        telemetry.opened(post.id, target: "comments", position: position)
        open(.newTakes(firstID: takes.firstNewTakeID))
        return true
      }
      cell.contentConfiguration = UIHostingConfiguration {
        ExpressPostCardView(
          post: post,
          isHidden: dataSource.recentlyHiddenIDs.contains(post.id),
          isExpanded: dataSource.expandedIDs.contains(post.id),
          showsNewTakes: showsNewTakes,
          mediaAspectRatio: dataSource.mediaAspectRatio(for: post),
          videos: dataSource.videos,
          onOpen: {
            // Like Android: new takes first; then publisher posts with `redirect` open their
            // article, video posts play full screen over their comments, and the rest open
            // the comments.
            if openNewTakes() { return }
            if post.opensArticle, let url = post.url {
              telemetry.opened(post.id, target: "article", position: position)
              openURL(url)
            } else if let videoURL = post.mediaVideoURL {
              telemetry.opened(post.id, target: "video", position: position)
              open(.video(videoURL, startSeconds: dataSource.videos.handoffSeconds(for: post.id)))
            } else {
              telemetry.opened(post.id, target: "comments", position: position)
              open(.comments)
            }
          },
          onOpenRail: {
            // The rail always opens the comments, never the video.
            if openNewTakes() { return }
            telemetry.opened(post.id, target: "comments", position: position)
            open(.comments)
          },
          onHide: {
            telemetry.hid(post.id, undo: false, position: position)
            dataSource.hide(post)
          },
          onUndo: {
            telemetry.hid(post.id, undo: true, position: position)
            dataSource.undoHide(post)
          },
          onToggleExpanded: {
            if !dataSource.expandedIDs.contains(post.id) { telemetry.expanded(post.id) }
            dataSource.toggleExpanded(post)
          }
        )
        // Another post in this cell (a refreshed first page) is another card: the old one's
        // video goes and the new one's reports where it is.
        .id(post.id)
      }
      .margins(.all, 0)
    } else {
      cell.contentConfiguration = UIHostingConfiguration {
        ExpressFeedFooterView(
          state: dataSource.state,
          hasPosts: !dataSource.posts.isEmpty,
          onRetry: { dataSource.retry() }
        )
      }
      .margins(.all, 0)
    }
    return cell
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    sizeForItemAt indexPath: IndexPath
  ) -> CGSize {
    var size = fittingSizeForCollectionView(collectionView, section: indexPath.section)
    if indexPath.item < dataSource.posts.count {
      size.height = dataSource.posts[indexPath.item].hasMedia ? 420 : 160
    } else if dataSource.showsSkeleton {
      size.height = ExpressFeedSkeleton.height(width: size.width)
    } else {
      size.height = 72
    }
    return size
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    insetForSectionAt section: Int
  ) -> UIEdgeInsets {
    UIEdgeInsets(top: 8, left: 12, bottom: 16, right: 12)
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    minimumLineSpacingForSectionAt section: Int
  ) -> CGFloat {
    4
  }

  func collectionView(
    _ collectionView: UICollectionView,
    willDisplay cell: UICollectionViewCell,
    forItemAt indexPath: IndexPath
  ) {
    dataSource.loadMoreIfNeeded(displayedIndex: indexPath.item)
    updateVideoViewport(collectionView)
    updateImpressions(collectionView)
  }

  func scrollViewDidScroll(_ scrollView: UIScrollView) {
    updateVideoViewport(scrollView)
    if let collectionView = scrollView as? UICollectionView {
      updateImpressions(collectionView)
    }
  }

  /// Posts at least half on screen (half of the card, or of the screen for tall cards).
  private func updateImpressions(_ collectionView: UICollectionView) {
    guard let feedSection, collectionView.window != nil else { return }
    let viewport = collectionView.bounds.inset(by: collectionView.adjustedContentInset)
    guard viewport.height > 0 else { return }
    var visible: [String: Int] = [:]
    for indexPath in collectionView.indexPathsForVisibleItems
    where indexPath.section == feedSection && indexPath.item < dataSource.posts.count {
      guard let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame else { continue }
      let shown = frame.intersection(viewport)
      guard !shown.isNull, frame.height > 0 else { continue }
      if shown.height / min(frame.height, viewport.height) >= 0.5 {
        visible[dataSource.posts[indexPath.item].id] = indexPath.item
      }
    }
    dataSource.videos.telemetry.updateOnScreen(visible)
  }

  /// The part of the window the feed shows, for choosing which video plays.
  private func updateVideoViewport(_ scrollView: UIScrollView) {
    guard scrollView.window != nil else { return }
    dataSource.videos.setViewport(scrollView.convert(scrollView.bounds, to: nil))
  }
}

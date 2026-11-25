// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Combine
import ExpressCore
import ObjectiveC
import Shared
import SwiftUI
import UIKit

extension BrowserViewController {
  /// Shows the comments pill on web pages, and hides it on the new tab page, while editing the
  /// address, in private tabs (so private pages are never sent for counts), in landscape and
  /// on iPad, like Android.
  func updateExpressCommentsPill(for url: URL?, isNewTabPage: Bool) {
    // Android refreshes the avatar on page loads, at most every 30 minutes.
    ExpressProfileModel.shared.refreshIfStale()
    // Android keeps the toolbars shown on any youtube.com page.
    toolbarVisibilityViewModel.isPinned =
      !isNewTabPage && (url?.host?.lowercased().contains("youtube.com") ?? false)
    let isPhonePortrait =
      traitCollection.horizontalSizeClass == .compact
      && traitCollection.verticalSizeClass == .regular
    guard let url, !isNewTabPage, !topToolbar.inOverlayMode,
      !privateBrowsingManager.isPrivateBrowsing, isPhonePortrait
    else {
      expressCommentsPillModel.hide()
      return
    }
    expressCommentsPillModel.show(for: url)
  }

  /// Home loads the new tab page in the current tab. Internal pages need a privileged request.
  func expressPillDidTapHome() {
    tabManager.selectedTab?.loadRequest(
      PrivilegedRequest(url: TabManager.ntpInteralURL) as URLRequest
    )
  }

  /// Opens the comments for the page in the current tab (haptic first, like Android).
  func expressPillDidTapComments() {
    guard let url = tabManager.selectedTab?.visibleURL else { return }
    UIImpactFeedbackGenerator(style: .light).impactOccurred()
    if tabManager.selectedTab?.isPrivate == false {
      var props = ExpressAnalytics.pageProperties(url)
      props["type"] = "page"
      ExpressAnalytics.shared.capture("Bottom Sheet Clicked", props)
    }
    presentExpressComments(for: .forPage(url))
  }

  /// Presents the comments sheet. YouTube watch pages get a 60% sheet so the video stays
  /// visible; everything else opens full height.
  func presentExpressComments(for target: ExpressCommentsTarget) {
    presentExpressComments(for: target, focusCommentID: nil)
  }

  func presentExpressComments(for target: ExpressCommentsTarget, focusCommentID: String?) {
    presentExpressCommentsSheet(for: target, focusCommentID: focusCommentID)
  }

  /// A feed video post: its comments, with the video full screen over them (Android's
  /// `PostVideoViewerFragment`). Closing the video, its rail or a swipe land on the comments.
  func presentExpressPostVideo(_ post: ExpressPost, videoURL: URL, startSeconds: Double?) {
    let item = ExpressPostVideoItem(post: post, videoURL: videoURL, startSeconds: startSeconds)
    presentExpressCommentsSheet(
      for: .post(id: post.id, title: post.caption),
      post: post,
      focusCommentID: nil,
      animated: false
    ) { commentsHost in
      final class ViewerBox { weak var controller: UIViewController? }
      let box = ViewerBox()
      let viewer = UIHostingController(
        rootView: ExpressPostVideoViewer(
          item: item,
          onClose: { box.controller?.dismiss(animated: true) },
          onShare: { url in
            guard let controller = box.controller else { return }
            let share = UIActivityViewController(activityItems: [url], applicationActivities: nil)
            share.popoverPresentationController?.sourceView = controller.view
            controller.present(share, animated: true)
          }
        )
      )
      viewer.modalPresentationStyle = .fullScreen
      viewer.overrideUserInterfaceStyle = .dark
      box.controller = viewer
      commentsHost.present(viewer, animated: true)
    }
  }

  /// `post` is the feed post the sheet was opened from, when the caller has it.
  private func presentExpressCommentsSheet(
    for target: ExpressCommentsTarget,
    post: ExpressPost? = nil,
    focusCommentID: String?,
    animated: Bool = true,
    completion: ((UIViewController) -> Void)? = nil
  ) {
    final class PresentedBox { weak var controller: UIViewController? }
    let box = PresentedBox()
    // Android's `Comments Opened` / `Comments Closed`.
    let commentsFor: String
    let postID: String?
    switch target {
    case .post(let id, _):
      commentsFor = "post"
      postID = id
    case .youtube:
      commentsFor = "youtube"
      postID = nil
    case .page:
      commentsFor = "page"
      postID = nil
    }
    let openedAt = Date()
    ExpressAnalytics.shared.capture(
      "Comments Opened",
      [
        "comments_for": commentsFor, "post_id": postID, "with_video": !animated,
        "from_notification": focusCommentID != nil,
      ]
    )
    let pageTitle: String? = {
      if case .post = target { return nil }
      return tabManager.selectedTab?.title
    }()
    // The chip at the top shows the post's author, age and media (Android's `SourceChip`):
    // from the card that opened it, else the feed's copy. A post that isn't in the feed (a
    // notification or a link) keeps the plain "Post" chip.
    let postSource: ExpressCommentsPostSource? = {
      guard case .post(let id, _) = target else { return nil }
      let feedPost = post ?? expressFeedDataSource.posts.first(where: { $0.id == id })
      return feedPost.map { ExpressCommentsPostSource(post: $0) }
    }()
    // Sends can land after the sheet has gone; the watcher keeps the pill right for those.
    let watcher = expressCommentsSendWatcher
    let host = ExpressCommentsHostingController(
      rootView: ExpressCommentsSheetView(
        target: target,
        pageTitle: pageTitle,
        postSource: postSource,
        focusCommentID: focusCommentID,
        onCountChange: { [weak self] count in
          if case .post = target { return }
          // Only while this sheet is up (it may outlive its dismissal briefly): a send landing
          // later is counted once, by the watcher.
          guard watcher.isOpen(box.controller) else { return }
          self?.expressCommentsPillModel.setCount(count)
        },
        onClose: { box.controller?.dismiss(animated: true) }
      )
      // Documents open in an in-app Safari view over the comments (Android's custom tab), so
      // closing it lands back on the same comment; the comments' videos hold meanwhile. Links
      // it can't show (not http or https) still open in a new tab once the sheet is away.
      .environment(\.expressOpenURL) { [weak self] url in
        guard let comments = box.controller else { return }
        if ExpressDocumentBrowser.present(url, over: comments) { return }
        comments.dismiss(animated: true) {
          self?.openURLInNewTab(url, isPrivate: false, isPrivileged: false)
        }
      }
      // Feed videos stay paused while the sheet covers the new tab page.
      .onDisappear { [weak self] in
        ExpressAnalytics.shared.capture(
          "Comments Closed",
          [
            "comments_for": commentsFor, "post_id": postID,
            "duration_ms": Int64(Date().timeIntervalSince(openedAt) * 1000),
          ]
        )
        self?.expressFeedDataSource.videos.isCovered = false
        self?.maybeShowExpressNewTabPrompts()
      }
    )
    // Closed by any route (×, a swipe down, a programmatic dismiss): the comments' voice note
    // stops and a recording is dropped, so neither carries on into the browser.
    host.onDismissed = { [weak host] in
      ExpressCommentsMediaFocus.shared.commentsClosed()
      if let host { watcher.sheetClosed(host) }
    }
    box.controller = host
    watcher.sheetOpened(host, listKey: Self.expressListKey(for: target))
    expressFeedDataSource.videos.isCovered = true
    if let sheet = host.sheetPresentationController {
      if target.prefersShortSheet {
        sheet.detents = [
          .custom(identifier: .init("express.comments.short")) { $0.maximumDetentValue * 0.6 },
          .large(),
        ]
      } else {
        sheet.detents = [.large()]
      }
      sheet.prefersGrabberVisible = true
      sheet.prefersScrollingExpandsWhenScrolledToEdge = true
      sheet.preferredCornerRadius = 16
    }
    present(host, animated: animated) { completion?(host) }
  }

  /// The list a sheet's top level shows, as the upload manager names it.
  private static func expressListKey(for target: ExpressCommentsTarget) -> String? {
    if case .post(let id, _) = target { return ExpressCommentParent.post(id: id).listKey }
    return target.pageKeyURL.map { ExpressCommentParent.page($0).listKey }
  }

  /// Follows the reader's comment sends past the sheet they were made in (one per browser).
  private var expressCommentsSendWatcher: ExpressCommentsSendWatcher {
    if let watcher = objc_getAssociatedObject(self, &expressCommentsSendWatcherKey)
      as? ExpressCommentsSendWatcher
    {
      return watcher
    }
    let watcher = ExpressCommentsSendWatcher(
      onPostedWhileClosed: { [weak self] listKey in
        self?.expressCommentPostedAfterClose(listKey: listKey)
      },
      onNoticeWhileClosed: { [weak self] notice in
        self?.showExpressCommentNotice(notice)
      }
    )
    objc_setAssociatedObject(
      self,
      &expressCommentsSendWatcherKey,
      watcher,
      .OBJC_ASSOCIATION_RETAIN_NONATOMIC
    )
    return watcher
  }

  /// A top-level comment posted after its sheet closed: one more on the pill, as Android's
  /// `incrementPageCommentCount` does whether or not the sheet is still up. Only while the pill
  /// is on that same page (not in a private tab); replies and feed-post comments never count.
  private func expressCommentPostedAfterClose(listKey: String) {
    guard let tab = tabManager.selectedTab, !tab.isPrivate, let url = tab.visibleURL,
      let pageURL = ExpressCommentsTarget.forPage(url).pageKeyURL,
      ExpressCommentParent.page(pageURL).listKey == listKey
    else { return }
    // YouTube watch pages keep YouTube's own count (`setCount` leaves those alone).
    expressCommentsPillModel.setCount(expressCommentsPillModel.targetCount + 1)
  }

  /// A send's notice ("Username … created") once the comments have closed: the comments' toast,
  /// just above the comments pill, for Android's 2 s (`Toast.LENGTH_SHORT`).
  private func showExpressCommentNotice(_ notice: ExpressCommentNotice) {
    guard notice.claim() else { return }
    let toast = UIHostingController(rootView: ExpressBrowserToast(message: notice.message))
    toast.view.backgroundColor = .clear
    toast.view.isUserInteractionEnabled = false
    addChild(toast)
    view.addSubview(toast.view)
    toast.didMove(toParent: self)
    toast.view.translatesAutoresizingMaskIntoConstraints = false
    NSLayoutConstraint.activate([
      toast.view.leadingAnchor.constraint(equalTo: view.leadingAnchor),
      toast.view.trailingAnchor.constraint(equalTo: view.trailingAnchor),
      toast.view.bottomAnchor.constraint(equalTo: expressCommentsPill.view.topAnchor, constant: -12),
      toast.view.heightAnchor.constraint(equalToConstant: 120),
    ])
    toast.view.alpha = 0
    UIAccessibility.post(notification: .announcement, argument: notice.message)
    UIView.animate(withDuration: 0.29) { toast.view.alpha = 1 }
    Task { @MainActor [weak toast] in
      try? await Task.sleep(nanoseconds: UInt64(ExpressToast.Length.short.seconds * 1_000_000_000))
      UIView.animate(withDuration: 0.36) {
        toast?.view.alpha = 0
      } completion: { _ in
        toast?.willMove(toParent: nil)
        toast?.view.removeFromSuperview()
        toast?.removeFromParent()
      }
    }
  }
}

private var expressCommentsSendWatcherKey: UInt8 = 0

/// The reader's comment sends outlive the sheet they were made in (a video can still be going up
/// after it closes). While a sheet is up, its own screens keep the pill in step (from the
/// server's total) and show the sends' notices; once it has closed, this hands on what lands:
/// a top-level comment for the pill, and notices for the browser to show.
@MainActor
private final class ExpressCommentsSendWatcher {
  private weak var openSheet: UIViewController?
  /// The list the open sheet's top level shows; its model counts that list's sends itself.
  private var openListKey: String?
  private var cancellables: Set<AnyCancellable> = []

  init(
    onPostedWhileClosed: @escaping @MainActor (String) -> Void,
    onNoticeWhileClosed: @escaping @MainActor (ExpressCommentNotice) -> Void
  ) {
    let uploads = ExpressCommentUploads.shared
    // Both are sent on the main actor.
    uploads.posted
      .sink { [weak self] key, _ in
        MainActor.assumeIsolated {
          guard let self, !(self.openSheet != nil && self.openListKey == key) else { return }
          onPostedWhileClosed(key)
        }
      }
      .store(in: &cancellables)
    uploads.notices
      .sink { [weak self] notice in
        MainActor.assumeIsolated {
          guard let self, self.openSheet == nil else { return }
          onNoticeWhileClosed(notice)
        }
      }
      .store(in: &cancellables)
  }

  func sheetOpened(_ sheet: UIViewController, listKey: String?) {
    openSheet = sheet
    openListKey = listKey
  }

  func sheetClosed(_ sheet: UIViewController) {
    guard openSheet === sheet else { return }
    openSheet = nil
    openListKey = nil
  }

  /// Whether `sheet` is the comments sheet on screen.
  func isOpen(_ sheet: UIViewController?) -> Bool {
    guard let sheet else { return false }
    return openSheet === sheet
  }
}

/// The comments' toast pill, bottom-aligned in a full-width box over the browser.
private struct ExpressBrowserToast: View {
  let message: String

  var body: some View {
    ExpressToastView(toast: ExpressToast(message: message))
      .padding(.horizontal, 24)
      .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .bottom)
  }
}

/// The comments sheet's controller. It tells when the sheet has gone for good, by whatever
/// route, as opposed to something being presented over it.
private final class ExpressCommentsHostingController<Content: View>: UIHostingController<Content> {
  var onDismissed: (() -> Void)?

  override func viewDidDisappear(_ animated: Bool) {
    super.viewDidDisappear(animated)
    guard isBeingDismissed || presentingViewController == nil, let onDismissed else { return }
    self.onDismissed = nil
    onDismissed()
  }
}

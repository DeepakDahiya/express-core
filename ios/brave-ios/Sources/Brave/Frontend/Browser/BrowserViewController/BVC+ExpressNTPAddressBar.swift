// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Shared
import UIKit

// Android's new tab page toolbar (`BraveNewTabPage.isLocationBarShownInNtp` and
// `BraveNewTabPageLayout.getToolbarTransitionPercentage`): on phones the header's search box
// stands in for the address field. The bar keeps its buttons, hides the field and takes the
// feed's colour with no divider; as the box scrolls up under the bar the field fades in over
// the last half of the box's height, and the bar's colour and divider come back once the box is
// all the way under. Editing shows the normal bar (the box fades out). Private tabs (Android's
// incognito page has no stand-in), the address bar at the bottom and iPad keep the normal bar.

/// The browser's state for the stand-in.
@MainActor
final class ExpressNTPAddressBarState {
  /// The feed's colour over the status bar while the header's box stands in.
  let statusBarBackdrop: UIView = {
    let view = UIView()
    view.backgroundColor = ExpressColors.feedBackground
    view.isUserInteractionEnabled = false
    view.alpha = 0
    view.autoresizingMask = [.flexibleWidth, .flexibleHeight]
    return view
  }()
  /// Set as the tab tray opens: the normal bar shows until the browser is back.
  var isSuspendedForTabTray = false
  /// Whether the address was being edited at the last update, so the end of editing fades.
  var wasEditing = false
}

extension BrowserViewController {
  /// The new tab page whose header box stands in for the address field now, if any.
  private var expressNTPStandInPage: NewTabPageViewController? {
    guard UIDevice.current.userInterfaceIdiom == .phone, !isUsingBottomBar,
      let tab = tabManager.selectedTab, !tab.isPrivate,
      let page = activeNewTabPageViewController, page.parent === self
    else { return nil }
    let tabPage: NewTabPageViewController? = tab.newTabPageViewController
    guard tabPage === page else { return nil }
    // An address, a search or a card's article loading from the new tab page shows the normal
    // bar at once, before the page goes (Android `setUrlFocusAnimationsDisabled`).
    if let url = topToolbar.currentURL, InternalURL(url)?.isAboutHomeURL != true {
      return nil
    }
    return page
  }

  /// Android `getToolbarTransitionPercentage`: 0 while the box rests below the bar, 1 once its
  /// top reaches the bar's bottom edge, over the last half of the box's height. A page that
  /// isn't laid out yet counts as resting.
  private func expressNTPSearchBoxTransition(on page: NewTabPageViewController) -> CGFloat {
    guard let box = page.expressSearchBoxFrame(in: view) else { return 0 }
    let transitionLength = box.height / 2
    guard transitionLength > 0 else { return 0 }
    let barBottom = header.frame.maxY
    return min(1, max(0, (barBottom + transitionLength - box.minY) / transitionLength))
  }

  /// Sets the address bar for the current page and where its header's box is. Cheap enough
  /// for every scroll step; call it whenever the page, the tab, editing or the layout changes.
  func updateExpressNTPAddressBar() {
    let state = expressNTPAddressBar
    if state.isSuspendedForTabTray {
      if presentedViewController != nil {
        applyExpressNTPAddressBar(transition: nil, page: nil, animated: false)
        return
      }
      // The tray went without the browser appearing again (a sheet).
      state.isSuspendedForTabTray = false
    }
    let page = expressNTPStandInPage
    let isEditing = topToolbar.inOverlayMode
    // Editing shows the normal bar at once, as the text field appears; when it ends the bar
    // fades back to the stand-in along with the field (TopToolbarView).
    let animated = page != nil && !isEditing && state.wasEditing
    state.wasEditing = isEditing
    applyExpressNTPAddressBar(
      transition: page.map { expressNTPSearchBoxTransition(on: $0) },
      page: page,
      animated: animated
    )
  }

  /// The tab tray is opening: the normal bar, until the browser appears again.
  func suspendExpressNTPAddressBarForTabTray() {
    expressNTPAddressBar.isSuspendedForTabTray = true
    applyExpressNTPAddressBar(transition: nil, page: nil, animated: false)
  }

  /// The browser is appearing again (the tab tray or a full-screen page went).
  func resumeExpressNTPAddressBar() {
    expressNTPAddressBar.isSuspendedForTabTray = false
    updateExpressNTPAddressBar()
  }

  /// `transition` is nil for the normal bar.
  private func applyExpressNTPAddressBar(
    transition: CGFloat?,
    page: NewTabPageViewController?,
    animated: Bool
  ) {
    let state = expressNTPAddressBar
    if state.statusBarBackdrop.superview !== statusBarOverlay {
      state.statusBarBackdrop.frame = statusBarOverlay.bounds
      statusBarOverlay.addSubview(state.statusBarBackdrop)
    }
    // How much of the normal bar shows (the toolbar works out the same for itself).
    let shown = topToolbar.inOverlayMode ? 1 : (transition ?? 1)
    let activePage = activeNewTabPageViewController
    let changes: () -> Void = { [self] in
      topToolbar.expressNTPTransition = transition
      state.statusBarBackdrop.alpha = 1 - shown
      // Android draws the bar's shadow only once the box is all the way under it.
      header.line.alpha = shown < 1 ? 0 : 1
      // The header's box gives way to the field, and fades out while editing.
      activePage?.setExpressSearchBoxOpacity(page == nil ? 1 : 1 - shown)
    }
    if animated {
      UIView.animate(
        withDuration: 0.2,
        delay: 0,
        options: [.beginFromCurrentState, .allowUserInteraction],
        animations: changes
      )
    } else {
      changes()
    }
  }

  func expressNewTabPageSearchBoxDidMove(_ newTabPage: NewTabPageViewController) {
    guard newTabPage === activeNewTabPageViewController else { return }
    updateExpressNTPAddressBar()
  }
}

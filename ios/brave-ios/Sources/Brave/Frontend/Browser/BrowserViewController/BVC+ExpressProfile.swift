// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Combine
import ExpressCore
import SwiftUI
import UIKit

extension BrowserViewController {
  /// Keeps the address bar's avatar in step with the account.
  func observeExpressAvatar() {
    let model = ExpressProfileModel.shared
    expressAvatarCancellable = model.$avatarURL
      .combineLatest(model.$isSignedIn)
      .receive(on: RunLoop.main)
      .sink { [weak self] url, isSignedIn in
        self?.topToolbar.expressAvatarButton.setAvatar(isSignedIn ? url : nil)
      }
    model.refreshIfStale()
    observeExpressSignInPrompts()
    ExpressPushManager.shared.openTarget = { [weak self] payload in
      self?.openExpressNotification(payload)
    }
    ExpressPushManager.shared.syncIfNeeded(reason: "app_start")
  }

  /// A like or reply notification: its post, or its page, with the comments open at that
  /// comment (Android waits up to 12 s for the page, then settles for the right site).
  func openExpressNotification(_ payload: ExpressPushPayload) {
    expressNewTabReturn.suppress()
    if presentedViewController != nil {
      dismiss(animated: false)
    }
    if payload.isPost, let postID = payload.postID {
      presentExpressComments(
        for: .post(id: postID, title: payload.postContent),
        focusCommentID: payload.targetCommentID
      )
      return
    }
    guard let url = payload.url else { return }
    switchToTabForURLOrOpen(url, isPrivate: false, isPrivileged: false)
    Task { @MainActor [weak self] in
      let deadline = Date().addingTimeInterval(12)
      while Date() < deadline {
        try? await Task.sleep(nanoseconds: 250_000_000)
        guard let self, let tab = self.tabManager.selectedTab else { return }
        let here = tab.visibleURL
        let sameHost = here?.host?.replacingOccurrences(of: "www.", with: "")
          == url.host?.replacingOccurrences(of: "www.", with: "")
        if sameHost, !tab.isLoading || Date() >= deadline {
          self.presentExpressComments(for: .forPage(here ?? url), focusCommentID: payload.targetCommentID)
          return
        }
      }
    }
  }

  /// Android: the photo opens the Profile; signed out, the glyph opens sign-in.
  func topToolbarDidTapExpressAvatarButton(_ topToolbar: TopToolbarView) {
    if ExpressProfileModel.shared.isSignedIn {
      presentExpressProfile()
    } else {
      presentExpressSignIn()
    }
  }

  func presentExpressProfile() {
    final class PresentedBox { weak var controller: UIViewController? }
    let box = PresentedBox()
    let host = UIHostingController(
      rootView: ExpressProfileView(
        model: .shared,
        onClose: { box.controller?.dismiss(animated: true) },
        onOpenSettings: { [weak self] in
          guard let self else { return }
          let settings = self.settingsController
          box.controller?.dismiss(animated: true) {
            self.presentSettingsNavigation(with: settings)
          }
        },
        onOpenURL: { [weak self] url in
          box.controller?.dismiss(animated: true) {
            self?.finishEditingAndSubmit(url)
          }
        },
        onOpenReferral: { [weak self] in
          self?.expressYouTube.presentAccessSheet(source: .profile, from: box.controller)
        }
      )
    )
    host.modalPresentationStyle = .fullScreen
    box.controller = host
    present(host, animated: true)
  }

  /// Phone sign-in. `required` is Android's `authRequired` (the app asked, not the reader): it
  /// can't be swiped away. Leaving without signing in starts a guest account if there's none.
  func presentExpressSignIn(required: Bool = false) {
    guard presentedViewController == nil else { return }
    final class PresentedBox { weak var controller: UIViewController? }
    let box = PresentedBox()
    let host = UIHostingController(
      rootView: ExpressSignInView(
        isRequired: required,
        onSkip: {
          if ExpressAccount.shared.validAccessToken == nil {
            Task { @MainActor in await ExpressAccount.shared.ensureGuestAccount(force: true) }
          }
          box.controller?.dismiss(animated: true)
        },
        onFinish: { box.controller?.dismiss(animated: true) }
      )
    )
    host.modalPresentationStyle = .fullScreen
    box.controller = host
    present(host, animated: true)
  }

  /// In India the new tab page asks for sign-in (once per launch), as on Android.
  func observeExpressSignInPrompts() {
    expressFeedDataSource.onSignInRequired = { [weak self] in
      self?.presentExpressSignIn(required: true)
    }
  }
}

extension BrowserViewController {
  /// A link that opened the app; referral links are attributed.
  public func handleExpressIncomingURL(_ url: URL) {
    expressNewTabReturn.suppress()
    expressYouTube.handleIncoming(url)
  }
}

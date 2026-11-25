// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveCore
import BraveShields
import BraveUI
import Shared
import SwiftUI
import UIKit
import Web

/// The URL bar's trust marker, its site sheet and the Privacy & Security screen (Android's
/// `TrustMarkers`, `SiteTrustSheet` and `PrivacySettingsFragment`).
extension BrowserViewController {
  func installExpressTrustMarker() {
    topToolbar.locationView.onTrustMarkerTap = { [weak self] in
      self?.presentExpressTrustSheet()
    }
    topToolbar.locationView.onPictureInPictureTap = { [weak self] in
      self?.expressYouTube.requestPictureInPicture()
    }
  }

  /// The selected tab's site: what was blocked, its trust status and its ad blocking.
  func presentExpressTrustSheet() {
    // The address the bar shows: error and reader pages unwrap to the site's own URL.
    guard presentedViewController == nil,
      let tab = tabManager.selectedTab,
      let url = tab.visibleURL?.displayURL ?? tab.visibleURL,
      TabLocationView.isWebPage(url)
    else { return }
    ExpressProtectedSites.record(tab: tab)

    final class Box { weak var controller: UIViewController? }
    let box = Box()
    let model = ExpressTrustSheetModel(
      tab: tab,
      url: url,
      status: topToolbar.locationView.trustMarker.status,
      onShieldsChanged: { [weak self] in self?.changedShieldSettings() }
    )
    let host = UIHostingController(
      rootView: ExpressTrustSheetView(
        model: model,
        onAdvanced: { [weak self] in
          box.controller?.dismiss(animated: true) {
            self?.presentExpressPrivacySettings()
          }
        }
      )
    )
    box.controller = host
    host.sizingOptions = .preferredContentSize
    host.view.backgroundColor = ExpressTrustColors.sheet
    if let sheet = host.sheetPresentationController {
      // The sheet takes the height of what it shows.
      sheet.detents = [
        .custom(identifier: .init("express.trust.sheet")) { [weak host] context in
          let width =
            context.containerTraitCollection.horizontalSizeClass == .regular
            ? 500 : UIScreen.main.bounds.width
          let fitted =
            host?.view.systemLayoutSizeFitting(
              CGSize(width: width, height: 0),
              withHorizontalFittingPriority: .required,
              verticalFittingPriority: .fittingSizeLevel
            ).height ?? 320
          return min(max(fitted, 280), context.maximumDetentValue)
        }
      ]
      sheet.preferredCornerRadius = 24
      sheet.prefersGrabberVisible = false
    }
    present(host, animated: true)
  }

  /// Privacy & Security, in its own navigation stack (from the trust sheet).
  func presentExpressPrivacySettings() {
    let controller = ExpressPrivacySettingsScreen.make(
      settings: makeExpressShieldsSettings(),
      profile: profile,
      privateBrowsingManager: privateBrowsingManager,
      openURL: { [weak self] url in self?.openDestinationURL(url) }
    )
    let container = SettingsNavigationController(rootViewController: controller)
    container.modalPresentationStyle =
      UIDevice.current.userInterfaceIdiom == .phone ? .pageSheet : .formSheet
    controller.navigationItem.rightBarButtonItem = .init(
      barButtonSystemItem: .done,
      target: container,
      action: #selector(SettingsNavigationController.done)
    )
    present(container, animated: true)
  }

  /// Brave's Shields settings model, as `showGlobalShieldsSettings` builds it.
  func makeExpressShieldsSettings(
    showsProgress: Bool = true
  ) -> AdvancedShieldsSettings {
    weak var spinner: SpinnerView?
    return AdvancedShieldsSettings(
      profile: profile,
      tabManager: tabManager,
      feedDataSource: feedDataSource,
      debounceService: DebounceServiceFactory.get(privateMode: false),
      braveShieldsSettings: BraveShieldsSettingsFactory.create(for: profileController.profile),
      braveCore: profileController,
      p3aUtils: braveCore.p3aUtils,
      rewards: rewards,
      webcompatReporterHandler: WebcompatReporter.ServiceFactory.get(privateMode: false),
      clearDataCallback: { [weak self] isLoading, _ in
        guard showsProgress, let self else { return }
        let presented = self.presentedViewController ?? self
        if isLoading, spinner == nil {
          let newSpinner = SpinnerView()
          newSpinner.present(on: presented.view)
          spinner = newSpinner
        } else if !isLoading {
          spinner?.dismiss()
          spinner = nil
        }
      }
    )
  }

  /// "Clear data when I close Express": like Android, it runs as the browser starts. History,
  /// cache, cookies and site data, and recent searches go; passwords and downloads stay.
  func runExpressClearOnLaunchIfNeeded() {
    guard ExpressClearOnExit.isEnabled.value else { return }
    let settings = makeExpressShieldsSettings(showsProgress: false)
    let kinds: Set<AdvancedShieldsSettings.ClearableSetting.ClearableType> = [
      .history, .cache, .cookiesAndCache, .recentSearches,
    ]
    let clearables = settings.clearableSettings.filter { kinds.contains($0.id) }.map(\.clearable)
    Task { await settings.clearPrivateData(clearables) }
  }
}

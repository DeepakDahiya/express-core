// Copyright (c) 2024 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveStrings
import BraveWidgetsModels
import DesignSystem
import Foundation
import Strings
import UIKit

extension WidgetShortcut {
  // Express Browser hides Playlist, Wallet, News and Leo, so they aren't shortcuts either.
  static let eligibleButtonShortcuts: [WidgetShortcut] = [
    .bookmarks,
    .history,
    .downloads,
  ]

  var displayString: String {
    switch self {
    case .unknown:
      return ""
    case .bookmarks:
      return Strings.bookmarksMenuItem
    case .history:
      return Strings.historyMenuItem
    case .downloads:
      return Strings.downloadsMenuItem
    default:
      return ""
    }
  }

  var braveSystemImageName: String? {
    switch self {
    case .unknown:
      return nil
    case .newTab:
      return "leo.plus.add"
    case .newPrivateTab:
      return "leo.product.private-window"
    case .bookmarks:
      return "leo.product.bookmarks"
    case .history:
      return "leo.history"
    case .downloads:
      return "leo.download"
    case .search:
      return "leo.search"
    case .scanQRCode:
      return "leo.qr.code"
    @unknown default:
      return nil
    }
  }

  var image: UIImage? {
    return braveSystemImageName.flatMap { UIImage(braveSystemNamed: $0) }
  }
}

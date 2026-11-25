// Copyright 2021 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveShared
import Foundation
import Shared
import SwiftUI
import UIKit

extension TabTrayController {
  /// Shown in the tab tray in private mode before any private tab is open. Express Browser shows
  /// its Android app's private new tab page text here (Android has no empty private tab
  /// switcher: it opens a private tab on that page instead).
  class TabTrayPrivateModeInfoView: UIView {
    let scrollView = UIScrollView().then {
      $0.alwaysBounceVertical = true
      $0.indicatorStyle = .white
    }

    private var layout = ExpressPrivateNTPContent.Layout(size: .zero)

    private lazy var contentController = UIHostingController(
      rootView: ExpressPrivateNTPContent(layout: layout)
    ).then {
      $0.sizingOptions = .intrinsicContentSize
      $0.view.backgroundColor = .clear
    }

    override init(frame: CGRect) {
      super.init(frame: frame)

      addSubview(scrollView)
      let contentView = contentController.view!
      scrollView.addSubview(contentView)

      scrollView.snp.makeConstraints {
        $0.edges.equalToSuperview()
      }
      contentView.snp.makeConstraints {
        $0.edges.equalTo(scrollView.contentLayoutGuide)
        $0.width.equalTo(scrollView.frameLayoutGuide)
      }
    }

    override func layoutSubviews() {
      super.layoutSubviews()
      let newLayout = ExpressPrivateNTPContent.Layout(size: bounds.size)
      if newLayout != layout {
        layout = newLayout
        contentController.rootView = ExpressPrivateNTPContent(layout: newLayout)
      }
    }

    required init?(coder aDecoder: NSCoder) {
      fatalError("init(coder:) has not been implemented")
    }
  }
}

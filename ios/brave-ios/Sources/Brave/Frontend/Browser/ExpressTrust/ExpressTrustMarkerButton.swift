// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// The URL bar's trust marker: the animated 24 pt shield, tappable for the site trust sheet.
final class ExpressTrustMarkerButton: UIButton {
  private let host: UIHostingController<ExpressTrustMarkerView>

  init(model: ExpressTrustMarkerModel) {
    host = UIHostingController(rootView: ExpressTrustMarkerView(model: model))
    super.init(frame: .zero)
    host.view.backgroundColor = .clear
    host.view.isUserInteractionEnabled = false
    host.view.translatesAutoresizingMaskIntoConstraints = false
    addSubview(host.view)
    NSLayoutConstraint.activate([
      host.view.widthAnchor.constraint(equalToConstant: 24),
      host.view.heightAnchor.constraint(equalToConstant: 24),
      host.view.centerYAnchor.constraint(equalTo: centerYAnchor),
      // A little room after it, like the lock icon it replaces.
      host.view.leadingAnchor.constraint(equalTo: leadingAnchor),
    ])
    isAccessibilityElement = true
    accessibilityTraits = .button
    accessibilityHint = "Shows what was blocked on this site"
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError()
  }

  override var intrinsicContentSize: CGSize {
    CGSize(width: 32, height: 24)
  }

  /// A comfortable target around the small shield.
  override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
    bounds.insetBy(dx: -6, dy: -10).contains(point)
  }
}

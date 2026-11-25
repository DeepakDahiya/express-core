// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SDWebImage
import UIKit

/// The avatar at the leading edge of the address bar, after Android's `profile_button`: the
/// reader's round photo when signed in (opens the Profile), a person glyph when not.
final class ExpressAvatarToolbarButton: UIButton {
  private static let side: CGFloat = 28
  private let avatarView = UIImageView()
  private var currentURL: URL?

  private static let placeholder: UIImage = {
    UIGraphicsImageRenderer(size: CGSize(width: side, height: side)).image { context in
      UIColor(red: 0xCE / 255, green: 0xC6 / 255, blue: 1, alpha: 1).setFill()
      context.cgContext.fillEllipse(in: CGRect(x: 0, y: 0, width: side, height: side))
    }
  }()

  override init(frame: CGRect) {
    super.init(frame: frame)
    avatarView.contentMode = .scaleAspectFill
    avatarView.clipsToBounds = true
    avatarView.layer.cornerRadius = Self.side / 2
    avatarView.isUserInteractionEnabled = false
    avatarView.translatesAutoresizingMaskIntoConstraints = false
    addSubview(avatarView)
    NSLayoutConstraint.activate([
      avatarView.centerXAnchor.constraint(equalTo: centerXAnchor),
      avatarView.centerYAnchor.constraint(equalTo: centerYAnchor),
      avatarView.widthAnchor.constraint(equalToConstant: Self.side),
      avatarView.heightAnchor.constraint(equalToConstant: Self.side),
      widthAnchor.constraint(equalToConstant: 40),
      heightAnchor.constraint(equalToConstant: 40),
    ])
    setContentHuggingPriority(.required, for: .horizontal)
    setContentCompressionResistancePriority(.required, for: .horizontal)
    accessibilityIdentifier = "urlBar-expressAvatarButton"
    setAvatar(nil)
  }

  @available(*, unavailable)
  required init?(coder: NSCoder) {
    fatalError()
  }

  /// The reader's photo, or nil for the signed-out glyph.
  func setAvatar(_ url: URL?) {
    if let url {
      accessibilityLabel = "Profile"
      guard url != currentURL else { return }
      currentURL = url
      avatarView.tintColor = nil
      avatarView.contentMode = .scaleAspectFill
      avatarView.sd_setImage(with: url, placeholderImage: Self.placeholder)
    } else {
      accessibilityLabel = "Sign in"
      currentURL = nil
      avatarView.sd_cancelCurrentImageLoad()
      avatarView.contentMode = .scaleAspectFit
      avatarView.tintColor = .label
      avatarView.image = UIImage(
        systemName: "person.crop.circle",
        withConfiguration: UIImage.SymbolConfiguration(pointSize: 24, weight: .regular)
      )
    }
  }

  override var isHighlighted: Bool {
    didSet { alpha = isHighlighted ? 0.6 : 1 }
  }
}

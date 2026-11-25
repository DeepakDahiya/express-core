// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveStrings
import BraveUI
import Strings
import SwiftUI
import UIKit

/// The private new tab page from Express Browser's Android app. Android shows Chromium's
/// incognito page there (`IncognitoNewTabPageView` with `incognito_description_layout.xml`,
/// laid out by `IncognitoDescriptionView`): the incognito icon, "You’ve gone Private", a
/// subtitle, two bulleted lists and "Learn more". Android's third-party cookie card under it
/// isn't shown here.
struct ExpressPrivateNTPContent: View {
  /// What Android's "Learn more" opens: Brave's `BraveHelpAndFeedbackLauncherImpl` sends every
  /// help link except Safe Browsing to its fallback support page.
  static let learnMoreURL = URL(string: "https://community.brave.app/")!

  /// `IncognitoDescriptionView.adjustView()`: the layout depends on the space available.
  struct Layout: Equatable {
    /// Wider than 720 pt: a centred 600 pt column, the two lists side by side and "Learn more"
    /// at the end of the subtitle.
    var isWide: Bool
    var iconSize: CGFloat
    var horizontalPadding: CGFloat
    var verticalPadding: CGFloat

    init(size: CGSize) {
      isWide = size.width > 720
      if isWide {
        iconSize = size.height <= 480 ? 72 : 120
        horizontalPadding = 0
        verticalPadding = size.height <= 320 ? 16 : 72
      } else {
        iconSize = size.width <= 240 || size.height <= 480 ? 48 : 72
        horizontalPadding = size.width <= 240 ? 24 : 32
        verticalPadding = 32
      }
    }
  }

  var layout: Layout
  /// Opens `learnMoreURL`; nil leaves "Learn more" out.
  var onLearnMore: (() -> Void)?

  /// `TextAppearance.Headline` (22sp) and `TextAppearance.TextMedium` (14sp).
  @ScaledMetric(relativeTo: .title2) private var titleSize = 22
  @ScaledMetric(relativeTo: .subheadline) private var bodySize = 14
  /// `incognito_ntp_total_space_between_views`.
  private let paragraphSpacing: CGFloat = 14
  /// `CONTENT_WIDTH_DP`.
  private static let contentWidth: CGFloat = 600
  /// `BULLETPOINTS_HORIZONTAL_SPACING_DP`.
  private static let listSpacing: CGFloat = 40

  private static let primary = Color(uiColor: ExpressIncognitoColors.textPrimary)
  private static let secondary = Color(uiColor: ExpressIncognitoColors.textSecondary)
  private static let emphasis = Color(uiColor: ExpressIncognitoColors.emphasis)
  private static let link = Color(uiColor: ExpressIncognitoColors.link)

  var body: some View {
    VStack(alignment: layout.isWide ? .center : .leading, spacing: paragraphSpacing) {
      Image("express-incognito-splash", bundle: .module)
        .renderingMode(.template)
        .resizable()
        .foregroundStyle(Color(uiColor: ExpressIncognitoColors.pageIcon))
        .frame(width: layout.iconSize, height: layout.iconSize)
        .accessibilityHidden(true)
      Text(Strings.ExpressPrivateNTP.title)
        .font(.system(size: titleSize))
        .lineSpacing(4)
        .foregroundStyle(Self.primary)
        .multilineTextAlignment(layout.isWide ? .center : .leading)
        .fixedSize(horizontal: false, vertical: true)
        .accessibilityAddTraits(.isHeader)
      Group {
        subtitle
        lists
        if !layout.isWide, let onLearnMore {
          Button(action: onLearnMore) {
            Text(Strings.ExpressPrivateNTP.learnMore)
              .font(.system(size: bodySize))
              .foregroundStyle(Self.link)
              .frame(minHeight: 44)
              .contentShape(.rect)
          }
          .buttonStyle(.plain)
          // The 44 pt touch target shouldn't add to the gap above it (Android's
          // `learnMoreSpacingTop`).
          .padding(.vertical, -15)
        }
      }
      .frame(maxWidth: layout.isWide ? Self.contentWidth : .infinity, alignment: .leading)
    }
    .padding(.horizontal, layout.horizontalPadding)
    .padding(.vertical, layout.verticalPadding)
    .frame(maxWidth: .infinity, alignment: layout.isWide ? .top : .topLeading)
    .environment(
      \.openURL,
      OpenURLAction { _ in
        onLearnMore?()
        return .handled
      }
    )
  }

  /// The subtitle; a wide layout ends it with the "Learn more" link.
  private var subtitle: some View {
    var text = AttributedString(Strings.ExpressPrivateNTP.subtitle)
    text.foregroundColor = Self.secondary
    if layout.isWide, onLearnMore != nil {
      var link = AttributedString(Strings.ExpressPrivateNTP.learnMore)
      link.foregroundColor = Self.link
      link.link = Self.learnMoreURL
      text += AttributedString(" ") + link
    }
    return Text(text)
      .font(.system(size: bodySize))
      .lineSpacing(3)
      .tint(Self.link)
      .fixedSize(horizontal: false, vertical: true)
  }

  @ViewBuilder private var lists: some View {
    let notSaved = list(
      header: Strings.ExpressPrivateNTP.notSavedHeader,
      items: [
        Strings.ExpressPrivateNTP.notSavedHistory,
        Strings.ExpressPrivateNTP.notSavedCookies,
        Strings.ExpressPrivateNTP.notSavedForms,
      ]
    )
    let visible = list(
      header: Strings.ExpressPrivateNTP.visibleHeader,
      items: [
        Strings.ExpressPrivateNTP.visibleWebsites,
        Strings.ExpressPrivateNTP.visibleEmployer,
        Strings.ExpressPrivateNTP.visibleProvider,
      ]
    )
    if layout.isWide {
      HStack(alignment: .top, spacing: Self.listSpacing) {
        notSaved
        visible
      }
    } else {
      VStack(alignment: .leading, spacing: paragraphSpacing) {
        notSaved
        visible
      }
    }
  }

  /// A header with its highlighted words, then three bullet points (`ChromeBulletSpan`: a dot
  /// and a 12 pt gap).
  private func list(header: String, items: [String]) -> some View {
    VStack(alignment: .leading, spacing: 3) {
      Text(Self.emphasized(header))
      ForEach(items, id: \.self) { item in
        HStack(alignment: .firstTextBaseline, spacing: 12) {
          Text(verbatim: "•")
            .accessibilityHidden(true)
          Text(item)
        }
      }
    }
    .font(.system(size: bodySize))
    .lineSpacing(3)
    .foregroundStyle(Self.secondary)
    .fixedSize(horizontal: false, vertical: true)
    .accessibilityElement(children: .combine)
  }

  /// Colours the text between `<em>` and `</em>` like Android's `incognito_emphasis`.
  static func emphasized(_ text: String) -> AttributedString {
    var result = AttributedString()
    var rest = Substring(text)
    func append(_ part: Substring, _ color: Color) {
      var piece = AttributedString(String(part))
      piece.foregroundColor = color
      result += piece
    }
    while let open = rest.range(of: "<em>") {
      append(rest[..<open.lowerBound], secondary)
      rest = rest[open.upperBound...]
      if let close = rest.range(of: "</em>") {
        append(rest[..<close.lowerBound], emphasis)
        rest = rest[close.upperBound...]
      } else {
        append(rest, emphasis)
        rest = ""
      }
    }
    append(rest, secondary)
    return result
  }
}

/// The private new tab page's only section.
@MainActor
final class ExpressPrivateNTPSectionProvider: NSObject, NTPSectionProvider {
  private let openURL: (URL) -> Void

  init(openURL: @escaping (URL) -> Void) {
    self.openURL = openURL
    super.init()
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
    let layout = ExpressPrivateNTPContent.Layout(size: collectionView.bounds.size)
    let openURL = self.openURL
    cell.contentConfiguration = UIHostingConfiguration {
      ExpressPrivateNTPContent(
        layout: layout,
        onLearnMore: { openURL(ExpressPrivateNTPContent.learnMoreURL) }
      )
    }
    .margins(.all, 0)
    return cell
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    sizeForItemAt indexPath: IndexPath
  ) -> CGSize {
    // An estimate: the cell sizes itself to the page (`ExpressHostingCell`).
    var size = fittingSizeForCollectionView(collectionView, section: indexPath.section)
    size.height = 600
    return size
  }

  func collectionView(
    _ collectionView: UICollectionView,
    layout collectionViewLayout: UICollectionViewLayout,
    insetForSectionAt section: Int
  ) -> UIEdgeInsets {
    .zero
  }
}

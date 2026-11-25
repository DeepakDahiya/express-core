// Copyright 2023 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveUI
import DesignSystem
import Foundation
import UIKit

/// A set of browser theme colors found in the `🦁 Browser` Figma doc. There will be a separate set depending
/// on the browsing mode you're in (standard vs private)
protocol BrowserColors {
  // MARK: - Text
  var textPrimary: UIColor { get }
  var textSecondary: UIColor { get }
  var textTertiary: UIColor { get }
  var textDisabled: UIColor { get }
  var textInteractive: UIColor { get }

  // MARK: - Icon
  var iconDefault: UIColor { get }
  var iconDisabled: UIColor { get }
  var iconActive: UIColor { get }

  // MARK: - BrowserButton
  var browserButtonBackgroundHover: UIColor { get }
  var browserButtonBackgroundActive: UIColor { get }

  // MARK: - TabSwitcher
  var tabSwitcherForeground: UIColor { get }
  var tabSwitcherSelectedCellBorder: UIColor { get }
  var tabSwitcherCellBackground: UIColor { get }
  var tabSwitcherBackground: UIColor { get }

  // MARK: - Container
  var containerBackground: UIColor { get }
  var containerInteractive: UIColor { get }
  var containerScrim: UIColor { get }
  var containerFrostedGlass: UIColor { get }

  // MARK: - Chrome
  var chromeBackground: UIColor { get }

  // MARK: - Divider
  var dividerSubtle: UIColor { get }
  var dividerStrong: UIColor { get }

  // MARK: - TabBar
  var tabBarTabBackground: UIColor { get }
  var tabBarTabActiveBackground: UIColor { get }

  // MARK: - Favorites/Search View Controller
  var favoritesAndSearchScreenBackground: UIColor { get }
  var favoritesAndSearchScreenSectionBackground: UIColor { get }
  var favoritesAndSearchScreenSectionGroupBackground: UIColor { get }
}

extension BrowserColors where Self == StandardBrowserColors {
  /// The standard set of light & dark mode browser colors
  static var standard: StandardBrowserColors { .init() }
}

extension BrowserColors where Self == PrivateModeBrowserColors {
  /// The set of browser colors specific to private mode
  static var privateMode: PrivateModeBrowserColors { .init() }
}

/// The standard set of light & dark mode browser colors
struct StandardBrowserColors: BrowserColors {
  var textPrimary: UIColor {
    .init(light: .primitiveNeutral5, dark: .primitiveNeutral98)
  }

  var textSecondary: UIColor {
    .init(light: .primitiveNeutral25, dark: .primitiveNeutral80)
  }

  var textTertiary: UIColor {
    .init(light: .primitiveNeutral50, dark: .primitiveNeutral70)
  }

  var textDisabled: UIColor {
    .init(lightRGBA: 0x2124_2A80, darkRGBA: 0xEDEE_F180)
  }

  var textInteractive: UIColor {
    .init(light: .primitivePrimary40, dark: .primitivePrimary70)
  }

  var iconDefault: UIColor {
    .init(light: .primitiveNeutral50, dark: .primitiveNeutral70)
  }

  var iconDisabled: UIColor {
    .init(lightRGBA: 0x6874_8580, darkRGBA: 0xA1AB_BA80)
  }

  var iconActive: UIColor {
    .init(light: .primitivePrimary40, dark: .primitivePrimary70)
  }

  var browserButtonBackgroundHover: UIColor {
    .init(light: .primitiveNeutral95, dark: .primitiveNeutral20)
  }

  var browserButtonBackgroundActive: UIColor {
    .init(light: .primitiveNeutral90, dark: .primitiveNeutral5)
  }

  var tabSwitcherForeground: UIColor {
    .init(braveSystemName: .containerDisabled)
  }

  var tabSwitcherSelectedCellBorder: UIColor {
    .init(braveSystemName: .buttonBackground)
  }

  var tabSwitcherCellBackground: UIColor {
    .init(braveSystemName: .containerBackground)
  }

  var tabSwitcherBackground: UIColor {
    .init(braveSystemName: .iosBrowserBackgroundIos)
  }

  var containerBackground: UIColor {
    .init(lightColor: .white, darkColor: .init(braveSystemName: .primitiveNeutral10))
  }

  var containerInteractive: UIColor {
    .init(light: .primitivePrimary95, dark: .primitivePrimary20)
  }

  var containerScrim: UIColor {
    .init(lightRGBA: 0x0D0F_1459, darkRGBA: 0x0D0F_14B3)
  }

  var containerFrostedGlass: UIColor {
    .init(lightRGBA: 0xFFFF_FFF7, darkRGBA: 0x2124_2AEB)
  }

  var chromeBackground: UIColor {
    .init(light: .primitiveNeutral98, dark: .primitiveNeutral5)
  }

  var dividerSubtle: UIColor {
    .init(lightRGBA: 0xA1AB_BA66, darkRGBA: 0x6874_8566)
  }

  var dividerStrong: UIColor {
    .init(lightRGBA: 0x6874_8566, darkRGBA: 0xA1AB_BA66)
  }

  var tabBarTabBackground: UIColor {
    .init(light: .primitiveNeutral95, dark: .primitiveNeutral5)
  }

  var tabBarTabActiveBackground: UIColor {
    .init(light: .primitiveNeutral98, dark: .primitiveNeutral10)
  }

  var favoritesAndSearchScreenBackground: UIColor {
    .init(light: .primitiveNeutral95, dark: .primitiveNeutral0)
  }

  var favoritesAndSearchScreenSectionBackground: UIColor {
    .init(braveSystemName: .materialThin)
  }

  var favoritesAndSearchScreenSectionGroupBackground: UIColor {
    self.containerBackground
  }
}

/// The Express Browser Android app's private-tab colours.
///
/// Express Android removed Brave's purple private theme, so its private tabs use Chromium's
/// incognito palette (`ui/android/java/res/values/color_palette.xml` and
/// `semantic_colors_non_adaptive.xml`), which is dark in both light and dark mode. Values are
/// resolved the way the Android app resolves them.
enum ExpressIncognitoColors {
  /// `toolbar_background_incognito`: `gm3_baseline_surface_dark`.
  static let toolbar = UIColor(rgb: 0x131314)
  /// Chromium's `toolbar_text_box_background_incognito`:
  /// `gm3_baseline_surface_container_highest_dark`. (Express Android still overrides this one
  /// with Brave's `primitive_private_window_10`, #22005D, in `brave_colors.xml`.)
  static let textBox = UIColor(rgb: 0x333537)
  /// `ntp_bg_incognito` and the private tab switcher (`default_bg_color_dark`): Chromium's
  /// #131314, and Express's `values-night` #202124 in dark mode.
  static let pageBackground = UIColor {
    $0.userInterfaceStyle == .dark ? UIColor(rgb: 0x202124) : UIColor(rgb: 0x131314)
  }
  /// `default_text_color_light` (`baseline_neutral_90`), also `default_icon_color_light`.
  static let textPrimary = UIColor(rgb: 0xE3E3E3)
  /// `default_text_color_secondary_light` (`baseline_neutral_variant_80`), also the address
  /// bar's hint text.
  static let textSecondary = UIColor(rgb: 0xC4C7C5)
  /// `default_text_color_disabled_light` / `default_icon_color_disabled_light`
  /// (`baseline_neutral_90` at 38%).
  static let textDisabled = UIColor(rgba: 0xE3E3_E361)
  /// `incognito_emphasis`: the highlighted words on the private new tab page.
  static let emphasis = UIColor.white
  /// `ntp_incognito_icon_color` (`baseline_neutral_95`), the tint of the page's icon.
  static let pageIcon = UIColor(rgb: 0xF2F2F2)
  /// `default_text_color_link_light` (`baseline_primary_80`), which Brave's Android colours
  /// set to #FF7654, and #FA7250 in dark mode.
  static let link = UIColor {
    $0.userInterfaceStyle == .dark ? UIColor(rgb: 0xFA7250) : UIColor(rgb: 0xFF7654)
  }
  /// Tab switcher cards (`gm3_baseline_surface_container_highest_dark`).
  static let card = UIColor(rgb: 0x333537)
  /// The tab switcher's mode toggle (`pane_switcher_background_incognito`).
  static let toggleBackground = UIColor(rgb: 0x1E1F20)
  /// The selected item of that toggle (`pane_switcher_selected_tab_incognito`).
  static let toggleSelected = UIColor(rgb: 0x37393B)
  /// `divider_line_bg_color_light` (`baseline_neutral_variant_30`).
  static let divider = UIColor(rgb: 0x444746)
  /// The address bar's suggestions: `omnibox_dropdown_bg_incognito`.
  static let suggestionsBackground = UIColor(rgb: 0x282A2C)
  /// `omnibox_scrolled_bg_incognito` (`gm3_baseline_surface_container_dark`).
  static let suggestionsSection = UIColor(rgb: 0x1E1F20)
  /// A suggestion row: `omnibox_suggestion_bg_incognito`.
  static let suggestion = UIColor(rgb: 0x1B1B1B)
  /// `default_scrim_color` (black at 65%).
  static let scrim = UIColor(rgba: 0x0000_00A6)
}

/// The set of browser colors specific to private mode: Express Browser uses its Android app's
/// grey (Chromium incognito) colours instead of Brave's purple private theme.
struct PrivateModeBrowserColors: BrowserColors {
  var textPrimary: UIColor {
    ExpressIncognitoColors.textPrimary
  }

  var textSecondary: UIColor {
    ExpressIncognitoColors.textSecondary
  }

  var textTertiary: UIColor {
    // Android's address bar hint uses the secondary text colour.
    ExpressIncognitoColors.textSecondary
  }

  var textDisabled: UIColor {
    ExpressIncognitoColors.textDisabled
  }

  var textInteractive: UIColor {
    .init(braveSystemName: .primitivePrimary70)
  }

  var iconDefault: UIColor {
    ExpressIncognitoColors.textPrimary
  }

  var iconDisabled: UIColor {
    ExpressIncognitoColors.textDisabled
  }

  var iconActive: UIColor {
    .init(braveSystemName: .primitivePrimary70)
  }

  var browserButtonBackgroundHover: UIColor {
    ExpressIncognitoColors.toggleSelected
  }

  var browserButtonBackgroundActive: UIColor {
    ExpressIncognitoColors.toggleBackground
  }

  var tabSwitcherForeground: UIColor {
    ExpressIncognitoColors.card
  }

  var tabSwitcherCellBackground: UIColor {
    ExpressIncognitoColors.card
  }

  var tabSwitcherSelectedCellBorder: UIColor {
    .init(braveSystemName: .buttonBackground)
  }

  var tabSwitcherBackground: UIColor {
    ExpressIncognitoColors.pageBackground
  }

  var containerBackground: UIColor {
    ExpressIncognitoColors.textBox
  }

  var containerInteractive: UIColor {
    .init(braveSystemName: .primitivePrimary10)
  }

  var containerScrim: UIColor {
    ExpressIncognitoColors.scrim
  }

  var containerFrostedGlass: UIColor {
    ExpressIncognitoColors.toolbar.withAlphaComponent(0.92)
  }

  var chromeBackground: UIColor {
    ExpressIncognitoColors.toolbar
  }

  var dividerSubtle: UIColor {
    ExpressIncognitoColors.divider
  }

  var dividerStrong: UIColor {
    // `baseline_neutral_variant_50`
    UIColor(rgb: 0x747775)
  }

  var tabBarTabBackground: UIColor {
    ExpressIncognitoColors.toolbar
  }

  var tabBarTabActiveBackground: UIColor {
    // `gm3_baseline_surface_container_high_dark`
    UIColor(rgb: 0x282A2C)
  }

  var favoritesAndSearchScreenBackground: UIColor {
    ExpressIncognitoColors.suggestionsBackground
  }

  var favoritesAndSearchScreenSectionBackground: UIColor {
    ExpressIncognitoColors.suggestionsSection
  }

  var favoritesAndSearchScreenSectionGroupBackground: UIColor {
    ExpressIncognitoColors.suggestion
  }
}

extension UIColor {
  fileprivate convenience init(light: FigmaColorResource, dark: FigmaColorResource) {
    self.init(dynamicProvider: { traitCollection in
      if traitCollection.userInterfaceStyle == .dark {
        return .init(braveSystemName: dark)
      }
      return .init(braveSystemName: light)
    })
  }

  fileprivate convenience init(lightColor: UIColor, darkColor: UIColor) {
    self.init(dynamicProvider: { traitCollection in
      if traitCollection.userInterfaceStyle == .dark {
        return darkColor
      }
      return lightColor
    })
  }

  fileprivate convenience init(lightRGBA: UInt32, darkRGBA: UInt32) {
    self.init(dynamicProvider: { traitCollection in
      if traitCollection.userInterfaceStyle == .dark {
        return .init(rgba: darkRGBA)
      }
      return .init(rgba: lightRGBA)
    })
  }
}

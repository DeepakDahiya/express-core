// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

import BraveCore
import BraveShared
import BraveUI
import Foundation
import Preferences
import Shared
import Static
import Web

class NTPTableViewController: TableViewController {
  // Express Browser: sponsored images are off and its new tab page is its own feed, so the
  // sponsored-images options and their "supports Brave's mission" footer are removed.
  enum BackgroundType: RepresentableOptionType {

    case defaultImages
    case superReferrer(String)

    var key: String {
      displayString
    }

    public var displayString: String {
      switch self {
      case .defaultImages: return "\(Strings.NTP.settingsDefaultImagesOnly)"
      case .superReferrer(let referrer): return referrer
      }
    }
  }

  private let rewards: BraveRewards?
  var linkTapped: ((URLRequest) -> Void)?

  init(rewards: BraveRewards?, linkTapped: ((URLRequest) -> Void)?) {
    self.rewards = rewards
    self.linkTapped = linkTapped

    super.init(style: .insetGrouped)
  }

  @available(*, unavailable)
  required init?(coder aDecoder: NSCoder) {
    fatalError()
  }

  override func viewDidLoad() {
    super.viewDidLoad()

    // Hides unnecessary empty rows
    tableView.tableFooterView = UIView()

    navigationItem.title = Strings.NTP.settingsTitle
    tableView.accessibilityIdentifier = "NewTabPageSettings.tableView"
    loadSections()

    Preferences.NewTabPage.backgroundImages.observe(from: self)
  }

  private func loadSections() {
    var imageSection = Section(
      header: .title(Strings.NTP.settingsBackgroundImages.uppercased()),
      rows: [
        .boolRow(
          title: Strings.NTP.settingsBackgroundImages,
          option: Preferences.NewTabPage.backgroundImages
        )
      ]
    )

    // Express Browser: with the sponsored options gone, "Media Type" only chooses between the
    // default images and an installed referral theme, so it shows only when one is installed.
    if Preferences.NewTabPage.backgroundImages.value,
      !Preferences.NewTabPage.installedCustomThemes.value.isEmpty
    {
      imageSection.rows.append(backgroundImagesSetting(section: imageSection))
    }

    // Express Browser: the Privacy Reports and Favorites widget toggles are hidden. The Express
    // new tab page (header and feed) shows neither widget, so the toggles did nothing.
    dataSource.sections = [imageSection]
  }

  private func selectedItem() -> BackgroundType {
    if let referrer = Preferences.NewTabPage.selectedCustomTheme.value {
      return .superReferrer(referrer)
    }
    return .defaultImages
  }

  private func backgroundImageOptions() -> [BackgroundType] {
    var available: [BackgroundType] = [.defaultImages]
    available += Preferences.NewTabPage.installedCustomThemes.value.map {
      .superReferrer($0)
    }
    return available
  }

  private func backgroundImagesSetting(section: Section) -> Row {
    var row = Row(
      text: Strings.NTP.settingsBackgroundImageSubMenu,
      detailText: selectedItem().displayString,
      accessory: .disclosureIndicator,
      cellClass: Value1Cell.self
    )

    row.selection = { [unowned self] in
      // Show options for tab bar visibility
      let optionsViewController = OptionSelectionViewController<BackgroundType>(
        headerText: Strings.NTP.settingsBackgroundImageSubMenu,
        style: .insetGrouped,
        options: self.backgroundImageOptions(),
        selectedOption: self.selectedItem(),
        optionChanged: { _, option in
          // Should turn this off whenever possible to prevent unnecessary resource downloading
          switch option {
          case .defaultImages:
            Preferences.NewTabPage.backgroundMediaType = .defaultImages
            Preferences.NewTabPage.selectedCustomTheme.value = nil
          case .superReferrer(let referrer):
            Preferences.NewTabPage.selectedCustomTheme.value = referrer
            Preferences.NewTabPage.backgroundMediaType = .sponsoredImagesAndVideos
          }

          self.dataSource.reloadCell(row: row, section: section, displayText: option.displayString)
        }
      )
      optionsViewController.navigationItem.title = Strings.NTP.settingsBackgroundImageSubMenu
      self.navigationController?.pushViewController(optionsViewController, animated: true)
    }
    return row
  }
}

extension NTPTableViewController: PreferencesObserver {
  func preferencesDidChange(for key: String) {
    loadSections()
  }
}

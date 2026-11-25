// Copyright 2021 The Brave Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at http://mozilla.org/MPL/2.0/.

import SwiftUI
import WidgetKit

@main
struct BraveWidgets: WidgetBundle {
  var body: some Widget {
    ShortcutsWidget()
    FavoritesWidget()
    // Express Browser: Brave News is hidden (as on Android), so its two widgets aren't offered.
    SingleStatWidget()
    StatsWidget()
    LockScreenShortcutWidget()
    LockScreenFavoriteWidget()
  }
}

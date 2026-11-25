// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

// Short messages that come and go without stopping the reader, after Android's `Toast`
// (`CommentListAdapter`: "Comment copied", "Couldn’t save your reaction"). They wear the
// profile's "Profile updated" look (`ExpressProfileToast`) and never take touches.

/// One message.
struct ExpressToast: Equatable, Identifiable {
  /// How long it stays: Android's `Toast.LENGTH_SHORT` (2 s) or `LENGTH_LONG` (3.5 s).
  enum Length: Equatable {
    case short
    case long

    var seconds: Double {
      switch self {
      case .short: return 2
      case .long: return 3.5
      }
    }
  }

  let id = UUID()
  let message: String
  /// A check before the text, for a confirmation ("Comment copied").
  var showsCheck = false
  var length: Length = .short
}

/// Shows a toast on the nearest host above (`expressToastHost`); does nothing without one.
struct ExpressToastAction {
  /// A reference, not a closure, so the environment value stays equal across renders and views
  /// reading it aren't redrawn for nothing.
  fileprivate weak var presenter: ExpressToastPresenter?

  @MainActor func callAsFunction(_ toast: ExpressToast) {
    presenter?.show(toast)
  }

  @MainActor func callAsFunction(
    _ message: String,
    showsCheck: Bool = false,
    length: ExpressToast.Length = .short
  ) {
    presenter?.show(ExpressToast(message: message, showsCheck: showsCheck, length: length))
  }
}

/// The toast a host is showing, and when it goes.
@MainActor
private final class ExpressToastPresenter: ObservableObject {
  @Published private(set) var toast: ExpressToast?
  private var hideTask: Task<Void, Never>?

  /// Timings from the profile toast (`ExpressProfileView.flashToast`).
  func show(_ new: ExpressToast) {
    hideTask?.cancel()
    UIAccessibility.post(notification: .announcement, argument: new.message)
    withAnimation(.easeOut(duration: 0.29)) { toast = new }
    hideTask = Task { @MainActor [weak self] in
      try? await Task.sleep(nanoseconds: UInt64(new.length.seconds * 1_000_000_000))
      guard !Task.isCancelled, let self else { return }
      withAnimation(.easeIn(duration: 0.36)) { self.toast = nil }
    }
  }
}

private struct ExpressToastActionKey: EnvironmentKey {
  static let defaultValue = ExpressToastAction()
}

extension EnvironmentValues {
  /// Shows a short message over the comments screen this view is on.
  var expressToast: ExpressToastAction {
    get { self[ExpressToastActionKey.self] }
    set { self[ExpressToastActionKey.self] = newValue }
  }
}

extension View {
  /// Hosts toasts for a screen: views under it show them through `\.expressToast`, and a
  /// model's one-off `error` shows as one and is cleared (instead of an alert, as Android's
  /// `Toast`). They sit `bottomPadding` above this view's bottom edge, so put the host inside the
  /// composer's safe-area inset to keep them clear of the box.
  func expressToastHost(
    error: Binding<String?> = .constant(nil),
    bottomPadding: CGFloat = 12
  ) -> some View {
    modifier(ExpressToastHost(error: error, bottomPadding: bottomPadding))
  }
}

private struct ExpressToastHost: ViewModifier {
  @Binding var error: String?
  let bottomPadding: CGFloat

  @StateObject private var presenter = ExpressToastPresenter()

  func body(content: Content) -> some View {
    content
      .environment(\.expressToast, ExpressToastAction(presenter: presenter))
      .overlay(alignment: .bottom) {
        ZStack {
          if let toast = presenter.toast {
            ExpressToastView(toast: toast)
              .padding(.horizontal, 24)
              .padding(.bottom, bottomPadding)
              .transition(.opacity.combined(with: .offset(y: 8)))
          }
        }
        .allowsHitTesting(false)
      }
      .onChange(of: error) { _, message in
        guard let message else { return }
        error = nil
        // Android keeps a failed post's reason up longer (`LENGTH_LONG`).
        presenter.show(ExpressToast(message: message, length: message.count > 40 ? .long : .short))
      }
  }
}

/// The toast itself: the profile toast's dark pill (light in dark mode).
struct ExpressToastView: View {
  let toast: ExpressToast

  var body: some View {
    HStack(spacing: 8) {
      if toast.showsCheck {
        Image(systemName: "checkmark")
          .font(.system(size: 13, weight: .bold))
          .foregroundColor(Color(ExpressProfileColors.toastCheck))
      }
      Text(toast.message)
        .font(.system(size: 14, weight: .medium))
        .foregroundColor(Color(ExpressProfileColors.toastText))
        .multilineTextAlignment(.center)
        .lineLimit(3)
    }
    .padding(.horizontal, 16)
    .padding(.vertical, 10)
    .frame(minHeight: 40)
    .background(
      RoundedRectangle(cornerRadius: 20, style: .continuous)
        .fill(Color(ExpressProfileColors.toastBackground))
    )
    .shadow(color: .black.opacity(0.2), radius: 6, y: 3)
    .accessibilityElement(children: .combine)
  }
}

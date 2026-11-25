// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import SwiftUI
import UIKit

/// A text field that cleans each edit before it's shown (lowercasing, dropping characters,
/// a length limit), like Android's input filters. SwiftUI's `TextField` can't reliably rewrite
/// its text while typing: it either drops keystrokes or shows text that differs from its value.
struct ExpressFilteredTextField: UIViewRepresentable {
  @Binding var text: String
  let placeholder: String
  var font: UIFont = .systemFont(ofSize: 16, weight: .medium)
  var textColor: UIColor = .label
  var autocapitalization: UITextAutocapitalizationType = .sentences
  var autocorrection: UITextAutocorrectionType = .default
  /// Applied to the text an edit inserts.
  var filter: (String) -> String = { $0 }
  /// An edit that would make the text longer than this is cut to fit.
  var maxLength: Int = .max
  var keyboardType: UIKeyboardType = .default
  var contentType: UITextContentType? = nil
  /// Draws no text or caret, for a field hidden behind custom boxes.
  var isInvisible = false
  /// Change it to put the keyboard up in this field.
  var focusRequest = 0

  func makeCoordinator() -> Coordinator { Coordinator(self) }

  func makeUIView(context: Context) -> UITextField {
    let field = UITextField()
    field.delegate = context.coordinator
    field.addTarget(context.coordinator, action: #selector(Coordinator.edited(_:)), for: .editingChanged)
    field.setContentHuggingPriority(.defaultLow, for: .horizontal)
    field.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)
    return field
  }

  func updateUIView(_ field: UITextField, context: Context) {
    context.coordinator.parent = self
    // While the reader types, the field is the source of truth: SwiftUI can hand back a value
    // from a keystroke or two ago, and writing it would drop what was typed since.
    if !field.isFirstResponder, field.text != text { field.text = text }
    field.font = font
    field.textColor = textColor
    field.autocapitalizationType = autocapitalization
    field.autocorrectionType = autocorrection
    field.spellCheckingType = autocorrection == .no ? .no : .default
    field.keyboardType = keyboardType
    field.textContentType = contentType
    if isInvisible {
      field.textColor = .clear
      field.tintColor = .clear
      field.accessibilityLabel = placeholder
      field.placeholder = nil
    } else {
      field.attributedPlaceholder = NSAttributedString(
        string: placeholder,
        attributes: [.foregroundColor: UIColor.placeholderText, .font: font]
      )
    }
    if focusRequest != context.coordinator.handledFocusRequest {
      context.coordinator.handledFocusRequest = focusRequest
      DispatchQueue.main.async { field.becomeFirstResponder() }
    }
  }

  final class Coordinator: NSObject, UITextFieldDelegate {
    var parent: ExpressFilteredTextField
    var handledFocusRequest = 0

    init(_ parent: ExpressFilteredTextField) {
      self.parent = parent
    }

    func textField(
      _ textField: UITextField,
      shouldChangeCharactersIn range: NSRange,
      replacementString string: String
    ) -> Bool {
      let current = textField.text ?? ""
      guard let swiftRange = Range(range, in: current) else { return false }
      let inserted = parent.filter(string)
      let room = parent.maxLength - (current.count - current[swiftRange].count)
      // Shrinking is always allowed, so a longer saved value can still be edited down.
      let allowed = room >= inserted.count ? inserted : String(inserted.prefix(max(0, room)))
      if allowed == string { return true }
      let updated = current.replacingCharacters(in: swiftRange, with: allowed)
      textField.text = updated
      let caret = current.distance(from: current.startIndex, to: swiftRange.lowerBound) + allowed.count
      if let position = textField.position(from: textField.beginningOfDocument, offset: caret) {
        textField.selectedTextRange = textField.textRange(from: position, to: position)
      }
      parent.text = updated
      return false
    }

    @objc func edited(_ textField: UITextField) {
      parent.text = textField.text ?? ""
    }

    func textFieldShouldReturn(_ textField: UITextField) -> Bool {
      textField.resignFirstResponder()
      return true
    }
  }
}

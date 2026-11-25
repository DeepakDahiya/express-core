// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import ExpressCore
import SwiftUI
import UIKit
import os.log

// Phone sign-in, ported from the Android app's `BrowserExpressSignupPreferences` and
// `BrowserExpressOtpVerifyPreferences`: name and number, then the 4-digit code from the SMS,
// then "You're all set". iPhone apps can't read SMS, so the code field offers the code from
// Messages above the keyboard instead (one-time-code AutoFill).

// MARK: - Colours (Android light / dark)

enum ExpressSignInColors {
  static let background = UIColor(expressLight: 0xFAFAFF, expressDark: 0x121318)
  static let textPrimary = UIColor(expressLight: 0x171A2B, expressDark: 0xE2E3E8)
  static let textSecondary = UIColor(expressLight: 0x6E7385, expressDark: 0xA8ABB5)
  static let accent = UIColor(expressLight: 0x577DFF, expressDark: 0x5B7CFF)
  static let onAccent = UIColor(expressLight: 0xFFFFFF, expressDark: 0xF0F2FF)
  static let surface = UIColor(expressLight: 0xFFFFFF, expressDark: 0x1E1F24)
  static let fieldStroke = UIColor(expressLight: 0xDBE0ED, expressDark: 0x3C3F4A)
  static let closeIcon = UIColor(expressLight: 0x747480, expressDark: 0xA8ABB5)
  static let card = UIColor(expressLight: 0xF0F2FF, expressDark: 0x1E1F24)
  static let codeRow = UIColor(expressLight: 0xFAFAFF, expressDark: 0x1E1F24)
  static let error = UIColor(expressLight: 0xB82933, expressDark: 0xFF7A80)
}

// MARK: - Model

@MainActor
final class ExpressSignInModel: ObservableObject {
  enum Step { case details, code, done }

  @Published var step: Step = .details
  @Published var name = ""
  @Published var phoneInput = ""
  @Published private(set) var code = ""
  @Published var errorMessage: String?
  @Published private(set) var isSending = false
  @Published private(set) var isVerifying = false
  @Published private(set) var isResending = false
  /// When the code can be sent again.
  @Published private(set) var resendAvailableAt: Date?

  private(set) var phone: String?
  private var purpose = "signup"
  private var autoSubmit: Task<Void, Never>?

  private let client = ExpressAPIClient.shared
  private let account = ExpressAccount.shared
  /// How the code was entered, for `OTP Submitted` (Android: manual or otp_text).
  private var submitSource = "manual"
  /// `OTP Autofilled From SMS` was sent for this code screen.
  private var didTrackAutofill = false

  /// Android's sign-in funnel events (`BrowserExpressAuthJourney`).
  func track(_ event: String, _ extra: [String: Any?] = [:]) {
    var props: [String: Any?] = ["flow": purpose == "login" ? "login" : "signup"]
    props.merge(extra) { _, new in new }
    ExpressAnalytics.shared.capture(event, props)
  }

  var maskedPhone: String { phone.map(ExpressPhoneNumber.masked) ?? "" }

  /// "Send verification code": both fields, a real number, then the SMS.
  func sendCode() async {
    guard !isSending else { return }
    errorMessage = nil
    let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
    let typedPhone = phoneInput.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmedName.isEmpty, !typedPhone.isEmpty else {
      errorMessage = "Add your name and mobile number to continue."
      return
    }
    guard let number = ExpressPhoneNumber.normalize(typedPhone) else {
      errorMessage = "Enter a valid 10-digit mobile number."
      return
    }
    isSending = true
    defer { isSending = false }
    track("OTP Requested", ["has_name": true])
    do {
      purpose = try await client.requestPhoneCode(phone: number, name: trimmedName)
      phone = number
      code = ""
      didTrackAutofill = false
      // Android starts on a one-minute wait before the first resend.
      resendAvailableAt = Date().addingTimeInterval(60)
      step = .code
      track("OTP Verify Screen Viewed")
    } catch {
      errorMessage = error.localizedDescription
      track("OTP Send Failed", ["error": error.localizedDescription])
    }
  }

  /// Each change to the code; four digits submit by themselves after 0.7 s, as on Android.
  func codeChanged(_ new: String) {
    // The whole code in one edit is AutoFill from Messages (a pasted code looks the same):
    // Android's `OTP Autofilled From SMS`, sent when its SMS Retriever reads the code. Once per
    // code screen.
    if code.isEmpty, new.count == 4, !didTrackAutofill {
      didTrackAutofill = true
      track("OTP Autofilled From SMS")
    }
    code = new
    autoSubmit?.cancel()
    autoSubmit = nil
    guard new.count == 4, !isVerifying else { return }
    autoSubmit = Task { [weak self] in
      try? await Task.sleep(nanoseconds: 700_000_000)
      guard !Task.isCancelled else { return }
      self?.submitSource = "otp_text"
      await self?.verify()
    }
  }

  func verify() async {
    guard !isVerifying else { return }
    guard let phone else {
      errorMessage = "Go back and enter your number again."
      return
    }
    guard code.count == 4 else {
      errorMessage = code.count == 3 ? "Enter the 1 digit to continue." : "Enter all 4 digits to continue."
      return
    }
    errorMessage = nil
    isVerifying = true
    defer { isVerifying = false }
    track("OTP Submitted", ["source": submitSource])
    let autoRead = submitSource != "manual"
    submitSource = "manual"
    do {
      let tokens = try await client.verifyPhone(
        phone: phone,
        code: code,
        accessToken: account.validAccessToken,
        refreshToken: account.refreshToken
      )
      if let tokens {
        account.store(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
        if let userID = account.claims?.userID {
          ExpressProfileModel.markPhoneVerified(userID: userID)
        }
      } else {
        Logger.module.info("Express dry run: showing sign-in success without signing in")
      }
      step = .done
      if account.validAccessToken != nil {
        track(purpose == "login" ? "Login" : "User Signed Up", ["auto_read": autoRead])
      }
    } catch {
      errorMessage = error.localizedDescription
      track("OTP Verify Failed", ["error": error.localizedDescription])
    }
  }

  func resend() async {
    guard let phone, !isResending else { return }
    if let resendAvailableAt, resendAvailableAt > Date() { return }
    errorMessage = nil
    isResending = true
    defer { isResending = false }
    do {
      let wait = try await client.resendPhoneCode(phone: phone, purpose: purpose)
      resendAvailableAt = Date().addingTimeInterval(TimeInterval(wait ?? 60))
      track("OTP Resent")
    } catch {
      errorMessage = error.localizedDescription
      if let retry = (error as? ExpressAuthError)?.retryAfter, retry > 0 {
        resendAvailableAt = Date().addingTimeInterval(TimeInterval(retry))
      }
    }
  }

  /// "Edit number": back to the first step with what was typed.
  func editNumber() {
    autoSubmit?.cancel()
    code = ""
    errorMessage = nil
    step = .details
  }
}

// MARK: - Screen

/// Full-screen sign-in. `isRequired` is Android's `authRequired`: opened by the app rather than
/// the reader, so it can't be swiped away; the close button and "Continue without signing in"
/// still work (they start a guest account).
struct ExpressSignInView: View {
  let isRequired: Bool
  /// The reader left without signing in.
  var onSkip: () -> Void
  /// The reader signed in and tapped "Start browsing".
  var onFinish: () -> Void

  @StateObject private var model = ExpressSignInModel()
  @State private var codeFocusRequest = 0
  @FocusState private var focusedField: Field?

  private enum Field { case name, phone }

  var body: some View {
    ScrollView {
      VStack(alignment: .leading, spacing: 0) {
        header
          .padding(.top, 10)
        switch model.step {
        case .details: details
        case .code: codeStep
        case .done: success
        }
      }
      .padding(.horizontal, 24)
      .padding(.bottom, 24)
    }
    .scrollDismissesKeyboard(.interactively)
    .background(Color(ExpressSignInColors.background).ignoresSafeArea())
    .onAppear { model.track("Auth Screen Viewed") }
    .interactiveDismissDisabled(isRequired)
    .animation(.easeInOut(duration: 0.2), value: model.step)
  }

  // MARK: Header

  private var header: some View {
    HStack {
      Text("express")
        .font(.system(size: 23, weight: .semibold))
        .foregroundColor(Color(ExpressSignInColors.textPrimary))
        .accessibilityAddTraits(.isHeader)
      Spacer()
      Button {
        if model.step == .done {
          onFinish()
        } else {
          model.track("Registration Skipped (Auto Login)")
          onSkip()
        }
      } label: {
        Image(systemName: "xmark")
          .font(.system(size: 16, weight: .medium))
          .foregroundColor(Color(ExpressSignInColors.closeIcon))
          .frame(width: 44, height: 44)
          .background(Circle().fill(Color(ExpressSignInColors.surface)))
      }
      .accessibilityLabel("Close")
    }
    .frame(height: 44)
  }

  // MARK: Step 1: name and number

  private var details: some View {
    VStack(alignment: .leading, spacing: 0) {
      eyebrow("Make yourself at home")
        .padding(.top, 45)
      headline("Your browser.\nA little more you.")
        .padding(.top, 12)
      bodyText("Sign in with your name and number.\nOr skip this and keep exploring.")
        .padding(.top, 11)

      fieldLabel("Your name")
        .padding(.top, 32)
      TextField("What should we call you?", text: $model.name)
        .textContentType(.name)
        .textInputAutocapitalization(.words)
        .autocorrectionDisabled()
        .submitLabel(.next)
        .focused($focusedField, equals: .name)
        .onSubmit { focusedField = .phone }
        .font(.system(size: 15))
        .foregroundColor(Color(ExpressSignInColors.textPrimary))
        .padding(.horizontal, 16)
        .frame(height: 54)
        .background(fieldBackground)
        .padding(.top, 8)

      fieldLabel("Mobile number")
        .padding(.top, 16)
      HStack(spacing: 0) {
        Text("+91")
          .font(.system(size: 15, weight: .medium))
          .foregroundColor(Color(ExpressSignInColors.textPrimary))
        Rectangle()
          .fill(Color(ExpressSignInColors.fieldStroke))
          .frame(width: 1, height: 26)
          .padding(.leading, 20)
          .padding(.trailing, 16)
        TextField("Mobile number", text: $model.phoneInput)
          .keyboardType(.phonePad)
          .textContentType(.telephoneNumber)
          .focused($focusedField, equals: .phone)
          .font(.system(size: 15))
          .foregroundColor(Color(ExpressSignInColors.textPrimary))
      }
      .padding(.horizontal, 16)
      .frame(height: 54)
      .background(fieldBackground)
      .padding(.top, 8)

      errorLine
        .padding(.top, 2)

      primaryButton(model.isSending ? "Sending code…" : "Send verification code") {
        focusedField = nil
        Task {
          await model.sendCode()
          if model.step == .code { codeFocusRequest += 1 }
        }
      }
      .disabled(model.isSending)
      .padding(.top, 5)
      caption("We’ll send an SMS to verify your number.")
        .padding(.top, 12)

      infoCard(
        symbol: "checkmark.shield",
        title: "Less tracking. More browsing.",
        detail: "Privacy controls, right where you need them."
      )
      .padding(.top, 39)
      skipLink("Continue without signing in")
        .padding(.top, 24)
    }
  }

  // MARK: Step 2: the code

  private var codeStep: some View {
    VStack(alignment: .leading, spacing: 0) {
      eyebrow("One quick check")
        .padding(.top, 45)
      headline("Check your\nmessages.")
        .padding(.top, 12)
      bodyText("Enter the 4-digit code sent to\n\(model.maskedPhone)")
        .padding(.top, 11)
      Button("Edit number") { model.editNumber() }
        .font(.system(size: 13, weight: .medium))
        .foregroundColor(Color(ExpressSignInColors.accent))
        .frame(height: 44)

      codeBoxes
        .padding(.top, 13)
      errorLine
        .padding(.top, 8)

      primaryButton(model.isVerifying ? "Verifying…" : "Verify & continue") {
        Task { await model.verify() }
      }
      .disabled(model.isVerifying)
      .padding(.top, 12)

      resendLink
        .padding(.top, 13)
      caption("Keep your code private. We’ll never ask you to share it.")
        .padding(.top, 5)

      infoCard(
        symbol: "bubble.left.and.bubble.right",
        title: "Join the conversation.",
        detail: "React and reply to text, photos and videos."
      )
      .padding(.top, 64)
      skipLink("I’ll do this later")
        .padding(.top, 24)
    }
    .task {
      try? await Task.sleep(nanoseconds: 250_000_000)
      codeFocusRequest += 1
    }
  }

  /// Four boxes mirroring one hidden field, which takes the typing and the code AutoFill.
  private var codeBoxes: some View {
    let digits = Array(model.code)
    let active = min(digits.count, 3)
    return ZStack {
      HStack(spacing: 12) {
        ForEach(0..<4, id: \.self) { index in
          let isActive = index == active && !model.isVerifying
          Text(index < digits.count ? String(digits[index]) : "–")
            .font(.system(size: 22, weight: .medium).monospacedDigit())
            .foregroundColor(Color(index < digits.count ? ExpressSignInColors.textPrimary : ExpressSignInColors.textSecondary))
            .frame(maxWidth: .infinity)
            .frame(height: 58)
            .background(
              RoundedRectangle(cornerRadius: 16)
                .fill(Color(ExpressSignInColors.surface))
                .overlay(
                  RoundedRectangle(cornerRadius: 16)
                    .stroke(Color(isActive ? ExpressSignInColors.accent : ExpressSignInColors.fieldStroke), lineWidth: 1)
                )
            )
        }
      }
      .accessibilityHidden(true)
      ExpressFilteredTextField(
        text: Binding(get: { model.code }, set: model.codeChanged),
        placeholder: "Verification code",
        autocapitalization: .none,
        autocorrection: .no,
        filter: { $0.filter(\.isNumber) },
        maxLength: 4,
        keyboardType: .numberPad,
        contentType: .oneTimeCode,
        isInvisible: true,
        focusRequest: codeFocusRequest
      )
    }
    .frame(height: 58)
    .background(Color(ExpressSignInColors.codeRow))
    .contentShape(Rectangle())
    .onTapGesture { codeFocusRequest += 1 }
  }

  private var resendLink: some View {
    TimelineView(.periodic(from: .now, by: 1)) { context in
      let remaining = model.resendAvailableAt.map { Int(ceil($0.timeIntervalSince(context.date))) } ?? 0
      Group {
        if model.isResending {
          Text("Sending a new code…")
            .foregroundColor(Color(ExpressSignInColors.textSecondary))
        } else if remaining > 0 {
          Text("Code sent. You can resend in \(remaining) \(remaining == 1 ? "second" : "seconds").")
            .foregroundColor(Color(ExpressSignInColors.textSecondary))
        } else {
          Button("Didn’t get a code? Resend") { Task { await model.resend() } }
            .foregroundColor(Color(ExpressSignInColors.accent))
        }
      }
      .font(.system(size: 13, weight: .medium))
      .frame(minHeight: 44, alignment: .leading)
    }
  }

  // MARK: Step 3: done

  private var success: some View {
    let name = model.name.trimmingCharacters(in: .whitespacesAndNewlines)
    return VStack(spacing: 0) {
      Circle()
        .fill(Color(ExpressSignInColors.card))
        .frame(width: 92, height: 92)
        .overlay(
          Image(systemName: "checkmark")
            .font(.system(size: 38, weight: .semibold))
            .foregroundColor(Color(ExpressSignInColors.accent))
        )
        .padding(.top, 78)
        .accessibilityHidden(true)
      Text(name.isEmpty ? "You’re all set." : "You’re all set, \(name).")
        .font(.system(size: 30, weight: .semibold))
        .foregroundColor(Color(ExpressSignInColors.textPrimary))
        .multilineTextAlignment(.center)
        .padding(.top, 41)
      Text("Your number is verified.\nMake yourself at home in Express.")
        .font(.system(size: 16))
        .foregroundColor(Color(ExpressSignInColors.textSecondary))
        .multilineTextAlignment(.center)
        .padding(.top, 15)
      primaryButton("Start browsing", action: onFinish)
        .padding(.top, 43)
      infoCard(
        symbol: "checkmark.shield",
        title: "Your privacy, your choice.",
        detail: "Manage protection from the address bar."
      )
      .padding(.top, 54)
    }
    .frame(maxWidth: .infinity)
  }

  // MARK: Pieces

  private var fieldBackground: some View {
    RoundedRectangle(cornerRadius: 16)
      .fill(Color(ExpressSignInColors.surface))
      .overlay(RoundedRectangle(cornerRadius: 16).stroke(Color(ExpressSignInColors.fieldStroke), lineWidth: 1))
  }

  /// Keeps its space when empty, so the button below doesn't jump.
  private var errorLine: some View {
    Text(model.errorMessage ?? " ")
      .font(.system(size: 12))
      .foregroundColor(Color(ExpressSignInColors.error))
      .frame(maxWidth: .infinity, minHeight: 16, alignment: .leading)
      .accessibilityHidden(model.errorMessage == nil)
  }

  private func eyebrow(_ text: String) -> some View {
    Text(text.uppercased())
      .font(.system(size: 11, weight: .semibold))
      .tracking(0.6)
      .foregroundColor(Color(ExpressSignInColors.accent))
  }

  private func headline(_ text: String) -> some View {
    Text(text)
      .font(.system(size: 34, weight: .semibold))
      .foregroundColor(Color(ExpressSignInColors.textPrimary))
      .fixedSize(horizontal: false, vertical: true)
      .accessibilityAddTraits(.isHeader)
  }

  private func bodyText(_ text: String) -> some View {
    Text(text)
      .font(.system(size: 15))
      .foregroundColor(Color(ExpressSignInColors.textSecondary))
      .fixedSize(horizontal: false, vertical: true)
  }

  private func fieldLabel(_ text: String) -> some View {
    Text(text)
      .font(.system(size: 12, weight: .medium))
      .foregroundColor(Color(ExpressSignInColors.textPrimary))
  }

  private func caption(_ text: String) -> some View {
    Text(text)
      .font(.system(size: 12))
      .foregroundColor(Color(ExpressSignInColors.textSecondary))
  }

  private func primaryButton(_ title: String, action: @escaping () -> Void) -> some View {
    Button(action: action) {
      Text(title)
        .font(.system(size: 16, weight: .semibold))
        .foregroundColor(Color(ExpressSignInColors.onAccent))
        .frame(maxWidth: .infinity)
        .frame(height: 54)
        .background(RoundedRectangle(cornerRadius: 16).fill(Color(ExpressSignInColors.accent)))
    }
    .buttonStyle(.plain)
  }

  private func infoCard(symbol: String, title: String, detail: String) -> some View {
    HStack(alignment: .top, spacing: 12) {
      Image(systemName: symbol)
        .font(.system(size: 19))
        .foregroundColor(Color(ExpressSignInColors.accent))
        .frame(width: 24)
        .padding(.top, 6)
      VStack(alignment: .leading, spacing: 3) {
        Text(title)
          .font(.system(size: 14, weight: .medium))
          .foregroundColor(Color(ExpressSignInColors.textPrimary))
        Text(detail)
          .font(.system(size: 12))
          .foregroundColor(Color(ExpressSignInColors.textSecondary))
      }
      Spacer(minLength: 0)
    }
    .padding(EdgeInsets(top: 13, leading: 16, bottom: 23, trailing: 16))
    .background(RoundedRectangle(cornerRadius: 16).fill(Color(ExpressSignInColors.card)))
    .accessibilityElement(children: .combine)
  }

  private func skipLink(_ title: String) -> some View {
    Button(title) {
      model.track("Registration Skipped (Auto Login)")
      onSkip()
    }
      .font(.system(size: 14, weight: .medium))
      .foregroundColor(Color(ExpressSignInColors.textSecondary))
      .frame(maxWidth: .infinity)
      .frame(height: 44)
  }
}

extension View {
  /// Sign-in the app asks for (commenting in India while signed out). Leaving without signing
  /// in starts a guest account, as Android does.
  func expressSignInPrompt(_ isPresented: Binding<Bool>) -> some View {
    fullScreenCover(isPresented: isPresented) {
      ExpressSignInView(
        isRequired: true,
        onSkip: {
          if ExpressAccount.shared.validAccessToken == nil {
            Task { @MainActor in await ExpressAccount.shared.ensureGuestAccount(force: true) }
          }
          isPresented.wrappedValue = false
        },
        onFinish: { isPresented.wrappedValue = false }
      )
    }
  }
}

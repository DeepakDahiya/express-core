// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Phone sign-in, after the Android app's `BrowserExpressSignupPreferencesUtil` and
/// `BrowserExpressOtpVerifyPreferencesUtil` (and `docs/browser_express_india_phone_auth_api.md`).
///
/// `POST /auth/register` texts a 4-digit code; `POST /auth/verify-phone` swaps it for tokens.
/// Verifying sends the current (guest) tokens, so the server upgrades that account and keeps its
/// comments and referrals.
public enum ExpressPhoneNumber {
  /// Android's rules: digits only; drop a leading 91 from 12 digits or 0 from 11; 10 digits are
  /// an Indian mobile number (which, unlike Android's check, must start with 6–9); otherwise a
  /// typed "+" with 8–15 digits is kept as it is. Returns the number as `+<digits>`, or nil when
  /// it isn't a phone number.
  public static func normalize(_ input: String) -> String? {
    let trimmed = input.trimmingCharacters(in: .whitespacesAndNewlines)
    var digits = trimmed.filter(\.isASCIIDigit)
    if digits.count == 12, digits.hasPrefix("91") {
      digits.removeFirst(2)
    } else if digits.count == 11, digits.hasPrefix("0") {
      digits.removeFirst()
    }
    let number: String
    if digits.count == 10, let first = digits.first, ("6"..."9").contains(first) {
      number = "+91" + digits
    } else if trimmed.hasPrefix("+"), (8...15).contains(digits.count) {
      number = "+" + digits
    } else {
      return nil
    }
    return number.range(of: #"^\+[1-9][0-9]{7,14}$"#, options: .regularExpression) != nil
      ? number : nil
  }

  /// "+91 ••••• •3210": every digit but the last four hidden, in groups of five.
  public static func masked(_ number: String) -> String {
    var digits = number.filter(\.isASCIIDigit)
    var prefix = "+"
    if number.hasPrefix("+91"), digits.count == 12 {
      digits.removeFirst(2)
      prefix = "+91 "
    }
    let hiddenCount = max(0, digits.count - 4)
    let shown = String(repeating: "•", count: hiddenCount) + digits.suffix(4)
    var groups: [String] = []
    var index = shown.startIndex
    while index < shown.endIndex {
      let end = shown.index(index, offsetBy: 5, limitedBy: shown.endIndex) ?? shown.endIndex
      groups.append(String(shown[index..<end]))
      index = end
    }
    return prefix + groups.joined(separator: " ")
  }
}

extension Character {
  fileprivate var isASCIIDigit: Bool { ("0"..."9").contains(self) }
}

/// A sign-in request the server turned down, in words to show, with the wait it asked for.
public struct ExpressAuthError: Error, LocalizedError, Sendable {
  public let message: String
  /// Seconds before trying again (the body's `retryAfterSeconds` or the `Retry-After` header).
  public let retryAfter: Int?

  public var errorDescription: String? { message }

  /// Clear words for the server's codes; its own `message`, when it sends one, wins.
  static func from(json: [String: Any], response: HTTPURLResponse?, fallback: String) -> ExpressAuthError {
    var retry = (json["retryAfterSeconds"] as? NSNumber)?.intValue
    if retry == nil, let header = response?.value(forHTTPHeaderField: "Retry-After") {
      retry = Int(header.trimmingCharacters(in: .whitespaces))
    }
    if let text = (json["message"] as? String).nonEmptyValue {
      return ExpressAuthError(message: text, retryAfter: retry)
    }
    let code = (json["code"] as? String).nonEmptyValue ?? (json["error"] as? String).nonEmptyValue
    let message: String
    switch code {
    case "invalid_phone": message = "Enter a valid mobile number."
    case "invalid_country": message = "Sign-in is only available for Indian numbers for now."
    case "invalid_name": message = "Add your name (up to 160 characters) to continue."
    case "account_disabled": message = "This account has been turned off."
    case "rate_limited", "otp_rate_limited": message = "Too many tries. Wait a moment, then try again."
    case "otp_delivery_failed": message = "We couldn’t send the code. Try again."
    case "invalid_otp": message = "That code isn’t right. Check it and try again."
    case "too_many_otp_attempts": message = "Too many wrong codes. Wait a moment, then try again."
    case "phone_challenge_not_found", "pending_signup_not_found":
      message = "That code has expired. Send a new one."
    case "phone_not_registered": message = "This number isn’t registered yet. Go back and send a new code."
    case "phone_verification_failed": message = "We couldn’t verify your number. Try again."
    case .some(let other) where other.contains(" "): message = other
    default: message = fallback
    }
    return ExpressAuthError(message: message, retryAfter: retry)
  }
}

extension ExpressAPIClient {
  /// `POST /auth/register {phone, country: "IN", name}`: texts a code. Returns the purpose the
  /// server chose ("login" for a known number, else "signup").
  public func requestPhoneCode(phone: String, name: String) async throws -> String {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST auth/register (no code was sent)")
      try? await Task.sleep(nanoseconds: 600_000_000)
      return "signup"
    }
    let json = try await authPost(
      "auth/register",
      body: ["phone": phone, "country": "IN", "name": name],
      fallback: "We couldn’t send the code. Try again."
    )
    guard json["ok"] as? Bool == true, json["otpSent"] as? Bool == true else {
      throw ExpressAuthError.from(json: json, response: nil, fallback: "We couldn’t send the code. Try again.")
    }
    return (json["purpose"] as? String) == "login" ? "login" : "signup"
  }

  /// `POST /auth/resend-phone-otp {phone, purpose}`. Returns when the next resend is allowed, if
  /// the server said.
  public func resendPhoneCode(phone: String, purpose: String) async throws -> Int? {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST auth/resend-phone-otp")
      try? await Task.sleep(nanoseconds: 600_000_000)
      return nil
    }
    let json = try await authPost(
      "auth/resend-phone-otp",
      body: ["phone": phone, "purpose": purpose],
      fallback: "We couldn’t send a new code. Try again."
    )
    guard json["ok"] as? Bool == true, json["otpSent"] as? Bool == true else {
      throw ExpressAuthError.from(json: json, response: nil, fallback: "We couldn’t send a new code. Try again.")
    }
    return (json["resendAvailableInSeconds"] as? NSNumber).map(\.intValue).flatMap { $0 > 0 ? $0 : nil }
  }

  /// `POST /auth/verify-phone {phone, otp}` with the current tokens, so a guest account is
  /// upgraded rather than replaced. Returns the new tokens, or nil in a dry run.
  public func verifyPhone(
    phone: String,
    code: String,
    accessToken: String?,
    refreshToken: String?
  ) async throws -> ExpressGuestTokens? {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped POST auth/verify-phone (signed in nothing)")
      try? await Task.sleep(nanoseconds: 600_000_000)
      return nil
    }
    var headers: [String: String] = [:]
    if let accessToken { headers["Authorization"] = "Bearer \(accessToken)" }
    if let refreshToken { headers["x-refresh"] = refreshToken }
    let json = try await authPost(
      "auth/verify-phone",
      body: ["phone": phone, "otp": code],
      headers: headers,
      fallback: "We couldn’t verify your number. Try again."
    )
    guard let access = (json["accessToken"] as? String).nonEmptyValue,
      let refresh = (json["refreshToken"] as? String).nonEmptyValue
    else {
      throw ExpressAuthError(message: "We couldn’t sign you in. Try again.", retryAfter: nil)
    }
    return ExpressGuestTokens(accessToken: access, refreshToken: refresh)
  }

  /// Posts JSON; any 2xx returns the body, anything else throws an `ExpressAuthError`.
  private func authPost(
    _ path: String,
    body: [String: Any],
    headers: [String: String] = [:],
    fallback: String
  ) async throws -> [String: Any] {
    var request = makeRequest(
      path: path,
      method: "POST",
      body: try? JSONSerialization.data(withJSONObject: body),
      timeout: 20
    )
    for (name, value) in headers { request.setValue(value, forHTTPHeaderField: name) }
    let data: Data
    let response: URLResponse
    do {
      (data, response) = try await session.data(for: request)
    } catch {
      throw ExpressAuthError(message: "Check your connection and try again.", retryAfter: nil)
    }
    let http = response as? HTTPURLResponse
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    guard let status = http?.statusCode, (200..<300).contains(status) else {
      Logger.module.error("Express \(path, privacy: .public) returned \(http?.statusCode ?? 0)")
      throw ExpressAuthError.from(json: json, response: http, fallback: fallback)
    }
    return json
  }
}

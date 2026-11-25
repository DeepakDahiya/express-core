// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import Security
import os.log

/// The signed-in Express account (or anonymous guest account), kept in the Keychain.
///
/// Mirrors the Android app: outside India a signed-out user silently gets a guest account
/// from `GET /auth/generate-username`; in India the user must sign in with their phone.
/// An expired access token is cleared when read, and the next new tab page creates a new
/// guest account. Unlike Android, the refresh token is kept, and both are cleared on sign-out.
@MainActor
public final class ExpressAccount: ObservableObject {
  public static let shared = ExpressAccount()

  @Published public private(set) var accessToken: String?
  public private(set) var refreshToken: String?

  private let client: ExpressAPIClient
  private var guestLoginTask: Task<Void, Never>?

  init(client: ExpressAPIClient = .shared) {
    self.client = client
    accessToken = ExpressKeychain.string(for: .accessToken)
    refreshToken = ExpressKeychain.string(for: .refreshToken)
  }

  /// The access token if it hasn't expired. Reading an expired token signs out (Android behaviour).
  public var validAccessToken: String? {
    guard let token = accessToken else { return nil }
    if let expiry = ExpressJWT(token: token)?.expiry, Date() >= expiry {
      Logger.module.info("Express access token expired; signing out")
      signOut()
      return nil
    }
    return token
  }

  public var isSignedIn: Bool { validAccessToken != nil }

  /// Claims of the current token (user id, username, referral code).
  public var claims: ExpressJWT? { validAccessToken.flatMap { ExpressJWT(token: $0) } }

  /// Users in India must sign in with a phone number instead of getting a guest account.
  /// "India" means the region is IN or the time zone is Asia/Kolkata, as on Android.
  public static var isIndia: Bool {
    Locale.current.region?.identifier.uppercased() == "IN"
      || TimeZone.current.identifier == "Asia/Kolkata"
  }

  public func store(accessToken: String, refreshToken: String?) {
    self.accessToken = accessToken
    ExpressKeychain.set(accessToken, for: .accessToken)
    if let refreshToken {
      self.refreshToken = refreshToken
      ExpressKeychain.set(refreshToken, for: .refreshToken)
    }
  }

  public func signOut() {
    accessToken = nil
    refreshToken = nil
    ExpressKeychain.set(nil, for: .accessToken)
    ExpressKeychain.set(nil, for: .refreshToken)
  }

  /// Creates a guest account when signed out and outside India. Only one attempt runs at a
  /// time; it retries after 1, 2 and 4 seconds. Returns whether a token is now available.
  ///
  /// `force` skips the India check. Android does that when an Indian user closes the phone
  /// sign-up; until phone sign-in is ported to iOS, commenting and reacting use it directly.
  @discardableResult
  public func ensureGuestAccount(force: Bool = false) async -> Bool {
    if validAccessToken != nil { return true }
    if Self.isIndia && !force { return false }
    if let running = guestLoginTask {
      await running.value
      return validAccessToken != nil
    }
    let task = Task { @MainActor [weak self] in
      guard let self else { return }
      for delay in [0, 1, 2, 4] {
        if delay > 0 {
          try? await Task.sleep(nanoseconds: UInt64(delay) * 1_000_000_000)
        }
        do {
          let tokens = try await self.client.generateGuestAccount()
          self.store(accessToken: tokens.accessToken, refreshToken: tokens.refreshToken)
          Logger.module.info("Express guest account created")
          return
        } catch {
          Logger.module.error(
            "Express guest login failed: \(error.localizedDescription, privacy: .public)"
          )
        }
      }
    }
    guestLoginTask = task
    await task.value
    guestLoginTask = nil
    return validAccessToken != nil
  }

  /// A stable per-install device id (Android uses SSAID or a UUID), kept in the Keychain.
  public static var deviceID: String {
    if let existing = ExpressKeychain.string(for: .deviceID) { return existing }
    let id = UUID().uuidString.lowercased()
    ExpressKeychain.set(id, for: .deviceID)
    return id
  }
}

/// Claims read from an Express JWT (`exp`, `_id`, `username`, `name`, `referralCode`).
public struct ExpressJWT {
  public let expiry: Date?
  public let userID: String?
  public let username: String?
  public let name: String?
  public let referralCode: String?

  /// Decodes the payload (second segment, base64url). Returns nil for anything that isn't a JWT;
  /// like Android, such tokens are treated as never expiring.
  public init?(token: String) {
    let segments = token.split(separator: ".")
    guard segments.count >= 2 else { return nil }
    var base64 = String(segments[1])
      .replacingOccurrences(of: "-", with: "+")
      .replacingOccurrences(of: "_", with: "/")
    let padding = (4 - base64.count % 4) % 4
    base64 += String(repeating: "=", count: padding)
    guard let data = Data(base64Encoded: base64),
      let object = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
    else { return nil }
    if let exp = object["exp"] as? Double {
      expiry = Date(timeIntervalSince1970: exp)
    } else if let exp = object["exp"] as? Int {
      expiry = Date(timeIntervalSince1970: TimeInterval(exp))
    } else {
      expiry = nil
    }
    userID = object["_id"] as? String
    username = object["username"] as? String
    name = object["name"] as? String
    referralCode = object["referralCode"] as? String
  }
}

/// Minimal Keychain storage for Express tokens. Items stay on this device and are readable
/// after the first unlock (no Face ID prompt, unlike the wallet's keychain helper).
enum ExpressKeychain {
  enum Key: String {
    case accessToken = "access-token"
    case refreshToken = "refresh-token"
    case deviceID = "device-id"
  }

  private static let service = "express.account"

  static func string(for key: Key) -> String? {
    let query: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: key.rawValue,
      kSecReturnData as String: true,
      kSecMatchLimit as String: kSecMatchLimitOne,
    ]
    var result: AnyObject?
    let status = SecItemCopyMatching(query as CFDictionary, &result)
    guard status == errSecSuccess, let data = result as? Data else {
      if status != errSecItemNotFound {
        Logger.module.error("Express keychain read failed: \(status)")
      }
      return nil
    }
    return String(data: data, encoding: .utf8)
  }

  static func set(_ value: String?, for key: Key) {
    let match: [String: Any] = [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: service,
      kSecAttrAccount as String: key.rawValue,
    ]
    SecItemDelete(match as CFDictionary)
    guard let value, let data = value.data(using: .utf8) else { return }
    var add = match
    add[kSecValueData as String] = data
    add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
    let status = SecItemAdd(add as CFDictionary, nil)
    if status != errSecSuccess {
      Logger.module.error("Express keychain write failed: \(status)")
    }
  }
}

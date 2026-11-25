// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Debug switches.
public enum ExpressDebug {
  /// Launch a Debug build with `-ExpressDryRun YES` (or the older `-ExpressReactionsDryRun YES`)
  /// to try features that write to the server without writing anything: the change shows in
  /// the app only. Always false in release builds.
  public static var isDryRun: Bool {
    #if DEBUG
    return UserDefaults.standard.bool(forKey: "ExpressDryRun")
      || UserDefaults.standard.bool(forKey: "ExpressReactionsDryRun")
    #else
    return false
    #endif
  }

  /// `-ExpressFakeScores 12.5.3` (karma, taste, cred; any separator), with the dry run on: the
  /// Profile shows these scores and counts up to them from the server's last-seen ones, so the
  /// count-up can be checked on an account whose scores are all 0. Nil in release builds.
  public static var fakeScores: ExpressScores? {
    #if DEBUG
    guard isDryRun, let text = UserDefaults.standard.string(forKey: "ExpressFakeScores") else {
      return nil
    }
    let numbers = text.split(whereSeparator: { !$0.isNumber }).compactMap { Int($0) }
    guard numbers.count == 3 else { return nil }
    return ExpressScores(karma: numbers[0], taste: numbers[1], cred: numbers[2])
    #else
    return nil
    #endif
  }
}

/// The reader's three scores, as on Android's Profile.
public struct ExpressScores: Equatable, Codable, Sendable {
  public var karma: Int
  public var taste: Int
  public var cred: Int

  public init(karma: Int, taste: Int, cred: Int) {
    self.karma = karma
    self.taste = taste
    self.cred = cred
  }

  public static let zero = ExpressScores(karma: 0, taste: 0, cred: 0)

  public subscript(index: Int) -> Int {
    switch index {
    case 0: return karma
    case 1: return taste
    default: return cred
    }
  }
}

/// `GET /user/me`, as the Profile reads it.
public struct ExpressMe: Equatable, Sendable {
  public let name: String?
  public let username: String?
  /// The server's avatar value, unresolved (may be a default face, legacy or missing).
  public let rawAvatar: String?
  public let scores: ExpressScores
  /// The scores the reader last saw; nil on a first visit.
  public let scoresSeen: ExpressScores?
  public let hasSeenKarmaLabel: Bool
  /// The account's verified phone number, if the server sends one.
  public let phone: String?
}

/// The result of saving the name and username.
public enum ExpressProfileUpdateResult: Sendable {
  case saved(accessToken: String, refreshToken: String?)
  case usernameTaken
  case failed(String?)
}

/// The reader's own avatar, cached on the device for the account that owns it (Android's
/// `ProfileAvatar.saveSelf` / `selfUrl`). Without a saved photo it is the account's default
/// face.
public enum ExpressSelfAvatar {
  private static let urlKey = "express.avatar_url"
  private static let ownerKey = "express.avatar_url_owner"

  /// The avatar to show for the account `userID`.
  public static func url(for userID: String?) -> URL? {
    guard let userID, !userID.isEmpty else { return nil }
    let defaults = UserDefaults.standard
    let saved =
      defaults.string(forKey: ownerKey) == userID ? defaults.string(forKey: urlKey) : nil
    return ExpressAvatar.resolve(saved, seed: userID)
  }

  /// Remembers the server's value for `userID`. Empty and "null" values are ignored.
  public static func save(_ raw: String?, for userID: String?) {
    guard let userID, !userID.isEmpty, let raw = raw.nonEmptyValue else { return }
    UserDefaults.standard.set(raw, forKey: urlKey)
    UserDefaults.standard.set(userID, forKey: ownerKey)
  }

  public static func clear() {
    UserDefaults.standard.removeObject(forKey: urlKey)
    UserDefaults.standard.removeObject(forKey: ownerKey)
  }
}

extension ExpressAPIClient {
  /// `GET /user/me` with `Bearer` auth, as the Android Profile sends it.
  public func fetchMe(accessToken: String) async throws -> ExpressMe {
    let request = makeRequest(path: "user/me", authorization: .bearer(accessToken), timeout: 15)
    let json = try await sendForJSON(request)
    guard json["success"] as? Bool == true, let user = json["user"] as? [String: Any] else {
      throw ExpressAPIError.server((json["error"] as? String) ?? "Couldn't load your profile")
    }
    func text(_ key: String) -> String? {
      (user[key] as? String).nonEmptyValue
    }
    func count(_ object: [String: Any], _ key: String) -> Int {
      max(0, (object[key] as? NSNumber)?.intValue ?? 0)
    }
    func scores(_ object: [String: Any]) -> ExpressScores {
      ExpressScores(karma: count(object, "karma"), taste: count(object, "taste"), cred: count(object, "cred"))
    }
    let seen = (user["scoresSeen"] as? [String: Any]).map(scores)
    return ExpressMe(
      name: text("name"),
      username: text("username"),
      rawAvatar: text("avatar"),
      scores: ExpressDebug.fakeScores ?? scores(user),
      scoresSeen: ExpressDebug.fakeScores == nil ? seen : (seen ?? .zero),
      hasSeenKarmaLabel: user["hasSeenKarmaLabel"] as? Bool ?? false,
      phone: text("phone")
    )
  }

  /// `PATCH /user/me/profile-state {scoresSeen}` (a write, fire and forget).
  public func markScoresSeen(_ scores: ExpressScores, accessToken: String) async {
    await patchProfileState(
      ["scoresSeen": ["karma": scores.karma, "taste": scores.taste, "cred": scores.cred]],
      accessToken: accessToken
    )
  }

  /// `PATCH /user/me/profile-state {hasSeenKarmaLabel: true}` (a write, fire and forget).
  public func markKarmaLabelSeen(accessToken: String) async {
    await patchProfileState(["hasSeenKarmaLabel": true], accessToken: accessToken)
  }

  private func patchProfileState(_ body: [String: Any], accessToken: String) async {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped PATCH user/me/profile-state")
      return
    }
    let request = makeRequest(
      path: "user/me/profile-state",
      method: "PATCH",
      authorization: .bearer(accessToken),
      body: try? JSONSerialization.data(withJSONObject: body),
      timeout: 15
    )
    _ = try? await session.data(for: request)
  }

  /// `GET /user/username-available`. Nil when the answer isn't known. The reader's own username
  /// counts as available.
  public func isUsernameAvailable(_ username: String, accessToken: String) async -> Bool? {
    let request = makeRequest(
      path: "user/username-available",
      query: [("username", username)],
      authorization: .bearer(accessToken),
      timeout: 15
    )
    guard let json = try? await sendForJSON(request), json["success"] as? Bool == true else {
      return nil
    }
    return json["available"] as? Bool
  }

  /// `PATCH /user/me {name, username}` (a write). New tokens carry the new claims.
  public func updateProfile(
    name: String,
    username: String,
    accessToken: String
  ) async -> ExpressProfileUpdateResult {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped PATCH user/me")
      return .failed("Dry run: nothing was saved.")
    }
    let request = makeRequest(
      path: "user/me",
      method: "PATCH",
      authorization: .bearer(accessToken),
      body: try? JSONSerialization.data(withJSONObject: ["name": name, "username": username]),
      timeout: 15
    )
    do {
      let (data, response) = try await session.data(for: request)
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
      if json["success"] as? Bool == true, let access = (json["accessToken"] as? String).nonEmptyValue {
        return .saved(accessToken: access, refreshToken: (json["refreshToken"] as? String).nonEmptyValue)
      }
      // Read the code on any status: Android only saw it with HTTP 200.
      if json["code"] as? String == "username_taken" { return .usernameTaken }
      let status = (response as? HTTPURLResponse)?.statusCode ?? 0
      return .failed((json["error"] as? String).nonEmptyValue ?? (status == 200 ? nil : "HTTP \(status)"))
    } catch {
      return .failed(nil)
    }
  }

  /// `PATCH /user/avatar`, multipart (a write). The image must already be the square JPEG.
  /// Returns nil on success, else a message (which may be empty).
  public func uploadAvatar(jpeg: Data, accessToken: String) async -> String? {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped PATCH user/avatar")
      return "Dry run: nothing was saved."
    }
    let boundary = "----ExpressFormBoundary\(Int(Date().timeIntervalSince1970 * 1000))"
    var body = Data()
    let filename = "avatar_\(Int(Date().timeIntervalSince1970 * 1000))_\(Int.random(in: 0...999)).jpg"
    body.append("--\(boundary)\r\n".data(using: .utf8)!)
    // The Android app sends the file under the field name "reports", so the server reads it there.
    body.append(
      "Content-Disposition: form-data; name=\"reports\"; filename=\"\(filename)\"\r\n".data(using: .utf8)!
    )
    body.append("Content-Type: image/jpeg\r\n\r\n".data(using: .utf8)!)
    body.append(jpeg)
    body.append("\r\n--\(boundary)--\r\n".data(using: .utf8)!)
    var request = makeRequest(
      path: "user/avatar",
      method: "PATCH",
      authorization: .raw(accessToken),
      body: body,
      timeout: 60
    )
    request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
    do {
      let (data, response) = try await session.data(for: request)
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
      if (response as? HTTPURLResponse)?.statusCode == 200, json["success"] as? Bool == true {
        return nil
      }
      return (json["error"] as? String) ?? ""
    } catch {
      return ""
    }
  }

  /// `DELETE /user/avatar` (a write): back to the default face.
  public func deleteAvatar(accessToken: String) async -> Bool {
    if ExpressDebug.isDryRun {
      Logger.module.info("Express dry run: skipped DELETE user/avatar")
      return false
    }
    let request = makeRequest(
      path: "user/avatar",
      method: "DELETE",
      authorization: .bearer(accessToken),
      timeout: 15
    )
    guard let json = try? await sendForJSON(request) else { return false }
    return json["success"] as? Bool == true
  }

  /// Sends a request and returns its JSON object; non-200 statuses throw.
  func sendForJSON(_ request: URLRequest) async throws -> [String: Any] {
    let (data, response) = try await session.data(for: request)
    guard let http = response as? HTTPURLResponse else { throw ExpressAPIError.invalidResponse }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    guard http.statusCode == 200 else {
      throw ExpressAPIError.http(http.statusCode, (json?["error"] as? String) ?? (json?["message"] as? String))
    }
    guard let json else { throw ExpressAPIError.invalidResponse }
    return json
  }
}

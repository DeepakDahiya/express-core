// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Errors from the Express backend (`api.browser.express`).
public enum ExpressAPIError: Error, LocalizedError {
  /// The server answered with a non-200 status, with its `error`/`message` text when it sent one.
  case http(Int, String?)
  /// The server answered 200 but reported `success: false`.
  case server(String)
  /// The response could not be read.
  case invalidResponse

  public var errorDescription: String? {
    switch self {
    case .http(let code, let message):
      return message.map { "\($0) (HTTP \(code))" } ?? "HTTP \(code)"
    case .server(let message): return message
    case .invalidResponse: return "Invalid response"
    }
  }
}

/// How a request authenticates. The Android app uses different formats per endpoint,
/// and the server expects the same, so each call picks the one Android uses.
enum ExpressAuthorization {
  case none
  /// `Authorization: <jwt>` (feed, comments, `/user/me`).
  case raw(String)
  /// `Authorization: Bearer <jwt>` (feed events, profile, push, referral).
  case bearer(String)
}

/// Thin async client for `https://api.browser.express/v1`, matching the Android app's requests.
public struct ExpressAPIClient: Sendable {
  public static let baseURL = URL(string: "https://api.browser.express/v1")!
  public static let shared = ExpressAPIClient()

  let session: URLSession

  public init(session: URLSession = .shared) {
    self.session = session
  }

  /// The device's region code, sent as `country` like Android (`Locale.getDefault().getCountry()`).
  public static var countryCode: String {
    Locale.current.region?.identifier ?? ""
  }

  // MARK: - Request building

  /// Percent-encodes a query value the way `URLEncoder.encode` does for anything but letters,
  /// digits and `-._~`, so URLs passed as values (for example `first_load?url=`) survive intact.
  static func encodeQueryValue(_ value: String) -> String {
    var allowed = CharacterSet.alphanumerics
    allowed.insert(charactersIn: "-._~")
    return value.addingPercentEncoding(withAllowedCharacters: allowed) ?? value
  }

  func makeRequest(
    path: String,
    query: [(String, String)] = [],
    method: String = "GET",
    authorization: ExpressAuthorization = .none,
    body: Data? = nil,
    timeout: TimeInterval = 20
  ) -> URLRequest {
    var components = URLComponents(
      url: Self.baseURL.appendingPathComponent(path),
      resolvingAgainstBaseURL: false
    )!
    if !query.isEmpty {
      components.percentEncodedQuery =
        query
        .map { "\(Self.encodeQueryValue($0.0))=\(Self.encodeQueryValue($0.1))" }
        .joined(separator: "&")
    }
    var request = URLRequest(
      url: components.url!,
      cachePolicy: .reloadIgnoringLocalCacheData,
      timeoutInterval: timeout
    )
    request.httpMethod = method
    request.setValue("application/json", forHTTPHeaderField: "Content-Type")
    switch authorization {
    case .none:
      break
    case .raw(let token):
      request.setValue(token, forHTTPHeaderField: "Authorization")
    case .bearer(let token):
      request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
    }
    request.httpBody = body
    return request
  }

  /// Sends a request and decodes a 200 response. Any other status throws `.http`.
  func send<T: Decodable>(_ request: URLRequest, as type: T.Type) async throws -> T {
    let (data, response) = try await session.data(for: request)
    guard let http = response as? HTTPURLResponse else {
      throw ExpressAPIError.invalidResponse
    }
    guard http.statusCode == 200 else {
      let body = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
      let message = (body?["error"] as? String) ?? (body?["message"] as? String)
      Logger.module.error(
        "Express API \(request.url?.path ?? "", privacy: .public) returned \(http.statusCode): \(message ?? "", privacy: .public)"
      )
      throw ExpressAPIError.http(http.statusCode, message)
    }
    do {
      return try JSONDecoder().decode(T.self, from: data)
    } catch {
      Logger.module.error(
        "Express API \(request.url?.path ?? "", privacy: .public) decode failed: \(error.localizedDescription, privacy: .public)"
      )
      throw ExpressAPIError.invalidResponse
    }
  }

  // MARK: - Endpoints

  /// `GET /public/config` (no auth).
  public func fetchRemoteConfig() async throws -> ExpressRemoteConfig {
    try await send(makeRequest(path: "public/config", timeout: 10), as: ExpressRemoteConfig.self)
  }

  /// `GET /auth/generate-username` creates an anonymous guest account (no auth).
  public func generateGuestAccount() async throws -> (accessToken: String, refreshToken: String) {
    struct Response: Decodable {
      var success: Bool
      var accessToken: String?
      var refreshToken: String?
      var error: String?
    }
    let response = try await send(
      makeRequest(path: "auth/generate-username", timeout: 20),
      as: Response.self
    )
    guard response.success else {
      throw ExpressAPIError.server(response.error ?? "Guest login failed")
    }
    guard let access = response.accessToken.nonEmptyValue,
      let refresh = response.refreshToken.nonEmptyValue
    else {
      throw ExpressAPIError.server("Missing auth tokens")
    }
    return (access, refresh)
  }

  /// `GET /post/feed`. The token is sent raw (no `Bearer`), and left out when signed out.
  public func fetchFeed(
    cursor: String?,
    perPage: Int = 20,
    accessToken: String?
  ) async throws -> ExpressFeedPage {
    var query: [(String, String)] = [
      ("per_page", String(perPage)),
      ("country", Self.countryCode),
    ]
    if let cursor, !cursor.isEmpty {
      query.append(("cursor", cursor))
    }
    let page = try await send(
      makeRequest(
        path: "post/feed",
        query: query,
        authorization: accessToken.map { .raw($0) } ?? .none,
        timeout: 20
      ),
      as: ExpressFeedPage.self
    )
    guard page.success else {
      throw ExpressAPIError.server(page.error ?? "Couldn't load posts")
    }
    return page
  }

  /// `GET /comment/first_load` returns the number of comments for a page (no auth).
  public func fetchCommentCount(for pageURL: URL) async throws -> Int {
    struct Response: Decodable {
      var success: Bool
      var commentCount: Int?
    }
    let response = try await send(
      makeRequest(
        path: "comment/first_load",
        query: [("url", pageURL.absoluteString), ("country", Self.countryCode)],
        timeout: 15
      ),
      as: Response.self
    )
    guard response.success else { throw ExpressAPIError.server("Comment count failed") }
    return response.commentCount ?? 0
  }

  /// `GET /public/tutorial-video` (no auth). Returns nil when the card should not show.
  public func fetchTutorialVideo(ignoringEnabled: Bool = false) async throws -> ExpressTutorialVideo? {
    let video = try await send(
      makeRequest(path: "public/tutorial-video", timeout: 10),
      as: ExpressTutorialVideo.self
    )
    if ignoringEnabled { return video.videoURL != nil || video.imageURL != nil ? video : nil }
    return video.isShown ? video : nil
  }
}

extension Optional where Wrapped == String {
  /// Treats nil, empty and the literal string "null" as absent, as the Android app does.
  var nonEmptyValue: String? {
    guard let value = self?.trimmingCharacters(in: .whitespacesAndNewlines),
      !value.isEmpty, value != "null"
    else { return nil }
    return value
  }
}

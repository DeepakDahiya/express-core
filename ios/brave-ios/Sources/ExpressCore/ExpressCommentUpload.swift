// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation
import os.log

/// Where a presigned file goes (`POST /comment/attachments/presign`).
public struct ExpressPresignedUpload: Sendable {
  public let key: String
  public let uploadURL: URL
  /// Sent verbatim on the PUT (S3 signs them); the client adds none of its own.
  public let headers: [String: String]
  public let tokens: ExpressGuestTokens?
}

/// The server predates presigned attachments (it answered 404).
public struct ExpressPresignUnavailable: Error {}

/// One page of `GET /gif/search`.
public struct ExpressGIFPage: Sendable {
  public struct GIF: Identifiable, Hashable, Sendable {
    public let id: String
    public let previewURL: URL
    public let url: URL
    public let width: Int
    public let height: Int
  }

  public let results: [GIF]
  /// "giphy" or "tenor", for the attribution line.
  public let provider: String?
  public let next: String?
}

/// GIF search isn't available right now (HTTP 503).
public struct ExpressGIFSearchUnavailable: Error {}

extension ExpressAPIClient {
  /// `POST /comment/attachments/presign {kind, mime, size, name?}` with the raw token, as Android
  /// does (`CommentUploadManager.presign`). `mime` and `size` describe the file as it will be sent.
  public func presignAttachment(
    kind: ExpressAttachmentKind,
    mime: String,
    size: Int64,
    name: String?,
    accessToken: String?
  ) async throws -> ExpressPresignedUpload {
    var body: [String: Any] = ["kind": kind.rawValue, "mime": mime, "size": size]
    if let name { body["name"] = name }
    var request = makeRequest(
      path: "comment/attachments/presign",
      method: "POST",
      authorization: accessToken.map { .raw($0) } ?? .none,
      body: try JSONSerialization.data(withJSONObject: body),
      timeout: 30
    )
    request.timeoutInterval = 30
    let (data, response) = try await session.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    if status == 404 { throw ExpressPresignUnavailable() }
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    guard json["success"] as? Bool == true,
      let key = json["key"] as? String,
      let upload = (json["uploadUrl"] as? String).flatMap(URL.init(string:))
    else {
      let message = (json["message"] as? String) ?? (json["error"] as? String)
      throw ExpressAPIError.server(message ?? "Server error \(status)")
    }
    var headers: [String: String] = [:]
    for (name, value) in json["headers"] as? [String: Any] ?? [:] {
      if let text = value as? String {
        headers[name] = text
      } else if let number = value as? NSNumber {
        headers[name] = number.stringValue
      }
    }
    return ExpressPresignedUpload(
      key: key,
      uploadURL: upload,
      headers: headers,
      tokens: Self.guestTokens(in: json)
    )
  }

  /// PUTs a file to its presigned URL, reporting bytes sent. Cancelling the calling task stops it.
  public func uploadAttachment(
    _ fileURL: URL,
    to presigned: ExpressPresignedUpload,
    progress: @escaping @Sendable (Int64) -> Void
  ) async throws {
    var request = URLRequest(
      url: presigned.uploadURL,
      cachePolicy: .reloadIgnoringLocalCacheData,
      timeoutInterval: 120
    )
    request.httpMethod = "PUT"
    for (name, value) in presigned.headers {
      request.setValue(value, forHTTPHeaderField: name)
    }
    let delegate = ExpressUploadProgressDelegate(progress: progress)
    let (data, response) = try await session.upload(
      for: request,
      fromFile: fileURL,
      delegate: delegate
    )
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard (200..<300).contains(status) else {
      Logger.module.error(
        "Express storage upload failed (\(status, privacy: .public)): \(String(decoding: data.prefix(300), as: UTF8.self), privacy: .public)"
      )
      throw ExpressAPIError.http(status, nil)
    }
  }

  /// The old single-file route (`POST /comment/with-media`, multipart), for a server without
  /// presigned uploads. Same response as `POST /comment`.
  public func postCommentWithMedia(
    _ content: String,
    to parent: ExpressCommentParent,
    file: URL,
    kind: ExpressAttachmentKind,
    mime: String,
    name: String?,
    width: Int,
    height: Int,
    durationMs: Int,
    accessToken: String?
  ) async throws -> (comment: ExpressComment, tokens: ExpressGuestTokens?) {
    let boundary = "----WebKitFormBoundary\(Int(Date().timeIntervalSince1970 * 1000))"
    var fields: [(String, String)] = [("content", content), ("platform", "iOS")]
    switch parent {
    case .page(let url):
      fields += [("parentType", "page"), ("url", url.absoluteString)]
    case .post(let id):
      fields += [("parentType", "post"), ("parentId", id)]
    case .comment(let id, let pageURL):
      fields += [("parentType", "comment"), ("parentId", id)]
      if let pageURL { fields.append(("url", pageURL.absoluteString)) }
    }
    fields.append(("mediaType", kind.rawValue))
    if kind == .audio, durationMs > 0 { fields.append(("mediaDurationMs", String(durationMs))) }
    if kind == .video, width > 0, height > 0 {
      fields += [("mediaWidth", String(width)), ("mediaHeight", String(height))]
    }
    var fileName = "media_\(Int(Date().timeIntervalSince1970 * 1000)).\(file.pathExtension)"
    if kind == .document, let name {
      fields.append(("mediaFileName", name))
      fileName = String(
        name.map { $0.isLetter && $0.isASCII || $0.isNumber && $0.isASCII || "._-".contains($0) ? $0 : "_" }
      )
    }
    var body = Data()
    for (name, value) in fields {
      body.append(Data("--\(boundary)\r\nContent-Disposition: form-data; name=\"\(name)\"\r\n\r\n\(value)\r\n".utf8))
    }
    body.append(
      Data(
        "--\(boundary)\r\nContent-Disposition: form-data; name=\"reports\"; filename=\"\(fileName)\"\r\nContent-Type: \(mime)\r\n\r\n"
          .utf8
      )
    )
    body.append(try Data(contentsOf: file))
    body.append(Data("\r\n--\(boundary)--\r\n".utf8))

    let country = Self.countryCode
    var request = makeRequest(
      path: "comment/with-media",
      query: country.isEmpty ? [] : [("country", country)],
      method: "POST",
      authorization: accessToken.map { .raw($0) } ?? .none,
      body: body,
      timeout: 180
    )
    request.setValue("multipart/form-data; boundary=\(boundary)", forHTTPHeaderField: "Content-Type")
    let (data, response) = try await session.data(for: request)
    let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any] ?? [:]
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard json["success"] as? Bool == true, let commentJSON = json["comment"] else {
      let message = (json["message"] as? String) ?? (json["error"] as? String)
      throw ExpressAPIError.server(message ?? "Server error \(status)")
    }
    let comment = try JSONDecoder().decode(
      ExpressComment.self,
      from: JSONSerialization.data(withJSONObject: commentJSON)
    )
    return (comment, Self.guestTokens(in: json))
  }

  /// `GET /gif/search?q=&pos=`: the server proxies GIPHY or Tenor, so no key lives here. An empty
  /// query returns trending GIFs.
  public func searchGIFs(query: String, position: String?) async throws -> ExpressGIFPage {
    var parameters = [("q", query)]
    if let position { parameters.append(("pos", position)) }
    var request = makeRequest(path: "gif/search", query: parameters, timeout: 15)
    #if DEBUG
    // `-ExpressGIFSearchURL http://127.0.0.1:8799/gif/search` points GIF search at a local
    // stand-in, for checking the picker while the live server answers 503.
    if let override = UserDefaults.standard.string(forKey: "ExpressGIFSearchURL"),
      var components = URLComponents(string: override)
    {
      components.queryItems = parameters.map { URLQueryItem(name: $0.0, value: $0.1) }
      request.url = components.url ?? request.url
    }
    #endif
    let (data, response) = try await session.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    if status == 503 { throw ExpressGIFSearchUnavailable() }
    guard status == 200,
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any]
    else { throw ExpressAPIError.http(status, nil) }
    let results = (json["results"] as? [[String: Any]] ?? []).compactMap { item -> ExpressGIFPage.GIF? in
      guard let preview = (item["previewUrl"] as? String).flatMap(URL.init(string:)),
        let url = (item["url"] as? String).flatMap(URL.init(string:))
      else { return nil }
      let id = (item["id"] as? String) ?? (item["id"] as? NSNumber)?.stringValue ?? url.absoluteString
      return ExpressGIFPage.GIF(
        id: id,
        previewURL: preview,
        url: url,
        width: (item["width"] as? NSNumber)?.intValue ?? 0,
        height: (item["height"] as? NSNumber)?.intValue ?? 0
      )
    }
    return ExpressGIFPage(
      results: results,
      provider: json["provider"] as? String,
      next: (json["next"] as? String).nonEmptyValue ?? (json["next"] as? NSNumber)?.stringValue
    )
  }

  static func guestTokens(in json: [String: Any]) -> ExpressGuestTokens? {
    guard let access = (json["accessToken"] as? String).nonEmptyValue,
      let refresh = (json["refreshToken"] as? String).nonEmptyValue
    else { return nil }
    return ExpressGuestTokens(accessToken: access, refreshToken: refresh)
  }
}

extension ExpressDebug {
  /// `-ExpressFakeUploadFailure YES`, with the dry run on: a comment's first send fails part way
  /// (its Retry then goes through); `-ExpressFakeUploadFailure always` fails every try. For
  /// checking Retry and Cancel in the simulator without writing to the server. `attempt` counts
  /// from 1. Always false in release builds.
  public static func fakesUploadFailure(attempt: Int) -> Bool {
    #if DEBUG
    guard isDryRun,
      let value = UserDefaults.standard.string(forKey: "ExpressFakeUploadFailure")?.lowercased()
    else { return false }
    if value == "always" { return true }
    return attempt <= 1 && ["yes", "true", "1"].contains(value)
    #else
    return false
    #endif
  }

  /// `-ExpressFakeAccountCreated YES`, with the dry run on: each dry-run send ends as if the post
  /// had come back with a new account, so "Username … created" shows (with the current
  /// username). Always false in release builds.
  public static var fakesAccountCreated: Bool {
    #if DEBUG
    return isDryRun && UserDefaults.standard.bool(forKey: "ExpressFakeAccountCreated")
    #else
    return false
    #endif
  }

  /// How long a dry-run upload with files takes to reach 100%: 4 s, or
  /// `-ExpressDryRunUploadSeconds N` (1–60), so its percentage and Cancel can be watched.
  public static var dryRunUploadSeconds: Double {
    #if DEBUG
    let seconds = UserDefaults.standard.double(forKey: "ExpressDryRunUploadSeconds")
    return seconds > 0 ? min(max(seconds, 1), 60) : 4
    #else
    return 4
    #endif
  }
}

/// Reports bytes sent for one upload task.
private final class ExpressUploadProgressDelegate: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
  let progress: @Sendable (Int64) -> Void

  init(progress: @escaping @Sendable (Int64) -> Void) {
    self.progress = progress
  }

  func urlSession(
    _ session: URLSession,
    task: URLSessionTask,
    didSendBodyData bytesSent: Int64,
    totalBytesSent: Int64,
    totalBytesExpectedToSend: Int64
  ) {
    progress(totalBytesSent)
  }
}

extension ExpressComment {
  /// A comment made on this device only (dry runs): decoded from the same JSON shape the server
  /// sends, so every card renders it as it would a real one.
  public static func local(
    id: String,
    content: String,
    userID: String?,
    username: String?,
    avatar: String?,
    attachments: [ExpressCommentAttachment]
  ) -> ExpressComment? {
    var json: [String: Any] = [
      "_id": id,
      "content": content,
      "createdAt": ISO8601DateFormatter().string(from: Date()),
      "commentCount": 0,
      "reactions": [] as [Any],
      "reactionCount": 0,
    ]
    if let userID {
      var user: [String: Any] = ["_id": userID]
      if let username { user["username"] = username }
      if let avatar { user["avatar"] = avatar }
      json["user"] = user
    }
    json["attachments"] = attachments.map { file -> [String: Any] in
      var item: [String: Any] = [
        "kind": file.kind.rawValue, "url": file.url.absoluteString,
        "width": file.width, "height": file.height, "durationMs": file.durationMs,
        "size": file.size,
      ]
      if let name = file.name { item["name"] = name }
      if let mime = file.mime { item["mime"] = mime }
      return item
    }
    guard let data = try? JSONSerialization.data(withJSONObject: json) else { return nil }
    return try? JSONDecoder().decode(ExpressComment.self, from: data)
  }
}

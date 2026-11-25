// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Foundation

/// A YouTube watch page's comment count, from the YouTube Data API as on Android
/// (`YouTubeCommentsUtil`): the pill shows YouTube's own number there, not Express's.
public enum ExpressYouTubeStats {
  /// The same key Android ships (`YouTubeCommentsUtil.YT_API_KEY`).
  private static let apiKey = "AIzaSyC-LdqrxY9etQklNNbfK1lNLkozljiixQg"

  /// The `v` of a youtube.com watch URL; nil for anything else (youtu.be and Shorts included,
  /// like Android).
  public static func watchVideoID(in url: URL) -> String? {
    guard url.scheme == "http" || url.scheme == "https",
      let host = url.host?.lowercased(), host.contains("youtube.com"),
      let id = URLComponents(url: url, resolvingAgainstBaseURL: false)?
        .queryItems?.first(where: { $0.name == "v" })?.value,
      !id.isEmpty
    else { return nil }
    return id
  }

  /// `GET youtube/v3/videos?part=statistics&id=`: `items[0].statistics.commentCount` (a string).
  public static func commentCount(videoID: String, session: URLSession = .shared) async throws -> Int {
    var components = URLComponents(string: "https://www.googleapis.com/youtube/v3/videos")!
    components.queryItems = [
      URLQueryItem(name: "key", value: apiKey),
      URLQueryItem(name: "part", value: "statistics"),
      URLQueryItem(name: "id", value: videoID),
    ]
    var request = URLRequest(url: components.url!, timeoutInterval: 15)
    request.setValue("application/json", forHTTPHeaderField: "Accept")
    if let bundleID = Bundle.main.bundleIdentifier {
      // Lets the key be restricted to this app in Google Cloud.
      request.setValue(bundleID, forHTTPHeaderField: "X-Ios-Bundle-Identifier")
    }
    let (data, response) = try await session.data(for: request)
    let status = (response as? HTTPURLResponse)?.statusCode ?? 0
    guard status == 200,
      let json = (try? JSONSerialization.jsonObject(with: data)) as? [String: Any],
      let item = (json["items"] as? [[String: Any]])?.first,
      let statistics = item["statistics"] as? [String: Any]
    else { throw ExpressAPIError.http(status, nil) }
    if let text = statistics["commentCount"] as? String { return Int(text) ?? 0 }
    return (statistics["commentCount"] as? NSNumber)?.intValue ?? 0
  }
}

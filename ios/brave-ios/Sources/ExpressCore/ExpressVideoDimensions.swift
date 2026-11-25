// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import CoreGraphics
import Foundation

/// Video sizes for comments, which the API usually leaves out. Like Android's
/// `CommentVideoDimensions`, it reads them from the file (the server's MP4s keep their header at
/// the start, so this is a small ranged read), gives up after 8 seconds, and keeps up to 500
/// results across launches.
public actor ExpressVideoDimensions {
  public static let shared = ExpressVideoDimensions()

  private static let defaultsKey = "ExpressVideoDimensions.v1"
  private static let maxEntries = 500
  private static let timeout: UInt64 = 8_000_000_000

  private var sizes: [String: CGSize] = [:]
  private var order: [String] = []
  private var inFlight: [String: Task<CGSize?, Never>] = [:]

  public init() {
    let stored = UserDefaults.standard.array(forKey: Self.defaultsKey) as? [[Any]] ?? []
    for entry in stored {
      guard entry.count == 3, let key = entry[0] as? String,
        let w = entry[1] as? Double, let h = entry[2] as? Double, w > 0, h > 0
      else { continue }
      sizes[key] = CGSize(width: w, height: h)
      order.append(key)
    }
  }

  /// The size already known for a video, if any.
  public func cachedSize(for url: URL) -> CGSize? {
    sizes[url.absoluteString]
  }

  /// The video's display size (rotation applied), or nil if it could not be read in time.
  public func size(for url: URL) async -> CGSize? {
    let key = url.absoluteString
    if let size = sizes[key] { return size }
    if let task = inFlight[key] { return await task.value }
    let task = Task { await Self.probe(url) }
    inFlight[key] = task
    let size = await task.value
    inFlight[key] = nil
    if let size { remember(size, for: key) }
    return size
  }

  private func remember(_ size: CGSize, for key: String) {
    if sizes[key] == nil { order.append(key) }
    sizes[key] = size
    while order.count > Self.maxEntries {
      sizes[order.removeFirst()] = nil
    }
    let stored: [[Any]] = order.compactMap { key in
      sizes[key].map { [key, Double($0.width), Double($0.height)] }
    }
    UserDefaults.standard.set(stored, forKey: Self.defaultsKey)
  }

  private static func probe(_ url: URL) async -> CGSize? {
    await withTaskGroup(of: CGSize?.self) { group in
      group.addTask { await readSize(url) }
      group.addTask {
        try? await Task.sleep(nanoseconds: timeout)
        return nil
      }
      let first = await group.next() ?? nil
      group.cancelAll()
      return first
    }
  }

  private static func readSize(_ url: URL) async -> CGSize? {
    // A finished cached copy if there is one; a probe shouldn't start a full download.
    let asset = AVURLAsset(url: ExpressMediaCache.shared.cachedFileURL(for: url) ?? url)
    guard let track = try? await asset.loadTracks(withMediaType: .video).first,
      let (natural, transform) = try? await track.load(.naturalSize, .preferredTransform)
    else { return nil }
    let rect = CGRect(origin: .zero, size: natural).applying(transform)
    let size = CGSize(width: abs(rect.width).rounded(), height: abs(rect.height).rounded())
    return size.width > 0 && size.height > 0 ? size : nil
  }
}

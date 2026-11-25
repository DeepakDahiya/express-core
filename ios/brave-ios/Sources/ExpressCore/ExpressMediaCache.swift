// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import CryptoKit
import Foundation
import UniformTypeIdentifiers
import os.log

/// Disk cache for comment and post media, after Android's `CommentMediaCache`: without it every
/// player streams from the origin again each time (a reel page swiped back to, a feed card
/// scrolled past twice, a voice note replayed). Media URLs are versioned, so a finished copy is
/// valid forever and eviction is only about space: least recently used first, over 256 MB.
///
/// A finished copy plays straight from disk. Otherwise the asset reads through a resource loader
/// that downloads the file once, saving it as it arrives and answering the player from what's
/// on disk. Any loader failure is reported to the player like a network failure would be.
public final class ExpressMediaCache: @unchecked Sendable {
  public static let shared = ExpressMediaCache()

  static let maxBytes: Int64 = 256 * 1024 * 1024
  static let scheme = "express-media"

  let directory: URL
  private let lock = NSLock()
  /// Downloads still in progress, shared by every asset for the same URL.
  private var loaders: [String: WeakLoader] = [:]
  private let maintenance = DispatchQueue(label: "express.media-cache.maintenance", qos: .utility)

  private struct WeakLoader {
    weak var loader: Loader?
  }

  private static var loaderKey: UInt8 = 0

  init(directory: URL? = nil) {
    let base =
      directory
      ?? FileManager.default.urls(for: .cachesDirectory, in: .userDomainMask)[0]
      .appendingPathComponent("express_media", isDirectory: true)
    self.directory = base
    try? FileManager.default.createDirectory(at: base, withIntermediateDirectories: true)
    // Partial downloads from an earlier run can't be resumed (their length isn't stored).
    maintenance.async { [base] in
      let files = (try? FileManager.default.contentsOfDirectory(at: base, includingPropertiesForKeys: nil)) ?? []
      for file in files where file.pathExtension == "part" {
        try? FileManager.default.removeItem(at: file)
      }
    }
  }

  // MARK: Public

  /// An asset for `url` that reads through the cache. Local files and HLS streams are passed
  /// through untouched.
  public func asset(for url: URL) -> AVURLAsset {
    guard Self.isCacheable(url) else { return AVURLAsset(url: url) }
    if let file = cachedFileURL(for: url) {
      Logger.module.info("Express media cache: hit \(url.lastPathComponent, privacy: .public)")
      return AVURLAsset(url: file)
    }
    let loader = sharedLoader(for: url)
    var components = URLComponents(url: url, resolvingAgainstBaseURL: false)
    components?.scheme = Self.scheme
    guard let loaderURL = components?.url else { return AVURLAsset(url: url) }
    let asset = AVURLAsset(url: loaderURL)
    asset.resourceLoader.setDelegate(loader, queue: loader.queue)
    // The resource loader holds its delegate weakly; the asset keeps the loader alive.
    objc_setAssociatedObject(asset, &Self.loaderKey, loader, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
    return asset
  }

  /// The finished copy of `url` on disk, if there is one. Marks it as recently used.
  public func cachedFileURL(for url: URL) -> URL? {
    guard Self.isCacheable(url) else { return nil }
    let file = finalURL(for: url)
    guard FileManager.default.fileExists(atPath: file.path) else { return nil }
    maintenance.async {
      try? FileManager.default.setAttributes([.modificationDate: Date()], ofItemAtPath: file.path)
    }
    return file
  }

  // MARK: Internals

  static func isCacheable(_ url: URL) -> Bool {
    guard let scheme = url.scheme?.lowercased(), scheme == "https" || scheme == "http" else {
      return false
    }
    let ext = url.pathExtension.lowercased()
    return ext != "m3u8" && ext != "mpd"
  }

  private func key(for url: URL) -> String {
    SHA256.hash(data: Data(url.absoluteString.utf8)).map { String(format: "%02x", $0) }.joined()
  }

  func finalURL(for url: URL) -> URL {
    // The extension tells AVFoundation the format; unknown ones fall back to MP4.
    let ext = url.pathExtension.lowercased()
    let known = UTType(filenameExtension: ext).map { $0.conforms(to: .audiovisualContent) } ?? false
    return directory.appendingPathComponent(key(for: url)).appendingPathExtension(known ? ext : "mp4")
  }

  /// A partial file of its own for each download, so two never share one.
  func partURL(for url: URL) -> URL {
    directory.appendingPathComponent("\(key(for: url))-\(UUID().uuidString)").appendingPathExtension("part")
  }

  private func sharedLoader(for url: URL) -> Loader {
    lock.lock()
    defer { lock.unlock() }
    let key = url.absoluteString
    if let existing = loaders[key]?.loader { return existing }
    let loader = Loader(remoteURL: url, partURL: partURL(for: url), finalURL: finalURL(for: url), cache: self)
    loaders[key] = WeakLoader(loader: loader)
    loaders = loaders.filter { $0.value.loader != nil }
    return loader
  }

  /// A download finished: new assets use the file, and the oldest files go if over budget.
  func didFinish(_ url: URL) {
    lock.lock()
    loaders[url.absoluteString] = nil
    lock.unlock()
    maintenance.async { [directory] in
      Self.evict(in: directory, maxBytes: Self.maxBytes)
    }
  }

  /// A download failed: the next asset for this URL starts a fresh one.
  func didFail(_ url: URL, loader: Loader) {
    lock.lock()
    if loaders[url.absoluteString]?.loader === loader { loaders[url.absoluteString] = nil }
    lock.unlock()
  }

  static func evict(in directory: URL, maxBytes: Int64) {
    let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey]
    let files =
      ((try? FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: keys)) ?? [])
      .filter { $0.pathExtension != "part" }
    var entries: [(url: URL, size: Int64, date: Date)] = files.compactMap { file in
      guard let values = try? file.resourceValues(forKeys: Set(keys)) else { return nil }
      return (file, Int64(values.fileSize ?? 0), values.contentModificationDate ?? .distantPast)
    }
    var total = entries.reduce(Int64(0)) { $0 + $1.size }
    guard total > maxBytes else { return }
    entries.sort { $0.date < $1.date }
    for entry in entries where total > maxBytes {
      try? FileManager.default.removeItem(at: entry.url)
      total -= entry.size
      Logger.module.info("Express media cache: evicted \(entry.size, privacy: .public) bytes")
    }
  }
}

// MARK: - Loader

extension ExpressMediaCache {
  /// Downloads one URL to disk and answers the player's byte-range requests from it. Everything
  /// runs on `queue` (the resource loader's queue and the URL session's delegate queue).
  final class Loader: NSObject, AVAssetResourceLoaderDelegate, @unchecked Sendable {
    let queue: DispatchQueue
    private let remoteURL: URL
    private let partURL: URL
    private let finalURL: URL
    private weak var cache: ExpressMediaCache?

    /// URLSession keeps its delegate alive until it's invalidated, so it gets a proxy that holds
    /// this loader weakly; the loader invalidates the session when it goes.
    private var sessionStorage: URLSession?
    private var session: URLSession {
      if let sessionStorage { return sessionStorage }
      let operations = OperationQueue()
      operations.underlyingQueue = queue
      operations.maxConcurrentOperationCount = 1
      let session = URLSession(
        configuration: .default,
        delegate: SessionProxy(loader: self),
        delegateQueue: operations
      )
      sessionStorage = session
      return session
    }

    private var task: URLSessionDataTask?
    private var writer: FileHandle?
    private var reader: FileHandle?
    private var contentLength: Int64?
    private var contentType: String?
    private var downloaded: Int64 = 0
    private var isComplete = false
    private var failure: Error?
    private var pending: [AVAssetResourceLoadingRequest] = []
    /// Requests far ahead of the download (a seek), fetched straight from the network.
    private var directTasks: [ObjectIdentifier: URLSessionDataTask] = [:]
    private var directRequests: [Int: AVAssetResourceLoadingRequest] = [:]
    private var idleCheck: DispatchWorkItem?

    /// How far past the download a request can start before it's fetched directly.
    private static let directThreshold: Int64 = 3 * 1024 * 1024
    /// An unfinished download nobody is waiting on stops after this long.
    private static let idleTimeout: TimeInterval = 10

    init(remoteURL: URL, partURL: URL, finalURL: URL, cache: ExpressMediaCache) {
      self.remoteURL = remoteURL
      self.partURL = partURL
      self.finalURL = finalURL
      self.cache = cache
      queue = DispatchQueue(label: "express.media-cache.loader", qos: .userInitiated)
    }

    deinit {
      sessionStorage?.invalidateAndCancel()
      try? writer?.close()
      try? reader?.close()
      if !isComplete { try? FileManager.default.removeItem(at: partURL) }
    }

    // MARK: AVAssetResourceLoaderDelegate

    func resourceLoader(
      _ resourceLoader: AVAssetResourceLoader,
      shouldWaitForLoadingOfRequestedResource loadingRequest: AVAssetResourceLoadingRequest
    ) -> Bool {
      pending.append(loadingRequest)
      idleCheck?.cancel()
      idleCheck = nil
      startDownloadIfNeeded()
      serve()
      return true
    }

    func resourceLoader(
      _ resourceLoader: AVAssetResourceLoader,
      didCancel loadingRequest: AVAssetResourceLoadingRequest
    ) {
      pending.removeAll { $0 === loadingRequest }
      if let direct = directTasks.removeValue(forKey: ObjectIdentifier(loadingRequest)) {
        directRequests[direct.taskIdentifier] = nil
        direct.cancel()
      }
      scheduleIdleCheckIfNeeded()
    }

    // MARK: Download

    private func startDownloadIfNeeded() {
      guard !isComplete, task == nil, failure == nil else { return }
      var request = URLRequest(url: remoteURL)
      if downloaded > 0 { request.setValue("bytes=\(downloaded)-", forHTTPHeaderField: "Range") }
      let task = session.dataTask(with: request)
      self.task = task
      task.resume()
    }

    /// Starts the idle clock once nothing is waiting. Data still arriving doesn't restart it
    /// (`serve()` runs on every chunk); a new request from the player stops it.
    private func scheduleIdleCheckIfNeeded() {
      guard pending.isEmpty, task != nil, !isComplete, idleCheck == nil else { return }
      let check = DispatchWorkItem { [weak self] in
        guard let self else { return }
        self.idleCheck = nil
        guard self.pending.isEmpty, let task = self.task else { return }
        // Nobody is playing this any more; keep what's on disk and resume if asked again.
        self.task = nil
        task.cancel()
      }
      idleCheck = check
      queue.asyncAfter(deadline: .now() + Self.idleTimeout, execute: check)
    }

    func urlSession(
      _ session: URLSession,
      dataTask: URLSessionDataTask,
      didReceive response: URLResponse,
      completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
      if let request = directRequests[dataTask.taskIdentifier] {
        let ok = ((response as? HTTPURLResponse)?.statusCode ?? 0) == 206
        if !ok { finish(request, error: Self.error("Unexpected response to a range request")) }
        completionHandler(ok ? .allow : .cancel)
        return
      }
      guard dataTask === task, let http = response as? HTTPURLResponse else {
        completionHandler(.cancel)
        return
      }
      switch http.statusCode {
      case 206:
        if contentLength == nil { contentLength = Self.totalLength(fromContentRange: http) }
      case 200:
        // A full response (the server ignored the range): start the file over.
        downloaded = 0
        try? writer?.close()
        writer = nil
        try? reader?.close()
        reader = nil
        contentLength = http.expectedContentLength > 0 ? http.expectedContentLength : nil
      default:
        fail(Self.error("HTTP \(http.statusCode)"))
        completionHandler(.cancel)
        return
      }
      if contentType == nil { contentType = Self.uti(forMIME: http.mimeType, url: remoteURL) }
      guard contentLength != nil else {
        fail(Self.error("Unknown length"))
        completionHandler(.cancel)
        return
      }
      if writer == nil {
        if downloaded == 0 || !FileManager.default.fileExists(atPath: partURL.path) {
          downloaded = 0
          FileManager.default.createFile(atPath: partURL.path, contents: nil)
        }
        writer = try? FileHandle(forWritingTo: partURL)
        if downloaded == 0 { try? writer?.truncate(atOffset: 0) }
      }
      guard writer != nil else {
        fail(Self.error("Couldn't open the cache file"))
        completionHandler(.cancel)
        return
      }
      completionHandler(.allow)
      serve()
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
      if let request = directRequests[dataTask.taskIdentifier] {
        request.dataRequest?.respond(with: data)
        return
      }
      guard dataTask === task, let writer else { return }
      do {
        try writer.seekToEnd()
        try writer.write(contentsOf: data)
        downloaded += Int64(data.count)
      } catch {
        fail(error)
        dataTask.cancel()
        return
      }
      serve()
    }

    func urlSession(_ session: URLSession, task finished: URLSessionTask, didCompleteWithError error: Error?) {
      if let request = directRequests.removeValue(forKey: finished.taskIdentifier) {
        directTasks[ObjectIdentifier(request)] = nil
        finish(request, error: error)
        return
      }
      guard finished === task else { return }
      task = nil
      if let contentLength, downloaded >= contentLength {
        try? writer?.close()
        writer = nil
        try? FileManager.default.removeItem(at: finalURL)
        do {
          try FileManager.default.moveItem(at: partURL, to: finalURL)
          isComplete = true
          Logger.module.info(
            "Express media cache: saved \(self.remoteURL.lastPathComponent, privacy: .public) (\(contentLength, privacy: .public) bytes)"
          )
          cache?.didFinish(remoteURL)
        } catch {
          fail(error)
        }
      } else if let error, (error as NSError).code != NSURLErrorCancelled {
        fail(error)
      }
      serve()
    }

    // MARK: Answering the player

    private var readURL: URL { isComplete ? finalURL : partURL }

    private func serve() {
      // Works on a copy, so nothing that runs while answering can change the list underneath.
      let requests = pending
      var done: [AVAssetResourceLoadingRequest] = []
      for request in requests where answer(request) {
        done.append(request)
      }
      pending.removeAll { request in done.contains { $0 === request } }
      scheduleIdleCheckIfNeeded()
    }

    /// Answers what it can of `request`; true when the request is finished with.
    private func answer(_ request: AVAssetResourceLoadingRequest) -> Bool {
      if request.isFinished || request.isCancelled { return true }
      if let info = request.contentInformationRequest, let contentLength {
        info.contentType = contentType ?? UTType.mpeg4Movie.identifier
        info.contentLength = contentLength
        info.isByteRangeAccessSupported = true
      }
      guard let dataRequest = request.dataRequest else {
        if contentLength != nil {
          request.finishLoading()
          return true
        }
        return failIfFailed(request)
      }
      guard let contentLength else { return failIfFailed(request) }
      let start = dataRequest.currentOffset
      let end =
        dataRequest.requestsAllDataToEndOfResource
        ? contentLength : min(contentLength, dataRequest.requestedOffset + Int64(dataRequest.requestedLength))
      if !isComplete, failure == nil, start > downloaded + Self.directThreshold {
        fetchDirectly(request, from: start, to: end)
        return true
      }
      let available = min(downloaded, end)
      // In pieces, so a long video isn't read into memory at once.
      while dataRequest.currentOffset < available,
        let data = read(from: dataRequest.currentOffset, count: available - dataRequest.currentOffset),
        !data.isEmpty
      {
        dataRequest.respond(with: data)
      }
      if dataRequest.currentOffset >= end {
        request.finishLoading()
        return true
      }
      return failIfFailed(request)
    }

    private func read(from offset: Int64, count: Int64) -> Data? {
      // Opened once: moving the finished file keeps the same file, so the handle stays valid.
      if reader == nil { reader = try? FileHandle(forReadingFrom: readURL) }
      guard let reader else { return nil }
      do {
        try reader.seek(toOffset: UInt64(offset))
        return try reader.read(upToCount: Int(min(count, 2 * 1024 * 1024)))
      } catch {
        return nil
      }
    }

    private func fetchDirectly(_ request: AVAssetResourceLoadingRequest, from start: Int64, to end: Int64) {
      var urlRequest = URLRequest(url: remoteURL)
      urlRequest.setValue("bytes=\(start)-\(end - 1)", forHTTPHeaderField: "Range")
      let task = session.dataTask(with: urlRequest)
      directTasks[ObjectIdentifier(request)] = task
      directRequests[task.taskIdentifier] = request
      task.resume()
    }

    private func finish(_ request: AVAssetResourceLoadingRequest, error: Error?) {
      guard !request.isFinished, !request.isCancelled else { return }
      if let error, (error as NSError).code != NSURLErrorCancelled {
        request.finishLoading(with: error)
      } else {
        request.finishLoading()
      }
    }

    /// Ends a request that can't be answered because the download failed; keeps it otherwise.
    private func failIfFailed(_ request: AVAssetResourceLoadingRequest) -> Bool {
      guard let failure else { return false }
      request.finishLoading(with: failure)
      return true
    }

    private func fail(_ error: Error) {
      Logger.module.error(
        "Express media cache: download failed for \(self.remoteURL.lastPathComponent, privacy: .public): \(error.localizedDescription, privacy: .public)"
      )
      failure = error
      try? writer?.close()
      writer = nil
      try? reader?.close()
      reader = nil
      downloaded = 0
      try? FileManager.default.removeItem(at: partURL)
      cache?.didFail(remoteURL, loader: self)
    }

    // MARK: Helpers

    static func totalLength(fromContentRange response: HTTPURLResponse) -> Int64? {
      guard let range = response.value(forHTTPHeaderField: "Content-Range"),
        let total = range.split(separator: "/").last
      else { return nil }
      return Int64(total)
    }

    static func uti(forMIME mime: String?, url: URL) -> String {
      if let mime, let type = UTType(mimeType: mime), type.conforms(to: .audiovisualContent) {
        return type.identifier
      }
      if let type = UTType(filenameExtension: url.pathExtension), type.conforms(to: .audiovisualContent) {
        return type.identifier
      }
      return UTType.mpeg4Movie.identifier
    }

    static func error(_ message: String) -> NSError {
      NSError(domain: "ExpressMediaCache", code: 1, userInfo: [NSLocalizedDescriptionKey: message])
    }
  }

  /// Forwards the session's callbacks to a loader without keeping it alive.
  private final class SessionProxy: NSObject, URLSessionDataDelegate, @unchecked Sendable {
    weak var loader: Loader?

    init(loader: Loader) {
      self.loader = loader
    }

    func urlSession(
      _ session: URLSession,
      dataTask: URLSessionDataTask,
      didReceive response: URLResponse,
      completionHandler: @escaping (URLSession.ResponseDisposition) -> Void
    ) {
      guard let loader else { return completionHandler(.cancel) }
      loader.urlSession(session, dataTask: dataTask, didReceive: response, completionHandler: completionHandler)
    }

    func urlSession(_ session: URLSession, dataTask: URLSessionDataTask, didReceive data: Data) {
      loader?.urlSession(session, dataTask: dataTask, didReceive: data)
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
      loader?.urlSession(session, task: task, didCompleteWithError: error)
    }
  }
}

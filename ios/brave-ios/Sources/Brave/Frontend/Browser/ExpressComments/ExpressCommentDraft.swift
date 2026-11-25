// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import AVFoundation
import ExpressCore
import ImageIO
import PhotosUI
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// A file waiting in the comment box (Android's `CommentDraftItems`): a local copy, already in
/// the form it will be uploaded, with its measurements.
struct ExpressDraftItem: Identifiable, Equatable {
  let id = UUID()
  var kind: ExpressAttachmentKind
  /// A private copy in `tmp/comment-media`.
  let fileURL: URL
  var mime: String
  var size: Int64
  var name: String?
  var width = 0
  var height = 0
  var durationMs = 0
  var thumbnail: UIImage?

  static func == (lhs: ExpressDraftItem, rhs: ExpressDraftItem) -> Bool { lhs.id == rhs.id }

  var isVisual: Bool { kind == .image || kind == .gif || kind == .video }

  /// "Photo", "GIF", "Video", "Voice note", or the file's name.
  var label: String {
    switch kind {
    case .image: return "Photo"
    case .gif: return "GIF"
    case .video: return "Video"
    case .audio: return "Voice note"
    case .document: return name ?? "Document"
    }
  }

  var asAttachment: ExpressCommentAttachment {
    ExpressCommentAttachment(
      kind: kind,
      url: fileURL,
      name: name,
      width: width,
      height: height,
      durationMs: durationMs,
      mime: mime,
      size: size
    )
  }
}

/// Limits and messages from Android's composer.
enum ExpressDraftLimits {
  static let maxFiles = 10
  static let maxVideoBytes: Int64 = 200 * 1024 * 1024
  static let maxFileBytes: Int64 = 20 * 1024 * 1024
  static let maxImageEdge: CGFloat = 1920
  static let gifMaxBytes = 15 * 1024 * 1024

  static let tooMany = "A comment can carry up to 10 files."
  static let tooLarge = "That file is too large to attach."
  static let documentTooLarge = "Documents can be up to 20 MB."
  static let unsupportedType = "This file type can’t be attached."
  static let unreadable = "Couldn’t add that file."

  /// Android's document whitelist.
  static let documentTypes: [UTType] = [
    .pdf, .rtf, .plainText, .commaSeparatedText,
    UTType("com.microsoft.word.doc"), UTType("org.openxmlformats.wordprocessingml.document"),
    UTType("com.microsoft.excel.xls"), UTType("org.openxmlformats.spreadsheetml.sheet"),
    UTType("com.microsoft.powerpoint.ppt"), UTType("org.openxmlformats.presentationml.presentation"),
    UTType("org.oasis-open.opendocument.text"), UTType("org.oasis-open.opendocument.spreadsheet"),
    UTType("org.oasis-open.opendocument.presentation"),
  ].compactMap { $0 }

  static let documentMimes: Set<String> = [
    "application/pdf", "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
    "application/vnd.oasis.opendocument.text", "application/vnd.oasis.opendocument.spreadsheet",
    "application/vnd.oasis.opendocument.presentation",
    "application/rtf", "text/plain", "text/csv", "text/rtf",
  ]
}

enum ExpressDraftError: LocalizedError {
  case message(String)
  var errorDescription: String? {
    switch self {
    case .message(let text): return text
    }
  }
}

/// Turns picked things into draft items.
enum ExpressDraftFactory {
  static var directory: URL {
    let url = FileManager.default.temporaryDirectory.appendingPathComponent("comment-media", isDirectory: true)
    try? FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
    return url
  }

  static func newFileURL(prefix: String, ext: String) -> URL {
    directory.appendingPathComponent("\(prefix)_\(Int(Date().timeIntervalSince1970 * 1000))_\(UUID().uuidString.prefix(6)).\(ext)")
  }

  // MARK: Photos

  /// A photo from the library or camera: upright, at most 1920 px on its long edge, JPEG 0.8.
  /// (Android's power-of-two sampling barely resizes phone photos; this caps them properly.)
  static func photo(_ image: UIImage, name: String?) throws -> ExpressDraftItem {
    let scaled = scaledUpright(image, maxEdge: ExpressDraftLimits.maxImageEdge)
    guard let data = scaled.jpegData(compressionQuality: 0.8) else {
      throw ExpressDraftError.message(ExpressDraftLimits.unreadable)
    }
    let url = newFileURL(prefix: "photo", ext: "jpg")
    try data.write(to: url)
    let pixels = CGSize(width: scaled.size.width * scaled.scale, height: scaled.size.height * scaled.scale)
    return ExpressDraftItem(
      kind: .image,
      fileURL: url,
      mime: "image/jpeg",
      size: Int64(data.count),
      name: name ?? url.lastPathComponent,
      width: Int(pixels.width),
      height: Int(pixels.height),
      thumbnail: thumbnail(of: scaled)
    )
  }

  /// A GIF kept byte for byte (Android never re-encodes GIFs).
  static func gif(data: Data, name: String?) throws -> ExpressDraftItem {
    guard Int64(data.count) <= ExpressDraftLimits.maxFileBytes else {
      throw ExpressDraftError.message(ExpressDraftLimits.tooLarge)
    }
    let url = newFileURL(prefix: "gif", ext: "gif")
    try data.write(to: url)
    var width = 0
    var height = 0
    var first: UIImage?
    if let source = CGImageSourceCreateWithData(data as CFData, nil) {
      if let properties = CGImageSourceCopyPropertiesAtIndex(source, 0, nil) as? [CFString: Any] {
        width = (properties[kCGImagePropertyPixelWidth] as? Int) ?? 0
        height = (properties[kCGImagePropertyPixelHeight] as? Int) ?? 0
      }
      if let frame = CGImageSourceCreateImageAtIndex(source, 0, nil) {
        first = UIImage(cgImage: frame)
      }
    }
    return ExpressDraftItem(
      kind: .gif,
      fileURL: url,
      mime: "image/gif",
      size: Int64(data.count),
      name: name ?? url.lastPathComponent,
      width: width,
      height: height,
      thumbnail: first.map(thumbnail(of:))
    )
  }

  // MARK: Videos

  /// A video copied as is (no transcoding, like Android), up to 200 MB.
  static func video(copying source: URL, name: String?) async throws -> ExpressDraftItem {
    let size = (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    guard size <= ExpressDraftLimits.maxVideoBytes else {
      throw ExpressDraftError.message(ExpressDraftLimits.tooLarge)
    }
    let ext = source.pathExtension.isEmpty ? "mov" : source.pathExtension.lowercased()
    let url = newFileURL(prefix: "video", ext: ext)
    try FileManager.default.copyItem(at: source, to: url)
    let asset = AVURLAsset(url: url)
    var width = 0
    var height = 0
    if let track = try? await asset.loadTracks(withMediaType: .video).first,
      let loaded = try? await track.load(.naturalSize, .preferredTransform)
    {
      let upright = loaded.0.applying(loaded.1)
      width = Int(abs(upright.width))
      height = Int(abs(upright.height))
    }
    let duration = (try? await asset.load(.duration)).map { CMTimeGetSeconds($0) } ?? 0
    let generator = AVAssetImageGenerator(asset: asset)
    generator.appliesPreferredTrackTransform = true
    generator.maximumSize = CGSize(width: 480, height: 480)
    let still = try? await generator.image(at: .zero).image
    let mime = UTType(filenameExtension: ext)?.preferredMIMEType ?? "video/quicktime"
    return ExpressDraftItem(
      kind: .video,
      fileURL: url,
      mime: mime,
      size: size,
      name: name ?? source.lastPathComponent,
      width: width,
      height: height,
      durationMs: duration.isFinite ? Int(duration * 1000) : 0,
      thumbnail: still.map { UIImage(cgImage: $0) }
    )
  }

  // MARK: Documents

  /// A document from Files, checked against Android's types and 20 MB.
  static func document(copying source: URL) throws -> ExpressDraftItem {
    let type = UTType(filenameExtension: source.pathExtension)
    let mime = type?.preferredMIMEType ?? "application/octet-stream"
    guard ExpressDraftLimits.documentMimes.contains(mime) else {
      throw ExpressDraftError.message(ExpressDraftLimits.unsupportedType)
    }
    let size = (try? source.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    guard size <= ExpressDraftLimits.maxFileBytes else {
      throw ExpressDraftError.message(ExpressDraftLimits.documentTooLarge)
    }
    let url = directory.appendingPathComponent("\(UUID().uuidString.prefix(8))_\(source.lastPathComponent)")
    try FileManager.default.copyItem(at: source, to: url)
    return ExpressDraftItem(
      kind: .document,
      fileURL: url,
      mime: mime,
      size: size,
      name: source.lastPathComponent
    )
  }

  // MARK: Voice notes

  static func voiceNote(at url: URL, durationMs: Int) -> ExpressDraftItem {
    let size = (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    return ExpressDraftItem(
      kind: .audio,
      fileURL: url,
      mime: "audio/mp4",
      size: size,
      name: url.lastPathComponent,
      durationMs: durationMs
    )
  }

  // MARK: Library picks

  /// Reads one library pick: GIFs stay GIFs, other images become JPEG, videos are copied.
  static func item(from pick: PhotosPickerItem) async throws -> ExpressDraftItem {
    let types = pick.supportedContentTypes
    if types.contains(where: { $0.conforms(to: .movie) }) {
      guard let movie = try await pick.loadTransferable(type: ExpressPickedMovie.self) else {
        throw ExpressDraftError.message(ExpressDraftLimits.unreadable)
      }
      defer { try? FileManager.default.removeItem(at: movie.url) }
      return try await video(copying: movie.url, name: nil)
    }
    guard let data = try await pick.loadTransferable(type: Data.self) else {
      throw ExpressDraftError.message(ExpressDraftLimits.unreadable)
    }
    if types.contains(.gif) || isGIF(data) {
      return try gif(data: data, name: nil)
    }
    guard Int64(data.count) <= ExpressDraftLimits.maxFileBytes * 3,
      let image = UIImage(data: data)
    else {
      throw ExpressDraftError.message(ExpressDraftLimits.unreadable)
    }
    return try photo(image, name: nil)
  }

  static func isGIF(_ data: Data) -> Bool {
    data.prefix(6).elementsEqual(Data("GIF89a".utf8)) || data.prefix(6).elementsEqual(Data("GIF87a".utf8))
  }

  // MARK: Image helpers

  static func scaledUpright(_ image: UIImage, maxEdge: CGFloat) -> UIImage {
    let pixelSize = CGSize(width: image.size.width * image.scale, height: image.size.height * image.scale)
    let longEdge = max(pixelSize.width, pixelSize.height)
    let factor = longEdge > maxEdge ? maxEdge / longEdge : 1
    let target = CGSize(width: (pixelSize.width * factor).rounded(), height: (pixelSize.height * factor).rounded())
    let format = UIGraphicsImageRendererFormat.default()
    format.scale = 1
    format.opaque = true
    // Drawing applies the photo's orientation, so the result is upright with no EXIF.
    return UIGraphicsImageRenderer(size: target, format: format).image { _ in
      image.draw(in: CGRect(origin: .zero, size: target))
    }
  }

  static func thumbnail(of image: UIImage) -> UIImage {
    scaledUpright(image, maxEdge: 360)
  }

  /// "18.6 MB", "2.1 MB", "640 KB".
  static func sizeText(_ bytes: Int64) -> String {
    ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
  }

  /// `mm:ss`, or `h:mm:ss` past an hour.
  static func durationText(milliseconds: Int) -> String {
    let total = max(0, milliseconds / 1000)
    let hours = total / 3600
    let minutes = (total % 3600) / 60
    let seconds = total % 60
    if hours > 0 { return String(format: "%d:%02d:%02d", hours, minutes, seconds) }
    return String(format: "%02d:%02d", minutes, seconds)
  }
}

/// A library video, delivered as a file the picker lends us; copied before it goes away.
struct ExpressPickedMovie: Transferable {
  let url: URL

  static var transferRepresentation: some TransferRepresentation {
    FileRepresentation(contentType: .movie) { movie in
      SentTransferredFile(movie.url)
    } importing: { received in
      let ext = received.file.pathExtension.isEmpty ? "mov" : received.file.pathExtension
      let copy = FileManager.default.temporaryDirectory
        .appendingPathComponent("picked_\(UUID().uuidString).\(ext)")
      try FileManager.default.copyItem(at: received.file, to: copy)
      return ExpressPickedMovie(url: copy)
    }
  }
}

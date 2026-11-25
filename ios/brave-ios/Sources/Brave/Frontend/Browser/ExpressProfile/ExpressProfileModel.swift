// Copyright (c) 2026 The Express Browser Authors. All rights reserved.
// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this file,
// You can obtain one at https://mozilla.org/MPL/2.0/.

import Combine
import ExpressCore
import Foundation
import os.log

/// The reader's own profile: the toolbar avatar and the Profile screen's data, after Android's
/// `ProfileAvatar` and `BrowserExpressProfileApi`.
///
/// The avatar shows at once from the device's cache (only for the account that saved it), and
/// `GET /user/me` refreshes it at most every 30 minutes, or straight away when the account
/// changes. The Profile screen always refreshes when it opens.
@MainActor
final class ExpressProfileModel: ObservableObject {
  static let shared = ExpressProfileModel()

  @Published private(set) var me: ExpressMe?
  /// The reader's avatar, or nil when signed out.
  @Published private(set) var avatarURL: URL?
  @Published private(set) var isSignedIn = false
  /// The last refresh failed and nothing has loaded yet.
  @Published private(set) var loadFailed = false

  private let account: ExpressAccount
  private let client: ExpressAPIClient
  private var lastFetch: Date?
  private var fetchTask: Task<ExpressMe?, Never>?
  private var userID: String?
  private var cancellable: AnyCancellable?

  init(account: ExpressAccount = .shared, client: ExpressAPIClient = .shared) {
    self.account = account
    self.client = client
    syncAccount()
    cancellable = account.$accessToken
      .dropFirst()
      .removeDuplicates()
      .receive(on: RunLoop.main)
      .sink { [weak self] _ in
        guard let self else { return }
        self.syncAccount()
        Task { await self.refresh() }
        ExpressPushManager.shared.syncIfNeeded(reason: "login")
        Task {
          await ExpressReferralTracker.shared.sendPending()
          await ExpressReferralTracker.shared.linkDeviceIfNeeded()
          await ExpressYouTubeUsage.shared.flush()
        }
      }
  }

  /// The signed-in account's claims, for the name and handle before `/user/me` answers.
  var claims: ExpressJWT? { account.claims }

  /// Whether the account has a verified phone: the server says so, or it was verified here.
  /// Guests (every account outside India until now) don't.
  var hasPhone: Bool {
    if me?.phone != nil { return true }
    guard let userID else { return false }
    return UserDefaults.standard.bool(forKey: Self.phoneVerifiedKey(userID))
  }

  static func markPhoneVerified(userID: String) {
    UserDefaults.standard.set(true, forKey: phoneVerifiedKey(userID))
  }

  private static func phoneVerifiedKey(_ userID: String) -> String {
    "express.phone_verified.\(userID)"
  }

  /// Refreshes if the last refresh is more than 30 minutes old (Android's toolbar rule).
  func refreshIfStale() {
    guard isSignedIn else { return }
    if let lastFetch, Date().timeIntervalSince(lastFetch) < 30 * 60 { return }
    Task { await refresh() }
  }

  /// `GET /user/me`. Concurrent calls share one request.
  @discardableResult
  func refresh() async -> ExpressMe? {
    if let fetchTask { return await fetchTask.value }
    guard let token = account.validAccessToken else {
      syncAccount()
      return nil
    }
    let task = Task { @MainActor [weak self] () -> ExpressMe? in
      guard let self else { return nil }
      do {
        let me = try await self.client.fetchMe(accessToken: token)
        let owner = ExpressJWT(token: token)?.userID
        ExpressSelfAvatar.save(me.rawAvatar, for: owner)
        // Ignore an answer for an account that has since signed out or changed.
        guard owner == self.userID else { return nil }
        self.me = me
        self.loadFailed = false
        self.lastFetch = Date()
        self.avatarURL = ExpressSelfAvatar.url(for: owner)
        self.syncKarmaLabel(me, userID: owner, token: token)
        return me
      } catch {
        Logger.module.error("Express profile load failed: \(error.localizedDescription, privacy: .public)")
        self.loadFailed = self.me == nil
        return nil
      }
    }
    fetchTask = task
    let result = await task.value
    fetchTask = nil
    return result
  }

  /// Tells the server which scores the reader has now seen (Android does this as soon as the
  /// numbers arrive), so the next visit counts up from here.
  func markScoresSeen(_ scores: ExpressScores) {
    guard let token = account.validAccessToken else { return }
    Task { await client.markScoresSeen(scores, accessToken: token) }
  }

  /// After Edit profile saves the name or username: the new tokens carry the new claims.
  func store(accessToken: String, refreshToken: String?) {
    account.store(accessToken: accessToken, refreshToken: refreshToken)
  }

  /// Logout: takes this device's notifications off the account, then forgets the account here.
  func signOut() async {
    if account.validAccessToken != nil { ExpressAnalytics.shared.capture("Logout") }
    await ExpressPushManager.shared.unregister()
    ExpressSelfAvatar.clear()
    account.signOut()
    me = nil
    lastFetch = nil
    syncAccount()
  }

  // MARK: Private

  private func syncAccount() {
    let claims = account.claims
    isSignedIn = claims != nil
    if claims?.userID != userID {
      userID = claims?.userID
      me = nil
      lastFetch = nil
      loadFailed = false
    }
    avatarURL = ExpressSelfAvatar.url(for: userID)
  }

  /// Either side having seen "+1 Karma" counts; the server is told if only this device knew.
  private func syncKarmaLabel(_ me: ExpressMe, userID: String?, token: String) {
    guard let userID else { return }
    let key = ExpressKarmaModel.labelSeenKey(for: userID)
    let seenHere = UserDefaults.standard.bool(forKey: key)
    if me.hasSeenKarmaLabel && !seenHere {
      UserDefaults.standard.set(true, forKey: key)
    } else if seenHere && !me.hasSeenKarmaLabel {
      Task { await client.markKarmaLabelSeen(accessToken: token) }
    }
  }
}

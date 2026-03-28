# YouTube Comments — Backend Integration Spec

**Feature:** Merge YouTube comments with our own comment layer
**Prepared for:** Backend team
**Context:** The Android app now fetches and displays YouTube comments via the YouTube Data API v3. We want to overlay our own interaction layer (upvotes, downvotes, replies) on top of those comments without scraping or bulk-importing YouTube data. Only comments that a user actually interacts with ever get written to our DB.

---

## Core Concept

When a user opens a YouTube video in the browser, the app:

1. Fetches comments from the YouTube API (read-only, no auth required)
2. Fetches our own stored comments for that page URL from our API (initially empty)
3. Merges the two lists — if a YouTube comment also exists in our DB (matched by `youtubeId`), our version takes precedence (it has real vote counts, replies, user vote state, etc.)

When a user interacts with a YouTube-sourced comment for the first time (vote or reply):

1. The app detects the comment has no `_id` in our system yet (only a `youtubeId`)
2. Calls the new **register** endpoint, passing the YouTube comment's data
3. Backend upserts a record — creates if not exists, returns existing `_id` if already registered by another user
4. App receives our `_id`, updates the in-memory comment object
5. Proceeds with the normal vote/reply flow using that `_id` — no other code path changes

---

## Schema Changes

### `Comment` collection — new fields

| Field | Type | Required | Description |
|---|---|---|---|
| `youtubeId` | `String` | No | The YouTube comment ID (e.g. `UgxA3kD...`). Null for non-YouTube comments. |
| `youtubeAuthorName` | `String` | No | Snapshot of the author display name at time of import. |
| `youtubeAvatarUrl` | `String` | No | Snapshot of the author profile image URL at time of import. |

**No `youtubeVideoId` field needed.** The full page URL is stored in the existing `pageParent` field (e.g. `https://www.youtube.com/watch?v=dQw4w9WgXcQ`). This is consistent with how all other comments are stored — by page URL, not by a platform-specific ID. Queries by page URL work identically for YouTube and non-YouTube pages.

**Index:** Add a unique sparse index on `youtubeId` so concurrent registrations never produce duplicates.

```
db.comments.createIndex({ youtubeId: 1 }, { unique: true, sparse: true })
```

**No other fields change.** `upvoteCount`, `downvoteCount`, `commentCount`, `user`, `content`, `commentParent`, `pageParent` — all behave identically to normal comments.

---

## New Endpoints

### 1. `GET /v1/comment/youtube`

Fetch all comments in our DB that belong to a specific YouTube page. Used on page load alongside the YouTube API fetch so the app can merge the two lists.

**Query parameters**

| Param | Type | Required | Description |
|---|---|---|---|
| `pageUrl` | `String` | Yes | Full page URL, e.g. `https://www.youtube.com/watch?v=dQw4w9WgXcQ` |
| `accessToken` | `String` | No | If provided, include the requesting user's vote state (`didVote`) on each comment |

**Backend query**

```js
Comment.find({
  pageParent: pageUrl,
  youtubeId:  { $exists: true, $ne: null },
  commentParent: null    // top-level only; replies fetched via existing reply endpoint
})
.sort({ trendingScore: -1 })
```

Using `pageUrl` as the lookup key means this is just a normal `pageParent` query with an extra filter — no new index needed beyond the existing `pageParent` index.

**Response `200 OK`**

```json
{
  "success": true,
  "comments": [
    {
      "_id": "64ab12...",
      "youtubeId": "UgxA3kD...",
      "pageParent": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
      "content": "this is incredible",
      "youtubeAuthorName": "SomeUser",
      "youtubeAvatarUrl": "https://yt3.ggpht.com/...",
      "upvoteCount": 4,
      "downvoteCount": 1,
      "commentCount": 2,
      "didVote": { "type": "upvote" },
      "trendingScore": 12
    }
  ]
}
```

- If no comments exist yet for this `pageUrl`, return `"comments": []` — this is the expected initial state.
- Sort by `trendingScore DESC` so our most-engaged comments surface near the top when merged.
- **Make sure `youtubeId` is included in the response** — the app uses it to match against the YouTube API's comment IDs during the merge step.

---

### 2. `POST /v1/comment/yt_interact`

Registers the full chain of YouTube ancestor comments in our system (if not already present) and records the user's interaction, all in a single atomic operation. Called only when a YouTube comment involved in the interaction does not yet exist in our DB. For subsequent interactions the comment already has a `_id` and the existing `/v1/vote` and `/v1/comment` endpoints are used directly.

**Why combined instead of separate register + interact calls?**
Splitting into two calls creates a failure window — ancestors could be registered but the interaction lost if the second call fails, leaving orphaned records. A single call is atomic, saves round trips, and the operations are always causally linked.

**Why an `ancestors` array?**

YouTube has exactly 2 levels of nesting (top-level comments and their direct replies — YouTube itself does not allow deeper nesting). This means the chain from root to interaction target is always at most 2 elements. The `ancestors` array carries every YouTube comment in that chain that may need to be registered, root first.

All scenarios this covers:

| Interaction | `ancestors` length | Records possibly created |
|---|---|---|
| Vote / reply on top-level comment | 1 | 1 (the top-level comment) |
| Vote on a YouTube reply | 2 | up to 2 (top-level + the reply) |
| Reply to a YouTube reply | 2 | up to 2 (top-level + the reply) + 1 new reply |

In every case the backend does at most 2 upserts before recording the interaction.

**Request body**

```json
{
  "pageUrl": "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
  "ancestors": [
    {
      "youtubeId": "UgxTopLevel...",
      "content": "this is incredible",
      "youtubeAuthorName": "SomeUser",
      "youtubeAvatarUrl": "https://yt3.ggpht.com/..."
    },
    {
      "youtubeId": "UgxReply...",
      "content": "totally agree",
      "youtubeAuthorName": "AnotherUser",
      "youtubeAvatarUrl": "https://yt3.ggpht.com/..."
    }
  ],
  "interactionType": "reply",
  "replyContent": "same here!"
}
```

`ancestors[0]` is always the top-level YouTube comment (root). `ancestors[1]`, when present, is the direct reply the user is interacting with. The interaction always targets the **last** element in the array.

| Field | Type | Required | Description |
|---|---|---|---|
| `pageUrl` | `String` | Yes | Full page URL — stored as `pageParent` on all created records |
| `ancestors` | `Array` | Yes | 1 or 2 YouTube comment objects, root first. Each object must have `youtubeId`, `content`, `youtubeAuthorName`. `youtubeAvatarUrl` is optional. |
| `interactionType` | `String` | Yes | `"upvote"`, `"downvote"`, or `"reply"` |
| `replyContent` | `String` | Only when `interactionType = "reply"` | Text of the new reply |

**Server logic**

```js
let parentId = null

// Step 1 — upsert each ancestor in chain order (root first)
for (const ancestor of body.ancestors) {
  const doc = await Comment.findOneAndUpdate(
    { youtubeId: ancestor.youtubeId },
    {
      $setOnInsert: {
        youtubeId:         ancestor.youtubeId,
        pageParent:        body.pageUrl,
        content:           ancestor.content,
        youtubeAuthorName: ancestor.youtubeAuthorName,
        youtubeAvatarUrl:  ancestor.youtubeAvatarUrl,
        commentParent:     parentId,      // null for root, _id of root for reply
        upvoteCount:       0,
        downvoteCount:     0,
        commentCount:      0,
        trendingScore:     0,
        user:              null,
      }
    },
    { upsert: true, new: true }
  )
  parentId = doc._id    // next ancestor's commentParent points here
}

// parentId is now the _id of the interaction target (last ancestor)
const targetId = parentId

// Step 2 — record the interaction on the target
if (body.interactionType === "upvote" || body.interactionType === "downvote") {
  await recordVote(targetId, requestingUser._id, body.interactionType)
}

if (body.interactionType === "reply") {
  await createComment({
    content:       body.replyContent,
    commentParent: targetId,
    pageParent:    body.pageUrl,
    user:          requestingUser._id,
  })
}
```

Reuse the existing `recordVote` and `createComment` internal functions — do not duplicate their logic.

**Response `200 OK`**

```json
{
  "success": true,
  "resolvedIds": {
    "UgxTopLevel...": "64ab12...",
    "UgxReply...":    "64cd34..."
  },
  "targetId": "64cd34...",
  "upvoteCount": 1,
  "downvoteCount": 0,
  "commentCount": 0,
  "didVote": { "type": "upvote" }
}
```

`resolvedIds` maps every `youtubeId` in the request to its `_id` in our DB. The app caches these so that if any ancestor appears again in the UI (e.g. the top-level comment is still visible in the list), its `_id` is already known and `yt_interact` is not called again for it.

`targetId` is the `_id` of the comment the interaction was recorded on (the last ancestor).

**Response `400`** if `ancestors` is empty, `pageUrl` is missing, or `interactionType = "reply"` with blank `replyContent`.

**Response `401`** if the user is not authenticated. No guest interactions on YouTube comments.

---

## Client-Side First-Interaction Flow

> Included so the backend team understands the exact call sequence and can write integration tests accordingly.

```
User taps upvote / downvote / reply on a comment
                    │
                    ▼
     Does the interaction TARGET have a _id?
                    │
        YES ────────┼──► use existing endpoints directly:
                    │       vote   → POST /v1/vote    { _id, type }
                    │       reply  → POST /v1/comment { commentParent: _id, content }
                    │
        NO ─────────┼──► Build ancestors array:
                    │       - If interacting with a top-level YouTube comment:
                    │           ancestors = [ topLevelComment ]
                    │       - If interacting with a YouTube reply:
                    │           ancestors = [ topLevelComment, theReply ]
                    │         (top-level comment is always available in the UI
                    │          since it's shown at position 0 in the reply sheet)
                    │
                    ▼
        POST /v1/comment/yt_interact  {
            pageUrl, ancestors, interactionType, replyContent?
        }
                    │
                    ▼
        Receive { resolvedIds, targetId, upvoteCount, downvoteCount, didVote }
        Cache resolvedIds: for each youtubeId → _id, update all
            matching in-memory comment objects so subsequent
            interactions on any of those comments take the YES path
        Update UI
```

**If `yt_interact` fails:** show an error toast. Do not retry silently. In-memory comments remain without `_id` and the user can try again.

**Subsequent interactions** (e.g. user already upvoted, now wants to remove it): the target comment now has `_id` in memory, so the YES path is taken and the existing endpoints handle it unchanged.

---

## Subsequent Interaction Flow (no new endpoints)

Once `yt_interact` has established our `_id`, all subsequent interactions use the existing endpoints unchanged:

| Action | Endpoint | Notes |
|---|---|---|
| Change / remove vote | `POST /v1/vote` with `_id` | No change |
| Add reply | `POST /v1/comment` with `commentParent = _id` | No change |
| Fetch replies | `GET /v1/comment?commentId=_id` | No change |

The `_id` is stored on the in-memory comment object for the session lifetime. Across sessions, `GET /v1/comment/youtube` already returns our version of the comment with `_id` set, so `yt_interact` is never called again for that comment.

---

## Merge Logic (client-side, for reference)

```
youtubeComments  = YouTube Data API response
ourComments      = GET /v1/comment/youtube?pageUrl=<currentPageUrl>

// Build a lookup map from our DB results
index = Map { comment.youtubeId → comment }   // built from ourComments

finalList = []

for each ytComment in youtubeComments:
    if index.has(ytComment.id):
        finalList.add(index[ytComment.id])   // our version wins (has real counts + _id)
    else:
        finalList.add(ytComment)             // YouTube-only, no _id yet

// Inject any of our comments not present in the current YouTube page
// (e.g. YouTube is paginated and this comment is on a later page,
//  but a user in our app already interacted with it)
for each ourComment in ourComments:
    if ourComment not already in finalList:
        insert at position sorted by trendingScore
```

---

## Edge Cases

### Concurrent registration
Two users interact with the same YouTube comment at the same time before either has registered it. The unique sparse index on `youtubeId` combined with `$setOnInsert` guarantees exactly one document is created. The user whose write lost the race gets the existing document back from `findOneAndUpdate` with `new: true`. Both receive the correct `_id`. No duplicates, no errors.

### YouTube comment deleted after registration
Our record persists. The app will see it in `GET /v1/comment/youtube` but not in the YouTube API response. The merge logic injects it into the list. This is correct behaviour — our users' votes and replies on that comment are real data that should remain visible. A `youtubeDeleted: Boolean` flag can be added later if we want to badge such comments in the UI.

### YouTube comment content edited after registration
We store a snapshot. We do not auto-sync. This is acceptable — the snapshot is a reference, not a live mirror, and comment text rarely changes materially. A background sync job can be added later if needed.

### `pageUrl` normalisation
YouTube URLs can appear in multiple forms:
- `https://www.youtube.com/watch?v=dQw4w9WgXcQ`
- `https://m.youtube.com/watch?v=dQw4w9WgXcQ`
- `https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=42s`

The app must strip extra query parameters and normalise to `https://www.youtube.com/watch?v={videoId}` before sending `pageUrl` in any request or query. The backend should also normalise on receipt. Agree on a canonical form before implementation.

### `user` field on registered comments
The YouTube comment has a YouTube author, not one of our users. The `user` field on the registered document should be `null`. The app reads `youtubeAuthorName` and `youtubeAvatarUrl` for display when `user` is null. Existing vote and reply endpoints must not crash when `comment.user` is null — verify this in tests.

---

## Summary of Deliverables

| # | What | Priority |
|---|---|---|
| 1 | Add `youtubeId`, `youtubeAuthorName`, `youtubeAvatarUrl` to Comment schema | High |
| 2 | Unique sparse index on `youtubeId` | High |
| 3 | `GET /v1/comment/youtube?pageUrl=` | High |
| 4 | `POST /v1/comment/yt_interact` — accepts `ancestors` array (max 2), upserts each in chain order, applies interaction to last, returns `{ resolvedIds, targetId, upvoteCount, downvoteCount, commentCount, didVote }` | High |
| 5 | Reuse existing `recordVote` / `createComment` internals inside `yt_interact` — do not duplicate logic | High |
| 6 | Include `youtubeId` in the standard comment response shape | High |
| 7 | Ensure vote + reply endpoints handle `user: null` without errors | High |
| 8 | Normalise `pageUrl` on receipt in both endpoints | Medium |
| 9 | Integration test: concurrent `yt_interact` for same `youtubeId` produces exactly one Comment document | Medium |

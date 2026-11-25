/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import org.chromium.base.Log;
import org.chromium.base.task.AsyncTask;
import org.chromium.chrome.R;
import org.chromium.chrome.browser.app.BraveActivity;
import org.chromium.chrome.browser.app.shimmer.ShimmerFrameLayout;
import org.chromium.chrome.browser.browser_express_walkthrough.WalkthroughReel;
import org.chromium.chrome.browser.crypto_wallet.util.AndroidUtils;
import org.chromium.chrome.browser.settings.PostHogEventKeys;
import org.chromium.chrome.browser.settings.PostHogUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Reel-style comment browser: one full screen per page, swiped vertically.
 *
 * <p>Drop-in alternative to {@link CommentListFragment} — same container, same host contract — so
 * the sheet around it keeps owning the input bar, the upload broadcasts and reply navigation. Only
 * the presentation differs: instead of one long scrolling list, {@link CommentSlidePacker} groups
 * the comments into pages and a vertical {@link ViewPager2} snaps between them.
 *
 * <p>YouTube watch pages deliberately do not use this: there the sheet stays partly open so the
 * video remains visible, and {@link CommentListFragment} is still what gets loaded.
 */
public class CommentReelFragment extends Fragment {
    private static final String TAG = "CommentReel";

    public static final String COMMENTS_FOR = "comments_for";
    public static final String POST_ID = "post_id";
    public static final String OPEN_KEYBOARD = "open_keyboard";

    private static final int PAGE = 1;
    private static final int PER_PAGE = 100;

    /**
     * Longest the first pack waits for the orientation of the videos it opens on, so a portrait
     * video can render as a reel from the very first frame instead of flashing inline and being
     * promoted a beat later. The shimmer covers the wait; past the deadline unresolved videos pack
     * inline and are promoted when their dimensions land (probe or player report).
     *
     * <p>Kept short because the list is ready the moment the API answers: the API sends each
     * video's size (mediaWidth/mediaHeight), and sizes the app has resolved before persist across
     * sessions, so only an old video never seen here can be waited on at all.
     */
    private static final long INITIAL_PROBE_WAIT_MS = 300;

    /**
     * How far either side of the opening comment a video still changes the opening page: one
     * page's worth of comments plus the packer's lookahead, which pulls later ones forward. Videos
     * further out resolve in the background and re-pack without moving the reader.
     */
    private static final int OPENING_PAGE_REACH = CommentSlidePacker.DEFAULT_MAX_COMMENTS_PER_SLIDE
            + CommentSlidePacker.DEFAULT_LOOKAHEAD_WINDOW;

    private static final long HEADER_FADE_MS = 150;

    private ViewPager2 mPager;
    private CommentSlideAdapter mAdapter;
    private ShimmerFrameLayout mShimmerLoading;
    private ViewGroup mShimmerItems;
    private LinearLayout mEmptyContainer;
    /** The header's "how far through" line and "15 / 87" (Figma "Scroll depth"). */
    private View mDepthRow;
    private ProgressBar mDepth;
    private TextView mPosition;
    /** The header's title: the post's "#Hashtag", like the reel pages', else "Comments". */
    private TextView mToolbarTitle;

    private final List<Comment> mComments = new ArrayList<>();
    private BottomSheetInputCallback mInputCallback;
    private EditText mMessageEditText;

    private String mCommentsFor;
    private String mPostId;
    private String mPageUrl;

    private boolean mCommentsLoaded;
    /** Width the last pack was measured against; a change means the pages must be rebuilt. */
    private int mPackedForWidth;
    private int mPackedForHeight;
    private int mPackedForHeaderInset;

    /**
     * The comments header floating over the pager. Text pages start below it; it steps aside
     * while a full-page video is showing, whose own white header reads over the video.
     */
    private View mPageHeader;
    private int mHeaderInset;

    /**
     * The sheet's comment box floats over the bottom of this page the same way. Text pages and
     * the loading and empty states stop above it; a reel runs under it while the sheet slides it
     * away (see {@link BrowserExpressCommentsBottomSheetFragment#setComposerOverReel}).
     */
    private View mOverlay;
    private int mComposerInset;
    private int mPackedForComposerInset;

    /**
     * The first comment of the page the reader was on when this page's view went, e.g. under a
     * replies page. The next pack opens on the page holding it.
     */
    private @Nullable String mRestoreAnchorCommentId;

    /**
     * Counts packs. The second half of a pack that opened on a reel (the pages behind it) checks
     * it, to find out whether another pack replaced the pages in the meantime.
     */
    private int mPackGeneration;

    /** True while the first pack is held for orientation probes; repack() waits it out. */
    private boolean mPackHeldForProbes;
    /** Ids of the opening page's videos the first pack still waits on. */
    private final Set<String> mHeldForCommentIds = new HashSet<>();

    /**
     * The page the pager last came to rest on (its first comment) and how far through the stream
     * that was. A reel page that rests here and isn't that page has just become the reader's
     * page, so its stream position shows, moving on from this count.
     */
    private @Nullable String mSettledAnchor;
    private int mSettledSeen;

    /** NEW takes whose page has come up, reported once each (New Take Viewed). */
    private final Set<String> mViewedNewTakes = new HashSet<>();

    /** The first-launch walkthrough's video step and bonus tip, over this stream. */
    private @Nullable WalkthroughReel mWalkthrough;

    private final WalkthroughReel.Host mWalkthroughHost =
            new WalkthroughReel.Host() {
                @Override
                public boolean isCurrentPageReel() {
                    if (mPager == null || mAdapter == null) return false;
                    if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) return false;
                    CommentSlide slide = mAdapter.getSlide(mPager.getCurrentItem());
                    return slide != null && slide.isReel() && currentReelHolder() != null;
                }

                @Override
                public @Nullable String currentReelAnchor() {
                    if (mPager == null || mAdapter == null) return null;
                    CommentSlide slide = mAdapter.getSlide(mPager.getCurrentItem());
                    return slide != null && slide.isReel() ? slide.getAnchorCommentId() : null;
                }

                @Override
                public @Nullable View reelTitle() {
                    CommentSlideAdapter.SlideHolder holder = currentReelHolder();
                    return holder != null ? holder.mReelTitle : null;
                }

                @Override
                public @Nullable View reelStreamProgress() {
                    CommentSlideAdapter.SlideHolder holder = currentReelHolder();
                    return holder != null ? holder.mStreamProgress : null;
                }

                @Override
                public @Nullable ViewGroup reelPage() {
                    CommentSlideAdapter.SlideHolder holder = currentReelHolder();
                    return holder != null && holder.itemView instanceof ViewGroup
                            ? (ViewGroup) holder.itemView
                            : null;
                }

                @Override
                public @Nullable View reelRows() {
                    CommentSlideAdapter.SlideHolder holder = currentReelHolder();
                    return holder != null ? holder.mRows : null;
                }

                @Override
                public @Nullable RecyclerView pagerList() {
                    if (mPager == null) return null;
                    View list = mPager.getChildAt(0);
                    return list instanceof RecyclerView ? (RecyclerView) list : null;
                }

                @Override
                public boolean isCovered() {
                    return !isResumed() || isReelCovered();
                }

                @Override
                public @Nullable String hashtag() {
                    BrowserExpressCommentsBottomSheetFragment sheet = sheet();
                    return sheet != null ? sheet.topicHashtag() : null;
                }

                @Override
                public void holdStreamProgress(boolean hold) {
                    CommentReelFragment.this.holdStreamProgress(hold);
                }

                @Override
                public List<View> reelEmojiChips() {
                    List<View> chips = new ArrayList<>();
                    View row = currentReelRow();
                    if (row == null) return chips;
                    int[] ids = {R.id.reaction_chip_1, R.id.reaction_chip_2, R.id.reaction_chip_3};
                    for (int id : ids) {
                        View chip = row.findViewById(id);
                        if (chip != null && chip.getVisibility() == View.VISIBLE) chips.add(chip);
                    }
                    return chips;
                }

                @Override
                public @Nullable View reelReplyButton() {
                    View row = currentReelRow();
                    View reply = row != null ? row.findViewById(R.id.btn_reply) : null;
                    return reply != null && reply.getVisibility() == View.VISIBLE ? reply : null;
                }
            };

    /** The stream position fading in under a reel's hashtag; one showing at a time. */
    private final ReelStreamProgress.Sequence mStreamProgress =
            new ReelStreamProgress.Sequence(new ReelStreamProgress.Sequence.Host() {
                @Override
                public @Nullable ReelStreamProgress rowFor(String reelId) {
                    // Found by comment, not as the current page: a row that is fading out stays on
                    // its page as the reader swipes away rather than vanishing mid-swipe.
                    if (mAdapter == null) return null;
                    int position = mAdapter.positionOfComment(reelId);
                    if (position < 0) return null; // Re-packed off the pages, e.g. deleted.
                    CommentSlideAdapter.SlideHolder holder = findSlideHolder(position);
                    return holder != null ? holder.mStreamProgress : null;
                }

                @Override
                public boolean isCovered() {
                    return isReelCovered();
                }
            });

    /**
     * Starts whichever video belongs to the page that just became current: the full-screen video
     * on a reel page (with sound), or the first inline video card on a shared page (muted, as
     * everywhere else). Selection-driven rather than bind-driven, because the pager keeps
     * adjacent pages bound and their players prepared — those must not start until swiped to.
     */
    private final ViewPager2.OnPageChangeCallback mPageChangeCallback =
            new ViewPager2.OnPageChangeCallback() {
                @Override
                public void onPageSelected(int position) {
                    markNewTakesSeenBefore(position);
                    autoplayCurrentPage();
                    updateDepth();
                    updateHeaderForPage(true);
                }

                @Override
                public void onPageScrollStateChanged(int state) {
                    if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                        // The walkthrough's first swipe, up or down.
                        if (mWalkthrough != null) mWalkthrough.onUserDrag();
                        // The page may be on its way out: its "Add your take" pill doesn't open
                        // until a page settles again.
                        ReelTakeBubble bubble = takeBubbleOn(mPager.getCurrentItem());
                        if (bubble != null) bubble.holdOff();
                    } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                        // A third reel's "Add your take" pill opens once the stream position
                        // has faded.
                        attemptSettleTakeBubble(mPager.getCurrentItem(), 0);
                        onPageSettled();
                    }
                }
            };

    public static CommentReelFragment newInstance(
            String postId, String commentsFor, boolean openKeyboard) {
        CommentReelFragment fragment = new CommentReelFragment();
        Bundle args = new Bundle();
        args.putString(COMMENTS_FOR, commentsFor);
        args.putString(POST_ID, postId);
        args.putBoolean(OPEN_KEYBOARD, openKeyboard);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Fragment parent = getParentFragment();
        if (parent instanceof BottomSheetInputCallback) {
            mInputCallback = (BottomSheetInputCallback) parent;
        } else {
            Log.e(TAG, "Parent fragment must implement BottomSheetInputCallback");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_browser_express_comment_reel, container, false);

        if (getArguments() != null) {
            mCommentsFor = getArguments().getString(COMMENTS_FOR);
            mPostId = getArguments().getString(POST_ID);
        }

        mPager = view.findViewById(R.id.comment_reel_pager);
        mShimmerLoading = view.findViewById(R.id.skeleton_shimmer);
        mShimmerItems = view.findViewById(R.id.shimmer_items);
        mEmptyContainer = view.findViewById(R.id.empty_container);

        mToolbarTitle = view.findViewById(R.id.be_toolbar_title);
        updateTitle();

        mPageHeader = view.findViewById(R.id.comments_page_header);
        mOverlay = view.findViewById(R.id.comments_reel_overlay);
        mOverlay.setPadding(mOverlay.getPaddingLeft(), mOverlay.getPaddingTop(),
                mOverlay.getPaddingRight(), mComposerInset);
        mDepthRow = view.findViewById(R.id.comments_depth_row);
        mDepth = view.findViewById(R.id.comments_depth);
        mPosition = view.findViewById(R.id.comments_position);
        BrowserExpressCommentsBottomSheetFragment sheet = sheet();
        if (sheet != null) {
            sheet.bindSourceChip(view);
            ((TextView) view.findViewById(R.id.comments_empty_body)).setText(sheet.isForPost()
                            ? R.string.browser_express_comments_empty_body
                            : R.string.browser_express_comments_empty_body_page);
        }
        view.findViewById(R.id.comments_empty_cta).setOnClickListener(v -> {
            BrowserExpressCommentsBottomSheetFragment host = sheet();
            if (host != null) host.openTakeComposer();
        });

        // Comments is the sheet's root page, so back here means closing the sheet — exactly what
        // the device back button does from this page.
        view.findViewById(R.id.back_button).setOnClickListener(v -> {
            BrowserExpressCommentsBottomSheetFragment host = sheet();
            if (host != null) {
                host.hideKeyboard();
                host.dismissBottomsheet();
            }
        });

        int shimmerRows = AndroidUtils.getSkeletonRowCount(
                org.chromium.ui.base.ViewUtils.dpToPx(requireContext(), 50));
        for (int i = 0; i < shimmerRows; i++) {
            inflater.inflate(R.layout.shimmer_skeleton_item, mShimmerItems, true);
        }
        mShimmerLoading.showShimmer(true);
        AndroidUtils.show(mShimmerItems);

        if (mInputCallback != null) {
            mMessageEditText = mInputCallback.getInputEditText();
            mInputCallback.resetPostStuff();
        }

        CommentActionHost host = getParentFragment() instanceof CommentActionHost
                ? (CommentActionHost) getParentFragment()
                : null;
        mAdapter = new CommentSlideAdapter(host, mMessageEditText, mPageUrl);
        mAdapter.setOnBackListener(() -> {
            BrowserExpressCommentsBottomSheetFragment commentsSheet = sheet();
            if (commentsSheet != null) {
                commentsSheet.hideKeyboard();
                commentsSheet.dismissBottomsheet();
            }
        });
        mPager.setAdapter(mAdapter);

        // Text pages start below the header, so its height is part of what pages pack against;
        // it changes when the source chip shows up.
        mPageHeader.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int inset = b - t;
            if (inset == mHeaderInset) return;
            mHeaderInset = inset;
            if (mPager != null) mPager.post(this::repackIfNeeded);
        });
        // One page either side is enough to make a swipe feel instant without keeping three
        // pages' worth of rows — and players — alive at once.
        mPager.setOffscreenPageLimit(1);

        // Packing needs the pager's real size, which is not known until it has been laid out.
        // The re-pack is posted rather than run inline: this fires during layout, and calling
        // notifyDataSetChanged() while the RecyclerView is laying out throws.
        mPager.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (mPager != null) mPager.post(this::repackIfNeeded);
        });
        mPager.registerOnPageChangeCallback(mPageChangeCallback);
        mWalkthrough = new WalkthroughReel((FrameLayout) view, mWalkthroughHost);

        if (!mComments.isEmpty()) {
            // Back from a replies page: the fragment kept its comments and only its view went.
            // Loading them again would start the reader over from the first page; the pack this
            // view's first layout runs opens on the page they left (mRestoreAnchorCommentId).
            finishInitialLoad();
        } else {
            fetchComments();
        }
        return view;
    }

    private void fetchComments() {
        try {
            BraveActivity activity = BraveActivity.getBraveActivity();
            String accessToken = activity.getAccessToken();

            if ("post".equals(mCommentsFor)) {
                new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                        null, null, mPostId, PAGE, PER_PAGE, accessToken, mGetCommentsCallback)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            } else {
                mPageUrl = activity.getActivityTab().getUrl().getSpec();
                new BrowserExpressGetCommentsUtil.GetCommentsWorkerTask(
                        mPageUrl, null, null, PAGE, PER_PAGE, accessToken, mGetCommentsCallback)
                        .executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
            }
        } catch (BraveActivity.BraveActivityNotFoundException e) {
            Log.e(TAG, "BraveActivity not available, cannot load comments", e);
        }
    }

    private final BrowserExpressGetCommentsUtil.GetCommentsCallback mGetCommentsCallback =
            new BrowserExpressGetCommentsUtil.GetCommentsCallback() {
                @Override
                public void getCommentsTotals(
                        int commentCount, int emoteCount, @Nullable String hashtag) {
                    BrowserExpressCommentsBottomSheetFragment commentsSheet = sheet();
                    if (commentsSheet != null) {
                        commentsSheet.onTopicTotals(commentCount, emoteCount, hashtag);
                    }
                    // The comments response can name the hashtag the feed post didn't carry.
                    updateTitle();
                }

                @Override
                public void getCommentsSuccessful(
                        List<Comment> comments, Comment parentComment, Comment grandParentComment) {
                    mComments.clear();
                    if (comments != null) mComments.addAll(comments);
                    mCommentsLoaded = true;
                    // Opened from a card's "new takes": the first pack lands on the first of them.
                    if (mRestoreAnchorCommentId == null) mRestoreAnchorCommentId = openingAnchor();
                    // Only now, with the takes marked NEW against the last open, does this open
                    // count; the next feed and stream measure from it.
                    if ("post".equals(mCommentsFor)) PostOpenedReporter.report(mPostId);

                    // Sizes from the API and cached ones land synchronously, so those portrait
                    // videos get their reel page in the very first pack. Unknown videos near the
                    // opening comment hold that pack — briefly, behind the shimmer — so the first
                    // page is already right rather than inline-then-promoted.
                    Set<String> held = resolveVideoOrientations();
                    if (!held.isEmpty() && mPager != null) {
                        mPackHeldForProbes = true;
                        mHeldForCommentIds.clear();
                        mHeldForCommentIds.addAll(held);
                        mPager.postDelayed(CommentReelFragment.this::releaseInitialHold,
                                INITIAL_PROBE_WAIT_MS);
                    } else {
                        finishInitialLoad();
                    }
                }

                @Override
                public void getCommentsFailed(String error) {
                    Log.e(TAG, "Failed to load comments: " + error);
                    mCommentsLoaded = true;
                    mShimmerLoading.hideShimmer();
                    mShimmerLoading.setVisibility(View.GONE);
                    AndroidUtils.gone(mShimmerItems);
                    mEmptyContainer.setVisibility(View.VISIBLE);
                    updateEmptyHint();
                }
            };

    /** Re-packs only when the pager's size actually changed, since layout fires often. */
    private void repackIfNeeded() {
        if (!mCommentsLoaded || mPager == null) return;
        if (mPager.getWidth() == mPackedForWidth && mPager.getHeight() == mPackedForHeight
                && mHeaderInset == mPackedForHeaderInset
                && mComposerInset == mPackedForComposerInset) {
            return;
        }
        repack(currentAnchorCommentId());
    }

    /**
     * Rebuilds the pages and restores the reader's place.
     *
     * @param anchorCommentId comment to return to, since re-packing renumbers every page.
     */
    private void repack(String anchorCommentId) {
        if (!mCommentsLoaded || mPager == null || getContext() == null) return;
        if (mPackHeldForProbes) return; // The first pack runs when the hold releases.

        int width = mPager.getWidth();
        int height = mPager.getHeight();
        if (width <= 0 || height <= 0) return; // Not laid out yet; the layout listener retries.
        if (anchorCommentId == null) anchorCommentId = mRestoreAnchorCommentId;
        mRestoreAnchorCommentId = null;

        // Pack against the height the rows actually get, not the whole page: the list holds
        // them below its top padding. Counting that padding as usable would leave every page a
        // few pixels scrollable, and a page that can scroll takes the first part of a swipe
        // instead of turning (see SlideScrollHost).
        // Text rows also start below the floating header and stop above the floating comment box;
        // a reel page, and the peek of one, are sized to the whole page.
        int rowsHeight = height - mHeaderInset - mComposerInset
                - getResources().getDimensionPixelSize(R.dimen.be_slide_rows_padding_top);
        if (rowsHeight <= 0) return;

        CommentRowMeasurer measurer = new CommentRowMeasurer(getContext(), width);
        // The next reel's preview only fills room the comments leave. Shorter than its two
        // rounded corners, it would read as a stray sliver, so such a page gets none.
        int minPeek = 2 * getResources().getDimensionPixelSize(R.dimen.be_reel_peek_corner_radius);
        CommentSlidePacker packer = new CommentSlidePacker(measurer, rowsHeight, height,
                CommentSlidePacker.DEFAULT_MAX_COMMENTS_PER_SLIDE,
                CommentSlidePacker.DEFAULT_LOOKAHEAD_WINDOW, minPeek);
        mAdapter.setHeaderInset(mHeaderInset);
        mAdapter.setComposerInset(mComposerInset);
        final int generation = ++mPackGeneration;
        boolean packRestLater = opensOnLeadingReel(anchorCommentId);
        if (packRestLater) {
            // The stream leads with a full-page video and the reader starts on it. That page is
            // all they see, and it is the page a full pack begins with, so it goes up alone and
            // its video starts loading in the coming frame. The other comments are measured into
            // pages after that frame (posted last, below) and added behind it without rebinding
            // it.
            mAdapter.setSlides(Collections.singletonList(packer.reelPage(mComments.get(0))));
        } else {
            mAdapter.setSlides(packer.pack(mComments));
        }

        mPackedForWidth = width;
        mPackedForHeight = height;
        mPackedForHeaderInset = mHeaderInset;
        mPackedForComposerInset = mComposerInset;

        int restoreTo = mAdapter.positionOfComment(anchorCommentId);
        if (restoreTo >= 0) {
            // Posted so the pager has applied the new page count first; setting it in the same
            // frame as notifyDataSetChanged() is dropped.
            final int target = restoreTo;
            mPager.post(() -> {
                if (mPager != null) mPager.setCurrentItem(target, false);
            });
        }
        // A re-pack rebuilds every page, which recycles the row that was playing. Restart the
        // current page's video once the new pages are up. When the restore above moved the pager,
        // setCurrentItem's own onPageSelected does this instead and this post finds the same page.
        mPager.post(this::autoplayCurrentPage);
        mPager.post(this::updateDepth);
        mPager.post(() -> updateHeaderForPage(false));
        if (packRestLater) mPager.post(() -> packBehindOpeningReel(packer, generation));
    }

    /**
     * The first pack, opening on the first comment, and that comment is a portrait video: the
     * stream's opening reel.
     */
    private boolean opensOnLeadingReel(@Nullable String anchorCommentId) {
        if (mAdapter.getItemCount() > 0 || mComments.isEmpty()) return false;
        Comment first = mComments.get(0);
        if (!first.isPortraitVideo()) return false;
        return anchorCommentId == null || anchorCommentId.equals(first.getId());
    }

    /**
     * Second half of a pack that put the opening reel up on its own: packs every comment after it
     * and adds those pages behind it. {@link CommentSlidePacker#reelPage} guarantees these are
     * the pages a full pack would have made. Skipped when another pack has replaced the pages
     * since.
     */
    private void packBehindOpeningReel(CommentSlidePacker packer, int generation) {
        if (generation != mPackGeneration || mPager == null || mAdapter == null) return;
        if (mComments.size() > 1) {
            mAdapter.appendSlides(packer.pack(mComments.subList(1, mComments.size())));
        }
        updateDepth();
    }

    /**
     * Sets the height of the sheet's comment box floating over the bottom of this page, re-packing
     * the text pages to stop above it.
     */
    public void setComposerInset(int insetPx) {
        if (insetPx == mComposerInset) return;
        mComposerInset = insetPx;
        if (mOverlay != null) {
            mOverlay.setPadding(mOverlay.getPaddingLeft(), mOverlay.getPaddingTop(),
                    mOverlay.getPaddingRight(), insetPx);
        }
        if (mPager != null) mPager.post(this::repackIfNeeded);
    }

    /**
     * Shows the comments header and the comment box over text pages and moves them out of the way
     * of a full-page video, whose own header takes over and which gets the whole page. They fade
     * rather than blink when the reader swipes.
     */
    private void updateHeaderForPage(boolean animate) {
        if (mPageHeader == null || mAdapter == null || mPager == null) return;
        CommentSlide slide = mAdapter.getSlide(mPager.getCurrentItem());
        boolean overReel = slide != null && slide.isReel();
        BrowserExpressCommentsBottomSheetFragment sheet = sheet();
        if (sheet != null) sheet.setComposerOverReel(overReel, animate);
        float target = overReel ? 0f : 1f;
        mPageHeader.animate().cancel();
        if (overReel) {
            if (!animate) {
                mPageHeader.setAlpha(0f);
                mPageHeader.setVisibility(View.INVISIBLE);
                return;
            }
            mPageHeader.animate().alpha(target).setDuration(HEADER_FADE_MS)
                    .withEndAction(() -> {
                        // Invisible, not just transparent, so taps reach the video under it.
                        if (mPageHeader != null) mPageHeader.setVisibility(View.INVISIBLE);
                    });
        } else {
            mPageHeader.setVisibility(View.VISIBLE);
            if (animate) {
                mPageHeader.animate().alpha(target).setDuration(HEADER_FADE_MS);
            } else {
                mPageHeader.setAlpha(target);
            }
        }
    }

    /** Ends the initial probe hold (deadline or last probe, whichever comes first). */
    private void releaseInitialHold() {
        if (!mPackHeldForProbes) return;
        mPackHeldForProbes = false;
        mHeldForCommentIds.clear();
        finishInitialLoad();
    }

    private void finishInitialLoad() {
        if (mShimmerLoading == null) return; // View already gone.
        mShimmerLoading.hideShimmer();
        mShimmerLoading.setVisibility(View.GONE);
        AndroidUtils.gone(mShimmerItems);
        mEmptyContainer.setVisibility(mComments.isEmpty() ? View.VISIBLE : View.GONE);
        updateEmptyHint();
        repack(null);
    }

    @Nullable
    private BrowserExpressCommentsBottomSheetFragment sheet() {
        return getParentFragment() instanceof BrowserExpressCommentsBottomSheetFragment
                ? (BrowserExpressCommentsBottomSheetFragment) getParentFragment()
                : null;
    }

    /**
     * Titles the header with the same name the reel pages use: the post's "#Hashtag", else
     * "Comments" (a page's comments, or a post without one).
     */
    private void updateTitle() {
        if (mToolbarTitle == null) return;
        BrowserExpressCommentsBottomSheetFragment sheet = sheet();
        String hashtag = sheet != null ? sheet.topicHashtag() : null;
        if (hashtag != null) {
            mToolbarTitle.setText(hashtag);
        } else {
            mToolbarTitle.setText(R.string.comments);
        }
    }

    /** "Write the first comment…" while there are none, "Add a comment" after. */
    private void updateEmptyHint() {
        if (mMessageEditText == null) return;
        mMessageEditText.setHint(mCommentsLoaded && mComments.isEmpty()
                        ? R.string.browser_express_comment_hint_first
                        : R.string.browser_express_comment_hint);
    }

    /**
     * How far through the comments the reader is: every comment up to the end of the current
     * page counts as read, so the last page reads "87 / 87" with the line full.
     */
    private void updateDepth() {
        if (mDepthRow == null || mAdapter == null || mPager == null) return;
        int total = mComments.size();
        if (total == 0 || mAdapter.getItemCount() == 0) {
            mDepthRow.setVisibility(View.INVISIBLE);
            return;
        }
        int seen = seenThrough(mPager.getCurrentItem());
        mPosition.setText(getString(R.string.browser_express_comments_position, seen, total));
        mDepth.setProgress(Math.round(1000f * seen / total));
        mDepthRow.setVisibility(View.VISIBLE);
    }

    /**
     * How many comments count as read once the reader is on page {@code position}: every one up
     * to the end of that page, at least 1. On a reel page this is the video's own place in the
     * stream. The text pages' header and the reels' stream position both use it, so they agree.
     */
    private int seenThrough(int position) {
        int seen = 0;
        for (int i = 0; i <= position && i < mAdapter.getItemCount(); i++) {
            CommentSlide slide = mAdapter.getSlide(i);
            if (slide != null) seen += slide.getItemCount();
        }
        return Math.max(1, Math.min(seen, mComments.size()));
    }

    /**
     * The pager is at rest on its current page. When that page is a reel and isn't the page the
     * pager last rested on, the reader has just arrived on it, so its stream position shows. The
     * line moves from the page they came from (README §3–5).
     *
     * <p>Every path that can leave the pager at rest calls this: a swipe or a jump ending, a
     * re-pack, coming back to the page. The page it already handled gets only the new numbers,
     * e.g. when takes arrive, and its timer carries on.
     */
    private void onPageSettled() {
        showStreamPositionIfArrived();
        // A vertical video coming to rest may start the walkthrough's video step.
        if (mWalkthrough != null) mWalkthrough.onPageSettled();
    }

    private void showStreamPositionIfArrived() {
        if (mPager == null || mAdapter == null) return;
        if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) return;
        int position = mPager.getCurrentItem();
        CommentSlide slide = mAdapter.getSlide(position);
        if (slide == null || slide.getAnchorCommentId() == null) return;
        String anchor = slide.getAnchorCommentId();
        int seen = seenThrough(position);
        int total = mComments.size();

        if (anchor.equals(mSettledAnchor)) {
            mSettledSeen = seen;
            if (mStreamProgress.isShowing(anchor)) mStreamProgress.update(seen, total);
            return;
        }
        // The first page of the stream has nothing to move on from.
        int from = mSettledSeen > 0 ? mSettledSeen : seen;
        mSettledAnchor = anchor;
        mSettledSeen = seen;
        reportNewTakesViewed(slide);
        if (!slide.isReel() || isReelCovered()) {
            mStreamProgress.stop();
            return;
        }
        mStreamProgress.start(anchor, from, seen, total);
    }

    /**
     * Keeps the current reel's stream position up (starting it if it isn't showing), or lets it
     * fade as usual. The walkthrough's video step points at it.
     */
    private void holdStreamProgress(boolean hold) {
        if (!hold) {
            mStreamProgress.setHeld(false);
            return;
        }
        if (mPager == null || mAdapter == null) return;
        int position = mPager.getCurrentItem();
        CommentSlide slide = mAdapter.getSlide(position);
        if (slide == null || !slide.isReel() || slide.getAnchorCommentId() == null) return;
        String anchor = slide.getAnchorCommentId();
        if (!mStreamProgress.isShowing(anchor)) {
            int seen = seenThrough(position);
            mStreamProgress.start(anchor, seen, seen, mComments.size());
        }
        mStreamProgress.setHeld(true);
    }

    /** The current page while it is a bound reel page, else null. */
    private @Nullable CommentSlideAdapter.SlideHolder currentReelHolder() {
        if (mPager == null) return null;
        CommentSlideAdapter.SlideHolder holder = findSlideHolder(mPager.getCurrentItem());
        return holder != null && holder.mReelTitle != null ? holder : null;
    }

    /** The current reel page's one row: its video, caption and rail. */
    private @Nullable View currentReelRow() {
        CommentSlideAdapter.SlideHolder holder = currentReelHolder();
        return holder != null && holder.mRows.getChildCount() > 0
                ? holder.mRows.getChildAt(0)
                : null;
    }

    /**
     * Where the stream opens when the sheet was asked to open at a comment, e.g. the first of a
     * feed card's "new takes": that comment when it is loaded; else, since it can sit past the
     * loaded takes, the first one marked NEW in the stream's order. That is how the server picks
     * firstNewTakeId (docs/new-takes.md). createdAt can't stand in for it: one pipeline run's
     * takes share one, and appended or merged takes arrive later than it. Null opens at the top.
     * The request is used up.
     */
    private @Nullable String openingAnchor() {
        BrowserExpressCommentsBottomSheetFragment sheet = sheet();
        String target = sheet != null ? sheet.getLastOpenedRepliesForCommentId() : null;
        if (target == null) return null;
        sheet.clearLastOpenedRepliesForCommentId();
        String firstNew = null;
        for (Comment comment : mComments) {
            if (target.equals(comment.getId())) return target;
            if (firstNew == null && comment.isNew()) firstNew = comment.getId();
        }
        return firstNew;
    }

    /**
     * The reader has moved on to {@code position}, past every page before it, so the new takes on
     * those have been seen and lose their NEW tag (design_handoff_new_takes §4). Pages still bound,
     * like the one just left, lose it now; the others when they are next bound.
     */
    private void markNewTakesSeenBefore(int position) {
        if (mAdapter == null) return;
        for (int i = 0; i < position && i < mAdapter.getItemCount(); i++) {
            CommentSlide slide = mAdapter.getSlide(i);
            if (slide == null) continue;
            boolean changed = false;
            for (CommentSlide.Item item : slide.getItems()) {
                if (item.getComment().showsNewTag()) {
                    item.getComment().markNewSeen();
                    changed = true;
                }
            }
            if (changed) hideNewTagsOn(i);
        }
    }

    private void hideNewTagsOn(int position) {
        CommentSlideAdapter.SlideHolder holder = findSlideHolder(position);
        if (holder == null) return;
        RecyclerView rows = holder.mRows;
        for (int i = 0; i < rows.getChildCount(); i++) {
            View tag = rows.getChildAt(i).findViewById(R.id.comment_new_tag);
            if (tag != null) tag.setVisibility(View.GONE);
        }
    }

    /** Reports each NEW take on a page the reader has just arrived on, once. */
    private void reportNewTakesViewed(CommentSlide slide) {
        for (CommentSlide.Item item : slide.getItems()) {
            Comment comment = item.getComment();
            if (!comment.isNew() || comment.getId() == null) continue;
            if (!mViewedNewTakes.add(comment.getId())) continue;
            PostHogUtil.capture(
                    PostHogEventKeys.NEW_TAKE_VIEWED,
                    "take_id",
                    comment.getId(),
                    "position",
                    mComments.indexOf(comment) + 1,
                    "post_id",
                    mPostId);
        }
    }

    /**
     * Something is open over the reel: the "Add your take" sheet, a full-screen viewer (both hold
     * playback), or a sheet over the comments, such as the emoji picker. A reel's stream
     * position doesn't show under any of them.
     */
    private boolean isReelCovered() {
        if (GlobalVideoPlaybackManager.getInstance().isHeld()) return true;
        if (!isAdded()) return true;
        // The emoji, GIF and attachment pickers are dialogs in the sheet's own fragment manager,
        // which is the one this page lives in.
        for (Fragment fragment : getParentFragmentManager().getFragments()) {
            if (!(fragment instanceof DialogFragment)) continue;
            Dialog dialog = ((DialogFragment) fragment).getDialog();
            if (dialog != null && dialog.isShowing()) return true;
        }
        return false;
    }

    /** The message box's send posts to these comments while this page is showing. */
    private void bindSendButton() {
        if (mInputCallback == null || mInputCallback.getSendButton() == null) return;
        mInputCallback.getSendButton().setOnClickListener(v -> {
            BrowserExpressCommentsBottomSheetFragment host = sheet();
            if (host == null || mMessageEditText == null) return;
            String content = mMessageEditText.getText().toString().trim();
            if (content.isEmpty()) return;
            host.postTextComment(content);
            mMessageEditText.setText("");
            host.hideKeyboard();
        });
    }

    /**
     * Makes sure every video comment knows its orientation. API sizes and cache hits apply
     * synchronously; misses resolve in the background — metadata probe or the inline player's
     * size report, whichever lands first — and are announced through
     * {@link #onVideoDimensionsResolved}.
     *
     * @return ids of the unresolved videos close enough to the opening comment
     *     ({@link #OPENING_PAGE_REACH}) to change the page the reader opens on — the ones worth
     *     holding the first pack for. Every other miss is still probed, just not waited on.
     */
    private Set<String> resolveVideoOrientations() {
        Set<String> nearOpening = new HashSet<>();
        if (getContext() == null) return nearOpening;
        // The resolution callbacks live in a process-wide cache; holding this fragment weakly
        // keeps a video that never resolves from pinning the whole view tree in memory.
        final java.lang.ref.WeakReference<CommentReelFragment> self =
                new java.lang.ref.WeakReference<>(this);
        int opening = Math.max(0, indexOfComment(mRestoreAnchorCommentId));
        for (int i = 0; i < mComments.size(); i++) {
            Comment comment = mComments.get(i);
            if (!comment.hasVideoMedia() || comment.hasCachedDimensions()) continue;
            if (CommentVideoDimensions.applyCached(comment)) continue;
            final Comment target = comment;
            boolean mayResolve = CommentVideoDimensions.fetchAsync(getContext(), comment, () -> {
                CommentReelFragment fragment = self.get();
                if (fragment != null) fragment.onVideoDimensionsResolved(target);
            });
            if (mayResolve && comment.getId() != null
                    && Math.abs(i - opening) < OPENING_PAGE_REACH) {
                nearOpening.add(comment.getId());
            }
        }
        return nearOpening;
    }

    /** Position of {@code commentId} in {@link #mComments}, or -1. */
    private int indexOfComment(@Nullable String commentId) {
        if (commentId == null) return -1;
        for (int i = 0; i < mComments.size(); i++) {
            if (commentId.equals(mComments.get(i).getId())) return i;
        }
        return -1;
    }

    /** A background resolution landed for {@code comment}; re-render whatever it changes. */
    private void onVideoDimensionsResolved(Comment comment) {
        if (mPackHeldForProbes) {
            // Whatever lands now is in place for the first pack; only the opening page's videos
            // end the wait early.
            if (mHeldForCommentIds.remove(comment.getId()) && mHeldForCommentIds.isEmpty()) {
                releaseInitialHold();
            }
            return;
        }
        if (mPager == null || mAdapter == null) return;
        // A portrait verdict moves the video onto its own page. A landscape one changes the
        // packing only when its card preset is not the 16:9 the row was measured at.
        int[] ratio = comment.getInlineMediaRatio();
        boolean inlineShapeChanged = ratio[0] != 16 || ratio[1] != 9;
        if (!comment.isPortraitVideo() && !inlineShapeChanged) return;

        String anchor = currentAnchorCommentId();
        CommentSlide current = mAdapter.getSlide(mPager.getCurrentItem());
        if (current != null && comment.getId() != null) {
            for (CommentSlide.Item item : current.getItems()) {
                if (comment.getId().equals(item.getComment().getId())) {
                    // The reader is looking at this very video (it likely just started playing
                    // inline, which is what reported the size) — follow it to its reel page.
                    anchor = comment.getId();
                    break;
                }
            }
        }
        repack(anchor);
    }

    /**
     * Finds the video holder on the current page and hands it to the playback manager, retrying
     * briefly because right after a (re)pack the page's rows have not been laid out yet.
     */
    private void autoplayCurrentPage() {
        int position = mPager != null ? mPager.getCurrentItem() : -1;
        attemptAutoplay(position, 0);
        attemptSettleTakeBubble(position, 0);
        onPageSettled();
    }

    /**
     * Tells the current reel page's "Add your take" pill that the pager has come to rest, so a
     * third reel's pill opens after the stream position (ReelTakeBubble#onSettled). Retries
     * briefly like autoplay because right after a (re)pack the page has not been bound.
     */
    private void attemptSettleTakeBubble(int position, int attempt) {
        if (mPager == null || mAdapter == null || position < 0) return;
        if (mPager.getCurrentItem() != position) return;
        // Mid-swipe it waits for SCROLL_STATE_IDLE, which calls back in here.
        if (mPager.getScrollState() != ViewPager2.SCROLL_STATE_IDLE) return;
        CommentSlide slide = mAdapter.getSlide(position);
        if (slide == null || !slide.isReel()) return;

        ReelTakeBubble bubble = takeBubbleOn(position);
        if (bubble != null) {
            bubble.onSettled();
        } else if (attempt < 5) {
            mPager.postDelayed(() -> attemptSettleTakeBubble(position, attempt + 1), 100);
        }
    }

    private @Nullable ReelTakeBubble takeBubbleOn(int position) {
        CommentSlideAdapter.SlideHolder holder = findSlideHolder(position);
        return holder != null ? holder.mTakeBubble : null;
    }

    private void attemptAutoplay(int position, int attempt) {
        if (mPager == null || mAdapter == null || position < 0) return;
        // A stale retry from before a swipe or re-pack must not start the wrong page's video.
        if (mPager.getCurrentItem() != position) return;

        CommentSlide slide = mAdapter.getSlide(position);
        if (slide == null) return;
        if (!slideHasVideo(slide)) {
            // The slide model already knows there is nothing to play, so the outgoing page's
            // video stops now rather than after the layout retries below run dry.
            GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
            return;
        }

        CommentListAdapter.CommentHolder videoHolder = findVideoHolder(position);
        if (videoHolder != null) {
            GlobalVideoPlaybackManager.getInstance().playVideo(videoHolder);
        } else if (attempt < 5) {
            mPager.postDelayed(() -> attemptAutoplay(position, attempt + 1), 100);
        }
    }

    private static boolean slideHasVideo(CommentSlide slide) {
        for (CommentSlide.Item item : slide.getItems()) {
            if (item.getComment().hasVideoMedia()) return true;
        }
        return false;
    }

    /** The bound page at the given position, or null. */
    private @Nullable CommentSlideAdapter.SlideHolder findSlideHolder(int position) {
        if (mPager == null) return null;
        View pagerChild = mPager.getChildAt(0);
        if (!(pagerChild instanceof RecyclerView)) return null;
        RecyclerView.ViewHolder slideHolder =
                ((RecyclerView) pagerChild).findViewHolderForAdapterPosition(position);
        return slideHolder instanceof CommentSlideAdapter.SlideHolder
                ? (CommentSlideAdapter.SlideHolder) slideHolder
                : null;
    }

    /** The first row on the given page with a prepared player, or null. */
    private CommentListAdapter.CommentHolder findVideoHolder(int position) {
        CommentSlideAdapter.SlideHolder slideHolder = findSlideHolder(position);
        if (slideHolder == null) return null;

        RecyclerView rows = slideHolder.mRows;
        for (int i = 0; i < rows.getChildCount(); i++) {
            RecyclerView.ViewHolder rowHolder = rows.getChildViewHolder(rows.getChildAt(i));
            if (rowHolder instanceof CommentListAdapter.CommentHolder
                    && ((CommentListAdapter.CommentHolder) rowHolder).hasVideo()) {
                return (CommentListAdapter.CommentHolder) rowHolder;
            }
        }
        return null;
    }

    private String currentAnchorCommentId() {
        CommentSlide slide = mAdapter != null ? mAdapter.getSlide(mPager.getCurrentItem()) : null;
        return slide != null ? slide.getAnchorCommentId() : null;
    }

    /**
     * Restarts the current page's video after something stacked over the reel — a media viewer,
     * say — paused it on the way in. The reel's own onResume does not fire for that, because a
     * dialog on top does not pause the fragments underneath.
     */
    public void triggerVideoVisibilityCheck() {
        if (mPager != null) mPager.post(this::autoplayCurrentPage);
    }

    @Override
    public void onResume() {
        super.onResume();
        registerUploadReceiver();
        // A replies page may have taken over the send button while it showed.
        bindSendButton();
        updateEmptyHint();
        // onPause stopped whatever was playing; the current page's video picks back up here.
        if (mPager != null) mPager.post(this::autoplayCurrentPage);
        if (mWalkthrough != null) mWalkthrough.resume();
    }

    @Override
    public void onPause() {
        super.onPause();
        GlobalVideoPlaybackManager.getInstance().pauseCurrentlyPlayingVideo();
        // Back to Home, or the app went away: the walkthrough waits for the next stream.
        if (mWalkthrough != null) mWalkthrough.pause();
        mStreamProgress.stop();
        if (mUploadReceiver != null && getContext() != null) {
            androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext())
                    .unregisterReceiver(mUploadReceiver);
            mUploadReceiver = null;
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (mWalkthrough != null) mWalkthrough.destroy();
        mWalkthrough = null;
        GlobalVideoPlaybackManager.getInstance().releaseAllResources();
        mStreamProgress.stop();
        if (mPager != null && mAdapter != null && mAdapter.getItemCount() > 0) {
            mRestoreAnchorCommentId = currentAnchorCommentId();
        }
        // The next view packs afresh, even at the same size. Its probe hold would never end:
        // the deadline was posted to this view's pager.
        mPackedForWidth = 0;
        mPackedForHeight = 0;
        // The pages behind an opening reel are packed for this view's pager; a pack still posted
        // must not land on the next view's.
        mPackGeneration++;
        mPackHeldForProbes = false;
        mHeldForCommentIds.clear();
        if (mPager != null) {
            mPager.unregisterOnPageChangeCallback(mPageChangeCallback);
            // Recycles every page, current and pre-built alike, which releases their video
            // players and the hardware decoders those hold.
            mPager.setAdapter(null);
        }
        mPager = null;
        mAdapter = null;
    }

    // ---------------------------------------------------------------------------------------
    // Optimistic posting — same contract the host calls on CommentListFragment.
    // ---------------------------------------------------------------------------------------

    /** The first comment on the page the reader is on; null before the first pack. */
    private @Nullable String currentPageAnchor() {
        if (mPager == null || mAdapter == null) return null;
        CommentSlide slide = mAdapter.getSlide(mPager.getCurrentItem());
        if (slide == null || slide.getItems().isEmpty()) return null;
        return slide.getItems().get(0).getComment().getId();
    }

    /** The comments loaded so far, for the "Add your take" topic card. */
    List<Comment> getLoadedComments() {
        return mComments;
    }

    public void addNewComment(Comment newComment) {
        mComments.add(0, newComment);
        mEmptyContainer.setVisibility(View.GONE);
        updateEmptyHint();
        // A just-posted video points at a local URI, so its orientation probe is near-instant —
        // a vertical recording lands directly on its own reel page.
        resolveVideoOrientations();
        // Anchor on the new comment so the reader is taken to what they just posted.
        repack(newComment.getId());
    }

    public void updateTemporaryComment(String tempId, Comment realComment) {
        if (tempId == null) return;
        for (int i = 0; i < mComments.size(); i++) {
            if (tempId.equals(mComments.get(i).getId())) {
                // The uploaded video is the same one the optimistic comment was probed with;
                // carrying the dimensions over saves probing the freshly-served remote URL.
                Comment tempComment = mComments.get(i);
                if (!realComment.hasCachedDimensions() && tempComment.hasCachedDimensions()) {
                    realComment.setMediaWidth(tempComment.getMediaWidth());
                    realComment.setMediaHeight(tempComment.getMediaHeight());
                }
                // The reader stays where they are; the banner's View brings them to it.
                String anchor = currentPageAnchor();
                mComments.set(i, realComment);
                resolveVideoOrientations();
                repack(anchor == null || tempId.equals(anchor) ? realComment.getId() : anchor);
                return;
            }
        }
    }

    public void markCommentAsFailed(String tempId) {
        if (tempId == null) return;
        for (Comment comment : mComments) {
            if (tempId.equals(comment.getId())) {
                comment.setUploadStatus(Comment.UploadStatus.FAILED);
                // As on success: the banner says so, and its Retry needs no trip back.
                String anchor = currentPageAnchor();
                repack(anchor != null ? anchor : tempId);
                return;
            }
        }
    }

    /**
     * Redraws a sending comment's progress or highlight on whichever bound page shows it, without
     * re-packing: the row's height doesn't change.
     */
    public void refreshUploadState(String commentId) {
        if (mPager == null || commentId == null) return;
        View pagerChild = mPager.getChildAt(0);
        if (!(pagerChild instanceof RecyclerView)) return;
        RecyclerView pages = (RecyclerView) pagerChild;
        for (int i = 0; i < pages.getChildCount(); i++) {
            RecyclerView.ViewHolder holder = pages.getChildViewHolder(pages.getChildAt(i));
            if (!(holder instanceof CommentSlideAdapter.SlideHolder)) continue;
            RecyclerView.Adapter<?> rows = ((CommentSlideAdapter.SlideHolder) holder).mRows.getAdapter();
            if (rows instanceof CommentListAdapter) {
                ((CommentListAdapter) rows).refreshUploadState(commentId);
            }
        }
    }

    /** Re-packs around a sending comment whose card changed height (failed, retried). */
    public void repackAround(String commentId) {
        repack(commentId);
    }

    /** Takes a comment off the pages, e.g. one whose upload was cancelled. */
    public void removeComment(String commentId) {
        if (commentId == null) return;
        for (int i = 0; i < mComments.size(); i++) {
            if (commentId.equals(mComments.get(i).getId())) {
                mComments.remove(i);
                mEmptyContainer.setVisibility(mComments.isEmpty() ? View.VISIBLE : View.GONE);
                updateEmptyHint();
                repack(currentAnchorCommentId());
                return;
            }
        }
    }

    private android.content.BroadcastReceiver mUploadReceiver;

    private void registerUploadReceiver() {
        if (getContext() == null) return;
        mUploadReceiver = new android.content.BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String action = intent.getAction();
                String tempId = intent.getStringExtra(UploadService.EXTRA_TEMP_ID);
                if (tempId == null) return;

                if (UploadService.BROADCAST_UPLOAD_COMPLETE.equals(action)) {
                    String json = intent.getStringExtra(UploadService.EXTRA_REAL_COMMENT_JSON);
                    if (json != null) {
                        updateTemporaryComment(
                                tempId, new com.google.gson.Gson().fromJson(json, Comment.class));
                    }
                } else if (UploadService.BROADCAST_UPLOAD_FAILED.equals(action)) {
                    markCommentAsFailed(tempId);
                }
            }
        };
        android.content.IntentFilter filter = new android.content.IntentFilter();
        filter.addAction(UploadService.BROADCAST_UPLOAD_COMPLETE);
        filter.addAction(UploadService.BROADCAST_UPLOAD_FAILED);
        androidx.localbroadcastmanager.content.LocalBroadcastManager.getInstance(getContext())
                .registerReceiver(mUploadReceiver, filter);
    }
}

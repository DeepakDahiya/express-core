/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.test.BaseRobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Unit tests for {@link CommentSlidePacker}. */
@RunWith(BaseRobolectricTestRunner.class)
public class CommentSlidePackerUnitTest {
    private static final int VIEWPORT = 1000;
    private static final int ROW = 200; // Five rows exactly fill the viewport.

    @Test
    @SmallTest
    public void emptyInputProducesNoSlides() {
        assertTrue(packer(VIEWPORT).pack(Collections.emptyList()).isEmpty());
        assertTrue(packer(VIEWPORT).pack(null).isEmpty());
    }

    @Test
    @SmallTest
    public void commentsThatFitShareOneSlide() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), text("b"), text("c")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
        assertEquals(3, slides.get(0).getItemCount());
        assertEquals(3 * ROW, slides.get(0).getHeightPx());
    }

    @Test
    @SmallTest
    public void overflowStartsANewSlide() {
        // Six rows of 200 against a 1000px viewport: five fit, the sixth spills over. The cap is
        // raised past six so that height, not the cap, is what splits the run.
        List<CommentSlide> slides = new CommentSlidePacker(new FakeMeasurer(ROW), VIEWPORT, 10)
                                            .pack(texts("a", "b", "c", "d", "e", "f"));

        assertEquals(2, slides.size());
        assertEquals(5, slides.get(0).getItemCount());
        assertEquals(1, slides.get(1).getItemCount());
        assertTrue(slides.get(0).getHeightPx() <= VIEWPORT);
    }

    @Test
    @SmallTest
    public void slideIsCappedEvenWhenEverythingWouldFit() {
        // Ten rows of 50 all fit in 1000px, but the cap keeps a page from becoming a feed.
        List<CommentSlide> slides =
                new CommentSlidePacker(new FakeMeasurer(50), VIEWPORT, 4)
                        .pack(texts("a", "b", "c", "d", "e", "f", "g", "h", "i", "j"));

        assertEquals(3, slides.size());
        assertEquals(4, slides.get(0).getItemCount());
        assertEquals(4, slides.get(1).getItemCount());
        assertEquals(2, slides.get(2).getItemCount());
    }

    @Test
    @SmallTest
    public void leftoverSpaceIsLeftBlank() {
        // Two rows of 200 with a cap of 2: the page ends at 400 of 1000px rather than reaching
        // for more comments. Blank space is the intended outcome, not a bug.
        List<CommentSlide> slides =
                new CommentSlidePacker(new FakeMeasurer(ROW), VIEWPORT, 2).pack(texts("a", "b"));

        assertEquals(1, slides.size());
        assertEquals(2 * ROW, slides.get(0).getHeightPx());
    }

    @Test
    @SmallTest
    public void portraitVideoGetsItsOwnReelSlide() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Collections.singletonList(portraitVideo("v")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_REEL, slides.get(0).getType());
        assertEquals(1, slides.get(0).getItemCount());
        assertEquals("v", slides.get(0).getReelComment().getId());
    }

    @Test
    @SmallTest
    public void imageCommentPacksInlineWithText() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), image("i"), text("b")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
        assertEquals(3, slides.get(0).getItemCount());
    }

    @Test
    @SmallTest
    public void landscapeVideoPacksInlineWithText() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), landscapeVideo("v"), text("b")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
        assertEquals(3, slides.get(0).getItemCount());
    }

    @Test
    @SmallTest
    public void videoWithUnknownDimensionsPacksInline() {
        // Until the orientation probe lands there is no way to tell portrait from landscape, so
        // the video shows inline; the probe's re-pack promotes it if it turns out portrait.
        List<CommentSlide> slides = packer(VIEWPORT).pack(Collections.singletonList(video("v")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
    }

    @Test
    @SmallTest
    public void reelTopsUpThePendingPageBeforeTakingItsOwn() {
        List<CommentSlide> slides = packer(VIEWPORT).pack(
                Arrays.asList(text("a"), portraitVideo("v"), text("b"), text("c")));

        // "b" and "c" are pulled forward by the lookahead to fill the page "v" would otherwise
        // have left three-quarters empty; the reel then takes the next page.
        assertEquals(2, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
        assertEquals(3, slides.get(0).getItemCount());
        assertEquals(CommentSlide.TYPE_REEL, slides.get(1).getType());
        assertEquals("v", slides.get(1).getReelComment().getId());
    }

    @Test
    @SmallTest
    public void reelFlushesThePendingTextSlideWhenNothingFits() {
        // Rows of 600 in a 1000px viewport: nothing can be pulled up, so the classic
        // flush-then-reel-then-resume shape survives, in order.
        List<CommentSlide> slides = new CommentSlidePacker(new FakeMeasurer(600), VIEWPORT)
                .pack(Arrays.asList(text("a"), portraitVideo("v"), text("b")));

        assertEquals(3, slides.size());
        assertEquals("a", slides.get(0).getAnchorCommentId());
        assertTrue(slides.get(1).isReel());
        assertEquals("b", slides.get(2).getAnchorCommentId());
    }

    @Test
    @SmallTest
    public void lookaheadFillsTheGapBeforeATallComment() {
        FakeMeasurer measurer = new FakeMeasurer(ROW).withHeight("wide", 900);
        List<CommentSlide> slides = new CommentSlidePacker(measurer, VIEWPORT)
                .pack(Arrays.asList(text("a"), text("wide"), text("b")));

        // "b" (200px) is pulled forward into the space "wide" (900px) could not use.
        assertEquals(2, slides.size());
        assertEquals(2, slides.get(0).getItemCount());
        assertEquals("a", slides.get(0).getItem(0).getComment().getId());
        assertEquals("b", slides.get(0).getItem(1).getComment().getId());
        assertEquals("wide", slides.get(1).getItem(0).getComment().getId());
    }

    @Test
    @SmallTest
    public void lookaheadReachesOnlyAsFarAsItsWindow() {
        List<Comment> comments = Arrays.asList(text("a"), text("wide"), text("big"), text("b"));
        FakeMeasurer measurer =
                new FakeMeasurer(ROW).withHeight("wide", 900).withHeight("big", 900);

        List<CommentSlide> narrow = new CommentSlidePacker(measurer, VIEWPORT,
                CommentSlidePacker.DEFAULT_MAX_COMMENTS_PER_SLIDE, 1).pack(comments);
        List<CommentSlide> wider = new CommentSlidePacker(measurer, VIEWPORT,
                CommentSlidePacker.DEFAULT_MAX_COMMENTS_PER_SLIDE, 2).pack(comments);

        // With a window of 1 only "big" is scanned (and does not fit); a window of 2 reaches
        // "b", which does.
        assertEquals(1, narrow.get(0).getItemCount());
        assertEquals(2, wider.get(0).getItemCount());
        assertEquals("b", wider.get(0).getItem(1).getComment().getId());
    }

    @Test
    @SmallTest
    public void adjacentPortraitVideosEachGetASlide() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(portraitVideo("v1"), portraitVideo("v2")));

        assertEquals(2, slides.size());
        assertTrue(slides.get(0).isReel());
        assertTrue(slides.get(1).isReel());
    }

    @Test
    @SmallTest
    public void portraitVideoRequiresKnownDimensionsAndAVideo() {
        assertTrue(portraitVideo("v").isPortraitVideo());
        assertFalse(landscapeVideo("v").isPortraitVideo());
        assertFalse(video("v").isPortraitVideo()); // Dimensions not known yet.
        assertFalse(squareVideo("v").isPortraitVideo());

        // A portrait *image* is still not a reel: only videos get the full-page treatment.
        Comment tallImage = image("i");
        tallImage.setMediaWidth(720);
        tallImage.setMediaHeight(1280);
        assertFalse(tallImage.isPortraitVideo());
    }

    @Test
    @SmallTest
    public void audioCommentsPackInlineWithText() {
        // A voice note draws as a fixed-height row, so it shares a page rather than claiming one.
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), audio("s"), text("b")));

        assertEquals(1, slides.size());
        assertEquals(CommentSlide.TYPE_TEXT, slides.get(0).getType());
        assertEquals(3, slides.get(0).getItemCount());
    }

    @Test
    @SmallTest
    public void audioIsMediaButNotVisualMedia() {
        Comment clip = audio("s");

        assertTrue(clip.hasAudioMedia());
        assertTrue(clip.hasMedia());
        assertFalse(clip.hasVisualMedia());
    }

    @Test
    @SmallTest
    public void audioDoesNotBreakTheRunOfTextAroundIt() {
        // Unlike a portrait video, audio must not force a page break at all — three short rows
        // share one page. The reel variant still splits: the video takes a page of its own
        // (with "b" pulled up alongside "a" by the lookahead).
        List<CommentSlide> withAudio =
                new CommentSlidePacker(new FakeMeasurer(ROW), VIEWPORT, 10)
                        .pack(Arrays.asList(text("a"), audio("s"), text("b")));
        List<CommentSlide> withReel =
                new CommentSlidePacker(new FakeMeasurer(ROW), VIEWPORT, 10)
                        .pack(Arrays.asList(text("a"), portraitVideo("v"), text("b")));

        assertEquals(1, withAudio.size());
        assertEquals(2, withReel.size());
        assertTrue(withReel.get(1).isReel());
    }

    @Test
    @SmallTest
    public void audioDurationRoundTripsOnTheModel() {
        Comment clip = audio("s");
        clip.setMediaDurationMs(4200);

        assertEquals(4200, clip.getMediaDurationMs());
        assertEquals(0, audio("t").getMediaDurationMs());
    }

    @Test
    @SmallTest
    public void longBodiesAreCutAtTheSharedDisplayLimit() {
        // CommentRowMeasurer and CommentHolder.bind() both render getDisplayContent(), so a page
        // is measured with exactly the text it draws. If this rule moves, both move together.
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Comment.DISPLAY_CONTENT_LIMIT + 50; i++) {
            body.append('x');
        }
        Comment comment = new Comment("long", body.toString(), 0, 0, 0, null, null, null, null,
                null, null, null, null, null, null);

        assertTrue(comment.isContentTruncated());
        assertEquals(Comment.DISPLAY_CONTENT_LIMIT + 3, comment.getDisplayContent().length());
        assertTrue(comment.getDisplayContent().endsWith("..."));
    }

    @Test
    @SmallTest
    public void shortBodiesAreLeftAlone() {
        Comment comment = text("a");

        assertFalse(comment.isContentTruncated());
        assertEquals(comment.getContent(), comment.getDisplayContent());
        assertFalse(comment.isPagerContentClamped());
        assertEquals(comment.getContent(), comment.getPagerDisplayContent());
    }

    @Test
    @SmallTest
    public void pagerContentClampsAtTheWordLimit() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Comment.PAGER_CONTENT_WORD_LIMIT + 5; i++) {
            body.append("word").append(i).append(' ');
        }
        Comment comment = new Comment("long", body.toString().trim(), 0, 0, 0, null, null, null,
                null, null, null, null, null, null, null);

        assertTrue(comment.isPagerContentClamped());
        String shown = comment.getPagerDisplayContent();
        assertTrue(shown.endsWith("…"));
        assertEquals(Comment.PAGER_CONTENT_WORD_LIMIT,
                shown.substring(0, shown.length() - 1).trim().split("\\s+").length);
    }

    @Test
    @SmallTest
    public void pagerContentCountsWordsNotLines() {
        // The clamp is a word budget: a short body keeps its line breaks and is left alone.
        Comment comment = new Comment("nl", "line one\nline two\nline three", 0, 0, 0, null,
                null, null, null, null, null, null, null, null, null);

        assertFalse(comment.isPagerContentClamped());
        assertEquals(comment.getContent(), comment.getPagerDisplayContent());
    }

    @Test
    @SmallTest
    public void expandingAPagerCommentShowsTheFullBody() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Comment.PAGER_CONTENT_WORD_LIMIT + 5; i++) {
            body.append("word").append(i).append(' ');
        }
        Comment comment = new Comment("long", body.toString().trim(), 0, 0, 0, null, null, null,
                null, null, null, null, null, null, null);
        assertTrue(comment.isPagerContentClamped());

        comment.setPagerExpanded(true);

        // Expanded: the full body shows, "see more" goes away, and the measurer — which goes
        // through these same methods — measures the row at its full height for the re-pack.
        assertFalse(comment.isPagerContentClamped());
        assertEquals(comment.getContent(), comment.getPagerDisplayContent());
    }

    @Test
    @SmallTest
    public void pagerContentAtExactlyTheWordLimitIsLeftAlone() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < Comment.PAGER_CONTENT_WORD_LIMIT; i++) {
            body.append("w").append(i).append(' ');
        }
        Comment comment = new Comment("edge", body.toString().trim(), 0, 0, 0, null, null, null,
                null, null, null, null, null, null, null);

        assertFalse(comment.isPagerContentClamped());
        assertEquals(comment.getContent(), comment.getPagerDisplayContent());
    }

    @Test
    @SmallTest
    public void nullBodyDoesNotCrashTheMeasurer() {
        Comment comment = new Comment("n", null, 0, 0, 0, null, null, null, null, null, null, null,
                null, null, null);

        assertFalse(comment.isContentTruncated());
        assertEquals("", comment.getDisplayContent());
    }

    @Test
    @SmallTest
    public void commentTallerThanTheViewportStillGetsASlide() {
        // The measurer truncates, so this should not occur in practice — but dropping the comment
        // or spinning forever would both be worse than one page that overflows. The lookahead
        // pulls "b" up next to "a" before the oversized page.
        FakeMeasurer measurer = new FakeMeasurer(ROW).withHeight("tall", VIEWPORT * 3);
        List<CommentSlide> slides = new CommentSlidePacker(measurer, VIEWPORT)
                                            .pack(Arrays.asList(text("a"), text("tall"), text("b")));

        assertEquals(2, slides.size());
        assertEquals(2, slides.get(0).getItemCount());
        assertEquals("tall", slides.get(1).getItem(0).getComment().getId());
        assertEquals(1, slides.get(1).getItemCount());
    }

    @Test
    @SmallTest
    public void everyCommentSurvivesPackingExactlyOnce() {
        // The lookahead may bend the order, but every comment must land on exactly one page.
        List<Comment> comments = Arrays.asList(text("a"), portraitVideo("v1"), text("b"),
                text("c"), image("i1"), text("d"), text("e"), text("f"), landscapeVideo("v2"),
                text("g"));

        List<String> packed = new ArrayList<>();
        for (CommentSlide slide : packer(VIEWPORT).pack(comments)) {
            for (CommentSlide.Item item : slide.getItems()) {
                packed.add(item.getComment().getId());
            }
        }

        List<String> expected = new ArrayList<>();
        for (Comment comment : comments) {
            expected.add(comment.getId());
        }
        Collections.sort(expected);
        Collections.sort(packed);
        assertEquals(expected, packed);
    }

    @Test
    @SmallTest
    public void orderIsPreservedWhenNoLookaheadIsNeeded() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), text("b"), text("c")));

        assertEquals(1, slides.size());
        assertEquals("a", slides.get(0).getItem(0).getComment().getId());
        assertEquals("b", slides.get(0).getItem(1).getComment().getId());
        assertEquals("c", slides.get(0).getItem(2).getComment().getId());
    }

    @Test
    @SmallTest
    public void nullCommentsAreSkipped() {
        List<CommentSlide> slides =
                packer(VIEWPORT).pack(Arrays.asList(text("a"), null, text("b")));

        assertEquals(1, slides.size());
        assertEquals(2, slides.get(0).getItemCount());
    }

    @Test
    @SmallTest
    public void literalNullMediaUrlIsNotMedia() {
        // The comments API sends the string "null" rather than omitting the field.
        Comment comment = comment("n", "null", "null");

        assertFalse(comment.hasMedia());
        assertEquals(
                CommentSlide.TYPE_TEXT, packer(VIEWPORT).pack(Collections.singletonList(comment))
                                                .get(0)
                                                .getType());
    }

    @Test
    @SmallTest
    public void emptyMediaUrlIsNotMedia() {
        assertFalse(comment("e", "", "").hasMedia());
    }

    @Test
    @SmallTest
    public void slideCarriesMeasurerOutputThrough() {
        Comment comment = text("a");
        CommentSlidePacker.Measurer measurer =
                c -> new CommentSlide.Item(c, "cut short…", true, ROW);

        CommentSlide.Item item = new CommentSlidePacker(measurer, VIEWPORT)
                                         .pack(Collections.singletonList(comment))
                                         .get(0)
                                         .getItem(0);

        assertSame(comment, item.getComment());
        assertEquals("cut short…", item.getDisplayText());
        assertTrue(item.isTruncated());
    }

    @Test
    @SmallTest
    public void anchorIdIsTheFirstCommentOnTheSlide() {
        List<CommentSlide> slides = packer(VIEWPORT).pack(texts("a", "b"));

        assertEquals("a", slides.get(0).getAnchorCommentId());
        assertNull(slides.get(0).getReelComment());
    }

    @Test
    @SmallTest
    public void itemsAreNotModifiableThroughTheSlide() {
        CommentSlide slide = packer(VIEWPORT).pack(texts("a")).get(0);
        try {
            slide.getItems().add(null);
            throw new AssertionError("slide items should be unmodifiable");
        } catch (UnsupportedOperationException expected) {
            // Expected.
        }
    }

    @Test
    @SmallTest
    public void streamOpeningOnAReelPacksAsThatReelThenTheRest() {
        // CommentReelFragment shows reelPage(first) on its own, then appends pack(rest). That is
        // only right if a full pack of the same stream comes out page for page the same.
        List<Comment> stream = Arrays.asList(portraitVideo("r0"), text("a"), landscapeVideo("l"),
                portraitVideo("r1"), text("b"), image("i"), text("c"), portraitVideo("r2"),
                text("d"));
        CommentSlidePacker packer =
                new CommentSlidePacker(new FakeMeasurer(ROW).withHeight("l", 450), VIEWPORT,
                        1200, 4, CommentSlidePacker.DEFAULT_LOOKAHEAD_WINDOW, 40);

        List<CommentSlide> full = packer.pack(stream);
        List<CommentSlide> split = new ArrayList<>();
        split.add(packer.reelPage(stream.get(0)));
        split.addAll(packer.pack(stream.subList(1, stream.size())));

        assertEquals(full.size(), split.size());
        for (int i = 0; i < full.size(); i++) {
            CommentSlide expected = full.get(i);
            CommentSlide actual = split.get(i);
            assertEquals(expected.getType(), actual.getType());
            assertEquals(expected.getPageHeightPx(), actual.getPageHeightPx());
            assertEquals(expected.getPeekHeightPx(), actual.getPeekHeightPx());
            assertSame(expected.getNextReelComment(), actual.getNextReelComment());
            assertEquals(expected.getItemCount(), actual.getItemCount());
            for (int j = 0; j < expected.getItemCount(); j++) {
                assertSame(expected.getItem(j).getComment(), actual.getItem(j).getComment());
            }
        }
        assertTrue(split.get(0).isReel());
        assertSame(stream.get(0), split.get(0).getReelComment());
    }

    private static CommentSlidePacker packer(int viewportHeightPx) {
        return new CommentSlidePacker(new FakeMeasurer(ROW), viewportHeightPx);
    }

    private static List<Comment> texts(String... ids) {
        List<Comment> comments = new ArrayList<>();
        for (String id : ids) {
            comments.add(text(id));
        }
        return comments;
    }

    private static Comment text(String id) {
        return comment(id, null, null);
    }

    /** A video whose dimensions are not (yet) known. */
    private static Comment video(String id) {
        return comment(id, null, "https://example.test/" + id + ".mp4");
    }

    private static Comment portraitVideo(String id) {
        Comment comment = video(id);
        comment.setMediaWidth(720);
        comment.setMediaHeight(1280);
        return comment;
    }

    private static Comment landscapeVideo(String id) {
        Comment comment = video(id);
        comment.setMediaWidth(1280);
        comment.setMediaHeight(720);
        return comment;
    }

    private static Comment squareVideo(String id) {
        Comment comment = video(id);
        comment.setMediaWidth(720);
        comment.setMediaHeight(720);
        return comment;
    }

    private static Comment image(String id) {
        return comment(id, "https://example.test/" + id + ".jpg", null);
    }

    private static Comment audio(String id) {
        Comment comment = comment(id, null, null);
        comment.setMediaAudioUrl("https://example.test/" + id + ".m4a");
        return comment;
    }

    private static Comment comment(String id, String mediaImageUrl, String mediaVideoUrl) {
        return new Comment(id, "content-" + id, 0, 0, 0, null, null, null, null, null,
                mediaImageUrl, mediaVideoUrl, null, null, null);
    }

    /** Returns a caller-controlled height per comment id, so tests can drive the packing. */
    private static class FakeMeasurer implements CommentSlidePacker.Measurer {
        private final Map<String, Integer> mHeights = new HashMap<>();
        private final int mDefaultHeight;

        FakeMeasurer(int defaultHeight) {
            mDefaultHeight = defaultHeight;
        }

        FakeMeasurer withHeight(String commentId, int heightPx) {
            mHeights.put(commentId, heightPx);
            return this;
        }

        @Override
        public CommentSlide.Item measure(Comment comment) {
            Integer height = mHeights.get(comment.getId());
            return new CommentSlide.Item(comment, comment.getContent(), false,
                    height != null ? height : mDefaultHeight);
        }
    }
}

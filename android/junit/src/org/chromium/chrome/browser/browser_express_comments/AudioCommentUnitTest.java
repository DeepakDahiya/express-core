/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

import org.chromium.base.test.BaseRobolectricTestRunner;

import java.util.Arrays;

/** Unit tests for the voice-note waveform and timer. */
@RunWith(BaseRobolectricTestRunner.class)
public class AudioCommentUnitTest {
    private static final int BARS = 36;

    @Test
    @SmallTest
    public void barPatternIsStableForAGivenComment() {
        // The strip is regenerated every time a row is bound. If it were not deterministic the
        // waveform would reshuffle each time the comment scrolled back into view.
        int seed = "comment-abc123".hashCode();

        assertArrayEquals(AudioWaveformView.generateBars(seed, BARS),
                AudioWaveformView.generateBars(seed, BARS), 0f);
    }

    @Test
    @SmallTest
    public void differentCommentsGetDifferentPatterns() {
        assertFalse(Arrays.equals(AudioWaveformView.generateBars(42, BARS),
                AudioWaveformView.generateBars(43, BARS)));
    }

    @Test
    @SmallTest
    public void everyBarIsTallEnoughToDraw() {
        // A zero-height bar renders as an invisible gap in the strip.
        boolean sawTallBar = false;
        for (float height : AudioWaveformView.generateBars(7, BARS)) {
            assertTrue("bar height out of range: " + height, height >= 0.12f && height <= 1f);
            if (height > 0.6f) sawTallBar = true;
        }
        assertTrue("pattern is flat, not speech-like", sawTallBar);
    }

    @Test
    @SmallTest
    public void degenerateBarCountsAreSafe() {
        assertEquals(0, AudioWaveformView.generateBars(1, 0).length);
        assertEquals(0, AudioWaveformView.generateBars(1, -5).length);
        assertEquals(BARS, AudioWaveformView.generateBars(0, BARS).length);
    }

    @Test
    @SmallTest
    public void progressMapsOntoLitBars() {
        assertEquals(0, AudioWaveformView.litBarCount(0f, BARS));
        assertEquals(18, AudioWaveformView.litBarCount(0.5f, BARS));
        assertEquals(BARS, AudioWaveformView.litBarCount(1f, BARS));
    }

    @Test
    @SmallTest
    public void progressOutsideZeroToOneIsClamped() {
        // getCurrentPosition() can briefly overshoot getDuration() near the end of a clip.
        assertEquals(BARS, AudioWaveformView.litBarCount(5f, BARS));
        assertEquals(0, AudioWaveformView.litBarCount(-1f, BARS));
        assertEquals(0, AudioWaveformView.litBarCount(0.5f, 0));
    }

    @Test
    @SmallTest
    public void timerFormatsAsMinutesAndSeconds() {
        assertEquals("0:00", AudioTime.format(0));
        assertEquals("0:07", AudioTime.format(7000));
        assertEquals("1:00", AudioTime.format(60000));
        assertEquals("1:23", AudioTime.format(83000));
    }

    @Test
    @SmallTest
    public void timerRoundsDownAndNeverGoesNegative() {
        assertEquals("0:07", AudioTime.format(7999));
        assertEquals("0:00", AudioTime.format(-500));
    }

    @Test
    @SmallTest
    public void progressPairShowsElapsedOverTotal() {
        assertEquals("0:03 / 0:12", AudioTime.formatProgress(3000, 12000));
    }
}

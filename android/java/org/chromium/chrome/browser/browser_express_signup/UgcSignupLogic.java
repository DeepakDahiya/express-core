/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_signup;

import androidx.annotation.Nullable;

import java.util.regex.Pattern;

/**
 * The sign-up screen's rules without any UI: Indian mobile numbers (ugc-signup-handoff
 * src/logic/phone.ts) and the resend schedule (src/logic/resend.ts).
 */
public final class UgcSignupLogic {
    private static final Pattern VALID_PHONE = Pattern.compile("^[6-9]\\d{9}$");
    private static final Pattern LEADING_PREFIX = Pattern.compile("^(?:91|0)(?=\\d{10}$)");

    /** Seconds before "Resend code" is offered: after the first send, then after each resend. */
    static final int[] RESEND_WAITS_S = {30, 60, 120};
    static final int MAX_RESENDS = 3;
    /** How long "Code sent ✓" shows after a resend before the countdown comes back. */
    static final int SENT_FLASH_S = 3;

    private UgcSignupLogic() {}

    /**
     * The national digits of whatever was typed or pasted ("+91 98765-43210", "098765 43210"):
     * digits only, a leading 91 or 0 dropped when exactly 10 digits follow it, at most 10.
     */
    public static String normalizePhone(@Nullable CharSequence raw) {
        if (raw == null) return "";
        String digits = raw.toString().replaceAll("\\D", "");
        digits = LEADING_PREFIX.matcher(digits).replaceFirst("");
        return digits.length() > 10 ? digits.substring(0, 10) : digits;
    }

    /** "98765 43210": five digits, a space, five digits, formatted as it grows. */
    public static String formatPhone(String digits) {
        return digits.length() <= 5 ? digits : digits.substring(0, 5) + " " + digits.substring(5);
    }

    /** A mobile number: 10 digits starting with 6 to 9. */
    public static boolean isValidPhone(String digits) {
        return VALID_PHONE.matcher(digits).matches();
    }

    /** E.164 for the API. Only for a valid number. */
    public static String toE164(String digits) {
        return "+91" + digits;
    }

    /**
     * The single resend button's schedule for one number. Immutable; times are
     * {@link android.os.SystemClock#elapsedRealtime()} milliseconds.
     *
     * <p>The first send waits 30 s, the first resend 60 s and the second 120 s; after the third
     * there are no more. The server keeps its own limits, so its "wait N seconds" always wins
     * ({@link #atLeast}).
     */
    public static final class ResendState {
        /** Resends used; 0 means only the first send. */
        public final int resends;
        /** When the button becomes available. */
        public final long availableAtMs;
        /** Until when "Code sent ✓" shows; 0 for none. */
        public final long flashUntilMs;

        private ResendState(int resends, long availableAtMs, long flashUntilMs) {
            this.resends = resends;
            this.availableAtMs = availableAtMs;
            this.flashUntilMs = flashUntilMs;
        }

        /** Right after the first send. */
        public static ResendState initial(long nowMs) {
            return new ResendState(0, nowMs + RESEND_WAITS_S[0] * 1000L, 0);
        }

        public boolean canResend(long nowMs) {
            return resends < MAX_RESENDS && nowMs >= availableAtMs;
        }

        /** After a resend at {@code nowMs}; this state again when a resend isn't allowed. */
        public ResendState afterResend(long nowMs) {
            if (!canResend(nowMs)) return this;
            int used = resends + 1;
            int waitS = used < RESEND_WAITS_S.length ? RESEND_WAITS_S[used] : 0;
            return new ResendState(used, nowMs + waitS * 1000L, nowMs + SENT_FLASH_S * 1000L);
        }

        /** The server asked for {@code seconds} more: never offer a resend before then. */
        public ResendState atLeast(int seconds, long nowMs) {
            if (seconds <= 0) return this;
            long until = nowMs + seconds * 1000L;
            return until <= availableAtMs ? this : new ResendState(resends, until, flashUntilMs);
        }

        /** What the button shows at {@code nowMs}. */
        public ResendView viewAt(long nowMs) {
            if (nowMs < flashUntilMs) return new ResendView(ResendView.SENT, 0);
            if (resends >= MAX_RESENDS) return new ResendView(ResendView.LOCKED, 0);
            int left = (int) Math.max(0, (long) Math.ceil((availableAtMs - nowMs) / 1000.0));
            return left > 0
                    ? new ResendView(ResendView.COUNTDOWN, left)
                    : new ResendView(ResendView.READY, 0);
        }
    }

    /** The resend button at one moment. */
    public static final class ResendView {
        public static final int SENT = 0;
        public static final int LOCKED = 1;
        public static final int COUNTDOWN = 2;
        public static final int READY = 3;

        public final int kind;
        /** For {@link #COUNTDOWN}: whole seconds left. */
        public final int secondsLeft;

        ResendView(int kind, int secondsLeft) {
            this.kind = kind;
            this.secondsLeft = secondsLeft;
        }

        public boolean isEnabled() {
            return kind == READY;
        }

        /** "0:30". */
        public String countdownText() {
            int seconds = secondsLeft % 60;
            return (secondsLeft / 60) + ":" + (seconds < 10 ? "0" : "") + seconds;
        }
    }
}

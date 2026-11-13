/* Copyright (c) 2026 The Browser Express Authors. All rights reserved.
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/. */

package org.chromium.chrome.browser.browser_express_comments;

import android.graphics.Rect;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.view.ViewCompat;
import androidx.fragment.app.FragmentManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.bottomsheet.BottomSheetDialogFragment;

import org.chromium.chrome.R;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The reaction picker (Figma "Express / Emoji picker", 483:3250). Any emoji is a reaction: the
 * comment's reactions come first as chips with their counts, the reader's own highlighted, and
 * every other emoji follows in WhatsApp's sections, the reader's recent picks first, with a bar of
 * the sections' icons below to jump between them. Tapping the reader's own reaction, as a chip or
 * in the list, removes it; any other emoji sets it.
 *
 * <p>The choice goes to a listener the card sets when it opens the sheet. A card does not survive
 * the sheet being recreated, so a recreated sheet has nobody to answer to and closes itself.
 */
public class CommentReactionSheet extends BottomSheetDialogFragment {
    public static final String TAG = "CommentReactionSheet";

    private static final String ARG_EMOJI = "emoji";
    private static final String ARG_COUNTS = "counts";
    private static final String ARG_MINE = "mine";

    private static final int COLUMNS = 8;
    /** The design's emoji cell and its distance from the sheet's edges. */
    private static final int CELL_DP = 32;
    private static final int EDGE_DP = 20;
    /** How much of the screen the sheet takes; the emoji scroll inside it. */
    private static final float SHEET_SCREEN_SHARE = 0.5f;
    /** The least the sheet takes, so a short window (landscape) still shows a few rows. */
    private static final int MIN_SHEET_DP = 280;

    /** The jump bar: each section's title and icon, in list order. */
    private static final int[][] TABS = {
        {R.string.browser_express_emoji_recents, R.drawable.ic_browser_express_emoji_tab_recents},
        {R.string.browser_express_emoji_smileys_people,
                R.drawable.ic_browser_express_emoji_tab_smileys},
        {R.string.browser_express_emoji_animals_nature,
                R.drawable.ic_browser_express_emoji_tab_animals},
        {R.string.browser_express_emoji_food_drink, R.drawable.ic_browser_express_emoji_tab_food},
        {R.string.browser_express_emoji_activity, R.drawable.ic_browser_express_emoji_tab_activity},
        {R.string.browser_express_emoji_travel_places,
                R.drawable.ic_browser_express_emoji_tab_travel},
        {R.string.browser_express_emoji_objects, R.drawable.ic_browser_express_emoji_tab_objects},
        {R.string.browser_express_emoji_symbols, R.drawable.ic_browser_express_emoji_tab_symbols},
        {R.string.browser_express_emoji_flags, R.drawable.ic_browser_express_emoji_tab_flags},
    };

    /** Receives the reader's pick. */
    public interface Listener {
        /** @param emoji the new reaction, or null when the reader removed theirs. */
        void onReactionPicked(@Nullable String emoji);
    }

    private @Nullable Listener mListener;
    private @Nullable String mMine;
    private final List<View> mTabs = new ArrayList<>();
    /** Offset that lines the full-width rows up with the design's 20dp edge. */
    private int mRowInset;

    /** Opens the picker for {@code comment}. */
    public static void show(FragmentManager fragmentManager, Comment comment, Listener listener) {
        List<Comment.ReactionCount> reactions = comment.getReactions();
        String[] emoji;
        int[] counts;
        if (reactions.isEmpty()) {
            // Nobody has reacted yet: the classic reactions, without counts, as quick picks.
            emoji = Comment.REACTION_EMOJIS.clone();
            counts = new int[emoji.length];
        } else {
            emoji = new String[reactions.size()];
            counts = new int[reactions.size()];
            for (int i = 0; i < reactions.size(); i++) {
                emoji[i] = reactions.get(i).getEmoji();
                counts[i] = reactions.get(i).getCount();
            }
        }
        Bundle args = new Bundle();
        args.putStringArray(ARG_EMOJI, emoji);
        args.putIntArray(ARG_COUNTS, counts);
        args.putString(ARG_MINE, comment.getMyReaction());

        CommentReactionSheet sheet = new CommentReactionSheet();
        sheet.setArguments(args);
        sheet.mListener = listener;
        sheet.show(fragmentManager, TAG);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NORMAL, R.style.BrowserExpressCommentSheetTheme);
        if (mListener == null) dismissAllowingStateLoss();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
            @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.browser_express_comment_reaction_sheet, container,
                false);
        // Half the screen: the list scrolls inside the sheet rather than the sheet growing over
        // the page. The bottom sheet hosts this view with these params.
        DisplayMetrics metrics = getResources().getDisplayMetrics();
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                Math.max(Math.round(metrics.heightPixels * SHEET_SCREEN_SHARE),
                        Math.round(MIN_SHEET_DP * metrics.density))));
        view.findViewById(R.id.sheet_close).setOnClickListener(v -> dismiss());

        Bundle args = requireArguments();
        mMine = args.getString(ARG_MINE);
        String[] emoji = args.getStringArray(ARG_EMOJI);
        int[] counts = args.getIntArray(ARG_COUNTS);

        PickerAdapter adapter = new PickerAdapter(emoji != null ? emoji : new String[0],
                counts != null ? counts : new int[0]);
        RecyclerView list = view.findViewById(R.id.reaction_picker_list);
        GridLayoutManager layout = new GridLayoutManager(requireContext(), COLUMNS) {
            @Override
            public void onMeasure(@NonNull RecyclerView.Recycler recycler,
                    @NonNull RecyclerView.State state, int widthSpec, int heightSpec) {
                // Spread here, before the cells are laid out. Padding set from a layout listener
                // only reaches a later layout pass, and when the catalog is already loaded none
                // comes, which left the grid and titles flush with the sheet's edges.
                spreadColumns(list, View.MeasureSpec.getSize(widthSpec));
                super.onMeasure(recycler, state, widthSpec, heightSpec);
            }
        };
        layout.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return adapter.getItemViewType(position) == PickerAdapter.TYPE_EMOJI ? 1 : COLUMNS;
            }
        });
        list.setLayoutManager(layout);
        list.setItemAnimator(null);
        list.setAdapter(adapter);
        // The chips and section titles span the grid; this lines them up at the design's edge.
        list.addItemDecoration(new RecyclerView.ItemDecoration() {
            @Override
            public void getItemOffsets(@NonNull Rect outRect, @NonNull View child,
                    @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
                if (parent.getChildViewHolder(child).getItemViewType()
                        == PickerAdapter.TYPE_EMOJI) {
                    return;
                }
                outRect.left = mRowInset;
                outRect.right = mRowInset;
            }
        });

        ViewGroup tabs = view.findViewById(R.id.reaction_picker_tabs);
        mTabs.clear();
        for (int[] tab : TABS) {
            ImageView icon = (ImageView) inflater.inflate(
                    R.layout.browser_express_reaction_picker_tab, tabs, false);
            icon.setImageResource(tab[1]);
            icon.setContentDescription(getString(tab[0]));
            icon.setTag(tab[0]);
            icon.setOnClickListener(v -> jumpTo(list, layout, adapter, tab[0]));
            tabs.addView(icon);
            mTabs.add(icon);
        }
        selectTab(R.string.browser_express_emoji_recents);
        // The section at the top of the list is the one lit up in the bar.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int first = layout.findFirstVisibleItemPosition();
                if (first != RecyclerView.NO_POSITION) selectTab(adapter.sectionAt(first));
            }
        });

        EmojiCatalog.load(sections -> {
            if (!isAdded()) return;
            adapter.addSections(sections);
            // A section with nothing this device can draw has no place to jump to.
            for (View tab : mTabs) {
                tab.setVisibility(adapter.positionOf((Integer) tab.getTag()) >= 0
                                ? View.VISIBLE
                                : View.GONE);
            }
        });
        EdgeToEdgeUtil.padBottomBySystemBars(view);
        return view;
    }

    /**
     * Brings a section's title to the top of the list at once, as WhatsApp does. Recents goes to
     * the very top, so the comment's own reactions show with it.
     */
    private void jumpTo(RecyclerView list, GridLayoutManager layout, PickerAdapter adapter,
            @StringRes int section) {
        int target = section == R.string.browser_express_emoji_recents
                ? 0
                : adapter.positionOf(section);
        if (target < 0) return; // Still finding which emoji this device can draw.
        list.stopScroll();
        layout.scrollToPositionWithOffset(target, 0);
        selectTab(section);
    }

    private void selectTab(@StringRes int section) {
        for (View tab : mTabs) tab.setSelected((Integer) tab.getTag() == section);
    }

    /**
     * Spaces the grid like the design's rows: eight 32dp cells, the first and last 20dp from the
     * sheet's edges. Equal columns get there when the list's side padding is half a column short
     * of that; the other rows take the difference back as offsets to line up at 20dp.
     */
    private void spreadColumns(RecyclerView list, int width) {
        float density = getResources().getDisplayMetrics().density;
        float cell = CELL_DP * density;
        float edge = EDGE_DP * density;
        float pitch = (width - 2 * edge - cell) / (COLUMNS - 1);
        int padding = Math.max(0, Math.round(edge + cell / 2 - pitch / 2));
        int inset = Math.round(edge) - padding;
        if (padding == list.getPaddingLeft() && inset == mRowInset) return;
        list.setPadding(padding, list.getPaddingTop(), padding, list.getPaddingBottom());
        mRowInset = inset;
        list.invalidateItemDecorations();
    }

    private void pick(String emoji) {
        // The reader's own reaction is a toggle, wherever they tap it.
        String reaction = Comment.sameEmoji(emoji, mMine) ? null : emoji;
        if (reaction != null) EmojiCatalog.remember(reaction);
        if (mListener != null) mListener.onReactionPicked(reaction);
        dismiss();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (getDialog() instanceof BottomSheetDialog) {
            BottomSheetBehavior<?> behavior = ((BottomSheetDialog) getDialog()).getBehavior();
            behavior.setSkipCollapsed(true);
            behavior.setState(BottomSheetBehavior.STATE_EXPANDED);
        }
    }

    /** The chips, then Recents and the catalog's sections, each a title over its emoji. */
    private final class PickerAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        static final int TYPE_HEADER = 0;
        static final int TYPE_LABEL = 1;
        static final int TYPE_EMOJI = 2;

        private final String[] mChipEmoji;
        private final int[] mChipCounts;
        /** Per item: its type, and the emoji or the section title. */
        private final List<Integer> mTypes = new ArrayList<>();
        private final List<Object> mValues = new ArrayList<>();
        /** Where each section's title is, by its title. */
        private final Map<Integer, Integer> mSectionStarts = new HashMap<>();

        PickerAdapter(String[] chipEmoji, int[] chipCounts) {
            mChipEmoji = chipEmoji;
            mChipCounts = chipCounts;
            add(TYPE_HEADER, null);
            addSection(R.string.browser_express_emoji_recents, EmojiCatalog.recents());
        }

        void addSections(List<EmojiCatalog.Section> sections) {
            int start = getItemCount();
            for (EmojiCatalog.Section section : sections) addSection(section.title, section.emoji);
            notifyItemRangeInserted(start, getItemCount() - start);
        }

        /** Where a section's title is, or -1 while it isn't in the list. */
        int positionOf(@StringRes int title) {
            Integer start = mSectionStarts.get(title);
            return start != null ? start : -1;
        }

        /** The section an item belongs to; the chips above them all count as Recents. */
        @StringRes
        int sectionAt(int position) {
            int section = R.string.browser_express_emoji_recents;
            int best = -1;
            for (Map.Entry<Integer, Integer> start : mSectionStarts.entrySet()) {
                if (start.getValue() <= position && start.getValue() > best) {
                    best = start.getValue();
                    section = start.getKey();
                }
            }
            return section;
        }

        private void addSection(@StringRes int title, List<String> emoji) {
            mSectionStarts.put(title, getItemCount());
            add(TYPE_LABEL, title);
            for (String one : emoji) add(TYPE_EMOJI, one);
        }

        private void add(int type, @Nullable Object value) {
            mTypes.add(type);
            mValues.add(value);
        }

        @Override
        public int getItemViewType(int position) {
            return mTypes.get(position);
        }

        @Override
        public int getItemCount() {
            return mTypes.size();
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            LayoutInflater inflater = LayoutInflater.from(parent.getContext());
            int layout = type == TYPE_HEADER ? R.layout.browser_express_reaction_picker_header
                    : type == TYPE_LABEL ? R.layout.browser_express_reaction_picker_label
                                         : R.layout.browser_express_reaction_picker_cell;
            View view = inflater.inflate(layout, parent, false);
            if (type == TYPE_LABEL) ViewCompat.setAccessibilityHeading(view, true);
            return new RecyclerView.ViewHolder(view) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            View view = holder.itemView;
            int type = mTypes.get(position);
            if (type == TYPE_EMOJI) {
                String emoji = (String) mValues.get(position);
                ((TextView) view).setText(emoji);
                view.setContentDescription(emoji);
                view.setOnClickListener(v -> pick(emoji));
                return;
            }
            if (type == TYPE_LABEL) {
                ((TextView) view).setText((Integer) mValues.get(position));
            } else {
                bindChips(view.findViewById(R.id.reaction_chips));
            }
        }

        private void bindChips(ViewGroup chips) {
            chips.removeAllViews();
            LayoutInflater inflater = LayoutInflater.from(chips.getContext());
            for (int i = 0; i < mChipEmoji.length; i++) {
                String emoji = mChipEmoji[i];
                int count = mChipCounts[i];
                View chip = inflater.inflate(
                        R.layout.browser_express_comment_reaction_option, chips, false);
                ((TextView) chip.findViewById(R.id.reaction_option_emoji)).setText(emoji);
                TextView countView = chip.findViewById(R.id.reaction_option_count);
                countView.setText(String.valueOf(count));
                countView.setVisibility(count > 0 ? View.VISIBLE : View.GONE);
                chip.setSelected(Comment.sameEmoji(emoji, mMine));
                chip.setContentDescription(count > 0 ? emoji + " " + count : emoji);
                chip.setOnClickListener(v -> pick(emoji));
                chips.addView(chip);
            }
        }
    }
}

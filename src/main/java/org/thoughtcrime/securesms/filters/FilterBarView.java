package org.thoughtcrime.securesms.filters;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.thoughtcrime.securesms.R;

/**
 * A horizontally-scrolling bar of filter chips. Shows: "All" | "Unread" | [custom folders] | "+"
 * Sticky above the chat list (does not scroll vertically with the list).
 */
public class FilterBarView extends HorizontalScrollView {

  /** Callback for a chip long-press; receives the chip view. API-level-agnostic alternative to java.util.function.Consumer. */
  private interface OnLongClickCallback {
    void onLongClick(@NonNull View view);
  }

  public interface Listener {
    void onFilterSelected(@NonNull ActiveFilter filter);

    void onAddFilterTapped();

    void onFilterLongPressed(@NonNull ChatFilter filter, @NonNull View sourceView);
  }

  private LinearLayout chipContainer;
  private @Nullable Listener listener;
  private @NonNull ActiveFilter activeFilter = ActiveFilter.ALL;
  private @NonNull List<ChatFilter> currentFilters = Collections.emptyList();
  private @NonNull Map<String, Integer> badgeCounts = Collections.emptyMap();
  private int allUnreadCount;

  public FilterBarView(@NonNull Context context) {
    super(context);
    init();
  }

  public FilterBarView(@NonNull Context context, @Nullable AttributeSet attrs) {
    super(context, attrs);
    init();
  }

  public FilterBarView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
    super(context, attrs, defStyleAttr);
    init();
  }

  private void init() {
    setHorizontalScrollBarEnabled(false);
    setOverScrollMode(View.OVER_SCROLL_NEVER);
    View root = LayoutInflater.from(getContext()).inflate(R.layout.view_filter_bar, this, true);
    chipContainer = root.findViewById(R.id.filter_chip_container);
  }

  public void setListener(@Nullable Listener listener) {
    this.listener = listener;
  }

  /**
   * Rebuilds all chips with updated data.
   *
   * @param filters custom filters for this account
   * @param active the currently active filter
   * @param badgeCounts unread counts per custom filter id
   * @param allUnreadCount total unread message count (badge for "All" and "Unread")
   */
  public void configure(
      @NonNull List<ChatFilter> filters,
      @NonNull ActiveFilter active,
      @NonNull Map<String, Integer> badgeCounts,
      int allUnreadCount) {
    this.currentFilters = filters;
    this.activeFilter = active;
    this.badgeCounts = badgeCounts;
    this.allUnreadCount = allUnreadCount;
    rebuildChips();
  }

  private void rebuildChips() {
    chipContainer.removeAllViews();

    addChip(
        getContext().getString(R.string.filter_all),
        0,
        activeFilter.equals(ActiveFilter.ALL),
        () -> {
          if (listener != null) listener.onFilterSelected(ActiveFilter.ALL);
        },
        null);

    addChip(
        getContext().getString(R.string.filter_unread),
        allUnreadCount,
        activeFilter.equals(ActiveFilter.UNREAD),
        () -> {
          if (listener != null) listener.onFilterSelected(ActiveFilter.UNREAD);
        },
        null);

    // Custom filter chips
    for (ChatFilter filter : currentFilters) {
      Integer count = badgeCounts.get(filter.getId());
      int badge = count != null ? count : 0;
      boolean isActive =
          activeFilter.getType() == ActiveFilter.Type.CUSTOM
              && filter.getId().equals(activeFilter.getFilterId());
      addChip(
          filter.getName(),
          badge,
          isActive,
          () -> {
            if (listener != null) listener.onFilterSelected(ActiveFilter.custom(filter.getId()));
          },
          chip -> {
            if (listener != null) listener.onFilterLongPressed(filter, chip);
          });
    }

    addAddButton();
  }

  private void addChip(
      @NonNull String label,
      int badgeCount,
      boolean isActive,
      @NonNull Runnable onClick,
      @Nullable OnLongClickCallback onLongClick) {

    View chip =
        LayoutInflater.from(getContext()).inflate(R.layout.item_filter_chip, chipContainer, false);
    ViewGroup.MarginLayoutParams lp =
        new ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.setMarginEnd(dpToPx(8));
    chip.setLayoutParams(lp);

    TextView labelView = chip.findViewById(R.id.filter_chip_label);
    TextView badgeView = chip.findViewById(R.id.filter_chip_badge);

    labelView.setText(label);

    int primaryGreen = ContextCompat.getColor(getContext(), R.color.delta_primary);
    int unreadBlue  = ContextCompat.getColor(getContext(), R.color.unread_count);

    if (isActive) {
      // Solid green background — reliable on both light and dark themes
      chip.setBackground(buildChipBg(primaryGreen, 0, 0));
      labelView.setTextColor(0xFFFFFFFF);
      labelView.setTypeface(labelView.getTypeface(), android.graphics.Typeface.BOLD);
    } else {
      // Surface background + faint stroke for dark-theme visibility
      int bg     = resolveAttrColor(android.R.attr.colorBackground);
      int stroke = 0x33808080;
      chip.setBackground(buildChipBg(bg, dpToPx(1), stroke));
      labelView.setTextColor(resolveAttrColor(android.R.attr.textColorPrimary));
      labelView.setTypeface(labelView.getTypeface(), android.graphics.Typeface.NORMAL);
    }

    if (badgeCount > 0) {
      badgeView.setVisibility(View.VISIBLE);
      badgeView.setText(String.valueOf(badgeCount));
      if (isActive) {
        // White pill on green — number is clearly readable
        badgeView.setBackground(buildChipBg(0xFFFFFFFF, 0, 0));
        badgeView.setTextColor(primaryGreen);
      } else {
        // Blue pill matching existing chat unread indicators
        badgeView.setBackground(buildChipBg(unreadBlue, 0, 0));
        badgeView.setTextColor(0xFFFFFFFF);
      }
    } else {
      badgeView.setVisibility(View.GONE);
    }

    chip.setOnClickListener(v -> onClick.run());
    if (onLongClick != null) {
      chip.setOnLongClickListener(
          v -> {
            onLongClick.onLongClick(v);
            return true;
          });
    }

    chipContainer.addView(chip);
  }

  private void addAddButton() {
    View chip =
        LayoutInflater.from(getContext()).inflate(R.layout.item_filter_chip, chipContainer, false);
    TextView labelView = chip.findViewById(R.id.filter_chip_label);
    chip.findViewById(R.id.filter_chip_badge).setVisibility(View.GONE);

    labelView.setText("+");
    labelView.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
    labelView.setTextColor(ContextCompat.getColor(getContext(), R.color.delta_primary));
    int bg     = resolveAttrColor(android.R.attr.colorBackground);
    int stroke = 0x33808080;
    chip.setBackground(buildChipBg(bg, dpToPx(1), stroke));

    chip.setOnClickListener(v -> {
      if (listener != null) listener.onAddFilterTapped();
    });

    chipContainer.addView(chip);
  }

  private GradientDrawable buildChipBg(int fillColor, int strokeWidth, int strokeColor) {
    GradientDrawable bg = new GradientDrawable();
    bg.setShape(GradientDrawable.RECTANGLE);
    bg.setCornerRadius(dpToPx(20));
    bg.setColor(fillColor);
    if (strokeWidth > 0) bg.setStroke(strokeWidth, strokeColor);
    return bg;
  }

  private int resolveAttrColor(int attr) {
    TypedArray ta = getContext().obtainStyledAttributes(new int[]{attr});
    int color = ta.getColor(0, 0xFF888888);
    ta.recycle();
    return color;
  }

  private int dpToPx(int dp) {
    float density = getContext().getResources().getDisplayMetrics().density;
    return Math.round(dp * density);
  }
}

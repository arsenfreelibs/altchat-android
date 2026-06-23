package org.thoughtcrime.securesms.passcode;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.ViewUtil;

/**
 * Shared numeric passcode keypad (digits 0-9 + backspace) and PIN-dot helpers, used by both the
 * lock screen ({@link PasscodeActivity}) and the create/change screen ({@link PasscodeSetupActivity})
 * so their look and behaviour stay in sync.
 */
public final class PasscodeKeypad {

  /** Receives keypad input. */
  public interface Listener {
    void onDigit(int digit);

    void onBackspace();
  }

  private static final int CELL_HEIGHT_DP = 72;
  private static final int DIGIT_TEXT_SIZE_SP = 26;

  private PasscodeKeypad() {}

  /**
   * Populates {@code grid} (a 3-column {@link GridLayout}) with the keypad. The bottom-left cell is
   * {@code bottomLeftCell} when provided (e.g. a fingerprint button), otherwise an empty spacer.
   *
   * @return the digit and backspace buttons, so the caller can enable/disable them (e.g. lockout).
   *     The bottom-left cell is not included.
   */
  public static List<View> build(
      Context ctx, GridLayout grid, Listener listener, @Nullable View bottomLeftCell) {
    int cellHeight = ViewUtil.dpToPx(ctx, CELL_HEIGHT_DP);
    List<View> buttons = new ArrayList<>();

    for (int digit = 1; digit <= 9; digit++) {
      Button b = digitButton(ctx, digit, listener);
      grid.addView(b, cellParams(cellHeight));
      buttons.add(b);
    }

    grid.addView(bottomLeftCell != null ? bottomLeftCell : new View(ctx), cellParams(cellHeight));

    Button zero = digitButton(ctx, 0, listener);
    grid.addView(zero, cellParams(cellHeight));
    buttons.add(zero);

    ImageButton backspace = borderlessIconButton(
        ctx, R.drawable.ic_passcode_backspace, ctx.getString(R.string.alt_passcode_delete),
        v -> listener.onBackspace());
    grid.addView(backspace, cellParams(cellHeight));
    buttons.add(backspace);

    return buttons;
  }

  /** Creates a flat (borderless) icon button, used for backspace and the fingerprint cell. */
  public static ImageButton borderlessIconButton(
      Context ctx, int iconRes, String contentDescription, View.OnClickListener onClick) {
    ImageButton button = new ImageButton(ctx, null, android.R.attr.borderlessButtonStyle);
    button.setImageResource(iconRes);
    button.setContentDescription(contentDescription);
    button.setScaleType(ImageView.ScaleType.CENTER);
    button.setOnClickListener(onClick);
    return button;
  }

  /** Updates the PIN dots so the first {@code filled} are activated (filled). */
  public static void refreshDots(LinearLayout dotsContainer, int filled) {
    for (int i = 0; i < dotsContainer.getChildCount(); i++) {
      dotsContainer.getChildAt(i).setActivated(i < filled);
    }
  }

  /** Plays a short horizontal shake on {@code view} (wrong-passcode feedback). */
  public static void shake(View view) {
    view.animate().cancel();
    view.setTranslationX(0);
    view.animate()
        .translationXBy(16f)
        .setDuration(40)
        .withEndAction(() -> view.animate().translationX(0).setDuration(120).start())
        .start();
  }

  private static Button digitButton(Context ctx, int digit, Listener listener) {
    Button button = new Button(ctx, null, android.R.attr.borderlessButtonStyle);
    button.setText(String.valueOf(digit));
    button.setTextSize(DIGIT_TEXT_SIZE_SP);
    button.setOnClickListener(v -> listener.onDigit(digit));
    return button;
  }

  private static GridLayout.LayoutParams cellParams(int height) {
    GridLayout.LayoutParams params = new GridLayout.LayoutParams();
    params.width = 0;
    params.height = height;
    params.columnSpec = GridLayout.spec(GridLayout.UNDEFINED, 1, 1f);
    params.rowSpec = GridLayout.spec(GridLayout.UNDEFINED, 1);
    params.setGravity(Gravity.FILL);
    return params;
  }
}

package org.thoughtcrime.securesms;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcMsg;
import java.util.Collections;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class MessageContextOverlay extends LinearLayout {

  private DcContext dcContext;
  private Rpc rpc;
  private DcMsg currentMsg;
  private boolean initialized = false;
  private int positionGeneration;

  // Action items
  private TextView actionReply;
  private TextView actionCopy;
  private TextView actionForward;
  private TextView actionSave;
  private TextView actionDelete;

  public MessageContextOverlay(Context context) {
    super(context);
  }

  public MessageContextOverlay(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  private void init() {
    if (initialized) return;
    initialized = true;
    dcContext = DcHelper.getContext(getContext());
    rpc = DcHelper.getRpc(getContext());
    actionReply = findViewById(R.id.ctx_action_reply);
    actionCopy = findViewById(R.id.ctx_action_copy);
    actionForward = findViewById(R.id.ctx_action_forward);
    actionSave = findViewById(R.id.ctx_action_save);
    actionDelete = findViewById(R.id.ctx_action_delete);
  }

  /**
   * Show the context overlay anchored to the given message item view.
   *
   * @param msg the tapped message
   * @param itemView the RecyclerView item view that was tapped
   * @param minTop optional top bound for the overlay (for spacing to reactions)
   * @param fragment the hosting ConversationFragment (for action delegation)
   */
  public void show(DcMsg msg, View itemView, int minTop, ConversationFragment fragment) {
    init();
    currentMsg = msg;

    DcChat chat = dcContext.getChat(msg.getChatId());

    // ── Bind action items ─────────────────────────────────────────
    // Reply
    boolean canReply = chat.canSend() && ConversationFragment.canReplyToMsg(msg);
    actionReply.setVisibility(canReply ? VISIBLE : GONE);
    if (canReply) {
      actionReply.setOnClickListener(v -> {
        hide();
        ((ConversationActivity) fragment.requireActivity()).handleReplyMessage(msg);
      });
    }

    // Copy
    String text = msg.getText();
    if (text == null) text = "";
    final String finalText = text;
    actionCopy.setOnClickListener(v -> {
      String copyText = finalText.isEmpty() ? msg.getSummarytext(10_000_000) : finalText;
      Util.writeTextToClipboard(getContext(), copyText);
      hide();
    });

    // Forward
    actionForward.setOnClickListener(v -> {
      hide();
      fragment.forwardSingleMessage(msg);
    });

    // Save / Unsave
    boolean canSave = msg.canSave() && !chat.isSelfTalk();
    actionSave.setVisibility(canSave ? VISIBLE : GONE);
    if (canSave) {
      boolean saved = msg.getSavedMsgId() != 0;
      actionSave.setText(saved ? R.string.unsave : R.string.save);
      actionSave.setCompoundDrawablesRelativeWithIntrinsicBounds(
          saved ? R.drawable.baseline_bookmark_remove_24 : R.drawable.baseline_bookmark_border_24,
          0, 0, 0);
      if (actionSave.getCompoundDrawablesRelative()[0] != null) {
        actionSave.getCompoundDrawablesRelative()[0]
            .setTint(getContext().getColor(android.R.color.tab_indicator_text));
      }
      actionSave.setOnClickListener(v -> {
        hide();
        toggleSave(msg);
      });
    }

    // Delete
    actionDelete.setOnClickListener(v -> {
      hide();
      fragment.deleteSingleMessage(msg, msg.getChatId());
    });

    // ── Position the overlay ──────────────────────────────────────
    setVisibility(VISIBLE);
    final int generation = ++positionGeneration;
    post(
        () -> {
          if (generation != positionGeneration
              || getVisibility() != VISIBLE
              || currentMsg == null
              || currentMsg.getId() != msg.getId()) {
            return;
          }
          position(msg, itemView, minTop);
        });
  }

  public void hide() {
    positionGeneration++;
    currentMsg = null;
    setVisibility(GONE);
  }

  /** Called from ConversationFragment scroll listener to keep overlay tracking the message. */
  public void move(int dy) {
    if (currentMsg != null && getVisibility() == VISIBLE) {
      ViewUtil.setTopMargin(this, (int) getY() - dy);
    }
  }

  public boolean isVisible() {
    return getVisibility() == VISIBLE;
  }

  // ── Private helpers ───────────────────────────────────────────────

  private void position(DcMsg msg, View itemView, int minTop) {
    int overlayW = getWidth();
    int overlayH = getHeight();

    // Parent FrameLayout dimensions
    int parentW = ((View) getParent()).getWidth();
    int parentH = ((View) getParent()).getHeight();

    // Horizontal: 18% margin from the respective edge — matches AddReactionView.positionContextMode()
    int hMargin = (int) (parentW * 0.18f);
    int x;
    if (msg.isOutgoing()) {
      x = parentW - overlayW - hMargin;
    } else {
      x = hMargin;
    }
    x = Math.max(0, Math.min(x, parentW - overlayW));

    // Vertical: in context mode (minTop > 0) use deterministic placement from minTop,
    // then clamp to keep all items visible.
    int itemTop = (int) itemView.getY();
    int marginDp8 = (int) dpToPx(8);

    int y = minTop > 0 ? minTop : itemTop + (int) (itemView.getHeight() * 0.35f);
    y = Math.max(y, marginDp8);
    // Hard bottom clamp — must be last so minTop can never push overlay off screen.
    y = Math.min(y, parentH - overlayH - marginDp8);
    y = Math.max(y, marginDp8);

    ViewUtil.setLeftMargin(this, x);
    ViewUtil.setTopMargin(this, y);
  }

  private float dpToPx(float dp) {
    return dp * getContext().getResources().getDisplayMetrics().density;
  }

  private void toggleSave(DcMsg msg) {
    Util.runOnBackground(() -> {
      try {
        if (msg.getSavedMsgId() != 0) {
          rpc.deleteMessages(dcContext.getAccountId(),
              Collections.singletonList(msg.getSavedMsgId()));
        } else {
          rpc.saveMsgs(dcContext.getAccountId(), Collections.singletonList(msg.getId()));
        }
      } catch (RpcException e) {
        e.printStackTrace();
      }
    });
  }
}

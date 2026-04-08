package org.thoughtcrime.securesms;

import android.os.Bundle;
import android.util.TypedValue;
import android.view.Menu;
import android.view.View;
import androidx.annotation.NonNull;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.ViewUtil;

public class ConnectivityActivity extends WebViewActivity implements DcEventCenter.DcEventDelegate {
  @Override
  protected void onCreate(Bundle state, boolean ready) {
    super.onCreate(state, ready);
    setForceDark();
    getSupportActionBar().setTitle(R.string.connectivity);

    // super.onCreate applies paddingTop=statusBarHeight to content_container.
    // With edge-to-edge the decor ActionBar also overlaps content, so we extend the padding.
    if (ViewUtil.isEdgeToEdgeSupported()) {
      View content = findViewById(R.id.content_container);
      TypedValue tv = new TypedValue();
      final int actionBarHeight = getTheme().resolveAttribute(
          androidx.appcompat.R.attr.actionBarSize, tv, true)
          ? TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics())
          : 0;
      ViewCompat.setOnApplyWindowInsetsListener(content, (v, wi) -> {
        Insets ins = Insets.max(
            wi.getInsets(WindowInsetsCompat.Type.systemBars()),
            wi.getInsets(WindowInsetsCompat.Type.displayCutout()));
        v.setPaddingRelative(ins.left, ins.top + actionBarHeight, ins.right, ins.bottom);
        return wi;
      });
      ViewCompat.requestApplyInsets(content);
    }

    refresh();

    DcHelper.getEventCenter(this).addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(this).removeObservers(this);
  }

  @Override
  public boolean onPrepareOptionsMenu(Menu menu) {
    // do not call super.onPrepareOptionsMenu() as the default "Search" menu is not needed
    return true;
  }

  private void refresh() {
    final String connectivityHtml =
        DcHelper.getContext(this)
            .getConnectivityHtml()
            .replace("</style>", " html { color-scheme: dark light; }</style>");
    webView.loadDataWithBaseURL(null, connectivityHtml, "text/html", "utf-8", null);
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    refresh();
  }
}

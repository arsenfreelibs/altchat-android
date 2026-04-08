package org.thoughtcrime.securesms.preferences;

import android.os.Bundle;
import android.view.View;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.preference.PreferenceFragmentCompat;
import androidx.recyclerview.widget.RecyclerView;

public abstract class CorrectedPreferenceFragment extends PreferenceFragmentCompat {
  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
  }

  @Override
  public void onActivityCreated(Bundle savedInstanceState) {
    super.onActivityCreated(savedInstanceState);

    View lv = getView().findViewById(android.R.id.list);
    if (lv != null) {
      lv.setPadding(0, 0, 0, 0);
      if (lv instanceof RecyclerView) ((RecyclerView) lv).setClipToPadding(false);
      ViewCompat.setOnApplyWindowInsetsListener(lv, (v, insets) -> {
        Insets nav = insets.getInsets(WindowInsetsCompat.Type.navigationBars());
        v.setPadding(0, 0, 0, nav.bottom);
        return insets;
      });
    }
  }
}

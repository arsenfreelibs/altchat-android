package org.thoughtcrime.securesms.filters;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * Manages chat folder (filter) storage in SharedPreferences.
 *
 * <p>Data is account-specific. Keys: - {@code chat_custom_filters_<accountId>} → JSON array of
 * ChatFilter objects - {@code chat_filter_map_<accountId>} → JSON object mapping chatId →
 * filterId
 */
public class FilterManager {

  public static final int MAX_CUSTOM_FILTERS = 8;

  private static final String TAG = FilterManager.class.getSimpleName();
  private static final String KEY_CUSTOM_FILTERS = "chat_custom_filters_";
  private static final String KEY_FILTER_MAP = "chat_filter_map_";

  private final Context context;
  private final int accountId;

  public FilterManager(@NonNull Context context) {
    this.context = context.getApplicationContext();
    this.accountId = DcHelper.getContext(context).getAccountId();
  }

  // ── filters CRUD ──────────────────────────────────────────────────────────

  public @NonNull List<ChatFilter> loadCustomFilters() {
    String json = prefs().getString(filtersKey(), "[]");
    List<ChatFilter> result = new ArrayList<>();
    try {
      JSONArray arr = new JSONArray(json);
      for (int i = 0; i < arr.length(); i++) {
        result.add(ChatFilter.fromJson(arr.getJSONObject(i)));
      }
    } catch (JSONException e) {
      Log.w(TAG, "Failed to load custom filters", e);
    }
    return result;
  }

  public void saveCustomFilters(@NonNull List<ChatFilter> filters) {
    JSONArray arr = new JSONArray();
    for (ChatFilter f : filters) {
      try {
        arr.put(f.toJson());
      } catch (JSONException ignore) {
      }
    }
    prefs().edit().putString(filtersKey(), arr.toString()).apply();
  }

  /** Creates a new ChatFilter with a fresh UUID. Does NOT persist — call saveCustomFilters(). */
  public @NonNull ChatFilter createFilter(@NonNull String name) {
    return new ChatFilter(UUID.randomUUID().toString(), name);
  }

  // ── chat ↔ filter assignments ─────────────────────────────────────────────

  /** Assigns a chat to a filter, replacing any prior assignment. */
  public void assignChat(int chatId, @NonNull String filterId) {
    Map<String, String> map = loadFilterMap();
    map.put(String.valueOf(chatId), filterId);
    saveFilterMap(map);
  }

  /** Removes a chat from all filters. */
  public void removeChatFromFilter(int chatId) {
    Map<String, String> map = loadFilterMap();
    map.remove(String.valueOf(chatId));
    saveFilterMap(map);
  }

  /** Returns the filterId the chat belongs to, or null if unassigned. */
  public @Nullable String filterIdForChat(int chatId) {
    return loadFilterMap().get(String.valueOf(chatId));
  }

  /** Returns all chatIds assigned to the given filterId. */
  public @NonNull List<Integer> chatIds(@NonNull String filterId) {
    List<Integer> result = new ArrayList<>();
    for (Map.Entry<String, String> entry : loadFilterMap().entrySet()) {
      if (filterId.equals(entry.getValue())) {
        try {
          result.add(Integer.parseInt(entry.getKey()));
        } catch (NumberFormatException ignore) {
        }
      }
    }
    return result;
  }

  /**
   * Loads the filter map once and returns it partitioned by filterId.
   * Use this instead of calling {@link #chatIds(String)} in a loop to avoid O(N) disk reads.
   */
  public @NonNull Map<String, List<Integer>> chatIdsByFilterId() {
    Map<String, List<Integer>> result = new HashMap<>();
    for (Map.Entry<String, String> entry : loadFilterMap().entrySet()) {
      String fid = entry.getValue();
      List<Integer> list = result.get(fid);
      if (list == null) {
        list = new ArrayList<>();
        result.put(fid, list);
      }
      try {
        list.add(Integer.parseInt(entry.getKey()));
      } catch (NumberFormatException ignore) {}
    }
    return result;
  }

  /** Removes all chat assignments for a filter. Call before deleting the filter. */
  public void removeFilterAssignments(@NonNull String filterId) {
    Map<String, String> map = loadFilterMap();
    Iterator<Map.Entry<String, String>> it = map.entrySet().iterator();
    while (it.hasNext()) {
      if (filterId.equals(it.next().getValue())) it.remove();
    }
    saveFilterMap(map);
  }

  // ── private helpers ────────────────────────────────────────────────────────

  private SharedPreferences prefs() {
    return PreferenceManager.getDefaultSharedPreferences(context);
  }

  private String filtersKey() {
    return KEY_CUSTOM_FILTERS + accountId;
  }

  private String mapKey() {
    return KEY_FILTER_MAP + accountId;
  }

  private @NonNull Map<String, String> loadFilterMap() {
    String json = prefs().getString(mapKey(), "{}");
    Map<String, String> map = new HashMap<>();
    try {
      JSONObject obj = new JSONObject(json);
      Iterator<String> keys = obj.keys();
      while (keys.hasNext()) {
        String key = keys.next();
        map.put(key, obj.getString(key));
      }
    } catch (JSONException e) {
      Log.w(TAG, "Failed to load filter map", e);
    }
    return map;
  }

  private void saveFilterMap(@NonNull Map<String, String> map) {
    JSONObject obj = new JSONObject();
    for (Map.Entry<String, String> entry : map.entrySet()) {
      try {
        obj.put(entry.getKey(), entry.getValue());
      } catch (JSONException ignore) {
      }
    }
    prefs().edit().putString(mapKey(), obj.toString()).apply();
  }
}

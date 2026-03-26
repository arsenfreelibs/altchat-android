/*
 * Copyright (C) 2015 Open Whisper Systems
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.thoughtcrime.securesms;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.b44t.messenger.DcChat;
import com.b44t.messenger.DcChatlist;
import com.b44t.messenger.DcContext;
import com.b44t.messenger.DcEvent;
import com.b44t.messenger.DcMsg;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import org.thoughtcrime.securesms.ConversationListAdapter.ItemClickListener;
import org.thoughtcrime.securesms.components.recyclerview.DeleteItemAnimator;
import org.thoughtcrime.securesms.components.reminder.DozeReminder;
import org.thoughtcrime.securesms.connect.DcEventCenter;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.filters.ActiveFilter;
import org.thoughtcrime.securesms.filters.ChatFilter;
import org.thoughtcrime.securesms.filters.FilterBarView;
import org.thoughtcrime.securesms.filters.FilterManager;
import org.thoughtcrime.securesms.mms.GlideApp;
import org.thoughtcrime.securesms.notifications.FcmReceiveService;
import org.thoughtcrime.securesms.permissions.Permissions;
import org.thoughtcrime.securesms.util.Prefs;
import org.thoughtcrime.securesms.util.ShareUtil;
import org.thoughtcrime.securesms.util.Util;
import org.thoughtcrime.securesms.util.ViewUtil;

public class ConversationListFragment extends BaseConversationListFragment
    implements ItemClickListener, DcEventCenter.DcEventDelegate {
  public static final String ARCHIVE = "archive";
  public static final String RELOAD_LIST = "reload_list";

  private static final String TAG = ConversationListFragment.class.getSimpleName();
  private static final String STATE_FILTER_TYPE = "filter_type";
  private static final String STATE_FILTER_ID   = "filter_id";

  private RecyclerView list;
  private FilterBarView filterBar;
  private View emptyState;
  private TextView emptySearch;
  private final String queryFilter = "";
  private boolean archive;
  private Timer reloadTimer;
  private boolean chatlistJustLoaded;
  private boolean reloadTimerInstantly;

  private volatile @NonNull ActiveFilter activeFilter = ActiveFilter.ALL;
  private volatile FilterManager filterManager;

  @Override
  public void onCreate(Bundle icicle) {
    super.onCreate(icicle);
    archive = getArguments().getBoolean(ARCHIVE, false);
    if (icicle != null) {
      String typeStr = icicle.getString(STATE_FILTER_TYPE);
      if (typeStr != null) {
        try {
          ActiveFilter.Type type = ActiveFilter.Type.valueOf(typeStr);
          String filterId = icicle.getString(STATE_FILTER_ID);
          if (type == ActiveFilter.Type.CUSTOM && filterId != null) {
            activeFilter = ActiveFilter.custom(filterId);
          } else if (type == ActiveFilter.Type.UNREAD) {
            activeFilter = ActiveFilter.UNREAD;
          }
        } catch (IllegalArgumentException ignored) {}
      }
    }

    DcEventCenter eventCenter = DcHelper.getEventCenter(requireActivity());
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_INCOMING_MSG, this);
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_MSGS_NOTICED, this);
    eventCenter.addMultiAccountObserver(DcContext.DC_EVENT_CHAT_DELETED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CHAT_MODIFIED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CONTACTS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSGS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_DELIVERED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_FAILED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_MSG_READ, this);
    eventCenter.addObserver(DcContext.DC_EVENT_REACTIONS_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_CONNECTIVITY_CHANGED, this);
    eventCenter.addObserver(DcContext.DC_EVENT_SELFAVATAR_CHANGED, this);
  }

  @Override
  public void onDestroy() {
    super.onDestroy();
    DcHelper.getEventCenter(requireActivity()).removeObservers(this);
  }

  @SuppressLint("RestrictedApi")
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle bundle) {
    final View view = inflater.inflate(R.layout.conversation_list_fragment, container, false);

    list = ViewUtil.findById(view, R.id.list);
    filterBar = ViewUtil.findById(view, R.id.filter_bar);
    fab = ViewUtil.findById(view, R.id.fab);
    emptyState = ViewUtil.findById(view, R.id.empty_state);
    emptySearch = ViewUtil.findById(view, R.id.empty_search);

    // add padding to avoid content hidden behind system bars
    ViewUtil.applyWindowInsets(list, true, archive, true, true);

    if (archive) {
      fab.setVisibility(View.GONE);
      TextView emptyTitle = ViewUtil.findById(view, R.id.empty_title);
      emptyTitle.setText(R.string.archive_empty_hint);
    } else {
      fab.setVisibility(View.VISIBLE);
    }
    // Apply insets to prevent fab from being covered by system bars
    ViewUtil.applyWindowInsetsAsMargin(fab);

    list.setHasFixedSize(true);
    list.setLayoutManager(new LinearLayoutManager(getActivity()));
    list.setItemAnimator(new DeleteItemAnimator());

    return view;
  }

  @Override
  public void onActivityCreated(Bundle bundle) {
    super.onActivityCreated(bundle);

    setHasOptionsMenu(true);
    initializeFabClickListener(false);
    list.setAdapter(new ConversationListAdapter(requireActivity(), GlideApp.with(this), this));

    if (!archive && !ShareUtil.isRelayingMessageContent(getActivity())) {
      filterManager = new FilterManager(requireActivity());
      filterBar.setVisibility(View.VISIBLE);
      filterBar.setListener(new FilterBarView.Listener() {
        @Override
        public void onFilterSelected(@NonNull ActiveFilter filter) {
          activeFilter = filter;
          loadChatlistAsync();
        }

        @Override
        public void onAddFilterTapped() {
          promptCreateFilter();
        }

        @Override
        public void onFilterLongPressed(@NonNull ChatFilter filter, @NonNull View sourceView) {
          promptManageFilter(filter);
        }
      });
    }

    loadChatlistAsync();
    chatlistJustLoaded = true;
  }

  @Override
  public void onResume() {
    super.onResume();

    updateReminders();

    if (requireActivity().getIntent().getIntExtra(RELOAD_LIST, 0) == 1 && !chatlistJustLoaded) {
      loadChatlistAsync();
      reloadTimerInstantly = false;
    }
    chatlistJustLoaded = false;

    reloadTimer = new Timer();
    reloadTimer.scheduleAtFixedRate(
        new TimerTask() {
          @Override
          public void run() {
            Util.runOnMain(
                () -> {
                  list.getAdapter().notifyDataSetChanged();
                });
          }
        },
        reloadTimerInstantly ? 0 : 60 * 1000,
        60 * 1000);
  }

  @Override
  public void onPause() {
    super.onPause();

    reloadTimer.cancel();
    reloadTimerInstantly = true;

    fab.stopPulse();
  }

  @Override
  public void onSaveInstanceState(@NonNull Bundle outState) {
    super.onSaveInstanceState(outState);
    outState.putString(STATE_FILTER_TYPE, activeFilter.getType().name());
    String filterId = activeFilter.getFilterId();
    if (filterId != null) outState.putString(STATE_FILTER_ID, filterId);
  }

  public void onNewIntent() {
    initializeFabClickListener(actionMode != null);
  }

  @Override
  public BaseConversationListAdapter getListAdapter() {
    return (BaseConversationListAdapter) list.getAdapter();
  }

  @SuppressLint({"StaticFieldLeak", "NewApi"})
  private void updateReminders() {
    // by the time onPostExecute() is asynchronously run, getActivity() might return null, so get
    // the activity here:
    Activity activity = requireActivity();
    new AsyncTask<Context, Void, Void>() {
      @Override
      protected Void doInBackground(Context... params) {
        final Context context = params[0];
        try {
          if (DozeReminder.isEligible(context)) {
            DozeReminder.addDozeReminderDeviceMsg(context);
          }
          FcmReceiveService.waitForRegisterFinished();
        } catch (Exception e) {
          e.printStackTrace();
        }
        return null;
      }

      @Override
      protected void onPostExecute(Void result) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          if (!Prefs.getBooleanPreference(
              activity, Prefs.ASKED_FOR_NOTIFICATION_PERMISSION, false)) {
            Prefs.setBooleanPreference(activity, Prefs.ASKED_FOR_NOTIFICATION_PERMISSION, true);
            Permissions.with(activity)
                .request(Manifest.permission.POST_NOTIFICATIONS)
                .ifNecessary()
                .onAllGranted(
                    () -> {
                      DozeReminder.maybeAskDirectly(activity);
                    })
                .onAnyDenied(
                    () -> {
                      final DcContext dcContext = DcHelper.getContext(activity);
                      DcMsg msg = new DcMsg(dcContext, DcMsg.DC_MSG_TEXT);
                      msg.setText(
                          "\uD83D\uDC49 "
                              + activity.getString(R.string.notifications_disabled)
                              + " \uD83D\uDC48\n\n"
                              + activity.getString(
                                  R.string.perm_explain_access_to_notifications_denied));
                      dcContext.addDeviceMsg("android.notifications-disabled", msg);
                    })
                .execute();
          } else {
            DozeReminder.maybeAskDirectly(activity);
          }
        } else {
          DozeReminder.maybeAskDirectly(activity);
        }
      }
    }.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, activity);
  }

  private final Object loadChatlistLock = new Object();
  private boolean inLoadChatlist;
  private boolean needsAnotherLoad;

  public void loadChatlistAsync() {
    synchronized (loadChatlistLock) {
      needsAnotherLoad = true;
      if (inLoadChatlist) {
        Log.i(TAG, "chatlist loading debounced");
        return;
      }
      inLoadChatlist = true;
    }

    Util.runOnAnyBackgroundThread(
        () -> {
          while (true) {
            synchronized (loadChatlistLock) {
              if (!needsAnotherLoad) {
                inLoadChatlist = false;
                return;
              }
              needsAnotherLoad = false;
            }

            Log.i(TAG, "executing debounced chatlist loading");
            loadChatlist();
            Util.sleep(100);
          }
        });
  }

  private void loadChatlist() {
    int listflags = 0;
    if (archive) {
      listflags |= DcContext.DC_GCL_ARCHIVED_ONLY;
    } else if (ShareUtil.isRelayingMessageContent(getActivity())) {
      listflags |= DcContext.DC_GCL_FOR_FORWARDING;
    } else {
      listflags |= DcContext.DC_GCL_ADD_ALLDONE_HINT;
    }

    Context context = getContext();
    if (context == null) {
      // can't load chat list at this time, see:
      // https://github.com/deltachat/deltachat-android/issues/2012
      Log.w(TAG, "Ignoring call to loadChatlist()");
      return;
    }
    DcChatlist chatlist =
        DcHelper.getContext(context)
            .getChatlist(listflags, queryFilter.isEmpty() ? null : queryFilter, 0);

    // Capture volatile fields once to ensure a consistent view for this entire background load
    final FilterManager fm = filterManager;
    final ActiveFilter af = activeFilter;

    // Load the filter map once — shared by computeFilteredIndices and badge computation
    final Map<String, List<Integer>> chatIdsByFilter =
        fm != null ? fm.chatIdsByFilterId() : null;

    // Compute filter indices on the background thread
    final int[] filteredIndices = computeFilteredIndices(chatlist, context, chatIdsByFilter, fm, af);

    // Compute filter bar badge data on the background thread to avoid disk I/O on the main thread
    final List<ChatFilter> filterBarFilters;
    final Map<String, Integer> filterBarBadges;
    final int filterBarAllUnread;
    final int filterBarUnreadChats;
    if (fm != null) {
      List<ChatFilter> filters = fm.loadCustomFilters();
      DcContext dcCtx = DcHelper.getContext(context);
      int allUnread = 0;
      int unreadChats = 0;
      for (int i = 0; i < chatlist.getCnt(); i++) {
        int chatId = chatlist.getChatId(i);
        if (chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL) continue;
        int fresh = dcCtx.getFreshMsgCount(chatId);
        if (fresh > 0) { allUnread += fresh; unreadChats++; }
      }
      Map<String, Integer> badges = new HashMap<>();
      for (ChatFilter f : filters) {
        List<Integer> chatIds = chatIdsByFilter != null ? chatIdsByFilter.get(f.getId()) : null;
        if (chatIds == null) continue;
        int count = 0;
        for (int chatId : chatIds) count += dcCtx.getFreshMsgCount(chatId);
        if (count > 0) badges.put(f.getId(), count);
      }
      filterBarFilters = filters;
      filterBarBadges = badges;
      filterBarAllUnread = allUnread;
      filterBarUnreadChats = unreadChats;
    } else {
      filterBarFilters = null;
      filterBarBadges = null;
      filterBarAllUnread = 0;
      filterBarUnreadChats = 0;
    }

    Util.runOnMain(
        () -> {
          // Determine visible count for empty-state detection
          int visibleCount = filteredIndices != null ? filteredIndices.length : chatlist.getCnt();

          if (visibleCount <= 0 && TextUtils.isEmpty(queryFilter)) {
            list.setVisibility(View.INVISIBLE);
            emptyState.setVisibility(View.VISIBLE);
            emptySearch.setVisibility(View.INVISIBLE);
            fab.startPulse(3 * 1000);
          } else if (visibleCount <= 0 && !TextUtils.isEmpty(queryFilter)) {
            list.setVisibility(View.INVISIBLE);
            emptyState.setVisibility(View.GONE);
            emptySearch.setVisibility(View.VISIBLE);
            emptySearch.setText(getString(R.string.search_no_result_for_x, queryFilter));
          } else {
            list.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
            emptySearch.setVisibility(View.INVISIBLE);
            fab.stopPulse();
          }

          ((ConversationListAdapter) list.getAdapter()).changeData(chatlist, filteredIndices);
          if (filterBarFilters != null && filterBar != null && filterBar.getVisibility() == View.VISIBLE) {
            filterBar.configure(filterBarFilters, activeFilter, filterBarBadges, filterBarAllUnread, filterBarUnreadChats);
          }
        });
  }

  /**
   * Computes which chatlist indices to show for the current activeFilter.
   * Returns null = show all (for ALL filter or archive/forwarding modes).
   */
  private int[] computeFilteredIndices(DcChatlist chatlist, Context context, Map<String, List<Integer>> chatIdsByFilter, FilterManager fm, ActiveFilter af) {
    if (fm == null) return null; // archive or forwarding mode

    int cnt = chatlist.getCnt();
    DcContext dcContext = DcHelper.getContext(context);

    switch (af.getType()) {
      case ALL:
        return null; // show all

      case UNREAD: {
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < cnt; i++) {
          int chatId = chatlist.getChatId(i);
          // Always include archived link
          if (chatId == DcChat.DC_CHAT_ID_ARCHIVED_LINK) {
            indices.add(i);
            continue;
          }
          if (chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL) continue;
          if (dcContext.getFreshMsgCount(chatId) > 0) {
            indices.add(i);
          }
        }
        return toIntArray(indices);
      }

      case CUSTOM: {
        String filterId = af.getFilterId();
        if (filterId == null) return null;
        List<Integer> assignedIds = chatIdsByFilter != null ? chatIdsByFilter.get(filterId) : null;
        if (assignedIds == null) return new int[0];
        Set<Integer> chatIdSet = new HashSet<>(assignedIds);
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < cnt; i++) {
          int chatId = chatlist.getChatId(i);
          if (chatId == DcChat.DC_CHAT_ID_ARCHIVED_LINK) {
            indices.add(i);
            continue;
          }
          if (chatId <= DcChat.DC_CHAT_ID_LAST_SPECIAL) continue;
          if (chatIdSet.contains(chatId)) {
            indices.add(i);
          }
        }
        return toIntArray(indices);
      }

      default:
        return null;
    }
  }

  private static int[] toIntArray(List<Integer> list) {
    int[] arr = new int[list.size()];
    for (int i = 0; i < list.size(); i++) arr[i] = list.get(i);
    return arr;
  }

  // ── filter CRUD dialogs ────────────────────────────────────────────────────

  private void promptCreateFilter() {
    if (filterManager == null) return;
    List<ChatFilter> existing = filterManager.loadCustomFilters();
    if (existing.size() >= FilterManager.MAX_CUSTOM_FILTERS) {
      new AlertDialog.Builder(requireActivity())
          .setMessage(R.string.filter_max_reached)
          .setPositiveButton(android.R.string.ok, null)
          .show();
      return;
    }

    EditText input = new EditText(requireActivity());
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setHint(R.string.filter_name_hint);

    new AlertDialog.Builder(requireActivity())
        .setTitle(R.string.filter_add)
        .setView(input)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              String name = input.getText().toString().trim();
              if (TextUtils.isEmpty(name)) return;
              List<ChatFilter> filters = filterManager.loadCustomFilters();
              filters.add(filterManager.createFilter(name));
              filterManager.saveCustomFilters(filters);
              loadChatlistAsync();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void promptManageFilter(ChatFilter filter) {
    new AlertDialog.Builder(requireActivity())
        .setItems(
            new CharSequence[] {
              getString(R.string.filter_rename), getString(R.string.filter_delete)
            },
            (dialog, which) -> {
              if (which == 0) promptRenameFilter(filter);
              else deleteFilter(filter);
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void promptRenameFilter(ChatFilter filter) {
    EditText input = new EditText(requireActivity());
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
    input.setText(filter.getName());
    input.selectAll();

    new AlertDialog.Builder(requireActivity())
        .setTitle(R.string.filter_rename)
        .setView(input)
        .setPositiveButton(
            android.R.string.ok,
            (dialog, which) -> {
              String name = input.getText().toString().trim();
              if (TextUtils.isEmpty(name)) return;
              List<ChatFilter> filters = filterManager.loadCustomFilters();
              for (ChatFilter f : filters) {
                if (f.getId().equals(filter.getId())) {
                  f.setName(name);
                  break;
                }
              }
              filterManager.saveCustomFilters(filters);
              loadChatlistAsync();
            })
        .setNegativeButton(android.R.string.cancel, null)
        .show();
  }

  private void deleteFilter(ChatFilter filter) {
    if (filterManager == null) return;
    List<ChatFilter> filters = filterManager.loadCustomFilters();
    filters.removeIf(f -> f.getId().equals(filter.getId()));
    filterManager.saveCustomFilters(filters);
    filterManager.removeFilterAssignments(filter.getId());
    if (activeFilter.getType() == ActiveFilter.Type.CUSTOM
        && filter.getId().equals(activeFilter.getFilterId())) {
      activeFilter = ActiveFilter.ALL;
    }
    loadChatlistAsync();
  }

  @Override
  protected boolean offerToArchive() {
    return !archive;
  }

  @Override
  protected FilterManager getFilterManager() {
    return filterManager;
  }

  @Override
  protected void onFilterAssignmentChanged() {
    loadChatlistAsync();
  }

  @Override
  protected void setFabVisibility(boolean isActionMode) {
    fab.setVisibility((isActionMode || !archive) ? View.VISIBLE : View.GONE);
  }

  @Override
  public void onItemClick(ConversationListItem item) {
    onItemClick(item.getChatId());
  }

  @Override
  public void onItemLongClick(ConversationListItem item) {
    onItemLongClick(item.getChatId());
  }

  @Override
  public void onSwitchToArchive() {
    ((ConversationSelectedListener) requireActivity()).onSwitchToArchive();
  }

  @Override
  public void handleEvent(@NonNull DcEvent event) {
    final int accId = event.getAccountId();
    if (event.getId() == DcContext.DC_EVENT_CHAT_DELETED) {
      DcHelper.getNotificationCenter(requireActivity())
          .removeNotifications(accId, event.getData1Int());
    } else if (accId != DcHelper.getContext(requireActivity()).getAccountId()) {
      Activity activity = getActivity();
      if (activity instanceof ConversationListActivity) {
        ((ConversationListActivity) activity).refreshUnreadIndicator();
      }

    } else if (event.getId() == DcContext.DC_EVENT_CONNECTIVITY_CHANGED) {
      Activity activity = getActivity();
      if (activity instanceof ConversationListActivity) {
        ((ConversationListActivity) activity).refreshTitle();
      }

    } else if (event.getId() == DcContext.DC_EVENT_SELFAVATAR_CHANGED) {
      Activity activity = getActivity();
      if (activity instanceof ConversationListActivity) {
        ((ConversationListActivity) activity).refreshAvatar();
      }

    } else {
      loadChatlistAsync();
    }
  }
}

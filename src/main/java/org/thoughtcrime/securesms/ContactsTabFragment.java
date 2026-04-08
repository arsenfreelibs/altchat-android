package org.thoughtcrime.securesms;

import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SearchView;
import androidx.appcompat.widget.Toolbar;
import androidx.fragment.app.Fragment;
import android.view.MenuItem;
import com.b44t.messenger.DcContact;
import com.b44t.messenger.DcContext;
import org.thoughtcrime.securesms.connect.DcHelper;

/**
 * The "Contacts" tab fragment hosted inside {@link ConversationListActivity}.
 *
 * <p>Shows the full list of contacts via {@link ContactSelectionListFragment} and opens a chat
 * when a contact is selected, instead of navigating to a separate "new chat" screen.
 */
public class ContactsTabFragment extends Fragment
    implements ContactSelectionListFragment.OnContactSelectedListener {

  private Toolbar toolbar;
  private SearchView searchView;
  private ContactSelectionListFragment contactsListFragment;

  @Nullable
  @Override
  public View onCreateView(@NonNull LayoutInflater inflater,
                           @Nullable ViewGroup container,
                           @Nullable Bundle savedInstanceState) {
    return inflater.inflate(R.layout.fragment_contacts_tab, container, false);
  }

  @Override
  public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
    super.onViewCreated(view, savedInstanceState);

    toolbar = view.findViewById(R.id.toolbar);
    // Do NOT call setSupportActionBar here — this fragment starts hidden.
    // The toolbar is set in onHiddenChanged(false) when the tab becomes visible.

    if (savedInstanceState == null) {
      contactsListFragment = new ContactSelectionListFragment();
      getChildFragmentManager().beginTransaction()
          .replace(R.id.contacts_container, contactsListFragment)
          .commit();
    } else {
      contactsListFragment = (ContactSelectionListFragment)
          getChildFragmentManager().findFragmentById(R.id.contacts_container);
    }

    if (contactsListFragment != null) {
      contactsListFragment.setOnContactSelectedListener(this);
    }
  }

  public void reattachToolbar() {
    if (toolbar == null || !isAdded()) return;
    toolbar.setTitle(R.string.contacts_headline);
    toolbar.getMenu().clear();
    toolbar.inflateMenu(R.menu.contacts_search);
    MenuItem searchItem = toolbar.getMenu().findItem(R.id.action_search_contacts);
    searchView = (SearchView) searchItem.getActionView();
    if (searchView != null) {
      searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
        @Override
        public boolean onQueryTextSubmit(String query) {
          if (contactsListFragment != null) contactsListFragment.setQueryFilter(query);
          return true;
        }
        @Override
        public boolean onQueryTextChange(String newText) {
          if (contactsListFragment != null) contactsListFragment.setQueryFilter(newText);
          return true;
        }
      });
    }
  }

  @Override
  public void onHiddenChanged(boolean hidden) {
    super.onHiddenChanged(hidden);
    if (hidden) {
      if (searchView != null && !searchView.isIconified()) {
        searchView.setQuery("", false);
        searchView.setIconified(true);
      }
    } else {
      reattachToolbar();
    }
  }

  @Override
  public void onResume() {
    super.onResume();
    // Re-attach the listener after the fragment is re-attached (e.g. tab switch)
    if (contactsListFragment != null) {
      contactsListFragment.setOnContactSelectedListener(this);
    }
  }

  // ContactSelectionListFragment.OnContactSelectedListener

  @Override
  public void onContactSelected(int contactId) {
    DcContext dcContext = DcHelper.getContext(requireActivity());
    int chatId = dcContext.getChatIdByContactId(contactId);
    if (chatId != 0) {
      openConversation(chatId);
    } else {
      String name = dcContext.getContact(contactId).getDisplayName();
      new AlertDialog.Builder(requireActivity())
          .setMessage(getString(R.string.ask_start_chat_with, name))
          .setCancelable(true)
          .setNegativeButton(android.R.string.cancel, null)
          .setPositiveButton(android.R.string.ok,
              (dialog, which) -> openConversation(dcContext.createChatByContactId(contactId)))
          .show();
    }
  }

  @Override
  public void onContactDeselected(int contactId) {
    // no-op for single-selection contacts tab
  }

  private void openConversation(int chatId) {
    Intent intent = new Intent(requireActivity(), ConversationActivity.class);
    intent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA,
        DcHelper.getContext(requireActivity()).getAccountId());
    intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
    startActivity(intent);
    requireActivity().overridePendingTransition(R.anim.slide_from_right, R.anim.fade_scale_out);
  }
}

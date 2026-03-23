package org.thoughtcrime.securesms.altplatform.search;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.thoughtcrime.securesms.ConversationActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.altplatform.AltPlatformService;
import org.thoughtcrime.securesms.altplatform.network.dto.UserProfileResponse;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AltUserSearchFragment extends Fragment implements AltUserSearchAdapter.OnUserClickListener {

    private static final long DEBOUNCE_MS = 400;

    private EditText searchInput;
    private RecyclerView recyclerView;
    private ProgressBar progressBar;
    private TextView emptyLabel;

    private AltUserSearchAdapter adapter;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private Runnable searchRunnable;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_user_search, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        searchInput = view.findViewById(R.id.search_input);
        recyclerView = view.findViewById(R.id.recycler_view);
        progressBar = view.findViewById(R.id.progress_bar);
        emptyLabel = view.findViewById(R.id.empty_label);

        adapter = new AltUserSearchAdapter(this);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setAdapter(adapter);

        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) {}
            @Override public void afterTextChanged(Editable s) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (searchRunnable != null) handler.removeCallbacks(searchRunnable);
                String query = s.toString().trim();
                if (query.length() < 2) {
                    adapter.setItems(Collections.emptyList());
                    emptyLabel.setVisibility(View.GONE);
                    progressBar.setVisibility(View.GONE);
                    return;
                }
                searchRunnable = () -> performSearch(query);
                handler.postDelayed(searchRunnable, DEBOUNCE_MS);
            }
        });
    }

    private void performSearch(String query) {
        progressBar.setVisibility(View.VISIBLE);
        emptyLabel.setVisibility(View.GONE);
        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            List<UserProfileResponse> results = service.searchUsers(query);
            Util.runOnMain(() -> {
                progressBar.setVisibility(View.GONE);
                adapter.setItems(results);
                emptyLabel.setVisibility(results.isEmpty() ? View.VISIBLE : View.GONE);
            });
        });
    }

    @Override
    public void onUserClick(UserProfileResponse profile) {
        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            int contactId = service.addContactFromAlt(profile);
            if (contactId > 0) {
                int accountId = DcHelper.getAccounts(requireContext()).getSelectedAccount().getAccountId();
                try {
                    int chatId = DcHelper.getRpc(requireContext())
                            .createChatByContactId(accountId, contactId);
                    Util.runOnMain(() -> {
                        Intent intent = new Intent(requireContext(), ConversationActivity.class);
                        intent.putExtra(ConversationActivity.CHAT_ID_EXTRA, chatId);
                        intent.putExtra(ConversationActivity.ACCOUNT_ID_EXTRA, accountId);
                        startActivity(intent);
                    });
                } catch (Exception e) {
                    Util.runOnMain(() -> {
                        if (isAdded()) {
                            android.widget.Toast.makeText(requireContext(),
                                    R.string.error, android.widget.Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
        handler.removeCallbacksAndMessages(null);
    }
}

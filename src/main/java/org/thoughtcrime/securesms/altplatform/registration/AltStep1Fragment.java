package org.thoughtcrime.securesms.altplatform.registration;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Step 1: collect username, display name, contact email.
 * DC email addresses from configured transports are shown read-only.
 * No API call is made here — data is passed forward to Step 2.
 */
public class AltStep1Fragment extends Fragment {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,32}$");

    private EditText usernameInput;
    private EditText displayNameInput;
    private EditText emailInput;
    private TextView dcAddrsLabel;
    private Button nextButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_step1, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        usernameInput = view.findViewById(R.id.username_input);
        displayNameInput = view.findViewById(R.id.display_name_input);
        emailInput = view.findViewById(R.id.email_input);
        dcAddrsLabel = view.findViewById(R.id.dc_addrs_label);
        nextButton = view.findViewById(R.id.next_button);

        loadDcAddrs();
        nextButton.setOnClickListener(v -> onNext());
    }

    private void loadDcAddrs() {
        executor.execute(() -> {
            try {
                chat.delta.rpc.Rpc rpc = DcHelper.getRpc(requireContext());
                int accountId = DcHelper.getAccounts(requireContext()).getSelectedAccount().getAccountId();
                List<chat.delta.rpc.types.EnteredLoginParam> transports = rpc.listTransports(accountId);
                StringBuilder sb = new StringBuilder();
                for (chat.delta.rpc.types.EnteredLoginParam t : transports) {
                    if (t.addr != null && !t.addr.isEmpty()) {
                        if (sb.length() > 0) sb.append(", ");
                        sb.append(t.addr);
                    }
                }
                String addrs = sb.toString();
                Util.runOnMain(() -> dcAddrsLabel.setText(
                        addrs.isEmpty()
                                ? getString(R.string.alt_no_dc_addrs)
                                : getString(R.string.alt_dc_addrs, addrs)));
            } catch (Exception e) {
                Util.runOnMain(() -> dcAddrsLabel.setText(R.string.alt_no_dc_addrs));
            }
        });
    }

    private void onNext() {
        String username = usernameInput.getText().toString().trim().toLowerCase();
        String displayName = displayNameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim().toLowerCase();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            usernameInput.setError(getString(R.string.alt_username_invalid));
            return;
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError(getString(R.string.alt_email_invalid));
            return;
        }

        if (getActivity() instanceof AltRegistrationActivity) {
            ((AltRegistrationActivity) getActivity()).goToStep2(username, displayName, email);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}


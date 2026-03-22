package org.thoughtcrime.securesms.altplatform.registration;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import chat.delta.rpc.Rpc;
import chat.delta.rpc.RpcException;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.connect.DcHelper;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

/**
 * Step 1: collect username, display name, contact email.
 * Creates a DC chatmail account on nine.testrun.org automatically.
 */
public class AltStep1Fragment extends Fragment {

    private static final Pattern USERNAME_PATTERN = Pattern.compile("^[a-z0-9_]{3,32}$");
    private static final String DEFAULT_CHATMAIL_HOST = "alt-to.site";

    private EditText usernameInput;
    private EditText displayNameInput;
    private EditText emailInput;
    private Button nextButton;
    private TextView statusLabel;

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
        nextButton = view.findViewById(R.id.next_button);
        statusLabel = view.findViewById(R.id.dc_addrs_label);

        nextButton.setOnClickListener(v -> onNext());
    }

    private void onNext() {
        String username = usernameInput.getText().toString().trim().toLowerCase();
        String displayName = displayNameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim().toLowerCase();

        if (!USERNAME_PATTERN.matcher(username).matches()) {
            usernameInput.setError(getString(R.string.alt_username_invalid));
            return;
        }
        if (displayName.isEmpty()) {
            displayNameInput.setError(getString(R.string.please_enter_name));
            return;
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError(getString(R.string.alt_email_invalid));
            return;
        }

        nextButton.setEnabled(false);
        nextButton.setText(R.string.loading);
        statusLabel.setVisibility(View.VISIBLE);
        statusLabel.setText(R.string.one_moment);

        executor.execute(() -> {
            boolean dcReady = ensureDcAccount(displayName);
            Util.runOnMain(() -> {
                nextButton.setEnabled(true);
                nextButton.setText(R.string.next);
                statusLabel.setVisibility(View.GONE);
                if (!dcReady) {
                    Toast.makeText(requireContext(), R.string.network_connection_unavailable, Toast.LENGTH_LONG).show();
                    return;
                }
                if (getActivity() instanceof AltRegistrationActivity) {
                    ((AltRegistrationActivity) getActivity()).goToStep2(username, displayName, email);
                }
            });
        });
    }

    /**
     * Creates a DC chatmail account on nine.testrun.org if one is not yet configured.
     * Sets the display name. Returns true on success.
     */
    private boolean ensureDcAccount(String displayName) {
        try {
            Rpc rpc = DcHelper.getRpc(requireContext());
            int accountId = DcHelper.getAccounts(requireContext()).getSelectedAccount().getAccountId();

            // Set display name regardless
            DcHelper.set(requireContext(), DcHelper.CONFIG_DISPLAY_NAME, displayName);

            // If already configured, skip account creation
            if (DcHelper.getAccounts(requireContext()).getSelectedAccount().isConfigured() == 1) {
                return true;
            }

            // Create chatmail account via QR
            rpc.addTransportFromQr(accountId, "dcaccount:" + DEFAULT_CHATMAIL_HOST);
            return true;
        } catch (RpcException e) {
            android.util.Log.e("AltStep1Fragment", "DC account creation failed", e);
            return false;
        } catch (Exception e) {
            android.util.Log.e("AltStep1Fragment", "ensureDcAccount error", e);
            return false;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}


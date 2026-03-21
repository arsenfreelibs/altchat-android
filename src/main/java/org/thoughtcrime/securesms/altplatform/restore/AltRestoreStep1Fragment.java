package org.thoughtcrime.securesms.altplatform.restore;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.altplatform.AltPlatformService;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restore Step 1: enter username and email → sends restore/verification email.
 */
public class AltRestoreStep1Fragment extends Fragment {

    private EditText usernameInput;
    private EditText emailInput;
    private Button nextButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_restore_step1, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        usernameInput = view.findViewById(R.id.username_input);
        emailInput = view.findViewById(R.id.email_input);
        nextButton = view.findViewById(R.id.next_button);
        nextButton.setOnClickListener(v -> onNext());
    }

    private void onNext() {
        String username = usernameInput.getText().toString().trim().toLowerCase();
        String email = emailInput.getText().toString().trim().toLowerCase();

        if (username.isEmpty()) {
            usernameInput.setError(getString(R.string.alt_username_required));
            return;
        }
        if (email.isEmpty() || !android.util.Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailInput.setError(getString(R.string.alt_email_invalid));
            return;
        }

        nextButton.setEnabled(false);
        nextButton.setText(R.string.loading);

        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.RestoreInitResult result = service.initiateRestore(username, email);
            Util.runOnMain(() -> {
                nextButton.setEnabled(true);
                nextButton.setText(R.string.next);
                if (result == AltPlatformService.RestoreInitResult.SUCCESS) {
                    if (getActivity() instanceof AltRestoreActivity) {
                        ((AltRestoreActivity) getActivity()).goToStep2(username, email);
                    }
                } else if (result == AltPlatformService.RestoreInitResult.USER_NOT_FOUND) {
                    usernameInput.setError(getString(R.string.alt_user_not_found));
                } else {
                    Toast.makeText(requireContext(), R.string.network_connection_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

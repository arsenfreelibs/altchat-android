package org.thoughtcrime.securesms.altplatform.registration;

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
import org.thoughtcrime.securesms.altplatform.storage.AltPrefs;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 2: set recovery password.
 * Calls the register API; on success moves to Step 3 (email verification).
 */
public class AltStep2Fragment extends Fragment {

    private EditText passwordInput;
    private EditText passwordConfirmInput;
    private Button registerButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_step2, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        passwordInput = view.findViewById(R.id.password_input);
        passwordConfirmInput = view.findViewById(R.id.password_confirm_input);
        registerButton = view.findViewById(R.id.register_button);

        registerButton.setOnClickListener(v -> onRegister());
    }

    private void onRegister() {
        String password = passwordInput.getText().toString();
        String confirm = passwordConfirmInput.getText().toString();

        if (password.length() < 8) {
            passwordInput.setError(getString(R.string.alt_password_too_short));
            return;
        }
        if (!password.equals(confirm)) {
            passwordConfirmInput.setError(getString(R.string.alt_passwords_mismatch));
            return;
        }

        Bundle args = getArguments();
        if (args == null) return;
        String username = args.getString(AltRegistrationActivity.EXTRA_USERNAME, "");
        String email = args.getString(AltRegistrationActivity.EXTRA_EMAIL, "");
        String displayName = args.getString(AltRegistrationActivity.EXTRA_DISPLAY_NAME, "");

        registerButton.setEnabled(false);
        registerButton.setText(R.string.loading);

        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.RegisterResult result =
                    service.register(username, email, displayName, password);
            Util.runOnMain(() -> {
                registerButton.setEnabled(true);
                registerButton.setText(R.string.alt_register_button);
                handleResult(result, username, displayName, email);
            });
        });
    }

    private void handleResult(AltPlatformService.RegisterResult result,
                              String username, String displayName, String email) {
        if (!(getActivity() instanceof AltRegistrationActivity)) return;
        AltRegistrationActivity activity = (AltRegistrationActivity) getActivity();
        switch (result) {
            case SUCCESS:
                AltPrefs.setPendingRegistration(requireContext(), username, displayName, email);
                activity.goToStep3(username, displayName, email);
                break;
            case USERNAME_TAKEN:
                Toast.makeText(requireContext(), R.string.alt_username_taken, Toast.LENGTH_LONG).show();
                activity.onBackPressed();  // back to step 1 to change username
                break;
            case EMAIL_TAKEN:
                Toast.makeText(requireContext(), R.string.alt_email_taken, Toast.LENGTH_LONG).show();
                activity.onBackPressed();
                break;
            case USERNAME_RESERVED:
                Toast.makeText(requireContext(), R.string.alt_username_reserved, Toast.LENGTH_LONG).show();
                activity.onBackPressed();
                break;
            case INVALID_USERNAME:
                Toast.makeText(requireContext(), R.string.alt_username_invalid, Toast.LENGTH_LONG).show();
                activity.onBackPressed();
                break;
            case NETWORK_ERROR:
                Toast.makeText(requireContext(), R.string.network_connection_unavailable,
                        Toast.LENGTH_SHORT).show();
                break;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

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
 * Restore Step 3: enter verification code + recovery password.
 * Verifies the code, downloads the encrypted private key, decrypts it, and imports into DC.
 */
public class AltRestoreStep3Fragment extends Fragment {

    private EditText codeInput;
    private EditText passwordInput;
    private Button restoreButton;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_restore_step3, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        codeInput = view.findViewById(R.id.code_input);
        passwordInput = view.findViewById(R.id.password_input);
        restoreButton = view.findViewById(R.id.restore_button);

        Bundle args = getArguments();
        String email = args != null ? args.getString(AltRestoreActivity.EXTRA_EMAIL, "") : "";
        restoreButton.setOnClickListener(v -> onRestore(email));
    }

    private void onRestore(String email) {
        String code = codeInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        if (code.isEmpty()) {
            codeInput.setError(getString(R.string.alt_code_required));
            return;
        }
        if (password.isEmpty()) {
            passwordInput.setError(getString(R.string.alt_password_required));
            return;
        }

        restoreButton.setEnabled(false);
        restoreButton.setText(R.string.loading);

        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.RestoreKeyResult result = service.restoreKey(email, code, password);
            Util.runOnMain(() -> {
                restoreButton.setEnabled(true);
                restoreButton.setText(R.string.alt_restore_button);
                switch (result) {
                    case SUCCESS:
                        if (getActivity() instanceof AltRestoreActivity) {
                            ((AltRestoreActivity) getActivity()).finishWithSuccess();
                        }
                        break;
                    case WRONG_PASSWORD:
                        passwordInput.setError(getString(R.string.alt_wrong_password));
                        break;
                    case INVALID_CODE:
                        codeInput.setError(getString(R.string.alt_code_invalid));
                        break;
                    case IMPORT_FAILED:
                        Toast.makeText(requireContext(), R.string.alt_import_failed,
                                Toast.LENGTH_LONG).show();
                        break;
                    case NETWORK_ERROR:
                        Toast.makeText(requireContext(), R.string.network_connection_unavailable,
                                Toast.LENGTH_SHORT).show();
                        break;
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

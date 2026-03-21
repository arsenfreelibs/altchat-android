package org.thoughtcrime.securesms.altplatform.restore;

import android.os.Bundle;
import android.os.CountDownTimer;
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

import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.altplatform.AltPlatformService;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Restore Step 2: enter the email verification code.
 * On success, moves to Step 3 (enter recovery password to decrypt key).
 */
public class AltRestoreStep2Fragment extends Fragment {

    private EditText codeInput;
    private Button nextButton;
    private Button resendButton;
    private TextView resendCountdownLabel;
    private CountDownTimer countdownTimer;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_restore_step2, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        codeInput = view.findViewById(R.id.code_input);
        nextButton = view.findViewById(R.id.next_button);
        resendButton = view.findViewById(R.id.resend_button);
        resendCountdownLabel = view.findViewById(R.id.resend_countdown_label);

        Bundle args = getArguments();
        String email = args != null ? args.getString(AltRestoreActivity.EXTRA_EMAIL, "") : "";
        TextView hintLabel = view.findViewById(R.id.code_hint_label);
        hintLabel.setText(getString(R.string.alt_code_sent_to, email));

        nextButton.setOnClickListener(v -> onNext(email));
        resendButton.setOnClickListener(v -> onResend(email));
        startCountdown();
    }

    private void onNext(String email) {
        String code = codeInput.getText().toString().trim();
        if (code.isEmpty()) {
            codeInput.setError(getString(R.string.alt_code_required));
            return;
        }
        if (getActivity() instanceof AltRestoreActivity) {
            // Pass code forward to step 3 — the actual verify call happens there with the password
            Bundle args = getArguments() != null ? getArguments() : new Bundle();
            args.putString("code", code);
            ((AltRestoreActivity) getActivity()).goToStep3(email);
        }
    }

    private void onResend(String email) {
        resendButton.setEnabled(false);
        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.ResendResult result = service.resendCode(email);
            Util.runOnMain(() -> {
                if (result == AltPlatformService.ResendResult.SUCCESS
                        || result == AltPlatformService.ResendResult.RATE_LIMITED) {
                    Toast.makeText(requireContext(), R.string.alt_code_resent, Toast.LENGTH_SHORT).show();
                    startCountdown();
                } else {
                    resendButton.setEnabled(true);
                    Toast.makeText(requireContext(), R.string.network_connection_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void startCountdown() {
        resendButton.setEnabled(false);
        resendCountdownLabel.setVisibility(View.VISIBLE);
        if (countdownTimer != null) countdownTimer.cancel();
        countdownTimer = new CountDownTimer(60_000, 1_000) {
            @Override
            public void onTick(long millisUntilFinished) {
                resendCountdownLabel.setText(
                        getString(R.string.alt_resend_in, millisUntilFinished / 1000));
            }
            @Override
            public void onFinish() {
                resendCountdownLabel.setVisibility(View.GONE);
                resendButton.setEnabled(true);
            }
        }.start();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (countdownTimer != null) {
            countdownTimer.cancel();
            countdownTimer = null;
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}

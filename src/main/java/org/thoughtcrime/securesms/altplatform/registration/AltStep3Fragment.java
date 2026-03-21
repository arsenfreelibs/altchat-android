package org.thoughtcrime.securesms.altplatform.registration;

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
import org.thoughtcrime.securesms.altplatform.storage.AltPrefs;
import org.thoughtcrime.securesms.util.Util;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Step 3: enter the email verification code.
 * On success, saves registration state and finishes the flow.
 */
public class AltStep3Fragment extends Fragment {

    private EditText codeInput;
    private Button verifyButton;
    private Button resendButton;
    private TextView resendCountdownLabel;

    private CountDownTimer countdownTimer;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_alt_step3, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        codeInput = view.findViewById(R.id.code_input);
        verifyButton = view.findViewById(R.id.verify_button);
        resendButton = view.findViewById(R.id.resend_button);
        resendCountdownLabel = view.findViewById(R.id.resend_countdown_label);

        Bundle args = getArguments();
        String email = args != null ? args.getString(AltRegistrationActivity.EXTRA_EMAIL, "") : "";
        TextView hintLabel = view.findViewById(R.id.code_hint_label);
        hintLabel.setText(getString(R.string.alt_code_sent_to, email));

        verifyButton.setOnClickListener(v -> onVerify(email));
        resendButton.setOnClickListener(v -> onResend(email));

        startCountdown();
    }

    private void onVerify(String email) {
        String code = codeInput.getText().toString().trim();
        if (code.isEmpty()) {
            codeInput.setError(getString(R.string.alt_code_required));
            return;
        }

        verifyButton.setEnabled(false);
        verifyButton.setText(R.string.loading);

        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.VerifyResult result = service.verifyEmail(email, code);
            Util.runOnMain(() -> {
                verifyButton.setEnabled(true);
                verifyButton.setText(R.string.alt_verify_button);
                if (result == AltPlatformService.VerifyResult.SUCCESS) {
                    Bundle args = getArguments();
                    String username = args != null
                            ? args.getString(AltRegistrationActivity.EXTRA_USERNAME, "") : "";
                    AltPrefs.setRegistered(requireContext(), username, email);
                    AltPrefs.clearPendingRegistration(requireContext());
                    if (getActivity() instanceof AltRegistrationActivity) {
                        ((AltRegistrationActivity) getActivity()).finishWithSuccess();
                    }
                } else if (result == AltPlatformService.VerifyResult.INVALID_CODE) {
                    codeInput.setError(getString(R.string.alt_code_invalid));
                } else {
                    Toast.makeText(requireContext(), R.string.network_connection_unavailable,
                            Toast.LENGTH_SHORT).show();
                }
            });
        });
    }

    private void onResend(String email) {
        resendButton.setEnabled(false);
        executor.execute(() -> {
            AltPlatformService service = new AltPlatformService(requireContext());
            AltPlatformService.ResendResult result = service.resendCode(email);
            Util.runOnMain(() -> {
                if (result == AltPlatformService.ResendResult.SUCCESS) {
                    startCountdown();
                    Toast.makeText(requireContext(), R.string.alt_code_resent, Toast.LENGTH_SHORT).show();
                } else if (result == AltPlatformService.ResendResult.RATE_LIMITED) {
                    Toast.makeText(requireContext(), R.string.alt_rate_limited, Toast.LENGTH_SHORT).show();
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

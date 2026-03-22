package org.thoughtcrime.securesms.altplatform.registration;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;
import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.ConversationListActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.WelcomeActivity;
import org.thoughtcrime.securesms.altplatform.storage.AltPrefs;
import org.thoughtcrime.securesms.connect.DcHelper;

public class AltRegistrationActivity extends BaseActionBarActivity {

    static final String EXTRA_EMAIL = "extra_email";
    static final String EXTRA_USERNAME = "extra_username";
    static final String EXTRA_DISPLAY_NAME = "extra_display_name";

    public static Intent getStartIntent(Context context) {
        return new Intent(context, AltRegistrationActivity.class);
    }

    /** Resumes registration at Step 3 (email verification) with saved data. */
    public static Intent getResumeStep3Intent(Context context, String username, String displayName, String email) {
        Intent intent = new Intent(context, AltRegistrationActivity.class);
        intent.putExtra(EXTRA_USERNAME, username);
        intent.putExtra(EXTRA_DISPLAY_NAME, displayName);
        intent.putExtra(EXTRA_EMAIL, email);
        intent.putExtra("resume_step3", true);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alt_registration);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.alt_registration_title);
        }

        if (savedInstanceState == null) {
            if (getIntent().getBooleanExtra("resume_step3", false)) {
                String username = getIntent().getStringExtra(EXTRA_USERNAME);
                String displayName = getIntent().getStringExtra(EXTRA_DISPLAY_NAME);
                String email = getIntent().getStringExtra(EXTRA_EMAIL);
                goToStep3(username, displayName, email);
            } else {
                getSupportFragmentManager()
                        .beginTransaction()
                        .replace(R.id.fragment_container, new AltStep1Fragment())
                        .commit();
            }
        }
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            onBackPressed();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        // If DC is already configured, pressing back would skip Alt registration.
        // Guard: if back stack is empty (or only Step 1 left), require confirmation.
        int backStackCount = getSupportFragmentManager().getBackStackEntryCount();
        boolean dcConfigured = DcHelper.isConfigured(getApplicationContext());

        if (dcConfigured && backStackCount == 0) {
            // Can't go further back — would exit to ConversationListActivity bypassing Alt setup
            new AlertDialog.Builder(this)
                    .setTitle(R.string.alt_cancel_registration_title)
                    .setMessage(R.string.alt_cancel_registration_message)
                    .setPositiveButton(R.string.alt_cancel_registration_confirm, (d, w) -> {
                        // Delete the DC account and go back to Welcome
                        cancelRegistrationAndGoToWelcome();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return;
        }
        super.onBackPressed();
    }

    private void cancelRegistrationAndGoToWelcome() {
        AltPrefs.clearPendingRegistration(getApplicationContext());
        new Thread(() -> {
            try {
                int accountId = DcHelper.getAccounts(this).getSelectedAccount().getAccountId();
                DcHelper.getAccounts(this).removeAccount(accountId);
            } catch (Exception ignored) {}
            runOnUiThread(() -> {
                Intent intent = new Intent(this, WelcomeActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            });
        }).start();
    }

    void goToStep2(String username, String displayName, String email) {
        Bundle args = new Bundle();
        args.putString(EXTRA_EMAIL, email);
        args.putString(EXTRA_USERNAME, username);
        args.putString(EXTRA_DISPLAY_NAME, displayName);
        AltStep2Fragment fragment = new AltStep2Fragment();
        fragment.setArguments(args);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("step2")
                .commit();
    }

    void goToStep3(String username, String displayName, String email) {
        Bundle args = new Bundle();
        args.putString(EXTRA_EMAIL, email);
        args.putString(EXTRA_USERNAME, username);
        args.putString(EXTRA_DISPLAY_NAME, displayName);
        AltStep3Fragment fragment = new AltStep3Fragment();
        fragment.setArguments(args);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("step3")
                .commit();
    }

    void finishWithSuccess() {
        Intent intent = new Intent(this, ConversationListActivity.class);
        intent.putExtra(ConversationListActivity.FROM_WELCOME, true);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);
        finish();
    }

    static boolean isTopFragment(Fragment f, Class<?> cls) {
        return f != null && cls.isInstance(f);
    }
}

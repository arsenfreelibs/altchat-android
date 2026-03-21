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
import org.thoughtcrime.securesms.R;

public class AltRegistrationActivity extends BaseActionBarActivity {

    static final String EXTRA_EMAIL = "extra_email";
    static final String EXTRA_USERNAME = "extra_username";
    static final String EXTRA_DISPLAY_NAME = "extra_display_name";

    public static Intent getStartIntent(Context context) {
        return new Intent(context, AltRegistrationActivity.class);
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
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new AltStep1Fragment())
                    .commit();
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
        setResult(RESULT_OK);
        finish();
    }

    static boolean isTopFragment(Fragment f, Class<?> cls) {
        return f != null && cls.isInstance(f);
    }
}

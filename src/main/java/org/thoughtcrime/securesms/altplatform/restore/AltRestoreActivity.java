package org.thoughtcrime.securesms.altplatform.restore;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;

import org.thoughtcrime.securesms.BaseActionBarActivity;
import org.thoughtcrime.securesms.R;
import org.thoughtcrime.securesms.util.DynamicTheme;

public class AltRestoreActivity extends BaseActionBarActivity {

    @Override
    protected void onPreCreate() {
        dynamicTheme = new DynamicTheme() {
            @Override protected int getLightThemeStyle() { return R.style.Theme_MaterialComponents_Light_Bridge; }
            @Override protected int getDarkThemeStyle() { return R.style.Theme_MaterialComponents_Bridge; }
        };
        super.onPreCreate();
    }

    static final String EXTRA_EMAIL = "extra_email";
    static final String EXTRA_USERNAME = "extra_username";

    public static Intent getStartIntent(Context context) {
        return new Intent(context, AltRestoreActivity.class);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_alt_restore);

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle(R.string.alt_restore_title);
        }

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.fragment_container, new AltRestoreStep1Fragment())
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

    void goToStep2(String username, String email) {
        Bundle args = new Bundle();
        args.putString(EXTRA_USERNAME, username);
        args.putString(EXTRA_EMAIL, email);
        AltRestoreStep2Fragment fragment = new AltRestoreStep2Fragment();
        fragment.setArguments(args);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("restore_step2")
                .commit();
    }

    void goToStep3(String email) {
        Bundle args = new Bundle();
        args.putString(EXTRA_EMAIL, email);
        AltRestoreStep3Fragment fragment = new AltRestoreStep3Fragment();
        fragment.setArguments(args);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack("restore_step3")
                .commit();
    }

    void finishWithSuccess() {
        setResult(RESULT_OK);
        finish();
    }
}

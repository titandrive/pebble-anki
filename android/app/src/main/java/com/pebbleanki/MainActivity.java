package com.pebbleanki;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    static final String PREFS_NAME    = "PebbleAnki";
    static final String PREF_SELECTED = "selected_decks";

    private static final String ANKIDROID_PERMISSION =
            "com.ichi2.anki.permission.READ_WRITE_DATABASE";

    private LinearLayout mDeckContainer;
    private TextView     mStatus;
    private Button       mToggleAll;
    private List<AnkiDroidHelper.Deck> mDecks;
    private List<CheckBox> mCheckBoxes = new ArrayList<>();

    private final ActivityResultLauncher<String> mPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    loadDecks();
                } else {
                    mStatus.setText("Permission denied. Grant AnkiDroid access and reopen the app.");
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mDeckContainer = findViewById(R.id.deck_container);
        mStatus        = findViewById(R.id.status_text);
        mToggleAll     = findViewById(R.id.btn_toggle_all);

        mToggleAll.setOnClickListener(v -> toggleAll());

        startService(new Intent(this, AnkiPebbleService.class));

        if (ContextCompat.checkSelfPermission(this, ANKIDROID_PERMISSION)
                == PackageManager.PERMISSION_GRANTED) {
            loadDecks();
        } else {
            mStatus.setText("Requesting AnkiDroid access...");
            mPermissionLauncher.launch(ANKIDROID_PERMISSION);
        }
    }

    private void loadDecks() {
        mStatus.setText("Loading decks...");
        new AsyncTask<Void, Void, List<AnkiDroidHelper.Deck>>() {
            @Override
            protected List<AnkiDroidHelper.Deck> doInBackground(Void... v) {
                return new AnkiDroidHelper(MainActivity.this).getDecks();
            }

            @Override
            protected void onPostExecute(List<AnkiDroidHelper.Deck> decks) {
                mDecks = decks;
                mCheckBoxes.clear();
                mDeckContainer.removeAllViews();

                if (decks.isEmpty()) {
                    mStatus.setText("No decks found. Make sure AnkiDroid API is enabled:\n" +
                            "AnkiDroid → Settings → Advanced → Enable AnkiDroid API");
                    return;
                }

                Set<String> saved = getPrefs().getStringSet(PREF_SELECTED, null);

                int px16 = dp(16);
                int px12 = dp(12);

                for (AnkiDroidHelper.Deck deck : decks) {
                    CheckBox cb = new CheckBox(MainActivity.this);
                    cb.setText(deck.name != null ? deck.name : "(unnamed)");
                    cb.setTextColor(Color.BLACK);
                    cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                    cb.setPadding(px16, px12, px16, px12);
                    cb.setTypeface(Typeface.DEFAULT);

                    boolean checked = saved == null || saved.contains(deck.name);
                    cb.setChecked(checked);
                    cb.setOnCheckedChangeListener((btn, isChecked) -> saveSelection());

                    mDeckContainer.addView(cb);
                    mCheckBoxes.add(cb);
                }

                updateToggleButton();
                mStatus.setText("Service running. Checked decks appear on your Pebble.");
            }
        }.execute();
    }

    private void toggleAll() {
        if (mCheckBoxes.isEmpty()) return;
        boolean anyUnchecked = mCheckBoxes.stream().anyMatch(cb -> !cb.isChecked());
        for (CheckBox cb : mCheckBoxes) cb.setChecked(anyUnchecked);
        updateToggleButton();
        saveSelection();
    }

    private void updateToggleButton() {
        boolean allChecked = mCheckBoxes.stream().allMatch(CheckBox::isChecked);
        mToggleAll.setText(allChecked ? "Deselect All" : "Select All");
    }

    private void saveSelection() {
        if (mDecks == null) return;
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < mDecks.size(); i++) {
            if (i < mCheckBoxes.size() && mCheckBoxes.get(i).isChecked()) {
                selected.add(mDecks.get(i).name);
            }
        }
        getPrefs().edit().putStringSet(PREF_SELECTED, selected).apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}

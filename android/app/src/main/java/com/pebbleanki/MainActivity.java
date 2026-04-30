package com.pebbleanki;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.View;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private List<CheckBox> mAllCheckBoxes = new ArrayList<>();

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
                mAllCheckBoxes.clear();
                mDeckContainer.removeAllViews();

                if (decks.isEmpty()) {
                    mStatus.setText("No decks found. Make sure AnkiDroid API is enabled:\n" +
                            "AnkiDroid → Settings → Advanced → Enable AnkiDroid API");
                    return;
                }

                Set<String> saved = getPrefs().getStringSet(PREF_SELECTED, null);

                // Group decks by top-level name (split on "::")
                Map<String, List<AnkiDroidHelper.Deck>> groups = new LinkedHashMap<>();
                for (AnkiDroidHelper.Deck deck : decks) {
                    String topLevel = deck.name != null && deck.name.contains("::")
                            ? deck.name.substring(0, deck.name.indexOf("::"))
                            : (deck.name != null ? deck.name : "");
                    groups.computeIfAbsent(topLevel, k -> new ArrayList<>()).add(deck);
                }

                for (Map.Entry<String, List<AnkiDroidHelper.Deck>> entry : groups.entrySet()) {
                    String groupName = entry.getKey();
                    List<AnkiDroidHelper.Deck> groupDecks = entry.getValue();

                    boolean isStandalone = groupDecks.size() == 1
                            && groupName.equals(groupDecks.get(0).name);

                    if (isStandalone) {
                        addDeckRow(groupDecks.get(0), saved, 0);
                    } else {
                        addGroupSection(groupName, groupDecks, saved);
                    }
                }

                updateToggleButton();
                mStatus.setText("Service running. Checked decks appear on your Pebble.");
            }
        }.execute();
    }

    private void addGroupSection(String groupName, List<AnkiDroidHelper.Deck> groupDecks,
                                 Set<String> saved) {
        // Child rows container (toggled visible/gone)
        LinearLayout childContainer = new LinearLayout(this);
        childContainer.setOrientation(LinearLayout.VERTICAL);

        List<CheckBox> groupCheckBoxes = new ArrayList<>();
        for (AnkiDroidHelper.Deck deck : groupDecks) {
            CheckBox cb = makeDeckCheckBox(deck, saved, dp(32));
            groupCheckBoxes.add(cb);
            mAllCheckBoxes.add(cb);
            childContainer.addView(cb);
        }

        // Header row: chevron + group name + group-level select checkbox
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setPadding(dp(16), dp(10), dp(16), dp(10));
        header.setBackgroundColor(Color.parseColor("#F5F5F5"));
        header.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final TextView chevron = new TextView(this);
        chevron.setText("▼ ");
        chevron.setTextColor(Color.parseColor("#555555"));
        chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);

        TextView label = new TextView(this);
        label.setText(groupName);
        label.setTextColor(Color.BLACK);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        label.setTypeface(Typeface.DEFAULT_BOLD);
        label.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        CheckBox groupToggle = new CheckBox(this);
        boolean allChecked = groupDecks.stream()
                .allMatch(d -> saved == null || saved.contains(d.name));
        groupToggle.setChecked(allChecked);
        groupToggle.setOnCheckedChangeListener((btn, checked) -> {
            for (CheckBox cb : groupCheckBoxes) cb.setChecked(checked);
            saveSelection();
            updateToggleButton();
        });

        header.addView(chevron);
        header.addView(label);
        header.addView(groupToggle);

        // Expand/collapse on header tap (not checkbox)
        final boolean[] expanded = {true};
        header.setOnClickListener(v -> {
            expanded[0] = !expanded[0];
            childContainer.setVisibility(expanded[0] ? View.VISIBLE : View.GONE);
            chevron.setText(expanded[0] ? "▼ " : "▶ ");
        });

        mDeckContainer.addView(header);
        mDeckContainer.addView(childContainer);
    }

    private void addDeckRow(AnkiDroidHelper.Deck deck, Set<String> saved, int indentPx) {
        CheckBox cb = makeDeckCheckBox(deck, saved, indentPx);
        mAllCheckBoxes.add(cb);
        mDeckContainer.addView(cb);
    }

    private CheckBox makeDeckCheckBox(AnkiDroidHelper.Deck deck, Set<String> saved, int indentPx) {
        CheckBox cb = new CheckBox(this);
        // Show only the leaf name (after last "::")
        String displayName = deck.name != null && deck.name.contains("::")
                ? deck.name.substring(deck.name.lastIndexOf("::") + 2)
                : (deck.name != null ? deck.name : "(unnamed)");
        cb.setText(displayName);
        cb.setTextColor(Color.BLACK);
        cb.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        cb.setPadding(dp(16) + indentPx, dp(10), dp(16), dp(10));

        boolean checked = saved == null || saved.contains(deck.name);
        cb.setChecked(checked);
        cb.setOnCheckedChangeListener((btn, isChecked) -> {
            saveSelection();
            updateToggleButton();
        });
        return cb;
    }

    private void toggleAll() {
        if (mAllCheckBoxes.isEmpty()) return;
        boolean anyUnchecked = mAllCheckBoxes.stream().anyMatch(cb -> !cb.isChecked());
        for (CheckBox cb : mAllCheckBoxes) cb.setChecked(anyUnchecked);
        updateToggleButton();
        saveSelection();
    }

    private void updateToggleButton() {
        boolean allChecked = !mAllCheckBoxes.isEmpty()
                && mAllCheckBoxes.stream().allMatch(CheckBox::isChecked);
        mToggleAll.setText(allChecked ? "Deselect All" : "Select All");
    }

    private void saveSelection() {
        if (mDecks == null) return;
        Set<String> selected = new HashSet<>();
        // Walk all decks and match to their checkbox by position
        int cbIndex = 0;
        for (AnkiDroidHelper.Deck deck : mDecks) {
            if (cbIndex < mAllCheckBoxes.size() && mAllCheckBoxes.get(cbIndex).isChecked()) {
                selected.add(deck.name);
            }
            cbIndex++;
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

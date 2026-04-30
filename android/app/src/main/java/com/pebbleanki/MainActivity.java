package com.pebbleanki;

import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class MainActivity extends AppCompatActivity {

    static final String PREFS_NAME    = "PebbleAnki";
    static final String PREF_SELECTED = "selected_decks";

    private static final String ANKIDROID_PERMISSION =
            "com.ichi2.anki.permission.READ_WRITE_DATABASE";

    private ListView mDeckList;
    private TextView mStatus;
    private Button   mToggleAll;
    private List<AnkiDroidHelper.Deck> mDecks;
    private boolean mAllSelected = true;

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

        mDeckList  = findViewById(R.id.deck_list);
        mStatus    = findViewById(R.id.status_text);
        mToggleAll = findViewById(R.id.btn_toggle_all);

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
                if (decks.isEmpty()) {
                    mStatus.setText("No decks found. Make sure AnkiDroid API is enabled:\n" +
                            "AnkiDroid → Settings → Advanced → Enable AnkiDroid API");
                    return;
                }

                String[] names = new String[decks.size()];
                for (int i = 0; i < decks.size(); i++) names[i] = decks.get(i).name;

                ArrayAdapter<String> adapter = new ArrayAdapter<>(
                        MainActivity.this,
                        R.layout.list_item_deck,
                        names);
                mDeckList.setAdapter(adapter);

                Set<String> saved = getPrefs().getStringSet(PREF_SELECTED, null);
                int checkedCount = 0;
                for (int i = 0; i < decks.size(); i++) {
                    boolean checked = saved == null || saved.contains(decks.get(i).name);
                    mDeckList.setItemChecked(i, checked);
                    if (checked) checkedCount++;
                }
                mAllSelected = checkedCount == decks.size();
                updateToggleButton();

                mDeckList.setOnItemClickListener((parent, view, pos, id) -> {
                    saveSelection();
                    int checked = 0;
                    for (int i = 0; i < mDecks.size(); i++) {
                        if (mDeckList.isItemChecked(i)) checked++;
                    }
                    mAllSelected = checked == mDecks.size();
                    updateToggleButton();
                });
                mStatus.setText("Service running. Checked decks appear on your Pebble.");
            }
        }.execute();
    }

    private void toggleAll() {
        if (mDecks == null) return;
        mAllSelected = !mAllSelected;
        for (int i = 0; i < mDecks.size(); i++) {
            mDeckList.setItemChecked(i, mAllSelected);
        }
        updateToggleButton();
        saveSelection();
    }

    private void updateToggleButton() {
        mToggleAll.setText(mAllSelected ? "Deselect All" : "Select All");
    }

    private void saveSelection() {
        if (mDecks == null) return;
        Set<String> selected = new HashSet<>();
        for (int i = 0; i < mDecks.size(); i++) {
            if (mDeckList.isItemChecked(i)) selected.add(mDecks.get(i).name);
        }
        getPrefs().edit().putStringSet(PREF_SELECTED, selected).apply();
    }

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
    }
}

package com.pebbleanki;

import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.IBinder;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Background service that bridges the Pebble watch and AnkiDroid.
 *
 * Communicates with the Pebble via the Rebble/Pebble app's Intent API —
 * no PebbleKit AAR required. All Bluetooth is handled by the Pebble app.
 */
public class AnkiPebbleService extends Service {

    private static final String TAG = "AnkiPebbleService";

    // Must match pebble/appinfo.json uuid
    private static final UUID APP_UUID =
            UUID.fromString("11867ba6-5ed8-45c6-b4b7-32d695240ce5");

    // Pebble Intent API actions (via Rebble/Pebble app)
    private static final String ACTION_RECEIVE     = "com.getpebble.action.app.RECEIVE";
    private static final String ACTION_RECEIVE_ACK = "com.getpebble.action.app.RECEIVE_ACK";
    private static final String ACTION_SEND        = "com.getpebble.action.app.SEND";
    private static final String EXTRA_UUID         = "uuid";
    private static final String EXTRA_MSG          = "msg";
    private static final String EXTRA_TRANSACTION  = "transaction_id";

    // AppMessage keys — must match pebble/appinfo.json appKeys
    private static final int KEY_MSG_TYPE    = 0;
    private static final int KEY_CARD_FRONT  = 1;
    private static final int KEY_CARD_BACK   = 2;
    private static final int KEY_CARD_ID     = 3;
    private static final int KEY_DECK_NAME   = 4;
    private static final int KEY_DECK_LIST   = 5;
    private static final int KEY_ANSWER      = 6;

    // Message type values — must match pebble/src/messaging.h
    private static final int MSG_GET_DECKS    = 0;
    private static final int MSG_DECK_LIST    = 1;
    private static final int MSG_SELECT_DECK  = 2;
    private static final int MSG_CARD         = 3;
    private static final int MSG_ANSWER       = 4;
    private static final int MSG_DONE         = 5;
    private static final int MSG_ERROR        = 6;

    private static final int EASE_AGAIN = 1;
    private static final int EASE_GOOD  = 3;

    private AnkiDroidHelper mAnki;
    private BroadcastReceiver mReceiver;

    // State for current session
    private final Map<String, Long> mDeckNameToId = new HashMap<>();
    private long mCurrentDeckId  = -1;
    private long mCurrentNoteId  = -1;
    private int  mCurrentCardOrd = -1;

    // ---- Lifecycle ---------------------------------------------------------

    @Override
    public void onCreate() {
        super.onCreate();
        mAnki = new AnkiDroidHelper(this);
        registerPebbleReceiver();
        Log.i(TAG, "Service started");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onDestroy() {
        if (mReceiver != null) {
            try { unregisterReceiver(mReceiver); } catch (Exception ignored) {}
        }
        super.onDestroy();
    }

    // ---- Pebble receiver ---------------------------------------------------

    private void registerPebbleReceiver() {
        mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String uuid = intent.getStringExtra(EXTRA_UUID);
                if (!APP_UUID.toString().equals(uuid)) return;

                int transactionId = intent.getIntExtra(EXTRA_TRANSACTION, -1);
                sendAck(transactionId);

                String msgJson = intent.getStringExtra(EXTRA_MSG);
                if (msgJson == null) return;
                try {
                    handleWatchMessage(new JSONArray(msgJson));
                } catch (JSONException e) {
                    Log.e(TAG, "Bad JSON from watch: " + msgJson, e);
                }
            }
        };
        IntentFilter filter = new IntentFilter(ACTION_RECEIVE);
        registerReceiver(mReceiver, filter);
    }

    private void sendAck(int transactionId) {
        Intent ack = new Intent(ACTION_RECEIVE_ACK);
        ack.putExtra(EXTRA_TRANSACTION, transactionId);
        sendBroadcast(ack);
    }

    // ---- Message handling --------------------------------------------------

    private void handleWatchMessage(JSONArray msg) {
        int msgType = (int) getUint(msg, KEY_MSG_TYPE);
        switch (msgType) {
            case MSG_GET_DECKS:   handleGetDecks();                          break;
            case MSG_SELECT_DECK: handleSelectDeck(getString(msg, KEY_DECK_NAME)); break;
            case MSG_ANSWER:      handleAnswer((int) getUint(msg, KEY_ANSWER)); break;
            default:
                Log.w(TAG, "Unknown message type: " + msgType);
        }
    }

    private Set<String> getSelectedDecks() {
        SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS_NAME, MODE_PRIVATE);
        return prefs.getStringSet(MainActivity.PREF_SELECTED, null);
    }

    private void handleGetDecks() {
        List<AnkiDroidHelper.Deck> decks = mAnki.getDecks();
        if (decks.isEmpty()) {
            sendError("AnkiDroid API not enabled");
            return;
        }

        Set<String> selected = getSelectedDecks();

        mDeckNameToId.clear();
        StringBuilder sb = new StringBuilder();
        for (AnkiDroidHelper.Deck deck : decks) {
            // If user has configured a selection, filter to it; otherwise show all
            if (selected != null && !selected.contains(deck.name)) continue;
            mDeckNameToId.put(deck.name, deck.id);
            if (sb.length() > 0) sb.append('\n');
            sb.append(deck.name);
            if (sb.length() > 1800) break;
        }

        PebbleMsg response = new PebbleMsg()
                .addUint(KEY_MSG_TYPE, MSG_DECK_LIST)
                .addString(KEY_DECK_LIST, sb.toString());
        sendToPebble(response);
    }

    private void handleSelectDeck(String deckName) {
        if (deckName == null) { sendError("No deck name"); return; }

        Long deckId = mDeckNameToId.get(deckName);
        if (deckId == null) {
            // Deck list may have changed — re-query
            for (AnkiDroidHelper.Deck d : mAnki.getDecks()) {
                mDeckNameToId.put(d.name, d.id);
                if (d.name.equals(deckName)) deckId = d.id;
            }
        }
        if (deckId == null) { sendError("Deck not found"); return; }

        mCurrentDeckId = deckId;
        sendNextCard();
    }

    private void handleAnswer(int ease) {
        if (mCurrentNoteId == -1) return;
        // Validate ease value — only Again and Good come from the watch
        if (ease != EASE_AGAIN && ease != EASE_GOOD) ease = EASE_AGAIN;
        mAnki.answerCard(mCurrentNoteId, mCurrentCardOrd, ease);
        sendNextCard();
    }

    // ---- Card flow ---------------------------------------------------------

    private void sendNextCard() {
        AnkiDroidHelper.CardInfo info = mAnki.getNextDueCard(mCurrentDeckId);
        if (info == null) {
            sendToPebble(new PebbleMsg().addUint(KEY_MSG_TYPE, MSG_DONE));
            return;
        }

        AnkiDroidHelper.CardContent content = mAnki.getCardContent(info.noteId, info.cardOrd);
        if (content == null) {
            // Skip unreadable card and try next
            mAnki.answerCard(info.noteId, info.cardOrd, EASE_AGAIN);
            sendNextCard();
            return;
        }

        mCurrentNoteId  = info.noteId;
        mCurrentCardOrd = info.cardOrd;

        String front = truncate(content.front, 255);
        String back  = truncate(content.back,  255);

        // AnkiDroid sometimes includes the front text at the start of the back
        if (back.startsWith(front)) back = back.substring(front.length()).trim();

        PebbleMsg card = new PebbleMsg()
                .addUint(KEY_MSG_TYPE,   MSG_CARD)
                .addString(KEY_CARD_FRONT, front)
                .addString(KEY_CARD_BACK,  back)
                .addUint(KEY_CARD_ID, 0); // session index — unused on Android side
        sendToPebble(card);
    }

    // ---- Pebble send -------------------------------------------------------

    private void sendToPebble(PebbleMsg msg) {
        Intent intent = new Intent(ACTION_SEND);
        intent.putExtra(EXTRA_UUID, APP_UUID.toString());
        intent.putExtra(EXTRA_MSG, msg.toJson());
        sendBroadcast(intent);
    }

    private void sendError(String message) {
        PebbleMsg err = new PebbleMsg()
                .addUint(KEY_MSG_TYPE, MSG_ERROR)
                .addString(KEY_DECK_NAME, truncate(message, 63));
        sendToPebble(err);
    }

    // ---- JSON helpers ------------------------------------------------------

    private static long getUint(JSONArray msg, int key) {
        try {
            for (int i = 0; i < msg.length(); i++) {
                JSONObject obj = msg.getJSONObject(i);
                if (obj.getInt("key") == key) return obj.getLong("value");
            }
        } catch (JSONException ignored) {}
        return -1;
    }

    private static String getString(JSONArray msg, int key) {
        try {
            for (int i = 0; i < msg.length(); i++) {
                JSONObject obj = msg.getJSONObject(i);
                if (obj.getInt("key") == key) return obj.getString("value");
            }
        } catch (JSONException ignored) {}
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    // ---- PebbleMsg builder -------------------------------------------------

    static class PebbleMsg {
        private final JSONArray json = new JSONArray();

        PebbleMsg addUint(int key, long value) {
            try {
                json.put(new JSONObject()
                        .put("key", key)
                        .put("type", "uint")
                        .put("length", 4)
                        .put("value", value));
            } catch (JSONException ignored) {}
            return this;
        }

        PebbleMsg addString(int key, String value) {
            if (value == null) value = "";
            try {
                json.put(new JSONObject()
                        .put("key", key)
                        .put("type", "string")
                        .put("length", value.length() + 1)
                        .put("value", value));
            } catch (JSONException ignored) {}
            return this;
        }

        String toJson() { return json.toString(); }
    }
}

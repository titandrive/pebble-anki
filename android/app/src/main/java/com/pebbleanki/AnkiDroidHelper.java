package com.pebbleanki;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;

/**
 * Wraps AnkiDroid's ContentProvider API.
 *
 * Requires com.ichi2.anki.permission.READ_WRITE_DATABASE permission and
 * "Enable AnkiDroid API" turned on in AnkiDroid → Settings → Advanced.
 */
public class AnkiDroidHelper {

    // AnkiDroid ContentProvider authority and URIs
    private static final String AUTHORITY = "com.ichi2.anki.flashcards";
    private static final Uri AUTHORITY_URI = Uri.parse("content://" + AUTHORITY);

    private static final Uri DECKS_URI   = Uri.withAppendedPath(AUTHORITY_URI, "decks/");
    private static final Uri REVIEW_URI  = Uri.withAppendedPath(AUTHORITY_URI, "reviewInfo/");
    private static final Uri NOTES_URI   = Uri.withAppendedPath(AUTHORITY_URI, "notes/");

    // Deck columns
    private static final String COL_DECK_ID   = "_id";
    private static final String COL_DECK_NAME = "deck_name";

    // ReviewInfo columns
    private static final String COL_NOTE_ID  = "note_id";
    private static final String COL_CARD_ORD = "card_ord";

    // ReviewInfo update columns (answering a card)
    private static final String COL_EASE       = "ease";
    private static final String COL_TIME_TAKEN = "timeTaken";

    // Card content columns (query via notes/{id}/cards/{ord})
    private static final String COL_QUESTION = "question_simple";
    private static final String COL_ANSWER   = "answer_simple";

    public static class Deck {
        public final long   id;
        public final String name;
        Deck(long id, String name) { this.id = id; this.name = name; }
    }

    public static class CardInfo {
        public final long noteId;
        public final int  cardOrd;
        CardInfo(long noteId, int cardOrd) { this.noteId = noteId; this.cardOrd = cardOrd; }
    }

    public static class CardContent {
        public final String front;
        public final String back;
        CardContent(String front, String back) { this.front = front; this.back = back; }
    }

    private final ContentResolver mCr;

    public AnkiDroidHelper(Context context) {
        mCr = context.getContentResolver();
    }

    /** Returns all decks. Empty list if AnkiDroid is not installed or API is disabled. */
    public List<Deck> getDecks() {
        List<Deck> decks = new ArrayList<>();
        Cursor c = null;
        try {
            c = mCr.query(DECKS_URI,
                    new String[]{COL_DECK_ID, COL_DECK_NAME},
                    null, null, null);
            if (c == null) return decks;
            while (c.moveToNext()) {
                long   id   = c.getLong(c.getColumnIndexOrThrow(COL_DECK_ID));
                String name = c.getString(c.getColumnIndexOrThrow(COL_DECK_NAME));
                decks.add(new Deck(id, name));
            }
        } catch (Exception e) {
            // AnkiDroid not installed or API disabled
        } finally {
            if (c != null) c.close();
        }
        return decks;
    }

    /**
     * Returns the next due card for a deck, or null if none are due.
     * AnkiDroid's scheduler picks which card to show next.
     */
    public CardInfo getNextDueCard(long deckId) {
        Cursor c = null;
        try {
            c = mCr.query(REVIEW_URI,
                    new String[]{COL_NOTE_ID, COL_CARD_ORD},
                    "deckID = ?",
                    new String[]{String.valueOf(deckId)},
                    null);
            if (c == null || !c.moveToFirst()) return null;
            long noteId  = c.getLong(c.getColumnIndexOrThrow(COL_NOTE_ID));
            int  cardOrd = c.getInt(c.getColumnIndexOrThrow(COL_CARD_ORD));
            return new CardInfo(noteId, cardOrd);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /** Returns the front and back text for a card. */
    public CardContent getCardContent(long noteId, int cardOrd) {
        Uri cardUri = Uri.withAppendedPath(NOTES_URI, noteId + "/cards/" + cardOrd);
        Cursor c = null;
        try {
            c = mCr.query(cardUri,
                    new String[]{COL_QUESTION, COL_ANSWER},
                    null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            String front = c.getString(c.getColumnIndexOrThrow(COL_QUESTION));
            String back  = c.getString(c.getColumnIndexOrThrow(COL_ANSWER));
            return new CardContent(
                    front == null ? "" : front.trim(),
                    back  == null ? "" : back.trim()
            );
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    /**
     * Submits an answer for a card. AnkiDroid's scheduler updates the card's
     * next review date accordingly.
     *
     * @param ease 1=Again, 2=Hard, 3=Good, 4=Easy
     */
    public void answerCard(long noteId, int cardOrd, int ease) {
        ContentValues cv = new ContentValues();
        cv.put(COL_NOTE_ID,   noteId);
        cv.put(COL_CARD_ORD,  cardOrd);
        cv.put(COL_EASE,      ease);
        cv.put(COL_TIME_TAKEN, 5000); // ms — not tracked, required by API
        try {
            mCr.update(REVIEW_URI, cv, null, null);
        } catch (Exception e) {
            // Ignore — next getNextDueCard will still work
        }
    }
}

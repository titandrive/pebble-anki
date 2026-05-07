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
            c = mCr.query(DECKS_URI, null, null, null, null);
            if (c == null) return decks;

            // Find ID column
            int idCol = c.getColumnIndex("_id");
            if (idCol == -1) idCol = c.getColumnIndex("deck_id");
            if (idCol == -1) idCol = 0;

            // Find name column — try known variants
            int nameCol = -1;
            for (String candidate : new String[]{"deck_name", "name", "deckName"}) {
                int idx = c.getColumnIndex(candidate);
                if (idx != -1) { nameCol = idx; break; }
            }

            // If name column still not found, expose column names for debugging
            String debugCols = nameCol == -1
                    ? "cols=" + java.util.Arrays.toString(c.getColumnNames()) : null;

            while (c.moveToNext()) {
                long   id   = c.getLong(idCol);
                String name = nameCol != -1 ? c.getString(nameCol) : debugCols;
                decks.add(new Deck(id, name));
            }
        } catch (Exception e) {
            // AnkiDroid not installed or API disabled
        } finally {
            if (c != null) c.close();
        }
        return decks;
    }

    public String lastDiagnostic = "";

    /**
     * Returns the next due card for the given deck, or null if none are due.
     * Tries reviewInfo/ first; falls back to notes search for V3/FSRS scheduler.
     */
    public CardInfo getNextDueCard(long deckId, String deckName) {
        // Primary: single reviewInfo/ query — do NOT call this twice or it confuses the scheduler
        CardInfo result = queryReviewInfo(deckId);
        if (result != null) { lastDiagnostic = "ri"; return result; }

        // Fallback: notes search (works with any scheduler)
        String q = "is:due deck:\"" + deckName + "\"";
        result = searchDueNote(q, false);
        if (result != null) { lastDiagnostic = "ns"; return result; }

        result = searchDueNote(q, true);
        if (result != null) { lastDiagnostic = "na"; return result; }

        lastDiagnostic = "none";
        return null;
    }

    private CardInfo queryReviewInfo(long deckId) {
        Cursor c = null;
        try {
            c = mCr.query(REVIEW_URI, null, "deckID",
                    new String[]{String.valueOf(deckId)}, null);
            if (c == null || !c.moveToFirst()) return null;
            int noteCol = c.getColumnIndex(COL_NOTE_ID);
            int ordCol  = c.getColumnIndex(COL_CARD_ORD);
            if (noteCol == -1 || ordCol == -1) return null;
            return new CardInfo(c.getLong(noteCol), c.getInt(ordCol));
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.close();
        }
    }

    private CardInfo searchDueNote(String query, boolean queryInArgs) {
        Cursor notesCursor = null;
        try {
            notesCursor = queryInArgs
                    ? mCr.query(NOTES_URI, new String[]{"_id"}, null, new String[]{query}, null)
                    : mCr.query(NOTES_URI, new String[]{"_id"}, query, null, null);
            if (notesCursor == null || !notesCursor.moveToFirst()) return null;
            long noteId = notesCursor.getLong(0);
            return findDueCardOrd(noteId);
        } catch (Exception e) {
            return null;
        } finally {
            if (notesCursor != null) notesCursor.close();
        }
    }

    private CardInfo findDueCardOrd(long noteId) {
        Uri cardsUri = Uri.withAppendedPath(NOTES_URI, noteId + "/cards");
        Cursor c = null;
        try {
            c = mCr.query(cardsUri, null, null, null, null);
            if (c == null) return new CardInfo(noteId, 0);
            int ordCol   = c.getColumnIndex(COL_CARD_ORD);
            int queueCol = c.getColumnIndex("queue");
            while (c.moveToNext()) {
                int ord   = ordCol   >= 0 ? c.getInt(ordCol)   : 0;
                int queue = queueCol >= 0 ? c.getInt(queueCol) : 1;
                if (queue >= 0) return new CardInfo(noteId, ord);
            }
            return new CardInfo(noteId, 0);
        } catch (Exception e) {
            return new CardInfo(noteId, 0);
        } finally {
            if (c != null) c.close();
        }
    }

    /** Returns the front and back text for a card. */
    public CardContent getCardContent(long noteId, int cardOrd) {
        Uri cardUri = Uri.withAppendedPath(NOTES_URI, noteId + "/cards/" + cardOrd);
        Cursor c = null;
        try {
            c = mCr.query(cardUri, null, null, null, null);
            if (c == null || !c.moveToFirst()) return null;
            String front = pickColumn(c, "question_simple", "question");
            String back  = pickColumn(c, "answer_simple",   "answer");
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

    private static String pickColumn(Cursor c, String... names) {
        for (String name : names) {
            int idx = c.getColumnIndex(name);
            if (idx != -1) return c.getString(idx);
        }
        return null;
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

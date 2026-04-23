#pragma once
#include <pebble.h>

// AppMessage keys (must match appinfo.json appKeys)
#define KEY_MSG_TYPE    0
#define KEY_CARD_FRONT  1
#define KEY_CARD_BACK   2
#define KEY_CARD_ID     3
#define KEY_DECK_NAME   4
#define KEY_DECK_LIST   5
#define KEY_ANSWER      6

// Message types
#define MSG_GET_DECKS    0
#define MSG_DECK_LIST    1
#define MSG_SELECT_DECK  2
#define MSG_CARD         3
#define MSG_ANSWER       4
#define MSG_DONE         5
#define MSG_ERROR        6

// Anki ease values
#define EASE_AGAIN  1
#define EASE_GOOD   3

#define INBOX_SIZE   2048
#define OUTBOX_SIZE   512

#define MAX_DECKS      20
#define MAX_DECK_NAME  64
#define MAX_CARD_TEXT  256

typedef enum {
  APP_STATE_LOADING,    // "Connecting..." — waiting for deck list
  APP_STATE_DECK_MENU,  // deck list ready, showing MenuLayer
  APP_STATE_FETCHING,   // "Loading cards..." — deck selected, waiting for first card
  APP_STATE_DONE,       // "Deck complete!"
  APP_STATE_ERROR,      // "Error!" — something went wrong
} AppStateKind;

typedef struct {
  AppStateKind state;

  char deck_names[MAX_DECKS][MAX_DECK_NAME];
  int  deck_count;

  // KEY_CARD_ID holds a session index (0, 1, 2...) not the raw Anki ID,
  // because Anki card IDs are 13-digit epoch-ms values that exceed int32.
  int32_t card_session_idx;
  char    card_front[MAX_CARD_TEXT];
  char    card_back[MAX_CARD_TEXT];

  char error_msg[64];
} AppState;

void       messaging_init(void);
void       messaging_deinit(void);
AppState  *messaging_get_state(void);

void send_get_decks(void);
void send_select_deck(const char *deck_name);
void send_answer(int32_t session_idx, int ease);

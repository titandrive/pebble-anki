#include "messaging.h"
#include "main_window.h"
#include "card_window.h"
#include <string.h>

static AppState s_state;

AppState *messaging_get_state(void) {
  return &s_state;
}

// ---- Outbox ----------------------------------------------------------------

void send_get_decks(void) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_uint32(out, KEY_MSG_TYPE, MSG_GET_DECKS);
  app_message_outbox_send();
}

void send_select_deck(const char *deck_name) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_uint32(out, KEY_MSG_TYPE, MSG_SELECT_DECK);
  dict_write_cstring(out, KEY_DECK_NAME, deck_name);
  app_message_outbox_send();
}

void send_answer(int32_t session_idx, int ease) {
  DictionaryIterator *out;
  if (app_message_outbox_begin(&out) != APP_MSG_OK) return;
  dict_write_uint32(out, KEY_MSG_TYPE, MSG_ANSWER);
  dict_write_uint32(out, KEY_CARD_ID, (uint32_t)session_idx);
  dict_write_uint32(out, KEY_ANSWER, (uint32_t)ease);
  app_message_outbox_send();
}

// ---- Deck list parsing -----------------------------------------------------

static void parse_deck_list(const char *raw) {
  s_state.deck_count = 0;
  char buf[INBOX_SIZE];
  strncpy(buf, raw, sizeof(buf) - 1);
  buf[sizeof(buf) - 1] = '\0';

  char *p = buf;
  char *nl;
  while ((nl = strchr(p, '\n')) != NULL && s_state.deck_count < MAX_DECKS) {
    *nl = '\0';
    if (strlen(p) > 0) {
      strncpy(s_state.deck_names[s_state.deck_count], p, MAX_DECK_NAME - 1);
      s_state.deck_names[s_state.deck_count][MAX_DECK_NAME - 1] = '\0';
      s_state.deck_count++;
    }
    p = nl + 1;
  }
  // Last entry (no trailing newline)
  if (*p && s_state.deck_count < MAX_DECKS) {
    strncpy(s_state.deck_names[s_state.deck_count], p, MAX_DECK_NAME - 1);
    s_state.deck_names[s_state.deck_count][MAX_DECK_NAME - 1] = '\0';
    s_state.deck_count++;
  }
}

// ---- Inbox -----------------------------------------------------------------

static void prv_inbox_received(DictionaryIterator *iter, void *ctx) {
  Tuple *type_t = dict_find(iter, KEY_MSG_TYPE);
  if (!type_t) return;

  uint32_t msg_type = type_t->value->uint32;

  switch (msg_type) {
    case MSG_DECK_LIST: {
      Tuple *list_t = dict_find(iter, KEY_DECK_LIST);
      if (list_t) parse_deck_list(list_t->value->cstring);
      if (card_window_is_on_stack()) window_stack_pop(false);
      // Auto-open last used deck if it's still in the list
      char saved[MAX_DECK_NAME] = {0};
      if (persist_read_string(0, saved, sizeof(saved)) > 0) {
        for (int i = 0; i < s_state.deck_count; i++) {
          if (strcmp(s_state.deck_names[i], saved) == 0) {
            s_state.state = APP_STATE_FETCHING;
            main_window_refresh();
            send_select_deck(saved);
            return;
          }
        }
      }
      s_state.state = APP_STATE_DECK_MENU;
      main_window_refresh();
      break;
    }
    case MSG_CARD: {
      Tuple *front_t = dict_find(iter, KEY_CARD_FRONT);
      Tuple *back_t  = dict_find(iter, KEY_CARD_BACK);
      Tuple *id_t    = dict_find(iter, KEY_CARD_ID);
      if (front_t) strncpy(s_state.card_front, front_t->value->cstring, MAX_CARD_TEXT - 1);
      if (back_t)  strncpy(s_state.card_back,  back_t->value->cstring,  MAX_CARD_TEXT - 1);
      if (id_t)    s_state.card_session_idx = (int32_t)id_t->value->uint32;
      card_window_push();
      break;
    }
    case MSG_DONE: {
      s_state.state = APP_STATE_DONE;
      if (card_window_is_on_stack()) window_stack_pop(false);
      main_window_refresh();
      break;
    }
    case MSG_ERROR: {
      Tuple *err_t = dict_find(iter, KEY_DECK_NAME);
      if (err_t) strncpy(s_state.error_msg, err_t->value->cstring, sizeof(s_state.error_msg) - 1);
      s_state.state = APP_STATE_ERROR;
      if (card_window_is_on_stack()) window_stack_pop(false);
      main_window_refresh();
      break;
    }
  }
}

static void prv_inbox_dropped(AppMessageResult reason, void *ctx) {
  APP_LOG(APP_LOG_LEVEL_ERROR, "Message dropped: %d", (int)reason);
}

// ---- Init / deinit ---------------------------------------------------------

void messaging_init(void) {
  memset(&s_state, 0, sizeof(s_state));
  s_state.state = APP_STATE_LOADING;

  app_message_register_inbox_received(prv_inbox_received);
  app_message_register_inbox_dropped(prv_inbox_dropped);
  app_message_open(INBOX_SIZE, OUTBOX_SIZE);
}

void messaging_deinit(void) {
  app_message_deregister_callbacks();
}

#include "main_window.h"
#include "messaging.h"
#include <string.h>
#include <stdio.h>

// ---- Layout ----------------------------------------------------------------
//
// Full-screen window (144x168).
//
// Status mode (loading / fetching / done / error):
//   Large TextLayer centered vertically.
//
// Deck menu mode:
//   Header TextLayer (top 28px) + deck name TextLayer (rest).
//   UP/DOWN to scroll through decks, SELECT to choose.

#define HEADER_H   28
#define NAV_H      20

static Window    *s_window;
static TextLayer *s_status_layer;
static TextLayer *s_header_layer;
static TextLayer *s_deck_layer;
static TextLayer *s_nav_layer;

static int s_selected_idx;
static char s_nav_buf[16];

// ---- Helpers ---------------------------------------------------------------

static void prv_update_deck_display(void) {
  AppState *s = messaging_get_state();
  if (s->deck_count == 0) {
    text_layer_set_text(s_deck_layer, "No decks found");
    text_layer_set_text(s_nav_layer, "");
    return;
  }
  if (s_selected_idx >= s->deck_count) s_selected_idx = s->deck_count - 1;
  if (s_selected_idx < 0) s_selected_idx = 0;
  text_layer_set_text(s_deck_layer, s->deck_names[s_selected_idx]);
  snprintf(s_nav_buf, sizeof(s_nav_buf), "%d / %d", s_selected_idx + 1, s->deck_count);
  text_layer_set_text(s_nav_layer, s_nav_buf);
}

// ---- Click handlers (deck menu mode) ---------------------------------------

static void prv_up_click(ClickRecognizerRef r, void *ctx) {
  AppState *s = messaging_get_state();
  if (s->state != APP_STATE_DECK_MENU) return;
  if (s_selected_idx > 0) s_selected_idx--;
  prv_update_deck_display();
}

static void prv_down_click(ClickRecognizerRef r, void *ctx) {
  AppState *s = messaging_get_state();
  if (s->state != APP_STATE_DECK_MENU) return;
  if (s_selected_idx < s->deck_count - 1) s_selected_idx++;
  prv_update_deck_display();
}

static void prv_select_click(ClickRecognizerRef r, void *ctx) {
  AppState *s = messaging_get_state();
  if (s->state != APP_STATE_DECK_MENU) return;
  if (s->deck_count == 0) return;
  persist_write_string(0, s->deck_names[s_selected_idx]);
  s->state = APP_STATE_FETCHING;
  main_window_refresh();
  send_select_deck(s->deck_names[s_selected_idx]);
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_UP,     prv_up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN,   prv_down_click);
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
}

// ---- Window lifecycle ------------------------------------------------------

static void prv_window_load(Window *win) {
  Layer *root = window_get_root_layer(win);
  GRect bounds = layer_get_bounds(root);
  int w = bounds.size.w;
  int h = bounds.size.h;

  // Status layer — centered, full screen (shown during loading/fetching/done/error)
  s_status_layer = text_layer_create(GRect(0, h / 2 - 24, w, 48));
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_status_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_status_layer));

  // Header — top bar (shown in deck menu mode)
  s_header_layer = text_layer_create(GRect(0, 0, w, HEADER_H));
  text_layer_set_text_alignment(s_header_layer, GTextAlignmentCenter);
  text_layer_set_font(s_header_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_header_layer, GColorBlack);
  text_layer_set_text_color(s_header_layer, GColorWhite);
  text_layer_set_text(s_header_layer, "Select Deck");
  layer_add_child(root, text_layer_get_layer(s_header_layer));

  // Deck name — middle area
  s_deck_layer = text_layer_create(GRect(4, HEADER_H + 4, w - 8, h - HEADER_H - NAV_H - 8));
  text_layer_set_font(s_deck_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_overflow_mode(s_deck_layer, GTextOverflowModeWordWrap);
  text_layer_set_background_color(s_deck_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_deck_layer));

  // Nav indicator — bottom bar
  s_nav_layer = text_layer_create(GRect(0, h - NAV_H, w, NAV_H));
  text_layer_set_text_alignment(s_nav_layer, GTextAlignmentCenter);
  text_layer_set_font(s_nav_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_background_color(s_nav_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_nav_layer));

  window_set_click_config_provider(win, prv_click_config);

  // Initial state
  layer_set_hidden(text_layer_get_layer(s_header_layer), true);
  layer_set_hidden(text_layer_get_layer(s_deck_layer),   true);
  layer_set_hidden(text_layer_get_layer(s_nav_layer),    true);
  text_layer_set_text(s_status_layer, "Connecting\nto Anki...");
}

static void prv_window_unload(Window *win) {
  text_layer_destroy(s_status_layer);
  text_layer_destroy(s_header_layer);
  text_layer_destroy(s_deck_layer);
  text_layer_destroy(s_nav_layer);
  s_status_layer = NULL;
  s_header_layer = NULL;
  s_deck_layer   = NULL;
  s_nav_layer    = NULL;
}

// ---- Public API ------------------------------------------------------------

void main_window_init(void) {
  s_window = window_create();
  window_set_window_handlers(s_window, (WindowHandlers){
    .load   = prv_window_load,
    .unload = prv_window_unload,
  });
}

void main_window_push(void) {
  window_stack_push(s_window, true);
}

void main_window_deinit(void) {
  window_destroy(s_window);
}

void main_window_show_deck_menu(void) {
  messaging_get_state()->state = APP_STATE_DECK_MENU;
  main_window_refresh();
}

void main_window_refresh(void) {
  if (!s_status_layer) return;

  AppState *s = messaging_get_state();
  bool show_menu = (s->state == APP_STATE_DECK_MENU);

  layer_set_hidden(text_layer_get_layer(s_status_layer), show_menu);
  layer_set_hidden(text_layer_get_layer(s_header_layer), !show_menu);
  layer_set_hidden(text_layer_get_layer(s_deck_layer),   !show_menu);
  layer_set_hidden(text_layer_get_layer(s_nav_layer),    !show_menu);

  if (show_menu) {
    s_selected_idx = 0;
    prv_update_deck_display();
  } else {
    const char *msg;
    switch (s->state) {
      case APP_STATE_LOADING:  msg = "Connecting\nto Anki..."; break;
      case APP_STATE_FETCHING: msg = "Loading\ncards...";      break;
      case APP_STATE_DONE:     msg = "Deck\ncomplete!";        break;
      case APP_STATE_ERROR:    msg = "Error!\nCheck Anki.";    break;
      default:                 msg = "";                        break;
    }
    text_layer_set_text(s_status_layer, msg);
  }
}

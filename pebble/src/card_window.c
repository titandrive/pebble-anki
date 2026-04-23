#include "card_window.h"
#include "messaging.h"

// ---- Layout (144x168) ------------------------------------------------------
//
//  y=0   ┌──────────────────────────────┐
//        │ hint bar [16px]              │  FONT_KEY_GOTHIC_14, inverted
//  y=16  ├──────────────────────────────┤
//        │                              │
//        │  front / back text [112px]   │  FONT_KEY_GOTHIC_18, word-wrap
//        │                              │
//  y=128 ├──────────────────────────────┤
//        │  answer prompt [40px]        │  FONT_KEY_GOTHIC_28_BOLD, center
//  y=168 └──────────────────────────────┘
//
// Before flip: front text visible, back text hidden.
//   hint  = "SELECT: Show Answer"
//   prompt = ""
//
// After flip: back text visible, front text hidden.
//   hint  = "▲ Good   ▼ Again"
//   prompt = "How did you do?"

static Window    *s_window;
static TextLayer *s_hint_layer;
static TextLayer *s_front_layer;
static TextLayer *s_back_layer;
static TextLayer *s_prompt_layer;
static bool       s_showing_back;
static bool       s_waiting;       // answered, waiting for next card

// ---- Helpers ---------------------------------------------------------------

static void prv_show_front(void) {
  AppState *s = messaging_get_state();
  s_showing_back = false;
  s_waiting = false;

  text_layer_set_text(s_front_layer, s->card_front);
  text_layer_set_text(s_back_layer, "");
  layer_set_hidden(text_layer_get_layer(s_front_layer), false);
  layer_set_hidden(text_layer_get_layer(s_back_layer), true);

  text_layer_set_text(s_hint_layer, "SELECT: Show Answer");
  text_layer_set_text(s_prompt_layer, "");
}

static void prv_show_back(void) {
  AppState *s = messaging_get_state();
  s_showing_back = true;

  text_layer_set_text(s_back_layer, s->card_back);
  layer_set_hidden(text_layer_get_layer(s_front_layer), true);
  layer_set_hidden(text_layer_get_layer(s_back_layer), false);

  text_layer_set_text(s_hint_layer, "\x18 Good   \x19 Again");
  text_layer_set_text(s_prompt_layer, "How did you do?");
}

// ---- Button handlers -------------------------------------------------------

static void prv_select_click(ClickRecognizerRef r, void *ctx) {
  if (!s_showing_back && !s_waiting) {
    prv_show_back();
  }
}

static void prv_up_click(ClickRecognizerRef r, void *ctx) {
  if (s_showing_back && !s_waiting) {
    s_waiting = true;
    text_layer_set_text(s_hint_layer, "");
    text_layer_set_text(s_prompt_layer, "Loading...");
    send_answer(messaging_get_state()->card_session_idx, EASE_GOOD);
  }
}

static void prv_down_click(ClickRecognizerRef r, void *ctx) {
  if (s_showing_back && !s_waiting) {
    s_waiting = true;
    text_layer_set_text(s_hint_layer, "");
    text_layer_set_text(s_prompt_layer, "Loading...");
    send_answer(messaging_get_state()->card_session_idx, EASE_AGAIN);
  }
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_UP, prv_up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN, prv_down_click);
}

// ---- Window lifecycle ------------------------------------------------------

static void prv_window_load(Window *win) {
  Layer *root = window_get_root_layer(win);

  // Hint bar — top 16px, inverted colours
  s_hint_layer = text_layer_create(GRect(0, 0, 144, 16));
  text_layer_set_font(s_hint_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_hint_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_hint_layer, GColorBlack);
  text_layer_set_text_color(s_hint_layer, GColorWhite);
  layer_add_child(root, text_layer_get_layer(s_hint_layer));

  // Front text — 16..128 (112px)
  s_front_layer = text_layer_create(GRect(4, 20, 136, 104));
  text_layer_set_font(s_front_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_overflow_mode(s_front_layer, GTextOverflowModeWordWrap);
  text_layer_set_background_color(s_front_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_front_layer));

  // Back text — same region, shown after flip
  s_back_layer = text_layer_create(GRect(4, 20, 136, 104));
  text_layer_set_font(s_back_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_overflow_mode(s_back_layer, GTextOverflowModeWordWrap);
  text_layer_set_background_color(s_back_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_back_layer));

  // Answer prompt — bottom 40px
  s_prompt_layer = text_layer_create(GRect(0, 128, 144, 40));
  text_layer_set_font(s_prompt_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_prompt_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_prompt_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_prompt_layer));

  window_set_click_config_provider(win, prv_click_config);

  prv_show_front();
}

static void prv_window_unload(Window *win) {
  text_layer_destroy(s_hint_layer);
  text_layer_destroy(s_front_layer);
  text_layer_destroy(s_back_layer);
  text_layer_destroy(s_prompt_layer);
  s_hint_layer = s_front_layer = s_back_layer = s_prompt_layer = NULL;
}

// ---- Public API ------------------------------------------------------------

void card_window_init(void) {
  s_window = window_create();
  window_set_background_color(s_window, GColorWhite);
  window_set_window_handlers(s_window, (WindowHandlers){
    .load   = prv_window_load,
    .unload = prv_window_unload,
  });
}

void card_window_push(void) {
  // Update state before pushing (load handler reads from AppState)
  // If already on stack, refresh layers directly
  if (window_stack_contains_window(s_window)) {
    if (window_is_loaded(s_window)) prv_show_front();
  } else {
    window_stack_push(s_window, true);
    // prv_window_load calls prv_show_front()
  }
}

void card_window_deinit(void) {
  window_destroy(s_window);
}

bool card_window_is_on_stack(void) {
  return window_stack_contains_window(s_window);
}

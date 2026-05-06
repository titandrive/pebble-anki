#include "card_window.h"
#include "messaging.h"
#include "main_window.h"

// ---- Layout (adapts to display size) ---------------------------------------
//
//  y=0   ┌──────────────────────────────┐
//        │ hint bar [16px]              │  FONT_KEY_GOTHIC_14, inverted
//  y=16  ├──────────────────────────────┤
//        │                              │
//        │  card text (scrollable)      │  plain Layer viewport + TextLayer
//        │                              │
//  y=-40 ├──────────────────────────────┤
//        │  answer prompt [40px]        │  FONT_KEY_GOTHIC_18_BOLD, center
//  y=h   └──────────────────────────────┘
//
// Controls:
//   UP (short)     = Good   (any time)
//   DOWN (short)   = Again  (any time)
//   SELECT (short) = Flip   (show answer)
//   UP (hold)      = scroll up
//   DOWN (hold)    = scroll down
//   SELECT (hold)  = back to deck menu

#define HINT_H      16
#define PROMPT_H    40
#define PAD          4
#define SCROLL_STEP 20

static Window    *s_window;
static TextLayer *s_hint_layer;
static Layer     *s_clip_layer;
static TextLayer *s_content_layer;
static TextLayer *s_prompt_layer;

static bool s_showing_back;
static bool s_waiting;
static int  s_scroll_offset;
static int  s_content_h;
static int  s_scroll_area_h;
static int  s_content_w;

// ---- Scroll ----------------------------------------------------------------

static void prv_apply_scroll(void) {
  if (s_scroll_offset < 0) s_scroll_offset = 0;
  int max_scroll = s_content_h > s_scroll_area_h ? s_content_h - s_scroll_area_h : 0;
  if (s_scroll_offset > max_scroll) s_scroll_offset = max_scroll;
  layer_set_frame(text_layer_get_layer(s_content_layer),
    GRect(0, PAD - s_scroll_offset, s_content_w, s_content_h));
}

static void prv_load_text(const char *text) {
  s_scroll_offset = 0;
  GFont font = fonts_get_system_font(FONT_KEY_GOTHIC_18);
  GSize sz = graphics_text_layout_get_content_size(
    text, font, GRect(0, 0, s_content_w, 2000),
    GTextOverflowModeWordWrap, GTextAlignmentLeft);
  s_content_h = sz.h + PAD * 2;
  text_layer_set_text(s_content_layer, text);
  prv_apply_scroll();
}

// ---- Helpers ---------------------------------------------------------------

static void prv_show_front(void) {
  s_showing_back = false;
  s_waiting = false;
  prv_load_text(messaging_get_state()->card_front);
  text_layer_set_text(s_hint_layer, "SEL: Flip  hold\x18\x19: Scroll");
  text_layer_set_text(s_prompt_layer, "");
}

// ---- Click handlers --------------------------------------------------------

static void prv_select_click(ClickRecognizerRef r, void *ctx) {
  if (s_waiting) return;
  s_showing_back = true;
  prv_load_text(messaging_get_state()->card_back);
  text_layer_set_text(s_hint_layer, "\x18 Good  \x19 Again  hold: Scroll");
  text_layer_set_text(s_prompt_layer, "How did you do?");
}

static void prv_up_click(ClickRecognizerRef r, void *ctx) {
  if (s_waiting) return;
  s_waiting = true;
  text_layer_set_text(s_hint_layer, "");
  text_layer_set_text(s_prompt_layer, "Loading...");
  send_answer(messaging_get_state()->card_session_idx, EASE_GOOD);
}

static void prv_down_click(ClickRecognizerRef r, void *ctx) {
  if (s_waiting) return;
  s_waiting = true;
  text_layer_set_text(s_hint_layer, "");
  text_layer_set_text(s_prompt_layer, "Loading...");
  send_answer(messaging_get_state()->card_session_idx, EASE_AGAIN);
}

static void prv_scroll_up(ClickRecognizerRef r, void *ctx) {
  s_scroll_offset -= SCROLL_STEP;
  prv_apply_scroll();
}

static void prv_scroll_down(ClickRecognizerRef r, void *ctx) {
  s_scroll_offset += SCROLL_STEP;
  prv_apply_scroll();
}

static void prv_long_select(ClickRecognizerRef r, void *ctx) {
  window_stack_pop(true);
  main_window_show_deck_menu();
}

static void prv_click_config(void *ctx) {
  window_single_click_subscribe(BUTTON_ID_SELECT, prv_select_click);
  window_single_click_subscribe(BUTTON_ID_UP,     prv_up_click);
  window_single_click_subscribe(BUTTON_ID_DOWN,   prv_down_click);
  window_long_click_subscribe(BUTTON_ID_SELECT, 0, prv_long_select, NULL);
  window_long_click_subscribe(BUTTON_ID_UP,     0, prv_scroll_up,   NULL);
  window_long_click_subscribe(BUTTON_ID_DOWN,   0, prv_scroll_down, NULL);
}

// ---- Window lifecycle ------------------------------------------------------

static void prv_window_load(Window *win) {
  Layer *root = window_get_root_layer(win);
  GRect bounds = layer_get_bounds(root);
  int w = bounds.size.w;
  int h = bounds.size.h;
  s_scroll_area_h = h - HINT_H - PROMPT_H;
  s_content_w = w - PAD * 2;

  // Hint bar
  s_hint_layer = text_layer_create(GRect(0, 0, w, HINT_H));
  text_layer_set_font(s_hint_layer, fonts_get_system_font(FONT_KEY_GOTHIC_14));
  text_layer_set_text_alignment(s_hint_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_hint_layer, GColorBlack);
  text_layer_set_text_color(s_hint_layer, GColorWhite);
  layer_add_child(root, text_layer_get_layer(s_hint_layer));

  // Clip viewport
  s_clip_layer = layer_create(GRect(0, HINT_H, w, s_scroll_area_h));
  layer_add_child(root, s_clip_layer);

  // Content TextLayer — child of clip layer, repositioned to scroll
  s_content_layer = text_layer_create(GRect(0, PAD, s_content_w, s_scroll_area_h));
  text_layer_set_font(s_content_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18));
  text_layer_set_overflow_mode(s_content_layer, GTextOverflowModeWordWrap);
  text_layer_set_background_color(s_content_layer, GColorClear);
  layer_add_child(s_clip_layer, text_layer_get_layer(s_content_layer));

  // Prompt bar
  s_prompt_layer = text_layer_create(GRect(0, h - PROMPT_H, w, PROMPT_H));
  text_layer_set_font(s_prompt_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_text_alignment(s_prompt_layer, GTextAlignmentCenter);
  text_layer_set_background_color(s_prompt_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_prompt_layer));

  window_set_click_config_provider(win, prv_click_config);
  prv_show_front();
}

static void prv_window_unload(Window *win) {
  text_layer_destroy(s_hint_layer);
  text_layer_destroy(s_content_layer);  // destroy child before parent layer
  layer_destroy(s_clip_layer);
  text_layer_destroy(s_prompt_layer);
  s_hint_layer    = NULL;
  s_content_layer = NULL;
  s_clip_layer    = NULL;
  s_prompt_layer  = NULL;
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
  if (window_stack_contains_window(s_window)) {
    if (window_is_loaded(s_window)) prv_show_front();
  } else {
    window_stack_push(s_window, true);
  }
}

void card_window_deinit(void) {
  window_destroy(s_window);
}

bool card_window_is_on_stack(void) {
  return window_stack_contains_window(s_window);
}

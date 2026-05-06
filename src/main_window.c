#include "main_window.h"
#include "messaging.h"
#include <string.h>

// ---- Layout ----------------------------------------------------------------
//
// Full-screen window (144x168).
//
// Status mode (loading / fetching / done / error):
//   TextLayer centered vertically, full width.
//
// Deck menu mode:
//   MenuLayer full screen, header row 28px, each deck row 40px.

#define HEADER_HEIGHT 28
#define ROW_HEIGHT    40

static Window    *s_window;
static TextLayer *s_status_layer;
static MenuLayer *s_menu_layer;

// ---- MenuLayer callbacks ---------------------------------------------------

static uint16_t prv_menu_num_sections(MenuLayer *ml, void *ctx) {
  return 1;
}

static uint16_t prv_menu_num_rows(MenuLayer *ml, uint16_t section, void *ctx) {
  return (uint16_t)messaging_get_state()->deck_count;
}

static int16_t prv_menu_header_height(MenuLayer *ml, uint16_t section, void *ctx) {
  return HEADER_HEIGHT;
}

static int16_t prv_menu_row_height(MenuLayer *ml, MenuIndex *idx, void *ctx) {
  return ROW_HEIGHT;
}

static void prv_menu_draw_header(GContext *gctx, const Layer *cell_layer,
                                 uint16_t section, void *ctx) {
  menu_cell_basic_header_draw(gctx, cell_layer, "Select Deck");
}

static void prv_menu_draw_row(GContext *gctx, const Layer *cell_layer,
                              MenuIndex *idx, void *ctx) {
  AppState *s = messaging_get_state();
  if (idx->row < (uint16_t)s->deck_count) {
    menu_cell_basic_draw(gctx, cell_layer, s->deck_names[idx->row], NULL, NULL);
  }
}

static void prv_menu_selection_changed(MenuLayer *ml, MenuIndex new_idx,
                                       MenuIndex old_idx, void *ctx) {}

static void prv_menu_select(MenuLayer *ml, MenuIndex *idx, void *ctx) {
  AppState *s = messaging_get_state();
  if (idx->row >= (uint16_t)s->deck_count) return;

  persist_write_string(0, s->deck_names[idx->row]);
  s->state = APP_STATE_FETCHING;
  main_window_refresh();
  send_select_deck(s->deck_names[idx->row]);
}

// ---- Window lifecycle ------------------------------------------------------

static void prv_window_load(Window *win) {
  Layer *root = window_get_root_layer(win);
  GRect bounds = layer_get_bounds(root);

  // Status text layer — centered vertically
  s_status_layer = text_layer_create(
    GRect(0, bounds.size.h / 2 - 24, bounds.size.w, 48)
  );
  text_layer_set_text_alignment(s_status_layer, GTextAlignmentCenter);
  text_layer_set_font(s_status_layer, fonts_get_system_font(FONT_KEY_GOTHIC_18_BOLD));
  text_layer_set_background_color(s_status_layer, GColorClear);
  layer_add_child(root, text_layer_get_layer(s_status_layer));

  // Menu layer — full screen
  s_menu_layer = menu_layer_create(bounds);
  menu_layer_set_callbacks(s_menu_layer, NULL, (MenuLayerCallbacks){
    .get_num_sections = prv_menu_num_sections,
    .get_num_rows     = prv_menu_num_rows,
    .get_header_height = prv_menu_header_height,
    .get_cell_height  = prv_menu_row_height,
    .draw_header      = prv_menu_draw_header,
    .draw_row          = prv_menu_draw_row,
    .select_click      = prv_menu_select,
    .selection_changed = prv_menu_selection_changed,
  });
  menu_layer_set_click_config_onto_window(s_menu_layer, win);
  layer_add_child(root, menu_layer_get_layer(s_menu_layer));

  // Initialize view state directly — initial state is always APP_STATE_LOADING
  layer_set_hidden(text_layer_get_layer(s_status_layer), false);
  layer_set_hidden(menu_layer_get_layer(s_menu_layer), true);
  text_layer_set_text(s_status_layer, "Connecting\nto Anki...");
}

static void prv_window_unload(Window *win) {
  text_layer_destroy(s_status_layer);
  s_status_layer = NULL;
  menu_layer_destroy(s_menu_layer);
  s_menu_layer = NULL;
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
  if (!s_status_layer || !s_menu_layer) return;

  AppState *s = messaging_get_state();
  bool show_menu = (s->state == APP_STATE_DECK_MENU);

  layer_set_hidden(text_layer_get_layer(s_status_layer), show_menu);
  layer_set_hidden(menu_layer_get_layer(s_menu_layer), !show_menu);

  if (show_menu) {
    menu_layer_reload_data(s_menu_layer);
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

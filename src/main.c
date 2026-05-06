#include <pebble.h>
#include "messaging.h"
#include "main_window.h"
#include "card_window.h"

static void init(void) {
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: start");
  main_window_init();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: main_window_init done");
  card_window_init();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: card_window_init done");
  messaging_init();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: messaging_init done");
  main_window_push();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: main_window_push done");
  send_get_decks();
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init: send_get_decks done");
}

static void deinit(void) {
  messaging_deinit();
  card_window_deinit();
  main_window_deinit();
}

int main(void) {
  init();
  app_event_loop();
  deinit();
  return 0;
}

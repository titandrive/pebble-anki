#include <pebble.h>
#include "messaging.h"
#include "main_window.h"
#include "card_window.h"

static void init(void) {
  main_window_init();
  card_window_init();
  messaging_init();   // opens AppMessage — must come after window init
  main_window_push();
  send_get_decks();
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

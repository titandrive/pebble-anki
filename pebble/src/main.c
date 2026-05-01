#include <pebble.h>
#include "messaging.h"
#include "main_window.h"
#include "card_window.h"

#define CRASH_STAGE_KEY 99

static void prv_log_crash_stage(uint8_t stage) {
  persist_write_int(CRASH_STAGE_KEY, stage);
}

static void init(void) {
  int last = persist_read_int(CRASH_STAGE_KEY);
  APP_LOG(APP_LOG_LEVEL_DEBUG, "init last_crash_stage=%d", last);
  persist_write_int(CRASH_STAGE_KEY, 0);

  prv_log_crash_stage(1);
  main_window_init();
  prv_log_crash_stage(2);
  card_window_init();
  prv_log_crash_stage(3);
  messaging_init();
  prv_log_crash_stage(4);
  main_window_push();
  prv_log_crash_stage(5);
  send_get_decks();
  prv_log_crash_stage(6);
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

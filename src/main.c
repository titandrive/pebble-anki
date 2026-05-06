#include <pebble.h>

static Window *s_win;

static void init(void) {
  s_win = window_create();
  window_stack_push(s_win, true);
}

static void deinit(void) {
  window_destroy(s_win);
}

int main(void) {
  init();
  app_event_loop();
  deinit();
  return 0;
}

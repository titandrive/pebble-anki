#pragma once
#include <stdbool.h>

void card_window_init(void);
void card_window_push(void);
void card_window_deinit(void);
bool card_window_is_on_stack(void);

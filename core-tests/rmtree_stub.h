#pragma once
#include <stdio.h>
#include <stdlib.h>

static int system_rm(const char *path) {
  char cmd[8300];
  snprintf(cmd, sizeof cmd, "rm -rf -- '%s'", path);
  return system(cmd);
}

#include "shq.h"
#include "test.h"

/* sh -c "printf %s <quoted>" должен вернуть исходные байты. */
static void roundtrip(const char *s) {
  char q[1024], cmd[1100], out[1024];
  CHECK(shq(s, q, sizeof q) > 0);
  snprintf(cmd, sizeof cmd, "printf %%s %s", q);
  FILE *f = popen(cmd, "r");
  size_t n = fread(out, 1, sizeof out - 1, f);
  pclose(f);
  out[n] = 0;
  CHECK(strcmp(out, s) == 0);
}

int main(void) {
  roundtrip("plain");
  roundtrip("it's");
  roundtrip("a b  c");
  roundtrip("new\nline");
  roundtrip("$(touch /tmp/ancdu-pwned)`id`;|&");
  roundtrip("\xff\xfe");
  roundtrip("'''");
  CHECK(access("/tmp/ancdu-pwned", F_OK) != 0);
  char small[4];
  CHECK(shq("abcd", small, sizeof small) == -1);
  TEST_END();
}

#include "shq.h"

int shq(const char *s, char *out, size_t cap) {
  size_t k = 0;
#define PUT(c)                    \
  do {                            \
    if (k + 1 >= cap) return -1;  \
    out[k++] = (c);               \
  } while (0)
  PUT('\'');
  for (; *s; s++) {
    if (*s == '\'') {
      PUT('\'');
      PUT('\\');
      PUT('\'');
      PUT('\'');
    } else {
      PUT(*s);
    }
  }
  PUT('\'');
#undef PUT
  out[k] = 0;
  return (int)k;
}

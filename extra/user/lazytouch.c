// Grow the heap lazily, then touch one byte in each new page.
// Each first touch is a page fault that the kernel serves by
// allocating and mapping a page (vmfault).
#include "kernel/types.h"
#include "user/user.h"

#define NPAGES 3
#define PGSIZE 4096

int
main(void)
{
  char *p = sbrklazy(NPAGES * PGSIZE);
  if (p == (char *)-1) {
    printf("lazytouch: sbrklazy failed\n");
    exit(1);
  }
  for (int i = 0; i < NPAGES; i++)
    p[i * PGSIZE] = i + 1;
  exit(0);
}

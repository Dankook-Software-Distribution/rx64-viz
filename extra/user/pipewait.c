// Sleep and wakeup on a pipe, and a process adopted by init.
// The child forks a grandchild and exits at once: the grandchild is an
// orphan, so init becomes its parent. The grandchild reads from the
// empty pipe and sleeps until the parent writes into it; it exits, and
// init (not the parent) reaps it.
#include "kernel/types.h"
#include "user/user.h"

int
main(void)
{
  int p[2];
  char buf[8];

  if (pipe(p) < 0) {
    printf("pipewait: pipe failed\n");
    exit(1);
  }
  int child = fork();
  if (child < 0) {
    printf("pipewait: fork failed\n");
    exit(1);
  }
  if (child == 0) {
    if (fork() == 0) {
      // grandchild: the pipe is empty, so read() sleeps
      close(p[1]);
      int n = read(p[0], buf, sizeof buf);
      if (n > 0)
        write(1, buf, n);
      exit(0);
    }
    exit(0); // the grandchild is now an orphan
  }
  close(p[0]);
  wait(0);  // the child, which exited at once
  pause(2); // let the grandchild block on the empty pipe
  write(p[1], "woke\n", 5);
  close(p[1]);
  exit(0);
}

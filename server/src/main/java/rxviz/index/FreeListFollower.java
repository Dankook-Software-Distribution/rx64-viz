package rxviz.index;

import java.util.ArrayList;
import java.util.List;

import rxviz.config.KernelProfile;

/**
 * kmem.freelist followed through the stores to its head: kalloc stores
 * the second frame there (a pop), kfree the frame it frees (a push).
 * Starts from the list as it was read at some step.
 */
final class FreeListFollower {

    private final KernelProfile kernel;
    /* the list, head last (pops and pushes at the end) */
    private final List<Long> free;

    FreeListFollower(KernelProfile kernel, List<Long> atStart) {
        this.kernel = kernel;
        this.free = new ArrayList<>(atStart.reversed());
    }

    /** A store of value into the head: the frame it popped (positive), or pushed (negated, minus 1). */
    long apply(long value) {
        long f = kernel.directMapToPhys(value);
        int n = free.size();
        if (value == 0 || (n > 1 && free.get(n - 2) == f))
            return n > 0 ? free.removeLast() : 0;
        free.add(f);
        return -f - 1;
    }

    int size() {
        return free.size();
    }
}

package rxviz.index;

import java.util.Arrays;

/**
 * Every value stored into a proc[k].state over a recording (found while
 * indexing): the state each process slot was in at any step, from the
 * kernel's own stores, not inferred.
 */
final class ProcStates {

    private final int[] initial;
    /* in step order */
    private final int[] steps;
    private final short[] slots;
    private final int[] values;
    /* per slot: the indexes of its changes, in order */
    private final int[][] bySlot;

    private ProcStates(int[] initial, int[] steps, short[] slots, int[] values) {
        this.initial = initial;
        this.steps = steps;
        this.slots = slots;
        this.values = values;
        int[] n = new int[initial.length];
        for (short k : slots)
            n[k]++;
        bySlot = new int[initial.length][];
        for (int k = 0; k < n.length; k++)
            bySlot[k] = new int[n[k]];
        int[] fill = new int[initial.length];
        for (int i = 0; i < slots.length; i++)
            bySlot[slots[i]][fill[slots[i]]++] = i;
    }

    int size() { return steps.length; }
    int step(int i) { return steps[i]; }
    int slot(int i) { return slots[i]; }
    int value(int i) { return values[i]; }
    int slots() { return initial.length; }

    /** proc[slot].state at the start of the recording. */
    int initial(int slot) {
        return slot < initial.length ? initial[slot] : 0;
    }

    /** Index of the first change at or after step. */
    int firstAt(int step) {
        return lowerBound(steps.length, i -> steps[i] < step);
    }

    /** proc[slot].state before step runs. */
    int at(int slot, int step) {
        if (slot < 0 || slot >= bySlot.length)
            return 0;
        int[] mine = bySlot[slot];
        int k = lowerBound(mine.length, j -> steps[mine[j]] < step);
        return k == 0 ? initial(slot) : values[mine[k - 1]];
    }

    /** Index of slot's first change at or after step, or -1. */
    int nextOf(int slot, int step) {
        if (slot < 0 || slot >= bySlot.length)
            return -1;
        int[] mine = bySlot[slot];
        int k = lowerBound(mine.length, j -> steps[mine[j]] < step);
        return k < mine.length ? mine[k] : -1;
    }

    /** The first index in [0, n) where before no longer holds. */
    static int lowerBound(int n, java.util.function.IntPredicate before) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (before.test(mid))
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo;
    }

    static final class Builder {
        private static final int MAX = 1 << 22;
        private final int[] initial;
        private int[] steps = new int[256];
        private short[] slots = new short[256];
        private int[] values = new int[256];
        private int n;

        Builder(int[] initial) {
            this.initial = initial.clone();
        }

        void add(int step, int slot, int value) {
            if (n == MAX)
                return;
            if (n == steps.length) {
                steps = Arrays.copyOf(steps, n * 2);
                slots = Arrays.copyOf(slots, n * 2);
                values = Arrays.copyOf(values, n * 2);
            }
            steps[n] = step;
            slots[n] = (short) slot;
            values[n] = value;
            n++;
        }

        ProcStates build() {
            return new ProcStates(initial, Arrays.copyOf(steps, n), Arrays.copyOf(slots, n), Arrays.copyOf(values, n));
        }
    }
}

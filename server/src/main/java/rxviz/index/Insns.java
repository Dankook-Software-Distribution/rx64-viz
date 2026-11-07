package rxviz.index;

import java.util.Arrays;

/**
 * Instructions of one segment by id. The plugin numbers translations from
 * one counter, so a segment's ids are a dense range and an array indexed
 * by id - base replaces a map on the hot path.
 */
final class Insns {

    private Recording.Insn[] arr = new Recording.Insn[4096];
    private int base = -1;

    void put(int id, Recording.Insn in) {
        if (base < 0)
            base = id;
        if (id < base) {
            /* not expected (ids only grow), but keep working */
            Recording.Insn[] a = new Recording.Insn[arr.length + (base - id)];
            System.arraycopy(arr, 0, a, base - id, arr.length);
            arr = a;
            base = id;
        }
        int i = id - base;
        if (i >= arr.length)
            arr = Arrays.copyOf(arr, Math.max(arr.length * 2, i + 1));
        arr[i] = in;
    }

    Recording.Insn get(int id) {
        int i = id - base;
        return base < 0 || i < 0 || i >= arr.length ? null : arr[i];
    }
}

package rxviz.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The event tree of a recording. Roots are runs: stretches of time in
 * which one thread (a process, or the scheduler) runs. Inside a run,
 * nodes are function calls and interrupts/exceptions/syscalls. A frame
 * that is still open when its thread is switched out ends there
 * (SUSPENDED) and is continued in the thread's next run (CONTINUED).
 *
 * Nodes are created in order of their start step, so node ids, and each
 * node's children, are sorted by start.
 */
public final class Tree {

    public static final byte RUN = 0, FUNC = 1, INTR = 2;
    public static final byte SUSPENDED = 1, CONTINUED = 2;

    int n;
    int[] start = new int[1024];
    int[] end = new int[1024];
    int[] parent = new int[1024];
    byte[] kind = new byte[1024];
    byte[] flags = new byte[1024];
    int[] thread = new int[1024];
    int[] vector = new int[1024];
    final List<String> label = new ArrayList<>();

    /* children in CSR form, built by finish() */
    private int[] childStart;
    private int[] childIdx;
    private int[] roots;
    /* INTR nodes that start a trap (not continued), in start order */
    private int[] traps;

    int add(byte k, int par, int st, int thr, String lab) {
        if (n == start.length) {
            int c = n * 2;
            start = Arrays.copyOf(start, c);
            end = Arrays.copyOf(end, c);
            parent = Arrays.copyOf(parent, c);
            kind = Arrays.copyOf(kind, c);
            flags = Arrays.copyOf(flags, c);
            thread = Arrays.copyOf(thread, c);
            vector = Arrays.copyOf(vector, c);
        }
        start[n] = st;
        end[n] = -1;
        parent[n] = par;
        kind[n] = k;
        flags[n] = 0;
        thread[n] = thr;
        vector[n] = -1;
        label.add(lab);
        return n++;
    }

    void finish() {
        int[] count = new int[n + 1];
        int nroots = 0;
        for (int i = 0; i < n; i++) {
            if (parent[i] < 0)
                nroots++;
            else
                count[parent[i]]++;
        }
        childStart = new int[n + 1];
        for (int i = 0; i < n; i++)
            childStart[i + 1] = childStart[i] + count[i];
        childIdx = new int[childStart[n]];
        int ntraps = 0;
        for (int i = 0; i < n; i++)
            if (kind[i] == INTR && (flags[i] & CONTINUED) == 0)
                ntraps++;
        traps = new int[ntraps];
        for (int i = 0, k = 0; i < n; i++)
            if (kind[i] == INTR && (flags[i] & CONTINUED) == 0)
                traps[k++] = i;
        int[] fill = Arrays.copyOf(childStart, n);
        roots = new int[nroots];
        int r = 0;
        for (int i = 0; i < n; i++) {
            if (parent[i] < 0)
                roots[r++] = i;
            else
                childIdx[fill[parent[i]]++] = i;
        }
    }

    public int size() { return n; }
    public int start(int i) { return start[i]; }
    public int end(int i) { return end[i]; }
    public int parent(int i) { return parent[i]; }
    public byte kind(int i) { return kind[i]; }
    public byte flags(int i) { return flags[i]; }
    public int vector(int i) { return vector[i]; }
    public String label(int i) { return label.get(i); }
    public int thread(int i) { return thread[i]; }

    /** Runs (roots), in start order. Do not modify. */
    int[] runs() { return roots; }

    /** Nodes that start a trap, in start order. Do not modify. */
    int[] traps() { return traps; }

    public int[] children(int i) {
        if (i < 0)
            return roots;
        return Arrays.copyOfRange(childIdx, childStart[i], childStart[i + 1]);
    }

    public int childCount(int i) {
        return i < 0 ? roots.length : childStart[i + 1] - childStart[i];
    }

    /** The deepest node that contains step, or -1. */
    public int innermost(int step) {
        int[] list = roots;
        int lo = 0, hi = list.length;
        int found = -1;
        while (true) {
            int pick = lastStartingAtOrBefore(list, lo, hi, step);
            if (pick < 0 || end[pick] <= step)
                return found;
            found = pick;
            list = childIdx;
            lo = childStart[pick];
            hi = childStart[pick + 1];
        }
    }

    private int lastStartingAtOrBefore(int[] list, int lo, int hi, int step) {
        int ans = -1;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (start[list[mid]] <= step) {
                ans = list[mid];
                lo = mid + 1;
            } else {
                hi = mid;
            }
        }
        return ans;
    }

    public int depth(int i) {
        int d = 0;
        while (i >= 0 && parent[i] >= 0) {
            i = parent[i];
            d++;
        }
        return d;
    }

    /** The run (root) that contains step, or -1. */
    public int rootAt(int step) {
        int r = lastStartingAtOrBefore(roots, 0, roots.length, step);
        return r >= 0 && end[r] > step ? r : -1;
    }

    /** Index of root node r in roots (sorted: nodes are numbered in start order). */
    private int rootIndex(int r) {
        return Arrays.binarySearch(roots, r);
    }

    /** The next run of the same thread after run r, or -1. */
    public int nextRunOfThread(int r) {
        for (int i = rootIndex(r) + 1; i < roots.length; i++)
            if (thread[roots[i]] == thread[r])
                return roots[i];
        return -1;
    }

    /** The previous run of the same thread before run r, or -1. */
    public int prevRunOfThread(int r) {
        for (int i = rootIndex(r) - 1; i >= 0; i--)
            if (thread[roots[i]] == thread[r])
                return roots[i];
        return -1;
    }

    /** The ancestor of node i (or i itself) at depth d, or -1. */
    public int ancestorAtDepth(int i, int d) {
        int k = depth(i);
        if (k < d)
            return -1;
        while (k > d) {
            i = parent[i];
            k--;
        }
        return i;
    }

    /**
     * The node where the frame of node i really started: i itself, or for
     * a CONTINUED node (reopened when its thread was switched back in),
     * the same frame in the thread's earlier runs. -1 if not found.
     */
    public int origin(int i) {
        int d = depth(i);
        while ((flags[i] & CONTINUED) != 0) {
            int prev = prevRunOfThread(ancestorAtDepth(i, 0));
            if (prev < 0 || end[prev] <= start[prev])
                return -1;
            int c = ancestorAtDepth(innermost(end[prev] - 1), d);
            if (c < 0 || kind[c] != kind[i] || !label(c).equals(label(i)))
                return -1;
            i = c;
        }
        return i;
    }

    /**
     * The last part of node i's frame: i itself, or, if it was switched
     * out (SUSPENDED), its continuation in the thread's last run that has
     * it. The mirror of origin().
     */
    public int lastPart(int i) {
        int d = depth(i);
        while ((flags[i] & SUSPENDED) != 0) {
            int run = nextRunOfThread(ancestorAtDepth(i, 0));
            if (run < 0)
                break;
            int c = ancestorAtDepth(innermost(start[run]), d);
            if (c < 0 || kind[c] != kind[i] || !label(c).equals(label(i)))
                break;
            i = c;
        }
        return i;
    }

    /** First INTR node starting after step, or -1. */
    public int nextIntr(int step) {
        int i = firstStartingAfter(step);
        for (; i < n; i++)
            if (kind[i] == INTR && (flags[i] & CONTINUED) == 0)
                return i;
        return -1;
    }

    /** Last INTR node starting before step, or -1. */
    public int prevIntr(int step) {
        int i = firstStartingAfter(step - 1) - 1;
        for (; i >= 0; i--)
            if (kind[i] == INTR && (flags[i] & CONTINUED) == 0 && start[i] < step)
                return i;
        return -1;
    }

    private int firstStartingAfter(int step) {
        int lo = 0, hi = n;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (start[mid] <= step)
                lo = mid + 1;
            else
                hi = mid;
        }
        return lo;
    }
}

package rxviz.index;

/**
 * Debugger-style moves over a recording. They follow one thread: when
 * it is switched out, the scan continues in that thread's next run, so
 * stepping over a call that sleeps lands where the call returns.
 *
 *   over       next step in this frame (or a caller), skipping calls/traps
 *   out        first step after the current frame returns
 *   line-into  first step on another source line
 *   line-over  like over, but also on another source line
 *   line-back  start of the previous source line in this thread
 */
public final class Stepper {

    /** Scans give up after this many steps (a long loop, or the end). */
    private static final int LIMIT = 20_000_000;

    private final Recording r;
    private final Tree t;
    private final int last;

    /* line keys of the chunk the scan is in */
    private int[] keys;
    private int keysChunk = -1;

    private int key(int step) {
        int c = step / ChunkStore.CHUNK;
        if (c != keysChunk) {
            keys = r.lineKeys(c);
            keysChunk = c;
        }
        return keys[step - c * ChunkStore.CHUNK];
    }

    public Stepper(Recording r) {
        this.r = r;
        this.t = r.tree();
        this.last = r.steps() - 1;
    }

    private int depthAt(int step) {
        int n = t.innermost(step);
        return n < 0 ? 0 : t.depth(n);
    }

    /**
     * First step after s, in s's thread, at depth <= maxDepth; with
     * byLine, at maxDepth itself it must also be on another line than s.
     */
    private int forward(int s, int maxDepth, boolean byLine) {
        int run = t.rootAt(s);
        if (run < 0)
            return Math.min(s + 1, last);
        int key0 = byLine ? key(s) : -1;
        int step = s + 1;
        for (int n = 0; n < LIMIT && step <= last; n++) {
            if (step >= t.end(run)) {
                run = t.nextRunOfThread(run);
                if (run < 0)
                    return last;
                step = t.start(run);
            }
            int m = t.innermost(step);
            int d = m < 0 ? 0 : t.depth(m);
            if (d > maxDepth) {
                /* inside a call or trap: jump to where that frame ends */
                int c = t.ancestorAtDepth(m, maxDepth + 1);
                step = Math.max(step + 1, t.end(c));
                continue;
            }
            if (d < maxDepth || !byLine)
                return step;
            int k = key(step);
            if (k >= 0 && k != key0)
                return step;
            step++;
        }
        return Math.min(step, last);
    }

    public int over(int s) {
        return forward(s, depthAt(s), false);
    }

    public int lineOver(int s) {
        return forward(s, depthAt(s), true);
    }

    public int out(int s) {
        int d = depthAt(s);
        return d <= 1 ? s : forward(s, d - 1, false);
    }

    public int lineInto(int s) {
        int run = t.rootAt(s);
        int key0 = key(s);
        int step = s + 1;
        for (int n = 0; n < LIMIT && step <= last; n++) {
            if (run >= 0 && step >= t.end(run)) {
                run = t.nextRunOfThread(run);
                if (run < 0)
                    return last;
                step = t.start(run);
            }
            int k = key(step);
            if (k >= 0 && k != key0)
                return step;
            step++;
        }
        return Math.min(step, last);
    }

    public int lineBack(int s) {
        int run = t.rootAt(s);
        int key0 = key(s);
        int step = s - 1;
        int k1 = -1;
        for (int n = 0; n < LIMIT && step >= 0; n++) {
            if (run >= 0 && step < t.start(run)) {
                run = t.prevRunOfThread(run);
                if (run < 0)
                    return Math.max(step + 1, 0);
                step = t.end(run) - 1;
            }
            int k = key(step);
            if (k1 < 0) {
                if (k >= 0 && k != key0)
                    k1 = k;
            } else if (k != k1) {
                return step + 1;
            }
            step--;
        }
        return Math.max(step + 1, 0);
    }
}

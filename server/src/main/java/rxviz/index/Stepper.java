package rxviz.index;

import java.util.Arrays;
import java.util.Set;
import java.util.function.IntPredicate;

import rxviz.elf.Addr;
import rxviz.elf.LineTable;

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
 *   loop-out   first step after the innermost loop around this step, in
 *              this frame (calls and traps in it are gone over)
 *   loop-back  last step of this frame before that loop was entered
 *   run-to     next (or previous) time a given source line starts, in any
 *              thread: the recording's own order of time
 *
 * With the step filter, line-into and line-back do not stop inside the
 * kernel's lock helpers (KernelProfile.stepSkip): a call to one is gone
 * over as a whole, unless the scan started inside it. Traps taken inside
 * one are still stopped in.
 */
public final class Stepper {

    /** Scans give up after this many steps (a long loop, or the end). */
    private static final int LIMIT = 20_000_000;

    private final Recording r;
    private final Tree t;
    private final int last;
    /* functions to skip, or null */
    private final Set<String> skip;

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
        this(r, false);
    }

    public Stepper(Recording r, boolean filter) {
        this.r = r;
        this.t = r.tree();
        this.last = r.steps() - 1;
        this.skip = filter ? r.kernel().stepSkip() : null;
    }

    /**
     * The outermost call of a skipped function around step (not counting
     * calls outside the innermost trap) that does not contain s; -1 if
     * none.
     */
    private int skipped(int step, int s) {
        if (skip == null)
            return -1;
        int found = -1;
        for (int n = t.innermost(step); n >= 0 && t.kind(n) == Tree.FUNC; n = t.parent(n))
            if (skip.contains(t.label(n)) && !(t.start(n) <= s && s < t.end(n)))
                found = n;
        return found;
    }

    private int depthAt(int step) {
        int n = t.innermost(step);
        return n < 0 ? 0 : t.depth(n);
    }

    /** First step after s, in s's thread, at depth < maxDepth, or at maxDepth where stop holds. */
    private int forward(int s, int maxDepth, IntPredicate stop) {
        int run = t.rootAt(s);
        if (run < 0)
            return Math.min(s + 1, last);
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
            if (d < maxDepth || stop.test(step))
                return step;
            step++;
        }
        return Math.min(step, last);
    }

    /**
     * Last step before s, in s's thread, at depth maxDepth where stop
     * holds; the first step of s's frame if the scan leaves it.
     */
    private int backward(int s, int maxDepth, IntPredicate stop) {
        int run = t.rootAt(s);
        int step = s - 1;
        for (int n = 0; n < LIMIT && step >= 0; n++) {
            if (run >= 0 && step < t.start(run)) {
                run = t.prevRunOfThread(run);
                if (run < 0)
                    return step + 1;
                step = t.end(run) - 1;
            }
            int m = t.innermost(step);
            int d = m < 0 ? 0 : t.depth(m);
            if (d > maxDepth) {
                int c = t.ancestorAtDepth(m, maxDepth + 1);
                step = Math.min(step - 1, t.start(c) - 1);
                continue;
            }
            if (d < maxDepth)
                return step + 1;
            if (stop.test(step))
                return step;
            step--;
        }
        return Math.max(step + 1, 0);
    }

    public int over(int s) {
        return forward(s, depthAt(s), x -> true);
    }

    public int lineOver(int s) {
        int key0 = key(s);
        return forward(s, depthAt(s), x -> {
            int k = key(x);
            return k >= 0 && k != key0;
        });
    }

    public int out(int s) {
        int d = depthAt(s);
        return d <= 1 ? s : forward(s, d - 1, x -> true);
    }

    /** loop-out: -1 if s is not in a loop. */
    public int loopOut(int s) {
        long[] loop = r.loopAround(s);
        if (loop == null)
            return -1;
        return forward(s, depthAt(s), x -> !inside(x, loop));
    }

    /** loop-back: -1 if s is not in a loop. */
    public int loopBack(int s) {
        long[] loop = r.loopAround(s);
        if (loop == null)
            return -1;
        return backward(s, depthAt(s), x -> !inside(x, loop));
    }

    private boolean inside(int step, long[] loop) {
        return Addr.in(r.insn(step).vaddr(), loop[0], loop[1] - loop[0] + 1);
    }

    public int runTo(int s, String file, int line, boolean back) {
        /* per line key: 0 unknown, 1 the target line, 2 another line (the
         * whole recording may be scanned: boot, about 15 s at worst) */
        byte[][] known = {new byte[64]};
        IntPredicate on = step -> {
            int k = key(step);
            if (k < 0)
                return false;
            if (k >= known[0].length)
                known[0] = Arrays.copyOf(known[0], Math.max(k + 1, known[0].length * 2));
            if (known[0][k] == 0) {
                LineTable.Src src = r.lineOfKey(k);
                known[0][k] = (byte) (src != null && src.line() == line && src.file().equals(file) ? 1 : 2);
            }
            return known[0][k] == 1;
        };
        if (!back) {
            boolean prev = on.test(s);
            for (int step = s + 1; step <= last; step++) {
                boolean hit = on.test(step);
                if (hit && !prev)
                    return step;
                prev = hit;
            }
            return -1;
        }
        int step = s;
        while (step >= 0 && on.test(step))    /* the run of it s is in */
            step--;
        while (step >= 0 && !on.test(step))   /* back to the one before */
            step--;
        if (step < 0)
            return -1;
        while (step > 0 && on.test(step - 1)) /* to its first step */
            step--;
        return step;
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
            int f = skipped(step, s);
            if (f >= 0) {
                step = Math.max(step + 1, t.end(f));
                continue;
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
            int f = skipped(step, s);
            if (f >= 0) {
                step = Math.min(step - 1, t.start(f) - 1);
                continue;
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

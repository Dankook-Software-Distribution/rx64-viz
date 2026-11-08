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
 *              and the rest of a rep string instruction; a call that
 *              never returns in the recording is stepped into
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
 * With the step filter, line-into does not stop inside the kernel's
 * lock helpers (KernelProfile.stepSkip): a call to one is gone over as a
 * whole, unless the scan started inside it. Traps taken inside one are
 * still stopped in. Trap stubs and swtch are not skipped: stepping into
 * a system call stops on vectors.S, alltraps, and so on, the way the CPU
 * goes. line-back is line-over backwards: calls and traps are gone over
 * as a whole, and from a function's first line it goes to its caller's.
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

    /** forward(): the thread never gets there in the recording (a call that does not return). */
    private static final int NEVER = -2;

    /**
     * First step after s, in s's thread, at depth < maxDepth, or at
     * maxDepth where stop holds; NEVER if the recording (or the scan
     * limit) ends first.
     */
    private int forward(int s, int maxDepth, IntPredicate stop) {
        int run = t.rootAt(s);
        if (run < 0)
            return Math.min(s + 1, last);
        int step = s + 1;
        for (int n = 0; n < LIMIT && step <= last; n++) {
            if (step >= t.end(run)) {
                run = t.nextRunOfThread(run);
                if (run < 0)
                    return NEVER;
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
        return NEVER;
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

    /**
     * Over one instruction; a rep-prefixed string instruction (rep stosb)
     * as a whole, though the CPU runs, and the trace records, each of its
     * iterations as a step. A call that never returns in the recording
     * (call main) is stepped into instead.
     */
    public int over(int s) {
        long pc = r.insn(s).vaddr();
        boolean rep = repString(r.insn(s).bytes());
        int to = forward(s, depthAt(s), x -> !rep || r.insn(x).vaddr() != pc);
        return to == NEVER ? Math.min(s + 1, last) : to;
    }

    /**
     * A step that starts another line after one on line key0. Code with no
     * line (riscv-style's trampoline) is a line per instruction, so line
     * stepping walks through it instead of jumping over it.
     */
    private static boolean newLine(int key0, int k) {
        return k != key0 || k < 0;
    }

    /** Over a source line; a call in it that never returns is stepped into. */
    public int lineOver(int s) {
        int key0 = key(s);
        int to = forward(s, depthAt(s), x -> newLine(key0, key(x)));
        return to == NEVER ? lineInto(s) : to;
    }

    /** -1 if the current function never returns in the recording. */
    public int out(int s) {
        int d = depthAt(s);
        if (d <= 1)
            return -1; /* the thread's outermost frame: nothing to return to */
        int to = forward(s, d - 1, x -> true);
        return to == NEVER ? -1 : to;
    }

    /** loop-out: -1 if s is not in a loop (or the loop never ends in the recording). */
    public int loopOut(int s) {
        long[] loop = r.loopAround(s);
        if (loop == null)
            return -1;
        int to = forward(s, depthAt(s), x -> !inside(x, loop));
        return to == NEVER ? -1 : to;
    }

    /** rep / repne + a string instruction (movs, cmps, stos, lods, scas). */
    static boolean repString(byte[] b) {
        int i = InsnKind.prefixEnd(b);
        boolean rep = false;
        for (int k = 0; k < i; k++)
            rep |= (b[k] & 0xff) == 0xf2 || (b[k] & 0xff) == 0xf3;
        if (i < b.length && (b[i] & 0xf0) == 0x40)
            i++; /* REX */
        if (!rep || i >= b.length)
            return false;
        int op = b[i] & 0xff;
        return op >= 0xa4 && op <= 0xaf && op != 0xa8 && op != 0xa9;
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

    /** First step after s on another line (filtered: not inside a skipped helper). */
    public int lineInto(int s) {
        int key0 = key(s);
        int run = t.rootAt(s);
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
            if (newLine(key0, key(step)))
                return step;
            step++;
        }
        return Math.min(step, last);
    }

    /**
     * The start of the previous source line in this frame, going over calls
     * and traps as wholes (line-over backwards); from a function's first
     * line, the start of the caller's line that called it.
     */
    public int lineBack(int s) {
        int run = t.rootAt(s);
        int d = depthAt(s);
        int key0 = key(s);
        int step = s - 1;
        int k1 = -1, at = -1;
        for (int n = 0; n < LIMIT && step >= 0; n++) {
            if (run >= 0 && step < t.start(run)) {
                run = t.prevRunOfThread(run);
                if (run < 0)
                    break;
                step = t.end(run) - 1;
            }
            int m = t.innermost(step);
            int dd = m < 0 ? 0 : t.depth(m);
            if (dd > d) {
                /* inside a call or trap: back over all of it */
                int c = t.ancestorAtDepth(m, d + 1);
                step = Math.min(step - 1, t.start(c) - 1);
                continue;
            }
            if (dd < d) {
                if (k1 >= 0)
                    return at;
                /* left the function from its first line: the caller's line */
                d = dd;
                k1 = key(step);
                at = step;
                if (k1 < 0)
                    return at; /* a caller with no line: its instruction */
                step--;
                continue;
            }
            int k = key(step);
            if (k < 0) {
                /* code with no line: one instruction is one line */
                return k1 < 0 ? step : at;
            }
            if (key0 < 0) {
                /* from such code back into a line: found it, now its start */
                k1 = k;
                at = step;
                key0 = k;
            } else {
                if (k1 < 0) {
                    if (k != key0) {
                        k1 = k;
                        at = step;
                    }
                } else if (k == k1) {
                    at = step;
                } else {
                    return at;
                }
            }
            step--;
        }
        return at >= 0 ? at : Math.max(step + 1, 0);
    }
}

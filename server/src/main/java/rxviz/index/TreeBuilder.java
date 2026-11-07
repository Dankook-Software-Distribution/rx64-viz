package rxviz.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rxviz.config.KernelProfile;

/**
 * Builds the {@link Tree} while the indexer walks the steps in order.
 *
 * Threads. Every step runs on some stack: a process's kernel stack
 * (known from KSTACK(p)), a user address space (CR3 while in user mode,
 * or on riscv-style's TRAPFRAME page), or another kernel stack (the
 * scheduler's). Moving from one to another is a context switch when it
 * happens inside swtch(); any other move (a trap, iretq, exec's new
 * address space) stays in the same thread, so those keys are merged.
 *
 * Frames. A call opens a FUNC node at the callee's first step; ret
 * closes it. An interrupt/exception entering a trap stub opens an INTR
 * node; iretq/sysret closes it. swtch() leaves with an indirect jmp,
 * which closes swtch's frame.
 */
final class TreeBuilder {

    private static final Pattern STUB = Pattern.compile("^u?vec(?:tor)?(\\d+)$");

    private static final class Thr {
        int union = -1;          /* merged into this thread, or -1 */
        final List<Integer> frames = new ArrayList<>();
        int slot = -1;
        boolean scheduler;
        String program;
    }

    private final KernelProfile kernel;
    private final Tree t = new Tree();
    private final List<Thr> threads = new ArrayList<>();
    private final Map<Long, Integer> keyThread = new HashMap<>();
    private final List<String> runProgram = new ArrayList<>(); /* by node id */
    private final List<String> runFirstProgram = new ArrayList<>();
    private final Set<Integer> bootRuns = new HashSet<>();

    private int cur = -1;
    private int run = -1;
    private long prevKey;
    private String prevFn = "";
    private boolean pendingCall;

    /* counters for the summary */
    int syscalls, interrupts, exceptions, switches;

    TreeBuilder(KernelProfile kernel) {
        this.kernel = kernel;
    }

    private int find(int i) {
        while (threads.get(i).union >= 0)
            i = threads.get(i).union;
        return i;
    }

    private int newThread() {
        threads.add(new Thr());
        return threads.size() - 1;
    }

    private long key(int cpl, long rsp, long cr3) {
        int slot = kernel.kstackSlot(rsp);
        if (slot >= 0)
            return (1L << 60) | slot;
        if (cpl == 3 || kernel.isUserTrapStack(rsp))
            return (2L << 60) | (cr3 >>> 12);
        return (3L << 60) | ((rsp - 1) >>> 12);
    }

    private void describeKey(int thr, long key) {
        Thr th = threads.get(thr);
        long type = key >>> 60;
        if (type == 1 && th.slot < 0)
            th.slot = (int) (key & 0xffff);
    }

    /**
     * One step. fn is the kernel function at pc ("" in user mode),
     * userFn the user function (or null), discTo the target of the
     * interrupt/exception reported just before this step (or -1).
     */
    void step(int i, long pc, InsnKind kind, int cpl, long rsp, long cr3, long rax,
              long cr2, String fn, String userFn, String program, long discTo) {
        long key = key(cpl, rsp, cr3);

        if (cur < 0) {
            cur = keyThread.computeIfAbsent(key, k -> newThread());
            describeKey(cur, key);
            startRun(i);
        } else if (key != prevKey) {
            boolean sw = "swtch".equals(fn) || "swtch".equals(prevFn);
            Integer known = keyThread.get(key);
            int to;
            if (known == null) {
                to = sw ? newThread() : cur;
                keyThread.put(key, to);
            } else {
                to = find(known);
                if (to != cur && !sw) {
                    /* same process seen through another key: whatever that
                     * thread still had open is from an earlier life */
                    threads.get(to).frames.clear();
                    threads.get(to).union = cur;
                    to = cur;
                }
            }
            describeKey(to, key);
            if (to != cur) {
                switches++;
                switchTo(i, to);
            }
        }
        prevKey = key;
        if ("scheduler".equals(fn))
            threads.get(cur).scheduler = true;
        if ("main".equals(fn))
            bootRuns.add(run);
        if (program != null) {
            Thr th = threads.get(cur);
            if (th.program != null && !th.program.equals(program)) {
                /* exec replaced the program: its frames never return */
                for (int node : th.frames)
                    t.end[node] = i;
                th.frames.clear();
                pendingCall = false;
            }
            th.program = program;
            runProgram.set(run, program);
            if (runFirstProgram.get(run) == null)
                runFirstProgram.set(run, program);
        }

        /* a new frame starting at this step */
        int vector = discTo == pc ? stubVector(fn) : -1;
        if (vector >= 0) {
            pendingCall = false;
            String lab;
            if (kernel.isSyscallVector(vector)) {
                lab = "syscall " + kernel.syscallName(rax);
                syscalls++;
            } else if (vector == 14) {
                lab = "page fault 0x" + Long.toHexString(cr2);
                exceptions++;
            } else {
                lab = kernel.vectorName(vector);
                if (vector >= 32)
                    interrupts++;
                else
                    exceptions++;
            }
            int node = open(Tree.INTR, i, lab);
            t.vector[node] = vector;
        } else if (pendingCall) {
            pendingCall = false;
            String lab = cpl == 3 ? (userFn != null ? userFn : "?") : (fn.isEmpty() ? "?" : fn);
            open(Tree.FUNC, i, lab);
        }

        /* what this instruction does to the frames */
        switch (kind) {
            case CALL -> pendingCall = true;
            case RET -> closeFunc(i + 1);
            case IRET, SYSRET -> closeIntr(i + 1);
            case JMP_INDIRECT -> {
                if ("swtch".equals(fn))
                    closeFunc(i + 1);
            }
            default -> { }
        }
        prevFn = fn;
    }

    private int stubVector(String fn) {
        Matcher m = STUB.matcher(fn);
        return m.matches() ? Integer.parseInt(m.group(1)) : -1;
    }

    private int open(byte kind, int i, String lab) {
        List<Integer> fr = threads.get(cur).frames;
        int par = fr.isEmpty() ? run : fr.getLast();
        int node = t.add(kind, par, i, cur, lab);
        fr.add(node);
        return node;
    }

    private void closeFunc(int endStep) {
        List<Integer> fr = threads.get(cur).frames;
        if (!fr.isEmpty() && t.kind[fr.getLast()] == Tree.FUNC)
            t.end[fr.removeLast()] = endStep;
    }

    private void closeIntr(int endStep) {
        List<Integer> fr = threads.get(cur).frames;
        int at = -1;
        for (int j = fr.size() - 1; j >= 0; j--)
            if (t.kind[fr.get(j)] == Tree.INTR) {
                at = j;
                break;
            }
        if (at < 0)
            return;
        while (fr.size() > at)
            t.end[fr.removeLast()] = endStep;
    }

    private void startRun(int i) {
        run = t.add(Tree.RUN, -1, i, cur, null);
        while (runProgram.size() < t.n) {
            runProgram.add(null);
            runFirstProgram.add(null);
        }
        runProgram.set(run, threads.get(cur).program);
        runFirstProgram.set(run, threads.get(cur).program);
        /* reopen the frames this thread left open when it was switched out */
        List<Integer> fr = threads.get(cur).frames;
        int par = run;
        for (int j = 0; j < fr.size(); j++) {
            int old = fr.get(j);
            int node = t.add(t.kind[old], par, i, cur, t.label.get(old));
            t.vector[node] = t.vector[old];
            t.flags[node] |= Tree.CONTINUED;
            fr.set(j, node);
            par = node;
        }
    }

    private void endRun(int i) {
        for (int node : threads.get(cur).frames) {
            t.end[node] = i;
            t.flags[node] |= Tree.SUSPENDED;
        }
        t.end[run] = i;
    }

    private void switchTo(int i, int to) {
        endRun(i);
        cur = to;
        startRun(i);
    }

    Tree finish(int nsteps) {
        if (cur >= 0) {
            for (int node : threads.get(cur).frames)
                t.end[node] = nsteps;
            threads.get(cur).frames.clear();
            t.end[run] = nsteps;
        }
        /* any node left open belongs to a thread that never came back */
        for (int i = 0; i < t.n; i++)
            if (t.end[i] < 0)
                t.end[i] = nsteps;
        while (runProgram.size() < t.n) {
            runProgram.add(null);
            runFirstProgram.add(null);
        }
        /* merged threads: a slot seen through any member names the whole */
        int[] slotOf = new int[threads.size()];
        Arrays.fill(slotOf, -1);
        for (int j = 0; j < threads.size(); j++)
            if (threads.get(j).slot >= 0 && slotOf[find(j)] < 0)
                slotOf[find(j)] = threads.get(j).slot;
        for (int i = 0; i < t.n; i++)
            if (t.kind[i] == Tree.RUN)
                t.label.set(i, runLabel(i, slotOf));
        /* merged threads: every node names its final thread */
        for (int i = 0; i < t.n; i++)
            t.thread[i] = find(t.thread[i]);
        t.finish();
        return t;
    }

    private String runLabel(int node, int[] slotOf) {
        int root = find(t.thread[node]);
        Thr th = threads.get(root);
        String prog = runProgram.get(node);
        if (prog == null)
            prog = th.program;
        String first = runFirstProgram.get(node);
        if (first != null && prog != null && !first.equals(prog))
            prog = first + "\u2192" + prog;   /* exec in this run: sh→echo */
        if (slotOf[root] >= 0)
            return "proc[" + slotOf[root] + "]" + (prog != null ? " " + prog : "");
        if (prog != null)
            return prog;
        if (bootRuns.contains(node))
            return "boot";
        return th.scheduler ? "scheduler" : "kernel";
    }
}

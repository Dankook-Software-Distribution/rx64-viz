package rxviz.index;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rxviz.config.KernelProfile;
import rxviz.elf.Structs;

/**
 * Builds the {@link Tree} while the indexer walks the steps in order.
 *
 * Threads. A thread is a kernel context: what swtch(old, new) saves into
 * old and resumes from new (proc[k].context for process k, cpus[c].context
 * for CPU c's scheduler). The running thread changes only inside swtch(),
 * at the instruction that loads new's stack pointer; everything else (a
 * trap, iretq, exec's new address space) stays in the same thread. A
 * context whose saved pc is a function's first instruction (forkret,
 * set by allocproc) never ran: it starts a new thread even if its proc
 * slot had one before (an exited process's). The thread running when the
 * recording begins takes its slot from the kernel stack it is on.
 *
 * Frames. A call opens a FUNC node at the callee's first step; ret
 * closes it. An interrupt/exception entering a trap stub opens an INTR
 * node; iretq/sysret closes it. swtch() leaves with an indirect jmp,
 * which closes swtch's frame.
 */
final class TreeBuilder {

    private static final Pattern STUB = Pattern.compile("^u?vec(?:tor)?(\\d+)$");

    private static final class Thr {
        final List<Integer> frames = new ArrayList<>();
        /* how many frames were open when it last returned to user mode */
        int userBase;
        int slot = -1;
        int pid = -1;
        /* its user frames were looked for on the user stack once */
        boolean userFound;
        boolean scheduler;
        String program;
    }

    private final KernelProfile kernel;
    private final MemoryImage image;
    private final Tree t = new Tree();
    private final List<Thr> threads = new ArrayList<>();
    /* kernel context address -> the thread that saves into and resumes from it */
    private final Map<Long, Integer> contextThread = new HashMap<>();
    private final List<String> runProgram = new ArrayList<>(); /* by node id */
    private final List<String> runFirstProgram = new ArrayList<>();
    private final Set<Integer> bootRuns = new HashSet<>();
    /* where the process table's and the CPUs' contexts are */
    private final Table procTable, cpuTable;
    /* struct context: offsets of the saved pc and stack pointer; proc's pid */
    private final long ctxPc, ctxSp, pidOff;

    private int cur = -1;
    private int run = -1;
    private String prevFn = "";
    private int prevCpl = -1;
    private boolean pendingCall;
    private boolean afterSwtch;
    /* the swtch() under way: the thread it goes to (-1: switched already
     * or none), new's saved stack pointer (null: unreadable), and whether
     * new never ran */
    private int swTo = -1;
    private Long swSp;
    private boolean swFresh;
    /* user-mode frames opened before their program was recognized: node -> pc */
    private final Map<Integer, Long> unnamed = new HashMap<>();
    /* a frame reopened in a later run (CONTINUED) -> the node it continues */
    private final Map<Integer, Integer> continues = new HashMap<>();

    /* counters for the summary */
    int syscalls, interrupts, exceptions, switches;

    /** A global array of structs holding a struct context: proc[], cpus[]. */
    private record Table(long addr, long count, long size, long context) {

        /** The element whose context is at va, or -1. */
        int index(long va) {
            long off = va - addr;
            if (off < 0 || off >= count * size || off % size != context)
                return -1;
            return (int) (off / size);
        }
    }

    TreeBuilder(KernelProfile kernel, MemoryImage image) {
        this.kernel = kernel;
        this.image = image;
        procTable = table("proc");
        cpuTable = table("cpus");
        Structs.Struct ctx = kernel.structs().get("context");
        ctxPc = offset(ctx, "rip", "ra");   /* x86-style's names, riscv-style's */
        ctxSp = offset(ctx, "rsp", "sp");
        pidOff = offset(kernel.structs().get("proc"), "pid");
    }

    private static long offset(Structs.Struct s, String... names) {
        for (String n : names) {
            Structs.Member m = s == null ? null : s.member(n);
            if (m != null)
                return m.offset();
        }
        return -1;
    }

    private Table table(String var) {
        long addr = kernel.kernelElf().addressOf(var);
        Structs.Struct el = kernel.structs().elementOf(var);
        long ctx = offset(el, "context");
        if (addr == -1 || ctx < 0 || el.size() <= 0)
            return null;
        return new Table(addr, kernel.kernelElf().sizeOf(var) / el.size(), el.size(), ctx);
    }

    private int newThread() {
        threads.add(new Thr());
        return threads.size() - 1;
    }

    /** A thread for the context at va: a process's (its slot and pid) or a CPU's scheduler. */
    private int threadFor(long va, long cr3) {
        int to = newThread();
        Thr th = threads.get(to);
        int k = procTable == null ? -1 : procTable.index(va);
        if (k >= 0)
            bindSlot(th, k, cr3);
        else if (cpuTable != null && cpuTable.index(va) >= 0)
            th.scheduler = true;
        contextThread.put(va, to);
        return to;
    }

    /** th is process slot k: its pid is proc[k].pid now (a pid stays for a process's life). */
    private void bindSlot(Thr th, int k, long cr3) {
        th.slot = k;
        if (pidOff >= 0) {
            Long pid = image.virtual(cr3, true).read(procTable.addr() + k * procTable.size() + pidOff, 4);
            th.pid = pid == null ? -1 : pid.intValue();
        }
    }

    /**
     * One step. fn is the kernel function at pc ("" in user mode),
     * userFn the user function (or null), discTo the target of the
     * interrupt/exception reported just before this step (or -1); rdi
     * and rsi are swtch()'s old and new contexts on its first step.
     */
    void step(int i, long pc, InsnKind kind, int cpl, long rsp, long rbp, long cr3, long rax,
              long rdi, long rsi, long cr2, String fn, String userFn, String program, long discTo) {
        boolean inSwtch = "swtch".equals(fn);
        if (cur < 0) {
            cur = newThread();
            startRun(i);
            if (cpl == 0 && !fn.isEmpty() && discTo != pc && kernel.isC(pc))
                earlier(i, pc, fn, rsp, rbp, cr3);
        }
        if (inSwtch && !"swtch".equals(prevFn))
            swtchEntered(rdi, rsi, cr3);
        /* the switch: new's stack pointer is loaded (at the latest, its jmp) */
        if (swTo >= 0 && inSwtch && ((swSp != null && rsp == swSp) || kind == InsnKind.JMP_INDIRECT)) {
            if (swTo != cur) {
                switches++;
                switchTo(i, swTo);
            }
            swTo = -1;
        }
        Thr running = threads.get(cur);
        /* the thread the recording began with: its slot from its kernel stack */
        if (running.slot < 0 && !running.scheduler && cpl == 0 && !inSwtch) {
            int k = kernel.kstackSlot(rsp);
            if (k >= 0 && procTable != null) {
                bindSlot(running, k, cr3);
                contextThread.putIfAbsent(procTable.addr() + k * procTable.size() + procTable.context(), cur);
            }
        }
        if (cpl == 3 && prevCpl == 0)
            running.userBase = running.frames.size();
        prevCpl = cpl;
        if ("scheduler".equals(fn))
            running.scheduler = true;
        if ("main".equals(fn))
            bootRuns.add(run);
        if (program != null) {
            Thr th = running;
            if (th.program != null && !th.program.equals(program)) {
                /* exec replaced the program: the frames open when exec
                 * returned to user mode never return; the new program's
                 * (opened before it was recognized: its main) stay */
                int keep = Math.min(th.userBase, th.frames.size());
                for (int k = 0; k < keep; k++)
                    t.end[th.frames.get(k)] = i;
                th.frames.subList(0, keep).clear();
                if (!th.frames.isEmpty())
                    t.parent[th.frames.getFirst()] = run;
            }
            th.program = program;
            runProgram.set(run, program);
            if (runFirstProgram.get(run) == null)
                runFirstProgram.set(run, program);
        }

        if (afterSwtch) {
            afterSwtch = false;
            resumed(i, pc, fn, rsp, rbp, cr3);
        }
        /* in user mode with no frame open (a forked child back from fork(),
         * a process first seen in the kernel): its frames from its stack */
        if (cpl == 3 && userFn != null && program != null && !running.userFound && running.frames.isEmpty()
                && discTo != pc) {
            running.userFound = true;
            userFrames(i, pc, userFn, rsp, rbp, cr3, kernel.program(program));
        }

        /* a new frame starting at this step */
        int vector = discTo == pc ? stubVector(fn) : -1;
        if (vector >= 0) {
            pendingCall = false;
            openTrap(i, vector, kernel.syscallName(rax), vector == 14 ? cr2 : null);
        } else if (pendingCall) {
            pendingCall = false;
            String lab = cpl == 3 ? (userFn != null ? userFn : "?") : (fn.isEmpty() ? "?" : fn);
            int node = open(Tree.FUNC, i, lab);
            if (cpl == 3 && userFn == null)
                unnamed.put(node, pc); /* the program is not known yet: name it at the end */
        }

        /* what this instruction does to the frames */
        switch (kind) {
            case CALL -> pendingCall = true;
            case RET -> closeFunc(i + 1);
            case IRET, SYSRET -> closeIntr(i + 1);
            case JMP_INDIRECT -> {
                if ("swtch".equals(fn)) {
                    closeFunc(i + 1);
                    afterSwtch = true;
                }
            }
            default -> { }
        }
        prevFn = fn;
    }

    /**
     * swtch(old, new) begins: old's context is the running thread's; new's
     * names the thread to resume. A saved pc at a function's first
     * instruction (forkret) is a context that never ran: a new thread.
     */
    private void swtchEntered(long old, long nw, long cr3) {
        contextThread.put(old, cur);
        var vm = image.virtual(cr3, true);
        Long pc = ctxPc < 0 ? null : vm.read(nw + ctxPc, 8);
        swSp = ctxSp < 0 ? null : vm.read(nw + ctxSp, 8);
        swFresh = pc != null && kernel.locate(pc, false, null).offset() == 0;
        Integer known = contextThread.get(nw);
        swTo = known == null || swFresh ? threadFor(nw, cr3) : known;
    }

    /**
     * The first step after swtch's jmp. A thread resuming lands in the
     * function that called swtch (sched, scheduler), the innermost frame
     * it left open. A new one lands on forkret's first instruction. One
     * first seen now was switched out before the recording began.
     */
    private void resumed(int i, long pc, String fn, long rsp, long rbp, long cr3) {
        if (!threads.get(cur).frames.isEmpty())
            return;
        if (swFresh)
            open(Tree.FUNC, i, fn.isEmpty() ? "?" : fn);
        else if (kernel.isC(pc))
            earlier(i, pc, fn, rsp, rbp, cr3);
    }

    /**
     * Frames a kernel thread opened before the recording began, at its
     * first step: the function running now and its callers from the
     * saved-rbp chain (the kernels keep frame pointers), up to the trap
     * that brought it into the kernel. That trap's vector is the trap
     * frame's trapno where the frame sits above trap()'s return address
     * (x86-style); otherwise a chain through a sys_* function is a system
     * call. The nodes start at this step, flagged EARLIER.
     */
    private void earlier(int i, long pc, String fn, long rsp, long rbp, long cr3) {
        var area = kernel.stackOf(rsp, false);
        var vm = image.virtual(cr3, true);
        Chain c = chain(vm, rbp, area.base(), area.top() - 15, false, null);
        List<String> chain = c.functions();
        List<Long> slots = c.slots(), rets = c.rets();
        /* a return into a trap stub (trapret, kernelvec) is where the trap
         * began: inside it, the handler's calls; outside, the callers of
         * the code it interrupted */
        int stub = -1;
        for (int k = 0; k < chain.size() && stub < 0; k++)
            if (!kernel.isC(rets.get(k) - 1))
                stub = k;
        List<String> inner = stub < 0 ? chain : chain.subList(0, stub);
        List<String> outer = stub < 0 ? List.of() : new ArrayList<>(chain.subList(stub + 1, chain.size()));
        int vector = -1;
        String sys = null;
        if (stub >= 0) {
            for (String f : inner)
                if (f.startsWith("sys_"))
                    sys = f.substring(4);
            /* trap(tf) is called with tf = rsp: the frame is right above
             * the stub's return address (x86-style); trust it only if cs
             * looks like a selector */
            long tf = slots.get(stub) + 8;
            Structs.Struct ts = kernel.structs().get("trapframe");
            Structs.Member tn = ts == null ? null : ts.member("trapno");
            Structs.Member tcs = ts == null ? null : ts.member("cs");
            Structs.Member trip = ts == null ? null : ts.member("rip");
            if (tn != null && tcs != null) {
                Long v = vm.read(tf + tn.offset(), 8);
                Long cs = vm.read(tf + tcs.offset(), 8);
                if (v != null && cs != null && v >= 0 && v < 256 && cs > 3 && cs < 0x100
                        && ((cs & 3) == 0 || (cs & 3) == 3)) {
                    vector = v.intValue();
                    /* a trap from the kernel: the function it interrupted */
                    Long rip = trip == null ? null : vm.read(tf + trip.offset(), 8);
                    String at = rip == null || (cs & 3) != 0 ? null : kernel.function(rip, false, null);
                    if (at != null)
                        outer.addFirst(at);
                }
            }
            if (vector < 0 && sys != null)
                vector = kernel.syscallVector();
        }
        List<Integer> fr = threads.get(cur).frames;
        int depth = fr.size();
        for (int k = outer.size() - 1; k >= 0; k--)
            open(Tree.FUNC, i, outer.get(k));
        if (vector >= 0)
            openTrap(i, vector, sys != null ? sys : "?", null);
        openEarlier(i, inner, fn, depth);
    }

    /** An INTR node for a trap through vector, counted: sys names a system call, cr2 (or null) a page fault's address. */
    private void openTrap(int i, int vector, String sys, Long cr2) {
        String lab = switch (kernel.trapKind(vector)) {
            case SYSCALL -> {
                syscalls++;
                yield "syscall " + sys;
            }
            case EXCEPTION -> {
                exceptions++;
                yield cr2 != null ? "page fault 0x" + Long.toHexString(cr2) : kernel.vectorName(vector);
            }
            case INTERRUPT -> {
                interrupts++;
                yield kernel.vectorName(vector);
            }
        };
        t.vector[open(Tree.INTR, i, lab)] = vector;
    }

    /** Callers up a saved-rbp chain, innermost first: their names, return-address slots and return addresses. */
    private record Chain(List<String> functions, List<Long> slots, List<Long> rets) {
    }

    /** The saved-rbp chain from fp while frames stay in [lo, hi) and step upwards; at most 64. */
    private Chain chain(MemoryImage.Virtual vm, long fp, long lo, long hi, boolean user,
                        KernelProfile.Program prog) {
        Chain c = new Chain(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (int k = 0; k < 64; k++) {
            if ((fp & 7) != 0 || Long.compareUnsigned(fp, lo) < 0 || Long.compareUnsigned(fp, hi) >= 0)
                break;
            Long ret = vm.read(fp + 8, 8), next = vm.read(fp, 8);
            if (ret == null || next == null || !kernel.isReturnAddress(ret, user, prog))
                break;
            String f = kernel.callerOf(ret, user, prog);
            if (f == null)
                break;
            c.functions().add(f);
            c.slots().add(fp + 8);
            c.rets().add(ret);
            if (Long.compareUnsigned(next, fp) <= 0)
                break;
            fp = next;
        }
        return c;
    }

    /** Open callers (innermost first) and then fn, flagged EARLIER: frames found on the stack. */
    private void openEarlier(int i, List<String> callers, String fn, int depth) {
        for (int k = callers.size() - 1; k >= 0; k--)
            open(Tree.FUNC, i, callers.get(k));
        open(Tree.FUNC, i, fn);
        List<Integer> fr = threads.get(cur).frames;
        for (int k = depth; k < fr.size(); k++)
            t.flags[fr.get(k)] |= Tree.EARLIER;
    }

    /**
     * The user functions open at step i: the one at pc and its callers
     * from the saved-rbp chain on the user stack. They began before this
     * thread was seen in user mode (a forked child's main() was entered by
     * its parent): flagged EARLIER, like earlier()'s.
     */
    private void userFrames(int i, long pc, String fn, long rsp, long rbp, long cr3, KernelProfile.Program prog) {
        if (prog == null)
            return;
        Chain c = chain(image.virtual(cr3, true), rbp, rsp, rsp + (1 << 16), true, prog);
        openEarlier(i, c.functions(), fn, threads.get(cur).frames.size());
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
        /* no trap open: a new process's first way out to user mode
         * (forkret ... userret): none of its kernel frames return */
        if (at < 0)
            at = 0;
        while (fr.size() > at)
            t.end[fr.removeLast()] = endStep;
    }

    /* runProgram and runFirstProgram are by node id: one entry for every node so far */
    private void padRuns() {
        while (runProgram.size() < t.n) {
            runProgram.add(null);
            runFirstProgram.add(null);
        }
    }

    private void startRun(int i) {
        run = t.add(Tree.RUN, -1, i, cur, null);
        padRuns();
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
            continues.put(node, old);
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
        padRuns();
        t.threadSlot = new int[threads.size()];
        t.threadPid = new int[threads.size()];
        for (int k = 0; k < threads.size(); k++) {
            t.threadSlot[k] = threads.get(k).slot;
            t.threadPid[k] = threads.get(k).pid;
        }
        for (int i = 0; i < t.n; i++)
            if (t.kind[i] == Tree.RUN)
                t.label.set(i, runLabel(i));
        /* user frames opened before the program was recognized: the run's program */
        for (var e : unnamed.entrySet()) {
            int run = e.getKey();
            while (run >= 0 && t.parent[run] >= 0)
                run = t.parent[run];
            String prog = run < 0 ? null : runProgram.get(run);
            KernelProfile.Program p = prog == null ? null : kernel.program(prog);
            String f = p == null ? null : kernel.function(e.getValue(), true, p);
            if (f != null)
                t.label.set(e.getKey(), f);
        }
        /* a renamed frame's continuations in later runs: the same name
         * (nodes are made in order, so the one continued is named first) */
        for (int i = 0; i < t.n; i++) {
            Integer from = continues.get(i);
            if (from != null)
                t.label.set(i, t.label.get(from));
        }
        t.finish();
        return t;
    }

    private String runLabel(int node) {
        Thr th = threads.get(t.thread[node]);
        String prog = runProgram.get(node);
        if (prog == null)
            prog = th.program;
        String first = runFirstProgram.get(node);
        if (first != null && prog != null && !first.equals(prog))
            prog = first + "\u2192" + prog;   /* exec in this run: sh→echo */
        if (th.slot >= 0)
            return t.processOf(node) + (prog != null ? " " + prog : "");
        if (prog != null)
            return prog;
        if (bootRuns.contains(node))
            return "boot";
        return th.scheduler ? "scheduler" : "kernel";
    }
}

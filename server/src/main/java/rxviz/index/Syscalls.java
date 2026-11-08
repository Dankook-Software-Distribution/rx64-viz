package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Program;
import rxviz.config.KernelProfile.Proto;

/**
 * The system calls and exceptions of a recording, in the order they
 * started, written the way strace writes them: write(1, "hi", 2) = 2.
 *
 * Arguments are the registers at the trap stub's first step (argraw()'s
 * registers, application.yml), typed by the prototype in user/user.h;
 * strings and arrays are read from user memory then. A pointer the
 * kernel writes through (read's buffer, pipe's fds) shows what the
 * kernel stored there during the call, from the recorded stores; that
 * scan is the costly part, so it runs only when the list is asked for.
 * The result is rax when the call returns to user mode.
 */
public final class Syscalls {

    /**
     * One call or exception. end: where its trap ended; returned: it came
     * back to user mode then; resumeStep: where it went on after sleeping
     * (-1 if it never slept); output: what the kernel wrote through a
     * pointer argument, or for an exception, what happened.
     */
    public record Call(int node, int step, int end, boolean returned, int resumeStep,
                       String process, int slot, int pid, String program, String kind, String name, String text,
                       String result, String output, int handlerStep, String handler,
                       boolean earlier, Wait waited, List<Wait> waits, String resumesAt) {

        /** The one-line form for the event tree and the timeline. */
        public String detail() {
            if (kind.equals("exception"))
                return output == null ? text : text + ": " + output;
            if (returned)
                return text + " = " + result;
            return name.equals("exit") ? text : text + " …";
        }

        Call with(String o, List<Wait> ws) {
            return new Call(node, step, end, returned, resumeStep, process, slot, pid, program, kind, name, text,
                    result, o, handlerStep, handler, earlier, main(ws), ws, resumesAt);
        }
    }

    /** The wait that tells why a call took long: its first sleep, else its first preemption; null if none. */
    static Wait main(List<Wait> ws) {
        for (Wait w : ws)
            if (w.reason().equals("sleep"))
                return w;
        return ws.isEmpty() ? null : ws.getFirst();
    }

    /**
     * Why a call stopped running before it ended: "sleep" (it called
     * sleep(); on: what its chan pointed at) or "preempted" (a timer
     * interrupt made it yield). wokenStep: the store that made it
     * RUNNABLE again, by: the thread that ran it and the function that
     * called wakeup() ("proc[4] wc in piperead"); -1/null if not found.
     * resumed: where the call went on (-1: never, in the recording).
     */
    public record Wait(String reason, int step, String on, int wokenStep, String by, int resumed) {
    }

    private static final int MAX_STR = 48;
    private static final int MAX_ARGV = 8;

    /** A pointer the kernel may write through: [va, va + len), its pointee type, in address space cr3. */
    private record Out(long va, int len, String pointee, long cr3) {
    }

    private final List<Call> calls = new ArrayList<>();
    private final Map<Integer, Integer> byNode = new HashMap<>();
    /* calls whose output is still to be read from the stores, by index */
    private final Map<Integer, Out> pending = new HashMap<>();
    /* calls that slept (index -> slot), whose chan and waker are still to be read */
    private final Map<Integer, Integer> sleepers = new java.util.TreeMap<>();

    private final Recording r;
    private final Tree t;
    private final KernelProfile kernel;
    private final int iRax, iRsp, iCr2;

    private Syscalls(Recording r) {
        this.r = r;
        this.t = r.tree();
        this.kernel = r.kernel();
        iRax = r.regIndex("rax");
        iRsp = r.regIndex("rsp");
        iCr2 = r.regIndex("cr2");
    }

    static Syscalls of(Recording r) {
        Syscalls s = new Syscalls(r);
        for (int n : r.tree().traps()) {
            Call c = switch (r.kernel().trapKind(r.tree().vector(n))) {
                case SYSCALL -> s.syscall(n);
                case EXCEPTION -> s.exception(n);
                case INTERRUPT -> null;
            };
            if (c != null) {
                s.byNode.put(n, s.calls.size());
                s.calls.add(c);
            }
        }
        return s;
    }

    /** Every call, with what the kernel wrote through pointer arguments. */
    public synchronized List<Call> list() {
        for (var e : pending.entrySet()) {
            Call c = calls.get(e.getKey());
            calls.set(e.getKey(), c.with(written(e.getValue(), c.step(), c.end()), c.waits()));
        }
        pending.clear();
        /* in call order, so the memory image only moves forward */
        for (var e : sleepers.entrySet()) {
            Call c = calls.get(e.getKey());
            List<Wait> ws = new ArrayList<>();
            for (Wait w : c.waits())
                ws.add(w.reason().equals("sleep") ? wokenBy(w, e.getValue()) : w);
            calls.set(e.getKey(), c.with(c.output(), ws));
        }
        sleepers.clear();
        return List.copyOf(calls);
    }

    /** The call a trap node (or a continuation of it) belongs to, or null. */
    public synchronized Call of(int node) {
        int o = t.origin(node);
        Integer i = o < 0 ? null : byNode.get(o);
        return i == null ? null : calls.get(i);
    }

    /**
     * The kernel function that handles trap node n: the call of want
     * (sys_write) in it, else its first call of a C function (trap(),
     * usertrap()); -1 if none. Calls are searched in time order.
     */
    private int handler(int n, String want) {
        int first = -1, seen = 0;
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        stack.push(n);
        while (!stack.isEmpty() && seen++ < 5000) {
            int c = stack.pop();
            if (c != n && t.kind(c) == Tree.FUNC) {
                if (want != null && want.equals(t.label(c)))
                    return c;
                if (first < 0 && inC(c))
                    first = c;
            }
            int[] kids = t.children(c);
            for (int i = kids.length - 1; i >= 0; i--)
                if (t.kind(kids[i]) != Tree.INTR) /* not another trap taken inside */
                    stack.push(kids[i]);
        }
        return first;
    }

    /**
     * For an exception: the function the C handler (trap()) spent the
     * call in, its callee that ran the most steps (vmfault for a page
     * fault), not counting the step-filter helpers; the handler itself
     * if it called nothing.
     */
    private int busiest(int h) {
        int best = h, most = -1;
        for (int c : t.children(h)) {
            if (t.kind(c) != Tree.FUNC || kernel.stepSkip().contains(t.label(c)))
                continue;
            int len = t.end(c) - t.start(c);
            if (len > most) {
                most = len;
                best = c;
            }
        }
        return best;
    }

    private boolean inC(int node) {
        return kernel.isC(r.insn(t.start(node)).vaddr());
    }

    /** The end of node n's last part, and where it went on after sleeping (-1 if it never slept). */
    private int[] endAndResume(int n) {
        int last = t.lastPart(n);
        return new int[] {t.end(last), last != n ? t.start(last) : -1};
    }

    /** "proc[2] pid 3 echo": the process, and the program that made the call. */
    /** The program that made call n: the one running before its trap, else its run's (a call from before the recording). */
    private String program(int n, Program prog) {
        if (prog != null)
            return prog.name();
        String run = t.label(t.ancestorAtDepth(n, 0)), who = t.processOf(n);
        if (run == null || who == null || !run.startsWith(who + " "))
            return null;
        String p = run.substring(who.length() + 1);
        return p.substring(p.lastIndexOf('\u2192') + 1); /* sh→echo: echo */
    }

    private String process(int n, Program prog) {
        String run = t.label(t.ancestorAtDepth(n, 0));
        String slot = t.processOf(n);
        if (slot == null)
            return prog != null ? prog.name() : run;
        return prog != null ? slot + " " + prog.name() : run;
    }

    /** The program of the user-mode step just before step (the one that trapped), or null. */
    private Program userProgram(int step) {
        if (step <= 0)
            return null;
        Recording.View before = r.view(step - 1);
        return before.cpl() == 3 ? before.program() : null;
    }

    // ---------------- system calls ----------------

    private Call syscall(int n) {
        int st = t.start(n);
        boolean earlier = (t.flags(n) & Tree.EARLIER) != 0;
        long[] regs = r.registers(st);
        String name = earlier ? t.label(n).substring("syscall ".length())
                : kernel.syscallName(regs[iRax]);
        Proto proto = kernel.syscallProto(name);
        int[] er = endAndResume(n);
        int end = er[0];
        boolean returned = end < r.steps() && r.view(end).cpl() == 3;
        if (earlier) {
            /* began before the recording: the arguments are the user's
             * registers, restored from the trap frame when it returns */
            if (!returned)
                proto = null;
            else
                regs = r.registers(end);
        }

        List<String> params = proto == null ? List.of() : proto.params();
        long[] args = new long[params.size()];
        List<String> argv = kernel.syscallArgs();
        for (int i = 0; i < args.length && i < argv.size(); i++)
            args[i] = regs[r.regIndex(argv.get(i))];

        /* the caller's address space: for a call from before the recording,
         * the one it returns to (riscv-style's kernel has its own table) */
        long cr3 = earlier && returned ? r.cr3(end) : r.cr3(st);
        List<String> shown = new ArrayList<>();
        Out[] out = {null};
        r.virtual(earlier && returned ? end : st, vm -> {
            for (int i = 0; i < args.length; i++) {
                String type = params.get(i);
                long a = args[i];
                shown.add(argText(kernel, vm, name, params, args, i));
                /* a pointer the kernel may write through: what it wrote, read later */
                boolean nextIsLen = i + 1 < args.length && !params.get(i + 1).contains("*");
                if (type.endsWith("*") && !type.equals("const char *") && !type.equals("char **")
                        && !(type.equals("const void *") && nextIsLen)) {
                    String pointee = type.replace("const ", "").replace(" *", "").trim();
                    int len = nextIsLen && (pointee.equals("void") || pointee.equals("char"))
                            ? (int) Math.max(0, Math.min(args[i + 1], 4096)) : 64;
                    if (a != 0 && len > 0 && out[0] == null)
                        out[0] = new Out(a, len, pointee, cr3);
                }
            }
            return null;
        });
        String text = proto == null ? name + "(…)" : name + "(" + String.join(", ", shown) + ")";

        String result = null;
        if (returned) {
            long rax = r.registers(end)[iRax];
            result = proto != null && proto.returns().endsWith("*") ? hex(rax) : Integer.toString((int) rax);
            if (out[0] != null && (int) rax >= 0)
                pending.put(calls.size(), out[0]);
        }
        int h = handler(n, "sys_" + name);
        int slot = t.slotOf(n);
        List<Wait> ws = waits(n, slot);
        if (ws.stream().anyMatch(w -> w.reason().equals("sleep")))
            sleepers.put(calls.size(), slot);
        Program up = userProgram(st);
        return new Call(n, st, end, returned, er[1], process(n, up), slot,
                t.pidOf(n), program(n, up), "syscall", name, text,
                result, null, h < 0 ? -1 : t.start(h), h < 0 ? null : t.label(h), earlier,
                main(ws), ws, null);
    }

    /**
     * Argument i of a call as strace shows it, read through vm: a string,
     * argv, the bytes a buffer holds (with a length after it), a pointer
     * in hex, a flag by the kernel's #define names, a number.
     */
    static String argText(KernelProfile kernel, MemoryImage.Virtual vm, String call, List<String> params,
                          long[] args, int i) {
        String type = params.get(i);
        long a = args[i];
        boolean nextIsLen = i + 1 < args.length && !params.get(i + 1).contains("*");
        if (type.equals("const char *"))
            return quote(vm.cstring(a, MAX_STR + 1));
        if (type.equals("char **"))
            return strings(vm, a);
        if (type.equals("const void *") && nextIsLen)
            return quote(new String(vm.bytes(a, (int) Math.min(args[i + 1], MAX_STR + 1)),
                    StandardCharsets.ISO_8859_1));
        if (type.endsWith("*"))
            return hex(a);
        return named(kernel, call, i, intText(type, a), a);
    }

    /**
     * A flag argument by the kernel's own #define names: sbrk(n,
     * SBRK_LAZY), open(path, O_CREATE|O_WRONLY). Other arguments as they are.
     */
    private static String named(KernelProfile kernel, String call, int arg, String text, long v) {
        String prefix = call.equals("sbrk") && arg == 1 ? "SBRK_" : call.equals("open") && arg == 1 ? "O_" : null;
        if (prefix == null)
            return text;
        Map<String, Long> defs = new java.util.LinkedHashMap<>();
        for (var e : kernel.defines().entrySet())
            if (e.getKey().startsWith(prefix) && e.getValue() >= 0)
                defs.put(e.getKey(), e.getValue());
        if (prefix.equals("SBRK_")) {
            for (var e : defs.entrySet())
                if (e.getValue() == v)
                    return e.getKey();
            return text;
        }
        /* open's flags: an access mode (0, 1, 2) plus or-ed bits */
        long mode = v & 3, rest = v & ~3L;
        String m = null;
        List<String> parts = new ArrayList<>();
        for (var e : defs.entrySet()) {
            long x = e.getValue();
            if (x < 4 && x == mode && m == null)
                m = e.getKey();
            else if (x >= 4 && Long.bitCount(x) == 1 && (rest & x) != 0) {
                parts.add(e.getKey());
                rest &= ~x;
            }
        }
        if (m == null || rest != 0)
            return text;
        parts.addFirst(m);
        return String.join("|", parts);
    }

    private static String intText(String type, long v) {
        if (type.equals("short"))
            return Short.toString((short) v);
        if (type.startsWith("uint") || type.startsWith("unsigned"))
            return Long.toString(v & 0xffffffffL);
        return Integer.toString((int) v);
    }

    /** What the kernel stored at [o.va, o.va + o.len) during [from, to), decoded by pointee type; null if nothing. */
    private String written(Out o, int from, int to) {
        /* the buffer's frames at the start of the call */
        long first = o.va() >>> 12;
        long[] framePa = r.memory(from, img -> {
            long[] pa = new long[(int) (((o.va() + o.len() - 1) >>> 12) - first + 1)];
            for (int i = 0; i < pa.length; i++) {
                long p = img.translate(o.cr3(), (first + i) << 12);
                pa[i] = p < 0 ? -1 : p & ~0xfffL;
            }
            return pa;
        });
        byte[] buf = new byte[o.len()];
        int[] n = {0};
        r.scanStores(from, to, (s, pa, size, value) -> {
            for (int i = 0; i < framePa.length; i++) {
                if (framePa[i] < 0 || ((pa ^ framePa[i]) & ~0xfffL) != 0)
                    continue;
                long base = ((first + i) << 12) - o.va(); /* offset of this frame's first byte */
                for (int b = 0; b < size; b++) {
                    long off = base + ((pa + b) & 0xfff);
                    if (off >= 0 && off < o.len()) {
                        buf[(int) off] = (byte) (value >>> (8 * b));
                        n[0] = Math.max(n[0], (int) off + 1);
                    }
                }
            }
        });
        if (n[0] == 0)
            return null;
        return switch (o.pointee()) {
            case "void", "char" -> quote(new String(buf, 0, n[0], StandardCharsets.ISO_8859_1));
            case "int" -> {
                List<String> ints = new ArrayList<>();
                for (int i = 0; i + 4 <= n[0]; i += 4)
                    ints.add(Integer.toString((buf[i] & 0xff) | (buf[i + 1] & 0xff) << 8
                            | (buf[i + 2] & 0xff) << 16 | buf[i + 3] << 24));
                yield "[" + String.join(", ", ints) + "]";
            }
            default -> "{" + n[0] + " bytes}";
        };
    }

    // ---------------- exceptions ----------------

    private Call exception(int n) {
        int st = t.start(n);
        int v = t.vector(n);
        long[] regs = r.registers(st);
        long rsp = regs[iRsp];
        boolean hasErr = KernelProfile.hasErrorCode(v);
        /* the frame the CPU pushed: [error code,] rip, cs */
        long[] frame = r.virtual(st, vm -> {
            long[] f = new long[3];
            for (int i = 0; i < 3; i++) {
                Long w = vm.read(rsp + 8L * i, 8);
                f[i] = w == null ? 0 : w;
            }
            return f;
        });
        long err = hasErr ? frame[0] : 0;
        long rip = hasErr ? frame[1] : frame[0];
        long cs = hasErr ? frame[2] : frame[1];
        boolean user = (cs & 3) == 3;
        String where = kernel.locate(rip, user, user ? userProgram(st) : null).label();
        int[] er = endAndResume(n);
        String text, output;
        if (v == 14) {
            text = "page fault at " + hex(regs[iCr2]);
            output = ((err & 4) != 0 ? "user " : "kernel ") + ((err & 16) != 0 ? "instruction fetch"
                    : (err & 2) != 0 ? "write" : "read") + " at " + where + ", "
                    + ((err & 1) != 0 ? "not allowed by the page's rights" : "page not present");
        } else {
            text = kernel.vectorName(v);
            output = "at " + where + (hasErr ? ", error code " + hex(err) : "");
        }
        boolean returned = er[0] < r.steps() && r.view(er[0]).cpl() == (user ? 3 : 0);
        int h = handler(n, null);
        if (h >= 0)
            h = busiest(h);
        int slot = t.slotOf(n);
        /* where it went on: the instruction that faulted, run again */
        String resumes = returned ? r.view(er[0]).location().label() : null;
        List<Wait> ws = waits(n, slot);
        if (ws.stream().anyMatch(w -> w.reason().equals("sleep")))
            sleepers.put(calls.size(), slot);
        Program up = userProgram(st);
        return new Call(n, st, er[0], returned, er[1], process(n, up), slot,
                t.pidOf(n), program(n, up), "exception",
                kernel.vectorName(v), text, null, output, h < 0 ? -1 : t.start(h),
                h < 0 ? null : t.label(h), (t.flags(n) & Tree.EARLIER) != 0,
                main(ws), ws, resumes);
    }

    // ---------------- sleeping ----------------

    /**
     * Every time call n was switched out, in order, and why: by the state
     * its process set before it called sched(), the last store to
     * proc[slot].state in that part. SLEEPING: it slept (sleep());
     * RUNNABLE: it was preempted (yield() after a timer interrupt);
     * anything else (ZOMBIE: exit) is no wait at all. What a sleep waits
     * on is read later (list()), with who woke it.
     */
    private List<Wait> waits(int n, int slot) {
        List<Wait> out = new ArrayList<>();
        Map<Long, String> states = kernel.structs().enumWith("SLEEPING");
        if (slot < 0 || states == null)
            return out;
        ProcStates ps = r.procStates();
        int d = t.depth(n);
        for (int part = n; (t.flags(part) & Tree.SUSPENDED) != 0; ) {
            int last = t.end(part) - 1;
            /* the state it set before switching out (still RUNNING: it set none in this part) */
            String state = states.get((long) ps.at(slot, last + 1));
            int run = t.nextRunOfThread(t.ancestorAtDepth(part, 0));
            int c = run < 0 ? -1 : t.ancestorAtDepth(t.innermost(t.start(run)), d);
            boolean goesOn = c >= 0 && t.kind(c) == t.kind(n) && t.label(c).equals(t.label(n));
            int resumed = goesOn ? t.start(c) : -1;
            if ("SLEEPING".equals(state))
                out.add(new Wait("sleep", last, null, -1, null, resumed));
            else if ("RUNNABLE".equals(state))
                out.add(new Wait("preempted", last, null, -1, null, resumed));
            if (!goesOn)
                break;
            part = c;
        }
        return out;
    }

    /**
     * What a sleeping call waited on (its chan, named), and the first store
     * after it went to sleep that changed its proc's state (SLEEPING ->
     * RUNNABLE): wakeup()'s, or kill's. Named by the thread that made it
     * and wakeup()'s caller.
     */
    private Wait wokenBy(Wait w, int slot) {
        String on = null;
        for (Processes.Proc p : r.processes(w.step()).procs())
            if (p.slot() == slot)
                on = p.chanName() != null ? p.chanName() : p.chan();
        ProcStates ps = r.procStates();
        int i = ps.nextOf(slot, w.step());
        int to = w.resumed() >= 0 ? w.resumed() : r.steps();
        int at = i < 0 || ps.step(i) >= to ? -1 : ps.step(i);
        if (at < 0)
            return new Wait(w.reason(), w.step(), on, -1, null, w.resumed());
        String who = r.threadLabel(at);
        String caller = null, trap = null;
        for (int a = t.innermost(at); a >= 0 && t.kind(a) != Tree.RUN; a = t.parent(a)) {
            if (caller == null && t.kind(a) == Tree.FUNC && t.label(a).equals("wakeup")) {
                int p = t.parent(a);
                caller = p >= 0 && t.kind(p) == Tree.FUNC ? t.label(p) : "?";
            }
            if (t.kind(a) == Tree.INTR && !r.kernel().isSyscallVector(t.vector(a)))
                trap = t.label(a);
        }
        /* an interrupt wakes it in whatever thread it lands in: name the interrupt */
        String by = trap != null ? trap + " (while " + who + " ran)" : who;
        return new Wait(w.reason(), w.step(), on, at, by + (caller != null ? ", in " + caller + "()" : ""),
                w.resumed());
    }

    // ---------------- formatting ----------------

    /** A NULL-terminated array of strings (exec's argv). */
    private static String strings(MemoryImage.Virtual vm, long va) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < MAX_ARGV; i++) {
            Long p = vm.read(va + 8L * i, 8);
            if (p == null || p == 0)
                return "[" + String.join(", ", out) + "]";
            out.add(quote(vm.cstring(p, MAX_STR + 1)));
        }
        return "[" + String.join(", ", out) + ", …]";
    }

    /** "hi\n" -> "\"hi\\n\"", cut at MAX_STR characters. */
    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        int n = Math.min(s.length(), MAX_STR);
        for (int i = 0; i < n; i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\n' -> b.append("\\n");
                case '\t' -> b.append("\\t");
                case '\r' -> b.append("\\r");
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                default -> {
                    if (c >= 32 && c < 127)
                        b.append(c);
                    else
                        b.append(String.format("\\x%02x", (int) c & 0xff));
                }
            }
        }
        b.append('"');
        if (s.length() > MAX_STR)
            b.append('…');
        return b.toString();
    }
}

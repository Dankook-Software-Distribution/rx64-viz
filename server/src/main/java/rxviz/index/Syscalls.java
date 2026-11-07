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
                       String process, String kind, String name, String text, String result,
                       String output) {

        /** The one-line form for the event tree and the timeline. */
        public String detail() {
            if (kind.equals("exception"))
                return output == null ? text : text + ": " + output;
            if (returned)
                return text + " = " + result;
            return name.equals("exit") ? text : text + " …";
        }

        Call withOutput(String o) {
            return new Call(node, step, end, returned, resumeStep, process, kind, name, text,
                    result, o);
        }
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
            calls.set(e.getKey(), c.withOutput(written(e.getValue(), c.step(), c.end())));
        }
        pending.clear();
        return List.copyOf(calls);
    }

    /** The call a trap node (or a continuation of it) belongs to, or null. */
    public synchronized Call of(int node) {
        int o = t.origin(node);
        Integer i = o < 0 ? null : byNode.get(o);
        return i == null ? null : calls.get(i);
    }

    /** The end of node n's last part, and where it went on after sleeping (-1 if it never slept). */
    private int[] endAndResume(int n) {
        int last = t.lastPart(n);
        return new int[] {t.end(last), last != n ? t.start(last) : -1};
    }

    /** "proc[2] echo": the process slot, and the program that made the call. */
    private String process(int n, int st) {
        String run = t.label(t.ancestorAtDepth(n, 0));
        String slot = run != null && run.startsWith("proc[") ? run.substring(0, run.indexOf(']') + 1) : null;
        Program prog = userProgram(st);
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
        long[] regs = r.registers(st);
        String name = kernel.syscallName(regs[iRax]);
        Proto proto = kernel.syscallProto(name);
        int[] er = endAndResume(n);
        int end = er[0];
        boolean returned = end < r.steps() && r.view(end).cpl() == 3;

        List<String> params = proto == null ? List.of() : proto.params();
        long[] args = new long[params.size()];
        List<String> argv = kernel.syscallArgs();
        for (int i = 0; i < args.length && i < argv.size(); i++)
            args[i] = regs[r.regIndex(argv.get(i))];

        long cr3 = r.cr3(st);
        List<String> shown = new ArrayList<>();
        Out[] out = {null};
        r.virtual(st, vm -> {
            for (int i = 0; i < args.length; i++) {
                String type = params.get(i);
                long a = args[i];
                boolean nextIsLen = i + 1 < args.length && !params.get(i + 1).contains("*");
                if (type.equals("const char *"))
                    shown.add(quote(vm.cstring(a, MAX_STR + 1)));
                else if (type.equals("char **"))
                    shown.add(strings(vm, a));
                else if (type.equals("const void *") && nextIsLen)
                    shown.add(quote(new String(vm.bytes(a, (int) Math.min(args[i + 1], MAX_STR + 1)),
                            StandardCharsets.ISO_8859_1)));
                else if (type.endsWith("*")) {
                    shown.add(hex(a));
                    String pointee = type.replace("const ", "").replace(" *", "").trim();
                    int len = nextIsLen && (pointee.equals("void") || pointee.equals("char"))
                            ? (int) Math.max(0, Math.min(args[i + 1], 4096)) : 64;
                    if (a != 0 && len > 0 && out[0] == null)
                        out[0] = new Out(a, len, pointee, cr3);
                } else
                    shown.add(intText(type, a));
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
        return new Call(n, st, end, returned, er[1], process(n, st), "syscall", name, text,
                result, null);
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
        return new Call(n, st, er[0], returned, er[1], process(n, st), "exception",
                kernel.vectorName(v), text, null, output);
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

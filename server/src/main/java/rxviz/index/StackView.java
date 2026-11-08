package rxviz.index;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import rxviz.config.KernelProfile;
import rxviz.config.KernelProfile.Program;
import rxviz.config.KernelProfile.StackArea;
import rxviz.elf.Addr;
import rxviz.elf.Structs;

/**
 * The stack in use at a step, slot by slot (8 bytes), from rsp up to the
 * top of the stack, with what each known slot holds and which function's
 * frame it is in. Everything comes from the recording:
 *
 *   - every call still open on the event-tree path pushed its return
 *     address at the rsp of the callee's first step (and the callee's
 *     `push %rbp`, once run, the slot below it);
 *   - every open trap pushed the CPU's frame (rip, cs, rflags, rsp, ss,
 *     after an error code for some exceptions) at the rsp of the stub's
 *     first step, and the stub and its kernel saved the rest of struct
 *     trapframe below it (field names from DWARF);
 *   - frames opened before the recording started are found through the
 *     saved-rbp chain (the kernels are built with frame pointers).
 *
 * A frame runs from just above the return address of its callee up to
 * and including its own return address; its function is the one that
 * its callee returns into.
 */
public final class StackView {

    /**
     * One 8-byte slot. label: what the kernel keeps there (null if
     * unknown); target: where a code address points; writer: for a trap
     * frame's slot, who stored its value (CPU, vector64, alltraps), or null.
     */
    public record Slot(long addr, long value, boolean mapped, String label, String target, String writer,
                       boolean stale) {
    }

    /** Addresses [from, to); kind "frame" or "trapframe"; step: where it was entered (-1 unknown). */
    public record Frame(String function, long from, long to, String kind, int step) {
    }

    public record Stack(int step, StackArea area, long rsp, long rbp, List<Slot> slots,
                        List<Frame> frames) {
    }

    /** A call's return address slot, or a trap's saved registers, found on the stack. */
    private sealed interface Mark permits Ret, Trap {
    }

    private record Ret(long addr, int step) implements Mark {
    }

    /** struct trapframe at [base, end); rip at ripAt; interrupted: function that was running in the kernel, or null. */
    private record Trap(long base, long end, long ripAt, String label, int step) implements Mark {
    }

    private static final int MAX_CHAIN = 256;

    private final Recording r;
    private final Tree t;
    private final KernelProfile kernel;
    private final int iRsp, iRbp;

    StackView(Recording r) {
        this.r = r;
        this.t = r.tree();
        this.kernel = r.kernel();
        List<String> names = Arrays.asList(r.regNames());
        iRsp = names.indexOf("rsp");
        iRbp = names.indexOf("rbp");
    }

    /** writers: also find who stored each trap frame slot (a scan of the stores; Names does not need it). */
    Stack at(int step, boolean withWriters) {
        Recording.View v = r.view(step);
        long rsp = v.regs()[iRsp];
        long rbp = v.regs()[iRbp];
        boolean user = v.cpl() == 3;
        StackArea area = kernel.stackOf(rsp, user);
        Structs.Struct tf = kernel.structs().get("trapframe");
        boolean tfFits = tf != null && tf.member("rip") != null
                && tf.member("rip").offset() == tf.size() - 40;
        long lo = rsp & ~7L;
        if (area.kind().equals("trap") && tfFits) {
            /* riscv-style's TRAPFRAME page holds struct trapframe at its
             * start, the CPU frame at its end: show all of it */
            area = new StackArea(area.kind(), area.label(), area.base(), area.base() + tf.size());
            lo = area.base();
        }
        if (!within(area, lo))
            lo = area.base();
        long from = lo;
        StackArea stack = area;

        /* the stack's words as the CPU sees them at this step (null: unmapped) */
        Words words = new Words(from, r.virtual(step, vm -> {
            Long[] w = new Long[(int) ((stack.top() - from) >>> 3)];
            for (int i = 0; i < w.length; i++)
                w[i] = vm.read(from + 8L * i, 8);
            return w;
        }));

        /* marks, keyed by offset from the stack base (unsigned order) */
        TreeMap<Long, Mark> marks = new TreeMap<>();
        Map<Long, String> labels = new HashMap<>();
        Map<Long, String> targets = new HashMap<>();
        for (int n = t.innermost(step); n >= 0 && t.kind(n) != Tree.RUN; n = t.parent(n)) {
            int o = t.origin(n);
            /* a frame from before the recording: the saved-rbp chain finds it */
            if (o < 0 || (t.flags(o) & Tree.EARLIER) != 0)
                continue;
            int st = t.start(o);
            long e = r.registers(st)[iRsp];
            if (t.kind(o) == Tree.FUNC) {
                if (!within(area, e) || Long.compareUnsigned(e, from) < 0)
                    continue;
                marks.put(e - area.base(), new Ret(e, st));
                int body = st + (isEndbr(st) ? 1 : 0);
                if (step > body && body < r.steps() && isPushRbp(body)
                        && Long.compareUnsigned(e - 8, from) >= 0)
                    labels.put(e - 8, "saved rbp");
            } else {
                int vec = t.vector(o);
                long ripAt = e + (vec >= 0 && KernelProfile.hasErrorCode(vec) ? 8 : 0);
                if (!within(area, ripAt))
                    continue;
                long end = ripAt + 40;
                long base = tfFits ? end - tf.size() : e;
                marks.put(base - area.base(), new Trap(base, end, ripAt, t.label(o), st));
                if (ripAt != e)
                    labels.put(e, tfFits ? "trapframe." + tf.memberAt(e - base).name() : "error code");
                for (long a = base; Long.compareUnsigned(a, end) < 0; a += 8) {
                    if (Long.compareUnsigned(a, from) < 0 || labels.containsKey(a))
                        continue;
                    String name = tfFits ? tf.memberAt(a - base).name()
                            : KernelProfile.cpuFrame(-1).get((int) ((a - ripAt) / 8));
                    labels.put(a, "trapframe." + name);
                }
                Long rip = words.get(ripAt), cs = words.get(ripAt + 8);
                if (rip != null && cs != null) {
                    boolean fromUser = (cs & 3) == 3;
                    Program p = fromUser && st > 0 ? r.view(st - 1).program() : null;
                    String target = code(rip, fromUser, p);
                    if (target != null)
                        targets.put(ripAt, target);
                }
            }
        }

        /* the saved-rbp chain, for frames the tree does not know */
        Program prog = v.program();
        long l = rbp;
        for (int i = 0; i < MAX_CHAIN; i++) {
            if ((l & 7) != 0 || !within(area, l) || !within(area, l + 15)
                    || Long.compareUnsigned(l, from) < 0)
                break;
            Long ret = words.get(l + 8), next = words.get(l);
            if (ret == null || next == null || !kernel.isReturnAddress(ret, user, prog))
                break;
            labels.putIfAbsent(l, "saved rbp");
            marks.putIfAbsent(l + 8 - area.base(), new Ret(l + 8, -1));
            if (Long.compareUnsigned(next, l) <= 0)
                break;
            l = next;
        }

        /* return-address slots */
        for (Mark m : marks.values())
            if (m instanceof Ret ret) {
                labels.put(ret.addr(), "return address");
                String target = code(words.or0(ret.addr()), user, prog);
                if (target != null)
                    targets.put(ret.addr(), target);
            }

        /* frames, from rsp upwards */
        List<Frame> frames = new ArrayList<>();
        String fn = v.location().symbol() != null ? v.location().symbol() : "?";
        long start = from;
        for (Mark m : marks.values()) {
            switch (m) {
                case Ret ret -> {
                    if (Long.compareUnsigned(ret.addr(), start) < 0)
                        continue;
                    frames.add(new Frame(fn, start, ret.addr() + 8, "frame", ret.step()));
                    String caller = kernel.callerOf(words.or0(ret.addr()), user, prog);
                    fn = caller == null ? "?" : caller;
                    start = ret.addr() + 8;
                }
                case Trap tr -> {
                    if (Long.compareUnsigned(tr.end(), start) <= 0)
                        continue;
                    if (Long.compareUnsigned(start, tr.base()) < 0)
                        frames.add(new Frame(fn, start, tr.base(), "frame", -1));
                    /* still being pushed: only its upper part is on the stack */
                    long base = Long.compareUnsigned(start, tr.base()) < 0 ? tr.base() : start;
                    frames.add(new Frame(tr.label(), base, tr.end(), "trapframe", tr.step()));
                    Long cs = words.get(tr.ripAt() + 8);
                    fn = cs == null || (cs & 3) == 3 ? null
                            : function(words.or0(tr.ripAt()), false, null);
                    start = tr.end();
                }
            }
        }
        if (Long.compareUnsigned(start, area.top()) < 0)
            frames.add(new Frame(fn, start, area.top(), "frame", -1));

        Map<Long, String> writers = new HashMap<>();
        java.util.Set<Long> stale = new java.util.HashSet<>();
        if (withWriters)
            writers(step, marks.values(), from, writers, stale);
        List<Slot> slots = new ArrayList<>();
        for (long a = from; Long.compareUnsigned(a, area.top()) < 0; a += 8) {
            slots.add(new Slot(a, words.or0(a), words.get(a) != null, labels.get(a), targets.get(a),
                    writers.get(a), stale.contains(a)));
        }
        return new Stack(step, area, rsp, rbp, slots, frames);
    }

    /** Longest stretch scanned for the stores of a trap frame's slots. */
    private static final int MAX_WRITERS = 4 << 20;

    /**
     * For each slot of the trap frames on the stack: the function whose
     * store wrote its value (the last store before step), or "CPU" for the
     * words the CPU pushed while delivering the trap (stored at the step
     * that trapped, before the stub's first step); into stale, the slots
     * no store has written since the trap began (what they hold is older).
     */
    private void writers(int step, java.util.Collection<Mark> marks, long from, Map<Long, String> out,
                         java.util.Set<Long> stale) {
        for (Mark m : marks) {
            if (!(m instanceof Trap tr) || tr.step() < 0 || step - tr.step() > MAX_WRITERS)
                continue;
            Map<Long, Long> paOf = r.virtual(step, vm -> {
                Map<Long, Long> x = new HashMap<>();
                for (long a = tr.base(); Long.compareUnsigned(a, tr.end()) < 0; a += 8)
                    if (vm.pa(a) >= 0)
                        x.put(vm.pa(a), a);
                return x;
            });
            /* the stores to this frame since the trap began: scanned once, then extended */
            Map<Long, Integer> last = new HashMap<>();
            for (int[] w : r.trapFrameStores(tr.step(), paOf.keySet(), step))
                if (w[0] < step)
                    last.put(paOf.get((long) w[1] << 3), w[0]);
            /* what the CPU pushed: from rsp at the stub's first step to the frame's end */
            long cpu = r.registers(tr.step())[iRsp];
            for (long a = tr.base(); Long.compareUnsigned(a, tr.end()) < 0; a += 8) {
                if (Long.compareUnsigned(a, from) < 0)
                    continue;
                if (Long.compareUnsigned(a, cpu) >= 0)
                    out.put(a, "CPU");
                else if (last.containsKey(a))
                    out.put(a, function(r.insn(last.get(a)).vaddr(), false, null));
                else
                    stale.add(a); /* left over from an earlier trap */
            }
        }
    }

    /** The stack's 8-byte words from `from`; null where unmapped or outside. */
    private record Words(long from, Long[] w) {
        Long get(long a) {
            long i = (a - from) >>> 3;
            return (a & 7) == (from & 7) && i < w.length ? w[(int) i] : null;
        }

        long or0(long a) {
            Long v = get(a);
            return v == null ? 0 : v;
        }
    }

    private static boolean within(StackArea area, long a) {
        return Addr.in(a, area.base(), area.top() - area.base());
    }

    private boolean isEndbr(int step) {
        byte[] b = r.insn(step).bytes();
        return b.length == 4 && (b[0] & 0xff) == 0xf3 && b[1] == 0x0f && b[2] == 0x1e
                && (b[3] & 0xff) == 0xfa;
    }

    private boolean isPushRbp(int step) {
        byte[] b = r.insn(step).bytes();
        return b.length == 1 && b[0] == 0x55;
    }

    /** "fn+0x1a" if v is an address in code, else null. */
    private String code(long v, boolean user, Program p) {
        KernelProfile.Location loc = kernel.locate(v, user, p);
        return loc.symbol() == null || loc.disas() == null ? null : loc.label();
    }

    private String function(long v, boolean user, Program p) {
        String f = kernel.function(v, user, p);
        return f == null ? "?" : f;
    }
}

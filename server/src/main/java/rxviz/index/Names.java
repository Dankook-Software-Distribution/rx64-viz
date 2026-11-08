package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rxviz.config.KernelProfile;
import rxviz.elf.Addr;
import rxviz.elf.ElfFile;
import rxviz.elf.Structs;

/**
 * What addresses at a step are, in the kernel's own names: the registers
 * that point at something (rdi -> trapframe.rax on proc[2]'s kernel
 * stack, rsp -> 0x28 below the top of it) and the memory operands of the
 * instruction at the step, with the address they use and the value there
 * (before the instruction runs). Names come from the recording: the
 * Stack view's slots (return addresses, struct trapframe fields), the
 * kernel's symbols and DWARF types (proc[2].trapframe, kmem.freelist),
 * the kernel stack slots and regions of application.yml.
 */
public final class Names {

    /**
     * text: the operand as written (0x28(%rax)); addr: its effective
     * address; value: what is there before the instruction (null:
     * unmapped); after: what is there once it ran, if it changed (a store).
     */
    public record Operand(String text, String addr, int size, String value, String after, String name) {
    }

    /** One instruction of the running line, as it runs in this pass: its step, address and operands. */
    public record LineStep(int step, String addr, List<Operand> operands, Trap trap, Control control) {
    }

    /**
     * An instruction that writes a control register (mov to cr0/cr3/cr4,
     * wrmsr to EFER): the value before and after, and what changed: the
     * bits turned on and off, or for cr3 whose page table it was and is.
     */
    public record Control(String reg, String before, String after, String change) {
    }

    /**
     * What the CPU itself does at a trap boundary, from the recording:
     * entering (the instruction at step traps: an int, a fault, an
     * interrupt before it) or leaving (iretq). gate: the IDT entry used;
     * rsp: before -> after and what TSS.rsp0 is; words: the frame it
     * pushed (entering) or popped (leaving), top first; segs: cs and ss
     * before -> after.
     */
    public record Trap(String kind, int vector, String gate, String rspBefore, String rspAfter,
                       String rsp0, List<String[]> words, String cs, String ss, String rflags) {
    }

    public record View(int step, Map<String, String> registers, List<Operand> operands,
                       List<LineStep> line, Trap trap, Control control) {
    }

    private static final String[] NAMED = {"rax", "rbx", "rcx", "rdx", "rsi", "rdi", "rbp", "rsp",
        "r8", "r9", "r10", "r11", "r12", "r13", "r14", "r15"};

    /* [%seg:]disp(base,index,scale), or %seg:disp alone */
    private static final Pattern MEM = Pattern.compile(
            "(?:%([cdefgs]s):)?(-?0x[0-9a-f]+|-?\\d+)?\\((%[a-z0-9]+)?(?:,(%[a-z0-9]+)(?:,(\\d))?)?\\)"
            + "|%([fg]s):(-?0x[0-9a-f]+)");

    private final Recording r;
    private final KernelProfile kernel;
    private final int step;
    private final Recording.View v;
    private StackView.Stack stack;
    /*
     * The page tables and CR3's user regions, built once: for the running
     * line's other steps, the cursor step's (a line rarely changes them;
     * each costs a walk of every page table)
     */
    private Names shared;
    private PageTables tables;
    private List<AddressSpace.Region> regions;

    private Names(Recording r, int step) {
        this.r = r;
        this.kernel = r.kernel();
        this.step = step;
        this.v = r.view(step);
    }

    static View at(Recording r, int step, boolean line) {
        Names n = new Names(r, step);
        Map<String, String> regs = new LinkedHashMap<>();
        for (String reg : NAMED) {
            int k = r.regIndex(reg);
            if (k < 0)
                continue;
            String name = n.name(n.v.regs()[k]);
            if (name != null)
                regs.put(reg, name);
        }
        /* the system call number about to be used */
        String dis = n.v.location().disas();
        int vec = dis != null && dis.startsWith("int") ? intVector(dis) : -1;
        if (vec >= 0 && r.kernel().isSyscallVector(vec)) {
            String sys = r.kernel().syscallName(n.v.regs()[r.regIndex("rax")]);
            regs.put("rax", "SYS_" + sys + ", the system call number");
            /* its arguments, in the registers the kernel reads them from (user.h's types) */
            KernelProfile.Proto proto = r.kernel().syscallProto(sys);
            List<String> argv = r.kernel().syscallArgs();
            long[] args = new long[proto == null ? 0 : Math.min(proto.params().size(), argv.size())];
            for (int i = 0; i < args.length; i++)
                args[i] = r.regIndex(argv.get(i)) < 0 ? 0 : n.v.regs()[r.regIndex(argv.get(i))];
            List<String> texts = r.virtual(step, vm -> {
                List<String> t = new ArrayList<>();
                for (int i = 0; i < args.length; i++)
                    t.add(Syscalls.argText(r.kernel(), vm, sys, proto.params(), args, i));
                return t;
            });
            for (int i = 0; i < args.length; i++) {
                String reg = argv.get(i), type = proto.params().get(i);
                String what = sys + "'s argument " + (i + 1) + " (" + type + "): " + texts.get(i);
                if (type.endsWith("*") && regs.containsKey(reg) && !texts.get(i).startsWith("\""))
                    what += ", " + regs.get(reg);
                regs.put(reg, what);
            }
        }
        return new View(step, regs, n.operands(), line ? n.lineSteps() : List.of(), n.trap(), n.control());
    }

    private static final Pattern MOV_CR = Pattern.compile("^mov\\s+%\\w+,%(cr[034])$");
    private static final long EFER_MSR = 0xc0000080L;

    /** "kernel_pagetable", "proc[1] pid 2 sh's page table", or the table's address. */
    private static String table(String owner, long cr3) {
        if (owner == null)
            return "the page table at " + hex(cr3 & MemoryImage.ADDR_MASK);
        return owner.equals("kernel_pagetable") ? owner : owner + "'s page table";
    }

    /** The control register this step's instruction writes, before and after; null if none. */
    private Control control() {
        String dis = v.location().disas();
        if (dis == null || step + 1 >= r.steps())
            return null;
        String reg = null;
        Matcher m = MOV_CR.matcher(dis.trim());
        if (m.matches())
            reg = m.group(1);
        else if (dis.trim().equals("wrmsr") && (v.regs()[r.regIndex("rcx")] & 0xffffffffL) == EFER_MSR)
            reg = "efer";
        int k = reg == null ? -1 : r.regIndex(reg);
        if (k < 0)
            return null;
        long before = v.regs()[k], after = r.registers(step + 1)[k];
        String change;
        if (reg.equals("cr3")) {
            change = table(r.tableOwner(step, before), before) + " \u2192 " + table(r.tableOwner(step, after), after)
                    + (before == after ? " (the same one: the TLB is flushed)" : "");
        } else {
            change = before == after ? "no bit changed" : Indexer.controlBits(reg, before, after);
        }
        return new Control(reg, hex(before), hex(after), change);
    }

    private static final Pattern INT_IMM = Pattern.compile("\\$0x([0-9a-f]+)");
    private static final Pattern REG32 = Pattern.compile("e[a-z]{2}|r\\d+d");
    private static final Pattern REG_OPERAND =
            Pattern.compile("%(r\\d+[dwb]?|[re]?[a-d]x|[a-d][lh]|[re]?[sd]i[l]?|[re]?[sb]p[l]?)\\b");
    private static final Pattern MEM_PART = Pattern.compile("\\([^)]*\\)");
    private static final Pattern EXTEND = Pattern.compile("(movz|movs)[bw]\\w*");
    /* a symbol DWARF gives no type for (an assembler table), and an offset into it */
    private static final Pattern OFFSET_SUFFIX = Pattern.compile("^(\\w+)\\+0x([0-9a-f]+)$");

    private static int intVector(String dis) {
        Matcher m = INT_IMM.matcher(dis);
        return m.find() ? Integer.parseInt(m.group(1), 16) : -1;
    }

    /* ---------------- the running line ---------------- */

    /** Steps of the running line from here, in this frame (calls and traps gone over), at most 64. */
    private List<LineStep> lineSteps() {
        List<LineStep> out = new ArrayList<>();
        var src = v.location();
        if (src.file() == null)
            return out;
        Tree t = r.tree();
        int depth = depthAt(t, step);
        for (int s = step; s < r.steps() && out.size() < 64; ) {
            int d = depthAt(t, s);
            if (d > depth) {
                int c = t.ancestorAtDepth(t.innermost(s), depth + 1);
                s = Math.max(s + 1, t.end(c));
                continue;
            }
            if (d < depth)
                break;
            Names at = s == step ? this : new Names(r, s);
            at.shared = this;
            var loc = at.v.location();
            if (loc.line() != src.line() || !src.file().equals(loc.file()))
                break;
            out.add(new LineStep(s, hex(at.v.insn().vaddr()), at.operands(), at.trap(), at.control()));
            s++;
        }
        return out;
    }

    private static int depthAt(Tree t, int s) {
        int n = t.innermost(s);
        return n < 0 ? 0 : t.depth(n);
    }

    /* ---------------- the CPU at a trap ---------------- */

    private Trap trap() {
        Tree t = r.tree();
        int iRsp = r.regIndex("rsp");
        if (step + 1 >= r.steps())
            return null;
        long[] after = r.registers(step + 1);
        long[] before = v.regs();
        /* entering: a trap node starts at the next step */
        int n = t.innermost(step + 1);
        if (n >= 0 && t.kind(n) == Tree.INTR && t.start(n) == step + 1 && (t.flags(n) & Tree.CONTINUED) == 0) {
            int vec = t.vector(n);
            var cpu = r.cpu(step);
            String gate = null;
            for (var g : cpu.idt())
                if (g.vector() == vec)
                    gate = "IDT[" + vec + "] " + g.name() + ": " + g.type() + " gate, DPL " + g.dpl()
                            + " \u2192 " + (g.handlerName() != null ? g.handlerName() : g.handler());
            String rsp0 = cpu.tss() == null || cpu.tss().rsp().isEmpty() ? null
                    : "TSS.rsp0 " + cpu.tss().rsp().get(0)
                    + (cpu.tss().rsp0Name() != null ? " (" + cpu.tss().rsp0Name() + ")" : "");
            /* what it pushed, top of the stack (the last word pushed) first */
            List<String[]> words = frameWords(step + 1, after[iRsp], KernelProfile.cpuFrame(vec)).reversed();
            return trapOf("enter", vec, gate, rsp0, words, before, after);
        }
        /* leaving: iretq pops rip, cs, rflags, rsp, ss */
        if (v.insn().kind() == InsnKind.IRET)
            return trapOf("leave", -1, null, null, frameWords(step, before[iRsp], KernelProfile.cpuFrame(-1)),
                    before, after);
        return null;
    }

    /** The words named names at sp upwards, read at step at ("?" where unmapped). */
    private List<String[]> frameWords(int at, long sp, List<String> names) {
        return r.virtual(at, vm -> {
            List<String[]> w = new ArrayList<>();
            for (int i = 0; i < names.size(); i++) {
                Long x = vm.read(sp + 8L * i, 8);
                w.add(new String[] {names.get(i), x == null ? "?" : hex(x)});
            }
            return w;
        });
    }

    private Trap trapOf(String kind, int vec, String gate, String rsp0, List<String[]> words, long[] before,
                        long[] after) {
        int iRsp = r.regIndex("rsp");
        return new Trap(kind, vec, gate, hex(before[iRsp]), hex(after[iRsp]), rsp0, words,
                change("cs", before, after), change("ss", before, after), change("eflags", before, after));
    }

    private String change(String reg, long[] before, long[] after) {
        int k = r.regIndex(reg);
        return hex(before[k]) + " \u2192 " + hex(after[k]);
    }

    /**
     * A physical address in the kernel image (its globals: proc[2].state,
     * kmem.freelist, ftable.file[1].ref), or null. Needs no memory: the
     * image is loaded where the ELF file says.
     */
    public static String physName(KernelProfile kernel, long pa, int size) {
        long va = kernel.kernelElf().linkedFromLoad(pa);
        return objectName(kernel, va == -1 ? pa : va, size); /* -1: loaded at its own address (riscv-style) */
    }

    /** A kernel global's C path at va (proc[2].state) for an access of size bytes (0: an address), or null. */
    private static String objectName(KernelProfile kernel, long va, int size) {
        ElfFile.Symbol obj = kernel.kernelElf().objectAt(va);
        if (obj == null)
            return null;
        String p = kernel.structs().path(obj.name(), va - obj.addr(), size);
        return p != null ? p : obj.name() + "+0x" + Long.toHexString(va - obj.addr());
    }

    /** A namer for step (see name()). */
    static Names of(Recording r, int step) {
        return new Names(r, step);
    }

    /* ---------------- operands ---------------- */

    private List<Operand> operands() {
        List<Operand> out = new ArrayList<>();
        String text = v.location().disas();
        if (text == null)
            return out;
        String mnemonic = text.split("\\s+")[0];
        /* lea computes an address and reads nothing; nop's operand is padding */
        if (mnemonic.startsWith("lea") || mnemonic.startsWith("nop"))
            return out;
        /* this step's stores: an operand they hit changes (its value after); others stay */
        List<Recording.Store> stores = step + 1 < r.steps() ? r.stores(step, step + 1, 64, false) : List.of();
        Matcher m = MEM.matcher(text);
        while (m.find()) {
            long ea;
            String seg;
            if (m.group(6) != null) {
                seg = m.group(6);
                ea = parse(m.group(7));
            } else {
                seg = m.group(1);
                ea = m.group(2) == null ? 0 : parse(m.group(2));
                if (m.group(3) != null) {
                    if (m.group(3).equals("%rip"))
                        ea += v.insn().vaddr() + v.insn().bytes().length;
                    else
                        ea += reg(m.group(3));
                }
                if (m.group(4) != null)
                    ea += reg(m.group(4)) * (m.group(5) == null ? 1 : Integer.parseInt(m.group(5)));
            }
            if (seg != null && (seg.equals("fs") || seg.equals("gs"))) {
                int k = r.regIndex(seg + "_base");
                if (k >= 0)
                    ea += v.regs()[k];
            }
            int size = size(text, mnemonic);
            long at = ea;
            long[] got = r.virtual(step, vm -> {
                Long x = vm.read(at, size);
                return new long[] {x == null ? 0 : x, x == null ? 0 : 1, vm.pa(at)};
            });
            Long val = got[1] == 0 ? null : got[0];
            boolean stored = got[2] >= 0 && stores.stream()
                    .anyMatch(w -> w.paddr() < got[2] + size && w.paddr() + w.size() > got[2]);
            Long now = stored ? r.virtual(step + 1, vm -> vm.read(at, size)) : val;
            String nm = name(ea, size);
            /* DWARF names array elements (syscalls[16]); an untyped table
             * indexed with a scale (vectors[num]) by the instruction's scale */
            Matcher off = nm == null || m.group(4) == null || m.group(5) == null ? null : OFFSET_SUFFIX.matcher(nm);
            if (off != null && off.matches()) {
                int scale = Integer.parseInt(m.group(5));
                long o = Long.parseUnsignedLong(off.group(2), 16);
                if (scale > 0 && o % scale == 0)
                    nm = off.group(1) + "[" + o / scale + "]";
            }
            /* a function's address (syscalls[16], or a store of forkret into
             * a context): the code it points at, after a store its new value */
            Long code = now != null && !now.equals(val) ? now : val;
            if (code != null && size == 8 && nm != null) {
                var loc = kernel.locate(code, false, null);
                if (loc.symbol() != null && loc.offset() == 0 && loc.disas() != null)
                    nm += " \u2192 " + loc.symbol() + "()";
            }
            out.add(new Operand(m.group(), hex(ea), size, val == null ? null : hex(val),
                    now != null && !now.equals(val) ? hex(now) : null, nm));
        }
        return out;
    }

    private static long parse(String s) {
        boolean neg = s.startsWith("-");
        String t = neg ? s.substring(1) : s;
        long x = t.startsWith("0x") ? Long.parseUnsignedLong(t.substring(2), 16) : Long.parseLong(t);
        return neg ? -x : x;
    }

    /** A register operand (%eax is rax's low half, ...): its value at the step. */
    private long reg(String name) {
        String n = name.substring(1);
        long mask = -1;
        String full = n;
        if (REG32.matcher(n).matches()) {
            full = n.startsWith("e") ? "r" + n.substring(1) : n.substring(0, n.length() - 1);
            mask = 0xffffffffL;
        }
        int k = r.regIndex(full);
        return k < 0 ? 0 : v.regs()[k] & mask;
    }

    /** Operand size: a suffix (movq, movzbl's source), else the register operand's width; 8 if unknown. */
    private static int size(String text, String mnemonic) {
        if (mnemonic.matches("movz[bw][lwq]|movs[bw][lwq]|movsl[q]"))
            return mnemonic.charAt(4) == 'b' ? 1 : mnemonic.charAt(4) == 'w' ? 2 : 4;
        if (EXTEND.matcher(mnemonic).matches())
            return mnemonic.charAt(4) == 'b' ? 1 : 2;
        String ops = text.length() > mnemonic.length() ? text.substring(mnemonic.length()) : "";
        Matcher m = REG_OPERAND.matcher(MEM_PART.matcher(ops).replaceAll(""));
        if (m.find()) {
            String g = m.group(1);
            if (g.matches("r\\d+") || g.startsWith("r"))
                return g.endsWith("d") ? 4 : g.endsWith("w") ? 2 : g.endsWith("b") ? 1 : 8;
            if (g.startsWith("e"))
                return 4;
            if (g.endsWith("l") || g.endsWith("h"))
                return 1;
            return 2;
        }
        char c = mnemonic.charAt(mnemonic.length() - 1);
        return switch (c) {
            case 'b' -> 1;
            case 'w' -> 2;
            case 'l' -> 4;
            default -> 8;
        };
    }

    /* ---------------- naming ---------------- */

    /** What va is, or null if nothing known (small numbers, unnamed memory). */
    String name(long va) {
        return name(va, 0);
    }

    /** What an access of size bytes at va is (size 0: the address itself, a pointer's target). */
    String name(long va, int size) {
        if (Long.compareUnsigned(va, 0x1000) < 0)
            return null;
        boolean user = v.cpl() == 3;
        /* the stack in use: Stack's slot labels and frames */
        var area = kernel.stackOf(v.regs()[r.regIndex("rsp")], user);
        if (Addr.in(va, area.base(), area.top() - area.base())) {
            String slot = stackSlot(va);
            /* a pointer to a trap frame points at the frame, not its first field */
            if (size == 0 && slot != null && slot.equals("trapframe." + firstTrapframeField()))
                slot = "trapframe";
            String where = area.label() + (Long.compareUnsigned(va, area.top()) <= 0
                    ? " (top − 0x" + Long.toHexString(area.top() - va) + ")" : "");
            return slot != null ? slot + " on " + area.label() : where;
        }
        if (user)
            return userName(va, v.program());
        /* CR3 holds a process's page table (on riscv-style also in the
         * kernel, until the trampoline switches it): the kernel's own
         * identity-mapped RAM is not mapped there, user memory is */
        long cr3 = v.regs()[r.regIndex("cr3")];
        var prog = r.programAt(cr3, step);
        KernelProfile.Region at = kernel.what(va);
        if (Long.compareUnsigned(va, 1L << 47) < 0 && kernel.kstackSlot(va + 1) < 0
                && (at == null || (prog != null && at.kind().equals("ram")))) {
            /* a user address, seen from the kernel: through CR3's user half */
            String u = userName(va, prog);
            if (u != null)
                return "user " + u;
            boolean mapped = r.paging(step) && r.virtual(step, vm -> vm.pa(va)) >= 0;
            if (prog != null && mapped) {
                /* which part of it: its stack, heap, ... (exec's layout) */
                for (AddressSpace.Region g : regions())
                    if (g.user() && Long.compareUnsigned(va, g.va()) >= 0 && Long.compareUnsigned(va, g.end()) < 0)
                        return g.label() + (g.label().contains(prog.name()) ? "" : " of " + prog.name())
                                + " (+0x" + Long.toHexString(va - g.va()) + ")";
                return "user memory of " + prog.name();
            }
            if (prog != null)
                return "user address, not mapped";
        }
        int slot = kernel.kstackSlot(va + 1);
        if (slot >= 0 && kernel.kstackSlot(va) == slot) {
            var other = kernel.stackOf(va + 1, false);
            return other.label() + " (top − 0x" + Long.toHexString(other.top() - va) + ")";
        }
        var code = kernel.locate(va, false, null);
        if (code.symbol() != null && code.disas() != null)
            return code.label();
        String obj = objectName(kernel, va, size);
        if (obj != null)
            return obj;
        KernelProfile.Region g = at;
        if (g != null) {
            if (g.kind().equals("trapframe")) {
                Structs.Struct tf = kernel.structs().get("trapframe");
                Structs.Member m = tf == null ? null : tf.memberAt(va - g.va());
                if (m != null && va - g.va() < tf.size())
                    return g.label() + " trapframe" + (size == 0 && va == g.va() ? "" : "." + m.name());
            }
            if (!g.kind().equals("ram"))
                return g.label();
            long pa = kernel.directMapToPhys(va);
            /* an entry of a page table: which table, whose, which va */
            String pte = pa >= 0 && r.paging(step) ? tables().entry(pa, size == 0) : null;
            if (pte != null)
                return pte;
            if (pa >= 0)
                return "frame 0x" + Long.toHexString(pa & ~0xfffL) + (pa % 4096 != 0 ? " + 0x" + Long.toHexString(pa & 0xfff) : "")
                        + " (" + g.label() + ")";
        }
        return null;
    }

    private PageTables tables() {
        if (shared != null)
            return shared.tables();
        if (tables == null)
            tables = r.pageTables(step);
        return tables;
    }

    private List<AddressSpace.Region> regions() {
        if (shared != null)
            return shared.regions();
        if (regions == null)
            regions = r.addressSpace(step, -1).regions();
        return regions;
    }

    private String firstTrapframeField() {
        Structs.Struct tf = kernel.structs().get("trapframe");
        Structs.Member m = tf == null ? null : tf.memberAt(0);
        return m == null ? "" : m.name();
    }

    private String stackSlot(long va) {
        if (stack == null)
            stack = r.stackLabels(step);
        for (StackView.Slot s : stack.slots())
            if (Long.compareUnsigned(va, s.addr()) >= 0 && Long.compareUnsigned(va, s.addr() + 8) < 0)
                return s.label() == null ? null
                        : s.label() + (va != s.addr() ? " + " + (va - s.addr()) : "");
        return null;
    }

    private String userName(long va, rxviz.config.KernelProfile.Program prog) {
        if (prog == null)
            return null;
        ElfFile.Symbol obj = prog.elf().objectAt(va);
        if (obj != null)
            return prog.name() + " " + obj.name() + (va != obj.addr() ? "+0x" + Long.toHexString(va - obj.addr()) : "");
        /* code: only an address that is an instruction (symbolAt would
         * also name the heap after the program's last function) */
        var loc = kernel.locate(va, true, prog);
        return loc.disas() != null ? loc.label() : null;
    }
}

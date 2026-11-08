package rxviz.config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import rxviz.elf.Addr;
import rxviz.elf.Disassembly;
import rxviz.elf.ElfFile;
import rxviz.elf.LineTable;
import rxviz.elf.Structs;

/** A kernel build with its symbols, disassembly and user programs loaded. */
public final class KernelProfile {

    /** Code from the image that runs at another virtual address (not in the ELF). */
    private record Alias(long va, long target, long size) {
    }

    /** Where an instruction address comes from; file/line from DWARF, may be null. */
    public record Location(String binary, String symbol, long offset, String disas,
                           String file, int line) {
        static final Location UNKNOWN = new Location(null, null, 0, null, null, 0);

        public String label() {
            if (symbol == null)
                return "?";
            return offset == 0 ? symbol : symbol + "+0x" + Long.toHexString(offset);
        }
    }

    /** An executable image: the kernel or a user program; code32: its ranges assembled for 32-bit mode (.code32). */
    public record Program(ElfFile elf, Disassembly disas, LineTable lines, List<long[]> code32) {
        public String name() {
            return elf.name();
        }

        static Program load(String name, Path file, LineTable.SourceTree src) {
            try {
                ElfFile elf = ElfFile.load(name, file);
                Disassembly d = Disassembly.of(elf);
                LineTable lines = LineTable.of(elf, src);
                List<long[]> c32 = lines.code32(src);
                for (long[] r : c32)
                    d.as32(elf, r[0], r[1]);
                return new Program(elf, d, lines, c32);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    /** The image pc runs from, and the address pc is linked at in it. */
    private record Resolved(Program image, long linked) {
    }

    /** A per-CPU stack in the kernel image, [base, base + extent), cut into slices of size bytes. */
    private record CpuStack(String symbol, long base, long extent, long size) {
    }

    /**
     * A stack: what it is (kind: kernel, trap, user, cpu, unknown), a
     * label for people, and its address range [base, top).
     */
    public record StackArea(String kind, String label, long base, long top) {
    }

    /**
     * A named range of kernel virtual addresses (see what()); pa: where va
     * is meant to map (a device's registers), or -1 if that is not fixed.
     */
    public record Region(long va, long size, String kind, String label, long pa) {
        public Region(long va, long size, String kind, String label) {
            this(va, size, kind, label, -1);
        }
    }

    /**
     * A system call's C prototype from user/user.h: return type and
     * parameter types ("int", "const char *", "char **", ...).
     */
    public record Proto(String name, String returns, List<String> params) {
    }

    /** One line of a disassembly listing, at its runtime address. */
    public record Line(long addr, String text, String function) {
    }

    /** x86 exception vectors 0-31: architectural, the same in every kernel. */
    private static final String[] EXCEPTIONS = {
        "divide error", "debug", "NMI", "breakpoint", "overflow", "bound range",
        "invalid opcode", "device not available", "double fault", "coprocessor overrun",
        "invalid TSS", "segment not present", "stack fault", "general protection fault",
        "page fault", "vector 15", "x87 FP error", "alignment check", "machine check",
        "SIMD FP error", "virtualization", "control protection",
    };

    private final String id;
    private final Path dir;
    private final Program kernel;
    private final List<Program> programs;
    private final long kstackBase;
    private final long kstackStride;
    private final int syscallVector;
    private final Map<Integer, String> vectors;
    private final List<String> syscalls;
    private final List<Alias> aliases = new ArrayList<>();
    private final long[] userTrapStackPages;
    private final List<CpuStack> cpuStacks = new ArrayList<>();
    private Structs structs; /* read on first use: a full DWARF dump */
    private final Set<String> stepSkip;
    private final List<String> syscallArgs;
    private final Map<String, Proto> protos = new HashMap<>();
    private final long directMap;
    private final List<Region> regions = new ArrayList<>();
    /* the kernel image's ELF segments, as regions */
    private final List<Region> imageRegions = new ArrayList<>();
    private final long bootEntry;

    KernelProfile(RxvizProperties.Kernel k, List<String> syscalls, List<String> stepSkip)
            throws IOException {
        this.stepSkip = Set.copyOf(stepSkip);
        this.syscallArgs = k.syscallArgs() == null ? List.of("rdi", "rsi", "rdx", "r10", "r8", "r9")
                : List.copyOf(k.syscallArgs());
        this.id = k.id();
        this.dir = Path.of(k.dir());
        LineTable.SourceTree src = LineTable.SourceTree.of(dir);
        List<Path> users;
        try (Stream<Path> files = Files.list(dir.resolve("user"))) {
            users = files.filter(p -> p.getFileName().toString().startsWith("_")).sorted().toList();
        }
        try {
            this.kernel = Program.load("kernel", dir.resolve("kernel/kernel"), src);
            /* objdump runs per file: load them side by side */
            this.programs = users.parallelStream()
                    .map(p -> Program.load(p.getFileName().toString().substring(1), p, src))
                    .toList();
        } catch (UncheckedIOException e) {
            throw e.getCause();
        }
        this.kstackBase = RxvizProperties.hex(k.kstackBase());
        this.kstackStride = RxvizProperties.hex(k.kstackStride());
        this.syscallVector = k.syscallVector();
        this.vectors = k.vectors() == null ? Map.of() : Map.copyOf(k.vectors());
        this.syscalls = List.copyOf(syscalls);
        if (k.aliases() != null)
            for (RxvizProperties.Alias a : k.aliases()) {
                long target = kernel.elf().addressOf(a.symbol());
                if (target == -1)
                    throw new IllegalArgumentException(id + ": no symbol " + a.symbol());
                aliases.add(new Alias(RxvizProperties.hex(a.va()), target, a.size()));
            }
        if (k.cpuStacks() != null)
            for (RxvizProperties.CpuStack c : k.cpuStacks()) {
                long base = kernel.elf().addressOf(c.symbol());
                if (base == -1)
                    throw new IllegalArgumentException(id + ": no symbol " + c.symbol());
                long extent = Math.max(c.size(), kernel.elf().sizeOf(c.symbol()));
                cpuStacks.add(new CpuStack(c.symbol(), base, extent, c.size()));
            }
        Path userH = dir.resolve("user/user.h");
        if (Files.isRegularFile(userH))
            for (String line : Files.readAllLines(userH)) {
                Proto p = parseProto(line);
                if (p != null)
                    protos.putIfAbsent(p.name(), p);
            }
        this.directMap = k.directMap() == null ? -1 : RxvizProperties.hex(k.directMap());
        for (ElfFile.Segment s : kernel.elf().segments())
            imageRegions.add(s.executable()
                    ? new Region(s.vaddr(), s.memsz(), "kernel-text", "kernel text")
                    : new Region(s.vaddr(), s.memsz(), "kernel-data", "kernel data"));
        if (k.regions() != null)
            for (RxvizProperties.Region g : k.regions())
                regions.add(new Region(RxvizProperties.hex(g.va()), RxvizProperties.hex(g.size()),
                        g.kind(), g.label(), g.pa() == null ? -1 : RxvizProperties.hex(g.pa())));
        String entry = k.bootEntry() == null ? "_entry" : k.bootEntry();
        long entryVa = kernel.elf().addressOf(entry);
        this.bootEntry = entryVa == -1 ? -1 : kernel.elf().physOf(entryVa);
        this.userTrapStackPages = k.userTrapStackPages() == null ? new long[0]
                : k.userTrapStackPages().stream().mapToLong(RxvizProperties::hex).toArray();
    }

    public String id() {
        return id;
    }

    public Path dir() {
        return dir;
    }

    public List<Program> programs() {
        return programs;
    }

    /**
     * Physical address where the kernel starts running (the loader jumps
     * there with paging off), or -1.
     */
    public long bootEntry() {
        return bootEntry;
    }

    /** Process table slot whose kernel stack holds rsp, or -1. */
    public int kstackSlot(long rsp) {
        long page = (rsp - 1) & ~0xfffL;
        long d = page - kstackBase;
        if (d % kstackStride != 0)
            return -1;
        long slot = d / kstackStride;
        return slot >= 0 && slot < 64 ? (int) slot : -1;
    }

    /**
     * True if rsp is on a page that the CPU uses as a stack when trapping
     * from user mode before the kernel switches to the process's kernel
     * stack (riscv-style's TRAPFRAME, mapped in every user page table).
     */
    public boolean isUserTrapStack(long rsp) {
        long page = (rsp - 1) & ~0xfffL;
        for (long p : userTrapStackPages)
            if (p == page)
                return true;
        return false;
    }

    private static final Pattern PROTO = Pattern.compile(
            "^\\s*([A-Za-z_][\\w\\s\\*]*?)\\s*\\b([A-Za-z_]\\w*)\\s*\\(([^)]*)\\)[^;]*;");
    private static final Set<String> TYPE_WORDS = Set.of("int", "char", "short", "long", "void",
            "uint", "unsigned", "const", "struct", "uint64", "uint32", "ushort", "uchar");

    /** "int write(int, const void *, int);" -> Proto; parameter names are dropped. */
    static Proto parseProto(String line) {
        Matcher m = PROTO.matcher(line);
        if (!m.find())
            return null;
        List<String> params = new ArrayList<>();
        String ps = m.group(3).trim();
        if (!ps.isEmpty() && !ps.equals("void") && !ps.contains("..."))
            for (String p : ps.split(",")) {
                String t = p.trim().replaceAll("\\s+", " ");
                String[] w = t.split(" ");
                String last = w[w.length - 1];
                /* "int fd" -> "int"; keep "struct stat *", "const char *" */
                if (w.length >= 2 && last.matches("[A-Za-z_]\\w*") && !TYPE_WORDS.contains(last)
                        && !w[w.length - 2].equals("struct"))
                    t = t.substring(0, t.length() - last.length()).trim();
                params.add(t.replaceAll("\\s*\\*", "*").replaceFirst("\\*", " *"));
            }
        return new Proto(m.group(2), m.group(1).trim().replaceAll("\\s+", " "), params);
    }

    /** The prototype of system call `name` (its stub may be sys_name, as sbrk's), or null. */
    public Proto syscallProto(String name) {
        Proto p = protos.get("sys_" + name);
        return p != null ? p : protos.get(name);
    }

    /** Registers that carry system call arguments 1..6 (argraw()). */
    public List<String> syscallArgs() {
        return syscallArgs;
    }

    /** Functions the step filter skips (lock helpers). */
    public Set<String> stepSkip() {
        return stepSkip;
    }

    /** The kernel's struct layouts (DWARF), read the first time they are needed. */
    public synchronized Structs structs() {
        if (structs == null) {
            try {
                structs = Structs.of(kernel.elf());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return structs;
    }

    /** The kernel image (ELF). */
    public ElfFile kernelElf() {
        return kernel.elf();
    }

    /** Physical address of a kernel virtual address in the direct map of RAM, or -1 if none is configured. */
    public long directMapToPhys(long va) {
        return directMap == -1 ? -1 : va - directMap;
    }

    /**
     * What a kernel-only page (no U bit) at va is: part of the kernel
     * image (by its ELF segments), a process's kernel stack, or a
     * configured region. Null if none of them.
     */
    public Region what(long va) {
        for (Region s : imageRegions)
            if (Addr.in(va, s.va(), s.size()))
                return s;
        int slot = kstackSlot(va + 4096);
        if (slot >= 0)
            return new Region(pageOf(va + 4096), 4096, "kstack", "kernel stack of proc[" + slot + "]");
        for (Region g : regions)
            if (Addr.in(va, g.va(), g.size()))
                return g;
        return null;
    }

    /** The 4 KB page holding the top word of a stack whose pointer is rsp. */
    private static long pageOf(long rsp) {
        return (rsp - 1) & ~0xfffL;
    }

    /**
     * The stack rsp points into: a process's kernel stack, the TRAPFRAME
     * page, a CPU stack in the kernel image, or (user) the user stack
     * page (USERSTACK = 1). Otherwise the page holding rsp.
     */
    public StackArea stackOf(long rsp, boolean user) {
        long page = pageOf(rsp);
        int slot = kstackSlot(rsp);
        if (slot >= 0)
            return new StackArea("kernel", "kernel stack of proc[" + slot + "]", page, page + 4096);
        if (isUserTrapStack(rsp))
            return new StackArea("trap", "TRAPFRAME page", page, page + 4096);
        if (user)
            return new StackArea("user", "user stack", page, page + 4096);
        for (CpuStack c : cpuStacks) {
            if (Addr.in(rsp - 1, c.base(), c.extent())) {
                long d = (rsp - 1) - c.base();
                long cpu = d / c.size();
                long base = c.base() + cpu * c.size();
                return new StackArea("cpu", c.symbol() + " (CPU " + cpu + ")", base, base + c.size());
            }
        }
        return new StackArea("unknown", "stack", page, page + 4096);
    }

    /** x86 exceptions for which the CPU pushes an error code (architectural). */
    /** What the CPU pushes for an interrupt or exception, lowest address first: [error code,] rip, cs, rflags, rsp, ss. */
    public static List<String> cpuFrame(int vector) {
        List<String> f = List.of("rip", "cs", "rflags", "rsp", "ss");
        if (!hasErrorCode(vector))
            return f;
        List<String> e = new ArrayList<>(f);
        e.addFirst("error code");
        return e;
    }

    public static boolean hasErrorCode(int vector) {
        return switch (vector) {
            case 8, 10, 11, 12, 13, 14, 17, 21, 29, 30 -> true;
            default -> false;
        };
    }

    public String vectorName(int vector) {
        String n = vectors.get(vector);
        if (n != null)
            return n;
        if (vector == syscallVector)
            return "syscall";
        return vector < EXCEPTIONS.length ? EXCEPTIONS[vector] : "vector " + vector;
    }

    /** What a trap through vector is. */
    public enum TrapKind { SYSCALL, INTERRUPT, EXCEPTION }

    /** Syscall vector, else exceptions below 32 (architectural), interrupts above. */
    public TrapKind trapKind(int vector) {
        return vector == syscallVector ? TrapKind.SYSCALL
                : vector >= 0 && vector < 32 ? TrapKind.EXCEPTION : TrapKind.INTERRUPT;
    }

    private Map<String, Long> defines;

    /** A page-table-entry bit the MMU ignores that the kernel names (PTE_R, bit 9), with its comment. */
    public record SoftBit(int bit, String name, String what) {
    }

    private List<SoftBit> softBits;

    /**
     * The bits 9-11 of a page-table entry (ignored by the MMU, free for
     * software) the kernel #defines as PTE_ flags: riscv-style keeps
     * RISC-V's R and X there.
     */
    public synchronized List<SoftBit> pteSoftBits() {
        if (softBits != null)
            return softBits;
        List<SoftBit> out = new ArrayList<>();
        java.util.regex.Pattern def = java.util.regex.Pattern.compile(
                "^#define\\s+(PTE_[A-Z0-9_]+)\\s+\\(1U?L?L?\\s*<<\\s*(\\d+)\\)\\s*(?://\\s*(.*))?$");
        for (String line : headerLines()) {
            java.util.regex.Matcher m = def.matcher(line.trim());
            int bit = m.matches() ? Integer.parseInt(m.group(2)) : -1;
            if (bit >= 9 && bit <= 11 && out.stream().noneMatch(b -> b.bit() == bit))
                out.add(new SoftBit(bit, m.group(1), m.group(3) == null ? "" : m.group(3).trim()));
        }
        return softBits = List.copyOf(out);
    }

    /**
     * Numeric #defines of the kernel's headers (SBRK_LAZY 2, O_CREATE
     * 0x200, ...), read once from kernel/**.h: the names the kernel's own
     * code gives to argument values.
     */
    public synchronized Map<String, Long> defines() {
        if (defines != null)
            return defines;
        Map<String, Long> out = new java.util.LinkedHashMap<>();
        java.util.regex.Pattern def = java.util.regex.Pattern.compile(
                "^#define\\s+([A-Z][A-Z0-9_]*)\\s+\\(?(0x[0-9a-fA-F]+|\\d+)\\)?\\s*(?:/[/*].*)?$");
        for (String line : headerLines()) {
            java.util.regex.Matcher m = def.matcher(line.trim());
            if (!m.matches())
                continue;
            String v = m.group(2);
            try {
                out.putIfAbsent(m.group(1), v.startsWith("0x")
                        ? Long.parseUnsignedLong(v.substring(2), 16) : Long.parseLong(v));
            } catch (NumberFormatException e) {
                /* wider than 64 bits: not a flag value */
            }
        }
        return defines = out;
    }

    private List<String> headerLines;

    /** Every line of the kernel's headers (kernel/**.h, in path order), read once; none if unreadable. */
    private synchronized List<String> headerLines() {
        if (headerLines != null)
            return headerLines;
        List<String> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(dir.resolve("kernel"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".h")).sorted().toList())
                out.addAll(Files.readAllLines(f));
        } catch (IOException e) {
            /* no names then */
        }
        return headerLines = out;
    }

    /**
     * True if the kernel code at pc is 32-bit (its .code32 entry code): the
     * CPU runs it in protected or compatibility mode, whatever EFER says.
     */
    public boolean isCode32(long pc) {
        Resolved r = resolve(pc, false, null);
        if (r == null || r.image() != kernel)
            return false;
        for (long[] c : kernel.code32())
            if (Long.compareUnsigned(r.linked(), c[0]) >= 0 && Long.compareUnsigned(r.linked(), c[1]) < 0)
                return true;
        return false;
    }

    private final Map<Program, rxviz.elf.Locals> locals = new java.util.concurrent.ConcurrentHashMap<>();

    /** The parameters and local variables of a program's functions (read on first use). */
    public rxviz.elf.Locals locals(Program p) {
        return locals.computeIfAbsent(p, x -> {
            try {
                return rxviz.elf.Locals.of(x.elf());
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }

    /** True if pc is compiled C in the kernel (a .c file, or a function inlined from a header), not assembly. */
    public boolean isC(long pc) {
        LineTable.Src src = source(pc, false, null);
        return src != null && !src.file().endsWith(".S");
    }

    /** A user program by name, or null. */
    public Program program(String name) {
        for (Program p : programs)
            if (p.name().equals(name))
                return p;
        return null;
    }

    /** The address pc is linked at in its image (aliases, the boot's physical entry resolved), or pc. */
    public long linked(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        return r == null ? pc : r.linked();
    }

    /** The kernel image as a Program. */
    public Program kernelProgram() {
        return kernel;
    }

    public int syscallVector() {
        return syscallVector;
    }

    public boolean isSyscallVector(int vector) {
        return vector == syscallVector;
    }

    public String syscallName(long number) {
        if (number > 0 && number < syscalls.size())
            return syscalls.get((int) number);
        return "#" + number;
    }

    /**
     * User code (CPL 3) runs from program; kernel code from the kernel
     * image, at its link address: code in an alias window (riscv-style's
     * trampoline) or at its load address (x86-style boot code before it
     * jumps high) is mapped to where it is linked. Null if unknown.
     */
    private Resolved resolve(long pc, boolean user, Program program) {
        if (user)
            return program == null ? null : new Resolved(program, pc);
        for (Alias al : aliases)
            if (Addr.in(pc, al.va, al.size))
                return new Resolved(kernel, al.target + (pc - al.va));
        long low = kernel.elf().linkedFromLoad(pc);
        return new Resolved(kernel, low == -1 ? pc : low);
    }

    /** True if pc is where a call instruction returns to. */
    public boolean isReturnAddress(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        return r != null && r.image().disas().followsCall(r.linked());
    }

    private static final Pattern DIRECT_JUMP =
            Pattern.compile("^j\\w*\\s+([0-9a-f]+)\\s+<");

    /**
     * The innermost loop around pc in its function, as compiled: a loop
     * is the target of backward direct jumps (jmp/jcc) up to the last of
     * them (a loop often has several: continue, the condition at the
     * bottom...). The smallest such [target, last jump] holding pc, as
     * runtime addresses {lo, hi}, or null.
     */
    public long[] loopAround(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return null;
        ElfFile elf = r.image().elf();
        long a = r.linked();
        ElfFile.Symbol fn = elf.symbolAt(a);
        if (fn == null)
            return null;
        Map<Long, Long> lastJump = new HashMap<>(); /* loop head -> last back-edge */
        for (var e : r.image().disas().between(fn.addr(), elf.symbolEnd(fn.addr()))) {
            Matcher m = DIRECT_JUMP.matcher(e.getValue().replace('\t', ' '));
            if (!m.find())
                continue;
            long target = Long.parseUnsignedLong(m.group(1), 16);
            if (Long.compareUnsigned(target, e.getKey()) <= 0)
                lastJump.merge(target, e.getKey(), (x, y) -> Long.compareUnsigned(x, y) >= 0 ? x : y);
        }
        long lo = 0, hi = 0;
        boolean found = false;
        for (var e : lastJump.entrySet()) {
            long head = e.getKey(), end = e.getValue();
            if (Long.compareUnsigned(head, a) > 0 || Long.compareUnsigned(a, end) > 0)
                continue;
            if (!found || Long.compareUnsigned(end - head, hi - lo) < 0) {
                lo = head;
                hi = end;
                found = true;
            }
        }
        long delta = pc - a;
        return found ? new long[] {lo + delta, hi + delta} : null;
    }

    /** Symbol, disassembly and source line for pc. */
    public Location locate(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return Location.UNKNOWN;
        long a = r.linked();
        ElfFile.Symbol s = r.image().elf().symbolAt(a);
        LineTable.Src src = r.image().lines().at(a);
        return new Location(r.image().name(), s == null ? null : s.name(),
                s == null ? 0 : a - s.addr(), user ? r.image().disas().at(a) : relocated(pc, r.image().disas().at(a)),
                src == null ? null : src.file(), src == null ? 0 : src.line());
    }

    /** Source line of pc (null if unknown). */
    public LineTable.Src source(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        return r == null ? null : r.image().lines().at(r.linked());
    }

    /** Function symbol at pc, or null. */
    /**
     * The function that called with return address ret: the one holding
     * the call (ret - 1). A call that never returns may be its function's
     * last instruction (main's call scheduler), so ret itself can be in the
     * next symbol.
     */
    public String callerOf(long ret, boolean user, Program program) {
        return ret == 0 ? null : function(ret - 1, user, program);
    }

    public String function(long pc, boolean user, Program program) {
        Resolved r = resolve(pc, user, program);
        ElfFile.Symbol s = r == null ? null : r.image().elf().symbolAt(r.linked());
        return s == null ? null : s.name();
    }

    /**
     * The first line at or after line in file that code comes from, in
     * the kernel or any user program (a blank line, a comment or a
     * function's name line has none); -1 if there is none.
     */
    public int codeLineAtOrAfter(String file, int line) {
        int best = -1;
        List<Program> images = new ArrayList<>(programs);
        images.add(kernel);
        for (Program p : images) {
            Integer l = p.lines().linesOf(file).ceiling(line);
            if (l != null && (best < 0 || l < best))
                best = l;
        }
        return best;
    }

    /** A kernel or user source file, by path relative to the kernel tree. */
    public Optional<Path> sourceFile(String rel) {
        Path p = dir.resolve(rel).normalize();
        if (!p.startsWith(dir) || !LineTable.isSource(p.getFileName().toString())
                || !Files.isRegularFile(p))
            return Optional.empty();
        return Optional.of(p);
    }

    /** The static disassembly around pc, listed at runtime addresses. */
    public List<Line> listing(long pc, boolean user, Program program, int before, int after) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return List.of();
        ElfFile elf = r.image().elf();
        long delta = pc - r.linked();
        List<Line> out = new ArrayList<>();
        for (var e : r.image().disas().around(r.linked(), before, after)) {
            ElfFile.Symbol s = elf.symbolAt(e.getKey());
            String fn = s != null && s.addr() == e.getKey() ? s.name() : null;
            out.add(new Line(e.getKey() + delta, relocated(pc, e.getValue()), fn));
        }
        return out;
    }

    private static final Pattern TARGET = Pattern.compile("\\b([0-9a-f]{4,16}) <");

    /**
     * Code that runs at an alias (riscv-style's trampoline at TRAMPOLINE):
     * objdump writes direct jump targets at the linked addresses; the CPU
     * jumps to the same place in the alias.
     */
    private String relocated(long pc, String text) {
        if (text == null)
            return null;
        for (Alias al : aliases) {
            if (!Addr.in(pc, al.va, al.size))
                continue;
            Matcher m = TARGET.matcher(text);
            StringBuilder b = new StringBuilder();
            while (m.find()) {
                long t = Long.parseUnsignedLong(m.group(1), 16);
                String rep = Addr.in(t, al.target, al.size)
                        ? Long.toHexString(al.va + (t - al.target)) + " <" : m.group();
                m.appendReplacement(b, Matcher.quoteReplacement(rep));
            }
            m.appendTail(b);
            return b.toString();
        }
        return text;
    }

    /**
     * The instructions of the function containing pc that come from one
     * source line: `line`, or pc's own line when line <= 0. A line can
     * compile to several separate pieces (loops); all of them are listed.
     */
    public List<Line> lineInstructions(long pc, boolean user, Program program, int line) {
        Resolved r = resolve(pc, user, program);
        if (r == null)
            return List.of();
        ElfFile elf = r.image().elf();
        LineTable lines = r.image().lines();
        LineTable.Src here = lines.at(r.linked());
        ElfFile.Symbol fn = elf.symbolAt(r.linked());
        if (here == null || fn == null)
            return List.of();
        int want = line > 0 ? line : here.line();
        long delta = pc - r.linked();
        List<Line> out = new ArrayList<>();
        for (var e : r.image().disas().between(fn.addr(), elf.symbolEnd(fn.addr()))) {
            LineTable.Src src = lines.at(e.getKey());
            if (src != null && src.line() == want && src.file().equals(here.file()))
                out.add(new Line(e.getKey() + delta, relocated(pc, e.getValue()), null));
        }
        return out;
    }
}

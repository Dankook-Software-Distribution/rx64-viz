package rxviz.index;

import static rxviz.elf.Addr.hex;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import rxviz.config.KernelProfile.Program;
import rxviz.elf.Locals;

/**
 * The parameters and local variables of the function running at a step,
 * with their values then: where DWARF says each one lives at this pc (a
 * register, memory at a register or frame-base offset, a constant, the
 * value a register had when the function was called), read from the
 * recorded registers and memory. Nothing is guessed: a variable with no
 * location here is "optimized out here", one whose expression is not
 * understood is "unknown here". A variable in memory that no store has
 * written since the function was called is "not assigned yet" (what it
 * holds is left over). Structs show their fields, arrays their elements,
 * pointers to structs the fields they point at; pointers are named like
 * the Names view.
 */
public final class Variables {

    /**
     * where: the register or address it is read from, or why there is no
     * value; unset: in memory no store has written since the call began;
     * fields: a struct's members (or a pointed-to struct's), else null;
     * dwarf: the location expression, when it was not understood; table:
     * for a pagetable_t, the physical address of the table it points at.
     */
    public record Value(String name, String type, boolean param, int line, String value, String where,
                        String points, boolean unset, List<Value> fields, String dwarf, String table) {
    }

    public record View(int step, String function, List<Value> vars) {
    }

    private static final Pattern REG = Pattern.compile("^DW_OP_reg\\d+ \\((\\w+)\\)$");
    private static final Pattern BREG = Pattern.compile("^DW_OP_breg\\d+ \\((\\w+)\\): (-?\\d+)$");
    private static final Pattern FBREG = Pattern.compile("^DW_OP_fbreg: (-?\\d+)$");
    private static final Pattern ADDR = Pattern.compile("^DW_OP_addr: ([0-9a-f]+)$");
    private static final Pattern LIT = Pattern.compile("^DW_OP_(?:lit(\\d+)|const\\w*: (-?\\d+))$");
    private static final Pattern ENTRY = Pattern.compile("^DW_OP_(?:GNU_)?entry_value: \\(DW_OP_reg\\d+ \\((\\w+)\\)\\)$");
    private static final int MAX_FIELDS = 40;
    private static final int MAX_ELEMS = 32;

    private final Recording r;
    private final int step;
    private final Recording.View v;
    private final long pc;
    private Names names; /* for naming pointers, made once */
    private Locals loc;
    /* the step the running function was called at (-1: before the recording) */
    private int entry = -1;
    /* variables in memory, for the one scan that finds which no store wrote since the call */
    private final List<long[]> inMemory = new ArrayList<>(); /* {index in out, pa, size} */
    private int current; /* index of the variable being evaluated */

    /** Longest stretch since the call scanned for the stores to its variables. */
    private static final int MAX_UNSET_SCAN = 4 << 20;

    /** What every Value of one variable (or field) shares. */
    private record Of(String name, String type, boolean param, int line) {
        Value value(String value, String where) {
            return new Value(name, type, param, line, value, where, null, false, null, null, null);
        }
    }

    private Variables(Recording r, int step) {
        this.r = r;
        this.step = step;
        this.v = r.view(step);
        this.pc = v.insn().vaddr();
    }

    static View at(Recording r, int step) {
        return new Variables(r, step).view();
    }

    private View view() {
        var kernel = r.kernel();
        boolean user = v.cpl() == 3;
        Program prog = user ? v.program() : kernel.kernelProgram();
        if (prog == null)
            return new View(step, null, List.of());
        /* the address the code is linked at (aliases, boot's physical entry) */
        long linked = kernel.linked(pc, user, prog);
        loc = kernel.locals(prog);
        Locals.Func f = loc.functionAt(linked);
        if (f == null)
            return new View(step, null, List.of());
        entry = entryStep(f.name());
        Locals.Expr fbe = Locals.at(f.frameBase(), f.frameBaseList(), linked);
        Long fb = fbe == null ? null : address(fbe.text());
        List<Value> out = new ArrayList<>();
        for (Locals.Var x : f.vars()) {
            Of o = new Of(x.name(), loc.typeName(x.type()), x.param(), x.line());
            Locals.Expr e = Locals.at(x.fixed(), x.list(), linked);
            current = out.size();
            out.add(e == null ? o.value(null, "optimized out here") : eval(o, x.type(), e.text(), fb));
        }
        markUnset(out);
        return new View(step, f.name(), out);
    }

    /**
     * One scan of the stores since the call: a variable in memory none of
     * them wrote is "not assigned yet" (its value is left over; what it
     * points at is not named). Not decided past MAX_UNSET_SCAN steps.
     */
    private void markUnset(List<Value> out) {
        if (entry < 0 || inMemory.isEmpty() || step - entry > MAX_UNSET_SCAN)
            return;
        boolean[] written = new boolean[inMemory.size()];
        r.scanStores(entry, step, (s, pa, size, value) -> {
            for (int k = 0; k < written.length; k++) {
                long[] m = inMemory.get(k);
                if (!written[k] && m[1] >= 0 && pa < m[1] + m[2] && pa + size > m[1])
                    written[k] = true;
            }
        });
        for (int k = 0; k < written.length; k++) {
            long[] m = inMemory.get(k);
            /* unmapped, or across a page (its frames need not be contiguous): not decided */
            if (written[k] || m[1] < 0 || (m[1] & 0xfff) + m[2] > 4096)
                continue;
            Value x = out.get((int) m[0]);
            out.set((int) m[0], new Value(x.name(), x.type(), x.param(), x.line(), x.value(), x.where(),
                    null, true, null, null, null));
        }
    }

    /** The step the innermost call of fn on the cursor's path began (-1: before the recording). */
    private int entryStep(String fn) {
        Tree t = r.tree();
        for (int n = t.innermost(step); n >= 0 && t.kind(n) != Tree.RUN; n = t.parent(n)) {
            if (t.kind(n) != Tree.FUNC || !fn.equals(t.label(n)))
                continue;
            int o = t.origin(n);
            return o < 0 || (t.flags(o) & Tree.EARLIER) != 0 ? -1 : t.start(o);
        }
        return -1;
    }

    private String nameOf(long va) {
        if (names == null)
            names = Names.of(r, step);
        return names.name(va);
    }

    private long reg(String name) {
        int k = r.regIndex(name);
        if (k < 0)
            throw new IllegalArgumentException(name);
        return v.regs()[k];
    }

    /** A frame base expression: an address (breg + offset). null if not understood. */
    private Long address(String expr) {
        Matcher m = BREG.matcher(expr.trim());
        if (!m.matches())
            return null;
        try {
            return reg(m.group(1)) + Long.parseLong(m.group(2));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private Value eval(Of o, long type, String expr, Long fb) {
        String[] ops = expr.split(";\\s*");
        String op = ops[0].trim();
        boolean stackValue = ops.length > 1 && ops[ops.length - 1].trim().equals("DW_OP_stack_value");
        try {
            Matcher m;
            if ((m = REG.matcher(op)).matches() && ops.length == 1)
                return value(o, type, reg(m.group(1)), m.group(1));
            if ((m = BREG.matcher(op)).matches() && (ops.length == 1 || (stackValue && ops.length == 2))) {
                long off = Long.parseLong(m.group(2));
                long a = reg(m.group(1)) + off;
                String where = m.group(1) + signed(off);
                return stackValue ? value(o, type, a, where) : memory(o, type, a, where, true);
            }
            if ((m = FBREG.matcher(op)).matches() && ops.length == 1 && fb != null) {
                long off = Long.parseLong(m.group(1));
                return memory(o, type, fb + off, "frame base" + signed(off), true);
            }
            if ((m = ADDR.matcher(op)).matches() && ops.length == 1)
                return memory(o, type, Long.parseUnsignedLong(m.group(1), 16), "static", true);
            if ((m = LIT.matcher(op)).matches() && stackValue && ops.length == 2) {
                long c = m.group(1) != null ? Long.parseLong(m.group(1)) : Long.parseLong(m.group(2));
                return value(o, type, c, "a constant here");
            }
            if ((m = ENTRY.matcher(op)).matches() && stackValue && ops.length == 2) {
                /* the value a register had when the function was called: recorded */
                if (entry < 0)
                    return unknown(o, "unknown here: it is " + m.group(1) + " as it was when "
                            + "the function was called, before the recording", expr);
                long at = r.registers(entry)[r.regIndex(m.group(1))];
                return value(o, type, at, m.group(1) + " when called (step " + entry + ")");
            }
        } catch (IllegalArgumentException e) {
            /* a register the trace does not have */
        }
        return unknown(o, "unknown here", expr);
    }

    private static Value unknown(Of o, String why, String expr) {
        return new Value(o.name(), o.type(), o.param(), o.line(), null, why, null, false, null, expr, null);
    }

    private static String signed(long off) {
        return off == 0 ? "" : off > 0 ? " + " + off : " \u2212 " + (-off);
    }

    /** A variable (top: not a field) in memory at a. */
    private Value memory(Of o, long type, long a, String where, boolean top) {
        Locals.Type t = loc.resolve(type);
        String w = "memory " + hex(a) + (where == null ? "" : " (" + where + ")");
        String kind = t == null ? "base" : t.kind();
        if (top && (kind.equals("struct") || kind.equals("union") || kind.equals("array")))
            inMemory.add(new long[] {current, r.virtual(step, vm -> vm.pa(a)), Math.max(1, loc.sizeOf(type))});
        if (kind.equals("struct") || kind.equals("union"))
            return new Value(o.name(), o.type(), o.param(), o.line(), "{…}", w, nameOf(a), false,
                    top ? fields(t, a) : null, null, null);
        if (kind.equals("array")) {
            String text = elements(t, a);
            return new Value(o.name(), o.type(), o.param(), o.line(), text != null ? text : "at " + hex(a), w,
                    nameOf(a), false, null, null, null);
        }
        long size = loc.sizeOf(type);
        int n = (int) Math.max(1, Math.min(8, size < 0 ? 8 : size));
        Long val = r.virtual(step, vm -> vm.read(a, n));
        if (top && val != null)
            inMemory.add(new long[] {current, r.virtual(step, vm -> vm.pa(a)), Math.max(1, size)});
        return val == null ? o.value(null, w + ", not mapped") : value(o, type, val, w);
    }

    /** A struct's members at a, one level deep. */
    private List<Value> fields(Locals.Type t, long a) {
        List<Value> out = new ArrayList<>();
        for (Locals.Member m : t.members()) {
            if (out.size() == MAX_FIELDS)
                break;
            out.add(memory(new Of(m.name(), loc.typeName(m.type()), false, 0), m.type(), a + m.offset(), null, false));
        }
        return out;
    }

    /** An array's elements ({3, 4}, or a char array as a string), or null if too big or not readable. */
    private String elements(Locals.Type t, long a) {
        Locals.Type e = loc.resolve(t.target());
        long es = loc.sizeOf(t.target());
        if (e == null || es <= 0 || es > 8 || t.dims().size() != 1 || t.dims().getFirst() <= 0)
            return null;
        long count = t.dims().getFirst();
        boolean chars = es == 1 && e.name() != null && e.name().contains("char");
        int n = (int) Math.min(count, chars ? 64 : MAX_ELEMS);
        long[] vals = r.virtual(step, vm -> {
            long[] x = new long[n];
            for (int i = 0; i < n; i++) {
                Long b = vm.read(a + i * es, (int) es);
                if (b == null)
                    return null;
                x[i] = b;
            }
            return x;
        });
        if (vals == null)
            return null;
        StringBuilder b = new StringBuilder();
        if (chars) {
            b.append('"');
            for (long c : vals) {
                if (c == 0)
                    break;
                b.append(c >= 32 && c < 127 ? String.valueOf((char) c) : String.format("\\x%02x", c));
            }
            return b.append('"').toString();
        }
        b.append('{');
        for (int i = 0; i < n; i++) {
            if (i > 0)
                b.append(", ");
            b.append(number(e, (int) es, vals[i]));
        }
        if (count > n)
            b.append(", …");
        return b.append('}').toString();
    }

    private Value value(Of o, long type, long raw, String where) {
        Locals.Type t = loc.resolve(type);
        long sz = loc.sizeOf(type);
        int size = (int) Math.max(1, Math.min(8, sz < 0 ? 8 : sz));
        long mask = size >= 8 ? -1L : (1L << (8 * size)) - 1;
        long u = raw & mask;
        String kind = t == null ? "base" : t.kind();
        if (kind.equals("pointer")) {
            /* a pointer to a struct: the fields it points at */
            Locals.Type to = t.target() < 0 ? null : loc.resolve(t.target());
            List<Value> fields = u != 0 && to != null && (to.kind().equals("struct") || to.kind().equals("union"))
                    && !to.members().isEmpty() && r.virtual(step, vm -> vm.read(u, 1)) != null ? fields(to, u) : null;
            /* a page table: where it is, for Memory to show it */
            String table = null;
            if (u != 0 && o.type().equals("pagetable_t")) {
                long pa = r.virtual(step, vm -> vm.pa(u));
                table = pa >= 0 && (pa & 0xfff) == 0 ? hex(pa) : null;
            }
            return new Value(o.name(), o.type(), o.param(), o.line(), hex(u), where, u != 0 ? nameOf(u) : null,
                    false, fields, null, table);
        }
        return o.value(number(t, size, u), where);
    }

    /** A number as its C type reads it; 8-byte unsigned ones (addresses, sizes) in hex first. */
    private static String number(Locals.Type t, int size, long u) {
        String kind = t == null ? "base" : t.kind();
        String name = t == null || t.name() == null ? "" : t.name();
        if (kind.equals("pointer"))
            return hex(u);
        if (kind.equals("enum"))
            return Long.toString(u);
        if (name.contains("unsigned") || name.startsWith("uint") || name.equals("_Bool")) {
            if (size >= 8 && Long.compareUnsigned(u, 9) > 0)
                return hex(u) + (Long.compareUnsigned(u, 1L << 32) < 0 ? " (" + Long.toUnsignedString(u) + ")" : "");
            return Long.toUnsignedString(u) + (Long.compareUnsigned(u, 0xffff) > 0 ? " (" + hex(u) + ")" : "");
        }
        if (name.equals("char") || name.equals("signed char")) {
            long c = (u << 56) >> 56;
            return c >= 32 && c < 127 ? c + " '" + (char) c + "'" : Long.toString(c);
        }
        return Long.toString(size >= 8 ? u : (u << (64 - 8 * size)) >> (64 - 8 * size));
    }
}

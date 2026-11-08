package rxviz.elf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parameters and local variables of the functions of an ELF file, and
 * where each one lives at a given pc, from its DWARF (objdump
 * --dwarf=info and --dwarf=loc). The kernels are built with -O -gdwarf-2:
 * a variable is in a register, at an offset from a register or the frame
 * base, or nowhere ("optimized out") for part of its function; location
 * lists say which, address range by address range.
 *
 * Only what DWARF says is used; an expression this reader does not know
 * makes the variable "unknown here", never a guess. Variables of inlined
 * functions are not listed (their caller's are).
 */
public final class Locals {

    /** A DWARF location expression, as objdump prints it ("DW_OP_breg6 (rbp): -20"). */
    public record Expr(String text) {
    }

    /** [lo, hi): where the expression holds. */
    public record Range(long lo, long hi, Expr expr) {
    }

    /** A variable or parameter: one fixed expression, or a list of ranges. */
    public record Var(String name, boolean param, int line, long type, Expr fixed, List<Range> list) {
    }

    /** A function with code: [lo, hi), its frame base, its variables in declaration order. */
    public record Func(String name, long lo, long hi, Expr frameBase, List<Range> frameBaseList,
                       List<Var> vars) {
    }

    /**
     * A type: kind (base, pointer, struct, union, enum, array, typedef,
     * const, volatile, other); an array's dimensions (-1: unknown); a
     * struct's or union's members.
     */
    public record Type(String kind, String name, long size, long target, List<Long> dims, List<Member> members) {
    }

    /** A member of a struct or union: name, byte offset, type. */
    public record Member(String name, long offset, long type) {
    }

    private final List<Func> funcs = new ArrayList<>();
    private final Map<Long, Type> types = new HashMap<>();

    private static final Pattern DIE = Pattern.compile("^\\s*<(\\d+)><([0-9a-f]+)>: Abbrev Number: \\d+ \\((DW_TAG_\\w+)\\)");
    private static final Pattern ATTR = Pattern.compile("^\\s*<[0-9a-f]+>\\s+(DW_AT_\\w+)\\s*: (.*)$");
    /* objdump prints offset 0 as "0", the others as "0x4c" */
    private static final Pattern LOCLIST = Pattern.compile("^(?:0x)?([0-9a-f]+) \\(location list\\)");
    private static final Pattern BLOCK = Pattern.compile("^\\d+ byte block: [0-9a-f ]*\\t?\\((.*)\\)\\s*$");
    private static final Pattern REF = Pattern.compile("^<0x([0-9a-f]+)>");
    private static final Pattern UCONST = Pattern.compile("DW_OP_plus_uconst: (\\d+)");
    private static final Pattern LOC_ENTRY = Pattern.compile(
            "^\\s*(?:([0-9a-f]{8}) )?([0-9a-f]{16}) ([0-9a-f]{16}) \\((.*)\\)\\s*$");
    private static final Pattern LOC_VIEWS = Pattern.compile("^\\s*([0-9a-f]{8}) v[0-9a-f]+ v[0-9a-f]+ views at [0-9a-f]+ for:");
    private static final Pattern LOC_END = Pattern.compile("^\\s*([0-9a-f]{8}) <End of list>");

    private Locals() {
    }

    public static Locals of(ElfFile elf) throws IOException {
        Locals l = new Locals();
        Map<Long, List<Range>> lists = l.readLocationLists(elf);
        l.readInfo(elf, lists);
        return l;
    }

    /* ---------------- .debug_loc ---------------- */

    private Map<Long, List<Range>> readLocationLists(ElfFile elf) throws IOException {
        Map<Long, List<Range>> out = new HashMap<>();
        long[] cur = {-1};
        long[] pendingOffset = {-1};
        Objdump.run(elf.path(), line -> {
            Matcher end = LOC_END.matcher(line);
            if (end.find()) {
                cur[0] = -1;
                return;
            }
            Matcher views = LOC_VIEWS.matcher(line);
            if (views.find()) {
                pendingOffset[0] = Long.parseLong(views.group(1), 16);
                return;
            }
            Matcher e = LOC_ENTRY.matcher(line);
            if (!e.find())
                return;
            long off = e.group(1) != null ? Long.parseLong(e.group(1), 16) : pendingOffset[0];
            pendingOffset[0] = -1;
            if (cur[0] < 0) {
                cur[0] = off;
                out.put(off, new ArrayList<>());
            }
            out.get(cur[0]).add(new Range(Long.parseUnsignedLong(e.group(2), 16),
                    Long.parseUnsignedLong(e.group(3), 16), new Expr(e.group(4))));
        }, "--dwarf=loc");
        return out;
    }

    /* ---------------- .debug_info ---------------- */

    private void readInfo(ElfFile elf, Map<Long, List<Range>> lists) throws IOException {
        /* the DIE being read, and the subprogram (with code) we are inside */
        String[] tag = {null};
        long[] off = {0};
        int[] depth = {0};
        Map<String, String> attrs = new HashMap<>();
        Object[] func = {null};       /* Func under construction */
        int[] funcDepth = {-1};
        int[] skipDepth = {-1};       /* inside an inlined subroutine: not ours */
        long[] agg = {-1, -1};        /* the struct/union whose members follow: offset, depth */
        long[] arr = {-1, -1};        /* the array whose subranges follow: offset, depth */
        Runnable flush = () -> {
            if (tag[0] == null)
                return;
            finish(tag[0], off[0], depth[0], attrs, lists, func, funcDepth, skipDepth, agg, arr);
            attrs.clear();
            tag[0] = null;
        };
        Objdump.run(elf.path(), line -> {
            Matcher d = DIE.matcher(line);
            if (d.find()) {
                flush.run();
                tag[0] = d.group(3);
                depth[0] = Integer.parseInt(d.group(1));
                off[0] = Long.parseLong(d.group(2), 16);
                return;
            }
            if (line.contains("Abbrev Number: 0")) {
                flush.run();
                return;
            }
            Matcher a = ATTR.matcher(line);
            if (a.find() && tag[0] != null)
                attrs.put(a.group(1), a.group(2).trim());
        }, "--dwarf=info");
        flush.run();
        if (func[0] != null)
            funcs.add((Func) func[0]);
        funcs.sort((x, y) -> Long.compareUnsigned(x.lo(), y.lo()));
    }

    private void finish(String tag, long off, int depth, Map<String, String> attrs,
                        Map<Long, List<Range>> lists, Object[] func, int[] funcDepth, int[] skipDepth,
                        long[] agg, long[] arr) {
        if (agg[0] >= 0 && depth <= agg[1])
            agg[0] = -1;
        if (arr[0] >= 0 && depth <= arr[1])
            arr[0] = -1;
        /* leaving the current function or inlined subroutine */
        if (skipDepth[0] >= 0 && depth <= skipDepth[0])
            skipDepth[0] = -1;
        if (func[0] != null && depth <= funcDepth[0]) {
            funcs.add((Func) func[0]);
            func[0] = null;
            funcDepth[0] = -1;
        }
        switch (tag) {
            case "DW_TAG_base_type", "DW_TAG_pointer_type", "DW_TAG_structure_type", "DW_TAG_union_type",
                 "DW_TAG_enumeration_type", "DW_TAG_array_type", "DW_TAG_typedef", "DW_TAG_const_type",
                 "DW_TAG_volatile_type" -> {
                String kind = tag.substring("DW_TAG_".length()).replace("_type", "")
                        .replace("structure", "struct");
                String bs = attrs.get("DW_AT_byte_size");
                types.put(off, new Type(kind, name(attrs), bs == null ? -1 : num(bs), ref(attrs.get("DW_AT_type")),
                        new ArrayList<>(), new ArrayList<>()));
                if (kind.equals("struct") || kind.equals("union")) {
                    agg[0] = off;
                    agg[1] = depth;
                } else if (kind.equals("array")) {
                    arr[0] = off;
                    arr[1] = depth;
                }
            }
            case "DW_TAG_member" -> {
                Type a = agg[0] >= 0 && depth == agg[1] + 1 ? types.get(agg[0]) : null;
                String n = name(attrs);
                if (a != null && n != null) {
                    String loc = attrs.get("DW_AT_data_member_location");
                    long mo = 0;
                    if (loc != null) {
                        Matcher u = UCONST.matcher(loc);
                        mo = u.find() ? Long.parseLong(u.group(1)) : num(loc);
                    }
                    a.members().add(new Member(n, mo, ref(attrs.get("DW_AT_type"))));
                }
            }
            case "DW_TAG_subrange_type" -> {
                Type a = arr[0] >= 0 && depth == arr[1] + 1 ? types.get(arr[0]) : null;
                if (a != null) {
                    String ub = attrs.get("DW_AT_upper_bound"), c = attrs.get("DW_AT_count");
                    a.dims().add(ub != null ? num(ub) + 1 : c != null ? num(c) : -1L);
                }
            }
            case "DW_TAG_subprogram" -> {
                String lo = attrs.get("DW_AT_low_pc"), hi = attrs.get("DW_AT_high_pc");
                if (lo == null || hi == null)
                    return;
                long l = num(lo), h = num(hi);
                if (Long.compareUnsigned(h, l) <= 0)
                    h = l + h; /* DWARF 4+: a length, not an address */
                Expr fb = fixed(attrs.get("DW_AT_frame_base"));
                List<Range> fbl = list(attrs.get("DW_AT_frame_base"), lists);
                func[0] = new Func(name(attrs), l, h, fb, fbl, new ArrayList<>());
                funcDepth[0] = depth;
            }
            case "DW_TAG_inlined_subroutine" -> {
                if (skipDepth[0] < 0)
                    skipDepth[0] = depth;
            }
            case "DW_TAG_variable", "DW_TAG_formal_parameter" -> {
                if (func[0] == null || skipDepth[0] >= 0 || depth <= funcDepth[0])
                    return;
                String n = name(attrs);
                String loc = attrs.get("DW_AT_location");
                if (n == null)
                    return;
                Func f = (Func) func[0];
                f.vars().add(new Var(n, tag.equals("DW_TAG_formal_parameter"),
                        (int) num(attrs.get("DW_AT_decl_line")), ref(attrs.get("DW_AT_type")),
                        fixed(loc), list(loc, lists)));
            }
            default -> { }
        }
    }

    private static String name(Map<String, String> attrs) {
        String n = attrs.get("DW_AT_name");
        if (n == null)
            return null;
        int i = n.lastIndexOf("): ");
        return i >= 0 ? n.substring(i + 3) : n;
    }

    private static long num(String s) {
        if (s == null)
            return 0;
        s = s.trim().split("\\s+")[0];
        try {
            return s.startsWith("0x") ? Long.parseUnsignedLong(s.substring(2), 16) : Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static long ref(String s) {
        if (s == null)
            return -1;
        Matcher m = REF.matcher(s);
        return m.find() ? Long.parseLong(m.group(1), 16) : -1;
    }

    private static Expr fixed(String s) {
        if (s == null)
            return null;
        Matcher m = BLOCK.matcher(s);
        return m.find() ? new Expr(m.group(1)) : null;
    }

    private static List<Range> list(String s, Map<Long, List<Range>> lists) {
        if (s == null)
            return null;
        Matcher m = LOCLIST.matcher(s);
        return m.find() ? lists.getOrDefault(Long.parseLong(m.group(1), 16), List.of()) : null;
    }

    /* ---------------- lookups ---------------- */

    /** The function whose code holds pc, or null. */
    public Func functionAt(long pc) {
        /* sorted by lo: the last one starting at or before pc */
        int lo = 0, hi = funcs.size() - 1, at = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (Long.compareUnsigned(funcs.get(mid).lo(), pc) <= 0) {
                at = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        if (at < 0)
            return null;
        Func f = funcs.get(at);
        return Long.compareUnsigned(pc, f.hi()) < 0 ? f : null;
    }

    /** The expression for v (or a frame base) at pc; null: none here (optimized out). */
    public static Expr at(Expr fixed, List<Range> list, long pc) {
        if (fixed != null)
            return fixed;
        if (list == null)
            return null;
        for (Range r : list)
            if (Long.compareUnsigned(pc, r.lo()) >= 0 && Long.compareUnsigned(pc, r.hi()) < 0)
                return r.expr();
        return null;
    }

    /** "int", "struct proc *", "char [16]"... */
    public String typeName(long off) {
        Type t = types.get(off);
        for (int guard = 0; guard < 16 && t != null; guard++) {
            switch (t.kind()) {
                case "pointer" -> {
                    return (t.target() < 0 ? "void" : typeName(t.target())) + " *";
                }
                case "const", "volatile" -> t = types.get(t.target());
                case "struct", "union", "enum" -> {
                    return t.kind() + " " + (t.name() == null ? "?" : t.name());
                }
                case "array" -> {
                    StringBuilder b = new StringBuilder(typeName(t.target())).append(' ');
                    for (long d : t.dims())
                        b.append('[').append(d < 0 ? "" : Long.toString(d)).append(']');
                    return t.dims().isEmpty() ? b + "[]" : b.toString();
                }
                default -> {
                    return t.name() != null ? t.name() : t.kind();
                }
            }
        }
        return t == null ? "void" : "?";
    }

    /** The byte size of the type at off (an array: its elements times its dimensions), or -1. */
    public long sizeOf(long off) {
        Type t = resolve(off);
        if (t == null)
            return -1;
        if (t.kind().equals("pointer") && t.size() < 0)
            return 8;
        if (!t.kind().equals("array"))
            return t.size();
        long n = sizeOf(t.target());
        for (long d : t.dims())
            n = d < 0 || n < 0 ? -1 : n * d;
        return n;
    }

    /** The type after typedefs and qualifiers. */
    public Type resolve(long off) {
        Type t = types.get(off);
        for (int guard = 0; guard < 16 && t != null
                && (t.kind().equals("typedef") || t.kind().equals("const") || t.kind().equals("volatile")); guard++)
            t = types.get(t.target());
        return t;
    }
}

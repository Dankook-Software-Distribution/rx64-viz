package rxviz.elf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Types from an ELF's DWARF (objdump --dwarf=info): struct and union
 * layouts with each member's type, arrays with their dimensions, and the
 * types of file-scope variables, so memory can be labeled with the
 * kernel's own names (proc[2].trapframe, syscalls[16], cpus[0].gdt[5]).
 */
public final class Structs {

    /** A member: its name, byte offset and type (a DIE offset, -1: none). */
    public record Member(String name, long offset, long type) {
    }

    public record Struct(String name, long size, List<Member> members) {

        /** The member that holds byte offset off (the last one starting at or before it), or null. */
        public Member memberAt(long off) {
            Member best = null;
            for (Member m : members)
                if (m.offset() <= off && (best == null || m.offset() >= best.offset()))
                    best = m;
            return off < size ? best : null;
        }

        public Member member(String name) {
            for (Member m : members)
                if (m.name().equals(name))
                    return m;
            return null;
        }
    }

    /**
     * A type DIE: kind (base, pointer, struct, union, enum, array, typedef,
     * const, volatile, other), name, byte size (-1: none given), the type
     * it refers to (-1: none) and, for an array, its dimensions (-1: unknown).
     */
    private record Type(String kind, String name, long size, long target, List<Long> dims) {
    }

    private static final Pattern DIE = Pattern.compile("^\\s*<(\\d+)><([0-9a-f]+)>: Abbrev Number: \\d+ \\((\\w+)\\)");
    private static final Pattern REF = Pattern.compile("<0x([0-9a-f]+)>");
    private static final Pattern ATTR = Pattern.compile("^\\s*<[0-9a-f]+>\\s+(DW_AT_\\w+)\\s*: (.*)$");
    private static final Pattern UCONST = Pattern.compile("DW_OP_plus_uconst: (\\d+)");
    private static final int MAX_DEPTH = 16;

    private final Map<String, Struct> byName = new HashMap<>();
    private final Map<Long, Struct> byOffset = new HashMap<>();
    private final Map<Long, Type> types = new HashMap<>();
    private final Map<String, Long> varType = new HashMap<>();
    /* every enumeration, by each of its enumerators' names */
    private final Map<String, Map<Long, String>> enums = new HashMap<>();

    private Structs() {
    }

    public static Structs of(ElfFile elf) throws IOException {
        Structs s = new Structs();
        s.parse(elf);
        return s;
    }

    /** The named struct (first complete definition), or null. */
    public Struct get(String name) {
        return byName.get(name);
    }

    /** The struct type of a file-scope variable (e.g. kmem's anonymous struct), or null. */
    public Struct ofVariable(String name) {
        Long t = varType.get(name);
        return t == null ? null : struct(t);
    }

    /** The element struct of a file-scope array of structs (cpus -> struct cpu), or null. */
    public Struct elementOf(String name) {
        Long t = varType.get(name);
        Type a = t == null ? null : resolve(t);
        return a == null || !a.kind().equals("array") ? null : struct(a.target());
    }

    /**
     * Like path(), for the start of a table: a struct variable's first
     * member when off is 0 (cpuentry -> cpuentry.gdt).
     */
    public String tablePath(String var, long off) {
        Struct t = ofVariable(var);
        Member m = off == 0 && t != null ? t.memberAt(0) : null;
        return m != null ? var + "." + m.name() : path(var, off);
    }

    /**
     * Where byte off of variable var is, in C, following the types DWARF
     * gives: "cpus[0].gdt[5]", "bcache.buf[3].lock", "syscalls[16]",
     * "ticks". It stops at a pointer or a scalar (proc[2].trapframe); what
     * is left is added as "+0x..".
     */
    public String path(String var, long off) {
        return path(var, off, 0);
    }

    /**
     * Like path(), for an access of size bytes (0: an address, not an
     * access): it goes on into members at offset 0 while they are bigger
     * than the access (a 4-byte store at &kmem is kmem.lock.locked).
     */
    public String path(String var, long off, int size) {
        StringBuilder b = new StringBuilder(var);
        Long ty = varType.get(var);
        for (int guard = 0; ty != null && guard < MAX_DEPTH
                && (off != 0 || (size > 0 && size(ty) > size)); guard++) {
            Type t = resolve(ty);
            if (t == null)
                break;
            if (t.kind().equals("array")) {
                long stride = size(t.target());
                for (int k = t.dims().size() - 1; k > 0; k--)
                    stride *= t.dims().get(k);
                if (stride <= 0)
                    break;
                long[] idx = new long[t.dims().size()];
                for (int k = 0; k < idx.length; k++) {
                    idx[k] = off / stride;
                    off %= stride;
                    if (k + 1 < idx.length)
                        stride /= t.dims().get(k + 1);
                }
                if (t.dims().getFirst() >= 0 && idx[0] >= t.dims().getFirst())
                    break; /* past the array's end */
                for (long x : idx)
                    b.append('[').append(x).append(']');
                ty = t.target();
            } else if (t.kind().equals("struct") || t.kind().equals("union")) {
                Struct st = struct(ty);
                Member m = st == null ? null : st.memberAt(off);
                if (m == null)
                    break;
                b.append('.').append(m.name());
                off -= m.offset();
                ty = m.type() < 0 ? null : m.type();
            } else {
                break; /* a pointer, a number: not inside */
            }
        }
        /* at the first byte of an array member of structs: its first one
         * (a sleep channel &bcache.buf[0]); a table of numbers stays the
         * table (cpus[0].gdt) */
        Type last = ty == null || b.length() == var.length() ? null : resolve(ty);
        if (off == 0 && last != null && last.kind().equals("array") && struct(last.target()) != null)
            b.append("[0]");
        if (off != 0)
            b.append("+0x").append(Long.toHexString(off));
        return b.toString();
    }

    /** The enumeration that has an enumerator named enumerator (value -> name), or null. */
    public Map<Long, String> enumWith(String enumerator) {
        return enums.get(enumerator);
    }

    /** The DIE offset of the type at off after typedefs and qualifiers (-1: none). */
    private long resolved(long off) {
        for (int guard = 0; guard < MAX_DEPTH && off >= 0; guard++) {
            Type t = types.get(off);
            if (t == null || !(t.kind().equals("typedef") || t.kind().equals("const")
                    || t.kind().equals("volatile")))
                return off;
            off = t.target();
        }
        return -1;
    }

    /** The type at DIE offset off after typedefs and qualifiers, or null. */
    private Type resolve(long off) {
        return types.get(resolved(off));
    }

    /** The struct or union at DIE offset off (a declaration: its definition, by name), or null. */
    private Struct struct(long off) {
        long at = resolved(off);
        Struct st = byOffset.get(at);
        Type t = types.get(at);
        if (st != null || t == null)
            return st;
        return t.name() != null && t.kind().equals("struct") ? byName.get(t.name()) : null;
    }

    /** The byte size of the type at DIE offset off, or -1. */
    private long size(long off) {
        Type t = resolve(off);
        if (t == null)
            return -1;
        if (t.kind().equals("array")) {
            long n = size(t.target());
            for (long d : t.dims())
                n = d < 0 || n < 0 ? -1 : n * d;
            return n;
        }
        if (t.size() < 0 && t.kind().equals("struct")) {
            Struct st = struct(off);
            return st == null ? -1 : st.size();
        }
        return t.size();
    }

    private void parse(ElfFile elf) throws IOException {
        Parser p = new Parser();
        Objdump.run(elf.path(), p::line, "--dwarf=info");
        p.flush();
        p.closeAggregates(0);
    }

    /**
     * Reads objdump's DIEs one at a time (a DIE's attributes, then on the
     * next DIE line, what it is): types, members of the struct or union
     * being read, an array's subranges, enumerators, file-scope variables.
     */
    private final class Parser {
        String tag;
        int depth;
        long off;
        final Map<String, String> attrs = new HashMap<>();
        /* open structs/unions (DIE offset, depth, members), innermost last */
        final List<long[]> aggregates = new ArrayList<>();
        final List<List<Member>> aggMembers = new ArrayList<>();
        /* the array whose subranges follow, and its depth */
        List<Long> dims;
        int arrayDepth = -1;
        /* the enumeration being read */
        Map<Long, String> enumValues;
        int enumDepth = -1;

        void line(String line) {
            Matcher d = DIE.matcher(line);
            if (d.find()) {
                flush();
                tag = d.group(3);
                depth = Integer.parseInt(d.group(1));
                off = Long.parseLong(d.group(2), 16);
                return;
            }
            Matcher a = ATTR.matcher(line);
            if (a.find() && tag != null)
                attrs.put(a.group(1), a.group(2).trim());
        }

        void closeAggregates(int depth) {
            while (!aggregates.isEmpty() && aggregates.getLast()[1] >= depth) {
                long[] agg = aggregates.removeLast();
                List<Member> ms = aggMembers.removeLast();
                Type t = types.get(agg[0]);
                if (t == null || t.size() <= 0 || ms.isEmpty())
                    continue;
                Struct st = new Struct(t.name(), t.size(), List.copyOf(ms));
                byOffset.put(agg[0], st);
                if (t.name() != null && t.kind().equals("struct"))
                    byName.putIfAbsent(t.name(), st);
            }
            if (enumDepth >= depth && enumValues != null) {
                Map<Long, String> e = Map.copyOf(enumValues);
                for (String n : e.values())
                    enums.putIfAbsent(n, e);
                enumDepth = -1;
                enumValues = null;
            }
            if (arrayDepth >= depth)
                arrayDepth = -1;
        }

        void flush() {
            if (tag == null)
                return;
            closeAggregates(depth);
            String name = attrs.containsKey("DW_AT_name") ? name(attrs.get("DW_AT_name")) : null;
            long type = ref(attrs.get("DW_AT_type"));
            switch (tag) {
                case "DW_TAG_base_type", "DW_TAG_pointer_type", "DW_TAG_typedef", "DW_TAG_const_type",
                     "DW_TAG_volatile_type", "DW_TAG_enumeration_type", "DW_TAG_subroutine_type" ->
                        types.put(off, new Type(kind(tag), name, size(), type, List.of()));
                case "DW_TAG_structure_type", "DW_TAG_union_type" -> {
                    types.put(off, new Type(kind(tag), name, size(), -1, List.of()));
                    aggregates.add(new long[] {off, depth});
                    aggMembers.add(new ArrayList<>());
                }
                case "DW_TAG_array_type" -> {
                    dims = new ArrayList<>();
                    arrayDepth = depth;
                    types.put(off, new Type("array", null, -1, type, dims));
                }
                case "DW_TAG_subrange_type" -> {
                    if (arrayDepth >= 0 && depth == arrayDepth + 1) {
                        String ub = attrs.get("DW_AT_upper_bound"), n = attrs.get("DW_AT_count");
                        long v = ub != null ? number(ub) : n != null ? number(n) - 1 : -2;
                        dims.add(v < -1 ? -1 : v + 1);
                    }
                }
                case "DW_TAG_member" -> {
                    if (!aggregates.isEmpty() && depth == aggregates.getLast()[1] + 1 && name != null) {
                        String loc = attrs.get("DW_AT_data_member_location");
                        long mOff = 0; /* a union's members have none */
                        if (loc != null) {
                            Matcher u = UCONST.matcher(loc);
                            mOff = u.find() ? Long.parseLong(u.group(1)) : number(loc);
                        }
                        if (mOff >= 0)
                            aggMembers.getLast().add(new Member(name, mOff, type));
                    }
                }
                case "DW_TAG_enumerator" -> {
                    String v = attrs.get("DW_AT_const_value");
                    if (enumValues != null && depth == enumDepth + 1 && name != null && v != null)
                        enumValues.put(number(v), name);
                }
                case "DW_TAG_variable" -> {
                    if (depth == 1 && name != null && type >= 0)
                        varType.putIfAbsent(name, type);
                }
                default -> { }
            }
            if (tag.equals("DW_TAG_enumeration_type")) {
                enumValues = new HashMap<>();
                enumDepth = depth;
            }
            attrs.clear();
            tag = null;
        }

        private long size() {
            String v = attrs.get("DW_AT_byte_size");
            return v == null ? -1 : number(v);
        }
    }

    private static String kind(String tag) {
        return switch (tag) {
            case "DW_TAG_base_type" -> "base";
            case "DW_TAG_pointer_type" -> "pointer";
            case "DW_TAG_typedef" -> "typedef";
            case "DW_TAG_const_type" -> "const";
            case "DW_TAG_volatile_type" -> "volatile";
            case "DW_TAG_enumeration_type" -> "enum";
            case "DW_TAG_structure_type" -> "struct";
            case "DW_TAG_union_type" -> "union";
            default -> "other";
        };
    }

    private static long ref(String v) {
        if (v == null)
            return -1;
        Matcher m = REF.matcher(v);
        return m.find() ? Long.parseLong(m.group(1), 16) : -1;
    }

    /** "(indirect string, offset: 0x42a): trapframe" or "cwd" -> the name. */
    private static String name(String v) {
        int i = v.lastIndexOf("): ");
        return v.startsWith("(") && i >= 0 ? v.substring(i + 3) : v;
    }

    private static long number(String v) {
        String t = v.split("\\s+")[0];
        try {
            return t.startsWith("0x") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

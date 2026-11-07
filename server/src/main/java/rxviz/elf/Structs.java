package rxviz.elf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Struct layouts from an ELF's DWARF (objdump --dwarf=info): member
 * names and offsets, so memory can be labeled with the kernel's own
 * field names (trapframe.rip, ...).
 */
public final class Structs {

    public record Member(String name, long offset) {
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

    private static final Pattern DIE = Pattern.compile("^\\s*<(\\d+)><([0-9a-f]+)>: Abbrev Number: \\d+ \\((\\w+)\\)");
    private static final Pattern REF = Pattern.compile("<0x([0-9a-f]+)>");
    private static final Pattern ATTR = Pattern.compile("^\\s*<[0-9a-f]+>\\s+(DW_AT_\\w+)\\s*: (.*)$");
    private static final Pattern UCONST = Pattern.compile("DW_OP_plus_uconst: (\\d+)");

    private final Map<String, Struct> byName = new HashMap<>();
    /* every struct (named or not) by DIE offset; file-scope variables' type offsets */
    private final Map<Long, Struct> byOffset = new HashMap<>();
    private final Map<String, Long> varType = new HashMap<>();

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
        return t == null ? null : byOffset.get(t);
    }

    private void parse(ElfFile elf) throws IOException {
        Parser p = new Parser();
        Objdump.run(elf.path(), p::line, "--dwarf=info");
        p.endStruct();
    }

    /** Reads objdump's DIEs line by line: structs and their members, file-scope variables' types. */
    private final class Parser {
        int depth;
        long dieOff;
        String tag;
        String varName;
        /* the struct being read (structDepth < 0: none) and its member being read */
        int structDepth = -1;
        long structOff;
        String sName;
        long sSize = -1;
        final List<Member> members = new ArrayList<>();
        String mName;
        long mOff = -1;

        void endMember() {
            if (mName != null && mOff >= 0)
                members.add(new Member(mName, mOff));
            mName = null;
            mOff = -1;
        }

        void endStruct() {
            if (structDepth < 0)
                return;
            endMember();
            if (sSize > 0 && !members.isEmpty()) {
                Struct st = new Struct(sName, sSize, List.copyOf(members));
                byOffset.put(structOff, st);
                if (sName != null)
                    byName.putIfAbsent(sName, st);
            }
            structDepth = -1;
            sName = null;
            sSize = -1;
            members.clear();
        }

        void line(String line) {
            Matcher d = DIE.matcher(line);
            if (d.find()) {
                depth = Integer.parseInt(d.group(1));
                dieOff = Long.parseLong(d.group(2), 16);
                tag = d.group(3);
                varName = null;
                if (structDepth >= 0) {
                    if (depth <= structDepth)
                        endStruct();
                    else
                        endMember();
                }
                if (structDepth < 0 && tag.equals("DW_TAG_structure_type")) {
                    structDepth = depth;
                    structOff = dieOff;
                }
                return;
            }
            Matcher a = ATTR.matcher(line);
            if (!a.find())
                return;
            String attr = a.group(1);
            String v = a.group(2).trim();
            if (depth == 1 && "DW_TAG_variable".equals(tag)) {
                if (attr.equals("DW_AT_name"))
                    varName = name(v);
                Matcher ref = REF.matcher(v);
                if (attr.equals("DW_AT_type") && varName != null && ref.find())
                    varType.putIfAbsent(varName, Long.parseLong(ref.group(1), 16));
                return;
            }
            if (structDepth < 0)
                return;
            boolean onStruct = depth == structDepth;
            boolean onMember = depth == structDepth + 1 && "DW_TAG_member".equals(tag);
            switch (attr) {
                case "DW_AT_name" -> {
                    if (onStruct)
                        sName = name(v);
                    else if (onMember)
                        mName = name(v);
                }
                case "DW_AT_byte_size" -> {
                    if (onStruct)
                        sSize = parseNumber(v);
                }
                case "DW_AT_data_member_location" -> {
                    if (onMember) {
                        Matcher u = UCONST.matcher(v);
                        mOff = u.find() ? Long.parseLong(u.group(1)) : parseNumber(v);
                    }
                }
                default -> { }
            }
        }
    }

    /** "(indirect string, offset: 0x42a): trapframe" or "cwd" -> the name. */
    private static String name(String v) {
        int i = v.lastIndexOf("): ");
        return v.startsWith("(") && i >= 0 ? v.substring(i + 3) : v;
    }

    private static long parseNumber(String v) {
        String t = v.split("\\s+")[0];
        try {
            return t.startsWith("0x") ? Long.parseLong(t.substring(2), 16) : Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}

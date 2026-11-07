package rxviz.elf;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AT&T disassembly of an ELF file by address, from objdump. QEMU's
 * plugin API returns no text when QEMU is built without capstone (as
 * Ubuntu's package is), and all code the guest runs comes from these
 * files anyway.
 */
public final class Disassembly {

    private static final Pattern LINE =
            Pattern.compile("^\\s*([0-9a-f]+):\\t(?:[0-9a-f]{2} )+\\s*\\t(.*)$");
    private static final Pattern HEX_ADDR = Pattern.compile("[0-9a-f]{8,}");

    /* by unsigned address order: kernel addresses are negative longs */
    private final NavigableMap<Long, String> text = new TreeMap<>(Long::compareUnsigned);

    public static Disassembly of(ElfFile elf) throws IOException {
        Disassembly d = new Disassembly();
        Objdump.run(elf.path(), line -> {
            Matcher m = LINE.matcher(line);
            if (m.matches())
                d.text.put(Long.parseUnsignedLong(m.group(1), 16), tidy(m.group(2)));
        }, "-d", "-w");
        return d;
    }

    /**
     * "mov    %rsp,%rbp" -> "mov\t%rsp,%rbp": mnemonic (with any prefix such
     * as "rep stos"), a tab, then the operands; drops objdump's "# 0x..."
     * notes.
     */
    private static String tidy(String s) {
        int hash = s.indexOf("  #");
        if (hash >= 0)
            s = s.substring(0, hash);
        String[] w = s.trim().split("\\s+");
        StringBuilder mn = new StringBuilder();
        int i = 0;
        while (i < w.length && !isOperand(w[i])) {
            if (i > 0)
                mn.append(' ');
            mn.append(w[i++]);
        }
        StringBuilder ops = new StringBuilder();
        for (; i < w.length; i++)
            ops.append(ops.isEmpty() ? "" : " ").append(w[i]);
        return ops.isEmpty() ? mn.toString() : mn + "\t" + ops;
    }

    private static boolean isOperand(String t) {
        char c = t.charAt(0);
        return c == '%' || c == '$' || c == '(' || c == '*' || c == '-' || c == '<'
                || Character.isDigit(c) || HEX_ADDR.matcher(t).matches();
    }

    public String at(long addr) {
        return text.get(addr);
    }

    /** True if the instruction just before addr is a call (addr is where it returns). */
    public boolean followsCall(long addr) {
        if (!text.containsKey(addr))
            return false;
        Map.Entry<Long, String> e = text.lowerEntry(addr);
        return e != null && e.getValue().startsWith("call");
    }

    /** Instructions with from <= address < to. */
    public List<Map.Entry<Long, String>> between(long from, long to) {
        return new ArrayList<>(text.subMap(from, true, to, false).entrySet());
    }

    /** Up to `before` instructions before addr, the one at (or after) addr, and `after` more. */
    public List<Map.Entry<Long, String>> around(long addr, int before, int after) {
        List<Map.Entry<Long, String>> out = new ArrayList<>();
        Map.Entry<Long, String> e = text.lowerEntry(addr);
        List<Map.Entry<Long, String>> back = new ArrayList<>();
        while (e != null && back.size() < before) {
            back.addFirst(e);
            e = text.lowerEntry(e.getKey());
        }
        out.addAll(back);
        e = text.ceilingEntry(addr);
        for (int i = 0; e != null && i <= after; i++) {
            out.add(e);
            e = text.higherEntry(e.getKey());
        }
        return out;
    }
}

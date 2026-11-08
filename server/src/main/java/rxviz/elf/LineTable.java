package rxviz.elf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.NavigableSet;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Address -> source file and line, from the DWARF line table
 * (objdump --dwarf=decodedline). Files are paths relative to the kernel
 * tree, e.g. "kernel/proc.c" or "user/echo.c".
 */
public final class LineTable {

    public record Src(String file, int line) {
    }

    /** The source files of a kernel tree, shared by the line tables of its ELF files. */
    public static final class SourceTree {
        private final Path root;
        private final Map<String, String> byBase = new HashMap<>();
        private final Map<String, Boolean> exists = new ConcurrentHashMap<>();

        public static SourceTree of(Path root) throws IOException {
            SourceTree t = new SourceTree(root);
            try (Stream<Path> s = Files.walk(root)) {
                List<Path> files = s.filter(Files::isRegularFile)
                        .filter(f -> isSource(f.getFileName().toString())).sorted().toList();
                for (Path f : files)
                    t.byBase.putIfAbsent(f.getFileName().toString(), root.relativize(f).toString());
            }
            return t;
        }

        private SourceTree(Path root) {
            this.root = root;
        }

        Path path(String rel) {
            return root.resolve(rel);
        }

        /** The table header names the file with its directory when it is that row's file. */
        String resolve(String header, String base) {
            if (header != null) {
                String h = header.startsWith("./") ? header.substring(2) : header;
                if (h.equals(base) || h.endsWith("/" + base))
                    if (exists.computeIfAbsent(h, x -> Files.isRegularFile(root.resolve(x))))
                        return h;
            }
            return byBase.get(base);
        }
    }

    private static final Pattern HEADER = Pattern.compile("^(?:CU: )?(\\S.*):$");
    private static final Pattern ROW =
            Pattern.compile("^(\\S+)\\s+(\\d+)\\s+(0x[0-9a-f]+|0)(?:\\s+\\d+)?(?:\\s+x)?\\s*$");
    /* the end of a sequence: the addresses from here on have no line (until the next row) */
    private static final Pattern END_ROW = Pattern.compile("^(\\S+)\\s+-\\s+(0x[0-9a-f]+)\\s*$");
    private static final Src END = new Src("", 0);

    /* unsigned address order: kernel addresses are negative longs */
    private final NavigableMap<Long, Src> rows = new TreeMap<>(Long::compareUnsigned);

    private LineTable() {
    }

    public static LineTable of(ElfFile elf, SourceTree tree) throws IOException {
        LineTable t = new LineTable();
        Map<String, String> interned = new HashMap<>();
        String[] header = {null};
        Objdump.run(elf.path(), line -> {
            Matcher h = HEADER.matcher(line);
            if (h.matches() && !line.startsWith("Contents")) {
                header[0] = h.group(1);
                return;
            }
            Matcher end = END_ROW.matcher(line);
            if (end.matches()) {
                /* over its own sequence's last (empty) row; a next sequence's first row comes later and wins */
                t.rows.put(Long.parseUnsignedLong(end.group(2).substring(2), 16), END);
                return;
            }
            Matcher m = ROW.matcher(line);
            if (!m.matches())
                return;
            String file = tree.resolve(header[0], m.group(1));
            if (file == null)
                return;
            file = interned.computeIfAbsent(file, f -> f);
            long addr = Long.parseUnsignedLong(m.group(3).replace("0x", ""), 16);
            t.rows.put(addr, new Src(file, Integer.parseInt(m.group(2))));
        }, "--dwarf=decodedline");
        return t;
    }

    public static boolean isSource(String name) {
        return name.endsWith(".c") || name.endsWith(".h") || name.endsWith(".S");
    }

    /** The lines of file that some code comes from (empty if none). */
    public NavigableSet<Integer> linesOf(String file) {
        TreeSet<Integer> out = new TreeSet<>();
        for (Src s : rows.values())
            if (s != END && s.file().equals(file))
                out.add(s.line());
        return out;
    }

    /**
     * Address ranges [from, to) of code assembled for 32-bit mode: rows
     * of .S files on lines between a .code32 and the next .code64 (the
     * kernels' entry code, which runs before long mode is on).
     */
    public List<long[]> code32(SourceTree tree) {
        Map<String, boolean[]> modes = new HashMap<>();
        List<long[]> out = new ArrayList<>();
        long from = -1;
        for (Map.Entry<Long, Src> e : rows.entrySet()) {
            Src s = e.getValue();
            boolean in32 = false;
            if (s != END && s.file().endsWith(".S")) {
                boolean[] m = modes.computeIfAbsent(s.file(), f -> modesOf(tree.path(f)));
                in32 = s.line() < m.length && m[s.line()];
            }
            if (in32 && from == -1)
                from = e.getKey();
            else if (!in32 && from != -1) {
                out.add(new long[] {from, e.getKey()});
                from = -1;
            }
        }
        return out;
    }

    /** Per line (1-based): whether it is in a .code32 part of the file. */
    private static boolean[] modesOf(Path file) {
        try {
            List<String> lines = Files.readAllLines(file);
            boolean[] m = new boolean[lines.size() + 1];
            boolean in32 = false;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i).trim();
                if (l.startsWith(".code32"))
                    in32 = true;
                else if (l.startsWith(".code64"))
                    in32 = false;
                m[i + 1] = in32;
            }
            return m;
        } catch (IOException e) {
            return new boolean[0];
        }
    }

    /** The row covering addr, or null (also past the end of a sequence: code without line info). */
    public Src at(long addr) {
        Map.Entry<Long, Src> e = rows.floorEntry(addr);
        return e == null || e.getValue() == END ? null : e.getValue();
    }
}

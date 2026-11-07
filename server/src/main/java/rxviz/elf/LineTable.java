package rxviz.elf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
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

    /** The row covering addr, or null. */
    public Src at(long addr) {
        Map.Entry<Long, Src> e = rows.floorEntry(addr);
        return e == null ? null : e.getValue();
    }
}

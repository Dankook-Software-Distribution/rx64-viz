package rxviz.index;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.TreeSet;

import com.github.luben.zstd.ZstdOutputStream;

/**
 * Full physical memory at some steps, zstd-compressed (xv6 memory is
 * mostly zeros and junk fill, so each is small): the segment's starting
 * snapshot is the one at step 0, then one every {@link #INTERVAL} steps.
 * Seeking starts from the nearest one instead of replaying every store
 * since the segment began.
 */
final class Checkpoints {

    static final int INTERVAL = 1 << 23;

    private final String prefix;
    private final Path snapshot;
    private final TreeSet<Integer> steps = new TreeSet<>();

    /** snapshot: memory at step 0 as written by the plugin (absent: all zero). */
    Checkpoints(Path prefix, Path snapshot) {
        this.prefix = prefix.toString();
        this.snapshot = snapshot;
        steps.add(0);
    }

    private Path file(int step) {
        return step == 0 ? snapshot : Path.of(prefix + "." + step + ".zst");
    }

    /** Memory as it is at step (before step's instruction). */
    void write(int step, MemoryImage img) throws IOException {
        try (OutputStream out = new ZstdOutputStream(Files.newOutputStream(file(step)), 1)) {
            img.writeTo(out);
        }
        steps.add(step);
    }

    /** Largest checkpoint step <= step (0 at least). */
    int floor(int step) {
        Integer s = steps.floor(step);
        return s == null ? 0 : s;
    }

    void load(int step, MemoryImage img) throws IOException {
        Path f = file(step);
        if (step == 0 && !Files.exists(f))
            img.clear();
        else
            img.fill(f);
    }

    long bytesOnDisk() {
        long n = 0;
        for (int s : steps)
            if (s > 0)
                try {
                    n += Files.size(file(s));
                } catch (IOException e) {
                    /* ignore */
                }
        return n;
    }
}

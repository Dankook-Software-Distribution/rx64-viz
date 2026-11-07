package rxviz.index;

import java.nio.file.Path;

/**
 * The files of segment seq in a recording directory. The plugin writes
 * trace and snapshot (rxtrace.c: PREFIX.NNN.zst, PREFIX.NNN.mem.zst with
 * PREFIX = dir/trace); the indexer writes chunks and checkpoints.
 */
public record SegmentFiles(Path trace, Path snapshot, Path chunks, Path checkpoints) {

    /** The plugin's out= and snap= prefix for a directory. */
    public static Path prefix(Path dir) {
        return dir.resolve("trace");
    }

    public static SegmentFiles of(Path dir, int seq) {
        String n = String.format("%03d", seq);
        return new SegmentFiles(dir.resolve("trace." + n + ".zst"),
                dir.resolve("trace." + n + ".mem.zst"),
                dir.resolve("steps." + n + ".chunks"),
                dir.resolve("steps." + n + ".ckpt"));
    }
}

package rxviz.session;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.TimeoutException;

import rxviz.config.KernelProfile;
import rxviz.config.RxvizProperties;
import rxviz.index.SegmentFiles;

/** Starting QEMU with the rxtrace plugin in a directory, and marking segments. */
final class Qemu {

    private Qemu() {
    }

    /**
     * Copies fs.img into dir, creates the control FIFO and starts QEMU.
     * The plugin writes segment seq's trace and snapshot as named by
     * SegmentFiles.of(dir, seq).
     */
    static Process start(KernelProfile kernel, RxvizProperties props, Path dir,
                         String pluginOptions) throws IOException, InterruptedException {
        Files.createDirectories(dir);
        Path fs = dir.resolve("fs.img");
        Files.copy(kernel.dir().resolve("fs.img"), fs, StandardCopyOption.REPLACE_EXISTING);
        Path ctl = dir.resolve("ctl");
        Files.deleteIfExists(ctl);
        Files.deleteIfExists(dir.resolve("ctl.ack"));
        if (new ProcessBuilder("mkfifo", ctl.toString()).start().waitFor() != 0)
            throw new IOException("mkfifo " + ctl);

        long mb = props.physMemBytes() >> 20;
        Path prefix = SegmentFiles.prefix(dir);
        String plugin = props.plugin() + ",mode=full," + pluginOptions
                + ",out=" + prefix + ",snap=" + prefix + ",snapmb=" + mb
                + ",stats=" + dir.resolve("stats.txt") + ",ctl=" + ctl;
        List<String> argv = List.of(
                "qemu-system-x86_64", "-machine", "q35",
                "-kernel", kernel.dir().resolve("kernel/kernel").toString(),
                "-m", mb + "M", "-smp", "1", "-nographic", "-nic", "none",
                "-drive", "file=" + fs + ",if=none,format=raw,id=x0",
                "-device", "virtio-blk-pci,drive=x0,disable-legacy=on,addr=0x3",
                "-no-reboot",
                /* guest time follows the instruction count, so tracing
                 * overhead does not change what the guest does */
                "-icount", "shift=" + props.icountShift() + ",sleep=on",
                "-plugin", plugin);
        return new ProcessBuilder(argv).directory(dir.toFile())
                .redirectErrorStream(true).start();
    }

    /**
     * Ends the current segment and starts `name` as segment number seq
     * (marks are counted from 1; boot is 0). Returns once the plugin has
     * switched modes and closed the previous segment file.
     */
    static void mark(Path dir, Process qemu, String name, int seq)
            throws IOException, InterruptedException, TimeoutException {
        Files.writeString(dir.resolve("ctl"), name + "\n");
        Path ack = dir.resolve("ctl.ack");
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (Files.exists(ack) && Files.readAllLines(ack).size() >= seq)
                return;
            if (!qemu.isAlive())
                throw new IOException("qemu exited");
            Thread.sleep(5);
        }
        throw new TimeoutException("mark " + name + " not acknowledged");
    }
}

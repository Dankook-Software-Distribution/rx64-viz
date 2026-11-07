package rxviz.session;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import rxviz.config.KernelProfile;
import rxviz.config.KernelRegistry;
import rxviz.config.RxvizProperties;
import rxviz.index.Indexer;
import rxviz.index.Recording;
import rxviz.index.SegmentFiles;

/**
 * The boot of each kernel, from its entry point to the first shell
 * prompt. Recording it takes minutes (kinit fills all of RAM byte by
 * byte), and with -icount and no input the guest boots the same way
 * every time, so it is recorded once into data-dir/boot/<kernel> and
 * reused; a change to the kernel or the plugin records it again.
 * Sessions boot count-only and skip all of this.
 */
@Service
public class BootService {

    private static final Logger log = LoggerFactory.getLogger(BootService.class);

    public enum Status { WAITING, RECORDING, INDEXING, READY, FAILED }

    public static final class Boot {
        public volatile Status status = Status.WAITING;
        public volatile String error;
        public volatile Recording recording;
    }

    private final KernelRegistry kernels;
    private final RxvizProperties props;
    private final Map<String, Boot> boots = new LinkedHashMap<>();

    public BootService(KernelRegistry kernels, RxvizProperties props) {
        this.kernels = kernels;
        this.props = props;
        for (String id : kernels.all().keySet())
            boots.put(id, new Boot());
    }

    public Boot boot(String kernelId) {
        Boot b = boots.get(kernelId);
        if (b == null)
            throw new SessionService.NotFoundException("no kernel " + kernelId);
        return b;
    }

    public static String recordingId(String kernelId) {
        return "boot-" + kernelId;
    }

    @EventListener(ApplicationReadyEvent.class)
    void startup() {
        /* the kernels are independent: record or index them side by side */
        for (KernelProfile k : kernels.all().values())
            Thread.ofPlatform().name("boot-" + k.id()).daemon().start(() -> prepare(k));
    }

    private void prepare(KernelProfile k) {
        Boot b = boots.get(k.id());
        Path dir = Path.of(props.dataDir(), "boot", k.id());
        try {
            String stamp = "kernel=" + sha256(k.dir().resolve("kernel/kernel"))
                    + " plugin=" + sha256(Path.of(props.plugin())) + "\n";
            Path marker = dir.resolve("complete");
            if (!Files.exists(marker) || !Files.readString(marker).equals(stamp)) {
                b.status = Status.RECORDING;
                long t0 = System.currentTimeMillis();
                record(k, dir);
                Files.writeString(marker, stamp);
                log.info("boot {}: recorded in {} s", k.id(), (System.currentTimeMillis() - t0) / 1000);
            }
            b.status = Status.INDEXING;
            b.recording = Indexer.index(recordingId(k.id()), "boot", k,
                    SegmentFiles.of(dir, 0), (int) props.physMemBytes());
            b.status = Status.READY;
            log.info("boot {}: indexed, {} steps in {} ms", k.id(), b.recording.steps(),
                    b.recording.summary().indexMillis());
        } catch (Throwable e) {
            log.error("boot {}: failed", k.id(), e);
            b.error = e.toString();
            b.status = Status.FAILED;
        }
    }

    /** Boot once with full recording armed at the kernel entry. */
    private void record(KernelProfile k, Path dir) throws Exception {
        if (k.bootEntry() < 0)
            throw new IllegalStateException(k.id() + ": no boot entry symbol");
        if (Files.exists(dir))
            try (Stream<Path> old = Files.list(dir)) {
                for (Path p : old.toList())
                    Files.delete(p);
            }
        Process qemu = Qemu.start(k, props, dir,
                "boot=on,arm=" + Long.toHexString(k.bootEntry()));
        StringBuilder out = new StringBuilder();
        try (InputStream in = qemu.getInputStream()) {
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + 30 * 60_000L;
            while (out.indexOf("$ ") < 0) {
                if (System.currentTimeMillis() > deadline)
                    throw new IllegalStateException("boot did not reach the prompt");
                int n = in.read(buf);
                if (n < 0)
                    throw new IOException("qemu exited during boot");
                out.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
            }
            /* end the boot segment; the plugin closes trace.000.zst */
            Qemu.mark(dir, qemu, "idle", 1);
        } finally {
            qemu.destroy();
            qemu.waitFor();
            Files.writeString(dir.resolve("console.txt"), out);
            Files.deleteIfExists(dir.resolve("fs.img"));
        }
    }

    private static String sha256(Path p) throws IOException, NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(Files.readAllBytes(p)));
    }
}

package rxviz.session;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rxviz.config.KernelProfile;
import rxviz.config.RxvizProperties;
import rxviz.index.Indexer;
import rxviz.index.Recording;
import rxviz.index.SegmentFiles;

/**
 * One running guest: QEMU with the rxtrace plugin, its console, and the
 * recordings made from it.
 *
 * Boot runs count-only. When the user presses Enter on a command line,
 * the session marks a "cmd:..." segment (the plugin switches to full
 * recording and acknowledges), then passes the Enter on. When sh prints
 * its next "$ " prompt, it marks "idle" (back to count-only; the segment
 * file is closed) and hands the segment to the indexer.
 */
public final class Session {

    private static final Logger log = LoggerFactory.getLogger(Session.class);
    private static final String PROMPT = "$ ";

    public enum State { BOOTING, READY, RUNNING, STOPPED }

    /** A command's recording and where it is in its life. */
    public static final class Rec {
        public final String id;
        public final int seq;
        public final String command;
        public volatile String status = "recording";
        public volatile String error;
        public volatile Recording recording;
        final CompletableFuture<Rec> done = new CompletableFuture<>();

        Rec(String id, int seq, String command) {
            this.id = id;
            this.seq = seq;
            this.command = command;
        }
    }

    public interface Listener {
        void output(String text);

        void recording(Rec rec);

        void state(State state);
    }

    private final String id;
    private final KernelProfile kernel;
    private final RxvizProperties props;
    private final Path dir;
    private final ExecutorService indexer;
    private final ExecutorService input = Executors.newSingleThreadExecutor();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final List<Rec> recordings = new CopyOnWriteArrayList<>();

    private Process qemu;
    private OutputStream qin;
    private final StringBuilder out = new StringBuilder();
    private int promptScan;
    private volatile State state = State.BOOTING;
    private int marks;          /* marks sent; segment files are numbered by it */
    private final StringBuilder line = new StringBuilder();
    private Rec running;

    Session(String id, KernelProfile kernel, RxvizProperties props, ExecutorService indexer) {
        this.id = id;
        this.kernel = kernel;
        this.props = props;
        this.dir = Path.of(props.dataDir(), "sessions", id);
        this.indexer = indexer;
    }

    public String id() { return id; }
    public KernelProfile kernel() { return kernel; }
    public State state() { return state; }

    public Rec recording(String recId) {
        for (Rec r : recordings)
            if (r.id.equals(recId))
                return r;
        return null;
    }

    public synchronized String console() {
        return out.toString();
    }

    public void addListener(Listener l) { listeners.add(l); }
    public void removeListener(Listener l) { listeners.remove(l); }

    // ---------------- QEMU ----------------

    void start() throws IOException, InterruptedException {
        /* boot runs count-only here; BootService records it once */
        qemu = Qemu.start(kernel, props, dir, "boot=off");
        qin = qemu.getOutputStream();
        Thread.ofPlatform().name("qemu-out-" + id).daemon().start(this::readOutput);
        log.info("session {}: started {} (pid {})", id, kernel.id(), qemu.pid());
    }

    private void readOutput() {
        byte[] buf = new byte[4096];
        try (InputStream in = qemu.getInputStream()) {
            int n;
            while ((n = in.read(buf)) > 0) {
                String s = new String(buf, 0, n, StandardCharsets.ISO_8859_1);
                boolean prompt;
                synchronized (this) {
                    out.append(s);
                    int at = state == State.BOOTING || state == State.RUNNING
                            ? out.indexOf(PROMPT, promptScan) : -1;
                    prompt = at >= 0;
                    if (prompt)
                        promptScan = at + PROMPT.length();
                }
                for (Listener l : listeners)
                    l.output(s);
                if (prompt)
                    input.submit(this::atPrompt);
            }
        } catch (IOException e) {
            log.debug("session {}: output closed", id, e);
        }
        setState(State.STOPPED);
    }

    private void write(String s) {
        try {
            qin.write(s.getBytes(StandardCharsets.ISO_8859_1));
            qin.flush();
        } catch (IOException e) {
            log.warn("session {}: console write failed", id, e);
        }
    }

    void stop() {
        if (qemu != null)
            qemu.destroy();
        input.shutdownNow();
        setState(State.STOPPED);
    }

    private void setState(State s) {
        state = s;
        for (Listener l : listeners)
            l.state(s);
    }

    // ---------------- marks ----------------

    /** Start segment `name`; returns once the plugin has switched modes. */
    private int mark(String name) throws IOException, InterruptedException, TimeoutException {
        int seq = ++marks;
        Qemu.mark(dir, qemu, name, seq);
        return seq;
    }

    // ---------------- input ----------------

    /** Console input from the user, in order. */
    public void input(String data) {
        input.submit(() -> handleInput(data));
    }

    private void handleInput(String data) {
        for (int i = 0; i < data.length(); i++) {
            char c = data.charAt(i);
            if (state != State.READY) {
                write(String.valueOf(c));
                continue;
            }
            if (c == '\r' || c == '\n') {
                String cmd = line.toString().trim();
                line.setLength(0);
                if (cmd.isEmpty())
                    write("\n");
                else
                    startCommand(cmd);
            } else if (c == 0x7f || c == '\b') {
                if (!line.isEmpty())
                    line.setLength(line.length() - 1);
                write(String.valueOf(c));
            } else {
                line.append(c);
                write(String.valueOf(c));
            }
        }
    }

    private void startCommand(String cmd) {
        try {
            int seq = mark("cmd:" + cmd);
            Rec rec = new Rec(id + "-" + seq, seq, cmd);
            recordings.add(rec);
            running = rec;
            synchronized (this) {
                promptScan = out.length();
            }
            setState(State.RUNNING);
            notifyRec(rec);
            write("\n");
        } catch (Exception e) {
            log.error("session {}: cannot start recording", id, e);
            write("\n");
        }
    }

    private void atPrompt() {
        try {
            if (state == State.BOOTING) {
                mark("idle");
                setState(State.READY);
            } else if (state == State.RUNNING) {
                mark("idle");
                Rec rec = running;
                running = null;
                setState(State.READY);
                rec.status = "indexing";
                notifyRec(rec);
                indexer.submit(() -> index(rec));
            }
        } catch (Exception e) {
            log.error("session {}: prompt handling failed", id, e);
        }
    }

    private void index(Rec rec) {
        try {
            rec.recording = Indexer.index(rec.id, rec.command, kernel,
                    SegmentFiles.of(dir, rec.seq), (int) props.physMemBytes());
            rec.status = "ready";
            log.info("session {}: indexed {} ({} steps)", id, rec.id,
                    rec.recording.steps());
        } catch (Throwable e) {
            log.error("session {}: indexing {} failed", id, rec.id, e);
            rec.status = "failed";
            rec.error = e.toString();
        }
        notifyRec(rec);
        rec.done.complete(rec);
    }

    private void notifyRec(Rec rec) {
        for (Listener l : listeners)
            l.recording(rec);
    }

    /** Type a command and wait until its recording is indexed. */
    public CompletableFuture<Rec> run(String command) {
        CompletableFuture<Rec> f = new CompletableFuture<>();
        input.submit(() -> {
            if (state != State.READY) {
                f.completeExceptionally(new IllegalStateException("session is " + state));
                return;
            }
            int before = recordings.size();
            handleInput(command + "\n");
            if (recordings.size() == before) {
                f.completeExceptionally(new IllegalStateException("no recording started"));
                return;
            }
            recordings.get(before).done.whenComplete((r, e) -> f.complete(r));
        });
        return f;
    }

    /** Wait until the guest shows its first prompt. */
    public void awaitReady(long millis) throws InterruptedException, TimeoutException {
        long deadline = System.currentTimeMillis() + millis;
        while (state != State.READY) {
            if (state == State.STOPPED)
                throw new IllegalStateException("guest stopped");
            if (System.currentTimeMillis() > deadline)
                throw new TimeoutException("guest not ready");
            Thread.sleep(20);
        }
    }

    /** The recordings so far (a snapshot, safe to iterate). */
    public List<Rec> recordingList() {
        return List.copyOf(recordings);
    }
}

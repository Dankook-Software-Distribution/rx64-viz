package dev.xv6viz.vm;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

/**
 * QEMU 하나와 거기 붙은 gdb 하나.
 *
 * 제어 동작(멈춤, 계속, 스냅샷, 따라가기)은 전부 {@link #ctl} 스레드에서 순서대로 실행한다.
 * gdb의 *stopped 알림은 리더 스레드에서 오는데, 우리가 기다리던 멈춤이면 {@link #stopWaiter}로
 * 넘기고, 아니면(브레이크포인트에 걸린 경우) ctl 스레드에 처리를 넘긴다.
 */
public class Vm {

    public enum State { STARTING, RUNNING, PAUSED, JOURNEY_WAIT, JOURNEY_STOP, STOPPED, FAILED }

    private static final Logger log = LoggerFactory.getLogger(Vm.class);
    private static final int CONSOLE_BACKLOG = 64 * 1024;

    private final String id;
    private final Path root;
    private final int cpus;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ExecutorService ctl = Executors.newSingleThreadExecutor(r -> daemon(r, "vm-ctl"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> daemon(r, "vm-timer"));
    private final List<Consumer<String>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<CompletableFuture<MiRecord>> stopWaiter = new AtomicReference<>();
    private final StringBuilder console = new StringBuilder();

    private volatile State state = State.STOPPED;
    private volatile String failure;
    private volatile boolean autoRefresh = true;
    private volatile String lastSnapshot;
    private volatile String lastJourney = "{\"active\":false}";
    private volatile String syscalls = "[]";

    private Process qemu;
    private OutputStream qemuIn;
    private GdbMi gdb;
    private Path workDir;
    private int generation;

    public Vm(String id, Path root, int cpus) {
        this.id = id;
        this.root = root;
        this.cpus = cpus;
        timer.scheduleWithFixedDelay(this::tick, 1000, 1000, TimeUnit.MILLISECONDS);
    }

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        return t;
    }

    // ------------------------------------------------------------------ 구독

    public void addListener(Consumer<String> l) { listeners.add(l); }

    private void emit(String msg) {
        for (Consumer<String> l : listeners) {
            try {
                l.accept(msg);
            } catch (RuntimeException e) {
                log.debug("listener 실패", e);
            }
        }
    }

    private void emit(String type, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("vm", id);
        m.putAll(fields);
        emit(json.writeValueAsString(m));
    }

    /** 이미 JSON인 데이터를 감싸서 보낸다. */
    private void emitRaw(String type, String data) {
        emit(raw(type, data));
    }

    private void emitStatus() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state.name());
        m.put("autoRefresh", autoRefresh);
        m.put("failure", failure);
        m.put("cpus", cpus);
        m.put("kernel", root.getFileName().toString());
        emit("status", m);
    }

    /** 새로 접속한 브라우저에 보낼 현재 상태. */
    public List<String> greeting() {
        List<String> out = new ArrayList<>();
        Map<String, Object> st = new LinkedHashMap<>();
        st.put("type", "status");
        st.put("vm", id);
        st.put("state", state.name());
        st.put("autoRefresh", autoRefresh);
        st.put("failure", failure);
        st.put("cpus", cpus);
        st.put("kernel", root.getFileName().toString());
        out.add(json.writeValueAsString(st));
        synchronized (console) {
            if (!console.isEmpty()) {
                out.add(json.writeValueAsString(Map.of("type", "console", "vm", id, "data", console.toString(), "replay", true)));
            }
        }
        out.add(raw("syscalls", syscalls));
        if (lastSnapshot != null) out.add(raw("snapshot", lastSnapshot));
        out.add(raw("journey", lastJourney));
        return out;
    }

    // ------------------------------------------------------------------ 시작과 종료

    public void start() {
        ctl.submit(this::doStart);
    }

    private void doStart() {
        teardown();
        generation++;
        state = State.STARTING;
        failure = null;
        lastSnapshot = null;
        lastJourney = "{\"active\":false}";
        synchronized (console) { console.setLength(0); }
        emit("console", Map.of("data", "", "reset", true));
        emitStatus();
        try {
            Path kernel = root.resolve("kernel/kernel");
            if (!Files.exists(kernel)) {
                throw new IOException(kernel + " 이 없습니다. " + root + " 에서 make 를 먼저 실행하세요.");
            }
            workDir = Files.createTempDirectory("xv6viz-" + id + "-");
            Path img = workDir.resolve("fs.img");
            Files.copy(root.resolve("fs.img"), img, StandardCopyOption.REPLACE_EXISTING);
            Path py = workDir.resolve("xv6viz.py");
            try (InputStream s = Vm.class.getResourceAsStream("/gdb/xv6viz.py")) {
                Files.copy(s, py, StandardCopyOption.REPLACE_EXISTING);
            }
            int port;
            try (ServerSocket ss = new ServerSocket(0)) {
                port = ss.getLocalPort();
            }

            qemu = new ProcessBuilder(
                    "qemu-system-x86_64", "-machine", "q35",
                    "-kernel", kernel.toString(), "-m", "128M", "-smp", String.valueOf(cpus),
                    "-display", "none", "-monitor", "none", "-serial", "stdio", "-nic", "none",
                    "-drive", "file=" + img + ",if=none,format=raw,id=x0",
                    "-device", "virtio-blk-pci,drive=x0,disable-legacy=on,addr=0x3",
                    "-no-reboot", "-S", "-gdb", "tcp:127.0.0.1:" + port)
                    .directory(root.toFile())
                    .redirectErrorStream(true)
                    .start();
            qemuIn = qemu.getOutputStream();
            int gen = generation;
            Thread reader = daemon(() -> pumpConsole(qemu.getInputStream(), gen), "qemu-console-" + id);
            reader.start();

            gdb = new GdbMi(root, this::onAsync);
            gdb.call("-gdb-set mi-async on");
            gdb.call("-gdb-set pagination off");
            gdb.call("-gdb-set confirm off");
            gdb.call("-file-exec-and-symbols " + kernel);
            connect(port);
            // 페이징이 켜진 main()까지 간 다음에 모듈을 읽는다
            gdb.call("-break-insert -t main");
            runUntilStop("-exec-continue", Duration.ofSeconds(20));
            gdb.call("-interpreter-exec console \"source " + py + "\"");
            syscalls = gdb.json("-xv6-syscalls");
            emitRaw("syscalls", syscalls);
            gdb.call("-exec-continue");
            state = State.RUNNING;
            emitStatus();
        } catch (Exception e) {
            log.error("VM 시작 실패", e);
            failure = e.getMessage();
            state = State.FAILED;
            emitStatus();
        }
    }

    private void connect(int port) throws InterruptedException {
        GdbMi.GdbException last = null;
        for (int i = 0; i < 20; i++) {
            try {
                gdb.call("-target-select remote 127.0.0.1:" + port);
                return;
            } catch (GdbMi.GdbException e) {
                last = e;
                Thread.sleep(150);
            }
        }
        throw last;
    }

    private void pumpConsole(InputStream out, int gen) {
        try (Reader r = new InputStreamReader(out, StandardCharsets.UTF_8)) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) {
                String s = new String(buf, 0, n);
                synchronized (console) {
                    console.append(s);
                    if (console.length() > CONSOLE_BACKLOG) console.delete(0, console.length() - CONSOLE_BACKLOG);
                }
                emit("console", Map.of("data", s));
            }
        } catch (IOException e) {
            log.debug("콘솔 종료", e);
        }
        if (gen == generation && state != State.STOPPED && state != State.STARTING) {
            state = State.STOPPED;
            emitStatus();
        }
    }

    private void teardown() {
        if (gdb != null) {
            gdb.close();
            gdb = null;
        }
        if (qemu != null) {
            qemu.destroy();
            try {
                if (!qemu.waitFor(2, TimeUnit.SECONDS)) qemu.destroyForcibly();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            qemu = null;
        }
        if (workDir != null) {
            try (var files = Files.walk(workDir)) {
                files.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            } catch (IOException e) {
                log.debug("작업 폴더 삭제 실패", e);
            }
            workDir = null;
        }
        state = State.STOPPED;
    }


    public void shutdown() {
        try {
            ctl.submit(this::teardown).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.debug("종료 중 오류", e);
        }
        ctl.shutdownNow();
        timer.shutdownNow();
    }

    // ------------------------------------------------------------------ 멈춤 처리

    private void onAsync(MiRecord rec) {
        if (!rec.isExecAsync() || !"stopped".equals(rec.cls())) return;
        CompletableFuture<MiRecord> w = stopWaiter.getAndSet(null);
        if (w != null) {
            w.complete(rec);
        } else if (state == State.STARTING) {
            // gdb가 처음 붙을 때의 멈춤. doStart()가 직접 이어서 실행한다.
        } else {
            ctl.submit(() -> onUnexpectedStop(rec));
        }
    }

    /** 명령을 보내고 그 결과로 VM이 멈출 때까지 기다린다. */
    private MiRecord runUntilStop(String cmd, Duration timeout) throws Exception {
        CompletableFuture<MiRecord> w = new CompletableFuture<>();
        stopWaiter.set(w);
        try {
            gdb.call(cmd);
            return w.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } finally {
            stopWaiter.compareAndSet(w, null);
        }
    }

    private boolean running() {
        return state == State.RUNNING || state == State.JOURNEY_WAIT;
    }

    private void interrupt() throws Exception {
        runUntilStop("-exec-interrupt", Duration.ofSeconds(5));
    }

    private boolean journeyHit() {
        return !gdb.json("-xv6-journey-hit").contains("\"hit\": null");
    }

    /** 따라가기 브레이크포인트에 걸렸을 때. 또는 늦게 도착한 interrupt. */
    private void onUnexpectedStop(MiRecord rec) {
        if (gdb == null) return;
        try {
            if (journeyHit()) {
                journeyStopped();
            } else if (running()) {
                gdb.call("-exec-continue");
            }
        } catch (Exception e) {
            log.warn("멈춤 처리 실패", e);
        }
    }

    private void journeyStopped() {
        state = State.JOURNEY_STOP;
        lastJourney = gdb.json("-xv6-journey-state");
        lastSnapshot = gdb.json(snapCmd());
        emitRaw("snapshot", lastSnapshot);
        emitRaw("journey", lastJourney);
        emitStatus();
    }

    /**
     * 멈춘 상태에서 작업을 하고, 원래 돌고 있었으면 다시 돌린다.
     * 멈춘 사이에 따라가기 브레이크포인트가 걸려 있었으면 그 단계로 넘어간다.
     */
    private <T> T whileStopped(Callable<T> work) throws Exception {
        boolean wasRunning = running();
        if (wasRunning) interrupt();
        T r = work.call();
        if (wasRunning) {
            if (journeyHit()) {
                journeyStopped();
            } else {
                gdb.call("-exec-continue");
            }
        }
        return r;
    }

    private <T> T onCtl(Callable<T> c) {
        Future<T> f = ctl.submit(c);
        try {
            return f.get(15, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException e) {
            Throwable cause = e.getCause();
            throw cause instanceof RuntimeException re ? re : new RuntimeException(cause);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void tick() {
        if (!autoRefresh || !running() || gdb == null) return;
        ctl.submit(() -> {
            if (!running() || gdb == null) return;
            try {
                whileStopped(() -> {
                    lastSnapshot = gdb.json(snapCmd());
                    emitRaw("snapshot", lastSnapshot);
                    return null;
                });
            } catch (Exception e) {
                log.debug("자동 갱신 실패", e);
            }
        });
    }

    // ------------------------------------------------------------------ 브라우저에서 오는 동작

    public void input(String data) {
        OutputStream o = qemuIn;
        if (o == null) return;
        try {
            synchronized (this) {
                o.write(data.getBytes(StandardCharsets.UTF_8));
                o.flush();
            }
        } catch (IOException e) {
            log.debug("콘솔 입력 실패", e);
        }
    }

    public void setAutoRefresh(boolean on) {
        autoRefresh = on;
        emitStatus();
    }

    public void pause() {
        ctl.submit(() -> {
            if (!running()) return;
            try {
                boolean journey = state == State.JOURNEY_WAIT;
                interrupt();
                if (journey && journeyHit()) {
                    journeyStopped();
                    return;
                }
                state = State.PAUSED;
                pausedInJourney = journey;
                lastSnapshot = gdb.json(snapCmd());
                emitRaw("snapshot", lastSnapshot);
                emitStatus();
            } catch (Exception e) {
                error("멈추지 못했습니다", e);
            }
        });
    }

    private boolean pausedInJourney;

    public void resume() {
        ctl.submit(() -> {
            try {
                if (state == State.PAUSED) {
                    gdb.call("-exec-continue");
                    state = pausedInJourney ? State.JOURNEY_WAIT : State.RUNNING;
                    emitStatus();
                } else if (state == State.JOURNEY_STOP) {
                    doJourneyNext();
                }
            } catch (Exception e) {
                error("계속 실행하지 못했습니다", e);
            }
        });
    }

    public void journeyStart(String name, String sys) {
        ctl.submit(() -> {
            if (gdb == null) return;
            try {
                if (state == State.JOURNEY_STOP || state == State.PAUSED) {
                    lastJourney = gdb.json("-xv6-journey-start " + arg(name) + " " + arg(sys));
                    gdb.call("-exec-continue");
                    state = State.JOURNEY_WAIT;
                } else {
                    state = State.JOURNEY_WAIT;
                    lastJourney = whileStopped(() -> gdb.json("-xv6-journey-start " + arg(name) + " " + arg(sys)));
                    // whileStopped 안에서 바로 첫 단계에 걸렸으면 JOURNEY_STOP이 되어 있다
                }
                pausedInJourney = false;
                emitRaw("journey", lastJourney);
                emitStatus();
            } catch (Exception e) {
                error("따라가기를 시작하지 못했습니다", e);
            }
        });
    }

    public void journeyNext() {
        ctl.submit(() -> {
            try {
                doJourneyNext();
            } catch (Exception e) {
                error("다음 단계로 가지 못했습니다", e);
            }
        });
    }

    private void doJourneyNext() throws Exception {
        if (state != State.JOURNEY_STOP) return;
        String adv = gdb.json("-xv6-journey-advance");
        if (adv.contains("\"stepi\"")) {
            // iretq 한 명령만 실행한다. 드물게 다른 이유로 먼저 멈추면 다시 시도한다.
            for (int i = 0; i < 4; i++) {
                MiRecord st = runUntilStop("-exec-step-instruction", Duration.ofSeconds(5));
                lastJourney = gdb.json("-xv6-journey-after-stepi");
                if (lastJourney.contains("\"step\": \"user\"")) break;
                log.debug("stepi 뒤 아직 유저 모드 아님: {}", st.results());
            }
            lastSnapshot = gdb.json(snapCmd());
            emitRaw("snapshot", lastSnapshot);
            emitRaw("journey", lastJourney);
            emitStatus();
            return;
        }
        if (adv.contains("\"finish\"")) {
            lastJourney = "{\"active\":false}";
            gdb.call("-exec-continue");
            state = State.RUNNING;
        } else {
            lastJourney = gdb.json("-xv6-journey-state");
            gdb.call("-exec-continue");
            state = State.JOURNEY_WAIT;
        }
        emitRaw("journey", lastJourney);
        emitStatus();
    }

    public void journeyCancel() {
        ctl.submit(() -> {
            if (gdb == null) return;
            try {
                boolean stopped = state == State.JOURNEY_STOP || state == State.PAUSED;
                if (stopped) {
                    gdb.json("-xv6-journey-cancel");
                    gdb.call("-exec-continue");
                } else {
                    whileStopped(() -> gdb.json("-xv6-journey-cancel"));
                }
                state = State.RUNNING;
                pausedInJourney = false;
                lastJourney = "{\"active\":false}";
                emitRaw("journey", lastJourney);
                emitStatus();
            } catch (Exception e) {
                error("따라가기를 끝내지 못했습니다", e);
            }
        });
    }

    /** 주소 공간, 커널 스택, 페이지 테이블 따라가기 같은 조회. */
    public String query(String command) {
        return onCtl(() -> {
            if (gdb == null) throw new IllegalStateException("VM이 실행 중이 아닙니다");
            return whileStopped(() -> gdb.json(command));
        });
    }

    private volatile Integer focusPid;

    private String snapCmd() {
        Integer f = focusPid;
        return f == null ? "-xv6-snapshot" : "-xv6-snapshot " + f;
    }

    /** 화면에서 고른 프로세스. 스냅샷에 그 프로세스의 커널 스택과 주소 공간이 같이 들어간다. */
    public void focus(int pid) {
        focusPid = pid;
        ctl.submit(() -> {
            if (gdb == null || state == State.STARTING || state == State.FAILED || state == State.STOPPED) return;
            try {
                whileStopped(() -> {
                    lastSnapshot = gdb.json(snapCmd());
                    emitRaw("snapshot", lastSnapshot);
                    return null;
                });
            } catch (Exception e) {
                error("상태를 읽지 못했습니다", e);
            }
        });
    }

    private static String arg(String s) {
        if (s == null || s.isBlank()) return "-";
        if (!s.matches("[A-Za-z0-9_.]{1,16}")) throw new IllegalArgumentException("이름에는 영문, 숫자, _ 만 쓸 수 있습니다: " + s);
        return s;
    }

    private void error(String what, Exception e) {
        log.warn(what, e);
        emit("error", Map.of("message", what + ": " + e.getMessage()));
    }

    public State state() { return state; }

    public String id() { return id; }

    public Path root() { return root; }

    private String raw(String type, String data) {
        return "{\"type\":\"" + type + "\",\"vm\":\"" + id + "\",\"data\":" + data + "}";
    }
}

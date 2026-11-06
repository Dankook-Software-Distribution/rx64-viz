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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * QEMU 하나와 거기 붙은 gdb 하나.
 *
 * 제어 동작(멈춤, 계속, 스냅샷, 따라가기)은 전부 {@link #ctl} 스레드에서 순서대로 실행한다.
 * gdb의 *stopped 알림은 리더 스레드에서 오는데, 우리가 기다리던 멈춤이면 {@link #stopWaiter}로
 * 넘기고, 아니면(브레이크포인트에 걸린 경우) ctl 스레드에 처리를 넘긴다.
 */
public class Vm {

    /** PAUSED: 커널이 멈춰 있다 (Pause, 중단점, step 끝). STEPPING: step/next/finish가 도는 중. */
    public enum State { STARTING, RUNNING, PAUSED, STEPPING, STOPPED, FAILED }

    private static final Logger log = LoggerFactory.getLogger(Vm.class);
    private static final int CONSOLE_BACKLOG = 64 * 1024;
    /** QEMU -m. xv6viz.py의 PHYSTOP도 이 값을 쓴다 (커널의 PHYSTOP과 같아야 한다). */
    private static final int MEM_MB = 128;

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
    private volatile String structs = "{}";

    private volatile String lastTickError;

    // Debug: 사용자가 건 중단점 "file:line" -> gdb 번호 (ctl 스레드에서만), 마지막으로 멈춘 곳
    private final Map<String, Integer> userBps = java.util.Collections.synchronizedMap(new LinkedHashMap<>());
    private volatile String lastDebug;
    private MiRecord pendingBp; // interrupt하려던 순간에 걸린 중단점

    // 명령 기록: 시스템 콜 목록. 이벤트는 ctl 스레드에서 쌓고, 새 브라우저에는 greet()에서 통째로 보낸다.
    private volatile boolean recording;
    private final List<JsonNode> recordEvents = new ArrayList<>();

    private Process qemu;
    private OutputStream qemuIn;
    private volatile GdbMi gdb;
    private Path workDir;
    private volatile int generation;

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
        emit(message(type, fields));
    }

    /** 이미 JSON인 데이터를 감싸서 보낸다. */
    private void emitRaw(String type, String data) {
        emit(raw(type, data));
    }

    private Map<String, Object> statusFields() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("state", state.name());
        m.put("autoRefresh", autoRefresh);
        m.put("failure", failure);
        m.put("cpus", cpus);
        m.put("kernel", root.getFileName().toString());
        return m;
    }

    private void emitStatus() {
        emit("status", statusFields());
    }

    private String message(String type, Map<String, Object> fields) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", type);
        m.put("vm", id);
        m.putAll(fields);
        return json.writeValueAsString(m);
    }

    /**
     * 새로 접속한 브라우저에 현재 상태를 보낸다. 세션은 이미 리스너 목록에 들어 있어야 한다.
     * 콘솔 출력은 console 잠금 안에서 버퍼에 쌓고 내보내므로, 리플레이 앞뒤로 같은 출력이
     * 두 번 가거나 빠지지 않는다.
     */
    public void greet(Consumer<String> out) {
        out.accept(message("status", statusFields()));
        synchronized (console) {
            out.accept(message("console", Map.of("data", console.toString(), "replay", true)));
        }
        out.accept(raw("structs", structs));
        if (lastSnapshot != null) out.accept(raw("snapshot", lastSnapshot));
        out.accept(recordMessage(true, List.of()));
        out.accept(message("breakpoints", Map.of("data", bpList())));
        String d = lastDebug;
        if (d != null && state == State.PAUSED) out.accept(raw("debug", d));
    }

    // ------------------------------------------------------------------ 시작과 종료

    public void start() {
        ctl.submit(this::doStart);
    }

    private void doStart() {
        generation++; // 이전 QEMU의 콘솔 스레드가 종료를 STOPPED로 알리지 않게 먼저 올린다
        teardown();
        state = State.STARTING;
        failure = null;
        lastSnapshot = null;
        recording = false;
        synchronized (recordEvents) { recordEvents.clear(); }
        userBps.clear();
        lastDebug = null;
        focusPid = null;
        pendingBp = null;
        queuedStop = null;
        targetStopped = true;
        emit(recordMessage(true, List.of()));
        emit("breakpoints", Map.of("data", bpList()));
        synchronized (console) {
            console.setLength(0);
            emit("console", Map.of("data", "", "reset", true));
        }
        emitStatus();
        try {
            Path kernel = root.resolve("kernel/kernel");
            if (!Files.exists(kernel)) {
                throw new IOException(kernel + " not found. Run make in " + root + " first.");
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
                    "-kernel", kernel.toString(), "-m", MEM_MB + "M", "-smp", String.valueOf(cpus),
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
            gdb.call("-interpreter-exec console \"set $xv6_phystop = " + MEM_MB * 1024L * 1024L + "\"");
            gdb.call("-interpreter-exec console \"source " + py + "\"");
            structs = gdb.json("-xv6-structs");
            emitRaw("structs", structs);
            gdb.call("-exec-continue");
            state = State.RUNNING;
            emitStatus();
        } catch (Exception e) {
            log.error("VM 시작 실패", e);
            teardown();
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
                    emit("console", Map.of("data", s));
                }
            }
        } catch (IOException e) {
            log.debug("콘솔 종료", e);
        }
        if (gen == generation && state != State.STOPPED && state != State.STARTING && state != State.FAILED) {
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

    // gdb가 알려 준 실행 상태. 우리가 기다리지 않던 멈춤은 queuedStop에 두고 ctl에서 처리한다.
    private volatile boolean targetStopped = true;
    private volatile MiRecord queuedStop;
    private MiRecord consumedStop; // interrupt()가 대신 처리한 멈춤 (ctl 스레드)

    private void onAsync(MiRecord rec) {
        if (!rec.isExecAsync()) return;
        if ("running".equals(rec.cls())) {
            targetStopped = false;
            return;
        }
        if (!"stopped".equals(rec.cls())) return;
        targetStopped = true;
        CompletableFuture<MiRecord> w = stopWaiter.getAndSet(null);
        if (w != null) {
            w.complete(rec);
        } else if (state == State.STARTING) {
            // gdb가 처음 붙을 때의 멈춤. doStart()가 직접 이어서 실행한다.
        } else {
            queuedStop = rec;
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
        return state == State.RUNNING;
    }

    /**
     * VM을 멈춘다. 기록용 브레이크포인트(멈추지 않는 것)를 gdb가 넘어가는 순간에 보낸 interrupt는
     * 묻힐 수 있어서, 1초씩 기다리며 다시 보낸다. 늦게 온 멈춤은 onUnexpectedStop이 다시 돌린다.
     */
    private void interrupt() throws Exception {
        // 이미 멈춰 있다 (막 끝난 step, 막 걸린 중단점, 늦게 온 interrupt): 보내지 않고 그 멈춤을 대신 처리한다
        MiRecord q = queuedStop;
        if (targetStopped && q != null) {
            queuedStop = null;
            consumedStop = q;
            if (isUserBp(q)) pendingBp = q;
            return;
        }
        java.util.concurrent.TimeoutException last = null;
        for (int i = 0; i < 5; i++) {
            try {
                MiRecord r = runUntilStop("-exec-interrupt", Duration.ofSeconds(1));
                if (isUserBp(r)) pendingBp = r;
                return;
            } catch (java.util.concurrent.TimeoutException e) {
                last = e;
                log.debug("interrupt 응답 없음, 다시 보냄 ({})", i + 1);
            }
        }
        throw last;
    }

    private JsonNode tree(String command) {
        return json.readTree(gdb.json(command));
    }

    /**
     * 우리가 기다리지 않던 멈춤: 사용자 중단점, step 끝, 또는 늦게 도착한 interrupt.
     * 기록용 브레이크포인트는 멈추지 않으니 여기 오지 않는다.
     */
    private void onUnexpectedStop(MiRecord rec) {
        if (gdb == null || rec == consumedStop) return;
        if (queuedStop == rec) queuedStop = null;
        try {
            if (state == State.STEPPING || (running() && isUserBp(rec))) debugStopped(rec);
            else if (running()) gdb.call("-exec-continue");
        } catch (Exception e) {
            log.warn("멈춤 처리 실패", e);
        }
    }

    private boolean isUserBp(MiRecord rec) {
        if (rec == null || !"breakpoint-hit".equals(rec.str("reason"))) return false;
        try {
            return userBps.containsValue(Integer.parseInt(rec.str("bkptno")));
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 멈췄다: 호출 스택과 변수, 그 CPU의 프로세스 스냅샷을 보낸다. stop은 *stopped (없으면 Pause). */
    private void debugStopped(MiRecord stop) {
        state = State.PAUSED;
        emitStatus();
        try {
            JsonNode n = tree("-xv6-debug-state");
            if (n instanceof tools.jackson.databind.node.ObjectNode o) {
                String reason = stop == null ? "pause" : stop.str("reason");
                o.put("reason", reason == null ? "stop" : reason);
                if (stop != null && stop.str("return-value") != null) o.put("returned", stop.str("return-value"));
            }
            sendDebug(n);
        } catch (Exception e) {
            error("Cannot read debug state", e);
        }
        try {
            refreshSnapshot();
        } catch (Exception e) {
            error("Cannot read state", e);
        }
    }

    /** 멈춘 곳(-xv6-debug-state)을 기억하고 보낸다. 그 CPU의 프로세스를 본다. */
    private void sendDebug(JsonNode n) {
        if (n.hasNonNull("pid")) focusPid = n.path("pid").asInt();
        lastDebug = json.writeValueAsString(n);
        emitRaw("debug", lastDebug);
    }

    private void refreshSnapshot() {
        lastSnapshot = gdb.json(snapCmd());
        emitRaw("snapshot", lastSnapshot);
    }

    /** 멈춘 상태에서 작업을 하고, 원래 돌고 있었으면 다시 돌린다. work가 실패해도 다시 돌린다. */
    private <T> T whileStopped(Callable<T> work) throws Exception {
        if (state == State.STEPPING) throw new IllegalStateException("a step is running");
        boolean wasRunning = running();
        if (wasRunning) interrupt();
        try {
            return work.call();
        } finally {
            if (wasRunning) {
                // interrupt하려던 순간 사용자 중단점에 걸렸으면 그대로 멈춘다
                MiRecord bp = pendingBp;
                pendingBp = null;
                if (bp != null) debugStopped(bp);
                else gdb.call("-exec-continue");
            }
        }
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
        if (!running() || gdb == null || (!autoRefresh && !recording)) return;
        ctl.submit(() -> {
            if (!running() || gdb == null) return;
            try {
                whileStopped(() -> {
                    if (autoRefresh) refreshSnapshot();
                    if (recording) pollRecord(false);
                    return null;
                });
                lastTickError = null;
            } catch (Exception e) {
                // 같은 오류가 매초 반복되면 처음 한 번만 화면에 보낸다
                String m = String.valueOf(e.getMessage());
                if (!m.equals(lastTickError)) error("Cannot read state", e);
                else log.debug("자동 갱신 실패", e);
                lastTickError = m;
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

    /** Pause: 멈추고 Debug 상태를 보낸다. step이 너무 오래 걸릴 때도 이걸로 끊는다. */
    public void pause() {
        ctl.submit(() -> {
            if (state != State.RUNNING && state != State.STEPPING) return;
            try {
                interrupt();
                MiRecord bp = pendingBp;
                pendingBp = null;
                debugStopped(bp);
            } catch (Exception e) {
                error("Cannot pause", e);
            }
        });
    }

    public void resume() {
        ctl.submit(() -> {
            if (state != State.PAUSED) return;
            try {
                gdb.json("-xv6-init"); // riscv-style: 물리 주소 모드를 켠 채로 돌린다 (기록용 브레이크포인트)
                gdb.call("-exec-continue");
                state = State.RUNNING;
                lastDebug = null;
                emitStatus();
            } catch (Exception e) {
                error("Cannot resume", e);
            }
        });
    }

    // ------------------------------------------------------------------ Debug

    /** kind: next(Step Over), step(Step Into), finish(Step Out). 끝나면 *stopped가 와서 debugStopped. */
    public void debugStep(String kind) {
        String cmd = switch (kind) {
            case "next" -> "-exec-next";
            case "step" -> "-exec-step";
            case "finish" -> "-exec-finish";
            case "nexti" -> "-exec-next-instruction";
            case "stepi" -> "-exec-step-instruction";
            default -> null;
        };
        ctl.submit(() -> {
            if (gdb == null || state != State.PAUSED || cmd == null) return;
            try {
                gdb.json("-xv6-step-prep");
                state = State.STEPPING;
                emitStatus();
                gdb.call(cmd);
            } catch (Exception e) {
                // 예: 가장 바깥 프레임에서 finish. 멈춘 채로 돌아온다.
                state = State.PAUSED;
                emitStatus();
                error("Cannot step", e);
                try {
                    sendDebug(tree("-xv6-debug-state"));
                } catch (Exception ignore) {
                    // 상태를 못 읽어도 멈춘 채로 둔다
                }
            }
        });
    }

    /** 호출 스택에서 고른 프레임의 변수 */
    public void debugFrame(int level) {
        ctl.submit(() -> {
            if (gdb == null || state != State.PAUSED) return;
            try {
                sendDebug(tree("-xv6-debug-state " + Math.max(0, level)));
            } catch (Exception e) {
                error("Cannot read frame", e);
            }
        });
    }

    /** cpus[]에서 고른 CPU로 바꾼다. step은 이 CPU에서 한다. */
    public void debugThread(int cpu) {
        ctl.submit(() -> {
            if (gdb == null || state != State.PAUSED) return;
            try {
                sendDebug(tree("-xv6-debug-thread " + cpu));
                refreshSnapshot();
            } catch (Exception e) {
                error("Cannot select cpu", e);
            }
        });
    }

    /** 중단점 켜고 끄기. 코드가 없는 줄이면 gdb가 옮긴 줄에 건다. */
    public void breakpoint(String file, int line, boolean on) {
        if (file == null || !file.matches("[A-Za-z0-9_./-]{1,160}") || file.contains("..") || line <= 0) {
            error("Cannot set breakpoint", new IllegalArgumentException("bad location " + file + ":" + line));
            return;
        }
        ctl.submit(() -> {
            if (gdb == null || state == State.STEPPING || state == State.STARTING) return;
            String key = file + ":" + line;
            try {
                Callable<Void> work = () -> {
                    if (on && !userBps.containsKey(key)) {
                        MiRecord r = gdb.call("-break-insert " + key);
                        Object b = r.results().get("bkpt");
                        if (!(b instanceof Map<?, ?> m)) throw new IllegalStateException("no breakpoint for " + key);
                        int num = Integer.parseInt(String.valueOf(m.get("number")));
                        Object l = m.get("line");
                        String at = l != null ? file + ":" + l : key;
                        // gdb가 이미 있는 중단점의 줄로 옮겼으면 새로 만든 것은 지운다
                        if (userBps.containsKey(at)) gdb.call("-break-delete " + num);
                        else userBps.put(at, num);
                    } else if (!on && userBps.containsKey(key)) {
                        gdb.call("-break-delete " + userBps.remove(key));
                    }
                    return null;
                };
                whileStopped(work);
            } catch (Exception e) {
                error("Cannot set breakpoint", e);
            }
            emit("breakpoints", Map.of("data", bpList()));
        });
    }

    private List<Map<String, Object>> bpList() {
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (userBps) {
            for (var e : userBps.entrySet()) {
                int c = e.getKey().lastIndexOf(':');
                out.add(Map.of("file", e.getKey().substring(0, c), "line", Integer.parseInt(e.getKey().substring(c + 1)), "n", e.getValue()));
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 명령 기록

    /** Trace 켜기. 끌 때까지(또는 3000개가 찰 때까지) 모든 시스템 콜을 남긴다. 명령은 셸에서 친다. */
    public void recordStart() {
        ctl.submit(() -> {
            if (gdb == null || state != State.RUNNING || recording) return;
            try {
                whileStopped(() -> gdb.json("-xv6-record-start"));
                synchronized (recordEvents) { recordEvents.clear(); }
                recording = true;
                emit(recordMessage(true, List.of()));
            } catch (Exception e) {
                error("Cannot start trace", e);
            }
        });
    }

    public void recordStop() {
        ctl.submit(() -> {
            if (gdb == null || !recording || state == State.STEPPING) return;
            try {
                whileStopped(() -> {
                    pollRecord(true);
                    return null;
                });
            } catch (Exception e) {
                error("Cannot stop trace", e);
            }
        });
    }

    /** 지금까지 남긴 것을 비운다. 켜져 있으면 계속 남긴다. */
    public void recordClear() {
        ctl.submit(() -> {
            if (gdb == null || state == State.STEPPING || state == State.STARTING) return;
            try {
                whileStopped(() -> gdb.json("-xv6-record-clear"));
                synchronized (recordEvents) { recordEvents.clear(); }
                emit(recordMessage(true, List.of()));
            } catch (Exception e) {
                error("Cannot clear trace", e);
            }
        });
    }

    /** VM이 멈춘 상태에서 부른다. 새 이벤트를 받아 보내고, 끝났으면 브레이크포인트를 지운다. */
    private void pollRecord(boolean stop) {
        int since;
        synchronized (recordEvents) { since = recordEvents.size(); }
        JsonNode r = tree("-xv6-record-poll " + since);
        List<JsonNode> evs = new ArrayList<>();
        r.path("events").forEach(evs::add);
        synchronized (recordEvents) { recordEvents.addAll(evs); }
        JsonNode before = pending;
        pending = r.path("pending");
        boolean ended = stop || r.path("done").asBoolean();
        if (ended) {
            gdb.json("-xv6-record-stop");
            recording = false;
            pending = null;
        }
        // 바뀐 것이 있을 때만 보낸다 (매초 보내면 화면의 입력칸이 다시 그려진다)
        if (!evs.isEmpty() || ended || !java.util.Objects.equals(before, pending)) emit(recordMessage(false, evs));
    }

    private volatile JsonNode pending;

    private String recordMessage(boolean reset, List<JsonNode> events) {
        List<JsonNode> evs = events;
        if (reset) {
            synchronized (recordEvents) { evs = new ArrayList<>(recordEvents); }
        }
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("reset", reset);
        d.put("active", recording);
        d.put("events", evs);
        d.put("pending", recording && pending != null ? pending : List.of());
        return message("record", Map.of("data", d));
    }

    /** 기록한 시스템 콜 하나의 단계와 직후 상태. */
    public String recordDetail(int i) {
        return query("-xv6-record-detail " + i);
    }

    /** 주소 공간, 커널 스택, 페이지 테이블 따라가기 같은 조회. */
    public String query(String command) {
        return onCtl(() -> {
            if (gdb == null) throw new IllegalStateException("VM is not running");
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
            if (gdb == null || state != State.RUNNING && state != State.PAUSED) return;
            try {
                whileStopped(() -> {
                    refreshSnapshot();
                    return null;
                });
            } catch (Exception e) {
                error("Cannot read state", e);
            }
        });
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

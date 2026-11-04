package dev.xv6viz.vm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** gdb를 MI 모드로 띄우고 명령/응답을 토큰으로 짝지어 준다. */
public final class GdbMi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GdbMi.class);

    private final Process proc;
    private final OutputStream in;
    private final AtomicInteger nextToken = new AtomicInteger(1);
    private final Map<Integer, CompletableFuture<MiRecord>> pending = new ConcurrentHashMap<>();
    private final Consumer<MiRecord> asyncListener;

    public GdbMi(Path workDir, Consumer<MiRecord> asyncListener) throws IOException {
        this.asyncListener = asyncListener;
        this.proc = new ProcessBuilder("gdb", "--interpreter=mi3", "-nx", "-q")
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        this.in = proc.getOutputStream();
        Thread t = new Thread(this::readLoop, "gdb-mi-reader");
        t.setDaemon(true);
        t.start();
    }

    private void readLoop() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                MiRecord rec;
                try {
                    rec = MiRecord.parse(line);
                } catch (RuntimeException e) {
                    log.warn("MI 파싱 실패: {}", line, e);
                    continue;
                }
                if (rec == null) continue;
                if (rec.isResult() && rec.token() != null) {
                    CompletableFuture<MiRecord> f = pending.remove(rec.token());
                    if (f != null) f.complete(rec);
                } else if (rec.isStream()) {
                    if (rec.kind() != '~') log.debug("gdb: {}", rec.stream().strip());
                } else {
                    asyncListener.accept(rec);
                }
            }
        } catch (IOException e) {
            log.debug("gdb 출력 종료", e);
        } finally {
            pending.values().forEach(f -> f.completeExceptionally(new IOException("gdb가 종료되었습니다")));
        }
    }

    public CompletableFuture<MiRecord> send(String command) {
        int token = nextToken.getAndIncrement();
        CompletableFuture<MiRecord> f = new CompletableFuture<>();
        pending.put(token, f);
        try {
            synchronized (in) {
                in.write((token + command + "\n").getBytes(StandardCharsets.UTF_8));
                in.flush();
            }
        } catch (IOException e) {
            pending.remove(token);
            f.completeExceptionally(e);
        }
        return f;
    }

    /** 명령을 보내고 ^done/^running을 기다린다. ^error면 예외. */
    public MiRecord call(String command, Duration timeout) {
        try {
            MiRecord r = send(command).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if ("error".equals(r.cls())) {
                throw new GdbException(command + ": " + r.str("msg"));
            }
            return r;
        } catch (GdbException e) {
            throw e;
        } catch (Exception e) {
            throw new GdbException(command + ": " + e.getMessage(), e);
        }
    }

    public MiRecord call(String command) {
        return call(command, Duration.ofSeconds(10));
    }

    /** -xv6-* 명령의 json 결과. */
    public String json(String command) {
        String j = call(command).str("json");
        if (j == null) throw new GdbException(command + ": json 결과 없음");
        return j;
    }

    public boolean alive() {
        return proc.isAlive();
    }

    @Override
    public void close() {
        proc.destroy();
        try {
            if (!proc.waitFor(2, TimeUnit.SECONDS)) proc.destroyForcibly();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            proc.destroyForcibly();
        }
    }

    public static final class GdbException extends RuntimeException {
        public GdbException(String m) { super(m); }
        public GdbException(String m, Throwable c) { super(m, c); }
    }
}

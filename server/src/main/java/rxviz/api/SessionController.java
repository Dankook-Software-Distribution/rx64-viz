package rxviz.api;

import java.util.List;
import java.util.concurrent.TimeUnit;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import rxviz.config.KernelRegistry;
import rxviz.session.BootService;
import rxviz.session.Session;
import rxviz.session.SessionService;

@RestController
@RequestMapping("/api")
public class SessionController {

    private final KernelRegistry kernels;
    private final SessionService sessions;
    private final BootService boots;

    public SessionController(KernelRegistry kernels, SessionService sessions, BootService boots) {
        this.kernels = kernels;
        this.sessions = sessions;
        this.boots = boots;
    }

    public record CreateRequest(String kernel) {
    }

    public record InputRequest(String data) {
    }

    public record RunRequest(String command, Integer timeoutSeconds) {
    }

    @GetMapping("/kernels")
    public List<Dto.KernelDto> kernels() {
        return kernels.all().values().stream().map(k -> Dto.KernelDto.of(k, boots)).toList();
    }

    /** The kernel's boot recording (recorded once, at first start). */
    @GetMapping("/kernels/{id}/boot")
    public Dto.BootDto boot(@PathVariable String id) {
        return Dto.BootDto.of(id, boots.boot(id));
    }

    /** A source file of a kernel tree (kernel/... or user/...), as lines. */
    @GetMapping("/kernels/{id}/source")
    public Dto.SourceDto source(@PathVariable String id, @RequestParam String path) throws Exception {
        var file = kernels.get(id).sourceFile(path)
                .orElseThrow(() -> new SessionService.NotFoundException("no source file " + path));
        return new Dto.SourceDto(id, path, java.nio.file.Files.readAllLines(file));
    }

    /** The shell: the one running guest (null when none). */
    @GetMapping("/shell")
    public Dto.SessionDto shell() {
        Session s = sessions.current();
        return s == null ? null : Dto.SessionDto.of(s);
    }

    /** Boot a fresh shell on a kernel, replacing the current one. */
    @PostMapping("/shell")
    public Dto.SessionDto newShell(@RequestBody CreateRequest req) throws Exception {
        return Dto.SessionDto.of(sessions.replace(req.kernel()));
    }

    @PostMapping("/sessions")
    public Dto.SessionDto create(@RequestBody CreateRequest req) throws Exception {
        return Dto.SessionDto.of(sessions.create(req.kernel()));
    }

    @DeleteMapping("/sessions/{id}")
    public void delete(@PathVariable String id) {
        sessions.delete(id);
    }

    @GetMapping(value = "/sessions/{id}/console", produces = MediaType.TEXT_PLAIN_VALUE)
    public String console(@PathVariable String id) {
        return sessions.get(id).console();
    }

    @PostMapping("/sessions/{id}/input")
    public void input(@PathVariable String id, @RequestBody InputRequest req) {
        sessions.get(id).input(req.data());
    }

    /** Wait for the first prompt (boot). */
    @PostMapping("/sessions/{id}/ready")
    public Dto.SessionDto ready(@PathVariable String id) throws Exception {
        Session s = sessions.get(id);
        s.awaitReady(120_000);
        return Dto.SessionDto.of(s);
    }

    /** Type a command and wait until its recording is indexed. */
    @PostMapping("/sessions/{id}/run")
    public Dto.RecDto run(@PathVariable String id, @RequestBody RunRequest req) throws Exception {
        int t = req.timeoutSeconds() == null ? 600 : req.timeoutSeconds();
        Session.Rec r = sessions.get(id).run(req.command()).get(t, TimeUnit.SECONDS);
        return Dto.RecDto.of(r);
    }
}

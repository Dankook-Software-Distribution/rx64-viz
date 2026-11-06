package dev.xv6viz.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import dev.xv6viz.vm.Vm;
import dev.xv6viz.vm.VmManager;

@RestController
@RequestMapping("/api")
public class ApiController {

    private final VmManager vms;

    public ApiController(VmManager vms) {
        this.vms = vms;
    }

    @GetMapping(value = "/walk", produces = MediaType.APPLICATION_JSON_VALUE)
    public String walk(@RequestParam String vm, @RequestParam int pid, @RequestParam String va,
                       @RequestParam(defaultValue = "user") String root) {
        if (!va.matches("(0x)?[0-9a-fA-F]{1,16}")) throw new IllegalArgumentException("not a hex address: " + va);
        if (!root.equals("user") && !root.equals("kernel")) throw new IllegalArgumentException("root must be user or kernel");
        return vms.get(vm).query("-xv6-walk " + pid + " " + (va.startsWith("0x") ? va : "0x" + va) + " " + root);
    }

    @GetMapping(value = "/physmem", produces = MediaType.APPLICATION_JSON_VALUE)
    public String physmem(@RequestParam String vm) {
        return vms.get(vm).query("-xv6-physmem");
    }

    /** 기록한 시스템 콜 하나: 트랩 단계마다의 레지스터와 스택, 시스템 콜 직후의 프로세스 상태. */
    @GetMapping(value = "/record", produces = MediaType.APPLICATION_JSON_VALUE)
    public String record(@RequestParam String vm, @RequestParam int i) {
        return vms.get(vm).recordDetail(i);
    }

    /** Source 뷰에서 열 수 있는 파일: kernel/, user/ 아래의 .c .h .S */
    @GetMapping(value = "/files", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<String> files(@RequestParam String vm) throws IOException {
        Path root = vms.get(vm).root();
        List<String> out = new ArrayList<>();
        for (String dir : List.of("kernel", "user")) {
            Path d = root.resolve(dir);
            if (!Files.isDirectory(d)) continue;
            try (Stream<Path> s = Files.walk(d)) {
                s.filter(Files::isRegularFile)
                        .map(f -> root.relativize(f).toString().replace('\\', '/'))
                        .filter(f -> f.endsWith(".c") || f.endsWith(".h") || f.endsWith(".S"))
                        .sorted()
                        .forEach(out::add);
            }
        }
        return out;
    }

    /** xv6 소스 파일. 그 커널 폴더 밖은 읽지 않는다. */
    @GetMapping(value = "/source", produces = "text/plain;charset=UTF-8")
    public String source(@RequestParam String vm, @RequestParam String file) throws IOException {
        Vm v = vms.get(vm);
        Path p = v.root().resolve(file).normalize();
        if (!p.startsWith(v.root()) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("no such source file: " + file);
        }
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> bad(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("error", e.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, String>> fail(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of("error", String.valueOf(e.getMessage())));
    }
}

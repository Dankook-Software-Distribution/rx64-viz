package dev.xv6viz.web;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

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
    public String walk(@RequestParam String vm, @RequestParam int pid, @RequestParam String va) {
        if (!va.matches("(0x)?[0-9a-fA-F]{1,16}")) throw new IllegalArgumentException("16진수 주소가 아닙니다: " + va);
        return vms.get(vm).query("-xv6-walk " + pid + " " + (va.startsWith("0x") ? va : "0x" + va));
    }

    @GetMapping(value = "/physmem", produces = MediaType.APPLICATION_JSON_VALUE)
    public String physmem(@RequestParam String vm) {
        return vms.get(vm).query("-xv6-physmem");
    }

    /** xv6 소스 파일. 그 커널 폴더 밖은 읽지 않는다. */
    @GetMapping(value = "/source", produces = "text/plain;charset=UTF-8")
    public String source(@RequestParam String vm, @RequestParam String file) throws IOException {
        Vm v = vms.get(vm);
        Path p = v.root().resolve(file).normalize();
        if (!p.startsWith(v.root()) || !Files.isRegularFile(p)) {
            throw new IllegalArgumentException("소스 파일이 없습니다: " + file);
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

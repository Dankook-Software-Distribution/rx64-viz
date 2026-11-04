package dev.xv6viz;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * @param kernels 이름 → xv6 소스 폴더. kernel/kernel 과 fs.img 가 빌드되어 있어야 한다.
 *                (Path로 바로 받으면 Spring이 리소스 경로로 해석하므로 문자열로 받는다.)
 * @param cpus    QEMU -smp 값
 */
@ConfigurationProperties("xv6viz")
public record Xv6Props(Map<String, String> kernels, int cpus) {
    public Xv6Props {
        if (kernels == null || kernels.isEmpty()) {
            kernels = new LinkedHashMap<>();
            kernels.put("riscv", "../rx64-riscv-style");
            kernels.put("x86", "../rx64-x86-style");
        }
        if (cpus <= 0) cpus = 3;
    }

    public static Path path(String dir) {
        return Path.of(dir).toAbsolutePath().normalize();
    }
}

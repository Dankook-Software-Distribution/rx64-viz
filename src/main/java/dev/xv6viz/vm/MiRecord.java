package dev.xv6viz.vm;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GDB/MI 출력 한 줄.
 *
 * <pre>
 *   12^done,json="{...}"
 *   *stopped,reason="breakpoint-hit",thread-id="1",...
 *   ~"console text\n"
 * </pre>
 *
 * 값은 String, Map(tuple), List(list) 셋 중 하나로 파싱한다.
 */
public record MiRecord(Integer token, char kind, String cls, Map<String, Object> results, String stream) {

    public String str(String key) {
        Object v = results.get(key);
        return v instanceof String s ? s : null;
    }

    public boolean isResult() { return kind == '^'; }
    public boolean isExecAsync() { return kind == '*'; }
    public boolean isStream() { return kind == '~' || kind == '@' || kind == '&'; }

    public static MiRecord parse(String line) {
        if (line.isEmpty() || line.startsWith("(gdb)")) return null;
        Parser p = new Parser(line);
        Integer token = null;
        int start = p.pos;
        while (p.pos < line.length() && Character.isDigit(line.charAt(p.pos))) p.pos++;
        if (p.pos > start) token = Integer.parseInt(line.substring(start, p.pos));
        if (p.pos >= line.length()) return null;
        char kind = line.charAt(p.pos++);
        switch (kind) {
            case '~', '@', '&' -> {
                return new MiRecord(token, kind, null, Map.of(), p.cstring());
            }
            case '^', '*', '+', '=' -> {
                int s = p.pos;
                while (p.pos < line.length() && line.charAt(p.pos) != ',') p.pos++;
                String cls = line.substring(s, p.pos);
                Map<String, Object> res = new LinkedHashMap<>();
                while (p.pos < line.length() && line.charAt(p.pos) == ',') {
                    p.pos++;
                    p.result(res);
                }
                return new MiRecord(token, kind, cls, res, null);
            }
            default -> {
                return null;
            }
        }
    }

    private static final class Parser {
        final String s;
        int pos;

        Parser(String s) { this.s = s; }

        void result(Map<String, Object> into) {
            int eq = s.indexOf('=', pos);
            String name = s.substring(pos, eq);
            pos = eq + 1;
            Object v = value();
            // 같은 이름이 여러 번 나오면(드물다) 리스트로 모은다
            into.merge(name, v, (a, b) -> {
                List<Object> l = a instanceof List<?> ? new ArrayList<>((List<?>) a) : new ArrayList<>(List.of(a));
                l.add(b);
                return l;
            });
        }

        Object value() {
            char c = s.charAt(pos);
            if (c == '"') return cstring();
            if (c == '{') {
                pos++;
                Map<String, Object> m = new LinkedHashMap<>();
                while (s.charAt(pos) != '}') {
                    result(m);
                    if (s.charAt(pos) == ',') pos++;
                }
                pos++;
                return m;
            }
            if (c == '[') {
                pos++;
                List<Object> l = new ArrayList<>();
                while (s.charAt(pos) != ']') {
                    char d = s.charAt(pos);
                    if (d == '"' || d == '{' || d == '[') {
                        l.add(value());
                    } else {
                        Map<String, Object> m = new LinkedHashMap<>();
                        result(m);
                        l.add(m);
                    }
                    if (s.charAt(pos) == ',') pos++;
                }
                pos++;
                return l;
            }
            throw new IllegalStateException("MI 값 파싱 실패 @" + pos + ": " + s);
        }

        /** C 문자열. gdb는 비ASCII 바이트를 \ooo로 내보내므로 바이트로 모아서 UTF-8로 푼다. */
        String cstring() {
            if (pos >= s.length() || s.charAt(pos) != '"') return "";
            pos++;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            while (pos < s.length()) {
                char c = s.charAt(pos++);
                if (c == '"') break;
                if (c != '\\') {
                    if (c < 0x80) out.write(c);
                    else out.writeBytes(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                char e = s.charAt(pos++);
                switch (e) {
                    case 'n' -> out.write('\n');
                    case 't' -> out.write('\t');
                    case 'r' -> out.write('\r');
                    case 'e' -> out.write(0x1b);
                    case 'a' -> out.write(0x07);
                    case 'b' -> out.write('\b');
                    case 'f' -> out.write('\f');
                    case 'v' -> out.write(0x0b);
                    default -> {
                        if (e >= '0' && e <= '7') {
                            int v = e - '0';
                            for (int k = 0; k < 2 && pos < s.length() && s.charAt(pos) >= '0' && s.charAt(pos) <= '7'; k++) {
                                v = v * 8 + (s.charAt(pos++) - '0');
                            }
                            out.write(v);
                        } else {
                            out.write(e);
                        }
                    }
                }
            }
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}

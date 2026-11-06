package dev.xv6viz.web;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import dev.xv6viz.vm.Vm;
import dev.xv6viz.vm.VmManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * 브라우저와 주고받는 WebSocket. 모든 메시지에 "vm"(커널 이름)이 붙는다.
 * 보내는 것: status, console, snapshot, structs, record, debug, breakpoints, error
 * 받는 것: input, pause, resume, restart, autoRefresh, focus, recordStart, recordStop, recordClear,
 *         debugNext, debugStep, debugFinish, debugFrame, debugThread, breakpoint
 */
@Component
public class Hub extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(Hub.class);

    private final VmManager vms;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public Hub(VmManager vms) {
        this.vms = vms;
        vms.all().forEach(vm -> vm.addListener(this::broadcast));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws IOException {
        WebSocketSession s = new ConcurrentWebSocketSessionDecorator(raw, 5000, 8 * 1024 * 1024);
        s.sendMessage(new TextMessage(json.writeValueAsString(
                java.util.Map.of("type", "kernels", "data", vms.all().stream().map(Vm::id).toList()))));
        // 리스너에 먼저 넣고 현재 상태를 보낸다. 콘솔 리플레이와 이후 출력의 순서는 Vm.greet()이 맞춘다.
        sessions.add(s);
        for (Vm vm : vms.all()) vm.greet(m -> send(s, m));
    }

    private static void send(WebSocketSession s, String msg) {
        try {
            s.sendMessage(new TextMessage(msg));
        } catch (Exception e) {
            log.debug("전송 실패", e);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        sessions.removeIf(s -> s.getId().equals(raw.getId()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        JsonNode m;
        try {
            m = json.readTree(message.getPayload());
        } catch (RuntimeException e) {
            log.debug("JSON이 아닌 메시지 무시: {}", message.getPayload());
            return;
        }
        Vm vm;
        try {
            vm = vms.get(m.path("vm").asString(""));
        } catch (IllegalArgumentException e) {
            log.debug("vm 없는 메시지 무시: {}", m);
            return;
        }
        switch (m.path("type").asString("")) {
            case "input" -> vm.input(m.path("data").asString(""));
            case "pause" -> vm.pause();
            case "resume" -> vm.resume();
            case "restart" -> vm.start();
            case "autoRefresh" -> vm.setAutoRefresh(m.path("on").asBoolean(true));
            case "focus" -> vm.focus(m.path("pid").asInt());
            case "recordStart" -> vm.recordStart();
            case "recordClear" -> vm.recordClear();
            case "debugNext" -> vm.debugStep("next");
            case "debugStep" -> vm.debugStep("step");
            case "debugFinish" -> vm.debugStep("finish");
            case "debugNexti" -> vm.debugStep("nexti");
            case "debugStepi" -> vm.debugStep("stepi");
            case "debugFrame" -> vm.debugFrame(m.path("level").asInt(0));
            case "debugThread" -> vm.debugThread(m.path("cpu").asInt(0));
            case "breakpoint" -> vm.breakpoint(m.path("file").asString(""), m.path("line").asInt(0), m.path("on").asBoolean(true));
            case "recordStop" -> vm.recordStop();
            default -> log.debug("알 수 없는 메시지: {}", m);
        }
    }

    private void broadcast(String msg) {
        for (WebSocketSession s : sessions) send(s, msg);
    }
}

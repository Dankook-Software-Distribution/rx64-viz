package rxviz.api;

import java.io.IOException;
import java.net.URI;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import tools.jackson.databind.json.JsonMapper;

import rxviz.session.Session;
import rxviz.session.SessionService;

/**
 * /ws/sessions/{id}: the guest console and recording progress.
 *   client -> server  {"type":"input","data":"..."}
 *   server -> client  {"type":"output","data":"..."}  (first: the backlog)
 *                     {"type":"recording","recording":{...}}
 *                     {"type":"state","state":"READY"}
 */
@Component
public class ConsoleSocket extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(ConsoleSocket.class);

    private final SessionService sessions;
    private final JsonMapper json;

    public ConsoleSocket(SessionService sessions, JsonMapper json) {
        this.sessions = sessions;
        this.json = json;
    }

    private Session guest(WebSocketSession ws) {
        URI uri = ws.getUri();
        String path = uri == null ? "" : uri.getPath();
        return sessions.get(path.substring(path.lastIndexOf('/') + 1));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        WebSocketSession ws = new ConcurrentWebSocketSessionDecorator(raw, 5000, 1 << 20);
        Session g = guest(raw);
        Session.Listener l = new Session.Listener() {
            @Override
            public void output(String text) {
                send(ws, Map.of("type", "output", "data", text));
            }

            @Override
            public void recording(Session.Rec rec) {
                send(ws, Map.of("type", "recording", "recording", Dto.RecDto.of(rec)));
            }

            @Override
            public void state(Session.State state) {
                send(ws, Map.of("type", "state", "state", state.name()));
            }
        };
        raw.getAttributes().put("listener", l);
        send(ws, Map.of("type", "output", "data", g.console()));
        send(ws, Map.of("type", "state", "state", g.state().name()));
        g.addListener(l);
    }

    @Override
    protected void handleTextMessage(WebSocketSession ws, TextMessage message) {
        Map<?, ?> m = json.readValue(message.getPayload(), Map.class);
        if ("input".equals(m.get("type")) && m.get("data") instanceof String data)
            guest(ws).input(data);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession ws, CloseStatus status) {
        Object l = ws.getAttributes().get("listener");
        if (l instanceof Session.Listener listener) {
            try {
                guest(ws).removeListener(listener);
            } catch (RuntimeException e) {
                /* session already gone */
            }
        }
    }

    private void send(WebSocketSession ws, Object msg) {
        try {
            if (ws.isOpen())
                ws.sendMessage(new TextMessage(json.writeValueAsString(msg)));
        } catch (IOException | RuntimeException e) {
            log.debug("websocket send failed", e);
        }
    }
}

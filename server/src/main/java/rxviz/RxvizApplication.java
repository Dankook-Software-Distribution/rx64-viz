package rxviz;

import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import rxviz.api.ConsoleSocket;
import rxviz.session.SessionService;

@SpringBootApplication
@ConfigurationPropertiesScan
public class RxvizApplication {

    public static void main(String[] args) {
        SpringApplication.run(RxvizApplication.class, args);
    }

    @Configuration
    @EnableWebSocket
    static class Sockets implements WebSocketConfigurer {
        private final ConsoleSocket console;

        Sockets(ConsoleSocket console) {
            this.console = console;
        }

        @Override
        public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
            registry.addHandler(console, "/ws/sessions/*").setAllowedOriginPatterns("*");
        }
    }

    @RestControllerAdvice
    static class Errors {
        @ExceptionHandler(SessionService.NotFoundException.class)
        ResponseEntity<Map<String, String>> notFound(RuntimeException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
        }

        @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
        ResponseEntity<Map<String, String>> bad(RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("error", String.valueOf(e.getMessage())));
        }
    }
}

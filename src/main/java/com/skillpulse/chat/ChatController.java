package com.skillpulse.chat;

import com.skillpulse.auth.AppUser;
import com.skillpulse.auth.AuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Learner-facing study chat. All routes are under /api/chat/**, which SecurityConfig requires a login for. */
@RestController
public class ChatController {
    private final AuthService auth;
    private final ChatService chat;

    public ChatController(AuthService auth, ChatService chat) {
        this.auth = auth;
        this.chat = chat;
    }

    /** Streams the reply as Server-Sent Events: start, meta, delta..., done (or error). */
    // No "produces" here on purpose: SseEmitter sets text/event-stream itself, and limit errors (429/503) are JSON.
    @PostMapping("/api/chat/stream")
    public SseEmitter stream(@RequestBody ChatDtos.SendRequest request) {
        AppUser user = auth.requireUser(null);
        return chat.startStream(user, request.getConversationId(), request.getMessage());
    }

    /** Same thing without streaming; used as a fallback by the page. */
    @PostMapping("/api/chat/messages")
    public ResponseEntity<Map<String, Object>> send(@RequestBody ChatDtos.SendRequest request) {
        AppUser user = auth.requireUser(null);
        Map<String, Object> result = chat.sendBlocking(user, request.getConversationId(), request.getMessage());
        if (result == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                    Collections.<String, Object>singletonMap("message", ChatService.UNAVAILABLE));
        }
        return ResponseEntity.ok(result);
    }

    @GetMapping("/api/chat/conversations")
    public List<Map<String, Object>> conversations() {
        return chat.listConversations(auth.requireUser(null));
    }

    @GetMapping("/api/chat/conversations/{id}")
    public Map<String, Object> conversation(@PathVariable("id") Long id) {
        return chat.getConversation(auth.requireUser(null), id);
    }

    @DeleteMapping("/api/chat/conversations/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") Long id) {
        chat.deleteConversation(auth.requireUser(null), id);
        return ResponseEntity.noContent().build();
    }
}

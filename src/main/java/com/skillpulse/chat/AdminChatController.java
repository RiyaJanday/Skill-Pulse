package com.skillpulse.chat;

import com.skillpulse.auth.AuthService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** Admin view of what the chat router refused. /api/admin/** already requires ADMIN; requireAdmin is a second check. */
@RestController
public class AdminChatController {
    private final AuthService auth;
    private final ChatService chat;

    public AdminChatController(AuthService auth, ChatService chat) {
        this.auth = auth;
        this.chat = chat;
    }

    @GetMapping("/api/admin/chat/summary")
    public Map<String, Object> summary() {
        auth.requireAdmin(null);
        return chat.moderationSummary();
    }

    @GetMapping("/api/admin/chat/refused")
    public List<Map<String, Object>> refused(@RequestParam(value = "category", defaultValue = "") String category,
                                             @RequestParam(value = "limit", defaultValue = "50") int limit) {
        auth.requireAdmin(null);
        return chat.refusedMessages(category, limit);
    }
}

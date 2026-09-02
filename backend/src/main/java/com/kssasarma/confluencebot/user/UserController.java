package com.kssasarma.confluencebot.user;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
public class UserController {

    private final UserPreferenceRepository prefRepo;
    private final ChatSessionRepository sessionRepo;
    private final ChatPreferenceRepository chatPrefRepo;

    public UserController(UserPreferenceRepository prefRepo,
                          ChatSessionRepository sessionRepo,
                          ChatPreferenceRepository chatPrefRepo) {
        this.prefRepo = prefRepo;
        this.sessionRepo = sessionRepo;
        this.chatPrefRepo = chatPrefRepo;
    }

    // ── User preferences ───────────────────────────────────────────────────

    @GetMapping("/preferences")
    public ResponseEntity<UserPreference> getPreferences(@AuthenticationPrincipal User user) {
        UserPreference pref = prefRepo.findByUserId(user.getId())
                .orElseGet(() -> {
                    UserPreference p = new UserPreference();
                    p.setUser(user);
                    return prefRepo.save(p);
                });
        return ResponseEntity.ok(pref);
    }

    @PatchMapping("/preferences")
    public ResponseEntity<UserPreference> updatePreferences(@AuthenticationPrincipal User user,
                                                            @RequestBody Map<String, Object> body) {
        UserPreference pref = prefRepo.findByUserId(user.getId())
                .orElseGet(() -> { UserPreference p = new UserPreference(); p.setUser(user); return p; });
        if (body.containsKey("theme")) pref.setTheme((String) body.get("theme"));
        if (body.containsKey("language")) pref.setLanguage((String) body.get("language"));
        if (body.containsKey("responseStyle")) pref.setResponseStyle((String) body.get("responseStyle"));
        if (body.containsKey("showSources")) pref.setShowSources((Boolean) body.get("showSources"));
        if (body.containsKey("showConfidence")) pref.setShowConfidence((Boolean) body.get("showConfidence"));
        return ResponseEntity.ok(prefRepo.save(pref));
    }

    // ── Chat sessions ──────────────────────────────────────────────────────

    @GetMapping("/chats")
    public ResponseEntity<List<ChatSessionDto>> getSessions(@AuthenticationPrincipal User user) {
        List<ChatSessionDto> sessions = sessionRepo
                .findByUserIdOrderByPinnedDescUpdatedAtDesc(user.getId())
                .stream().map(ChatSessionDto::from).toList();
        return ResponseEntity.ok(sessions);
    }

    @PostMapping("/chats")
    public ResponseEntity<ChatSessionDto> createSession(@AuthenticationPrincipal User user,
                                                        @RequestBody(required = false) Map<String, Object> body) {
        ChatSession s = new ChatSession();
        s.setUser(user);
        s.setChatId(java.util.UUID.randomUUID().toString());
        if (body != null && body.containsKey("title")) s.setTitle((String) body.get("title"));
        return ResponseEntity.ok(ChatSessionDto.from(sessionRepo.save(s)));
    }

    @PatchMapping("/chats/{chatId}")
    public ResponseEntity<ChatSessionDto> updateSession(@AuthenticationPrincipal User user,
                                                        @PathVariable String chatId,
                                                        @RequestBody Map<String, Object> body) {
        ChatSession s = sessionRepo.findByChatIdAndUserId(chatId, user.getId())
                .orElseThrow(() -> new IllegalArgumentException("Session not found"));
        if (body.containsKey("title")) s.setTitle((String) body.get("title"));
        if (body.containsKey("pinned")) s.setPinned((Boolean) body.get("pinned"));
        return ResponseEntity.ok(ChatSessionDto.from(sessionRepo.save(s)));
    }

    @DeleteMapping("/chats/{chatId}")
    public ResponseEntity<Void> deleteSession(@AuthenticationPrincipal User user,
                                              @PathVariable String chatId) {
        sessionRepo.deleteByChatIdAndUserId(chatId, user.getId());
        return ResponseEntity.noContent().build();
    }

    // ── Per-chat preferences ───────────────────────────────────────────────

    @GetMapping("/chats/{chatId}/preferences")
    public ResponseEntity<ChatPreference> getChatPrefs(@AuthenticationPrincipal User user,
                                                        @PathVariable String chatId) {
        ChatPreference pref = chatPrefRepo.findByChatIdAndUserId(chatId, user.getId())
                .orElseGet(() -> {
                    ChatPreference p = new ChatPreference();
                    p.setChatId(chatId);
                    p.setUser(user);
                    return chatPrefRepo.save(p);
                });
        return ResponseEntity.ok(pref);
    }

    @PatchMapping("/chats/{chatId}/preferences")
    public ResponseEntity<ChatPreference> updateChatPrefs(@AuthenticationPrincipal User user,
                                                          @PathVariable String chatId,
                                                          @RequestBody Map<String, Object> body) {
        ChatPreference pref = chatPrefRepo.findByChatIdAndUserId(chatId, user.getId())
                .orElseGet(() -> { ChatPreference p = new ChatPreference(); p.setChatId(chatId); p.setUser(user); return p; });
        if (body.containsKey("responseStyle")) pref.setResponseStyle((String) body.get("responseStyle"));
        if (body.containsKey("showSources")) pref.setShowSources((Boolean) body.get("showSources"));
        if (body.containsKey("showConfidence")) pref.setShowConfidence((Boolean) body.get("showConfidence"));
        if (body.containsKey("customPrompt")) pref.setCustomPrompt((String) body.get("customPrompt"));
        return ResponseEntity.ok(chatPrefRepo.save(pref));
    }

    public record ChatSessionDto(String chatId, String title, boolean pinned,
                                 java.time.Instant createdAt, java.time.Instant updatedAt) {
        static ChatSessionDto from(ChatSession s) {
            return new ChatSessionDto(s.getChatId(), s.getTitle(), s.isPinned(),
                    s.getCreatedAt(), s.getUpdatedAt());
        }
    }
}

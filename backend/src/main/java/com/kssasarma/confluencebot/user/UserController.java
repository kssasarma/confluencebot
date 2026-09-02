package com.kssasarma.confluencebot.user;

import com.kssasarma.confluencebot.user.dto.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Everything the signed-in user owns: their preferences, their conversations and the transcripts.
 *
 * The controller only adapts HTTP to the services — no entity ever leaves this layer, which is
 * what keeps lazily-loaded associations from being serialized outside their transaction.
 */
@Tag(name = "User", description = "Preferences, conversations and transcripts of the signed-in user")
@RestController
@RequestMapping("/api/user")
public class UserController {

    private final PreferenceService preferenceService;
    private final ChatSessionService chatSessionService;

    public UserController(PreferenceService preferenceService, ChatSessionService chatSessionService) {
        this.preferenceService = preferenceService;
        this.chatSessionService = chatSessionService;
    }

    // ── Account-wide preferences ──────────────────────────────────────────────

    @Operation(summary = "Read the account-wide preferences")
    @GetMapping("/preferences")
    public UserPreferenceResponse getPreferences(@AuthenticationPrincipal User user) {
        return preferenceService.getUserPreferences(user);
    }

    @Operation(summary = "Update the account-wide preferences (omitted fields stay unchanged)")
    @PatchMapping("/preferences")
    public UserPreferenceResponse updatePreferences(@AuthenticationPrincipal User user,
                                                    @Valid @RequestBody UserPreferenceUpdateRequest request) {
        return preferenceService.updateUserPreferences(user, request);
    }

    // ── Conversations ─────────────────────────────────────────────────────────

    @Operation(summary = "List or search the user's conversations",
            description = """
                    Returns one page, pinned conversations first and then most recently used. \
                    Supply `q` to filter by a phrase in the title or anywhere in a transcript; \
                    matching results carry the passage that matched. Follow `nextCursor` for the \
                    next page and stop when it is null — page size is capped server-side.
                    """)
    @GetMapping("/chats")
    public ChatSessionPage listSessions(@AuthenticationPrincipal User user,
                                        @RequestParam(required = false) String q,
                                        @RequestParam(required = false) String cursor,
                                        @RequestParam(required = false, defaultValue = "0") int limit) {
        return chatSessionService.listSessions(user, q, cursor, limit);
    }

    @Operation(summary = "Create a conversation",
            description = "Idempotent: an untouched, untitled conversation is reused instead of "
                    + "creating another empty one.")
    @PostMapping("/chats")
    @ResponseStatus(HttpStatus.CREATED)
    public ChatSessionResponse createSession(@AuthenticationPrincipal User user,
                                             @Valid @RequestBody(required = false) CreateChatSessionRequest request) {
        return chatSessionService.createSession(user, request == null ? null : request.title());
    }

    @Operation(summary = "Rename or pin a conversation")
    @PatchMapping("/chats/{chatId}")
    public ChatSessionResponse updateSession(@AuthenticationPrincipal User user,
                                             @PathVariable String chatId,
                                             @Valid @RequestBody UpdateChatSessionRequest request) {
        return chatSessionService.updateSession(user, chatId, request);
    }

    @Operation(summary = "Delete a conversation and its transcript")
    @DeleteMapping("/chats/{chatId}")
    public ResponseEntity<Void> deleteSession(@AuthenticationPrincipal User user,
                                              @PathVariable String chatId) {
        chatSessionService.deleteSession(user, chatId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Read the transcript of a conversation, oldest turn first")
    @GetMapping("/chats/{chatId}/messages")
    public List<ChatMessageResponse> transcript(@AuthenticationPrincipal User user,
                                                @PathVariable String chatId) {
        return chatSessionService.transcript(user, chatId);
    }

    // ── Per-conversation preference overrides ─────────────────────────────────

    @Operation(summary = "Read the per-conversation overrides (null means inherited)")
    @GetMapping("/chats/{chatId}/preferences")
    public ChatPreferenceResponse getChatPreferences(@AuthenticationPrincipal User user,
                                                     @PathVariable String chatId) {
        return preferenceService.getChatPreferences(user, chatId);
    }

    @Operation(summary = "Replace the per-conversation overrides",
            description = "The body is the complete override set: a null field means the "
                    + "conversation goes back to inheriting the account-wide value.")
    @PutMapping("/chats/{chatId}/preferences")
    public ChatPreferenceResponse replaceChatPreferences(@AuthenticationPrincipal User user,
                                                         @PathVariable String chatId,
                                                         @Valid @RequestBody ChatPreferenceRequest request) {
        return preferenceService.replaceChatPreferences(user, chatId, request);
    }
}

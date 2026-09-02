package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.ChatRequest;
import com.kssasarma.confluencebot.api.dto.ChatStreamEvent;
import com.kssasarma.confluencebot.chat.ChatQuery;
import com.kssasarma.confluencebot.chat.ChatService;
import com.kssasarma.confluencebot.user.User;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;

@Tag(name = "Chat", description = "Ask questions and receive answers grounded in Confluence documentation")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final ChatService chatService;
    private final Executor streamExecutor;
    private final ScheduledExecutorService streamScheduler;
    private final Duration streamTimeout;
    private final Duration heartbeatInterval;
    private final Duration lingerGrace;

    public ChatController(ChatService chatService,
                          @Qualifier("chatStreamExecutor") Executor streamExecutor,
                          @Qualifier("sseHeartbeatScheduler") ScheduledExecutorService streamScheduler,
                          @Value("${chat.stream.timeout:PT3M}") Duration streamTimeout,
                          @Value("${chat.stream.heartbeat-interval:PT15S}") Duration heartbeatInterval,
                          @Value("${chat.stream.linger-grace:PT2S}") Duration lingerGrace) {
        this.chatService = chatService;
        this.streamExecutor = streamExecutor;
        this.streamScheduler = streamScheduler;
        this.streamTimeout = streamTimeout;
        this.heartbeatInterval = heartbeatInterval;
        this.lingerGrace = lingerGrace;
    }

    @Operation(
            summary = "Ask a question",
            description = """
                    Embeds the query, performs hybrid (dense + lexical) search against ingested \
                    Confluence chunks, and calls the configured LLM with the retrieved context. \
                    Returns the answer and the Confluence pages used as sources, each with a \
                    direct section-anchor URL. Supply a chatId to have the exchange recorded in \
                    the caller's transcript.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer generated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ChatApiResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "answer": "To reset your password, navigate to the login page \
                                    and click **Forgot password**.",
                                      "sources": [
                                        {
                                          "pageId": "131073",
                                          "title": "Password Reset Guide",
                                          "url": "http://confluence.example.com/display/IT/Password+Reset+Guide",
                                          "anchorUrl": "http://confluence.example.com/display/IT/Password+Reset+Guide#Self-Service-Reset",
                                          "spaceKey": "IT",
                                          "score": 0.91
                                        }
                                      ],
                                      "followUpQuestions": ["How do I enable two-factor authentication?"],
                                      "chatId": "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c",
                                      "title": "How do I reset my password?"
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Query validation failed",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "503", description = "The language model is unavailable",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class)))
    })
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<ChatApiResponse> chat(@AuthenticationPrincipal User user,
                                                @Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(new ChatQuery(request.question(), request.chatId(), user)));
    }

    @Operation(
            summary = "Ask a question and stream the answer",
            description = """
                    Same pipeline as POST /api/chat, delivered as server-sent events so the answer \
                    appears while it is being written. Each event carries a JSON payload with a \
                    `type` of `sources`, `token`, `title`, `done` or `error`; the stream ends with \
                    the literal `[DONE]`. Comment frames are sent periodically as keep-alives and \
                    carry no data. Disconnecting cancels generation.
                    """)
    @ApiResponse(responseCode = "200", description = "Answer stream opened",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = ChatStreamEvent.class)))
    @PostMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamChat(@AuthenticationPrincipal User user,
                                 @Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(streamTimeout.toMillis());
        SseChatStreamAdapter adapter =
                new SseChatStreamAdapter(emitter, streamScheduler, heartbeatInterval, lingerGrace);
        ChatQuery query = new ChatQuery(request.question(), request.chatId(), user);

        try {
            // Retrieval and generation are long and blocking; keep them off the servlet thread.
            streamExecutor.execute(() -> {
                try {
                    adapter.bind(chatService.stream(query, adapter));
                } catch (Exception e) {
                    log.error("Answer stream failed to start: {}", e.getMessage(), e);
                    adapter.onFailed("The answer could not be generated. Please try again.");
                }
            });
        } catch (TaskRejectedException e) {
            log.warn("Rejected an answer stream: the chat executor is saturated");
            adapter.onFailed("The assistant is busy right now. Please try again in a moment.");
        }

        return emitter;
    }
}

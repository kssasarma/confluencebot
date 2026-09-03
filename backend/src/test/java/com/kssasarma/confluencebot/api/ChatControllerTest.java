package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;
import com.kssasarma.confluencebot.chat.ChatQuery;
import com.kssasarma.confluencebot.chat.ChatService;
import com.kssasarma.confluencebot.chat.ChatStreamHandle;
import com.kssasarma.confluencebot.chat.ChatStreamListener;
import com.kssasarma.confluencebot.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.http.MediaType;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    private static final String CHAT_ID = "0f2a5f1e-9c1c-4f1f-9a2b-6f0d5f4a1b2c";

    @Mock
    private ChatService chatService;

    private MockMvc mockMvc;

    /**
     * Keep-alives and the post-answer linger are disabled above (both durations are zero), so
     * this scheduler is never asked to run anything; it exists only to satisfy the constructor.
     */
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService, new SyncTaskExecutor(), scheduler,
                        Duration.ofMinutes(1), Duration.ZERO, Duration.ZERO))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void chat_validQuery_returns200WithAnswer() throws Exception {
        when(chatService.chat(any(ChatQuery.class)))
                .thenReturn(new ChatApiResponse("Configure X by...", List.of()));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "How do I configure X?"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Configure X by..."))
                .andExpect(jsonPath("$.sources").isArray());
    }

    @Test
    void chat_blankQuery_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_queryUnder3Chars_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "ab"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_queryOver1000Chars_returns400() throws Exception {
        String longQuery = "A".repeat(1001);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\": \"" + longQuery + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_malformedChatId_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "How do I configure X?", "chatId": "not-a-uuid"}
                                """))
                .andExpect(status().isBadRequest());
    }

    /** The UI asks for text/event-stream; answering that with JSON is what produced a 500. */
    @Test
    void streamChat_emitsSourcesTokensAndDone() throws Exception {
        when(chatService.stream(any(ChatQuery.class), any(ChatStreamListener.class)))
                .thenAnswer(invocation -> {
                    ChatStreamListener listener = invocation.getArgument(1);
                    listener.onSources(List.of(new SourceReference(
                            "123", "Guide", "http://confluence/123", "http://confluence/123", "ENG", 0.9)));
                    listener.onToken("Configure ");
                    listener.onToken("X by...");
                    listener.onCompleted(new ChatApiResponse("Configure X by...", List.of(),
                            List.of("What next?")).inConversation(CHAT_ID, "How do I configure X?"));
                    return ChatStreamHandle.NOOP;
                });

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "How do I configure X?", "chatId": "%s"}
                                """.formatted(CHAT_ID)))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString();

        assertThat(body)
                .contains("\"type\":\"sources\"")
                .contains("\"type\":\"token\"")
                .contains("Configure ")
                .contains("\"type\":\"done\"")
                .contains(CHAT_ID)
                .contains("[DONE]");
    }

    @Test
    void streamChat_reportsAFailureAsAnEventRatherThanAnError() throws Exception {
        when(chatService.stream(any(ChatQuery.class), any(ChatStreamListener.class)))
                .thenAnswer(invocation -> {
                    ChatStreamListener listener = invocation.getArgument(1);
                    listener.onFailed("The AI service is temporarily unavailable.");
                    return ChatStreamHandle.NOOP;
                });

        MvcResult result = mockMvc.perform(post("/api/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.TEXT_EVENT_STREAM, MediaType.APPLICATION_JSON)
                        .content("""
                                {"question": "How do I configure X?"}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        String body = mockMvc.perform(asyncDispatch(result))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).contains("\"type\":\"error\"").contains("temporarily unavailable");
    }
}

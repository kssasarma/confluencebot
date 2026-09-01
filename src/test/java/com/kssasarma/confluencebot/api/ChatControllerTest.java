package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.chat.ChatService;
import com.kssasarma.confluencebot.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class ChatControllerTest {

    @Mock
    private ChatService chatService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders
                .standaloneSetup(new ChatController(chatService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void chat_validQuery_returns200WithAnswer() throws Exception {
        when(chatService.chat("How do I configure X?"))
                .thenReturn(new ChatApiResponse("Configure X by...", List.of()));

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "How do I configure X?"}
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
                                {"query": ""}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_queryUnder3Chars_returns400() throws Exception {
        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query": "ab"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_queryOver1000Chars_returns400() throws Exception {
        String longQuery = "A".repeat(1001);

        mockMvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\": \"" + longQuery + "\"}"))
                .andExpect(status().isBadRequest());
    }

}

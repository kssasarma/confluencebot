package com.kssasarma.confluencebot.api;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.ChatRequest;
import com.kssasarma.confluencebot.chat.ChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Chat", description = "Ask questions and receive answers grounded in Confluence documentation")
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @Operation(
            summary = "Ask a question",
            description = """
                    Embeds the query, performs HNSW cosine similarity search against ingested \
                    Confluence chunks, and calls the configured LLM with the retrieved context. \
                    Returns the answer and the Confluence pages used as sources, each with a \
                    direct section-anchor URL.
                    """)
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Answer generated successfully",
                    content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE,
                            schema = @Schema(implementation = ChatApiResponse.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "answer": "To reset your password, navigate to the login page \
                                    and click **Forgot password**. Enter your registered email address \
                                    and click **Send reset link**.",
                                      "sources": [
                                        {
                                          "pageId": "131073",
                                          "title": "Password Reset Guide",
                                          "url": "http://confluence.example.com/display/IT/Password+Reset+Guide",
                                          "anchorUrl": "http://confluence.example.com/display/IT/Password+Reset+Guide#Self-Service-Reset",
                                          "spaceKey": "IT",
                                          "score": 0.91
                                        }
                                      ]
                                    }
                                    """))),
            @ApiResponse(responseCode = "400", description = "Query validation failed",
                    content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                            schema = @Schema(implementation = ProblemDetail.class),
                            examples = @ExampleObject(value = """
                                    {
                                      "type": "urn:confluencebot:error:validation",
                                      "title": "Validation Failed",
                                      "status": 400,
                                      "detail": "question: Query must not be blank"
                                    }
                                    """)))
    })
    @PostMapping
    public ResponseEntity<ChatApiResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ResponseEntity.ok(chatService.chat(request.question()));
    }
}

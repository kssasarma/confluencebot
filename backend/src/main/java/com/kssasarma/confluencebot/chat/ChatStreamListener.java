package com.kssasarma.confluencebot.chat;

import com.kssasarma.confluencebot.api.dto.ChatApiResponse;
import com.kssasarma.confluencebot.api.dto.SourceReference;

import java.util.List;

/**
 * Receives an answer as it is produced.
 *
 * The chat pipeline pushes into this instead of returning a transport-specific type, so the
 * service layer stays unaware of server-sent events, WebSockets or whatever comes next.
 *
 * Exactly one of {@link #onCompleted} or {@link #onFailed} is called, and it is always last.
 */
public interface ChatStreamListener {

    /** The cited pages, known before the first token is generated. */
    void onSources(List<SourceReference> sources);

    /** A fragment of the answer, ready to be appended to what the user already sees. */
    void onToken(String delta);

    /** The finished answer, including anything the transcript recorded. */
    void onCompleted(ChatApiResponse response);

    /** The answer could not be produced; the message is safe to show to the user. */
    void onFailed(String message);
}

package com.kssasarma.confluencebot.chat.title;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Replaces a conversation's clipped opening question with a short summary of what it is about.
 *
 * <p>Asynchronous by contract, not by convenience: the answer must never wait on a title. Callers
 * get a future that is already bounded — it resolves to {@link Optional#empty()} rather than
 * failing, so no call site needs to defend against a naming problem.
 */
public interface ChatTitleRefiner {

    /**
     * @return the refined title if one was produced in time, otherwise empty. Never fails.
     */
    CompletableFuture<Optional<String>> refine(TitleRefinementRequest request);
}

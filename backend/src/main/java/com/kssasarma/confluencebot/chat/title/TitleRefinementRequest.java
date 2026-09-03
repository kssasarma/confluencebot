package com.kssasarma.confluencebot.chat.title;

import com.kssasarma.confluencebot.user.User;

/**
 * Everything needed to give a conversation a better name than its opening question.
 *
 * @param user      owner of the conversation; the refinement is applied on their behalf
 * @param chatId    conversation to rename
 * @param question  the question that opened the conversation
 * @param answer    the answer it produced
 * @param firstTurn true when this exchange is the one the title was derived from — a conversation
 *                  is named once, and re-summarising it on every turn would make the sidebar
 *                  restless for no benefit
 */
public record TitleRefinementRequest(
        User user,
        String chatId,
        String question,
        String answer,
        boolean firstTurn
) {

    public boolean isRefinable() {
        return firstTurn
                && user != null
                && chatId != null && !chatId.isBlank()
                && question != null && !question.isBlank();
    }
}

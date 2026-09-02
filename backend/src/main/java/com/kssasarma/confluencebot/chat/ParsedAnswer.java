package com.kssasarma.confluencebot.chat;

import java.util.List;

/** An answer split from the follow-up questions the model appends after the marker. */
public record ParsedAnswer(String answer, List<String> followUpQuestions) {}

package com.kssasarma.confluencebot.chat;

/** A system/user message pair ready to be handed to the model. */
public record LlmPrompt(String system, String user) {}

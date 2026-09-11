package com.kssasarma.confluencebot.prompt;

import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.core.io.ClassPathResource;

/**
 * Loads a prompt template from {@code src/main/resources/prompts/**}.
 *
 * <p>Every prompt this application sends to a model lives under {@code resources/prompts} as a
 * {@code .st} file, never as a Java string literal — wording is then a file edit, not a code
 * change, and a diff to a prompt reads as a diff to a prompt rather than being buried in
 * surrounding Java. This is the single place that turns a classpath location into a usable
 * {@link PromptTemplate}, so every prompt-owning class loads the same way.
 *
 * <p>{@link ClassPathResource} resolves without a Spring {@code ApplicationContext} — deliberately
 * not {@code @Value("classpath:...")} constructor injection, which would force every direct {@code
 * new} in a unit test to also supply resources it has no reason to know about. A path that is
 * wrong or a file that fails to parse fails fast, at the call site, as an unchecked exception.
 */
public final class PromptResources {

    private PromptResources() {}

    /** @param classpathLocation path under {@code src/main/resources}, e.g. {@code "prompts/chat/system-base.st"} */
    public static PromptTemplate load(String classpathLocation) {
        try {
            return new PromptTemplate(new ClassPathResource(classpathLocation));
        } catch (Exception e) {
            throw new IllegalStateException("Could not load prompt template: " + classpathLocation, e);
        }
    }
}

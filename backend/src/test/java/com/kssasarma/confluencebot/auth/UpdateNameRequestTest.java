package com.kssasarma.confluencebot.auth;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The compact constructor trims before {@code @Size} sees the value — otherwise padding
 * whitespace that pushes a raw string past 255 characters could reject a name that is well within
 * the limit once trimmed, which is the value {@link com.kssasarma.confluencebot.auth
 * .AuthServiceImpl#updateName} actually persists.
 */
class UpdateNameRequestTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    @Test
    void constructor_trimsSurroundingWhitespace() {
        UpdateNameRequest request = new UpdateNameRequest("  Ada Lovelace  ");

        assertThat(request.name()).isEqualTo("Ada Lovelace");
    }

    @Test
    void constructor_paddedNameWithinLimitAfterTrim_passesValidation() {
        String padding = " ".repeat(10);
        String name = "A".repeat(250);
        UpdateNameRequest request = new UpdateNameRequest(padding + name + padding);

        assertThat(request.name()).hasSize(250);
        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void constructor_blankAfterTrim_failsNotBlankValidation() {
        UpdateNameRequest request = new UpdateNameRequest("   ");

        assertThat(validator.validate(request)).isNotEmpty();
    }
}

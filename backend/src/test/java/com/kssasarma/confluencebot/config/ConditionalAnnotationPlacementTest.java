package com.kssasarma.confluencebot.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.stereotype.Component;

import java.lang.annotation.Annotation;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the whole application against the wiring trap that once removed the default
 * {@code ConfidenceScorer} at startup.
 *
 * <p>{@code @ConditionalOnBean} and {@code @ConditionalOnMissingBean} are evaluated in the
 * register-bean phase, after component scanning has already registered the class's own definition.
 * On a scanned {@code @Component} the condition therefore inspects a bean factory that already
 * contains the very bean it is asking about: {@code @ConditionalOnMissingBean} sees itself, decides
 * the bean exists, and Spring removes the definition it just registered. The class vanishes with no
 * error of its own — the failure surfaces later and elsewhere, as an unsatisfied dependency in
 * whatever tried to inject it.
 *
 * <p>Both annotations belong on {@code @Bean} methods, where the condition runs before the
 * definition is registered, or on auto-configuration classes, which Spring Boot processes after all
 * user beans are known.
 */
class ConditionalAnnotationPlacementTest {

    private static final String APPLICATION_PACKAGE = "com.kssasarma.confluencebot";

    private static final List<Class<? extends Annotation>> ON_BEAN_CONDITIONS =
            List.of(ConditionalOnMissingBean.class, ConditionalOnBean.class);

    @Test
    void noScannedComponentGuardsItselfWithAnOnBeanCondition() {
        // useDefaultFilters=false: only the classes carrying these annotations, nothing else.
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        ON_BEAN_CONDITIONS.forEach(annotation -> scanner.addIncludeFilter(new AnnotationTypeFilter(annotation)));

        Set<BeanDefinition> conditioned = scanner.findCandidateComponents(APPLICATION_PACKAGE);

        assertThat(conditioned)
                .describedAs("classes carrying both a stereotype and an @ConditionalOn(Missing)Bean "
                        + "- the condition matches the scanned definition itself and deletes it; "
                        + "move the condition to a @Bean method")
                .filteredOn(ConditionalAnnotationPlacementTest::isStereotyped)
                .extracting(BeanDefinition::getBeanClassName)
                .isEmpty();
    }

    /**
     * Read from the scanned metadata rather than the class: {@code ClassPathScanningCandidate
     * ComponentProvider} does not load the classes it finds, so {@code getResolvableType()} on its
     * definitions resolves to {@code Object} and would quietly match nothing.
     */
    private static boolean isStereotyped(BeanDefinition definition) {
        // @Service, @Repository and @RestController are meta-annotated with @Component, which
        // MergedAnnotations traverses for us.
        return definition instanceof AnnotatedBeanDefinition annotated
                && annotated.getMetadata().getAnnotations().isPresent(Component.class);
    }
}

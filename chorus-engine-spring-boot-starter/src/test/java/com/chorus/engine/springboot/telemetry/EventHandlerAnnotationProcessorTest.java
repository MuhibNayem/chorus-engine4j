package com.chorus.engine.springboot.telemetry;

import com.chorus.engine.annotation.EventHandler;
import com.chorus.engine.springboot.ChorusAutoConfiguration;
import com.chorus.engine.springboot.testsupport.FakeEventBus;
import com.chorus.engine.telemetry.event.AgentStartEvent;
import com.chorus.engine.telemetry.event.ChorusEvent;
import com.chorus.engine.telemetry.event.EventBus;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Comprehensive tests for {@link EventHandlerAnnotationProcessor}.
 *
 * <p>Covers:
 * <ul>
 *   <li>@EventHandler with specific event types → subscribed</li>
 *   <li>@EventHandler with empty array → wildcard subscription</li>
 *   <li>Multiple handlers on same bean</li>
 *   <li>Multiple event types in single annotation</li>
 *   <li>Missing eventBus → no-op</li>
 * </ul>
 */
class EventHandlerAnnotationProcessorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
        .withPropertyValues("chorus.enabled=true")
        .withUserConfiguration(FakeEventBusConfig.class);

    // ================================================================
    // SPECIFIC EVENT TYPE SUBSCRIPTION
    // ================================================================

    @Test
    void specificEventTypeSubscribed() {
        contextRunner
            .withUserConfiguration(TypedHandlerBeanConfig.class)
            .run(context -> {
                FakeEventBus eventBus = context.getBean(FakeEventBus.class);
                assertThat(eventBus.hasSubscription("agent.start")).isTrue();
            });
    }

    @Test
    void specificEventTypeReceivesEvents() {
        contextRunner
            .withUserConfiguration(TypedHandlerBeanConfig.class)
            .run(context -> {
                FakeEventBus eventBus = context.getBean(FakeEventBus.class);
                AgentStartEvent event = new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now());
                eventBus.publish(event);

                TypedHandlerBean bean = context.getBean(TypedHandlerBean.class);
                assertThat(bean.receivedEvents).hasSize(1);
                assertThat(bean.receivedEvents.get(0)).isEqualTo(event);
            });
    }

    // ================================================================
    // WILDCARD SUBSCRIPTION
    // ================================================================

    @Test
    void wildcardSubscriptionReceivesAllEvents() {
        contextRunner
            .withUserConfiguration(WildcardHandlerBeanConfig.class)
            .run(context -> {
                FakeEventBus eventBus = context.getBean(FakeEventBus.class);
                AgentStartEvent event = new AgentStartEvent("run-1", "agent-a", "gpt-4o", Instant.now());
                eventBus.publish(event);

                WildcardHandlerBean bean = context.getBean(WildcardHandlerBean.class);
                assertThat(bean.receivedEvents).hasSize(1);
            });
    }

    // ================================================================
    // MULTIPLE HANDLERS ON SAME BEAN
    // ================================================================

    @Test
    void multipleHandlersOnSameBean() {
        contextRunner
            .withUserConfiguration(MultiHandlerBeanConfig.class)
            .run(context -> {
                FakeEventBus eventBus = context.getBean(FakeEventBus.class);
                assertThat(eventBus.hasSubscription("agent.start")).isTrue();
                assertThat(eventBus.hasSubscription("agent.end")).isTrue();
            });
    }

    // ================================================================
    // MULTIPLE EVENT TYPES IN SINGLE ANNOTATION
    // ================================================================

    @Test
    void multipleEventTypesInSingleAnnotation() {
        contextRunner
            .withUserConfiguration(MultiTypeHandlerBeanConfig.class)
            .run(context -> {
                FakeEventBus eventBus = context.getBean(FakeEventBus.class);
                assertThat(eventBus.hasSubscription("type.a")).isTrue();
                assertThat(eventBus.hasSubscription("type.b")).isTrue();
            });
    }

    // ================================================================
    // MISSING EVENT BUS
    // ================================================================

    @Test
    void missingEventBusMeansNoOp() {
        new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ChorusAutoConfiguration.class))
            .withPropertyValues(
                "chorus.enabled=true",
                "chorus.telemetry.enabled=false"
            )
            .withUserConfiguration(TypedHandlerBeanConfig.class)
            .run(context -> {
                assertThat(context).doesNotHaveBean("eventBus");
                // Processor should not throw
            });
    }

    // ================================================================
    // Test configurations
    // ================================================================

    @Configuration
    static class FakeEventBusConfig {
        @Bean
        public EventBus eventBus() {
            return new FakeEventBus();
        }
    }

    static class TypedHandlerBean {
        final List<ChorusEvent> receivedEvents = new ArrayList<>();

        @EventHandler("agent.start")
        public void onAgentStart(ChorusEvent event) {
            receivedEvents.add(event);
        }
    }

    @Configuration
    static class TypedHandlerBeanConfig {
        @Bean
        public TypedHandlerBean typedHandlerBean() {
            return new TypedHandlerBean();
        }
    }

    static class WildcardHandlerBean {
        final List<ChorusEvent> receivedEvents = new ArrayList<>();

        @EventHandler
        public void onAnyEvent(ChorusEvent event) {
            receivedEvents.add(event);
        }
    }

    @Configuration
    static class WildcardHandlerBeanConfig {
        @Bean
        public WildcardHandlerBean wildcardHandlerBean() {
            return new WildcardHandlerBean();
        }
    }

    static class MultiHandlerBean {
        @EventHandler("agent.start")
        public void onStart(ChorusEvent event) {}

        @EventHandler("agent.end")
        public void onEnd(ChorusEvent event) {}
    }

    @Configuration
    static class MultiHandlerBeanConfig {
        @Bean
        public MultiHandlerBean multiHandlerBean() {
            return new MultiHandlerBean();
        }
    }

    static class MultiTypeHandlerBean {
        @EventHandler({"type.a", "type.b"})
        public void onMultiple(ChorusEvent event) {}
    }

    @Configuration
    static class MultiTypeHandlerBeanConfig {
        @Bean
        public MultiTypeHandlerBean multiTypeHandlerBean() {
            return new MultiTypeHandlerBean();
        }
    }
}

/*
 * Copyright (c) 2010-2018. Axon Framework
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.axonframework.deadline;

import org.axonframework.commandhandling.CommandHandler;
import org.axonframework.commandhandling.TargetAggregateIdentifier;
import org.axonframework.commandhandling.model.AggregateIdentifier;
import org.axonframework.commandhandling.model.AggregateMember;
import org.axonframework.commandhandling.model.EntityId;
import org.axonframework.config.Configuration;
import org.axonframework.config.DefaultConfigurer;
import org.axonframework.config.SagaConfiguration;
import org.axonframework.deadline.annotation.DeadlineHandler;
import org.axonframework.eventhandling.EventMessage;
import org.axonframework.eventhandling.Timestamp;
import org.axonframework.eventhandling.saga.SagaEventHandler;
import org.axonframework.eventhandling.saga.StartSaga;
import org.axonframework.eventsourcing.EventSourcingHandler;
import org.axonframework.eventsourcing.eventstore.EmbeddedEventStore;
import org.axonframework.eventsourcing.eventstore.EventStore;
import org.axonframework.eventsourcing.eventstore.inmemory.InMemoryEventStorageEngine;
import org.junit.*;
import org.mockito.*;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import static org.axonframework.commandhandling.model.AggregateLifecycle.apply;
import static org.axonframework.eventhandling.GenericEventMessage.asEventMessage;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/**
 * Tests whether a {@link DeadlineManager} implementations functions as expected.
 * This is an abstract (integration) test class, whose tests cases should work for any DeadlineManager implementation.
 *
 * @author Milan Savic
 * @author Steven van Beelen
 * @since 3.3
 */
public abstract class AbstractDeadlineManagerTestSuite {

    private static final int DEADLINE_TIMEOUT = 1000;
    private static final int CHILD_ENTITY_DEADLINE_TIMEOUT = 500;
    private static final String IDENTIFIER = "id";
    private static final boolean CANCEL_BEFORE_DEADLINE = true;
    private static final boolean DO_NOT_CANCEL_BEFORE_DEADLINE = false;

    protected Configuration configuration;

    @Before
    public void setUp() {
        EventStore eventStore = spy(new EmbeddedEventStore(new InMemoryEventStorageEngine()));
        configuration = DefaultConfigurer.defaultConfiguration()
                                         .configureEventStore(c -> eventStore)
                                         .configureAggregate(MyAggregate.class)
                                         .registerModule(SagaConfiguration.subscribingSagaManager(MySaga.class))
                                         .registerComponent(DeadlineManager.class, this::buildDeadlineManager)
                                         .start();
    }

    @After
    public void tearDown() {
        configuration.shutdown();
    }

    /**
     * Build the {@link DeadlineManager} to be tested.
     *
     * @param configuration the {@link Configuration} used to set up this test class
     * @return a {@link DeadlineManager} to be tested
     */
    public abstract DeadlineManager buildDeadlineManager(Configuration configuration);

    @Test
    public void testDeadlineOnAggregate() throws InterruptedException {
        configuration.commandGateway().sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(2)).publish(eventCaptor.capture());
        assertEquals(new MyAggregateCreatedEvent(IDENTIFIER), eventCaptor.getAllValues().get(0).getPayload());
        assertEquals(
                new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)),
                eventCaptor.getAllValues().get(1).getPayload()
        );
    }

    @Test
    public void testDeadlineCancellationOnAggregate() throws InterruptedException {
        configuration.commandGateway().sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, CANCEL_BEFORE_DEADLINE));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(1)).publish(eventCaptor.capture());
        assertEquals(new MyAggregateCreatedEvent(IDENTIFIER), eventCaptor.getAllValues().get(0).getPayload());
    }

    @Test
    public void testDeadlineOnChildEntity() throws InterruptedException {
        configuration.commandGateway().sendAndWait(new CreateMyAggregateCommand(IDENTIFIER));
        configuration.commandGateway().sendAndWait(new TriggerDeadlineInChildEntityCommand(IDENTIFIER));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(3)).publish(eventCaptor.capture());
        assertEquals(new MyAggregateCreatedEvent(IDENTIFIER), eventCaptor.getAllValues().get(0).getPayload());
        assertEquals(
                new DeadlineOccurredInChildEvent(new ChildDeadlinePayload("entity" + IDENTIFIER)),
                eventCaptor.getAllValues().get(1).getPayload()
        );
        assertEquals(
                new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)),
                eventCaptor.getAllValues().get(2).getPayload()
        );
    }

    @Test
    public void testDeadlineWithSpecifiedDeadlineName() throws InterruptedException {
        String expectedDeadlinePayload = "deadlinePayload";

        configuration.commandGateway().sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, CANCEL_BEFORE_DEADLINE));
        configuration.commandGateway().sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(2)).publish(eventCaptor.capture());
        assertEquals(new MyAggregateCreatedEvent(IDENTIFIER), eventCaptor.getAllValues().get(0).getPayload());
        assertEquals(
                new SpecificDeadlineOccurredEvent(expectedDeadlinePayload),
                eventCaptor.getAllValues().get(1).getPayload()
        );
    }

    @Test
    public void testDeadlineWithoutPayload() throws InterruptedException {
        configuration.commandGateway().sendAndWait(new CreateMyAggregateCommand(IDENTIFIER, CANCEL_BEFORE_DEADLINE));
        configuration.commandGateway().sendAndWait(new ScheduleSpecificDeadline(IDENTIFIER, null));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(2)).publish(eventCaptor.capture());
        assertEquals(new MyAggregateCreatedEvent(IDENTIFIER), eventCaptor.getAllValues().get(0).getPayload());
        assertEquals(new SpecificDeadlineOccurredEvent(null), eventCaptor.getAllValues().get(1).getPayload());
    }

    @Test
    public void testDeadlineOnSaga() throws InterruptedException {
        EventMessage<Object> testEventMessage =
                asEventMessage(new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE));
        configuration.eventStore().publish(testEventMessage);
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(2)).publish(eventCaptor.capture());
        assertEquals(
                new SagaStartingEvent(IDENTIFIER, DO_NOT_CANCEL_BEFORE_DEADLINE),
                eventCaptor.getAllValues().get(0).getPayload()
        );
        assertEquals(
                new DeadlineOccurredEvent(new DeadlinePayload(IDENTIFIER)),
                eventCaptor.getAllValues().get(1).getPayload()
        );
    }

    @Test
    public void testDeadlineCancellationOnSaga() throws InterruptedException {
        configuration.eventStore().publish(asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(1)).publish(eventCaptor.capture());
        assertEquals(
                new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                eventCaptor.getAllValues().get(0).getPayload()
        );
    }

    @Test
    public void testDeadlineWithSpecifiedDeadlineNameOnSaga() throws InterruptedException {
        String expectedDeadlinePayload = "deadlinePayload";

        configuration.eventStore().publish(asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.eventStore().publish(asEventMessage(
                new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload))
        );
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(3)).publish(eventCaptor.capture());
        assertEquals(
                new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                eventCaptor.getAllValues().get(0).getPayload()
        );
        assertEquals(
                new ScheduleSpecificDeadline(IDENTIFIER, expectedDeadlinePayload),
                eventCaptor.getAllValues().get(1).getPayload()
        );
        assertEquals(
                new SpecificDeadlineOccurredEvent(expectedDeadlinePayload),
                eventCaptor.getAllValues().get(2).getPayload()
        );
    }

    @Test
    public void testDeadlineWithoutPayloadOnSaga() throws InterruptedException {
        configuration.eventStore().publish(asEventMessage(new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE)));
        configuration.eventStore().publish(asEventMessage(new ScheduleSpecificDeadline(IDENTIFIER, null)));
        Thread.sleep(DEADLINE_TIMEOUT + 100);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<EventMessage<?>> eventCaptor = ArgumentCaptor.forClass(EventMessage.class);

        verify(configuration.eventStore(), times(3)).publish(eventCaptor.capture());
        assertEquals(
                new SagaStartingEvent(IDENTIFIER, CANCEL_BEFORE_DEADLINE),
                eventCaptor.getAllValues().get(0).getPayload()
        );
        assertEquals(new ScheduleSpecificDeadline(IDENTIFIER, null), eventCaptor.getAllValues().get(1).getPayload());
        assertEquals(new SpecificDeadlineOccurredEvent(null), eventCaptor.getAllValues().get(2).getPayload());
    }

    private static class CreateMyAggregateCommand {

        private final String id;
        private final boolean cancelBeforeDeadline;

        private CreateMyAggregateCommand(String id) {
            this(id, false);
        }

        private CreateMyAggregateCommand(String id, boolean cancelBeforeDeadline) {
            this.id = id;
            this.cancelBeforeDeadline = cancelBeforeDeadline;
        }
    }

    private static class TriggerDeadlineInChildEntityCommand {

        @TargetAggregateIdentifier
        private final String id;

        private TriggerDeadlineInChildEntityCommand(String id) {
            this.id = id;
        }
    }

    private static class ScheduleSpecificDeadline {

        @TargetAggregateIdentifier
        private final String id;
        private final Object payload;

        private ScheduleSpecificDeadline(String id, Object payload) {
            this.id = id;
            this.payload = payload;
        }

        public String getId() {
            return id;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, payload);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final ScheduleSpecificDeadline other = (ScheduleSpecificDeadline) obj;
            return Objects.equals(this.id, other.id)
                    && Objects.equals(this.payload, other.payload);
        }
    }

    private static class MyAggregateCreatedEvent {

        private final String id;

        private MyAggregateCreatedEvent(String id) {
            this.id = id;
        }

        public String getId() {
            return id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            MyAggregateCreatedEvent that = (MyAggregateCreatedEvent) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class SagaStartingEvent {

        private final String id;
        private final boolean cancelBeforeDeadline;

        private SagaStartingEvent(String id, boolean cancelBeforeDeadline) {
            this.id = id;
            this.cancelBeforeDeadline = cancelBeforeDeadline;
        }

        public String getId() {
            return id;
        }

        public boolean isCancelBeforeDeadline() {
            return cancelBeforeDeadline;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            SagaStartingEvent that = (SagaStartingEvent) o;
            return cancelBeforeDeadline == that.cancelBeforeDeadline && Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, cancelBeforeDeadline);
        }
    }

    private static class DeadlinePayload {

        private final String id;

        private DeadlinePayload(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlinePayload that = (DeadlinePayload) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class ChildDeadlinePayload {

        private final String id;

        private ChildDeadlinePayload(String id) {
            this.id = id;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            ChildDeadlinePayload that = (ChildDeadlinePayload) o;
            return Objects.equals(id, that.id);
        }

        @Override
        public int hashCode() {
            return Objects.hash(id);
        }
    }

    private static class DeadlineOccurredEvent {

        private final DeadlinePayload deadlinePayload;

        private DeadlineOccurredEvent(DeadlinePayload deadlinePayload) {
            this.deadlinePayload = deadlinePayload;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlineOccurredEvent that = (DeadlineOccurredEvent) o;
            return Objects.equals(deadlinePayload, that.deadlinePayload);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deadlinePayload);
        }
    }

    private static class DeadlineOccurredInChildEvent {

        private final ChildDeadlinePayload deadlineInfo;

        private DeadlineOccurredInChildEvent(ChildDeadlinePayload deadlineInfo) {
            this.deadlineInfo = deadlineInfo;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (o == null || getClass() != o.getClass()) {
                return false;
            }
            DeadlineOccurredInChildEvent that = (DeadlineOccurredInChildEvent) o;
            return Objects.equals(deadlineInfo, that.deadlineInfo);
        }

        @Override
        public int hashCode() {
            return Objects.hash(deadlineInfo);
        }
    }

    private static class SpecificDeadlineOccurredEvent {

        private final Object payload;

        private SpecificDeadlineOccurredEvent(Object payload) {
            this.payload = payload;
        }

        @Override
        public int hashCode() {
            return Objects.hash(payload);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            final SpecificDeadlineOccurredEvent other = (SpecificDeadlineOccurredEvent) obj;
            return Objects.equals(this.payload, other.payload);
        }
    }

    @SuppressWarnings("unused")
    public static class MySaga {

        @Autowired
        private transient EventStore eventStore;

        @StartSaga
        @SagaEventHandler(associationProperty = "id")
        public void on(SagaStartingEvent sagaStartingEvent, DeadlineManager deadlineManager) {
            String deadlineName = "deadlineName";
            String deadlineId = deadlineManager.schedule(
                    Duration.ofMillis(DEADLINE_TIMEOUT), deadlineName, new DeadlinePayload(sagaStartingEvent.id)
            );

            if (sagaStartingEvent.isCancelBeforeDeadline()) {
                deadlineManager.cancelSchedule(deadlineName, deadlineId);
            }
        }

        @SagaEventHandler(associationProperty = "id")
        public void on(ScheduleSpecificDeadline message, DeadlineManager deadlineManager) {
            Object deadlinePayload = message.payload;
            if (deadlinePayload != null) {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "specificDeadlineName", deadlinePayload);
            } else {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "payloadlessDeadline");
            }
        }

        @DeadlineHandler
        public void on(DeadlinePayload deadlinePayload, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            eventStore.publish(asEventMessage(new DeadlineOccurredEvent(deadlinePayload)));
        }

        @DeadlineHandler(deadlineName = "specificDeadlineName")
        public void on(Object deadlinePayload, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            eventStore.publish(asEventMessage(new SpecificDeadlineOccurredEvent(deadlinePayload)));
        }

        @DeadlineHandler(deadlineName = "payloadlessDeadline")
        public void on() {
            eventStore.publish(asEventMessage(new SpecificDeadlineOccurredEvent(null)));
        }
    }

    @SuppressWarnings("unused")
    public static class MyAggregate {

        @AggregateIdentifier
        private String id;
        @AggregateMember
        private MyEntity myEntity;

        public MyAggregate() {
            // empty constructor
        }

        @CommandHandler
        public MyAggregate(CreateMyAggregateCommand command, DeadlineManager deadlineManager) {
            apply(new MyAggregateCreatedEvent(command.id));

            String deadlineName = "deadlineName";
            String deadlineId = deadlineManager.schedule(
                    Duration.ofMillis(DEADLINE_TIMEOUT), deadlineName, new DeadlinePayload(command.id)
            );

            if (command.cancelBeforeDeadline) {
                deadlineManager.cancelSchedule(deadlineName, deadlineId);
            }
        }

        @CommandHandler
        public void on(ScheduleSpecificDeadline message, DeadlineManager deadlineManager) {
            Object deadlinePayload = message.payload;
            if (deadlinePayload != null) {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "specificDeadlineName", deadlinePayload);
            } else {
                deadlineManager.schedule(Duration.ofMillis(DEADLINE_TIMEOUT), "payloadlessDeadline");
            }
        }

        @EventSourcingHandler
        public void on(MyAggregateCreatedEvent event) {
            this.id = event.id;
            this.myEntity = new MyEntity(id);
        }

        @DeadlineHandler
        public void on(DeadlinePayload deadlinePayload, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            apply(new DeadlineOccurredEvent(deadlinePayload));
        }

        @DeadlineHandler(deadlineName = "specificDeadlineName")
        public void on(Object deadlinePayload) {
            apply(new SpecificDeadlineOccurredEvent(deadlinePayload));
        }

        @DeadlineHandler(deadlineName = "payloadlessDeadline")
        public void on() {
            apply(new SpecificDeadlineOccurredEvent(null));
        }

        @CommandHandler
        public void handle(TriggerDeadlineInChildEntityCommand command, DeadlineManager deadlineManager) {
            deadlineManager.schedule(
                    Duration.ofMillis(CHILD_ENTITY_DEADLINE_TIMEOUT),
                    "deadlineName",
                    new ChildDeadlinePayload("entity" + command.id)
            );
        }
    }

    public static class MyEntity {

        @EntityId
        private final String id;

        private MyEntity(String id) {
            this.id = id;
        }

        @DeadlineHandler
        public void on(ChildDeadlinePayload deadlineInfo, @Timestamp Instant timestamp) {
            assertNotNull(timestamp);
            apply(new DeadlineOccurredInChildEvent(deadlineInfo));
        }
    }
}
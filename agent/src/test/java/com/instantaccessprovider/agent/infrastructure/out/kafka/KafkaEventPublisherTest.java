// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.infrastructure.out.kafka;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.model.ResourceContext;
import com.instantaccessprovider.agent.domain.model.SourceType;
import com.instantaccessprovider.agent.infrastructure.out.kafka.schema.RawIamEventMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KafkaEventPublisherTest {

    @Mock
    KafkaTemplate<String, RawIamEventMessage> kafkaTemplate;

    @Mock
    KafkaTopicResolver topicResolver;

    KafkaEventPublisher publisher;

    @BeforeEach
    void setUp() {
        publisher = new KafkaEventPublisher(kafkaTemplate, topicResolver);
    }

    @Test
    void publish_sendsToResolvedTopic() {
        RawIamEvent event = buildEvent();
        String expectedTopic = "iam.raw.ad-corp-example-com";

        when(topicResolver.resolve(event.sourceId())).thenReturn(expectedTopic);
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);

        verify(kafkaTemplate).send(eq(expectedTopic), eq(event.deduplicationKey()), any());
    }

    @Test
    void publish_usesDeduplicationKeyAsKafkaKey() {
        RawIamEvent event = buildEvent();
        when(topicResolver.resolve(any())).thenReturn("iam.raw.test");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        publisher.publish(event);

        verify(kafkaTemplate).send(any(), eq(event.deduplicationKey()), any());
    }

    @Test
    void publish_messageIsCloudEvents10Compliant() {
        RawIamEvent event = buildEvent();
        when(topicResolver.resolve(any())).thenReturn("iam.raw.test");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ArgumentCaptor<RawIamEventMessage> captor = ArgumentCaptor.forClass(RawIamEventMessage.class);

        publisher.publish(event);

        verify(kafkaTemplate).send(any(), any(), captor.capture());
        RawIamEventMessage msg = captor.getValue();

        assertThat(msg.specversion()).isEqualTo("1.0");
        assertThat(msg.type()).isEqualTo(RawIamEventMessage.EVENT_TYPE);
        assertThat(msg.dataContentType()).isEqualTo("application/json");
        assertThat(msg.source()).isEqualTo(event.sourceId());
        assertThat(msg.id()).isNotBlank();
        assertThat(msg.time()).isNotNull();
    }

    @Test
    void publish_dataContainsOriginalEventFields() {
        RawIamEvent event = buildEvent();
        when(topicResolver.resolve(any())).thenReturn("iam.raw.test");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ArgumentCaptor<RawIamEventMessage> captor = ArgumentCaptor.forClass(RawIamEventMessage.class);
        publisher.publish(event);
        verify(kafkaTemplate).send(any(), any(), captor.capture());

        var data = captor.getValue().data();
        assertThat(data.sourceId()).isEqualTo(event.sourceId());
        assertThat(data.nativeEventId()).isEqualTo(event.nativeEventId());
        assertThat(data.nativePayload()).isEqualTo(event.nativePayload());
        assertThat(data.detectedAt()).isEqualTo(event.detectedAt());
        assertThat(data.deduplicationKey()).isEqualTo(event.deduplicationKey());
    }

    @Test
    void publish_dataContainsResource_whenPresent() {
        RawIamEvent event = RawIamEvent.of(
                "ad://corp.example.com", SourceType.ACTIVE_DIRECTORY,
                "4728-001", "<Event/>", Instant.now(),
                new ResourceContext("server", "DC01")
        );
        when(topicResolver.resolve(any())).thenReturn("iam.raw.test");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ArgumentCaptor<RawIamEventMessage> captor = ArgumentCaptor.forClass(RawIamEventMessage.class);
        publisher.publish(event);
        verify(kafkaTemplate).send(any(), any(), captor.capture());

        var data = captor.getValue().data();
        assertThat(data.resourceType()).isEqualTo("server");
        assertThat(data.resourceRef()).isEqualTo("DC01");
    }

    @Test
    void publish_dataResourceIsNull_whenNotProvided() {
        RawIamEvent event = buildEvent(); // sans resource
        when(topicResolver.resolve(any())).thenReturn("iam.raw.test");
        when(kafkaTemplate.send(any(), any(), any()))
                .thenReturn(CompletableFuture.completedFuture(null));

        ArgumentCaptor<RawIamEventMessage> captor = ArgumentCaptor.forClass(RawIamEventMessage.class);
        publisher.publish(event);
        verify(kafkaTemplate).send(any(), any(), captor.capture());

        var data = captor.getValue().data();
        assertThat(data.resourceType()).isNull();
        assertThat(data.resourceRef()).isNull();
    }

    private static RawIamEvent buildEvent() {
        return RawIamEvent.of(
                "ad://corp.example.com",
                SourceType.ACTIVE_DIRECTORY,
                "4728-001",
                "<Event><EventID>4728</EventID></Event>",
                Instant.now()
        );
    }
}

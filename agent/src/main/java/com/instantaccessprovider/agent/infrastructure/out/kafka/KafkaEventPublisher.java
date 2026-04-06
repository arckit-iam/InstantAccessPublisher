package com.instantaccessprovider.agent.infrastructure.out.kafka;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.port.out.EventPublisher;
import com.instantaccessprovider.agent.infrastructure.out.kafka.schema.RawIamEventData;
import com.instantaccessprovider.agent.infrastructure.out.kafka.schema.RawIamEventMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Port OUT — publie un {@link RawIamEvent} sur le topic Kafka {@code iam.raw.{sourceId}}.
 *
 * Format du message : enveloppe CloudEvents 1.0 (JSON).
 * Clé Kafka         : {@code deduplicationKey} (SHA-256) pour l'idempotence côté consommateur.
 */
@Component
public class KafkaEventPublisher implements EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaEventPublisher.class);

    private final KafkaTemplate<String, RawIamEventMessage> kafkaTemplate;
    private final KafkaTopicResolver topicResolver;

    public KafkaEventPublisher(
            KafkaTemplate<String, RawIamEventMessage> iapKafkaTemplate,
            KafkaTopicResolver topicResolver) {
        this.kafkaTemplate = iapKafkaTemplate;
        this.topicResolver = topicResolver;
    }

    @Override
    public void publish(RawIamEvent event) {
        String topic = topicResolver.resolve(event.sourceId());
        RawIamEventMessage message = toCloudEvent(event);

        CompletableFuture<SendResult<String, RawIamEventMessage>> future =
                kafkaTemplate.send(topic, event.deduplicationKey(), message);

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("Échec publication sourceId={} nativeEventId={}",
                        event.sourceId(), event.nativeEventId(), ex);
            } else {
                log.debug("Publié → topic={} partition={} offset={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset());
            }
        });
    }

    private RawIamEventMessage toCloudEvent(RawIamEvent event) {
        return new RawIamEventMessage(
                RawIamEventMessage.SPEC_VERSION,
                UUID.randomUUID().toString(),
                event.sourceId(),
                RawIamEventMessage.EVENT_TYPE,
                Instant.now(),
                RawIamEventMessage.DATA_CONTENT_TYPE,
                event.sourceType().name().toLowerCase(),
                new RawIamEventData(
                        event.sourceId(),
                        event.sourceType().name().toLowerCase(),
                        event.nativeEventId(),
                        event.nativePayload(),
                        event.detectedAt(),
                        event.deduplicationKey(),
                        event.resource() != null ? event.resource().type() : null,
                        event.resource() != null ? event.resource().ref()  : null
                )
        );
    }
}

package com.instantaccessprovider.agent.infrastructure.out.kafka.schema;

import java.time.Instant;

/**
 * Contenu métier de l'événement IAM brut (champ {@code data} de l'enveloppe CloudEvents).
 */
public record RawIamEventData(
        String sourceId,
        String sourceType,
        String nativeEventId,
        String nativePayload,
        Instant detectedAt,
        String deduplicationKey
) {}

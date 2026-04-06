package com.instantaccessprovider.agent.infrastructure.out.kafka.schema;

import com.fasterxml.jackson.annotation.JsonInclude;
import org.springframework.lang.Nullable;

import java.time.Instant;

/**
 * Contenu métier de l'événement IAM brut (champ {@code data} de l'enveloppe CloudEvents).
 *
 * <p>{@code resourceType} et {@code resourceRef} sont optionnels ({@code null} = absent du JSON).
 * Ils transportent le contexte de la ressource cible tel que fourni par la source,
 * sans normalisation (format libre).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record RawIamEventData(
        String sourceId,
        String sourceType,
        String nativeEventId,
        String nativePayload,
        Instant detectedAt,
        String deduplicationKey,
        @Nullable String resourceType,
        @Nullable String resourceRef
) {}

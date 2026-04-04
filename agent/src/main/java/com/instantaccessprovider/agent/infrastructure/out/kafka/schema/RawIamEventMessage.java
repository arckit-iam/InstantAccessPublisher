package com.instantaccessprovider.agent.infrastructure.out.kafka.schema;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;

/**
 * Enveloppe CloudEvents 1.0 publiée sur le topic {@code iam.raw.{sourceId}}.
 *
 * <pre>
 * {
 *   "specversion":     "1.0",
 *   "id":              "<uuid>",
 *   "source":          "ad://corp.example.com",
 *   "type":            "com.instantaccessprovider.iam.raw.event",
 *   "time":            "2025-06-01T12:00:00Z",
 *   "datacontenttype": "application/json",
 *   "iamsourcetype":   "active_directory",
 *   "data": { ... }
 * }
 * </pre>
 *
 * Clé Kafka : {@code deduplicationKey} (SHA-256) — garantit l'idempotence côté consommateur.
 */
public record RawIamEventMessage(
        String specversion,
        String id,
        String source,
        String type,
        Instant time,
        @JsonProperty("datacontenttype") String dataContentType,
        String iamsourcetype,
        RawIamEventData data
) {
    public static final String SPEC_VERSION = "1.0";
    public static final String EVENT_TYPE = "com.instantaccessprovider.iam.raw.event";
    public static final String DATA_CONTENT_TYPE = "application/json";
}

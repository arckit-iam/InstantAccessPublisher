package com.instantaccessprovider.agent.infrastructure.out.kafka;

import org.springframework.stereotype.Component;

/**
 * Résout le nom du topic Kafka à partir du {@code sourceId} d'un événement.
 *
 * <pre>
 * "ad://corp.example.com"  →  "iam.raw.ad-corp-example-com"
 * "syslog://fw-01"         →  "iam.raw.syslog-fw-01"
 * "webhook://app-rh"       →  "iam.raw.webhook-app-rh"
 * </pre>
 *
 * Règle de sanitisation : supprime le scheme {@code ://}, remplace tout caractère
 * non alphanumérique / tiret / underscore / point par un tiret, convertit en minuscules.
 */
@Component
public class KafkaTopicResolver {

    static final String TOPIC_PREFIX = "iam.raw.";

    public String resolve(String sourceId) {
        String sanitized = sourceId
                .replaceAll("://", "-")
                .replaceAll("[^a-zA-Z0-9._-]", "-")
                .replaceAll("-{2,}", "-")   // collapse consecutive dashes
                .replaceAll("^-|-$", "")    // trim leading/trailing dashes
                .toLowerCase();
        return TOPIC_PREFIX + sanitized;
    }
}

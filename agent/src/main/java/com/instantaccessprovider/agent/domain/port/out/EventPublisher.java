package com.instantaccessprovider.agent.domain.port.out;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;

/**
 * Port secondaire (driven) — contrat que l'application impose à l'infrastructure sortante.
 * Implémenté par KafkaEventPublisher.
 */
public interface EventPublisher {
    void publish(RawIamEvent event);
}

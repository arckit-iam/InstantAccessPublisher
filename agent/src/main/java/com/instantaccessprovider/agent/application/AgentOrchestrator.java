package com.instantaccessprovider.agent.application;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.domain.port.out.EventPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orchestrateur de l'Agent.
 *
 * Implémente le port primaire EventCollectionUseCase — les adapters entrants l'appellent.
 * Délègue la publication au port secondaire EventPublisher — Kafka l'implémente.
 *
 * Logique intentionnellement simple : receive → publish.
 * La déduplication, l'enrichissement et la détection sont faits dans le Stream Processor,
 * en aval des topics iam.raw.*.
 */
@Service
public class AgentOrchestrator implements EventCollectionUseCase {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final EventPublisher eventPublisher;

    public AgentOrchestrator(EventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Override
    public void handle(RawIamEvent event) {
        log.debug("Received event sourceId={} nativeEventId={} dedupKey={}",
                event.sourceId(), event.nativeEventId(), event.deduplicationKey());
        eventPublisher.publish(event);
    }
}

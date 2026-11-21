// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.model.ResourceContext;
import com.instantaccessprovider.agent.domain.model.SourceType;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.infrastructure.resource.ResourceContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Noyau métier du WEF adapter : parse, filtre et dispatche les événements Windows Event Log.
 *
 * <p>Responsabilités :
 * <ol>
 *   <li>Parser le XML brut en {@link WefRawEvent}</li>
 *   <li>Filtrer sur les {@code eventIds} configurés</li>
 *   <li>Résoudre le {@link ResourceContext} via les règles de mapping</li>
 *   <li>Construire un {@link RawIamEvent} et le soumettre au use case</li>
 * </ol>
 *
 * <p>Cette classe est indépendante de toute API native Windows — elle peut être
 * instanciée et testée sur n'importe quel système (y compris Linux/CI).
 * L'appel à la couche JNA est isolé dans {@link WinEvtSubscription}.
 *
 * <p>Thread-safe : {@link #process(String)} peut être invoqué depuis le thread pool
 * Windows du callback JNA.
 */
@Component
class WefEventProcessor {

    private static final Logger log = LoggerFactory.getLogger(WefEventProcessor.class);

    private final Set<Integer> allowedEventIds;
    private final String sourceId;
    private final EventCollectionUseCase useCase;
    private final ResourceContextResolver resolver;
    private final WefXmlParser parser = new WefXmlParser();

    WefEventProcessor(AgentProperties props,
                      EventCollectionUseCase useCase,
                      ResourceContextResolver resolver) {
        AgentProperties.WefProperties wef = props.adapters() != null ? props.adapters().wef() : null;
        this.allowedEventIds = (wef != null && wef.eventIds() != null)
                ? Collections.unmodifiableSet(new HashSet<>(wef.eventIds()))
                : Collections.emptySet();
        this.sourceId = (wef != null && wef.sourceId() != null) ? wef.sourceId() : "ad://unknown";
        this.useCase  = useCase;
        this.resolver = resolver;
    }

    /**
     * Traite un XML Windows Event brut.
     *
     * <p>Les erreurs de parsing sont loguées mais ne propagent pas d'exception
     * (le callback JNA ne doit jamais provoquer un crash JVM).
     *
     * @param rawXml XML complet de l'événement, tel que renvoyé par EvtRender
     */
    void process(String rawXml) {
        WefRawEvent raw;
        try {
            raw = parser.parse(rawXml);
        } catch (WefParseException e) {
            log.error("Impossible de parser un événement WEF — ignoré", e);
            return;
        }

        if (!allowedEventIds.contains(raw.eventId())) {
            log.trace("EventID={} filtré (non configuré dans eventIds)", raw.eventId());
            return;
        }

        // nativeEventId unique : recordId est unique par canal/machine, combiné avec le FQDN du DC
        String nativeEventId   = raw.recordId() + "@" + raw.computer();
        // Pour les règles native-event-id, on passe le type d'événement (ex: "4720")
        String eventTypeString = String.valueOf(raw.eventId());

        Optional<ResourceContext> resource = resolver.resolve(rawXml, sourceId, eventTypeString);

        RawIamEvent event = RawIamEvent.of(
                sourceId,
                SourceType.ACTIVE_DIRECTORY,
                nativeEventId,
                eventTypeString,
                rawXml,
                Instant.now(),
                resource.orElse(null)
        );

        log.debug("Événement WEF traité : eventId={} recordId={} computer={}",
                  raw.eventId(), raw.recordId(), raw.computer());
        useCase.handle(event);
    }

    /** Expose le sourceId pour que WefAdapter puisse le logger. */
    String sourceId() {
        return sourceId;
    }

    /** Expose les eventIds filtrés pour que WefAdapter puisse les logger. */
    Set<Integer> allowedEventIds() {
        return allowedEventIds;
    }
}

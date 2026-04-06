package com.instantaccessprovider.agent.infrastructure.in.wef;

import java.time.Instant;

/**
 * Représentation parsée d'un événement Windows Event Log brut.
 * Produit par {@link WefXmlParser} avant filtrage et construction du {@link com.instantaccessprovider.agent.domain.model.RawIamEvent}.
 *
 * @param eventId     identifiant du type d'événement (ex: 4720, 4728…)
 * @param recordId    EventRecordID — unique dans le canal sur ce DC
 * @param timeCreated horodatage système de l'événement (TimeCreated/@SystemTime)
 * @param computer    FQDN du DC auquel l'événement appartient (ex: DC01.iap.local)
 * @param channel     canal source (ex: Security, ForwardedEvents)
 * @param rawXml      XML brut complet tel que livré par WEF
 */
record WefRawEvent(
        int eventId,
        long recordId,
        Instant timeCreated,
        String computer,
        String channel,
        String rawXml
) {}

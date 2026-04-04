package com.instantaccessprovider.agent.domain.port.in;

import com.instantaccessprovider.agent.domain.model.RawIamEvent;

/**
 * Port primaire (driving) — contrat exposé par l'application aux adapters entrants.
 * Chaque Adapter appelle handle() dès qu'un événement brut est collecté.
 */
public interface EventCollectionUseCase {
    void handle(RawIamEvent event);
}

package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter entrant — Windows Event Forwarding (Active Directory).
 *
 * Transport : WEF / WS-Management depuis le WEC Server (Windows Event Collector).
 * Fallback  : DirSync LDAP (voir DirsyncAdapter).
 *
 * TODO: implémenter la connexion au canal WEF via les API Windows (JNA / WEvtApi.dll)
 *       ou via une librairie WEF Java.
 *       Pour chaque événement reçu :
 *         1. Parser le XML Windows Event Log
 *         2. Filtrer sur les eventIds configurés
 *         3. Créer un RawIamEvent
 *         4. Appeler eventCollectionUseCase.handle(event)
 */
@Component
public class WefAdapter implements InputAdapter {

    private static final Logger log = LoggerFactory.getLogger(WefAdapter.class);
    private static final String ADAPTER_TYPE = "wef";

    private final EventCollectionUseCase eventCollectionUseCase;
    private final AgentProperties.WefProperties properties;

    public WefAdapter(EventCollectionUseCase eventCollectionUseCase, AgentProperties props) {
        this.eventCollectionUseCase = eventCollectionUseCase;
        this.properties = props.adapters() != null ? props.adapters().wef() : null;
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    @Override
    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    @Override
    public void start() {
        if (!isEnabled()) {
            log.info("WEF adapter désactivé");
            return;
        }
        throw new UnsupportedOperationException(
                "WEF adapter non implémenté. Canal cible : " + properties.channelName()
        );
    }

    @Override
    public void stop() {
        // TODO: fermer la subscription WEF proprement
    }
}

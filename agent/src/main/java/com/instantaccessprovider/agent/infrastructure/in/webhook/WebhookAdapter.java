package com.instantaccessprovider.agent.infrastructure.in.webhook;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter entrant — Webhook HTTP (applications SaaS, portails RH, SIRH).
 *
 * Expose un endpoint HTTP(S) POST pour recevoir des événements IAM
 * en provenance d'applications tierces (ex: Workday, SAP SuccessFactors).
 *
 * TODO: implémenter un serveur HTTP léger (Spring WebFlux ou Netty) sur listenPort.
 *       Pour chaque requête POST reçue :
 *         1. Valider le HMAC-SHA256 de la signature (header X-IAP-Signature)
 *         2. Parser le body JSON
 *         3. Extraire le sourceId depuis le header X-Source-Id ou le body
 *         4. Créer un RawIamEvent avec le payload brut
 *         5. Appeler eventCollectionUseCase.handle(event)
 *         6. Répondre HTTP 202 Accepted
 */
@Component
public class WebhookAdapter implements InputAdapter {

    private static final Logger log = LoggerFactory.getLogger(WebhookAdapter.class);
    private static final String ADAPTER_TYPE = "webhook";

    private final EventCollectionUseCase eventCollectionUseCase;
    private final AgentProperties.WebhookProperties properties;

    public WebhookAdapter(EventCollectionUseCase eventCollectionUseCase, AgentProperties props) {
        this.eventCollectionUseCase = eventCollectionUseCase;
        this.properties = props.adapters() != null ? props.adapters().webhook() : null;
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
            log.info("Webhook adapter désactivé");
            return;
        }
        throw new UnsupportedOperationException(
                "Webhook adapter non implémenté. Port cible : " + properties.listenPort()
        );
    }

    @Override
    public void stop() {
        // TODO: fermer le serveur HTTP (Netty shutdown)
    }
}

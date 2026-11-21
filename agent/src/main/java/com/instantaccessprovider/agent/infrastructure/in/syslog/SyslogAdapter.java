// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.infrastructure.in.syslog;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter entrant — Syslog (ISVD, serveurs Linux, équipements réseau).
 *
 * Transport : Syslog UDP/TCP/TLS (RFC 5424 ou format CEF).
 * Sources    : ISVD, rsyslog/syslog-ng Linux, firewalls, switches.
 *
 * TODO: implémenter un serveur Syslog (Netty UDP/TCP) sur listenPort.
 *       Pour chaque message reçu :
 *         1. Parser le format Syslog (RFC 5424) ou CEF
 *         2. Extraire le sourceId depuis le champ sourceidField (ex: hostname)
 *         3. Créer un RawIamEvent avec le payload brut
 *         4. Appeler eventCollectionUseCase.handle(event)
 */
@Component
public class SyslogAdapter implements InputAdapter {

    private static final Logger log = LoggerFactory.getLogger(SyslogAdapter.class);
    private static final String ADAPTER_TYPE = "syslog";

    private final EventCollectionUseCase eventCollectionUseCase;
    private final AgentProperties.SyslogProperties properties;

    public SyslogAdapter(EventCollectionUseCase eventCollectionUseCase, AgentProperties props) {
        this.eventCollectionUseCase = eventCollectionUseCase;
        this.properties = props.adapters() != null ? props.adapters().syslog() : null;
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
            log.info("Syslog adapter désactivé");
            return;
        }
        throw new UnsupportedOperationException(
                "Syslog adapter non implémenté. Port cible : " + properties.listenPort()
                + " protocole : " + properties.protocol()
        );
    }

    @Override
    public void stop() {
        // TODO: fermer le serveur Syslog (Netty channel shutdown)
    }
}

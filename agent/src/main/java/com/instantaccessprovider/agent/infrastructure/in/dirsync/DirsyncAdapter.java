package com.instantaccessprovider.agent.infrastructure.in.dirsync;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Adapter entrant — LDAP DirSync (Active Directory, OpenLDAP).
 *
 * Polling LDAP via le mécanisme DirSync (RFC 4533 ou MS-ADLS).
 * Détecte les modifications d'objets AD (utilisateurs, groupes) depuis le dernier poll.
 *
 * TODO: implémenter le polling LDAP toutes les pollIntervalSeconds secondes :
 *         1. Ouvrir une connexion LDAP sécurisée (LDAPS/StartTLS) vers ldapUrl
 *         2. Effectuer une recherche LDAP avec le contrôle DirSync (OID 1.2.840.113556.1.4.841)
 *         3. Stocker le cookie DirSync entre les polls (fichier local ou Redis)
 *         4. Pour chaque entrée modifiée : sérialiser en JSON, créer un RawIamEvent
 *         5. Appeler eventCollectionUseCase.handle(event)
 */
@Component
public class DirsyncAdapter implements InputAdapter {

    private static final Logger log = LoggerFactory.getLogger(DirsyncAdapter.class);
    private static final String ADAPTER_TYPE = "dirsync";

    private final EventCollectionUseCase eventCollectionUseCase;
    private final AgentProperties.DirsyncProperties properties;

    public DirsyncAdapter(EventCollectionUseCase eventCollectionUseCase, AgentProperties props) {
        this.eventCollectionUseCase = eventCollectionUseCase;
        this.properties = props.adapters() != null ? props.adapters().dirsync() : null;
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
            log.info("DirSync adapter désactivé");
            return;
        }
        throw new UnsupportedOperationException(
                "DirSync adapter non implémenté. LDAP cible : " + properties.ldapUrl()
                + " baseDn : " + properties.baseDn()
                + " poll interval : " + properties.pollIntervalSeconds() + "s"
        );
    }

    @Override
    public void stop() {
        // TODO: fermer la connexion LDAP et persister le cookie DirSync
    }
}

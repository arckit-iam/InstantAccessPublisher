package com.instantaccessprovider.agent.domain.port.in;

/**
 * Contrat de cycle de vie pour tous les adapters entrants.
 * Implémenté par WefAdapter, SyslogAdapter, WebhookAdapter, DirsyncAdapter.
 */
public interface InputAdapter {
    /** Identifiant du protocole : "wef", "syslog", "webhook", "dirsync" */
    String adapterType();

    /** Démarre l'écoute. Non bloquant. */
    void start();

    /** Arrêt propre (drain + fermeture des connexions). */
    void stop();

    /** Retourne false si l'adapter est désactivé dans la configuration. */
    boolean isEnabled();
}

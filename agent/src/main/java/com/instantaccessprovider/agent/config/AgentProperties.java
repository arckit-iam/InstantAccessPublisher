// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Collections;
import java.util.List;

/**
 * Configuration de l'Agent, liée depuis application.yml (préfixe iap.agent).
 * Chaque section adapters.xxx correspond à un InputAdapter.
 */
@ConfigurationProperties(prefix = "iap.agent")
public record AgentProperties(
        String instanceId,
        AdaptersProperties adapters,
        List<ResourceMappingRule> resourceMappings
) {

    /**
     * Règle de résolution du contexte de ressource.
     *
     * <p>Le champ {@code matchField} indique la valeur inspectée :
     * <ul>
     *   <li>{@code "payload"}          — nativePayload brut de l'événement (défaut)</li>
     *   <li>{@code "source-id"}        — sourceId de l'événement</li>
     *   <li>{@code "native-event-id"}  — nativeEventId de l'événement</li>
     * </ul>
     *
     * <p>{@code resourceType} et {@code resourceRef} supportent les références aux groupes
     * de capture du regex via {@code $1}, {@code $2}, etc.
     *
     * <p>Exemples YAML :
     * <pre>
     *   # Mapping statique : tout événement dont le payload contient OU=SX123456
     *   - match-field: payload
     *     pattern: "OU=SX123456[,\\s]"
     *     resource-type: application
     *     resource-ref: SX123456
     *
     *   # Mapping dynamique : extraire le nom d'OU comme ref
     *   - match-field: payload
     *     pattern: "OU=([A-Za-z0-9_-]+),OU=Applications,"
     *     resource-type: application
     *     resource-ref: "$1"
     *
     *   # Mapping sur source-id
     *   - match-field: source-id
     *     pattern: "ad://(.+)"
     *     resource-type: server
     *     resource-ref: "$1"
     * </pre>
     *
     * @param matchField   Champ à inspecter. {@code null} est traité comme {@code "payload"}.
     * @param pattern      Expression régulière Java (java.util.regex).
     * @param resourceType Type de ressource, peut contenir des références $1..$9.
     * @param resourceRef  Référence de ressource, peut contenir des références $1..$9.
     */
    public record ResourceMappingRule(
            String matchField,
            String pattern,
            String resourceType,
            String resourceRef
    ) {
        public String effectiveMatchField() {
            return (matchField == null || matchField.isBlank()) ? "payload" : matchField;
        }
    }

    /** Retourne la liste des règles, jamais null. */
    public List<ResourceMappingRule> resourceMappings() {
        return resourceMappings != null ? resourceMappings : Collections.emptyList();
    }

    public record AdaptersProperties(
            WefProperties wef,
            SyslogProperties syslog,
            WebhookProperties webhook,
            DirsyncProperties dirsync
    ) {}

    /** Adapter WEF (Windows Event Forwarding — Active Directory) */
    public record WefProperties(
            Boolean enabled,
            String channelName,
            List<Integer> eventIds,
            String sourceId,
            /** Hôte distant où lire ForwardedEvents (ex: WEC01). Null = canal local. */
            String remoteHost
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /** Adapter Syslog (ISVD, Linux, équipements réseau) */
    public record SyslogProperties(
            Boolean enabled,
            Integer listenPort,
            String protocol,
            String sourceIdField
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /** Adapter Webhook (applications métier via HTTP POST) */
    public record WebhookProperties(
            Boolean enabled,
            Integer listenPort
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }

    /** Adapter DirSync LDAP (fallback AD + OpenLDAP) */
    public record DirsyncProperties(
            Boolean enabled,
            String ldapUrl,
            String baseDn,
            String bindDn,
            String bindPassword,
            Integer pollIntervalSeconds
    ) {
        public boolean isEnabled() { return Boolean.TRUE.equals(enabled); }
    }
}

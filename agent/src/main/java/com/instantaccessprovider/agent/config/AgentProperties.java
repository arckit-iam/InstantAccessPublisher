package com.instantaccessprovider.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration de l'Agent, liée depuis application.yml (préfixe iap.agent).
 * Chaque section adapters.xxx correspond à un InputAdapter.
 */
@ConfigurationProperties(prefix = "iap.agent")
public record AgentProperties(
        String instanceId,
        AdaptersProperties adapters
) {
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
            String sourceId
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

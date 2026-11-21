// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.domain.model;

public enum SourceType {
    ACTIVE_DIRECTORY,
    SYSLOG,
    LDAP,
    WEBHOOK,
    ENTRA_ID,
    UNKNOWN;

    public static SourceType fromString(String value) {
        if (value == null) return UNKNOWN;
        return switch (value.toLowerCase()) {
            case "activedirectory", "active_directory" -> ACTIVE_DIRECTORY;
            case "syslog"                              -> SYSLOG;
            case "ldap"                                -> LDAP;
            case "webhook"                             -> WEBHOOK;
            case "entra-id", "entra_id"                -> ENTRA_ID;
            default                                    -> UNKNOWN;
        };
    }
}

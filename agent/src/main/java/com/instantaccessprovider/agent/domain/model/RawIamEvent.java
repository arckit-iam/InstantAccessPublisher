// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.domain.model;

import org.springframework.lang.Nullable;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

/**
 * Événement IAM brut, format natif de la source.
 * Immuable. Produit dans iam.raw.{sourceId} avant toute normalisation métier.
 *
 * @param sourceId         Identifiant de la source  ex: "ad://corp.example.com"
 * @param sourceType       Type de source
 * @param nativeEventId    Identifiant unique de l'occurrence (ex: recordId@DC01.iap.local pour WEF)
 * @param nativeEventType  Type d'événement dans le système source (ex: "4720" pour WEF, null si non applicable)
 * @param nativePayload    Payload brut non transformé (XML, JSON, CEF…)
 * @param detectedAt       Horodatage de détection par l'Agent (ISO 8601)
 * @param deduplicationKey SHA-256(sourceId | nativeEventId | timestamp/s)
 * @param resource         Ressource cible optionnelle (type libre + référence libre).
 *                         Null si l'événement ne porte pas de contexte de ressource.
 */
public record RawIamEvent(
        String sourceId,
        SourceType sourceType,
        String nativeEventId,
        @Nullable String nativeEventType,
        String nativePayload,
        Instant detectedAt,
        String deduplicationKey,
        @Nullable ResourceContext resource
) {
    /**
     * Factory sans contexte de ressource (cas le plus fréquent).
     */
    public static RawIamEvent of(
            String sourceId,
            SourceType sourceType,
            String nativeEventId,
            String nativePayload,
            Instant detectedAt
    ) {
        return of(sourceId, sourceType, nativeEventId, null, nativePayload, detectedAt, null);
    }

    /**
     * Factory avec contexte de ressource optionnel.
     * Passer {@code null} pour {@code resource} si l'information n'est pas disponible.
     */
    public static RawIamEvent of(
            String sourceId,
            SourceType sourceType,
            String nativeEventId,
            String nativePayload,
            Instant detectedAt,
            @Nullable ResourceContext resource
    ) {
        return of(sourceId, sourceType, nativeEventId, null, nativePayload, detectedAt, resource);
    }

    /**
     * Factory avec nativeEventType et contexte de ressource optionnels.
     */
    public static RawIamEvent of(
            String sourceId,
            SourceType sourceType,
            String nativeEventId,
            @Nullable String nativeEventType,
            String nativePayload,
            Instant detectedAt,
            @Nullable ResourceContext resource
    ) {
        return new RawIamEvent(
                sourceId,
                sourceType,
                nativeEventId,
                nativeEventType,
                nativePayload,
                detectedAt,
                computeDeduplicationKey(sourceId, nativeEventId, detectedAt),
                resource
        );
    }

    /**
     * SHA-256(sourceId | nativeEventId | timestamp tronqué à la seconde).
     * Deux événements identiques arrivant dans la même seconde (réplication AD multi-DC)
     * produisent la même clé → déduplication garantie.
     */
    static String computeDeduplicationKey(String sourceId, String nativeEventId, Instant detectedAt) {
        Instant truncated = detectedAt.truncatedTo(ChronoUnit.SECONDS);
        String input = sourceId + "|" + nativeEventId + "|" + truncated;
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 non disponible", e);
        }
    }
}

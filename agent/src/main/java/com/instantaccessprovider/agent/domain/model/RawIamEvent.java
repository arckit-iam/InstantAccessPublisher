package com.instantaccessprovider.agent.domain.model;

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
 * @param nativeEventId    ID de l'événement dans le système source
 * @param nativePayload    Payload brut non transformé (XML, JSON, CEF…)
 * @param detectedAt       Horodatage de détection par l'Agent (ISO 8601)
 * @param deduplicationKey SHA-256(sourceId | nativeEventId | timestamp/s)
 */
public record RawIamEvent(
        String sourceId,
        SourceType sourceType,
        String nativeEventId,
        String nativePayload,
        Instant detectedAt,
        String deduplicationKey
) {
    /**
     * Factory — calcule automatiquement la clé de déduplication.
     */
    public static RawIamEvent of(
            String sourceId,
            SourceType sourceType,
            String nativeEventId,
            String nativePayload,
            Instant detectedAt
    ) {
        return new RawIamEvent(
                sourceId,
                sourceType,
                nativeEventId,
                nativePayload,
                detectedAt,
                computeDeduplicationKey(sourceId, nativeEventId, detectedAt)
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

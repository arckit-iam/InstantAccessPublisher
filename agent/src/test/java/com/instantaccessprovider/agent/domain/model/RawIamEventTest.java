// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class RawIamEventTest {

    private static final String SOURCE_ID = "ad://corp.example.com";
    private static final String NATIVE_ID = "4728-a1b2c3";
    private static final Instant NOW = Instant.now();

    @Test
    void deduplicationKey_isDeterministic() {
        String key1 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, NOW);
        String key2 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, NOW);
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void deduplicationKey_isSha256Hex() {
        String key = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, NOW);
        assertThat(key).matches("[0-9a-f]{64}");
    }

    @Test
    void deduplicationKey_sameSecond_sameKey() {
        Instant t1 = NOW.truncatedTo(ChronoUnit.SECONDS);
        Instant t2 = t1.plusMillis(500); // même seconde, 500 ms plus tard
        String key1 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, t1);
        String key2 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, t2);
        assertThat(key1).isEqualTo(key2);
    }

    @Test
    void deduplicationKey_differentNativeId_differentKey() {
        String key1 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, "event-001", NOW);
        String key2 = RawIamEvent.computeDeduplicationKey(SOURCE_ID, "event-002", NOW);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void deduplicationKey_differentSourceId_differentKey() {
        String key1 = RawIamEvent.computeDeduplicationKey("ad://corp-a.com", NATIVE_ID, NOW);
        String key2 = RawIamEvent.computeDeduplicationKey("ad://corp-b.com", NATIVE_ID, NOW);
        assertThat(key1).isNotEqualTo(key2);
    }

    @Test
    void factory_setsDedupKeyMatchingComputeMethod() {
        RawIamEvent event = RawIamEvent.of(SOURCE_ID, SourceType.ACTIVE_DIRECTORY,
                NATIVE_ID, "<xml/>", NOW);
        String expected = RawIamEvent.computeDeduplicationKey(SOURCE_ID, NATIVE_ID, NOW);
        assertThat(event.deduplicationKey()).isEqualTo(expected);
    }

    @Test
    void factory_setsAllFields() {
        RawIamEvent event = RawIamEvent.of(SOURCE_ID, SourceType.ACTIVE_DIRECTORY,
                NATIVE_ID, "<xml/>", NOW);
        assertThat(event.sourceId()).isEqualTo(SOURCE_ID);
        assertThat(event.sourceType()).isEqualTo(SourceType.ACTIVE_DIRECTORY);
        assertThat(event.nativeEventId()).isEqualTo(NATIVE_ID);
        assertThat(event.nativePayload()).isEqualTo("<xml/>");
        assertThat(event.detectedAt()).isEqualTo(NOW);
    }

    @Test
    void factory_withoutResource_resourceIsNull() {
        RawIamEvent event = RawIamEvent.of(SOURCE_ID, SourceType.ACTIVE_DIRECTORY, NATIVE_ID, "<xml/>", NOW);
        assertThat(event.resource()).isNull();
    }

    @Test
    void factory_withResource_setsResourceContext() {
        ResourceContext resource = new ResourceContext("server", "DC01");
        RawIamEvent event = RawIamEvent.of(SOURCE_ID, SourceType.ACTIVE_DIRECTORY, NATIVE_ID, "<xml/>", NOW, resource);
        assertThat(event.resource()).isEqualTo(resource);
        assertThat(event.resource().type()).isEqualTo("server");
        assertThat(event.resource().ref()).isEqualTo("DC01");
    }
}

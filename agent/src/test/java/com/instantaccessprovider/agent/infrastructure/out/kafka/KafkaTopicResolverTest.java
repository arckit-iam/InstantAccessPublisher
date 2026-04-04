package com.instantaccessprovider.agent.infrastructure.out.kafka;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class KafkaTopicResolverTest {

    private final KafkaTopicResolver resolver = new KafkaTopicResolver();

    @ParameterizedTest(name = "{0} → {1}")
    @CsvSource({
            "ad://corp.example.com,          iam.raw.ad-corp.example.com",
            "syslog://fw-datacenter-01,       iam.raw.syslog-fw-datacenter-01",
            "webhook://app-rh,               iam.raw.webhook-app-rh",
            "dirsync://ldap.corp.example.com, iam.raw.dirsync-ldap.corp.example.com"
    })
    void resolve_sanitizesSourceIdTopic(String sourceId, String expectedTopic) {
        assertThat(resolver.resolve(sourceId.trim())).isEqualTo(expectedTopic.trim());
    }

    @Test
    void resolve_prefixIsAlwaysPresent() {
        String topic = resolver.resolve("ad://anything");
        assertThat(topic).startsWith(KafkaTopicResolver.TOPIC_PREFIX);
    }

    @Test
    void resolve_isLowercase() {
        String topic = resolver.resolve("AD://Corp.Example.COM");
        assertThat(topic).isEqualTo(topic.toLowerCase());
    }

    @Test
    void resolve_consecutiveDashesAreCollapsed() {
        // Les caractères spéciaux consécutifs ne doivent pas créer "--"
        String topic = resolver.resolve("ad://corp--example.com");
        assertThat(topic).doesNotContain("--");
    }
}

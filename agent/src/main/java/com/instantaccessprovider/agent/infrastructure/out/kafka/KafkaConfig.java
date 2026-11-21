// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.infrastructure.out.kafka;

import com.instantaccessprovider.agent.infrastructure.out.kafka.schema.RawIamEventMessage;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.Map;

/**
 * Déclare un {@link KafkaTemplate} typé {@code <String, RawIamEventMessage>}.
 *
 * La sérialisation JSON utilise le {@link JsonSerializer} de Spring Kafka.
 * Les headers de type Java ({@code __TypeId__}) sont désactivés pour garantir
 * l'interopérabilité avec les consommateurs non-Java.
 */
@Configuration
public class KafkaConfig {

    @Bean
    public ProducerFactory<String, RawIamEventMessage> iapProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> props = kafkaProperties.buildProducerProperties(null);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
        props.put(JsonSerializer.ADD_TYPE_INFO_HEADERS, false);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, RawIamEventMessage> iapKafkaTemplate(
            ProducerFactory<String, RawIamEventMessage> iapProducerFactory) {
        return new KafkaTemplate<>(iapProducerFactory);
    }
}

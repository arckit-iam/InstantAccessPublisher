package com.instantaccessprovider.agent;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

import java.util.List;

@SpringBootApplication
@EnableConfigurationProperties(AgentProperties.class)
public class AgentApplication {

    private static final Logger log = LoggerFactory.getLogger(AgentApplication.class);

    public static void main(String[] args) {
        SpringApplication.run(AgentApplication.class, args);
    }

    /**
     * Démarre tous les adapters activés au lancement de l'application.
     * Les adapters désactivés ({@code enabled: false}) sont ignorés silencieusement.
     */
    @Bean
    CommandLineRunner startAdapters(List<InputAdapter> adapters) {
        return args -> {
            long started = adapters.stream()
                    .filter(InputAdapter::isEnabled)
                    .peek(adapter -> {
                        log.info("Démarrage adapter : {}", adapter.adapterType());
                        adapter.start();
                    })
                    .count();

            if (started == 0) {
                log.warn("Aucun adapter activé — l'agent tourne mais ne collecte pas d'événements. "
                        + "Activez au moins un adapter dans application.yml (iap.agent.adapters.*.enabled: true).");
            }
        };
    }
}

package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.port.in.InputAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Adapter entrant — Windows Event Forwarding (Active Directory).
 *
 * <p>Flux de traitement :
 * <pre>
 *   ForwardedEvents (WEF channel)
 *     → EvtSubscribe (JNA / wevtapi.dll)
 *       → WinEvtSubscription.renderXml()
 *         → WefEventProcessor.process()
 *           → WefXmlParser, ResourceContextResolver
 *             → EventCollectionUseCase.handle(RawIamEvent)
 * </pre>
 *
 * <p>Cet adapter ne fonctionne que sur Windows (wevtapi.dll). Sur tout autre OS,
 * l'agent démarrera sans erreur si l'adapter est désactivé ; sinon une
 * {@link IllegalStateException} explicite sera levée au démarrage.
 */
@Component
public class WefAdapter implements InputAdapter {

    private static final Logger log = LoggerFactory.getLogger(WefAdapter.class);
    private static final String ADAPTER_TYPE = "wef";

    private final AgentProperties.WefProperties properties;
    private final WefEventProcessor processor;

    private volatile WinEvtSubscription subscription;

    public WefAdapter(AgentProperties props, WefEventProcessor processor) {
        this.properties = props.adapters() != null ? props.adapters().wef() : null;
        this.processor  = processor;
    }

    @Override
    public String adapterType() {
        return ADAPTER_TYPE;
    }

    @Override
    public boolean isEnabled() {
        return properties != null && properties.isEnabled();
    }

    @Override
    public void start() {
        if (!isEnabled()) {
            log.info("WEF adapter désactivé");
            return;
        }

        String xpathQuery = buildXpathQuery(properties.eventIds());
        String remoteHost = properties.remoteHost();
        try {
            subscription = new WinEvtSubscription(
                    properties.channelName(),
                    xpathQuery,
                    processor::process,
                    remoteHost
            );
            if (remoteHost != null && !remoteHost.isBlank()) {
                log.info("WEF adapter démarré — hôte={} canal={} eventIds={}",
                         remoteHost, properties.channelName(), processor.allowedEventIds());
            } else {
                log.info("WEF adapter démarré — canal={} eventIds={}",
                         properties.channelName(), processor.allowedEventIds());
            }
        } catch (UnsatisfiedLinkError e) {
            throw new IllegalStateException(
                    "wevtapi.dll non disponible. L'agent WEF doit s'exécuter sur Windows.", e);
        }
    }

    @Override
    public void stop() {
        if (subscription != null) {
            subscription.close();
            subscription = null;
            log.info("WEF adapter arrêté");
        }
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    /**
     * Construit le filtre XPath pour EvtSubscribe.
     *
     * <p>Exemple : {@code *[System[EventID=4720 or EventID=4728 or EventID=4730]]}
     */
    static String buildXpathQuery(List<Integer> eventIds) {
        if (eventIds == null || eventIds.isEmpty()) {
            return "*";
        }
        return eventIds.stream()
                .map(id -> "EventID=" + id)
                .collect(Collectors.joining(" or ", "*[System[", "]]"));
    }
}

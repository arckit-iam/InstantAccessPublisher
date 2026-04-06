package com.instantaccessprovider.agent.infrastructure.resource;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.model.ResourceContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Résout le {@link ResourceContext} d'un événement à partir des règles configurées
 * dans {@code iap.agent.resource-mappings}.
 *
 * <p>Les règles sont évaluées dans l'ordre de déclaration ; la première correspondance
 * gagne. Les patrons regex (champ {@code pattern}) sont compilés une seule fois au
 * démarrage pour des raisons de performance.
 *
 * <p>Usage dans un adapter entrant :
 * <pre>
 *   Optional&lt;ResourceContext&gt; resource =
 *       resolver.resolve(nativePayload, sourceId, nativeEventId);
 *   RawIamEvent event = RawIamEvent.of(sourceId, sourceType, nativeEventId,
 *                                      nativePayload, Instant.now(),
 *                                      resource.orElse(null));
 * </pre>
 */
@Component
public class ResourceContextResolver {

    private final List<CompiledRule> compiled;

    public ResourceContextResolver(AgentProperties properties) {
        this.compiled = properties.resourceMappings().stream()
                .map(CompiledRule::new)
                .toList();
    }

    /**
     * Résout un {@link ResourceContext} en appliquant les règles configurées.
     *
     * @param payload       nativePayload brut de l'événement (peut être null)
     * @param sourceId      sourceId de l'événement
     * @param nativeEventId nativeEventId de l'événement
     * @return le premier contexte résolu, ou {@link Optional#empty()} si aucune règle ne correspond
     */
    public Optional<ResourceContext> resolve(String payload, String sourceId, String nativeEventId) {
        if (compiled.isEmpty()) return Optional.empty();

        for (CompiledRule rule : compiled) {
            String candidate = switch (rule.matchField) {
                case "source-id"       -> sourceId;
                case "native-event-id" -> nativeEventId;
                default                -> payload; // "payload" + tout autre value → payload
            };
            if (candidate == null) continue;

            Matcher m = rule.pattern.matcher(candidate);
            if (m.find()) {
                String type = substituteGroups(m, rule.resourceType);
                String ref  = substituteGroups(m, rule.resourceRef);
                return Optional.of(new ResourceContext(type, ref));
            }
        }
        return Optional.empty();
    }

    // --------------------------------------------------------------------------
    // Internals
    // --------------------------------------------------------------------------

    /**
     * Substitue les références de groupes de capture {@code $1}.{@code $9}
     * dans un template.  Ex: template="$1" + group(1)="SX123456" → "SX123456".
     */
    private static String substituteGroups(Matcher m, String template) {
        String result = template;
        for (int i = 1; i <= m.groupCount(); i++) {
            String group = m.group(i);
            if (group != null) {
                result = result.replace("$" + i, group);
            }
        }
        return result;
    }

    private static final class CompiledRule {
        final String  matchField;
        final Pattern pattern;
        final String  resourceType;
        final String  resourceRef;

        CompiledRule(AgentProperties.ResourceMappingRule rule) {
            this.matchField   = rule.effectiveMatchField();
            this.pattern      = Pattern.compile(rule.pattern());
            this.resourceType = rule.resourceType();
            this.resourceRef  = rule.resourceRef();
        }
    }
}

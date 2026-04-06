package com.instantaccessprovider.agent.infrastructure.resource;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.model.ResourceContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ResourceContextResolverTest {

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static ResourceContextResolver resolverWith(AgentProperties.ResourceMappingRule... rules) {
        AgentProperties props = new AgentProperties("test", null, List.of(rules));
        return new ResourceContextResolver(props);
    }

    private static AgentProperties.ResourceMappingRule rule(String matchField, String pattern,
                                                              String type, String ref) {
        return new AgentProperties.ResourceMappingRule(matchField, pattern, type, ref);
    }

    // ------------------------------------------------------------------
    // No rules
    // ------------------------------------------------------------------

    @Test
    void resolve_noRules_returnsEmpty() {
        ResourceContextResolver resolver = resolverWith();
        assertThat(resolver.resolve("<xml/>", "ad://test", "4728")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Static mapping on payload
    // ------------------------------------------------------------------

    @Test
    void resolve_staticPayloadMatch_returnsContext() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=SX123456[,\\s]", "application", "SX123456")
        );
        String payload = "CN=grp,OU=SX123456,OU=Applications,DC=iap,DC=local";
        Optional<ResourceContext> result = resolver.resolve(payload, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("application");
        assertThat(result.get().ref()).isEqualTo("SX123456");
    }

    @Test
    void resolve_noMatch_returnsEmpty() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=SX123456[,\\s]", "application", "SX123456")
        );
        String payload = "CN=grp,OU=HR,OU=Groups,DC=iap,DC=local";
        assertThat(resolver.resolve(payload, "ad://iap.local", "4728")).isEmpty();
    }

    // ------------------------------------------------------------------
    // Dynamic mapping — capture group $1
    // ------------------------------------------------------------------

    @Test
    void resolve_captureGroupInRef_substituted() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=([A-Za-z0-9_-]+),OU=Applications,", "application", "$1")
        );
        String payload = "CN=grp,OU=SX999999,OU=Applications,DC=iap,DC=local";
        Optional<ResourceContext> result = resolver.resolve(payload, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("application");
        assertThat(result.get().ref()).isEqualTo("SX999999");
    }

    @Test
    void resolve_captureGroupInTypeAndRef_bothSubstituted() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=([A-Z]+)-([A-Z0-9]+),", "$1", "$2")
        );
        String payload = "CN=grp,OU=SERVER-DC01,OU=Infra,DC=iap,DC=local";
        Optional<ResourceContext> result = resolver.resolve(payload, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("SERVER");
        assertThat(result.get().ref()).isEqualTo("DC01");
    }

    // ------------------------------------------------------------------
    // Match on source-id
    // ------------------------------------------------------------------

    @Test
    void resolve_matchOnSourceId_returnsContext() {
        ResourceContextResolver resolver = resolverWith(
                rule("source-id", "ad://([^/]+)", "directory", "$1")
        );
        Optional<ResourceContext> result = resolver.resolve(null, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("directory");
        assertThat(result.get().ref()).isEqualTo("iap.local");
    }

    // ------------------------------------------------------------------
    // Match on native-event-id
    // ------------------------------------------------------------------

    @Test
    void resolve_matchOnNativeEventId_returnsContext() {
        ResourceContextResolver resolver = resolverWith(
                rule("native-event-id", "^(4727|4730)$", "group-lifecycle", "global-security")
        );
        Optional<ResourceContext> result = resolver.resolve("<xml/>", "ad://iap.local", "4727");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("group-lifecycle");
        assertThat(result.get().ref()).isEqualTo("global-security");
    }

    // ------------------------------------------------------------------
    // First-match-wins ordering
    // ------------------------------------------------------------------

    @Test
    void resolve_firstMatchWins() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=SX123456[,\\s]", "application", "SX123456"),
                rule("payload", "OU=([A-Za-z0-9_-]+),OU=Applications,", "application", "$1")
        );
        // Le premier pattern matche aussi bien que le second → doit retourner "SX123456" (statique)
        String payload = "CN=grp,OU=SX123456,OU=Applications,DC=iap,DC=local";
        Optional<ResourceContext> result = resolver.resolve(payload, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().ref()).isEqualTo("SX123456"); // règle 1 gagne
    }

    @Test
    void resolve_firstRuleNoMatch_fallsToSecond() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=SX123456[,\\s]", "application", "SX123456"),
                rule("payload", "OU=([A-Za-z0-9_-]+),OU=Servers,", "server", "$1")
        );
        String payload = "CN=grp,OU=DC01,OU=Servers,DC=iap,DC=local";
        Optional<ResourceContext> result = resolver.resolve(payload, "ad://iap.local", "4728");

        assertThat(result).isPresent();
        assertThat(result.get().type()).isEqualTo("server");
        assertThat(result.get().ref()).isEqualTo("DC01");
    }

    // ------------------------------------------------------------------
    // Null / empty payload
    // ------------------------------------------------------------------

    @Test
    void resolve_nullPayload_skipsPayloadRule() {
        ResourceContextResolver resolver = resolverWith(
                rule("payload", "OU=SX123456", "application", "SX123456")
        );
        assertThat(resolver.resolve(null, "ad://iap.local", "4728")).isEmpty();
    }

    @Test
    void resolve_nullMatchField_defaultsToPayload() {
        ResourceContextResolver resolver = resolverWith(
                rule(null, "OU=SX123456[,\\s]", "application", "SX123456")
        );
        String payload = "CN=grp,OU=SX123456,OU=Applications,DC=iap,DC=local";
        assertThat(resolver.resolve(payload, "ad://iap.local", "4728")).isPresent();
    }
}

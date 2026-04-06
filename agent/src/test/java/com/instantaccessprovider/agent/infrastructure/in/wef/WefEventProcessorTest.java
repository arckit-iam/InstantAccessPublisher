package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.instantaccessprovider.agent.config.AgentProperties;
import com.instantaccessprovider.agent.domain.model.RawIamEvent;
import com.instantaccessprovider.agent.domain.model.ResourceContext;
import com.instantaccessprovider.agent.domain.model.SourceType;
import com.instantaccessprovider.agent.domain.port.in.EventCollectionUseCase;
import com.instantaccessprovider.agent.infrastructure.resource.ResourceContextResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WefEventProcessorTest {

    // ---------------------------------------------------------------------------
    // Fixtures XML
    // ---------------------------------------------------------------------------

    /** Evento 4720 — EventRecordID=12345, Computer=DC01.iap.local */
    private static final String EVENT_4720 = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"/>
                <EventID>4720</EventID>
                <Version>0</Version><Level>0</Level><Task>13824</Task><Opcode>0</Opcode>
                <Keywords>0x8020000000000000</Keywords>
                <TimeCreated SystemTime="2026-04-06T10:37:27.1234567Z"/>
                <EventRecordID>12345</EventRecordID>
                <Correlation/><Execution ProcessID="512" ThreadID="1024"/>
                <Channel>Security</Channel>
                <Computer>DC01.iap.local</Computer>
                <Security/>
              </System>
              <EventData>
                <Data Name="TargetUserName">wef-test-0001</Data>
                <Data Name="SamAccountName">wef-test-0001</Data>
              </EventData>
            </Event>
            """;

    /** Événement non configuré (EventID=9999) — doit être filtré. */
    private static final String EVENT_9999 = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"/>
                <EventID>9999</EventID>
                <Version>0</Version><Level>0</Level><Task>0</Task><Opcode>0</Opcode>
                <Keywords>0x0</Keywords>
                <TimeCreated SystemTime="2026-04-06T12:00:00.0000000Z"/>
                <EventRecordID>55555</EventRecordID>
                <Correlation/><Execution ProcessID="512" ThreadID="1024"/>
                <Channel>Security</Channel>
                <Computer>DC01.iap.local</Computer>
                <Security/>
              </System>
              <EventData/>
            </Event>
            """;

    /** Événement 4728 avec MemberName dans une OU application (pour le test de ResourceContext). */
    private static final String EVENT_4728_WITH_APP_OU = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"/>
                <EventID>4728</EventID>
                <Version>0</Version><Level>0</Level><Task>13826</Task><Opcode>0</Opcode>
                <Keywords>0x8020000000000000</Keywords>
                <TimeCreated SystemTime="2026-04-06T11:00:00.0000000Z"/>
                <EventRecordID>99999</EventRecordID>
                <Correlation/><Execution ProcessID="512" ThreadID="1025"/>
                <Channel>Security</Channel>
                <Computer>DC01.iap.local</Computer>
                <Security/>
              </System>
              <EventData>
                <Data Name="MemberName">CN=wef-test-0001,OU=SX999999,OU=Applications,DC=iap,DC=local</Data>
                <Data Name="TargetUserName">grp-app-sx999999</Data>
                <Data Name="TargetSid">S-1-5-21-984562994-3965904818-3631805304-1300</Data>
              </EventData>
            </Event>
            """;

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    /**
     * Crée un processor avec les eventIds configurés et un vrai {@link ResourceContextResolver}
     * (sans règles de mapping).
     */
    private WefEventProcessor processorWith(EventCollectionUseCase useCase, List<Integer> eventIds) {
        AgentProperties props = propsWithEventIds(eventIds);
        ResourceContextResolver resolver = new ResourceContextResolver(props);
        return new WefEventProcessor(props, useCase, resolver);
    }

    /** Crée un processor avec un resolver injecté (pour tester la résolution de ressource). */
    private WefEventProcessor processorWith(EventCollectionUseCase useCase,
                                             List<Integer> eventIds,
                                             ResourceContextResolver resolver) {
        AgentProperties props = propsWithEventIds(eventIds);
        return new WefEventProcessor(props, useCase, resolver);
    }

    private static AgentProperties propsWithEventIds(List<Integer> eventIds) {
        return new AgentProperties(
                "test-agent",
                new AgentProperties.AdaptersProperties(
                        new AgentProperties.WefProperties(true, "ForwardedEvents", eventIds, "ad://iap.local"),
                        null, null, null
                ),
                List.of()
        );
    }

    // ---------------------------------------------------------------------------
    // Tests : filtrage
    // ---------------------------------------------------------------------------

    @Test
    void process_eventIdInAllowedList_useCaseCalled() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720, 4728));

        processor.process(EVENT_4720);

        verify(useCase, times(1)).handle(any(RawIamEvent.class));
    }

    @Test
    void process_eventIdNotInAllowedList_useCaseNotCalled() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720, 4728));

        processor.process(EVENT_9999);

        verify(useCase, never()).handle(any());
    }

    @Test
    void process_emptyAllowedList_useCaseNotCalled() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        WefEventProcessor processor = processorWith(useCase, List.of());

        processor.process(EVENT_4720);

        verify(useCase, never()).handle(any());
    }

    // ---------------------------------------------------------------------------
    // Tests : construction du RawIamEvent
    // ---------------------------------------------------------------------------

    @Test
    void process_nativeEventId_isRecordIdAtComputer() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().nativeEventId()).isEqualTo("12345@DC01.iap.local");
    }

    @Test
    void process_sourceId_comesFromProperties() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().sourceId()).isEqualTo("ad://iap.local");
    }

    @Test
    void process_sourceType_isActiveDirectory() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().sourceType()).isEqualTo(SourceType.ACTIVE_DIRECTORY);
    }

    @Test
    void process_nativePayload_isRawXml() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().nativePayload()).isEqualTo(EVENT_4720);
    }

    @Test
    void process_detectedAt_isNotNull() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().detectedAt()).isNotNull();
    }

    // ---------------------------------------------------------------------------
    // Tests : résolution du ResourceContext
    // ---------------------------------------------------------------------------

    @Test
    void process_withMatchingResourceRule_resourceContextAttached() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);

        // Règle : payload contient OU=SX999999,OU=Applications, → type=application, ref=SX999999
        AgentProperties.ResourceMappingRule rule = new AgentProperties.ResourceMappingRule(
                "payload",
                "OU=([A-Za-z0-9_-]+),OU=Applications,",
                "application",
                "$1"
        );
        AgentProperties props = new AgentProperties(
                "test-agent",
                new AgentProperties.AdaptersProperties(
                        new AgentProperties.WefProperties(true, "ForwardedEvents",
                                List.of(4728), "ad://iap.local"),
                        null, null, null
                ),
                List.of(rule)
        );
        ResourceContextResolver resolver = new ResourceContextResolver(props);
        WefEventProcessor processor = new WefEventProcessor(props, useCase, resolver);

        processor.process(EVENT_4728_WITH_APP_OU);

        verify(useCase).handle(captor.capture());
        RawIamEvent event = captor.getValue();
        assertThat(event.resource()).isNotNull();
        assertThat(event.resource().type()).isEqualTo("application");
        assertThat(event.resource().ref()).isEqualTo("SX999999");
    }

    @Test
    void process_withoutResourceRule_resourceIsNull() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        processor.process(EVENT_4720);

        verify(useCase).handle(captor.capture());
        assertThat(captor.getValue().resource()).isNull();
    }

    @Test
    void process_nativeEventIdRuleOnEventType_resourceContextAttached() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        ArgumentCaptor<RawIamEvent> captor = ArgumentCaptor.forClass(RawIamEvent.class);

        // Règle sur native-event-id=4728 → apply fixed type/ref
        AgentProperties.ResourceMappingRule rule = new AgentProperties.ResourceMappingRule(
                "native-event-id",
                "^4728$",
                "group-lifecycle",
                "global"
        );
        AgentProperties props = new AgentProperties(
                "test-agent",
                new AgentProperties.AdaptersProperties(
                        new AgentProperties.WefProperties(true, "ForwardedEvents",
                                List.of(4728), "ad://iap.local"),
                        null, null, null
                ),
                List.of(rule)
        );
        ResourceContextResolver resolver = new ResourceContextResolver(props);
        WefEventProcessor processor = new WefEventProcessor(props, useCase, resolver);

        processor.process(EVENT_4728_WITH_APP_OU);

        verify(useCase).handle(captor.capture());
        ResourceContext ctx = captor.getValue().resource();
        assertThat(ctx).isNotNull();
        assertThat(ctx.type()).isEqualTo("group-lifecycle");
        assertThat(ctx.ref()).isEqualTo("global");
    }

    // ---------------------------------------------------------------------------
    // Tests : robustesse
    // ---------------------------------------------------------------------------

    @Test
    void process_invalidXml_doesNotThrow_useCaseNotCalled() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        // Ne doit pas propager d'exception (le callback JNA ne doit pas crasher la JVM)
        assertThatCode(() -> processor.process("<invalid xml <<"))
                .doesNotThrowAnyException();

        verify(useCase, never()).handle(any());
    }

    @Test
    void process_emptyXml_doesNotThrow_useCaseNotCalled() {
        EventCollectionUseCase useCase = mock(EventCollectionUseCase.class);
        WefEventProcessor processor = processorWith(useCase, List.of(4720));

        assertThatCode(() -> processor.process(""))
                .doesNotThrowAnyException();

        verify(useCase, never()).handle(any());
    }

    // ---------------------------------------------------------------------------
    // Tests : introspection (sourceId, allowedEventIds)
    // ---------------------------------------------------------------------------

    @Test
    void sourceId_returnsConfiguredValue() {
        WefEventProcessor processor = processorWith(mock(EventCollectionUseCase.class), List.of(4720));
        assertThat(processor.sourceId()).isEqualTo("ad://iap.local");
    }

    @Test
    void allowedEventIds_returnsConfiguredSet() {
        WefEventProcessor processor = processorWith(mock(EventCollectionUseCase.class), List.of(4720, 4728, 4730));
        assertThat(processor.allowedEventIds()).containsExactlyInAnyOrder(4720, 4728, 4730);
    }

    @Test
    void allowedEventIds_nullConfig_returnsEmptySet() {
        AgentProperties propsNoWef = new AgentProperties("test", null, List.of());
        ResourceContextResolver resolver = new ResourceContextResolver(propsNoWef);
        WefEventProcessor processor = new WefEventProcessor(propsNoWef, mock(EventCollectionUseCase.class), resolver);

        assertThat(processor.allowedEventIds()).isEmpty();
    }
}

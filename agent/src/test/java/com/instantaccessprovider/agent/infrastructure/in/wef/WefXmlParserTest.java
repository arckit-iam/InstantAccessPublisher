package com.instantaccessprovider.agent.infrastructure.in.wef;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WefXmlParserTest {

    private final WefXmlParser parser = new WefXmlParser();

    // ---------------------------------------------------------------------------
    // Fixtures — XML Windows Event Log avec namespace complet
    // ---------------------------------------------------------------------------

    /** Événement 4720 : création d'un compte utilisateur AD. */
    private static final String EVENT_4720 = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"
                          Guid="{54849625-5478-4994-A5BA-3E3B0328C30D}"/>
                <EventID>4720</EventID>
                <Version>0</Version>
                <Level>0</Level>
                <Task>13824</Task>
                <Opcode>0</Opcode>
                <Keywords>0x8020000000000000</Keywords>
                <TimeCreated SystemTime="2026-04-06T10:37:27.1234567Z"/>
                <EventRecordID>12345</EventRecordID>
                <Correlation/>
                <Execution ProcessID="512" ThreadID="1024"/>
                <Channel>Security</Channel>
                <Computer>DC01.iap.local</Computer>
                <Security UserID="S-1-5-21-984562994-3965904818-3631805304-500"/>
              </System>
              <EventData>
                <Data Name="TargetUserName">wef-test-0001</Data>
                <Data Name="SamAccountName">wef-test-0001</Data>
              </EventData>
            </Event>
            """;

    /** Événement 4728 : ajout d'un membre à un groupe global. */
    private static final String EVENT_4728 = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"/>
                <EventID>4728</EventID>
                <Version>0</Version>
                <Level>0</Level>
                <Task>13826</Task>
                <Opcode>0</Opcode>
                <Keywords>0x8020000000000000</Keywords>
                <TimeCreated SystemTime="2026-04-06T10:37:35.0000000Z"/>
                <EventRecordID>12350</EventRecordID>
                <Correlation/>
                <Execution ProcessID="512" ThreadID="1025"/>
                <Channel>Security</Channel>
                <Computer>DC01.iap.local</Computer>
                <Security/>
              </System>
              <EventData>
                <Data Name="MemberName">CN=wef-test-0001,CN=Users,DC=iap,DC=local</Data>
                <Data Name="TargetUserName">wef-grp-global-0001</Data>
                <Data Name="TargetSid">S-1-5-21-984562994-3965904818-3631805304-1200</Data>
              </EventData>
            </Event>
            """;

    /** Événement provenant d'un second DC avec RecordID différent. */
    private static final String EVENT_DC02 = """
            <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
              <System>
                <Provider Name="Microsoft-Windows-Security-Auditing"/>
                <EventID>4722</EventID>
                <Version>0</Version>
                <Level>0</Level>
                <Task>13824</Task>
                <Opcode>0</Opcode>
                <Keywords>0x8020000000000000</Keywords>
                <TimeCreated SystemTime="2026-04-07T08:00:00.0000000Z"/>
                <EventRecordID>99001</EventRecordID>
                <Correlation/>
                <Execution ProcessID="788" ThreadID="900"/>
                <Channel>Security</Channel>
                <Computer>DC02.iap.local</Computer>
                <Security/>
              </System>
              <EventData>
                <Data Name="TargetUserName">john.doe</Data>
              </EventData>
            </Event>
            """;

    // ---------------------------------------------------------------------------
    // Tests : parsing correct
    // ---------------------------------------------------------------------------

    @Test
    void parse_event4720_allFieldsExtracted() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_4720);

        assertThat(raw.eventId()).isEqualTo(4720);
        assertThat(raw.recordId()).isEqualTo(12345L);
        assertThat(raw.computer()).isEqualTo("DC01.iap.local");
        assertThat(raw.channel()).isEqualTo("Security");
        assertThat(raw.timeCreated()).isEqualTo(Instant.parse("2026-04-06T10:37:27.1234567Z"));
        assertThat(raw.rawXml()).isSameAs(EVENT_4720); // pas de copie
    }

    @Test
    void parse_event4728_allFieldsExtracted() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_4728);

        assertThat(raw.eventId()).isEqualTo(4728);
        assertThat(raw.recordId()).isEqualTo(12350L);
        assertThat(raw.computer()).isEqualTo("DC01.iap.local");
        assertThat(raw.channel()).isEqualTo("Security");
    }

    @Test
    void parse_dc02Event_computerCorrectelyExtracted() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_DC02);

        assertThat(raw.eventId()).isEqualTo(4722);
        assertThat(raw.recordId()).isEqualTo(99001L);
        assertThat(raw.computer()).isEqualTo("DC02.iap.local");
    }

    /** Windows utilise 7 décimales (précision 100 ns) — java.time.Instant doit les accepter. */
    @Test
    void parse_windowsTimestamp7Dec_parsedToInstant() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_4720);
        // "2026-04-06T10:37:27.1234567Z" → las 7 décimales ne doivent pas lever d'exception
        assertThat(raw.timeCreated().toString()).startsWith("2026-04-06T10:37:27");
    }

    @Test
    void parse_timestampZeroFraction_parsedCorrectly() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_4728);
        assertThat(raw.timeCreated()).isEqualTo(Instant.parse("2026-04-06T10:37:35.000000000Z"));
    }

    @Test
    void parse_rawXml_preservedInRecord() throws WefParseException {
        WefRawEvent raw = parser.parse(EVENT_4728);
        assertThat(raw.rawXml()).isEqualTo(EVENT_4728);
    }

    // ---------------------------------------------------------------------------
    // Tests : erreurs de parsing
    // ---------------------------------------------------------------------------

    @Test
    void parse_invalidXml_throwsWefParseException() {
        assertThatThrownBy(() -> parser.parse("<not valid xml <<"))
                .isInstanceOf(WefParseException.class);
    }

    @Test
    void parse_emptyString_throwsWefParseException() {
        assertThatThrownBy(() -> parser.parse(""))
                .isInstanceOf(WefParseException.class);
    }

    @Test
    void parse_xmlMissingEventId_throwsWefParseException() {
        String xmlNoEventId = """
                <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
                  <System>
                    <TimeCreated SystemTime="2026-04-06T10:00:00.0000000Z"/>
                    <EventRecordID>1</EventRecordID>
                    <Channel>Security</Channel>
                    <Computer>DC01.iap.local</Computer>
                    <Security/>
                  </System>
                </Event>
                """;
        assertThatThrownBy(() -> parser.parse(xmlNoEventId))
                .isInstanceOf(WefParseException.class);
    }

    @Test
    void parse_xmlMissingRecordId_throwsWefParseException() {
        String xmlNoRecordId = """
                <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
                  <System>
                    <EventID>4720</EventID>
                    <TimeCreated SystemTime="2026-04-06T10:00:00.0000000Z"/>
                    <Channel>Security</Channel>
                    <Computer>DC01.iap.local</Computer>
                    <Security/>
                  </System>
                </Event>
                """;
        assertThatThrownBy(() -> parser.parse(xmlNoRecordId))
                .isInstanceOf(WefParseException.class);
    }

    // ---------------------------------------------------------------------------
    // Tests : buildXpathQuery (méthode statique de WefAdapter)
    // ---------------------------------------------------------------------------

    @Test
    void buildXpathQuery_emptyList_returnsStar() {
        assertThat(WefAdapter.buildXpathQuery(List.of())).isEqualTo("*");
    }

    @Test
    void buildXpathQuery_nullList_returnsStar() {
        assertThat(WefAdapter.buildXpathQuery(null)).isEqualTo("*");
    }

    @Test
    void buildXpathQuery_singleId_correctXpath() {
        assertThat(WefAdapter.buildXpathQuery(List.of(4720)))
                .isEqualTo("*[System[EventID=4720]]");
    }

    @Test
    void buildXpathQuery_multipleIds_allIncluded() {
        String query = WefAdapter.buildXpathQuery(List.of(4720, 4728, 4730));
        assertThat(query)
                .startsWith("*[System[")
                .endsWith("]]")
                .contains("EventID=4720")
                .contains("EventID=4728")
                .contains("EventID=4730")
                .contains(" or ");
    }
}

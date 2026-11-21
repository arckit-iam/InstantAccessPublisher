// SPDX-FileCopyrightText: 2026 Dédale Solutions
// SPDX-License-Identifier: EUPL-1.2

package com.instantaccessprovider.agent.infrastructure.in.wef;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Parse le XML Windows Event Log livré par WEF (ContentFormat=Events).
 *
 * <p>Le format attendu est l'enveloppe standard Windows :
 * <pre>{@code
 * <Event xmlns="http://schemas.microsoft.com/win/2004/08/events/event">
 *   <System>
 *     <EventID>4720</EventID>
 *     <EventRecordID>12345</EventRecordID>
 *     <TimeCreated SystemTime="2026-04-06T10:37:27.1234567Z"/>
 *     <Computer>DC01.iap.local</Computer>
 *     <Channel>Security</Channel>
 *     ...
 *   </System>
 *   <EventData>...</EventData>
 * </Event>
 * }</pre>
 *
 * <p>Cette classe est intentionnellement sans état afin d'être thread-safe :
 * un nouveau {@link DocumentBuilder} est créé à chaque appel de {@link #parse}.
 */
class WefXmlParser {

    static final String WEF_NS = "http://schemas.microsoft.com/win/2004/08/events/event";

    private static final DocumentBuilderFactory FACTORY;

    static {
        FACTORY = DocumentBuilderFactory.newInstance();
        FACTORY.setNamespaceAware(true);
    }

    /**
     * Parse un événement WEF XML.
     *
     * @param xml le XML complet de l'événement tel que livré par EvtRender
     * @return l'événement parsé
     * @throws WefParseException si le XML est invalide ou si un champ obligatoire est manquant
     */
    WefRawEvent parse(String xml) throws WefParseException {
        try {
            DocumentBuilder builder = FACTORY.newDocumentBuilder();
            // Désactive la résolution d'entités externes (XXE mitigation)
            builder.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));

            Document doc = builder.parse(new InputSource(new StringReader(xml)));
            Element system = requiredElement(doc, "System");

            int    eventId   = Integer.parseInt(requiredChildText(system, "EventID"));
            long   recordId  = Long.parseLong(requiredChildText(system, "EventRecordID"));
            String computer  = requiredChildText(system, "Computer");
            String channel   = requiredChildText(system, "Channel");

            Element timeCreatedEl = (Element) system.getElementsByTagNameNS(WEF_NS, "TimeCreated").item(0);
            if (timeCreatedEl == null) throw new WefParseException("Champ TimeCreated manquant", null);
            Instant timeCreated = parseSystemTime(timeCreatedEl.getAttribute("SystemTime"));

            return new WefRawEvent(eventId, recordId, timeCreated, computer, channel, xml);

        } catch (WefParseException e) {
            throw e;
        } catch (Exception e) {
            throw new WefParseException("Parsing XML WEF échoué", e);
        }
    }

    // --------------------------------------------------------------------------
    // Helpers
    // --------------------------------------------------------------------------

    private static Element requiredElement(Document doc, String localName) throws WefParseException {
        NodeList nodes = doc.getElementsByTagNameNS(WEF_NS, localName);
        if (nodes.getLength() == 0)
            throw new WefParseException("Élément <" + localName + "> manquant dans l'événement WEF", null);
        return (Element) nodes.item(0);
    }

    private static String requiredChildText(Element parent, String localName) throws WefParseException {
        NodeList nodes = parent.getElementsByTagNameNS(WEF_NS, localName);
        if (nodes.getLength() == 0)
            throw new WefParseException("Champ <" + localName + "> manquant dans <System>", null);
        String text = nodes.item(0).getTextContent().trim();
        if (text.isEmpty())
            throw new WefParseException("Champ <" + localName + "> vide dans <System>", null);
        return text;
    }

    /**
     * Parse le format ISO 8601 Windows (jusqu'à 7 décimales = 100ns precision).
     * Ex: "2026-04-06T10:37:27.1234567Z"
     */
    private static Instant parseSystemTime(String systemTime) throws WefParseException {
        if (systemTime == null || systemTime.isBlank())
            throw new WefParseException("Attribut SystemTime vide sur TimeCreated", null);
        try {
            return Instant.parse(systemTime);
        } catch (DateTimeParseException e) {
            throw new WefParseException("Format SystemTime invalide : " + systemTime, e);
        }
    }
}

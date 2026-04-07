package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Gère le cycle de vie d'un abonnement Windows Event Log via {@link WinEvtApi}.
 *
 * <p>L'abonnement est non bloquant : Windows livre les événements sur un thread pool
 * via le callback {@link WinEvtApi.EvtSubscribeCallback}. La référence au callback
 * doit être maintenue en vie (champ d'instance) pour éviter le garbage collection.
 *
 * <p>Utiliser dans un try-with-resources ou appeler {@link #close()} à l'arrêt.
 */
class WinEvtSubscription implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(WinEvtSubscription.class);

    /** Erreur Windows : buffer trop petit. */
    private static final int ERROR_INSUFFICIENT_BUFFER = 122;

    /** Taille maximale tolérée pour un événement (1 Mo). Sanity check contre des XML pathologiques. */
    private static final int MAX_EVENT_SIZE_BYTES = 1024 * 1024;

    private final HANDLE subscriptionHandle;
    // Null quand l'abonnement est local.
    private final HANDLE sessionHandle;
    // IMPORTANT : cette référence doit rester vivante tant que l'abonnement est actif.
    // Si le GC collecte l'objet, Windows appellera un pointeur de fonction invalide → JVM crash.
    @SuppressWarnings("FieldCanBeLocal")
    private final WinEvtApi.EvtSubscribeCallback callback;

    /**
     * Crée et démarre immédiatement l'abonnement (canal local).
     *
     * @param channelPath  canal WEF, ex: "ForwardedEvents"
     * @param xpathQuery   filtre XPath, ex: "*[System[EventID=4720 or EventID=4728]]"
     * @param xmlConsumer  appelé pour chaque événement livré (XML brut complet)
     * @throws IllegalStateException si EvtSubscribe échoue
     */
    WinEvtSubscription(String channelPath, String xpathQuery, Consumer<String> xmlConsumer) {
        this(channelPath, xpathQuery, xmlConsumer, null);
    }

    /**
     * Crée et démarre immédiatement l'abonnement, avec support de session distante.
     *
     * <p>Si {@code remoteHost} est non null/blanc, une session RPC est ouverte vers
     * ce serveur en utilisant les identifiants Kerberos du compte courant.
     * Le compte doit être membre du groupe <em>Event Log Readers</em> sur le serveur distant.
     *
     * @param channelPath  canal WEF, ex: "ForwardedEvents"
     * @param xpathQuery   filtre XPath, ex: "*[System[EventID=4720 or EventID=4728]]"
     * @param xmlConsumer  appelé pour chaque événement livré (XML brut complet)
     * @param remoteHost   hostname ou IP du collecteur WEF distant, null = local
     * @throws IllegalStateException si EvtOpenSession ou EvtSubscribe échoue
     */
    WinEvtSubscription(String channelPath, String xpathQuery, Consumer<String> xmlConsumer,
                       String remoteHost) {
        WinEvtApi api = WinEvtApi.INSTANCE;

        // Ouvrir une session distante si nécessaire
        if (remoteHost != null && !remoteHost.isBlank()) {
            WinEvtApi.EvtRpcLogin login = new WinEvtApi.EvtRpcLogin();
            login.server   = new WString(remoteHost);
            login.user     = null;
            login.domain   = null;
            login.password = null;
            login.flags    = WinEvtApi.EVT_RPC_LOGIN_AUTH_DEFAULT;
            login.write();

            this.sessionHandle = api.EvtOpenSession(
                    WinEvtApi.EVT_LOGIN_CLASS_RPC_LOGIN, login.getPointer(), 0, 0);

            if (sessionHandle == null) {
                throw new IllegalStateException(
                        "EvtOpenSession échoué pour l'hôte '" + remoteHost +
                        "' (GetLastError=" + Native.getLastError() + "). " +
                        "Vérifiez que le compte courant est membre d'Event Log Readers sur " + remoteHost);
            }
            log.info("Session RPC ouverte vers {}", remoteHost);
        } else {
            this.sessionHandle = null;
        }

        this.callback = (action, userContext, eventHandle) -> {
            if (action == WinEvtApi.EVT_SUBSCRIBE_ACTION_DELIVER) {
                try {
                    String xml = renderXml(api, eventHandle);
                    xmlConsumer.accept(xml);
                } catch (Exception e) {
                    // Ne pas propager — crasherait la JVM
                    log.error("Erreur lors du rendu/traitement d'un événement WEF", e);
                }
            }
            return 0; // ERROR_SUCCESS
        };

        this.subscriptionHandle = api.EvtSubscribe(
                sessionHandle,
                null,
                new WString(channelPath),
                new WString(xpathQuery),
                null,
                null,
                callback,
                WinEvtApi.EVT_SUBSCRIBE_TO_FUTURE_EVENTS
        );

        if (subscriptionHandle == null) {
            throw new IllegalStateException(
                    "EvtSubscribe échoué pour le canal '" + channelPath +
                    "' (GetLastError=" + Native.getLastError() + ")");
        }
    }

    @Override
    public void close() {
        if (subscriptionHandle != null) {
            WinEvtApi.INSTANCE.EvtClose(subscriptionHandle);
        }
        if (sessionHandle != null) {
            WinEvtApi.INSTANCE.EvtClose(sessionHandle);
            log.info("Session RPC WEF distante fermée");
        }
        log.info("Abonnement WEF fermé");
    }

    // --------------------------------------------------------------------------
    // Rendu XML (pattern deux appels Windows)
    // --------------------------------------------------------------------------

    /**
     * Rend l'événement en XML via EvtRender.
     *
     * <p>Pattern standard Windows :
     * <ol>
     *   <li>Premier appel avec buffer=0 → ERROR_INSUFFICIENT_BUFFER + taille requise dans bufferUsed</li>
     *   <li>Allouer le buffer</li>
     *   <li>Deuxième appel avec le buffer → XML UTF-16LE dans le buffer</li>
     * </ol>
     */
    private static String renderXml(WinEvtApi api, HANDLE eventHandle) {
        IntByReference bufferUsed   = new IntByReference(0);
        IntByReference propCount    = new IntByReference(0);

        // Premier appel : obtenir la taille requise
        api.EvtRender(null, eventHandle, WinEvtApi.EVT_RENDER_EVENT_XML,
                      0, null, bufferUsed, propCount);

        int sizeBytes = bufferUsed.getValue();
        if (sizeBytes <= 0 || sizeBytes > MAX_EVENT_SIZE_BYTES) {
            throw new IllegalStateException(
                    "Taille d'événement WEF inattendue : " + sizeBytes + " octets");
        }

        // Deuxième appel : rendu effectif
        Memory buffer = new Memory(sizeBytes);
        if (!api.EvtRender(null, eventHandle, WinEvtApi.EVT_RENDER_EVENT_XML,
                           sizeBytes, buffer, bufferUsed, propCount)) {
            throw new IllegalStateException(
                    "EvtRender échoué (GetLastError=" + Native.getLastError() + ")");
        }

        // Le buffer contient une chaîne UTF-16LE null-terminée
        return buffer.getWideString(0);
    }
}

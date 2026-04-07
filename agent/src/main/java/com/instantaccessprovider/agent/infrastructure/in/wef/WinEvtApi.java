package com.instantaccessprovider.agent.infrastructure.in.wef;

import com.sun.jna.Callback;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;

import java.util.List;

/**
 * Interface JNA vers {@code wevtapi.dll} (Windows Event Log API).
 *
 * <p>Seules les fonctions nécessaires à la collecte WEF sont déclarées :
 * {@code EvtSubscribe}, {@code EvtRender}, {@code EvtClose}.
 *
 * <p>Les paramètres de type chaîne utilisent {@link WString} (LPCWSTR) car l'API
 * Windows Event Log est exclusivement Unicode.
 *
 * @see <a href="https://learn.microsoft.com/en-us/windows/win32/api/winevt/">Windows Event Log API</a>
 */
interface WinEvtApi extends StdCallLibrary {

    WinEvtApi INSTANCE = Native.load("wevtapi", WinEvtApi.class);

    // ------------------------------------------------------------------
    // Constantes
    // ------------------------------------------------------------------

    /** Livrer uniquement les événements futurs (depuis le moment de l'abonnement). */
    int EVT_SUBSCRIBE_TO_FUTURE_EVENTS  = 1;

    /** Action dans le callback : un événement est disponible. */
    int EVT_SUBSCRIBE_ACTION_DELIVER    = 1;

    /** Flag EvtRender : rendu en XML complet de l'événement. */
    int EVT_RENDER_EVENT_XML            = 1;

    /** LoginClass pour EvtOpenSession : connexion RPC (seule valeur supportée). */
    int EVT_LOGIN_CLASS_RPC_LOGIN       = 1;

    /** Authentification par défaut (Kerberos/NTLM intégré de l'utilisateur courant). */
    int EVT_RPC_LOGIN_AUTH_DEFAULT      = 0;

    // ------------------------------------------------------------------
    // Structures
    // ------------------------------------------------------------------

    /**
     * Structure passée à {@link #EvtOpenSession} pour une connexion RPC distante.
     *
     * <p>Laisser {@code user}, {@code domain} et {@code password} à {@code null}
     * pour utiliser les identifiants du compte courant (Kerberos/NTLM intégré).
     */
    @Structure.FieldOrder({"server", "user", "domain", "password", "flags"})
    class EvtRpcLogin extends Structure {
        /** Nom du serveur distant, ex: "WEC01" ou "192.168.10.20". */
        public WString server;
        /** Nom d'utilisateur, ou null pour le compte courant. */
        public WString user;
        /** Domaine, ou null pour le domaine courant. */
        public WString domain;
        /** Mot de passe, ou null pour le compte courant. */
        public WString password;
        /** Flags d'authentification : {@link #EVT_RPC_LOGIN_AUTH_DEFAULT}. */
        public int flags;
    }

    // ------------------------------------------------------------------
    // Fonctions
    // ------------------------------------------------------------------

    /**
     * Callback invoqué par le thread pool Windows pour chaque événement livré.
     * ATTENTION : ne jamais lancer d'exception depuis le callback — cela crasherait la JVM.
     */
    interface EvtSubscribeCallback extends Callback {
        /** @return 0 (ERROR_SUCCESS) si traitement OK ; le code Windows sera logué sinon. */
        int invoke(int action, Pointer userContext, HANDLE event);
    }

    /**
     * Crée un abonnement push aux événements d'un canal Windows Event Log.
     *
     * @param Session     null = session locale
     * @param SignalEvent null = mode callback (pas signal)
     * @param ChannelPath nom du canal, ex: "ForwardedEvents"
     * @param Query       XPath, ex: "*[System[EventID=4720 or EventID=4728]]"
     * @param Bookmark    null = pas de reprise (voir EVT_SUBSCRIBE_TO_FUTURE_EVENTS)
     * @param Context     contexte libre passé au callback
     * @param Callback    callback appelé pour chaque événement
     * @param Flags       EVT_SUBSCRIBE_TO_FUTURE_EVENTS ou EVT_SUBSCRIBE_START_AT_OLDEST_RECORD
     * @return handle de l'abonnement (à fermer avec EvtClose), null si erreur
     */
    HANDLE EvtSubscribe(
            HANDLE Session,
            HANDLE SignalEvent,
            WString ChannelPath,
            WString Query,
            HANDLE Bookmark,
            Pointer Context,
            EvtSubscribeCallback Callback,
            int Flags
    );

    /**
     * Rend un événement dans un format donné (ici XML).
     *
     * @param Context        null pour EVT_RENDER_EVENT_XML
     * @param Fragment       handle de l'événement (fourni dans le callback)
     * @param Flags          EVT_RENDER_EVENT_XML
     * @param BufferSize     taille du buffer en octets
     * @param Buffer         buffer de destination (null pour obtenir la taille requise)
     * @param BufferUsed     taille utilisée / requise (en octets)
     * @param PropertyCount  nombre de propriétés rendues (non utilisé en mode XML)
     * @return true si succès ; false + GetLastError()=122 si buffer trop petit
     */
    boolean EvtRender(
            HANDLE Context,
            HANDLE Fragment,
            int Flags,
            int BufferSize,
            Pointer Buffer,
            IntByReference BufferUsed,
            IntByReference PropertyCount
    );

    /** Ferme un handle ouvert par une API EvtXxx. */
    boolean EvtClose(HANDLE Object);

    /**
     * Ouvre une session RPC vers un serveur d'événements distant.
     *
     * <p>Exemple d'utilisation avec Kerberos intégré :
     * <pre>
     *   EvtRpcLogin login = new EvtRpcLogin();
     *   login.server = new WString("WEC01");
     *   login.flags  = EVT_RPC_LOGIN_AUTH_DEFAULT;
     *   login.write();
     *   HANDLE session = api.EvtOpenSession(EVT_LOGIN_CLASS_RPC_LOGIN, login.getPointer(), 0, 0);
     * </pre>
     *
     * @param LoginClass doit être {@link #EVT_LOGIN_CLASS_RPC_LOGIN} (1)
     * @param Login      pointeur sur une structure {@link EvtRpcLogin} écrite en mémoire native
     * @param Timeout    doit être 0 (réservé)
     * @param Flags      doit être 0 (réservé)
     * @return handle de session (à fermer avec {@link #EvtClose}), null si erreur
     */
    HANDLE EvtOpenSession(int LoginClass, Pointer Login, int Timeout, int Flags);
}

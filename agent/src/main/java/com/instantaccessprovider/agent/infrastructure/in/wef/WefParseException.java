package com.instantaccessprovider.agent.infrastructure.in.wef;

/** Erreur de parsing d'un événement Windows Event Log XML. */
class WefParseException extends Exception {
    WefParseException(String message, Throwable cause) {
        super(message, cause);
    }
}

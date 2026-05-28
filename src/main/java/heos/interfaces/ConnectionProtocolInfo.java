package heos.interfaces;

/**
 * Stores the protocol version advertised by the client during the handshake.
 */
public interface ConnectionProtocolInfo {
    void heos$setClientProtocolVersion(int protocolVersion);

    int heos$getClientProtocolVersion();

    void heos$setDebugPlayerName(String playerName);

    String heos$getDebugPlayerName();

    Object heos$getChannel();
}

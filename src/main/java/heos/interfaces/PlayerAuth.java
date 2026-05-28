package heos.interfaces;

import heos.storage.PlayerData;
import net.minecraft.SharedConstants;
import net.minecraft.network.Connection;

/**
 * Player authentication extension interface
 */
public interface PlayerAuth {
    
    /**
     * Sets the authentication status of the player
     */
    void heos$setAuthenticated(boolean authenticated);
    
    /**
     * Checks whether player is authenticated
     */
    boolean heos$isAuthenticated();
    
    /**
     * Checks whether player can skip authentication (premium player)
     */
    boolean heos$canSkipAuth();
    
    /**
     * Sets whether player can skip authentication
     */
    void heos$setCanSkipAuth(boolean canSkip);
    
    /**
     * Whether the player is using a Mojang account
     */
    boolean heos$isUsingMojangAccount();
    
    /**
     * Sets whether player is using Mojang account
     */
    void heos$setUsingMojangAccount(boolean usingMojang);
    
    /**
     * Gets the player's IP address
     */
    String heos$getIpAddress();
    
    /**
     * Sets the player's IP address from connection
     */
    void heos$setIpAddress(Connection connection);

    void heos$setConnection(Connection connection);

    Connection heos$getConnection();
    
    /**
     * Sets the player's IP address directly
     */
    void heos$setIpAddress(String ipAddress);

    /**
     * Gets the protocol version advertised by the client during handshake.
     */
    int heos$getClientProtocolVersion();

    /**
     * Sets the protocol version advertised by the client during handshake.
     */
    void heos$setClientProtocolVersion(int protocolVersion);

    default boolean heos$isSameProtocol() {
        return heos$getClientProtocolVersion() == SharedConstants.getProtocolVersion();
    }
    
    /**
     * Gets player data
     */
    PlayerData heos$getPlayerData();
    
    /**
     * Sets player data
     */
    void heos$setPlayerData(PlayerData data);
    
    /**
     * Sends authentication message to player
     */
    void heos$sendAuthMessage();

    /**
     * Called after the player has fully authenticated.
     */
    void heos$onAuthenticated();

    /**
     * Starts or refreshes the TPS display for this player.
     */
    void heos$startTpsDisplay();

    /**
     * Stops the TPS display for this player.
     */
    void heos$stopTpsDisplay();
}

package heos.folia.utils;

import org.bukkit.entity.Player;

import java.net.InetSocketAddress;

public final class FoliaPlayerAccess {
    private FoliaPlayerAccess() {
    }

    public static String ip(Player player) {
        InetSocketAddress address = player.getAddress();
        return address == null || address.getAddress() == null ? "" : address.getAddress().getHostAddress();
    }
}

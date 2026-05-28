package heos.folia.utils;

import org.bukkit.entity.Player;
import net.kyori.adventure.text.Component;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

public final class FoliaDisconnects {
    private FoliaDisconnects() {
    }

    public static void disconnect(Player player, String message, String internalReason) {
        if (player == null) {
            return;
        }
        try {
            Object component = literalComponent(message);
            Object internalComponent = literalComponent(internalReason);
            Object packet = disconnectPacket(component);
            Object listener = playerConnection(player);
            sendPacket(listener, packet);
            listener.getClass().getMethod("disconnect", componentClass()).invoke(listener, internalComponent);
        } catch (ReflectiveOperationException | RuntimeException e) {
            player.kick(Component.text(message == null ? "" : message));
        }
    }

    private static Object literalComponent(String message) throws ReflectiveOperationException {
        return componentClass().getMethod("literal", String.class).invoke(null, message);
    }

    private static Class<?> componentClass() throws ClassNotFoundException {
        return Class.forName("net.minecraft.network.chat.Component");
    }

    private static Object disconnectPacket(Object component) throws ReflectiveOperationException {
        Class<?> packetClass = Class.forName("net.minecraft.network.protocol.game.ClientboundDisconnectPacket");
        Constructor<?> constructor = packetClass.getConstructor(componentClass());
        return constructor.newInstance(component);
    }

    private static Object playerConnection(Player player) throws ReflectiveOperationException {
        Object handle = player.getClass().getMethod("getHandle").invoke(player);
        Field connectionField = handle.getClass().getField("connection");
        return connectionField.get(handle);
    }

    private static void sendPacket(Object listener, Object packet) throws ReflectiveOperationException {
        for (Method method : listener.getClass().getMethods()) {
            if (!method.getName().equals("send") || method.getParameterCount() != 1) {
                continue;
            }
            Class<?> parameterType = method.getParameterTypes()[0];
            if (parameterType.isAssignableFrom(packet.getClass())) {
                method.invoke(listener, packet);
                return;
            }
        }
        throw new NoSuchMethodException("send(Packet)");
    }
}

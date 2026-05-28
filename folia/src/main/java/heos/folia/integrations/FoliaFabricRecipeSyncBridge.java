package heos.folia.integrations;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

/**
 * Sends the Fabric recipe sync payload used by recipe viewers on Minecraft 1.21.2+.
 */
final class FoliaFabricRecipeSyncBridge {
    private static final String PAYLOAD_NAMESPACE = "fabric";
    private static final String PAYLOAD_PATH = "recipe_sync";
    static final String PAYLOAD_CHANNEL = PAYLOAD_NAMESPACE + ":" + PAYLOAD_PATH;

    private final Plugin plugin;

    FoliaFabricRecipeSyncBridge(Plugin plugin) {
        this.plugin = plugin;
    }

    void send(Player player) {
        if (!player.isOnline()) {
            return;
        }

        try {
            Object handle = player.getClass().getMethod("getHandle").invoke(player);
            ByteBuf bytes = encodePayload(handle);
            sendPayload(player, bytes);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING,
                    "Failed to send Fabric recipe sync payload to " + player.getName(), exception);
        }
    }

    private ByteBuf encodePayload(Object playerHandle) throws ReflectiveOperationException {
        ByteBuf rawBytes = Unpooled.buffer();
        Object buffer = registryFriendlyBuffer(rawBytes, registryAccess(playerHandle));
        Object serializerRegistry = recipeSerializerRegistry();
        Map<Object, List<Object>> recipesBySerializer = recipesBySerializer(playerHandle, serializerRegistry);

        writeVarInt(buffer, recipesBySerializer.size());
        for (Map.Entry<Object, List<Object>> entry : recipesBySerializer.entrySet()) {
            writeIdentifier(buffer, registryKey(serializerRegistry, entry.getKey()));
            writeVarInt(buffer, entry.getValue().size());
            Object streamCodec = streamCodec(entry.getKey());
            for (Object recipeHolder : entry.getValue()) {
                writeResourceKey(buffer, recipeKey(recipeHolder));
                encode(streamCodec, buffer, recipeValue(recipeHolder));
            }
        }
        return rawBytes;
    }

    private Map<Object, List<Object>> recipesBySerializer(
            Object playerHandle,
            Object serializerRegistry
    ) throws ReflectiveOperationException {
        Map<Object, List<Object>> grouped = new LinkedHashMap<>();
        for (Object recipeHolder : recipeHolders(playerHandle)) {
            Object recipe = recipeValue(recipeHolder);
            Object serializer = recipeSerializer(recipe);
            if (!isVanillaSerializer(serializerRegistry, serializer)) {
                continue;
            }
            grouped.computeIfAbsent(serializer, ignored -> new ArrayList<>()).add(recipeHolder);
        }
        return grouped;
    }

    private boolean isVanillaSerializer(Object serializerRegistry, Object serializer) throws ReflectiveOperationException {
        Object key = registryKey(serializerRegistry, serializer);
        Method getNamespace = findMethod(key.getClass(), "getNamespace", 0);
        if (getNamespace != null) {
            return "minecraft".equals(getNamespace.invoke(key));
        }
        return String.valueOf(key).startsWith("minecraft:");
    }

    private Collection<?> recipeHolders(Object playerHandle) throws ReflectiveOperationException {
        Object level = invokeNoArgsNamed(playerHandle, "level");
        Object recipeManager = invokeNoArgsNamed(level, "recipeAccess");
        Method getRecipes = recipeManager.getClass().getMethod("getRecipes");
        Object recipes = getRecipes.invoke(recipeManager);
        if (recipes instanceof Collection<?> holders && containsNmsRecipeHolders(holders)) {
            return holders;
        }
        throw new NoSuchFieldException("ServerLevel.recipeAccess().getRecipes()");
    }

    private boolean containsNmsRecipeHolders(Collection<?> values) throws ClassNotFoundException {
        Object first = values.stream().findFirst().orElse(null);
        return first == null || Class.forName("net.minecraft.world.item.crafting.RecipeHolder").isInstance(first);
    }

    private Object minecraftServer() throws ReflectiveOperationException {
        return plugin.getServer().getClass().getMethod("getServer").invoke(plugin.getServer());
    }

    private Object recipeValue(Object recipeHolder) throws ReflectiveOperationException {
        Object recipe = invokeNoArgsNamed(recipeHolder, "value");
        if (!Class.forName("net.minecraft.world.item.crafting.Recipe").isInstance(recipe)) {
            throw new NoSuchMethodException(recipeHolder.getClass().getName() + ".value() -> Recipe");
        }
        return recipe;
    }

    private Object recipeKey(Object recipeHolder) throws ReflectiveOperationException {
        return invokeNoArgsNamed(recipeHolder, "id");
    }

    private Object recipeSerializer(Object recipe) throws ReflectiveOperationException {
        Object serializer = invokeNoArgsNamed(recipe, "getSerializer");
        if (!Class.forName("net.minecraft.world.item.crafting.RecipeSerializer").isInstance(serializer)) {
            throw new NoSuchMethodException(recipe.getClass().getName() + ".getSerializer() -> RecipeSerializer");
        }
        return serializer;
    }

    private Object recipeSerializerRegistry() throws ReflectiveOperationException {
        Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
        Field field = registries.getField("RECIPE_SERIALIZER");
        return field.get(null);
    }

    private Object registryKey(Object registry, Object value) throws ReflectiveOperationException {
        Method getKey = findMethod(registry.getClass(), "getKey", 1);
        if (getKey != null) {
            return getKey.invoke(registry, value);
        }
        for (Method method : registry.getClass().getMethods()) {
            if (method.getParameterCount() == 1
                    && (method.getReturnType().getName().endsWith("ResourceLocation")
                    || method.getReturnType().getName().endsWith("Identifier"))) {
                return method.invoke(registry, value);
            }
        }
        throw new NoSuchMethodException("Registry.getKey");
    }

    private Object streamCodec(Object serializer) throws ReflectiveOperationException {
        return invokeNoArgsNamed(serializer, "streamCodec");
    }

    private void encode(Object streamCodec, Object buffer, Object value) throws ReflectiveOperationException {
        Method encode = Class.forName("net.minecraft.network.codec.StreamCodec")
                .getMethod("encode", Object.class, Object.class);
        encode.invoke(streamCodec, buffer, value);
    }

    private Object registryFriendlyBuffer(ByteBuf bytes, Object registryAccess) throws ReflectiveOperationException {
        Class<?> bufferClass = Class.forName("net.minecraft.network.RegistryFriendlyByteBuf");
        for (Constructor<?> constructor : bufferClass.getConstructors()) {
            Class<?>[] parameters = constructor.getParameterTypes();
            if (parameters.length == 2
                    && ByteBuf.class.isAssignableFrom(parameters[0])
                    && parameters[1].isInstance(registryAccess)) {
                return constructor.newInstance(bytes, registryAccess);
            }
        }
        throw new NoSuchMethodException("RegistryFriendlyByteBuf(ByteBuf, RegistryAccess)");
    }

    private Object registryAccess(Object playerHandle) throws ReflectiveOperationException {
        Object registryAccess = tryInvokeNoArgsNamed(playerHandle, "registryAccess");
        if (registryAccess != null) {
            return registryAccess;
        }
        return invokeNoArgsNamed(minecraftServer(), "registryAccess");
    }

    private void writeIdentifier(Object buffer, Object value) throws ReflectiveOperationException {
        Method method = findMethod(buffer.getClass(), "writeIdentifier", 1);
        if (method != null) {
            method.invoke(buffer, value);
            return;
        }

        method = findMethod(buffer.getClass(), "writeResourceLocation", 1);
        if (method != null) {
            method.invoke(buffer, value);
            return;
        }

        method = findMethod(buffer.getClass(), "writeUtf", 1);
        if (method == null) {
            throw new NoSuchMethodException("RegistryFriendlyByteBuf.writeIdentifier");
        }
        method.invoke(buffer, String.valueOf(value));
    }

    private void writeVarInt(Object buffer, int value) throws ReflectiveOperationException {
        Method method = findMethod(buffer.getClass(), "writeVarInt", 1);
        if (method == null) {
            throw new NoSuchMethodException("RegistryFriendlyByteBuf.writeVarInt");
        }
        method.invoke(buffer, value);
    }

    private void writeResourceKey(Object buffer, Object key) throws ReflectiveOperationException {
        Method method = findMethod(buffer.getClass(), "writeResourceKey", 1);
        if (method == null) {
            throw new NoSuchMethodException("RegistryFriendlyByteBuf.writeResourceKey");
        }
        method.invoke(buffer, key);
    }

    private void sendPayload(Player player, ByteBuf bytes) {
        byte[] data = new byte[bytes.readableBytes()];
        bytes.getBytes(bytes.readerIndex(), data);
        player.sendPluginMessage(plugin, PAYLOAD_CHANNEL, data);
    }

    private Object invokeNoArgsNamed(Object target, String name) throws ReflectiveOperationException {
        Object value = tryInvokeNoArgsNamed(target, name);
        if (value != null) {
            return value;
        }
        throw new NoSuchMethodException(target.getClass().getName() + "." + name + "()");
    }

    private Object tryInvokeNoArgsNamed(Object target, String name) throws ReflectiveOperationException {
        Method method = findMethod(target.getClass(), name, 0);
        if (method == null || method.getReturnType() == Void.TYPE) {
            return null;
        }
        return method.invoke(target);
    }

    private Method findMethod(Class<?> type, String name, int parameterCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == parameterCount) {
                return method;
            }
        }
        return null;
    }
}

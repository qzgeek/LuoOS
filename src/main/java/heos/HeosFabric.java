package heos;

import heos.commands.BanCommands;
import heos.commands.ChangePasswordCommand;
import heos.commands.HeosAdminCommand;
import heos.commands.LoginCommand;
import heos.commands.RegisterCommand;
import heos.event.AuthEventHandler;
import heos.integrations.RecipeSyncFeature;
import heos.integrations.ViaVersionDetailsFeature;
import heos.utils.HeosLogger;
import heos.utils.LogFilterService;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.loader.api.FabricLoader;
//? if < 1.21.2 {
/*import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.item.ItemStack;
*///?}

/**
 * Fabric entrypoint for Heos.
 */
public class HeosFabric implements ModInitializer {
    private static final String[] STARTUP_BANNER = {
        "Initializing Heos authentication system...",
        "Heos authentication system initialized successfully!"
    };

    @Override
    public void onInitialize() {
        Heos.gameDirectory = FabricLoader.getInstance().getGameDir();

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            HeosLogger.info("Initialized Heos client compatibility features");
            return;
        }

        RecipeSyncFeature.initialize();
        ViaVersionDetailsFeature.initialize();
        LogFilterService.installConfiguredFilters();
        logStartupContext();
        installCommandCallbacks();
        installServerCallbacks();
        installInteractionGuards();
        HeosLogger.info(STARTUP_BANNER[1]);
    }

    private void logStartupContext() {
        HeosLogger.info(STARTUP_BANNER[0]);
        HeosLogger.info("Game directory: " + Heos.gameDirectory);
    }

    private void installCommandCallbacks() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            LoginCommand.register(dispatcher);
            RegisterCommand.register(dispatcher);
            ChangePasswordCommand.register(dispatcher);
            HeosAdminCommand.register(dispatcher);
            BanCommands.register(dispatcher);
            HeosLogger.info("Registered Heos commands");
        });
    }

    private void installServerCallbacks() {
        ServerLifecycleEvents.SERVER_STARTED.register(Heos::onStartServer);
        ServerLifecycleEvents.SERVER_STOPPED.register(Heos::onStopServer);
    }

    private void installInteractionGuards() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> AuthEventHandler.onBreakBlock(player));
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> AuthEventHandler.onUseBlock(player));
        registerUseItemGuard();
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> AuthEventHandler.onAttackEntity(player));
        UseEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> AuthEventHandler.onUseEntity(player));
    }

    private void registerUseItemGuard() {
        //? if >= 1.21.2 {
        UseItemCallback.EVENT.register((player, world, hand) -> AuthEventHandler.onUseItem(player));
        //?} else {
        /*UseItemCallback.EVENT.register((player, world, hand) -> {
            InteractionResult result = AuthEventHandler.onUseItem(player);
            if (result == InteractionResult.FAIL) {
                return InteractionResultHolder.fail(ItemStack.EMPTY);
            }
            if (result == InteractionResult.SUCCESS) {
                return InteractionResultHolder.success(ItemStack.EMPTY);
            }
            return InteractionResultHolder.pass(ItemStack.EMPTY);
        });
        *///?}
    }
}

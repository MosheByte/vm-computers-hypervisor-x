package net.hypervisorx;

import com.mojang.brigadier.CommandDispatcher;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.registry.HvxBlocks;
import net.hypervisorx.registry.HvxComponents;
import net.hypervisorx.registry.HvxEntities;
import net.hypervisorx.registry.HvxItems;
import net.hypervisorx.registry.HvxMenus;
import net.hypervisorx.registry.HvxSounds;
import net.hypervisorx.registry.HvxTabs;
import net.hypervisorx.server.HvxCommands;
import net.hypervisorx.vm.VmManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared constants and the loader-independent bootstrap of VM Computers: HyperVisor X.
 * Loader entrypoints call {@link #init()} once and forward lifecycle events to the hooks below.
 */
public final class HyperVisorX {
    public static final String MOD_ID = "hypervisorx";
    public static final String MOD_NAME = "VM Computers: HyperVisor X";
    public static final String VERSION = "1.0.0";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_NAME);

    private HyperVisorX() {
    }

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    /** Registers all our game objects. Order matters, Fabric registers right away. */
    public static void init() {
        HvxComponents.init();
        HvxSounds.init();
        HvxBlocks.init();
        HvxItems.init();
        HvxBlockEntities.init();
        HvxMenus.init();
        HvxEntities.init();
        HvxTabs.init();
        LOG.info("{} {} initialised", MOD_NAME, VERSION);
    }

    public static void onServerStarted(MinecraftServer server) {
        HvxConfig.get();
        VmManager.onServerStarted(server);
    }

    public static void onServerStopping(MinecraftServer server) {
        VmManager.onServerStopping(server);
    }

    public static void onServerTick(MinecraftServer server) {
        VmManager m = VmManager.get();
        if (m != null) m.tick();
    }

    public static void onPlayerLeft(ServerPlayer player) {
        VmManager m = VmManager.get();
        if (m != null) m.onPlayerLeft(player);
    }

    public static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher) {
        HvxCommands.register(dispatcher);
    }
}

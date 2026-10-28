package net.hypervisorx.client;

import net.hypervisorx.ClientBridge;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.block.PeripheralBlock;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.block.entity.HostBlockEntity;
import net.hypervisorx.client.audio.VmAudioPlayer;
import net.hypervisorx.client.render.ComputerRenderer;
import net.hypervisorx.client.render.HvxRenderTypes;
import net.hypervisorx.client.render.LaptopRenderer;
import net.hypervisorx.client.render.MonitorRenderer;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.client.render.SupplyPodRenderer;
import net.hypervisorx.client.render.VideoWallRenderer;
import net.hypervisorx.client.screen.LaptopHud;
import net.hypervisorx.client.screen.LibraryScreen;
import net.hypervisorx.client.screen.SettingsScreen;
import net.hypervisorx.client.screen.StoreScreen;
import net.hypervisorx.config.HvxClientConfig;
import net.hypervisorx.item.LaptopItem;
import net.hypervisorx.network.C2S;
import net.hypervisorx.platform.Services;
import net.hypervisorx.registry.HvxBlockEntities;
import net.hypervisorx.registry.HvxBlocks;
import net.hypervisorx.registry.HvxEntities;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.List;
import java.util.function.Function;

/** Client bootstrap plus the per-frame/per-tick hooks. Nothing loader-specific in here. */
public final class HvxClient {
    public static final ResourceLocation SCREEN_SHADER = HyperVisorX.id("rendertype_hvx_screen");

    public interface RendererSink {
        <T extends BlockEntity> void blockEntity(BlockEntityType<? extends T> type, BlockEntityRendererProvider<T> provider);

        <T extends Entity> void entity(EntityType<? extends T> type, EntityRendererProvider<T> provider);
    }

    /** Extra block models the renderers draw themselves (the tower's insides). Each loader registers these. */
    public static final List<ResourceLocation> PART_MODELS = List.of(
            HyperVisorX.id("block/part/gpu_gt"), HyperVisorX.id("block/part/gpu_gx"), HyperVisorX.id("block/part/gpu_titan"),
            HyperVisorX.id("block/part/ram"), HyperVisorX.id("block/part/cooler"), HyperVisorX.id("block/part/card"),
            HyperVisorX.id("block/part/drive"));

    /** Baked model lookup for {@link #PART_MODELS}. The loader plugs the real one in. */
    public static Function<ResourceLocation, net.minecraft.client.resources.model.BakedModel> partModels = id -> null;

    private HvxClient() {
    }

    public static void init() {
        HvxClientConfig.get();
        ClientBridge.install(new ClientBridge.Impl() {
            @Override
            public void openStore() {
                Minecraft.getInstance().setScreen(new StoreScreen());
            }

            @Override
            public void openSettings() {
                Minecraft.getInstance().setScreen(new SettingsScreen(null));
            }

            @Override
            public void openLibrary() {
                Minecraft.getInstance().setScreen(new LibraryScreen(null));
            }
        });
        LaptopItem.clientBattery = ClientVmCache::battery;
    }

    public static void registerRenderers(RendererSink sink) {
        sink.blockEntity(HvxBlockEntities.MONITOR.get(), MonitorRenderer::new);
        sink.blockEntity(HvxBlockEntities.VIDEO_WALL.get(), VideoWallRenderer::new);
        sink.blockEntity(HvxBlockEntities.LAPTOP.get(), LaptopRenderer::new);
        sink.blockEntity(HvxBlockEntities.COMPUTER.get(), ComputerRenderer::new);
        sink.entity(HvxEntities.SUPPLY_POD.get(), SupplyPodRenderer::new);
    }

    /** Blocks with tempered glass. Translucent layer on Fabric, NeoForge just reads render_type from the model. */
    public static List<Block> translucentBlocks() {
        return List.of();
    }

    public static List<Block> cutoutBlocks() {
        return List.of(HvxBlocks.GAMING_TOWER.get(), HvxBlocks.SERVER_RACK.get(), HvxBlocks.DUAL_MONITOR_ARM.get(), HvxBlocks.TRIPLE_MONITOR_ARM.get(),
                HvxBlocks.SUPPLY_POD.get());
    }

    public static void onShaderLoaded(ShaderInstance shader) {
        HvxRenderTypes.setScreenShader(shader);
    }

    /** Dev-only smoke test hook. AutoTest doesn't ship in release jars. */
    private static final Runnable DEV_HOOK = devHook();

    private static Runnable devHook() {
        if (!Boolean.getBoolean("hvx.autotest") && !Boolean.getBoolean("hvx.demoworld") && !Boolean.getBoolean("hvx.vmwaretest")
                && System.getProperty("hvx.backendtest", "").isBlank()) return () -> { };
        try {
            java.lang.reflect.Method tick = Class.forName("net.hypervisorx.client.AutoTest").getMethod("tick");
            return () -> {
                try {
                    tick.invoke(null);
                } catch (ReflectiveOperationException e) {
                    HyperVisorX.LOG.error("Autotest hook failed", e);
                }
            };
        } catch (ReflectiveOperationException e) {
            return () -> { };
        }
    }

    /** Runs at the end of every client tick. */
    public static void onClientTick() {
        DEV_HOOK.run();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        ScreenTracker.tick();
        while (HvxKeys.FOCUS.consumeClick()) focusUnderCrosshair(mc);
        while (HvxKeys.LAPTOP_POWER.consumeClick()) {
            boolean main = mc.player.getMainHandItem().getItem() instanceof LaptopItem;
            if (main || mc.player.getOffhandItem().getItem() instanceof LaptopItem) {
                Services.PLATFORM.sendToServer(new C2S.Laptop(main, C2S.Laptop.POWER));
            }
        }
        while (HvxKeys.TOGGLE_PIP.consumeClick()) {
            HvxClientConfig cfg = HvxClientConfig.get();
            cfg.laptopPictureInPicture = !cfg.laptopPictureInPicture;
            cfg.save();
            mc.player.displayClientMessage(Component.translatable(cfg.laptopPictureInPicture ? "hypervisorx.msg.pip_on" : "hypervisorx.msg.pip_off"), true);
        }
        while (HvxKeys.SETTINGS.consumeClick()) mc.setScreen(new SettingsScreen(null));
    }

    private static void focusUnderCrosshair(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bhr && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos pos = bhr.getBlockPos();
            BlockEntity be = mc.level.getBlockEntity(pos);
            boolean keyboard = mc.level.getBlockState(pos).getBlock() instanceof PeripheralBlock;
            if (be instanceof DisplayBlockEntity || be instanceof HostBlockEntity || keyboard) {
                if (keyboard) {
                    BlockPos display = PeripheralBlock.nearestDisplay(mc.level, pos, 4);
                    if (display != null) pos = display;
                }
                Services.PLATFORM.sendToServer(new C2S.RequestFocus(pos));
                return;
            }
        }
        InteractionHand hand = mc.player.getMainHandItem().getItem() instanceof LaptopItem ? InteractionHand.MAIN_HAND
                : mc.player.getOffhandItem().getItem() instanceof LaptopItem ? InteractionHand.OFF_HAND : null;
        if (hand != null) Services.PLATFORM.sendToServer(new C2S.Laptop(hand == InteractionHand.MAIN_HAND, C2S.Laptop.OPEN));
    }

    /** Once a frame, before the level draws. */
    public static void onFrameStart() {
        ScreenHits.beginFrame();
        ScreenTextureManager.uploadAll();
        VmAudioPlayer.tick();
    }

    public static void onHud(GuiGraphics g, DeltaTracker delta) {
        LaptopHud.render(g, delta);
    }

    public static void onDisconnect() {
        Minecraft.getInstance().execute(() -> {
            ScreenTextureManager.clear();
            VmAudioPlayer.clear();
            ClientVmCache.clear();
            ScreenTracker.clear();
            ScreenHits.clear();
            DeskView.reset();
        });
    }
}

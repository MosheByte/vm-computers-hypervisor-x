package net.hypervisorx.client;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.client.render.ScreenTexture;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.client.screen.FocusScreen;
import net.hypervisorx.client.screen.StoreScreen;
import net.hypervisorx.server.DemoBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Developer smoke test, enabled with {@code -Dhvx.autotest=true}: creates a flat creative world,
 * builds the /hvx demo showroom, waits for the embedded Linux VM to stream, captures screenshots
 * of the world, the focus screen and the store, writes {@code hvx-autotest.txt} and quits.
 */
public final class AutoTest {
    public static final boolean ENABLED = Boolean.getBoolean("hvx.autotest");
    private static int stage;
    private static int ticks;
    private static UUID demoVm;
    private static final List<String> report = new ArrayList<>();

    private AutoTest() {
    }

    public static void tick() {
        if (!ENABLED) return;
        Minecraft mc = Minecraft.getInstance();
        ticks++;
        try {
            switch (stage) {
                case 0 -> {
                    if (mc.screen instanceof TitleScreen && ticks > 40) {
                        GameRules rules = new GameRules();
                        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                        mc.createWorldOpenFlows().createFreshLevel("hvx-autotest-" + System.currentTimeMillis(),
                                new LevelSettings("HVX Autotest", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT),
                                new WorldOptions(1234L, false, false),
                                access -> access.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                                mc.screen);
                        stage = 1;
                        ticks = 0;
                        log("world creation started");
                    }
                }
                case 1 -> {
                    if (mc.level != null && mc.player != null && mc.getSingleplayerServer() != null && ticks > 100) {
                        mc.player.setXRot(4f);
                        mc.player.setYRot(180f);
                        var server = mc.getSingleplayerServer();
                        UUID self = mc.player.getUUID();
                        server.execute(() -> {
                            ServerPlayer sp = server.getPlayerList().getPlayer(self);
                            if (sp == null) return;
                            sp.teleportTo(sp.getX(), sp.getY(), sp.getZ());
                            sp.setYRot(180f);
                            sp.setXRot(4f);
                            server.overworld().setDayTime(6000);
                            log("demo: " + DemoBuilder.build(sp).getString());
                        });
                        stage = 2;
                        ticks = 0;
                    }
                }
                case 2 -> {
                    if (ticks == 2) mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    if (ticks == 40) teleport(mc, 0.5, 1.2, 3.0);
                    if (ticks == 200) typeInVm(mc, "clear; echo; echo '  HyperVisor X :: real Linux on a RISC-V MicroVM inside Minecraft'; echo; uname -a; echo; free; echo; ls /; echo; top -b -n 1 | head -12\r");
                    if (ticks > 300) {
                        demoVm = findDemoVm(mc);
                        ScreenTexture tex = ScreenTextureManager.get(demoVm);
                        log("demo vm " + demoVm + " status=" + (ClientVmCache.get(demoVm) == null ? "none" : ClientVmCache.get(demoVm).state())
                                + " texture=" + (tex == null ? "none" : tex.width() + "x" + tex.height() + " content=" + tex.hasContent()));
                        shot(mc, "hvx-world.png");
                        stage = 3;
                        ticks = 0;
                    }
                }
                case 3 -> {
                    if (ticks == 2) teleport(mc, -1.5, -0.6, -2.4);
                    if (ticks == 60) shot(mc, "hvx-closeup.png");
                    if (ticks == 70 && demoVm != null) mc.setScreen(new FocusScreen(demoVm, false));
                    if (ticks == 190) {
                        shot(mc, "hvx-focus.png");
                        mc.setScreen(new StoreScreen());
                    }
                    if (ticks == 230) {
                        shot(mc, "hvx-store.png");
                        mc.setScreen(new net.hypervisorx.client.screen.LibraryScreen(null));
                    }
                    if (ticks == 300) {
                        shot(mc, "hvx-library.png");
                        mc.setScreen(null);
                        openPcMenu(mc);
                    }
                    if (ticks == 360) {
                        shot(mc, "hvx-computer.png");
                        mc.setScreen(null);
                        stage = 4;
                        ticks = 0;
                    }
                }
                case 4 -> {
                    if (ticks == 20) {
                        writeReport(mc);
                        mc.stop();
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            log("FAILED at stage " + stage + ": " + t);
            HyperVisorX.LOG.error("Autotest failure", t);
            writeReport(mc);
            mc.stop();
        }
    }

    private static void teleport(Minecraft mc, double dx, double dy, double dz) {
        var server = mc.getSingleplayerServer();
        UUID self = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(self);
            if (sp != null) sp.teleportTo(sp.getX() + dx, sp.getY() + dy, sp.getZ() + dz);
        });
    }

    private static void typeInVm(Minecraft mc, String text) {
        UUID vm = findDemoVm(mc);
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var m = net.hypervisorx.vm.VmManager.get();
            var session = m == null || vm == null ? null : m.session(vm);
            if (session == null || session.instance() == null) return;
            text.codePoints().forEach(cp -> {
                if (cp == '\r') session.instance().key(net.hypervisorx.vm.input.KeyMaps.KEY_ENTER, true);
                else session.instance().text(cp);
            });
        });
    }

    private static void openPcMenu(Minecraft mc) {
        var server = mc.getSingleplayerServer();
        UUID self = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(self);
            if (sp != null && DemoBuilder.lastPc != null) {
                sp.teleportTo(DemoBuilder.lastPc.getX() + 0.5, DemoBuilder.lastPc.getY(), DemoBuilder.lastPc.getZ() + 2.5);
                net.hypervisorx.server.HostService.openHostMenu(sp, DemoBuilder.lastPc);
            }
        });
    }

    private static UUID findDemoVm(Minecraft mc) {
        BlockPos center = mc.player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 6, 16))) {
            if (mc.level.getBlockEntity(p) instanceof DisplayBlockEntity d && d.displayedVm() != null) return d.displayedVm();
        }
        return null;
    }

    private static void shot(Minecraft mc, String name) {
        Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), c -> log("screenshot " + name + ": " + c.getString()));
    }

    private static synchronized void log(String s) {
        report.add(s);
        HyperVisorX.LOG.info("[autotest] {}", s);
    }

    private static synchronized void writeReport(Minecraft mc) {
        try {
            Files.write(Path.of(mc.gameDirectory.getPath(), "hvx-autotest.txt"), report);
        } catch (IOException e) {
            HyperVisorX.LOG.error("Could not write autotest report", e);
        }
    }
}

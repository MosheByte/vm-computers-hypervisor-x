package net.hypervisorx.client;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.block.entity.ComputerBlockEntity;
import net.hypervisorx.block.entity.DisplayBlockEntity;
import net.hypervisorx.client.render.ScreenTexture;
import net.hypervisorx.client.render.ScreenTextureManager;
import net.hypervisorx.client.screen.FocusScreen;
import net.hypervisorx.client.screen.StoreScreen;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.images.ImageLibrary;
import net.hypervisorx.server.DemoBuilder;
import net.hypervisorx.server.HostService;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dev smoke test, turn it on with {@code -Dhvx.autotest=true}. Makes a flat creative world, builds
 * the /hvx demo showroom, waits for the embedded Linux VM to stream, then screenshots the world,
 * close-ups, the focus screen, store, library and PC screen, writes {@code hvx-autotest.txt} and quits.
 * <p>
 * {@code -Dhvx.demoworld=true} only does the setup half and leaves you standing in the showroom.
 * {@code -Dhvx.vmwaretest=true} also downloads Tiny Core Linux, boots it on VMware in the gaming
 * tower, puts it on the curved ultrawide, takes screenshots and leaves the game open.
 */
public final class AutoTest {
    public static final boolean DEMO_WORLD = Boolean.getBoolean("hvx.demoworld");
    public static final boolean VMWARE = Boolean.getBoolean("hvx.vmwaretest");
    public static final boolean ENABLED = Boolean.getBoolean("hvx.autotest") || DEMO_WORLD || VMWARE;
    private static int stage;
    private static int ticks;
    private static UUID demoVm;
    private static UUID vmwareVm;
    private static Vec3 origin;
    private static int desktopAt = -1;
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
                    if (ticks == 1) mc.options.pauseOnLostFocus = false;
                    if (mc.screen instanceof TitleScreen && ticks > 40) {
                        GameRules rules = new GameRules();
                        rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
                        rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
                        boolean keep = DEMO_WORLD || VMWARE;
                        mc.createWorldOpenFlows().createFreshLevel((keep ? "hvx-demo-" : "hvx-autotest-") + System.currentTimeMillis(),
                                new LevelSettings(keep ? "HyperVisor X Demo" : "HVX Autotest", GameType.CREATIVE, false, Difficulty.PEACEFUL, true, rules,
                                        WorldDataConfiguration.DEFAULT),
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
                            origin = sp.position();
                            server.overworld().setDayTime(6000);
                            log("demo: " + DemoBuilder.build(sp).getString());
                        });
                        stage = 2;
                        ticks = 0;
                    }
                }
                case 2 -> {
                    if (ticks == 2) mc.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
                    if (ticks == 40) teleport(mc, 0.5, 1.2, 3.0, 180f, 4f);
                    if (ticks == 200) typeInVm(mc, "clear; echo; echo '  HyperVisor X :: real Linux on a RISC-V MicroVM inside Minecraft'; echo; uname -a; echo; free; echo; ls /; echo; top -b -n 1 | head -12\r");
                    if (ticks > 300) {
                        demoVm = findDemoVm(mc);
                        ScreenTexture tex = ScreenTextureManager.get(demoVm);
                        log("demo vm " + demoVm + " status=" + (ClientVmCache.get(demoVm) == null ? "none" : ClientVmCache.get(demoVm).state())
                                + " texture=" + (tex == null ? "none" : tex.width() + "x" + tex.height() + " content=" + tex.hasContent()));
                        if (DEMO_WORLD) {
                            log("demo world ready");
                            stage = -1;
                            return;
                        }
                        shot(mc, "hvx-world.png");
                        stage = 3;
                        ticks = 0;
                    }
                }
                case 3 -> {
                    if (ticks == 2) mc.setScreen(null);
                    if (ticks == 2) teleport(mc, -1.0, 0.6, 0.6, 180f, 18f);
                    if (ticks == 50) shot(mc, "hvx-closeup.png");
                    if (ticks == 52) mc.setScreen(null);
                    if (ticks == 52) teleport(mc, -3.3, 0.2, -2.1, -128f, 22f);
                    if (ticks == 100) shot(mc, "hvx-tower.png");
                    if (ticks == 102) mc.setScreen(null);
                    if (ticks == 102) teleport(mc, 3.6, 0.6, -2.6, -142f, 20f);
                    if (ticks == 150) shot(mc, "hvx-rack.png");
                    if (ticks == 152) mc.setScreen(null);
                    if (ticks == 152) teleport(mc, -4.0, 0.2, -1.9, 180f, 32f);
                    if (ticks == 200) shot(mc, "hvx-desk.png");
                    if (ticks == 202) teleport(mc, -1.0, 0.6, 0.6, 180f, 18f);
                    if (ticks == 210 && demoVm != null) mc.setScreen(new FocusScreen(demoVm, false));
                    if (ticks == 330) {
                        shot(mc, "hvx-focus.png");
                        mc.setScreen(new StoreScreen());
                    }
                    if (ticks == 370) {
                        shot(mc, "hvx-store.png");
                        mc.setScreen(new net.hypervisorx.client.screen.LibraryScreen(null));
                    }
                    if (ticks == 440) {
                        shot(mc, "hvx-library.png");
                        mc.setScreen(null);
                        openPcMenu(mc, DemoBuilder.lastPc);
                    }
                    if (ticks == 500) {
                        shot(mc, "hvx-computer.png");
                        mc.setScreen(null);
                        stage = VMWARE ? 10 : 4;
                        ticks = 0;
                    }
                }
                case 4 -> {
                    if (ticks == 20) {
                        writeReport(mc);
                        mc.stop();
                    }
                }
                // ------------------------------------------------------------ VMware end-to-end test
                case 10 -> {
                    if (ticks == 1) {
                        var server = mc.getSingleplayerServer();
                        server.execute(() -> {
                            ImageLibrary.Job job = ImageLibrary.download("tinycore", HvxConfig.get().backendContext());
                            log("tinycore download: " + job.state);
                        });
                    }
                    if (ticks % 20 == 0) {
                        String state = ImageLibrary.jobs().stream().filter(j -> j.id().equals("tinycore")).map(j -> j.state()).findFirst().orElse("?");
                        if (ticks % 200 == 0) log("tinycore: " + state);
                        if (state.startsWith("done")) {
                            log("tinycore ready: " + state);
                            stage = 11;
                            ticks = 0;
                        } else if (state.startsWith("failed") || ticks > 20 * 600) {
                            log("tinycore download FAILED: " + state);
                            stage = 19;
                            ticks = 0;
                        }
                    }
                }
                case 11 -> {
                    if (ticks == 1) {
                        var server = mc.getSingleplayerServer();
                        UUID self = mc.player.getUUID();
                        server.execute(() -> {
                            ServerPlayer sp = server.getPlayerList().getPlayer(self);
                            BlockPos pc = DemoBuilder.lastPc;
                            BlockPos tower = DemoBuilder.lastTower;
                            BlockPos curved = DemoBuilder.lastCurved;
                            if (sp == null || tower == null || !(sp.level().getBlockEntity(tower) instanceof ComputerBlockEntity gaming)) {
                                log("VMware: tower missing");
                                return;
                            }
                            VmRecord r = HostService.ensureRecord(sp, gaming, 0);
                            r.backend = "vmware";
                            r.guestOs = GuestOs.LINUX;
                            r.image = "TinyCore-16.iso";
                            r.name = "VMware: Tiny Core Linux";
                            r.access = VmRecord.Access.PUBLIC;
                            if (curved != null && sp.level().getBlockEntity(curved) instanceof DisplayBlockEntity d) d.link(tower, 0);
                            VmManager m = VmManager.get();
                            m.data().changed();
                            vmwareVm = r.id;
                            Component err = m.startUnchecked(r, gaming.builtSpec(), gaming);
                            log("VMware start: " + (err == null ? "ok" : err.getString()) + " vm=" + r.id + " pc=" + pc);
                        });
                    }
                    if (ticks == 40) teleport(mc, 0.6, 0.4, -1.6, 180f, 14f);
                    if (ticks > 60 && ticks % 40 == 0 && vmwareVm != null) {
                        var st = ClientVmCache.get(vmwareVm);
                        ScreenTexture tex = ScreenTextureManager.get(vmwareVm);
                        if (ticks % 200 == 0) {
                            log("VMware status=" + (st == null ? "none" : st.state() + " " + st.width() + "x" + st.height() + " '" + st.message() + "'")
                                    + " texture=" + (tex == null ? "none" : tex.width() + "x" + tex.height() + " content=" + tex.hasContent()));
                        }
                        if (st != null && st.state().equals("ERROR")) {
                            log("VMware ERROR: " + st.message());
                            stage = 19;
                            ticks = 0;
                        } else if (tex != null && tex.hasContent() && st.width() >= 800) {
                            // hit graphics mode (X11 is up), give the desktop time to load its dock
                            if (desktopAt < 0) {
                                desktopAt = ticks;
                                log("VMware guest switched to " + st.width() + "x" + st.height());
                            } else if (ticks - desktopAt > 20 * 45) {
                                stage = 12;
                                ticks = 0;
                            }
                        } else if (ticks > 20 * 240) {
                            log("VMware: no picture after 4 minutes");
                            stage = 19;
                            ticks = 0;
                        }
                    }
                }
                case 12 -> {
                    if (ticks == 1) shot(mc, "hvx-vmware-world.png");
                    if (ticks == 10) mc.setScreen(new FocusScreen(vmwareVm, false));
                    if (ticks == 120) shot(mc, "hvx-vmware-focus.png");
                    if (ticks == 130) {
                        mc.setScreen(null);
                        openPcMenu(mc, DemoBuilder.lastTower);
                    }
                    if (ticks == 190) {
                        shot(mc, "hvx-vmware-pc.png");
                        mc.setScreen(null);
                        log("VMware test PASSED");
                        stage = 19;
                        ticks = 0;
                    }
                }
                case 19 -> {
                    if (ticks == 5) {
                        writeReport(mc);
                        teleport(mc, 0.6, 0.4, -1.6, 180f, 14f);
                        stage = -1;
                    }
                }
                default -> {
                }
            }
        } catch (Throwable t) {
            log("FAILED at stage " + stage + ": " + t);
            HyperVisorX.LOG.error("Autotest failure", t);
            writeReport(mc);
            if (!VMWARE && !DEMO_WORLD) mc.stop();
            stage = -1;
        }
    }

    /** Teleports the player to {@code origin + offset} (the spot the showroom got built from). */
    private static void teleport(Minecraft mc, double dx, double dy, double dz, float yaw, float pitch) {
        var server = mc.getSingleplayerServer();
        UUID self = mc.player.getUUID();
        Vec3 o = origin;
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(self);
            if (sp == null || o == null) return;
            sp.teleportTo(sp.serverLevel(), o.x + dx, o.y + dy, o.z + dz, yaw, pitch);
        });
    }

    private static void typeInVm(Minecraft mc, String text) {
        UUID vm = findDemoVm(mc);
        var server = mc.getSingleplayerServer();
        server.execute(() -> {
            var m = VmManager.get();
            var session = m == null || vm == null ? null : m.session(vm);
            if (session == null || session.instance() == null) return;
            text.codePoints().forEach(cp -> {
                if (cp == '\r') session.instance().key(net.hypervisorx.vm.input.KeyMaps.KEY_ENTER, true);
                else session.instance().text(cp);
            });
        });
    }

    private static void openPcMenu(Minecraft mc, BlockPos pos) {
        var server = mc.getSingleplayerServer();
        UUID self = mc.player.getUUID();
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(self);
            if (sp != null && pos != null) HostService.openHostMenu(sp, pos);
        });
    }

    private static UUID findDemoVm(Minecraft mc) {
        BlockPos center = mc.player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 6, 16))) {
            if (mc.level.getBlockEntity(p) instanceof DisplayBlockEntity d && d.displayedVm() != null && !d.displayedVm().equals(vmwareVm)) {
                return d.displayedVm();
            }
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

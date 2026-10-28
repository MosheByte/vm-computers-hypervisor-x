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
    /** Which hypervisor the end-to-end test uses: -Dhvx.backendtest=vmware|virtualbox|qemu|hostcapture (-Dhvx.vmwaretest = vmware). */
    public static final String BACKEND = System.getProperty("hvx.backendtest", "").isBlank()
            ? (Boolean.getBoolean("hvx.vmwaretest") ? "vmware" : "") : System.getProperty("hvx.backendtest").trim();
    public static final boolean VMWARE = !BACKEND.isEmpty();
    public static final boolean ENABLED = Boolean.getBoolean("hvx.autotest") || DEMO_WORLD || VMWARE;
    private static final boolean CAPTURE = BACKEND.equals("hostcapture");
    /** -Dhvx.testimage=<absolute path> boots that image instead of Tiny Core, -Dhvx.testos=WINDOWS_11 etc. sets the guest type. */
    private static final String TEST_IMAGE = System.getProperty("hvx.testimage", "").trim();
    private static final GuestOs TEST_OS = GuestOs.parse(System.getProperty("hvx.testos", "LINUX"));
    /** Long guest installs (Windows): sit in focus mode and grab a progress shot every 2 minutes. */
    private static final boolean LONG_RUN = !TEST_IMAGE.isEmpty() && TEST_OS != GuestOs.LINUX;
    private static String testImageName = "TinyCore-16.iso";
    private static int progressShots;
    private static final String CAPTURE_TITLE = "HyperVisor X capture test";
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
                            log("demo: " + DemoBuilder.build(sp, !VMWARE).getString());
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
                    if (ticks == 202) standBeforeScreen(mc, demoVm);
                    if (ticks == 230 && demoVm != null) mc.setScreen(new FocusScreen(demoVm, false));
                    if (ticks == 320 && demoVm != null) {
                        double cx = mc.getWindow().getGuiScaledWidth() / 2.0, cy = mc.getWindow().getGuiScaledHeight() / 2.0;
                        ScreenHits.Hit hit = DeskView.pick(demoVm, cx, cy, mc.getWindow().getGuiScaledWidth(), mc.getWindow().getGuiScaledHeight());
                        log("desk view: seated=" + DeskView.active() + " centre hit=" + (hit == null ? "none"
                                : String.format(java.util.Locale.ROOT, "u=%.3f v=%.3f d=%.2f", hit.u(), hit.v(), hit.distance())));
                    }
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
                        openPcMenu(mc, DemoBuilder.lastTower);
                    }
                    if (ticks == 560) {
                        shot(mc, "hvx-computer-tower.png");
                        var server = mc.getSingleplayerServer();
                        UUID self = mc.player.getUUID();
                        server.execute(() -> {
                            ServerPlayer sp = server.getPlayerList().getPlayer(self);
                            if (sp == null) return;
                            for (var it : List.of(net.hypervisorx.registry.HvxItems.RAM_32, net.hypervisorx.registry.HvxItems.GPU_GT,
                                    net.hypervisorx.registry.HvxItems.NVME, net.hypervisorx.registry.HvxItems.HDD, net.hypervisorx.registry.HvxItems.CPU_X8)) {
                                sp.getInventory().add(new net.minecraft.world.item.ItemStack(it.get()));
                            }
                        });
                    }
                    // motherboard page: pull parts out, put them back in through the socket picker
                    if (mc.screen instanceof net.hypervisorx.client.screen.ComputerScreen cs && cs.board() != null && ticks > 560 && ticks < 800) {
                        var b = cs.board();
                        if (ticks == 570) b.testRemove(5);
                        if (ticks == 590) b.testInstall(5);
                        if (ticks == 598) shot(mc, "hvx-board-anim-gpu.png");
                        if (ticks == 620) b.testRemove(1);
                        if (ticks == 640) b.testInstall(1);
                        if (ticks == 646) shot(mc, "hvx-board-anim-ram.png");
                        if (ticks == 660) b.testRemove(0);
                        if (ticks == 680) b.testInstall(0);
                        if (ticks == 690) shot(mc, "hvx-board-anim-cpu.png");
                        if (ticks == 700) b.testView(-30f, 40f);
                        if (ticks == 712) shot(mc, "hvx-board-turned.png");
                        if (ticks == 716) b.testView(14f, 50f);
                        if (ticks == 720) {
                            shot(mc, "hvx-board-done.png");
                            StringBuilder sb = new StringBuilder("board slots:");
                            for (int i = 0; i < cs.getMenu().form().slotCount(); i++) sb.append(' ').append(i).append('=')
                                    .append(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(cs.getMenu().slots.get(i).getItem().getItem()).getPath());
                            log(sb.toString());
                            b.testOpen(6);
                        }
                        if (ticks == 730) shot(mc, "hvx-board-picker.png");
                        if (ticks == 740) {
                            b.close();
                            cs.showBoard(false);
                        }
                        if (ticks == 750) {
                            shot(mc, "hvx-computer-system.png");
                            cs.showBoard(true);
                        }
                    }
                    if (ticks == 800) {
                        mc.setScreen(null);
                        stage = VMWARE ? 10 : 5;
                        ticks = 0;
                    }
                }
                // more stills: retro corner, settings, file browser, then the same shots at night
                case 5 -> {
                    if (ticks == 2) {
                        mc.setScreen(null);
                        var server = mc.getSingleplayerServer();
                        BlockPos pod = DemoBuilder.lastCorner;
                        if (pod != null) server.execute(() -> server.overworld().setBlockAndUpdate(pod,
                                net.hypervisorx.registry.HvxBlocks.SUPPLY_POD.get().defaultBlockState()));
                        teleport(mc, 2.6, 0.2, -4.5, 180f, 30f);
                    }
                    if (ticks == 60) shot(mc, "hvx-retro-corner.png");
                    if (ticks == 62) teleport(mc, 1.5, -0.3, -5.2, 180f, 24f);
                    if (ticks == 110) shot(mc, "hvx-crt.png");
                    if (ticks == 112) mc.setScreen(new net.hypervisorx.client.screen.SettingsScreen(null));
                    if (ticks == 160) {
                        shot(mc, "hvx-settings.png");
                        mc.setScreen(new net.hypervisorx.client.screen.FileBrowserScreen(null));
                    }
                    if (ticks == 210) {
                        shot(mc, "hvx-files.png");
                        mc.setScreen(null);
                        var server = mc.getSingleplayerServer();
                        server.execute(() -> server.overworld().setDayTime(18000));
                        teleport(mc, 0.5, 1.2, 3.0, 180f, 4f);
                    }
                    if (ticks == 280) shot(mc, "hvx-night-world.png");
                    if (ticks == 282) teleport(mc, -3.3, 0.2, -2.1, -128f, 22f);
                    if (ticks == 330) shot(mc, "hvx-night-tower.png");
                    if (ticks == 332) teleport(mc, -4.0, 0.2, -1.9, 180f, 32f);
                    if (ticks == 380) shot(mc, "hvx-night-desk.png");
                    if (ticks == 382) teleport(mc, 3.6, 0.6, -2.6, -142f, 20f);
                    if (ticks == 430) {
                        shot(mc, "hvx-night-rack.png");
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
                // ------------------------------------------------------------ VMware end-to-end test
                case 10 -> {
                    if (CAPTURE && ticks == 1) {
                        // need a window we own to capture: Notepad, with a known file name in the title
                        Path f = Path.of(System.getProperty("java.io.tmpdir"), CAPTURE_TITLE + ".txt");
                        Files.writeString(f, "HyperVisor X host window capture test\r\n\r\nThis Notepad window is shown on a monitor inside Minecraft.\r\n");
                        new ProcessBuilder("notepad.exe", f.toString()).start();
                        HvxConfig.get().allowHostWindowCapture = true;
                        log("capture: started Notepad and enabled host window capture for the test");
                    }
                    if (CAPTURE) {
                        if (ticks == 60) {
                            stage = 11;
                            ticks = 0;
                        }
                        return;
                    }
                    if (!TEST_IMAGE.isEmpty()) {
                        if (ticks == 1) {
                            testImageName = ImageLibrary.importPath(TEST_IMAGE);
                            log("test image linked: " + testImageName + " (" + TEST_OS + ")");
                        }
                        if (ticks == 20) {
                            stage = 11;
                            ticks = 0;
                        }
                        return;
                    }
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
                            r.backend = BACKEND;
                            r.guestOs = TEST_OS;
                            r.efi = TEST_OS.needsEfiAndTpm();
                            r.image = CAPTURE ? "" : testImageName;
                            r.target = CAPTURE ? CAPTURE_TITLE : "";
                            r.name = CAPTURE ? "Host window: Notepad" : BACKEND + ": " + (LONG_RUN ? TEST_OS.displayName() : "Tiny Core Linux");
                            r.access = VmRecord.Access.PUBLIC;
                            if (curved != null && sp.level().getBlockEntity(curved) instanceof DisplayBlockEntity d) d.link(tower, 0);
                            VmManager m = VmManager.get();
                            m.data().changed();
                            vmwareVm = r.id;
                            Component err = m.startUnchecked(r, gaming.builtSpec(), gaming);
                            log(BACKEND + " start: " + (err == null ? "ok" : err.getString()) + " vm=" + r.id + " pc=" + pc);
                        });
                    }
                    if (ticks == 40) teleport(mc, 0.6, 0.4, -1.6, 180f, 14f);
                    if (LONG_RUN && vmwareVm != null) {
                        // watch the whole install full screen, a shot every 2 min, status every 10s
                        if (ticks == 80) mc.setScreen(new FocusScreen(vmwareVm, false));
                        var st = ClientVmCache.get(vmwareVm);
                        if (ticks % 200 == 0) log(BACKEND + " install: " + (st == null ? "none" : st.state() + " " + st.width() + "x" + st.height() + " "
                                + Math.round(st.fps()) + " fps '" + st.message() + "'"));
                        if (ticks % 2400 == 1200) shot(mc, "hvx-win-progress-" + (++progressShots) + ".png");
                        if (st != null && st.state().equals("ERROR")) {
                            log(BACKEND + " ERROR: " + st.message());
                            stage = 19;
                            ticks = 0;
                        }
                        return;
                    }
                    if (ticks > 60 && ticks % 40 == 0 && vmwareVm != null) {
                        var st = ClientVmCache.get(vmwareVm);
                        ScreenTexture tex = ScreenTextureManager.get(vmwareVm);
                        if (ticks % 200 == 0) {
                            log(BACKEND + " status=" + (st == null ? "none" : st.state() + " " + st.width() + "x" + st.height() + " " + Math.round(st.fps())
                                    + " fps '" + st.message() + "'") + " texture=" + (tex == null ? "none" : tex.width() + "x" + tex.height() + " content=" + tex.hasContent()));
                        }
                        if (st != null && st.state().equals("ERROR")) {
                            log(BACKEND + " ERROR: " + st.message());
                            stage = 19;
                            ticks = 0;
                        } else if (CAPTURE && tex != null && tex.hasContent() && ticks > 200) {
                            stage = 12;
                            ticks = 0;
                        } else if (tex != null && tex.hasContent() && st.width() >= 800) {
                            // hit graphics mode (X11 is up), give the desktop time to load its dock
                            if (desktopAt < 0) {
                                desktopAt = ticks;
                                log(BACKEND + " guest switched to " + st.width() + "x" + st.height());
                            } else if (ticks - desktopAt > 20 * 45) {
                                stage = 12;
                                ticks = 0;
                            }
                        } else if (ticks > 20 * 300) {
                            log(BACKEND + ": no desktop picture after 5 minutes");
                            stage = 19;
                            ticks = 0;
                        }
                    }
                }
                case 12 -> {
                    String p = "hvx-" + BACKEND + "-";
                    if (ticks == 1) shot(mc, p + "world.png");
                    if (ticks == 10) mc.setScreen(new FocusScreen(vmwareVm, false));
                    // input check (VMs only): wiggle the guest cursor around, then click the terminal in the dock
                    if (!CAPTURE) {
                        if (ticks == 40) pointer(mc, 120, 120, 0);
                        if (ticks == 60) shot(mc, p + "pointer-a.png");
                        if (ticks == 62) pointer(mc, 870, 600, 0);
                        if (ticks == 82) shot(mc, p + "pointer-b.png");
                        if (ticks == 84) pointer(mc, 610, 742, 0);
                        if (ticks == 90) pointer(mc, 610, 742, 1);
                        if (ticks == 93) pointer(mc, 610, 742, 0);
                    }
                    if (ticks == 120) {
                        var st = ClientVmCache.get(vmwareVm);
                        log(BACKEND + " focus: " + (st == null ? "?" : st.width() + "x" + st.height() + " " + Math.round(st.fps()) + " fps '" + st.message() + "'"));
                        shot(mc, p + "focus.png");
                    }
                    if (ticks == 130) {
                        mc.setScreen(null);
                        openPcMenu(mc, DemoBuilder.lastTower);
                    }
                    if (ticks == 190) {
                        shot(mc, p + "pc.png");
                        mc.setScreen(null);
                        log(BACKEND + " test PASSED");
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

    /** Puts the player 2.2 blocks in front of the closest flat monitor showing {@code vm}, looking at it (like walking up to it). */
    private static void standBeforeScreen(Minecraft mc, UUID vm) {
        BlockPos best = null;
        BlockPos center = mc.player.blockPosition();
        for (BlockPos p : BlockPos.betweenClosed(center.offset(-16, -4, -16), center.offset(16, 6, 16))) {
            if (mc.level.getBlockEntity(p) instanceof DisplayBlockEntity d && vm != null && vm.equals(d.displayedVm())
                    && mc.level.getBlockState(p).getBlock() == net.hypervisorx.registry.HvxBlocks.MONITOR.get()
                    && (best == null || p.distSqr(center) < best.distSqr(center))) best = p.immutable();
        }
        if (best == null) {
            log("desk view: no monitor of the demo VM found");
            return;
        }
        var facing = mc.level.getBlockState(best).getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.HORIZONTAL_FACING);
        Vec3 c = Vec3.atBottomCenterOf(best);
        double x = c.x + facing.getStepX() * 2.2, z = c.z + facing.getStepZ() * 2.2;
        float yaw = facing.getOpposite().toYRot();
        var server = mc.getSingleplayerServer();
        UUID self = mc.player.getUUID();
        log("desk view: standing before monitor " + best + " facing " + facing);
        server.execute(() -> {
            ServerPlayer sp = server.getPlayerList().getPlayer(self);
            if (sp != null) sp.teleportTo(sp.serverLevel(), x, c.y - 0.6, z, yaw, 22f);
        });
    }

    /** Sends a pointer event to the VMware guest down the same server path player input takes. */
    private static void pointer(Minecraft mc, int x, int y, int buttons) {
        var server = mc.getSingleplayerServer();
        UUID vm = vmwareVm;
        server.execute(() -> {
            var m = VmManager.get();
            var session = m == null || vm == null ? null : m.session(vm);
            if (session != null && session.instance() != null) session.instance().pointer(x, y, buttons);
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

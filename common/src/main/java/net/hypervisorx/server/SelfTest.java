package net.hypervisorx.server;

import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendContext;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.GuestOs;
import net.hypervisorx.backend.VmInstance;
import net.hypervisorx.backend.VmLaunchSpec;
import net.hypervisorx.backend.embedded.EmbeddedBackend;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.vm.display.FrameStream;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.UUID;

/**
 * {@code /hvx selftest}: probes every hypervisor, then boots a throwaway embedded MicroVM through
 * the real streaming encoder and reports boot time + encoded frame size. Runs off-thread.
 */
public final class SelfTest {
    private SelfTest() {
    }

    public static void run(CommandSourceStack source) {
        BackendContext ctx = HvxConfig.get().backendContext();
        Thread t = new Thread(() -> {
            try {
                BackendRegistry.rescan(ctx).join();
                BackendRegistry.statuses().forEach((id, st) -> say(source, (st.available() ? "✔ " : "✘ ") + id
                        + (st.version().isEmpty() ? "" : " " + st.version()) + " - " + st.message(), st.available() ? ChatFormatting.GREEN : ChatFormatting.GRAY));
                UUID id = UUID.randomUUID();
                VmLaunchSpec spec = new VmLaunchSpec(id, "selftest", GuestOs.LINUX, 1, 64, 960, 544, 0, false, false, false,
                        ctx.vmsRoot().resolve("selftest"), null, null, List.of(), false, false, "linux", null, false);
                long t0 = System.currentTimeMillis();
                VmInstance vm = new EmbeddedBackend().launch(spec, ctx);
                FrameStream stream = new FrameStream(vm.framebuffer());
                FrameStream.Viewer viewer = new FrameStream.Viewer(false, false, 1e12);
                long bytes = 0;
                int tiles = 0;
                long deadline = t0 + 30_000;
                boolean booted = false;
                while (System.currentTimeMillis() < deadline) {
                    Thread.sleep(250);
                    stream.commit();
                    for (var packet : stream.collect(viewer, 900_000).packets()) {
                        for (var tile : packet) {
                            bytes += tile.data().length;
                            tiles++;
                        }
                    }
                    if (vm.framebuffer().sourceFps() > 0 && tiles > 20) {
                        booted = true;
                        if (System.currentTimeMillis() - t0 > 4000) break;
                    }
                }
                long ms = System.currentTimeMillis() - t0;
                vm.close();
                if (booted) {
                    say(source, "Embedded MicroVM booted and streamed " + tiles + " tiles (" + bytes / 1024 + " KiB compressed) in " + ms + " ms", ChatFormatting.AQUA);
                } else {
                    say(source, "Embedded MicroVM did not produce a picture within 30 s", ChatFormatting.RED);
                }
            } catch (Exception e) {
                HyperVisorX.LOG.error("Self-test failed", e);
                say(source, "Self-test failed: " + e.getMessage(), ChatFormatting.RED);
            }
        }, "HyperVisorX-SelfTest");
        t.setDaemon(true);
        t.start();
    }

    private static void say(CommandSourceStack source, String text, ChatFormatting color) {
        source.getServer().execute(() -> source.sendSuccess(() -> Component.literal(text).withStyle(color), false));
    }
}

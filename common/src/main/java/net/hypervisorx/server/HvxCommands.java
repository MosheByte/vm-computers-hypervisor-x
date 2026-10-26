package net.hypervisorx.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.hypervisorx.HyperVisorX;
import net.hypervisorx.backend.BackendRegistry;
import net.hypervisorx.backend.BackendStatus;
import net.hypervisorx.backend.HypervisorBackend;
import net.hypervisorx.config.HvxConfig;
import net.hypervisorx.hardware.HostLimits;
import net.hypervisorx.vm.VmManager;
import net.hypervisorx.vm.VmRecord;
import net.hypervisorx.vm.VmSession;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/** The {@code /hvx} admin command. */
public final class HvxCommands {
    private HvxCommands() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("hvx")
                .then(Commands.literal("backends").executes(HvxCommands::backends))
                .then(Commands.literal("list").executes(HvxCommands::list))
                .then(Commands.literal("rescan").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    BackendRegistry.rescan(HvxConfig.get().backendContext());
                    ctx.getSource().sendSuccess(() -> Component.literal("Rescanning hypervisors..."), false);
                    return 1;
                }))
                .then(Commands.literal("stop").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("vm", StringArgumentType.word())
                                .executes(ctx -> stop(ctx, false))
                                .then(Commands.argument("force", BoolArgumentType.bool())
                                        .executes(ctx -> stop(ctx, BoolArgumentType.getBool(ctx, "force"))))))
                .then(Commands.literal("selftest").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("Running HyperVisor X self-test..."), false);
                    SelfTest.run(ctx.getSource());
                    return 1;
                }))
                .then(Commands.literal("demo").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    Component msg = DemoBuilder.build(ctx.getSource().getPlayerOrException());
                    ctx.getSource().sendSuccess(() -> msg, true);
                    return 1;
                }))
                .then(Commands.literal("info").then(Commands.argument("vm", StringArgumentType.word()).executes(HvxCommands::info)))
                .then(Commands.literal("reload").requires(s -> s.hasPermission(2)).executes(ctx -> {
                    HvxConfig.replace(HvxConfig.get().copy());
                    ctx.getSource().sendSuccess(() -> Component.literal("HyperVisor X config reloaded"), true);
                    return 1;
                })));
    }

    private static int backends(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(HyperVisorX.MOD_NAME + " " + HyperVisorX.VERSION
                + " - host: " + HostLimits.cpus() + " CPUs, " + HostLimits.totalRamMb() + " MiB").withStyle(ChatFormatting.AQUA), false);
        for (HypervisorBackend b : BackendRegistry.all()) {
            BackendStatus s = BackendRegistry.status(b.id());
            Component line = Component.literal((s.available() ? " ✔ " : " ✘ ") + b.displayName()
                            + (s.version().isEmpty() ? "" : " " + s.version()) + " - " + s.message())
                    .withStyle(s.available() ? ChatFormatting.GREEN : ChatFormatting.GRAY);
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> ctx) {
        VmManager m = VmManager.get();
        if (m == null) return 0;
        if (m.sessions().isEmpty()) {
            ctx.getSource().sendSuccess(() -> Component.literal("No virtual machines are running."), false);
            return 1;
        }
        for (VmSession s : m.sessions()) {
            Component line = Component.literal(String.format(Locale.ROOT, " %s  %-24s %-10s %-10s %4.0f fps  %d viewers",
                    s.record.id.toString().substring(0, 8), s.record.name, s.backendId, s.state(), s.fps(), s.viewers.size()));
            ctx.getSource().sendSuccess(() -> line, false);
        }
        return m.sessions().size();
    }

    private static VmRecord find(String prefix) {
        VmManager m = VmManager.get();
        if (m == null) return null;
        for (VmRecord r : m.data().all()) {
            if (r.id.toString().startsWith(prefix.toLowerCase(Locale.ROOT))) return r;
        }
        return null;
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, boolean force) {
        VmRecord r = find(StringArgumentType.getString(ctx, "vm"));
        if (r == null) {
            ctx.getSource().sendFailure(Component.literal("No VM with that id"));
            return 0;
        }
        VmManager.get().stop(r.id, force ? VmManager.StopMode.FORCE : VmManager.StopMode.ACPI);
        ctx.getSource().sendSuccess(() -> Component.literal("Stopping " + r.name), true);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        VmRecord r = find(StringArgumentType.getString(ctx, "vm"));
        if (r == null) {
            ctx.getSource().sendFailure(Component.literal("No VM with that id"));
            return 0;
        }
        VmSession s = VmManager.get().session(r.id);
        ctx.getSource().sendSuccess(() -> Component.literal(r.name + " [" + r.id + "] owner=" + r.ownerName + " kind=" + r.kind
                + " backend=" + r.backend + "/" + r.lastBackend + " os=" + r.guestOs + " image=" + r.image + " access=" + r.access
                + " state=" + (s == null ? "STOPPED" : s.state() + " (" + s.message() + ")")), false);
        return 1;
    }
}

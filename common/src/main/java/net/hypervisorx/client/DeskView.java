package net.hypervisorx.client;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.List;
import java.util.UUID;

/**
 * The in-world "seated" view: when a screen is used, the camera glides in front of it and stays
 * locked there while the world keeps rendering around the monitor. The player does not move;
 * only the camera is moved (by {@code CameraMixin}) and narrowed a little (by {@code GameRendererMixin})
 * so the picture fills about two thirds of the view. Leaving glides the camera back to the eyes.
 */
public final class DeskView {
    private static final float ENTER_SECONDS = 0.5f;
    private static final float EXIT_SECONDS = 0.35f;
    /** Share of the view height / width the picture should fill once seated. */
    private static final double FILL_H = 0.55;
    private static final double FILL_W = 0.72;
    /** The camera leans in at most this far from the eyes (blocks). */
    private static final double MAX_TRAVEL = 1.5;
    private static final double MIN_FOV = 36;

    private static boolean active;
    private static UUID vm;
    private static BlockPos anchor;
    private static Vec3 seat = Vec3.ZERO;
    private static Vec3 lookAt = Vec3.ZERO;
    private static float yaw;
    private static float pitch;
    private static double fovScale = 1;
    private static float blend;
    private static long lastNanos;
    private static boolean savedHideGui;
    /** FOV (degrees) without our zoom, and the one we actually used last frame. */
    private static double baseFov = 70;
    private static double renderFov = 70;

    private DeskView() {
    }

    public static boolean active() {
        return active;
    }

    /** True while seated, or still gliding back to the eyes. */
    public static boolean engaged() {
        return active || blend > 0f;
    }

    public static BlockPos anchor() {
        return anchor;
    }

    /**
     * Seats the camera in front of whichever screen showing {@code vm} is closest to the crosshair.
     * False if none of them is in view (held laptop, screen behind the player).
     */
    public static boolean enter(UUID vmId) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return false;
        List<ScreenHits.Quad> quads = ScreenHits.of(vmId);
        Vec3 eye = mc.player.getEyePosition();
        Vec3 look = mc.player.getViewVector(1f);
        BlockPos aimed = mc.hitResult instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b.getBlockPos() : null;
        ScreenHits.Quad best = null;
        double bestScore = Double.MAX_VALUE;
        boolean aimedShows = false;
        for (ScreenHits.Quad q : quads) aimedShows |= q.pos().equals(aimed);
        for (ScreenHits.Quad q : quads) {
            Vec3 c = q.center();
            double d = c.distanceTo(eye);
            if (d > 8 || q.normal().dot(eye.subtract(c)) <= 0) continue;
            // the clicked screen wins, otherwise a visible one near the crosshair
            if (aimedShows && !q.pos().equals(aimed)) continue;
            double score = (1 - look.dot(c.subtract(eye).normalize())) + d * 0.02;
            if (!aimedShows && hidden(mc, eye, q)) score += 3;
            if (score < bestScore) {
                bestScore = score;
                best = q;
            }
        }
        // nothing clicked and every candidate is blocked by something: full-window view is better then
        if (best == null || !aimedShows && hidden(mc, eye, best)) return false;
        // whole picture = every quad in this screen group (curved segments, spanned or arm panels)
        Vec3 sum = Vec3.ZERO;
        Vec3 normal = Vec3.ZERO;
        Vec3 right = Vec3.ZERO;
        int corners = 0;
        List<ScreenHits.Quad> group = new java.util.ArrayList<>();
        for (ScreenHits.Quad q : quads) if (q.pos().equals(best.pos())) group.add(q);
        // desktop spanned over neighbouring monitors: they show other slices of the picture, not copies
        boolean grew = true;
        while (grew) {
            grew = false;
            for (ScreenHits.Quad q : quads) {
                if (group.contains(q) || !otherSlice(q, group)) continue;
                double near = Double.MAX_VALUE;
                for (ScreenHits.Quad g : group) near = Math.min(near, g.center().distanceTo(q.center()));
                if (near > 2.2) continue;
                group.add(q);
                grew = true;
            }
        }
        for (ScreenHits.Quad q : group) {
            sum = sum.add(q.tl()).add(q.bl()).add(q.br()).add(q.tr());
            corners += 4;
            normal = normal.add(q.normal().scale(q.area()));
            right = right.add(q.tr().subtract(q.tl()));
        }
        Vec3 center = sum.scale(1.0 / corners);
        normal = normal.normalize();
        right = right.normalize();
        Vec3 up = right.cross(normal).normalize();
        if (up.dot(best.tl().subtract(best.bl())) < 0) up = up.scale(-1);
        double halfW = 0;
        double halfH = 0;
        for (ScreenHits.Quad q : group) {
            for (Vec3 p : new Vec3[]{q.tl(), q.bl(), q.br(), q.tr()}) {
                Vec3 r = p.subtract(center);
                halfW = Math.max(halfW, Math.abs(r.dot(right)));
                halfH = Math.max(halfH, Math.abs(r.dot(up)));
            }
        }
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        double tan = Math.tan(Math.toRadians(baseFov) / 2);
        // where a seated player's eyes would be: straight in front of the picture
        double want = Math.max(0.5, Math.max(halfH / (FILL_H * tan), halfW / (FILL_W * tan * aspect)));
        Vec3 target = center.add(normal.scale(want));
        Vec3 move = target.subtract(eye);
        if (move.length() > MAX_TRAVEL) target = eye.add(move.scale(MAX_TRAVEL / move.length()));
        BlockHitResult clip = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
        if (clip.getType() != HitResult.Type.MISS) target = eye.add(clip.getLocation().subtract(eye).scale(0.75));
        Vec3 to = center.subtract(target);
        double dist = Math.max(0.2, -to.dot(normal));
        double needTan = Math.max(halfH / (FILL_H * dist), halfW / (FILL_W * dist * aspect));
        double fov = Math.toDegrees(2 * Math.atan(needTan));
        fovScale = Mth.clamp(fov / baseFov, MIN_FOV / baseFov, 1.0);
        seat = target;
        lookAt = center;
        yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90f;
        pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG);
        vm = best.vm();
        anchor = best.pos();
        if (!active) {
            savedHideGui = mc.options.hideGui;
            mc.options.hideGui = true;
        }
        active = true;
        lastNanos = System.nanoTime();
        return true;
    }

    /** True if some block (not the screen's own) is between the eyes and the picture. */
    private static boolean hidden(Minecraft mc, Vec3 eye, ScreenHits.Quad q) {
        Vec3 target = q.center().add(q.normal().scale(0.05));
        BlockHitResult r = mc.level.clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, mc.player));
        return r.getType() == HitResult.Type.BLOCK && !r.getBlockPos().equals(q.pos())
                && r.getLocation().distanceToSqr(eye) < target.distanceToSqr(eye) - 0.04;
    }

    /** True if {@code q} shows a part of the desktop nothing in {@code group} shows (spanned neighbour, not a mirror). */
    private static boolean otherSlice(ScreenHits.Quad q, List<ScreenHits.Quad> group) {
        for (ScreenHits.Quad g : group) {
            if (Math.min(q.u1(), g.u1()) - Math.max(q.u0(), g.u0()) > 0.01) return false;
        }
        return true;
    }

    /** Stand up: camera glides back to the eyes, HUD comes back. */
    public static void exit() {
        if (!active) return;
        active = false;
        Minecraft.getInstance().options.hideGui = savedHideGui;
    }

    public static void reset() {
        exit();
        blend = 0f;
        vm = null;
        anchor = null;
    }

    /** Steps the glide, the camera calls this once a frame. Returns the eased blend (0 = eyes, 1 = seated). */
    public static float frame() {
        long now = System.nanoTime();
        float dt = lastNanos == 0 ? 0f : Math.min(0.1f, (now - lastNanos) / 1e9f);
        lastNanos = now;
        blend = Mth.clamp(blend + (active ? dt / ENTER_SECONDS : -dt / EXIT_SECONDS), 0f, 1f);
        if (blend == 0f && !active) {
            vm = null;
            anchor = null;
        }
        return eased();
    }

    private static float eased() {
        float t = blend;
        return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
    }

    public static Vec3 position(Vec3 eyes, float k) {
        return eyes.lerp(seat, k);
    }

    public static float yaw(float eyes, float k) {
        return eyes + Mth.wrapDegrees(yaw - eyes) * k;
    }

    public static float pitch(float eyes, float k) {
        return Mth.lerp(k, eyes, pitch);
    }

    /** Applied to the world FOV only, never the hand. */
    public static double fov(double vanilla) {
        baseFov = vanilla;
        double out = engaged() ? vanilla * Mth.lerp(eased(), 1.0, fovScale) : vanilla;
        renderFov = out;
        return out;
    }

    /** Ray from the camera through a point in the window (GUI coords). */
    public static Vec3[] mouseRay(double mx, double my, int guiWidth, int guiHeight) {
        Minecraft mc = Minecraft.getInstance();
        Camera cam = mc.gameRenderer.getMainCamera();
        double nx = mx / guiWidth * 2 - 1;
        double ny = 1 - my / guiHeight * 2;
        double tan = Math.tan(Math.toRadians(renderFov) / 2);
        double aspect = mc.getWindow().getWidth() / (double) Math.max(1, mc.getWindow().getHeight());
        Vector3f f = cam.getLookVector();
        Vector3f u = cam.getUpVector();
        Vector3f l = cam.getLeftVector();
        double sx = nx * tan * aspect;
        double sy = ny * tan;
        Vec3 dir = new Vec3(f.x() - l.x() * sx + u.x() * sy, f.y() - l.y() * sx + u.y() * sy, f.z() - l.z() * sx + u.z() * sy).normalize();
        return new Vec3[]{cam.getPosition(), dir};
    }

    /** Guest position under the mouse, or null if the cursor's off to the side of the screen. */
    public static ScreenHits.Hit pick(UUID target, double mx, double my, int guiWidth, int guiHeight) {
        Vec3[] ray = mouseRay(mx, my, guiWidth, guiHeight);
        return ScreenHits.raycast(target, ray[0], ray[1], 24);
    }

    /** Center of the seated picture, for distance checks. */
    public static Vec3 lookAt() {
        return lookAt;
    }
}

package net.hypervisorx.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Where each VM picture got drawn last frame, in world coords. The in-world view raycasts the
 * mouse against these quads to find the guest pixel under the cursor, so flat, curved, spanned
 * and video-wall screens all go through the same path.
 */
public final class ScreenHits {
    /** One drawn picture quad. Corners go TL, BL, BR, TR. u0..u1 runs across the guest, v is 0..1 going down. */
    public record Quad(UUID vm, BlockPos pos, Vec3 tl, Vec3 bl, Vec3 br, Vec3 tr, float u0, float u1) {
        public Vec3 center() {
            return tl.add(br).scale(0.5);
        }

        /** Unit normal pointing out of the picture, toward whoever's looking at it. */
        public Vec3 normal() {
            Vec3 right = tr.subtract(tl);
            Vec3 down = bl.subtract(tl);
            return down.cross(right).normalize();
        }

        public double area() {
            return tr.subtract(tl).length() * bl.subtract(tl).length();
        }
    }

    /** Where a ray hit a picture, in guest coords (0..1). */
    public record Hit(Quad quad, double u, double v, double distance) {
    }

    private static final int MAX_QUADS = 4096;
    private static List<Quad> current = new ArrayList<>();
    private static List<Quad> previous = new ArrayList<>();

    private ScreenHits() {
    }

    /** Once a frame, before the level renders. The frame that just finished becomes "previous". */
    public static void beginFrame() {
        List<Quad> t = previous;
        previous = current;
        current = t;
        current.clear();
    }

    public static void clear() {
        current.clear();
        previous.clear();
    }

    /**
     * Records a picture quad drawn with the block-entity pose {@code m} (camera-relative, the way the
     * level renderer sets it up). Quads far from their block get skipped, those are item/GUI renders.
     */
    public static void add(UUID vm, BlockPos pos, Matrix4f m, float xLeft, float yBottom, float xRight, float yTop, float z, float u0, float u1) {
        if (vm == null || pos == null || current.size() >= MAX_QUADS) return;
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        Vec3 tl = world(m, xLeft, yTop, z, cam);
        if (tl.distanceToSqr(Vec3.atCenterOf(pos)) > 25) return;
        current.add(new Quad(vm, pos, tl, world(m, xLeft, yBottom, z, cam), world(m, xRight, yBottom, z, cam), world(m, xRight, yTop, z, cam), u0, u1));
    }

    private static Vec3 world(Matrix4f m, float x, float y, float z, Vec3 cam) {
        Vector3f v = m.transformPosition(x, y, z, new Vector3f());
        return new Vec3(v.x + cam.x, v.y + cam.y, v.z + cam.z);
    }

    /** Pictures of {@code vm} from the frame in progress, or from the last full frame if there are none yet. */
    public static List<Quad> of(UUID vm) {
        List<Quad> out = new ArrayList<>();
        for (Quad q : current) if (q.vm().equals(vm)) out.add(q);
        if (out.isEmpty()) for (Quad q : previous) if (q.vm().equals(vm)) out.add(q);
        return out;
    }

    /**
     * Closest picture of {@code vm} the ray hits. A hair outside the edge still counts (gets clamped)
     * so the taskbar and screen corners are easy to hit.
     */
    public static Hit raycast(UUID vm, Vec3 origin, Vec3 dir, double maxDistance) {
        Hit best = null;
        for (Quad q : of(vm)) {
            Vec3 right = q.tr().subtract(q.tl());
            Vec3 down = q.bl().subtract(q.tl());
            Vec3 n = down.cross(right);
            double denom = dir.dot(n);
            if (Math.abs(denom) < 1e-9) continue;
            double t = q.tl().subtract(origin).dot(n) / denom;
            if (t <= 0 || t > maxDistance || (best != null && t >= best.distance())) continue;
            Vec3 rel = origin.add(dir.scale(t)).subtract(q.tl());
            double a = rel.dot(right) / right.lengthSqr();
            double b = rel.dot(down) / down.lengthSqr();
            double slackA = 0.004 / Math.max(1e-6, q.u1() - q.u0());
            if (a < -slackA || a > 1 + slackA || b < -0.02 || b > 1.02) continue;
            a = Math.max(0, Math.min(1, a));
            b = Math.max(0, Math.min(1, b));
            best = new Hit(q, q.u0() + a * (q.u1() - q.u0()), b, t);
        }
        return best;
    }
}

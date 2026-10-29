package net.hypervisorx.mixin;

import net.hypervisorx.client.DeskView;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Leans the camera toward the screen you're using and points it at the picture ({@link DeskView}). */
@Mixin(Camera.class)
public abstract class CameraMixin {
    @Shadow
    protected abstract void setPosition(Vec3 pos);

    @Shadow
    protected abstract void setRotation(float yRot, float xRot);

    @Shadow
    public abstract Vec3 getPosition();

    @Shadow
    public abstract float getXRot();

    @Shadow
    public abstract float getYRot();

    @Inject(method = "setup", at = @At("TAIL"))
    private void hypervisorx$deskView(BlockGetter level, Entity entity, boolean detached, boolean mirrored, float partialTick, CallbackInfo ci) {
        if (!DeskView.engaged()) return;
        float k = DeskView.frame();
        if (k <= 0f) return;
        // position first, it decides where the camera looks from
        Vec3 pos = DeskView.position(getPosition(), k);
        setRotation(DeskView.yaw(getYRot(), k), DeskView.pitch(getXRot(), k));
        setPosition(pos);
    }
}

package net.hypervisorx.entity;

import net.hypervisorx.block.entity.CrateBlockEntity;
import net.hypervisorx.registry.HvxBlocks;
import net.hypervisorx.registry.HvxEntities;
import net.hypervisorx.registry.HvxSounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Orbital supply drop. Falls out of the sky with a rocket trail, fires retro-thrusters near the
 * ground and turns into a delivery crate with the order inside.
 */
public class SupplyPodEntity extends Entity {
    private static final EntityDataAccessor<Boolean> LANDED = SynchedEntityData.defineId(SupplyPodEntity.class, EntityDataSerializers.BOOLEAN);
    private final List<ItemStack> cargo = new ArrayList<>();
    private UUID customer;
    private int landedTicks;
    private boolean announced;

    public SupplyPodEntity(EntityType<? extends SupplyPodEntity> type, Level level) {
        super(type, level);
        noCulling = true;
    }

    public static SupplyPodEntity create(ServerLevel level, Vec3 pos, List<ItemStack> cargo, UUID customer) {
        SupplyPodEntity pod = new SupplyPodEntity(HvxEntities.SUPPLY_POD.get(), level);
        pod.setPos(pos);
        pod.cargo.addAll(cargo);
        pod.customer = customer;
        pod.setDeltaMovement(0, -1.2, 0);
        return pod;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(LANDED, false);
    }

    public boolean landed() {
        return entityData.get(LANDED);
    }

    @Override
    public void tick() {
        super.tick();
        Level level = level();
        if (!landed()) {
            if (!level.isClientSide && !announced) {
                announced = true;
                level.playSound(null, blockPosition(), HvxSounds.POD_INCOMING.get(), SoundSource.NEUTRAL, 4f, 1f);
            }
            double vy = Math.max(-1.6, getDeltaMovement().y - 0.05);
            HitResult below = level.clip(new ClipContext(position(), position().add(0, -14, 0), ClipContext.Block.COLLIDER,
                    ClipContext.Fluid.ANY, this));
            boolean braking = below.getType() != HitResult.Type.MISS;
            if (braking) vy = Math.max(vy, -0.32);
            setDeltaMovement(0, vy, 0);
            move(MoverType.SELF, getDeltaMovement());
            if (level.isClientSide) {
                for (int i = 0; i < (braking ? 6 : 3); i++) {
                    double ox = (random.nextDouble() - 0.5) * 0.6;
                    double oz = (random.nextDouble() - 0.5) * 0.6;
                    level.addParticle(braking ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME, getX() + ox, getY() - 0.1, getZ() + oz, ox * 0.2, -0.4, oz * 0.2);
                    level.addParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, getX() + ox, getY() + 0.2, getZ() + oz, 0, 0.05, 0);
                }
            } else if (onGround() || verticalCollision || getY() < level.getMinBuildHeight()) {
                land((ServerLevel) level);
            }
        } else {
            landedTicks++;
            if (level.isClientSide && landedTicks < 30) {
                level.addParticle(ParticleTypes.LARGE_SMOKE, getX() + random.nextGaussian() * 0.4, getY() + 0.3, getZ() + random.nextGaussian() * 0.4, 0, 0.08, 0);
            }
            if (!level.isClientSide && landedTicks > 40) discard();
        }
    }

    private void land(ServerLevel level) {
        entityData.set(LANDED, true);
        setDeltaMovement(Vec3.ZERO);
        level.playSound(null, blockPosition(), HvxSounds.POD_LAND.get(), SoundSource.NEUTRAL, 2f, 1f);
        level.sendParticles(ParticleTypes.EXPLOSION, getX(), getY() + 0.5, getZ(), 3, 0.6, 0.3, 0.6, 0.0);
        level.sendParticles(ParticleTypes.CLOUD, getX(), getY() + 0.2, getZ(), 30, 1.2, 0.2, 1.2, 0.05);
        BlockPos pos = blockPosition();
        for (int i = 0; i < 4 && !level.getBlockState(pos).canBeReplaced(); i++) pos = pos.above();
        List<ItemStack> leftovers = new ArrayList<>(cargo);
        if (level.getBlockState(pos).canBeReplaced()) {
            level.setBlockAndUpdate(pos, HvxBlocks.DELIVERY_CRATE.get().defaultBlockState());
            if (level.getBlockEntity(pos) instanceof CrateBlockEntity crate) {
                NonNullList<ItemStack> rest = crate.fill(cargo);
                leftovers = new ArrayList<>(rest);
            }
        }
        for (ItemStack s : leftovers) level.addFreshEntity(new ItemEntity(level, getX(), getY() + 0.5, getZ(), s));
        cargo.clear();
        if (customer != null && level.getServer().getPlayerList().getPlayer(customer) instanceof ServerPlayer p) {
            p.sendSystemMessage(Component.translatable("hypervisorx.msg.pod_landed", pos.getX(), pos.getY(), pos.getZ()));
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        cargo.clear();
        HolderLookup.Provider regs = registryAccess();
        for (Tag t : tag.getList("Cargo", Tag.TAG_COMPOUND)) {
            ItemStack.parse(regs, t).ifPresent(cargo::add);
        }
        customer = tag.hasUUID("Customer") ? tag.getUUID("Customer") : null;
        entityData.set(LANDED, tag.getBoolean("Landed"));
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ListTag list = new ListTag();
        HolderLookup.Provider regs = registryAccess();
        for (ItemStack s : cargo) if (!s.isEmpty()) list.add(s.save(regs));
        tag.put("Cargo", list);
        if (customer != null) tag.putUUID("Customer", customer);
        tag.putBoolean("Landed", landed());
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distance) {
        return distance < 256 * 256;
    }
}

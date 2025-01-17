package net.soulsweaponry.entity.mobs;

import net.minecraft.block.BlockState;
import net.minecraft.entity.*;
import net.minecraft.entity.ai.goal.*;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.boss.BossBar.Color;
import net.minecraft.entity.damage.DamageSource;
import net.minecraft.entity.data.DataTracker;
import net.minecraft.entity.data.TrackedData;
import net.minecraft.entity.data.TrackedDataHandlerRegistry;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.soulsweaponry.config.ConfigConstructor;
import net.soulsweaponry.entity.ai.goal.DayStalkerGoal;
import net.soulsweaponry.networking.PacketRegistry;
import net.soulsweaponry.registry.ItemRegistry;
import net.soulsweaponry.registry.SoundRegistry;
import net.soulsweaponry.registry.WeaponRegistry;
import net.soulsweaponry.util.CustomDeathHandler;
import net.soulsweaponry.util.ParticleNetworking;
import org.jetbrains.annotations.Nullable;
import software.bernie.geckolib3.core.IAnimatable;
import software.bernie.geckolib3.core.PlayState;
import software.bernie.geckolib3.core.builder.AnimationBuilder;
import software.bernie.geckolib3.core.builder.ILoopType;
import software.bernie.geckolib3.core.controller.AnimationController;
import software.bernie.geckolib3.core.event.predicate.AnimationEvent;
import software.bernie.geckolib3.core.manager.AnimationData;
import software.bernie.geckolib3.core.manager.AnimationFactory;
import software.bernie.geckolib3.util.GeckoLibUtil;

import java.util.Optional;
import java.util.UUID;

public class DayStalker extends BossEntity implements IAnimatable {

    public AnimationFactory factory = GeckoLibUtil.createFactory(this);
    public int deathTicks;
    public int phaseTwoTicks;
    public int phaseTwoMaxTransitionTicks = 120;
    public static final int ATTACKS_LENGTH = DayStalker.Attacks.values().length;
    private static final TrackedData<Integer> ATTACKS = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<Boolean> INITIATING_PHASE_2 = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> IS_PHASE_2 = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> IS_FLYING = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Optional<UUID>> PARTNER_UUID = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.OPTIONAL_UUID);
    private static final TrackedData<Integer> REMAINING_ANI_TICKS = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<BlockPos> TARGET_POS = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BLOCK_POS);
    private static final TrackedData<Boolean> CHASE_TARGET = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Boolean> WAIT_ANIMATION = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BOOLEAN);
    private static final TrackedData<Integer> SPAWN_PARTICLES_STATE = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.INTEGER);
    private static final TrackedData<BlockPos> FLAMETHROWER_TARGET = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.BLOCK_POS);
    private static final TrackedData<Float> FLAMES_EDGE_RADIUS = DataTracker.registerData(DayStalker.class, TrackedDataHandlerRegistry.FLOAT);

    public DayStalker(EntityType<? extends DayStalker> entityType, World world) {
        super(entityType, world, Color.YELLOW);
        this.drops.add(WeaponRegistry.DAWNBREAKER);
        this.drops.add(ItemRegistry.LORD_SOUL_DAY_STALKER);
    }

    @Override
    protected void initGoals() {
        this.goalSelector.add(0, new SwimGoal(this));
        this.goalSelector.add(2, new DayStalkerGoal(this, 0.75D, true));
        this.goalSelector.add(8, new LookAtEntityGoal(this, PlayerEntity.class, 8.0F));
        this.goalSelector.add(8, new LookAroundGoal(this));
        this.targetSelector.add(2, new ActiveTargetGoal<>(this, PlayerEntity.class, true));
        this.targetSelector.add(5, (new RevengeGoal(this)).setGroupRevenge());
    }

    public void setAttackAnimation(Attacks attack) {
        for (int i = 0; i < ATTACKS_LENGTH; i++) {
            if (Attacks.values()[i].equals(attack)) {
                this.dataTracker.set(ATTACKS, i);
            }
        }
    }

    public Attacks getAttackAnimation() {
        return Attacks.values()[this.dataTracker.get(ATTACKS)];
    }

    public boolean isInitiatingPhaseTwo() {
        return this.dataTracker.get(INITIATING_PHASE_2);
    }

    public void setInitiatePhaseTwo(boolean bl) {
        this.dataTracker.set(INITIATING_PHASE_2, bl);
    }

    private <E extends IAnimatable> PlayState chains(AnimationEvent<E> event) {
        if (!this.isInitiatingPhaseTwo() && this.isPhaseTwo()) {
            if (this.getAttackAnimation().equals(Attacks.FLAMES_REACH)) {
                return PlayState.STOP;
            } else {
                event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_chains_2", ILoopType.EDefaultLoopTypes.LOOP));
            }
        }
        return PlayState.CONTINUE;
    }

    private <E extends IAnimatable> PlayState idles(AnimationEvent<E> event) {
        if (this.isInitiatingPhaseTwo()) {
            return PlayState.STOP;
        }
        if (this.isDead() || this.getAttackAnimation().equals(Attacks.DEATH) || this.getDeathTicks() > 0) {
            if (this.isPhaseTwo()) {
                event.getController().setAnimation(new AnimationBuilder().addAnimation("death_2", ILoopType.EDefaultLoopTypes.LOOP));
            } else {
                event.getController().setAnimation(new AnimationBuilder().addAnimation("death_1", ILoopType.EDefaultLoopTypes.LOOP));
            }
        } else {
            // NOTE:
            // Old geckolib doesn't manage to play multiple animations at the same time, apparently.
            // If the idle animations play during attacks, it messes things up.
            if (!this.getAttackAnimation().equals(Attacks.IDLE)) {
                return PlayState.STOP;
            }
            if (!this.isInitiatingPhaseTwo()) {
                if (this.isPhaseTwo()) {
                    if (!this.getAttackAnimation().equals(Attacks.FLAMES_REACH)) {
                        event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_2", ILoopType.EDefaultLoopTypes.LOOP));
                    } else {
                        event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_flames_reach_2", ILoopType.EDefaultLoopTypes.LOOP));
                    }
                } else {
                    if (!this.getAttackAnimation().equals(Attacks.FLAMES_REACH)) {
                        if (this.isFlying()) {
                            event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_flying_1", ILoopType.EDefaultLoopTypes.LOOP));
                        } else {
                            event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_1", ILoopType.EDefaultLoopTypes.LOOP));
                        }
                    } else {
                        event.getController().setAnimation(new AnimationBuilder().addAnimation("idle_flames_reach_1", ILoopType.EDefaultLoopTypes.LOOP));
                    }
                }
            }
        }
        return PlayState.CONTINUE;
    }

    private <E extends IAnimatable> PlayState attacks(AnimationEvent<E> event) {
        if (this.isDead()) return PlayState.STOP;
        if (this.isInitiatingPhaseTwo()) {
            event.getController().setAnimation(new AnimationBuilder().addAnimation("start_phase_2", ILoopType.EDefaultLoopTypes.PLAY_ONCE));
        } else {
            if (!this.isPhaseTwo()) {
                switch (this.getAttackAnimation()) {
                    case AIR_COMBUSTION -> event.getController().setAnimation(new AnimationBuilder().addAnimation("air_combustion_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case DECIMATE -> event.getController().setAnimation(new AnimationBuilder().addAnimation("decimate_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case DAWNBREAKER -> event.getController().setAnimation(new AnimationBuilder().addAnimation("dawnbreaker_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case CHAOS_STORM -> event.getController().setAnimation(new AnimationBuilder().addAnimation("chaos_storm_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMETHROWER -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flamethrower_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case SUNFIRE_RUSH -> event.getController().setAnimation(new AnimationBuilder().addAnimation("sunfire_rush_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case CONFLAGRATION -> event.getController().setAnimation(new AnimationBuilder().addAnimation("conflagration_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMES_EDGE -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flames_edge_1", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMES_REACH -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flames_reach_1", ILoopType.EDefaultLoopTypes.LOOP));
                    default -> event.getController().setAnimation(new AnimationBuilder().addAnimation("empty_1", ILoopType.EDefaultLoopTypes.LOOP));
                }
            } else {
                switch (this.getAttackAnimation()) {
                    case AIR_COMBUSTION -> event.getController().setAnimation(new AnimationBuilder().addAnimation("air_combustion_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case DECIMATE -> event.getController().setAnimation(new AnimationBuilder().addAnimation("decimate_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case DAWNBREAKER -> event.getController().setAnimation(new AnimationBuilder().addAnimation("dawnbreaker_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case CHAOS_STORM -> event.getController().setAnimation(new AnimationBuilder().addAnimation("chaos_storm_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMETHROWER -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flamethrower_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case SUNFIRE_RUSH -> event.getController().setAnimation(new AnimationBuilder().addAnimation("sunfire_rush_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case CONFLAGRATION -> event.getController().setAnimation(new AnimationBuilder().addAnimation("conflagration_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMES_EDGE -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flames_edge_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case RADIANCE -> event.getController().setAnimation(new AnimationBuilder().addAnimation("radiance_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case WARMTH -> event.getController().setAnimation(new AnimationBuilder().addAnimation("warmth_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case OVERHEAT -> event.getController().setAnimation(new AnimationBuilder().addAnimation("overheat_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case INFERNO -> event.getController().setAnimation(new AnimationBuilder().addAnimation("inferno_2", ILoopType.EDefaultLoopTypes.LOOP));
                    case FLAMES_REACH -> event.getController().setAnimation(new AnimationBuilder().addAnimation("flames_reach_2", ILoopType.EDefaultLoopTypes.LOOP));
                    default -> event.getController().setAnimation(new AnimationBuilder().addAnimation("empty_2", ILoopType.EDefaultLoopTypes.LOOP));
                }
            }
        }
        return PlayState.CONTINUE;
    }

    @Override
    public void updatePostDeath() {
        this.deathTicks++;
        if (this.deathTicks == this.getTicksUntilDeath() && !this.world.isClient()) {
            this.world.sendEntityStatus(this, EntityStatuses.ADD_DEATH_PARTICLES);
            CustomDeathHandler.deathExplosionEvent(world, this.getBlockPos(), false, SoundRegistry.DAWNBREAKER_EVENT);
            this.remove(RemovalReason.KILLED);
        }
    }

    @Override
    protected SoundEvent getDeathSound() {
        return this.isPhaseTwo() ? SoundRegistry.HARD_BOSS_DEATH_LONG : SoundRegistry.HARD_BOSS_DEATH_SHORT;
    }

    @Override
    public int getTicksUntilDeath() {
        return this.isPhaseTwo() ? 140 : 80;
    }

    @Override
    public int getDeathTicks() {
        return this.deathTicks;
    }

    @Override
    public void setDeath() {
        this.setAttackAnimation(Attacks.DEATH);
    }

    @Override
    public boolean isFireImmune() {
        return true;
    }

    @Override
    public boolean isUndead() {
        return false;
    }

    @Override
    public EntityGroup getGroup() {
        return EntityGroup.DEFAULT;
    }

    @Override
    public boolean disablesShield() {
        return true;
    }

    @Override
    public double getBossMaxHealth() {
        return ConfigConstructor.day_stalker_health;
    }

    @Override
    public void registerControllers(AnimationData data) {
        data.addAnimationController(new AnimationController<>(this, "idles", 0, this::idles));
        data.addAnimationController(new AnimationController<>(this, "chains", 0, this::chains));
        data.addAnimationController(new AnimationController<>(this, "attacks", 0, this::attacks));
    }

    @Override
    public AnimationFactory getFactory() {
        return this.factory;
    }

    @Override
    protected void initDataTracker() {
        super.initDataTracker();
        this.dataTracker.startTracking(ATTACKS, 0);
        this.dataTracker.startTracking(INITIATING_PHASE_2, false);
        this.dataTracker.startTracking(IS_PHASE_2, false);
        this.dataTracker.startTracking(PARTNER_UUID, Optional.empty());
        this.dataTracker.startTracking(REMAINING_ANI_TICKS, 0);
        this.dataTracker.startTracking(IS_FLYING, false);
        this.dataTracker.startTracking(TARGET_POS, new BlockPos(0, 0, 0));
        this.dataTracker.startTracking(CHASE_TARGET, true);
        this.dataTracker.startTracking(WAIT_ANIMATION, false);
        this.dataTracker.startTracking(SPAWN_PARTICLES_STATE, 0);
        this.dataTracker.startTracking(FLAMETHROWER_TARGET, new BlockPos(0, 0, 0));
        this.dataTracker.startTracking(FLAMES_EDGE_RADIUS, 2f);
    }

    @Nullable
    public NightProwler getPartner(ServerWorld world) {
        return (NightProwler) world.getEntity(this.getPartnerUuid());
    }

    public UUID getPartnerUuid() {
        return this.dataTracker.get(PARTNER_UUID).orElse(null);
    }

    public void setPartnerUuid(@Nullable UUID uuid) {
        this.dataTracker.set(PARTNER_UUID, Optional.ofNullable(uuid));
    }

    public boolean isPartner(LivingEntity living) {
        return this.getPartnerUuid() != null && living.getUuid() != null && this.getPartnerUuid().equals(living.getUuid());
    }

    public enum Attacks {
        IDLE, DEATH, AIR_COMBUSTION, DECIMATE, DAWNBREAKER, CHAOS_STORM, FLAMETHROWER, SUNFIRE_RUSH,
        CONFLAGRATION, FLAMES_EDGE, RADIANCE, WARMTH, OVERHEAT, INFERNO, FLAMES_REACH, BLAZE_BARRAGE
    }

    public static DefaultAttributeContainer.Builder createBossAttributes() {
        return HostileEntity.createHostileAttributes()
                .add(EntityAttributes.GENERIC_FOLLOW_RANGE, 120D)
                .add(EntityAttributes.GENERIC_MAX_HEALTH, ConfigConstructor.day_stalker_health)
                .add(EntityAttributes.GENERIC_MOVEMENT_SPEED, 0.3D)
                .add(EntityAttributes.GENERIC_ATTACK_DAMAGE, 20.0D)
                .add(EntityAttributes.GENERIC_KNOCKBACK_RESISTANCE, 10.0D)
                .add(EntityAttributes.GENERIC_ARMOR, 10.0D)
                .add(EntityAttributes.GENERIC_FLYING_SPEED, 0.8D);
    }

    @Override
    public void writeCustomDataToNbt(NbtCompound nbt) {
        super.writeCustomDataToNbt(nbt);
        if (this.getPartnerUuid() != null) {
            nbt.putUuid("partner_uuid", this.getPartnerUuid());
        }
        nbt.putBoolean("phase_two", this.isPhaseTwo());
        nbt.putInt("remaining_ani_ticks", this.getRemainingAniTicks());
        nbt.putBoolean("is_flying", this.isFlying());
        nbt.putBoolean("chase_target", this.shouldChaseTarget());
    }

    @Override
    public void readCustomDataFromNbt(NbtCompound nbt) {
        super.readCustomDataFromNbt(nbt);
        UUID uUID = null;
        if (nbt.containsUuid("partner_uuid")) {
            uUID = nbt.getUuid("partner_uuid");
        }
        if (uUID != null) {
            try {
                this.setPartnerUuid(uUID);
            } catch (Throwable ignored) {}
        }
        if (nbt.contains("phase_two")) {
            this.setPhaseTwo(nbt.getBoolean("phase_two"));
        }
        if (nbt.contains("remaining_ani_ticks")) {
            this.setRemainingAniTicks(nbt.getInt("remaining_ani_ticks"));
        }
        if (nbt.contains("is_flying")) {
            this.setFlying(nbt.getBoolean("is_flying"));
        }
        if (nbt.contains("chase_target")) {
            this.setChaseTarget(nbt.getBoolean("chase_target"));
        }
    }

    public boolean isEmpowered() {
        return (!this.world.isClient && this.world.isDay()) || this.isPhaseTwo();
    }

    @Override
    protected void mobTick() {
        super.mobTick();
        if (!this.world.isClient) {
            LivingEntity partner = this.getPartner((ServerWorld) this.world);
            if (!this.isPhaseTwo() && (partner == null || partner.isDead())) {
                this.setInitiatePhaseTwo(true);
            }
        }
        if (this.isEmpowered()) {
            if (this.getHealth() < this.getMaxHealth() && this.age % 10 == 0) {
                this.heal(1f);
            }
            this.addStatusEffect(new StatusEffectInstance(StatusEffects.RESISTANCE, 40, 1, false, false));
        }
        if (this.isInitiatingPhaseTwo()) {
            this.phaseTwoTicks++;
            int maxHealTicks = this.phaseTwoMaxTransitionTicks - 40;
            float healPerTick = this.getMaxHealth() / maxHealTicks;
            this.heal(healPerTick);
            if (this.phaseTwoTicks == 76) {
                this.world.playSound(null, this.getBlockPos(), SoundRegistry.DAY_STALKER_RADIANCE, SoundCategory.HOSTILE, 1f, 1f);
            }
            if (this.phaseTwoTicks == 81) {
                if (!world.isClient) {
                    ParticleNetworking.sendServerParticlePacket((ServerWorld) world, PacketRegistry.DEATH_EXPLOSION_PACKET_ID, this.getBlockPos(), false);
                }
                DayStalkerGoal placeHolder = new DayStalkerGoal(this, 1D, true);
                placeHolder.aoe(4D, 50f, 4f);
            }
            if (this.phaseTwoTicks >= phaseTwoMaxTransitionTicks) {
                this.setPhaseTwo(true);
                this.setInitiatePhaseTwo(false);
                this.setFlying(false);
            }
        }
        this.setRemainingAniTicks(Math.max(this.getRemainingAniTicks() - 1, 0));
        if (this.getRemainingAniTicks() <= 0 && this.shouldWaitAnimation()) {
            this.setWaitAnimation(false);
            this.setAttackAnimation(Attacks.IDLE);
        }
    }

    @Override
    public void tickMovement() {
        super.tickMovement();
        if (this.getParticleState() == 1 && this.world.isClient) {
            Vec3d pos = Vec3d.ofCenter(this.getFlamethrowerTarget());
            double e = pos.getX() - (this.getX());
            double f = pos.getY() + 1D - this.getBodyY(1.0D);
            double g = pos.getZ() - this.getZ();
            double distance = this.squaredDistanceTo(pos);
            double h = Math.sqrt(Math.sqrt(distance)) * 0.5D;
            for (int i = 0; i < 50; i++) {
                double velX = e + this.getRandom().nextGaussian()/2 * h;
                double velY = f + this.getRandom().nextGaussian()/2 * h;
                double velZ = g + this.getRandom().nextGaussian()/2 * h;
                this.world.addParticle(ParticleTypes.FLAME, this.getX(), this.getEyeY(), this.getZ(), velX / 10, velY / 10, velZ / 10);
            }
        }
        if (this.getAttackAnimation().equals(Attacks.FLAMES_EDGE)) {
            float r = this.getFlamesEdgeRadius();
            for (int theta = 0; theta < 360; theta += this.isPhaseTwo() ? 4 : 8) {
                double x0 = this.getX();
                double z0 = this.getZ();
                double x = x0 + r * Math.cos(theta * Math.PI / 180);
                double z = z0 + r * Math.sin(theta * Math.PI / 180);
                if (this.getParticleState() == 2 && this.age % 8 == 0) {
                    this.world.addParticle(ParticleTypes.FLAME, x, this.getBodyY(0.5D), z, 0, 0, 0);
                } else if (this.getParticleState() == 3) {
                    this.world.addParticle(ParticleTypes.SOUL_FIRE_FLAME, x, this.getBodyY(0.5D), z,
                            this.random.nextGaussian()/8, this.random.nextGaussian()/8, this.random.nextGaussian()/8);
                    this.world.addParticle(ParticleTypes.LARGE_SMOKE, x, this.getBodyY(0.5D), z,
                            0, 0.2f, 0);
                }
            }
        }
    }

    /**
     * Sets particle state. 0 is idle or false, 1 is FLAMETHROWER, 2 is FLAMES_EDGE
     * @param type Set the particle state
     */
    public void setParticleState(int type) {
        this.dataTracker.set(SPAWN_PARTICLES_STATE, type);
    }

    public int getParticleState() {
        return this.dataTracker.get(SPAWN_PARTICLES_STATE);
    }

    public void setFlamethrowerTarget(BlockPos pos) {
        this.dataTracker.set(FLAMETHROWER_TARGET, pos);
    }

    public BlockPos getFlamethrowerTarget() {
        return this.dataTracker.get(FLAMETHROWER_TARGET);
    }

    public void setRemainingAniTicks(int ticks) {
        this.dataTracker.set(REMAINING_ANI_TICKS, ticks);
    }

    public int getRemainingAniTicks() {
        return this.dataTracker.get(REMAINING_ANI_TICKS);
    }

    public void setPhaseTwo(boolean bl) {
        this.dataTracker.set(IS_PHASE_2, bl);
    }

    public boolean isPhaseTwo() {
        return this.dataTracker.get(IS_PHASE_2);
    }

    public void setFlying(boolean bl) {
        this.dataTracker.set(IS_FLYING, bl);
    }

    public boolean isFlying() {
        return this.dataTracker.get(IS_FLYING);
    }

    public void setTargetPos(BlockPos pos) {
        this.dataTracker.set(TARGET_POS, pos);
    }

    public BlockPos getTargetPos() {
        return this.dataTracker.get(TARGET_POS);
    }

    public void setChaseTarget(boolean bl) {
        this.dataTracker.set(CHASE_TARGET, bl);
    }

    public boolean shouldChaseTarget() {
        return this.dataTracker.get(CHASE_TARGET);
    }

    public void setWaitAnimation(boolean bl) {
        this.dataTracker.set(WAIT_ANIMATION, bl);
    }

    public boolean shouldWaitAnimation() {
        return this.dataTracker.get(WAIT_ANIMATION);
    }

    public void setFlamesEdgeRadius(float radius) {
        this.dataTracker.set(FLAMES_EDGE_RADIUS, radius);
    }

    public float getFlamesEdgeRadius() {
        return this.dataTracker.get(FLAMES_EDGE_RADIUS);
    }

    @Override
    public boolean damage(DamageSource source, float amount) {
        if (this.isInitiatingPhaseTwo()) {
            return false;
        }
        if (source.isFromFalling()) {
            return false;
        }
        if (this.getAttackAnimation().equals(Attacks.OVERHEAT)) {
            amount = amount * 0.6f;
        }
        if (source.isProjectile()) {
            amount = amount * 0.75f;
        }
        return super.damage(source, amount);
    }

    @Override
    public boolean hasNoGravity() {
        return this.isFlying();
    }

    @Override
    protected void fall(double heightDifference, boolean onGround, BlockState state, BlockPos landedPosition) {
        if (!this.isFlying()) {
            super.fall(heightDifference, onGround, state, landedPosition);
        }
    }

    @Override
    public boolean isClimbing() {
        return !this.isFlying() && super.isClimbing();
    }
}
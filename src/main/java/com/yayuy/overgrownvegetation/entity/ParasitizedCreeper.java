package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.AvoidEntityGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import software.bernie.geckolib.core.animation.RawAnimation;

import javax.annotation.Nullable;

/**
 * 寄生されたクリーパー(G1 段階 0、2026-11-08 組み込み。仕様は Vault「G1 クリーパー(段階 0)」の答え 1・答え 2)。
 * クリーパーだけ攻撃しない。周りの胞子濃度を上げる歩く小さな発生源で、敵からはゆっくり逃げる(11-05 ユーザー)。爆発しない・導火の音なし。
 *
 * <ul>
 *   <li>胞子を漏らす: いつも周りの浮遊胞子を少しずつ上げる({@value #EMIT_EVERY} tick ごとに点の放出、1 日あたり {@value #SPORES_PER_DAY}【仮】)。</li>
 *   <li>吹く: {@value #PUFF_MIN}〜{@value #PUFF_MAX} tick おき【仮】に背の袋が縮んで胞子を吹く(アニメ {@code puff} 1.6 秒、粒は {@value #PUFF_PARTICLE_TICK} tick 目【仮】。
 *   粒は萌芽体の袋と同じ {@code spore_jet})。</li>
 *   <li>逃げる相手は自分を狙うもの(プレイヤーと、G1 を狙う Mob = 鉄のゴーレム・狼など)。おとなしい Mob からは逃げない。走らない(速さは歩きのまま)。逃げ出す距離 8【仮】。</li>
 *   <li>夜は明かり・生き物の多い方へ歩く(共通のまま)。</li>
 * </ul>
 */
public class ParasitizedCreeper extends AbstractParasitizedMonster {

    public static final int ACTION_PUFF = 4;
    static final int EMIT_EVERY = 100;
    static final double SPORES_PER_DAY = 0.5D;
    static final int PUFF_MIN = 80;
    static final int PUFF_MAX = 160;
    static final int PUFF_TICKS = 32;
    static final int PUFF_PARTICLE_TICK = 3;
    static final float FLEE_DISTANCE = 8.0F;

    private static final RawAnimation PUFF_ANIM = RawAnimation.begin().thenPlay("puff");

    private int nextPuff = PUFF_MIN;
    private int puffTick = -1;

    public ParasitizedCreeper(EntityType<? extends ParasitizedCreeper> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 20.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.25D)
                .add(Attributes.FOLLOW_RANGE, 35.0D);
    }

    @Override
    public G1Species species() {
        return G1Species.CREEPER;
    }

    @Override
    protected void registerGoals() {
        super.registerGoals();
        this.goalSelector.addGoal(1, new AvoidEntityGoal<>(this, LivingEntity.class, FLEE_DISTANCE, 1.0D, 1.0D, this::isHunting));
    }

    /** 自分を狙っている相手か(サバイバルのプレイヤーと、自分を標的にしている Mob)。 */
    private boolean isHunting(LivingEntity e) {
        if (e instanceof Player p) {
            return !p.isCreative() && !p.isSpectator();
        }
        return e instanceof Mob m && m.getTarget() == this;
    }

    @Override
    public void tick() {
        super.tick();
        if (!(level() instanceof ServerLevel level) || isRising() || !isAlive()) {
            return;
        }
        if (tickCount % EMIT_EVERY == 0) {
            SporeFieldServer.emitPoint(level, blockPosition(), SPORES_PER_DAY * EMIT_EVERY / 24000.0D);
        }
        if (puffTick >= 0) {
            puffTick++;
            if (puffTick == PUFF_PARTICLE_TICK) {
                // 背の右上の袋から上へ吹く(粒だけ。光らない)
                float yaw = this.yBodyRot * Mth.DEG_TO_RAD;
                double bx = getX() - Mth.sin(yaw) * -0.25D + Mth.cos(yaw) * -0.15D;
                double bz = getZ() + Mth.cos(yaw) * -0.25D + Mth.sin(yaw) * -0.15D;
                level.sendParticles(ModParticles.SPORE_JET.get(), bx, getY() + 1.45D, bz, 14, 0.12D, 0.08D, 0.12D, 0.04D);
            }
            if (puffTick >= PUFF_TICKS) {
                puffTick = -1;
                setAction(ACTION_NONE);
            }
        } else if (--nextPuff <= 0) {
            puffTick = 0;
            setAction(ACTION_PUFF);
            nextPuff = PUFF_MIN + getRandom().nextInt(PUFF_MAX - PUFF_MIN + 1);
        }
    }

    @Nullable
    @Override
    protected RawAnimation actionAnimation(int action) {
        return action == ACTION_PUFF ? PUFF_ANIM : super.actionAnimation(action);
    }

    @Override
    protected SoundEvent getHurtSound(DamageSource source) {
        return SoundEvents.CREEPER_HURT;
    }

    @Override
    protected SoundEvent getDeathSound() {
        return SoundEvents.CREEPER_DEATH;
    }
}

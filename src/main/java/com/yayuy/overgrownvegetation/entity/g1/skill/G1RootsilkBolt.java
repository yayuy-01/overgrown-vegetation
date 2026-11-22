package com.yayuy.overgrownvegetation.entity.g1.skill;

import com.yayuy.overgrownvegetation.registry.ModSounds;
import com.yayuy.overgrownvegetation.block.RootsilkWebBlock;
import com.yayuy.overgrownvegetation.registry.ModG1;
import com.yayuy.overgrownvegetation.registry.ModItems;
import com.yayuy.overgrownvegetation.spore.SporeExposureTracker;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.ThrowableItemProjectile;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * クモの 1 段目が吐く根糸の束(2026-11-13、能力の第 4 回。仕様 Vault「G1 クモ(1 段目)」3 節 (c)【決定 11-09・数は仮】)。
 * ほぼまっすぐ飛ぶ弾(重力 {@value #GRAVITY}。投げ物の 0.03 の 1/3)。光らない。見た目は繊維質のアイテムの絵(新しい絵は作らない)。
 *
 * <ul>
 *   <li><b>当たれば</b>: 絡まって遅くなり跳べない({@link G1Tangle}。鈍足 II + 跳ぶ強さ 0 を {@code slowTicks} tick)+ 暴露量 +{@code dose}
 *   ({@link G1SporeDose} = マスクで防げる)。ダメージは無い(仕様に無い)。<b>盾で受ければ絡まらない</b>
 *   ({@link LivingEntity#isDamageSourceBlocked} で見る)。植物側の Mob・落ちた莢はすり抜ける。</li>
 *   <li><b>外れたら</b>(ブロックに当たる): 当たった面の手前の 1 マスに根糸の巣({@link RootsilkWebBlock#placeAt}。空気・置き換えてよい草花のマスだけ)。</li>
 *   <li>{@value #MAX_AGE} tick 飛んだら消える。</li>
 * </ul>
 */
public class G1RootsilkBolt extends ThrowableItemProjectile {

    static final double GRAVITY = 0.01D;
    static final int MAX_AGE = 60;
    /** 狙いの持ち上げ(ブロック / 水平 1 ブロック)。速さ 1.2・重力 0.01 だと 10 ブロックで 0.35 ほど落ちるぶん。 */
    static final double DROP_PER_BLOCK = 0.035D;

    private double dose;
    private int slowTicks;
    private boolean hitEntity;
    private boolean webPlaced;

    public G1RootsilkBolt(EntityType<? extends G1RootsilkBolt> type, Level level) {
        super(type, level);
    }

    public G1RootsilkBolt(Level level, LivingEntity shooter) {
        super(ModG1.ROOTSILK_BOLT.get(), shooter, level);
    }

    /** 当たったときの量(暴露量 %秒・絡まる長さ tick)。 */
    public G1RootsilkBolt setLoad(double dose, int slowTicks) {
        this.dose = dose;
        this.slowTicks = slowTicks;
        return this;
    }

    @Override
    protected Item getDefaultItem() {
        return ModItems.PLANT_FIBER.get();
    }

    @Override
    protected double getDefaultGravity() {
        return GRAVITY;
    }

    @Override
    protected boolean canHitEntity(Entity target) {
        if (target instanceof G1SeedPod) return false;
        if (target instanceof LivingEntity living && SporeExposureTracker.isPlantSide(living)) return false;
        return super.canHitEntity(target);
    }

    @Override
    public void tick() {
        super.tick();
        if (!level().isClientSide() && tickCount > MAX_AGE) {
            discard();
        }
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        super.onHitEntity(result);
        if (!(result.getEntity() instanceof LivingEntity living) || living == getOwner()) return;
        DamageSource source = getOwner() instanceof LivingEntity owner ? damageSources().mobProjectile(this, owner) : damageSources().thrown(this, null);
        if (living.isDamageSourceBlocked(source)) {
            level().playSound(null, living.getX(), living.getY(), living.getZ(), SoundEvents.SHIELD_BLOCK, SoundSource.HOSTILE, 0.8F, 1.0F);
            return;
        }
        hitEntity = true;
        G1SporeDose.add(living, dose);
        G1Tangle.apply(living, slowTicks);
    }

    /** その相手に当たったことにする(GameTest: 世界に入っていない偽プレイヤーへの当たり)。 */
    public void hitForTests(LivingEntity target) {
        onHitEntity(new EntityHitResult(target));
    }

    @Override
    protected void onHitBlock(BlockHitResult result) {
        super.onHitBlock(result);
        if (!(level() instanceof ServerLevel level)) return;
        BlockPos at = result.getBlockPos().relative(result.getDirection());
        webPlaced = RootsilkWebBlock.placeAt(level, at);
    }

    @Override
    protected void onHit(HitResult result) {
        super.onHit(result);
        if (level() instanceof ServerLevel level) {
            level.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, ModG1.ROOTSILK_WEB.get().defaultBlockState()),
                    getX(), getY(), getZ(), 10, 0.2D, 0.2D, 0.2D, 0.03D);
            if (!webPlaced) {
                // 巣を張ったときは置く音が鳴る。それ以外(相手・置けない所)は湿った繊維の音
                level.playSound(null, getX(), getY(), getZ(), ModSounds.G1_SAC_LUMP_HIT.get(), SoundSource.HOSTILE, 0.8F, 0.8F);
            }
            discard();
        }
    }

    /** 相手に絡まったか(GameTest・確かめ用)。 */
    public boolean hitEntity() {
        return hitEntity;
    }

    /** 根糸の巣を張ったか(GameTest・確かめ用)。 */
    public boolean webPlaced() {
        return webPlaced;
    }
}

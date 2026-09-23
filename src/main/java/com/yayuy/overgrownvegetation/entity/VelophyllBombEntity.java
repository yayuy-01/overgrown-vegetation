package com.yayuy.overgrownvegetation.entity;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.effect.ParasitizedCreeperDetonation;
import com.yayuy.overgrownvegetation.effect.SporeInfectionEffects;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.ParasitizedMob;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;


/**
 * ヴェロフィル({@link VelophyllEntity})が落とす<b>胞子爆弾</b>。
 *
 * <p>落ち方と「ブロックを一切壊さない」方針は攻城戦ボスの {@link SiegeBombEntity} と同じだが、
 * 効き方が 3 点だけ違う(ユーザー指定):
 * <ul>
 *     <li><b>爆風が広い</b> — {@code velophyllBombBlastRadius}(既定 6)。</li>
 *     <li><b>汚染は狭い</b> — {@code velophyllBombCorruptionRadius}(既定 2)の、輪郭が波打った円。</li>
 *     <li><b>ダメージが距離で減衰しない</b> — 爆風は範囲内なら一律
 *         {@code velophyllBombBlastDamage}(既定 12)、爆弾が当たった 1 体だけ
 *         {@code velophyllBombDirectDamage}(既定 45)。</li>
 * </ul>
 *
 * <h2>なぜ {@code Level#explode} を使わないのか</h2>
 * バニラの爆発はダメージが「威力 × 距離の減衰 × 遮蔽」で決まるので、「爆風は一律 12・直撃は 45」を
 * 素直に表現できない。ここでは演出(粒子・音)とノックバックを自前で出し、ダメージも自分で配る。
 *
 * <h2>壊すのは葉だけ</h2>
 * 地形は壊さない —— ただし<b>葉({@link ModTags#BOMB_SHATTERS})だけは例外</b>で、
 * <b>落下中は撃ち抜いて</b>({@link #shatterFoliage})、<b>爆発時は
 * {@code velophyllBombLeafClearRadius} の範囲を薙ぎ払う</b>。
 * これが無いと森では全弾が天蓋の上で爆ぜ、木の下の相手には永久に一発も届かない
 * (ユーザー報告 2026-09-14)。ドロップは出さない(このMODの汚染まわりと同じ方針)。
 *
 * <p>グループ1〜10 の Mob は巻き込まない({@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire}
 * と同じ「汚染側は同士討ちしない」方針)。
 */
public class VelophyllBombEntity extends Entity {

    /** 何にも当たらず落ち続けた場合に自然消滅するまでの tick。 */
    private static final int MAX_LIFETIME_TICKS = 400;
    /** 1 tick あたりの落下加速度。照準({@link VelophyllBombGoal})が落下時間の計算に使う。 */
    public static final double GRAVITY = 0.05D;
    /** 1 tick あたりの空気抵抗。 */
    public static final double DRAG = 0.995D;
    /** 生き物への当たり判定を膨らませる量(ブロック)。 */
    private static final double HIT_INFLATE = 0.35D;
    /** 「何に当たったか」を調べるとき、爆弾の当たり判定の箱を膨らませる量(ブロック)。 */
    private static final double SHATTER_PROBE = 0.2D;
    /** 葉を撃ち抜くとき、通り道として開ける穴の半径(ブロック)。 */
    private static final double SHATTER_HOLE_RADIUS = 1.4D;

    /**
     * 爆弾の大きさ(1.0 = ヴェロフィルが落とす通常の爆弾)。威力・各種半径・見た目がまとめてこの倍率になる。
     *
     * <p>1.0 未満になるのは、機関砲台({@code block/GunTurretBlockEntity})が<b>増援を運ぶ胞子弾</b>を
     * 撃ち落としたときに散る小さな爆弾({@link com.yayuy.overgrownvegetation.siege.ShellTracker#intercept})だけ。
     * 描画にも使うので同期する。
     */
    private static final EntityDataAccessor<Float> DATA_SIZE =
            SynchedEntityData.defineId(VelophyllBombEntity.class, EntityDataSerializers.FLOAT);

    /** 描画時の回転に使う、生成時に決まる乱数の位相。 */
    private float spinSeed;

    /** 落とした本人。巻き込み判定から外すためだけに持つ(消えていても爆発はする)。 */
    @Nullable
    private LivingEntity owner;

    /**
     * 既に爆発したか。{@link #discard()} は爆発処理を抜けた後にしか効かないので、
     * {@code isRemoved()} では処理中の再入を防げない。
     */
    private boolean exploded;

    public VelophyllBombEntity(EntityType<? extends VelophyllBombEntity> type, Level level) {
        super(type, level);
        this.blocksBuilding = false;
    }

    public VelophyllBombEntity(Level level, Vec3 pos, Vec3 velocity, @Nullable LivingEntity owner) {
        this(level, pos, velocity, owner, 1.0F);
    }

    /** @param size 大きさの倍率(1.0 = 通常。威力・半径・見た目がまとめてこの倍率になる) */
    public VelophyllBombEntity(Level level, Vec3 pos, Vec3 velocity, @Nullable LivingEntity owner, float size) {
        this(ModEntities.VELOPHYLL_BOMB.get(), level);
        this.setPos(pos.x, pos.y, pos.z);
        this.setDeltaMovement(velocity);
        this.owner = owner;
        this.spinSeed = level.random.nextFloat() * 360.0F;
        this.entityData.set(DATA_SIZE, Math.max(0.05F, size));
    }

    /** 描画用: 生成時に決まった回転の位相。 */
    public float spinSeed() {
        return this.spinSeed;
    }

    /** 大きさの倍率(1.0 = 通常)。威力・各種半径・描画の大きさがこの倍率になる。 */
    public float size() {
        return this.entityData.get(DATA_SIZE);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SIZE, 1.0F);
    }

    @Override
    public void tick() {
        super.tick();

        Vec3 velocity = this.getDeltaMovement().add(0.0D, -GRAVITY, 0.0D).scale(DRAG);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);

        if (this.level().isClientSide()) {
            this.level().addParticle(ModParticles.OVERGROWN_SPORE.get(),
                    this.getX(), this.getY() + this.getBbHeight() * 0.5D, this.getZ(),
                    0.0D, 0.0D, 0.0D);
            return;
        }

        if (this.tickCount > MAX_LIFETIME_TICKS || this.getY() < this.level().getMinBuildHeight() - 8) {
            this.discard();
            return;
        }

        if (this.onGround() || this.verticalCollision || this.horizontalCollision) {
            // 葉に当たっただけなら、砕いて落下を続ける(森で天蓋に防がれ続けないように)。
            if (shatterFoliage((ServerLevel) this.level())) {
                return;
            }
            detonate((ServerLevel) this.level(), null);
            return;
        }
        LivingEntity struck = firstStruck();
        if (struck != null) {
            detonate((ServerLevel) this.level(), struck);
        }
    }

    /**
     * 行く手を塞いでいるのが<b>葉だけ</b>なら砕いて通す(ユーザー要望 2026-09-14:
     * 「木の葉に爆弾が当たった時、木の葉が消えるように」)。
     *
     * <p>これが無いと、森ではすべての爆弾が天蓋の上で爆ぜてしまい、木の下にいる相手には
     * <b>永久に一発も届かない</b>。砕けるのは {@link ModTags#BOMB_SHATTERS} のブロックだけで、
     * 幹や建物に当たれば今までどおりその場で爆発する。
     *
     * @return 砕いて落下を続ける場合 {@code true}。硬いものに当たっていれば {@code false}(= 爆発)。
     */
    private boolean shatterFoliage(ServerLevel level) {
        AABB box = this.getBoundingBox().inflate(SHATTER_PROBE);
        boolean foliage = false;
        for (BlockPos pos : BlockPos.betweenClosed(
                BlockPos.containing(box.minX, box.minY, box.minZ),
                BlockPos.containing(box.maxX, box.maxY, box.maxZ))) {
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(ModTags.BOMB_SHATTERS)) {
                foliage = true;
            } else if (!state.getCollisionShape(level, pos).isEmpty()) {
                // 葉以外の硬いものが混ざっている。撃ち抜かずにここで爆発する。
                return false;
            }
        }
        if (!foliage) {
            return false;
        }
        // 通り道が塞がったままだと次の tick も同じ所で止まるので、少しだけ広めに開ける。
        // 触れた葉 1 つずつではなく<b>自分の周りを 1 回</b>で済ませる(そのほうが遥かに安い)。
        clearFoliageAround(level, this.blockPosition(), SHATTER_HOLE_RADIUS);
        return true;
    }

    /** {@code center} の周り半径 {@code radius} の葉を、ドロップ無しで壊す。 */
    private static void clearFoliageAround(ServerLevel level, BlockPos center, double radius) {
        int r = Mth.ceil(radius);
        double radiusSq = radius * radius;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > radiusSq) {
                        continue;
                    }
                    pos.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(pos) || !level.getBlockState(pos).is(ModTags.BOMB_SHATTERS)) {
                        continue;
                    }
                    // false = ドロップ無し(このMODの汚染まわりと同じ方針)。破壊の演出は出る。
                    level.destroyBlock(pos, false);
                }
            }
        }
    }

    /** 爆弾の当たり判定に触れている、巻き込んでよい生き物のうち最初の 1 体。 */
    @Nullable
    private LivingEntity firstStruck() {
        AABB box = this.getBoundingBox().inflate(HIT_INFLATE);
        for (LivingEntity entity : this.level().getEntitiesOfClass(LivingEntity.class, box)) {
            if (canHarm(entity)) {
                return entity;
            }
        }
        return null;
    }

    /**
     * ダメージを与えてよい相手か。
     * <ul>
     *     <li>プレイヤー(クリエイティブ・観戦を除く)</li>
     *     <li>グループ1〜10 に属していない生き物({@link Predation#isInvaderTarget} と同じ線引き)</li>
     *     <li><b>グループ1〜3</b> —— グループ4 の<b>獲物</b>({@link #isPrey})。2026-09-17 に追加。</li>
     * </ul>
     * 落とした本人と、ほかのグループ4・5〜7 は除く。
     *
     * <h2>獲物を通すようにした経緯(ユーザー報告 2026-09-17)</h2>
     * 「同士討ちを防ぐ仕様で、爆弾がグループ2〜3 にダメージを与えられていなかった」。
     * グループ4 がグループ1〜3 を狩る関係は 2026-09-15 に入れてあり、
     * {@link com.yayuy.overgrownvegetation.phase.MobGroupFriendlyFire} にも例外が通っている ——
     * <b>止めていたのはここ</b>だった。この足切りは「グループに属していれば味方」という
     * 捕食より前の線引きのままで、爆風も直撃も獲物へ一切入らなかった。
     */
    private boolean canHarm(LivingEntity entity) {
        if (entity == this.owner || !entity.isAlive()) {
            return false;
        }
        if (entity instanceof net.minecraft.world.entity.player.Player player) {
            return !player.isCreative() && !player.isSpectator();
        }
        // 獲物の判定を先に通す —— グループ1 は ParasitizedMob なので、下の除外に先に当たってしまう。
        if (isPrey(entity)) {
            return true;
        }
        return !(entity instanceof ParasitizedMob)
                && !(entity instanceof BlightFernEntity)
                && MobGroup.of(entity) == null; // Entity 版: 寄生植物装備のプレイヤーは味方
    }

    /**
     * 落とした本人(グループ4)の<b>獲物</b>か。
     *
     * <p>本人が分からないとき(リログで参照が切れたとき)も獲物として扱う ——
     * <b>この爆弾を落とすのはグループ4 しかいない</b>(ヴェロフィルの爆撃と
     * スカトゥリウムの迫撃砲)ので、襲う側を確かめる意味がない。
     */
    private boolean isPrey(LivingEntity entity) {
        return this.owner != null
                ? Predation.isInvaderPrey(this.owner, entity)
                : Predation.isInvaderPreyGroup(entity);
    }

    /**
     * @param direct 爆弾が直接ぶつかった相手({@code null} なら地形に当たった)。
     *               この 1 体だけが {@code velophyllBombDirectDamage} を追加で受ける。
     */
    private void detonate(ServerLevel level, @Nullable LivingEntity direct) {
        if (this.exploded) {
            return;
        }
        this.exploded = true;
        Vec3 center = this.position();

        applyBlast(level, center, direct);
        // 忌避灯(2026-09-19): 近くで炸裂したら灯りを胞子と蔦で覆って止める。
        com.yayuy.overgrownvegetation.defense.RepellentLamps.coverNear(level, center,
                com.yayuy.overgrownvegetation.data.DesignData.defense().lampCoverRadius() * size());
        // 機関砲台(2026-09-21): 同じく胞子で覆って止める。スカトゥリウムの迫撃砲はこれが狙い。
        com.yayuy.overgrownvegetation.defense.GunTurrets.corruptNear(level, center,
                com.yayuy.overgrownvegetation.data.DesignData.defense().turretCorruptRadius() * size());
        // 爆風で天蓋に穴を開ける。次に落ちてくる爆弾がそこを通れるようになる。
        double leafRadius = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll").leafClearRadius() * size();
        if (leafRadius > 0.0D) {
            clearFoliageAround(level, this.blockPosition(), leafRadius);
        }
        corrupt(level, center);
        playEffects(level, center);
        this.discard();
    }

    /** 爆風のダメージ・ノックバック・胞子感染。距離では減衰しない。 */
    private void applyBlast(ServerLevel level, Vec3 center, @Nullable LivingEntity direct) {
        // 爆弾の数値は mobs.json の velophyll.bomb(mod マネージャーの Mob ステータス)。
        com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll");
        // 小さい爆弾(機関砲台が撃ち落とした胞子弾の破片)は、威力も半径もそのぶん小さい。
        float size = size();
        double radius = cfg.blastRadius() * size;
        float blast = (float) cfg.blastDamage() * size;
        float directBonus = (float) cfg.directDamage() * size;
        double knockback = cfg.knockback() * size;
        double infectRadius = cfg.infectRadius() * size;
        DamageSource source = ModDamageTypes.velophyllBomb(level, this, this.owner);

        if (direct != null && directBonus > 0.0F) {
            direct.hurt(source, directBonus);
        }
        if (radius <= 0.0D) {
            return;
        }
        double radiusSq = radius * radius;
        double infectSq = infectRadius * infectRadius;
        AABB box = new AABB(center.x - radius, center.y - radius, center.z - radius,
                center.x + radius, center.y + radius, center.z + radius);
        for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box)) {
            double distSq = entity.distanceToSqr(center);
            if (distSq > radiusSq) {
                continue;
            }
            if (infectRadius > 0.0D && distSq <= infectSq
                    && AbstractOvergrownInvaderEntity.isSporeVictim(this.owner, entity)) {
                SporeInfectionEffects.infect(entity, 0);
            }
            if (!canHarm(entity)) {
                continue;
            }
            // 直撃した相手は上で 45 を受けているので、爆風ぶんは重ねない。
            if (entity != direct && blast > 0.0F) {
                entity.hurt(source, blast);
            }
            if (knockback > 0.0D) {
                Vec3 push = entity.position().subtract(center);
                double length = push.length();
                if (length > 1.0E-4D) {
                    // 縁でも真上でもなく、中心から外向きに押す。近いほど強い。
                    double strength = knockback * (1.0D - length / radius);
                    entity.push(push.x / length * strength,
                            push.y / length * strength + strength * 0.3D,
                            push.z / length * strength);
                }
            }
        }
    }

    /** 着弾点の地表を円形に汚染する。輪郭は波打たせる(半径は爆風よりずっと狭い)。 */
    private void corrupt(ServerLevel level, Vec3 center) {
        com.yayuy.overgrownvegetation.data.MobStat.Bomb cfg = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll");
        double radius = cfg.corruptionRadius() * size();
        if (radius <= 0.0D
                || SporeDeviceData.inhibits(level, this.blockPosition(), SporeDeviceData.Inhibit.CORRUPTION)) {
            return;
        }
        ParasitizedCreeperDetonation.corruptSurfaceDisc(level, center, radius,
                cfg.corruptionNoise());
    }

    private void playEffects(ServerLevel level, Vec3 center) {
        double radius = com.yayuy.overgrownvegetation.data.MobStats.bomb("velophyll").blastRadius() * size();
        level.sendParticles(ParticleTypes.EXPLOSION_EMITTER, center.x, center.y, center.z, 1,
                0.0D, 0.0D, 0.0D, 0.0D);
        // 爆風の広さが目で分かるように、胞子を半径いっぱいへ撒く。
        level.sendParticles(ModParticles.OVERGROWN_SPORE.get(), center.x, center.y + 0.5D, center.z,
                80, radius * 0.4D, radius * 0.25D, radius * 0.4D, 0.05D);
        level.sendParticles(ParticleTypes.SPORE_BLOSSOM_AIR, center.x, center.y + 0.8D, center.z,
                30, radius * 0.4D, radius * 0.3D, radius * 0.4D, 0.0D);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 3.0F, 0.8F);
        level.playSound(null, center.x, center.y, center.z,
                SoundEvents.SCULK_CATALYST_BLOOM, SoundSource.HOSTILE, 1.4F, 0.7F);
    }

    /**
     * <b>爆弾は他の爆発では一切ダメージを受けない。</b>
     * 同時に落ちている爆弾どうしが誘爆し合って {@link StackOverflowError} になるのを根元で断つ
     * ({@link SiegeBombEntity#ignoreExplosion} と同じ理由)。
     */
    @Override
    public boolean ignoreExplosion(net.minecraft.world.level.Explosion explosion) {
        return true;
    }

    /** 落ちてくる爆弾は矢などで撃ち落とせる。 */
    @Override
    public boolean isPickable() {
        return !this.isRemoved();
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level() instanceof ServerLevel serverLevel && !this.isRemoved() && !this.exploded) {
            detonate(serverLevel, null);
            return true;
        }
        return false;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.spinSeed = tag.getFloat("SpinSeed");
        // 古いセーブには Size が無い(= 通常の大きさ)。
        this.entityData.set(DATA_SIZE, tag.contains("Size") ? Math.max(0.05F, tag.getFloat("Size")) : 1.0F);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putFloat("SpinSeed", this.spinSeed);
        tag.putFloat("Size", size());
    }

    /** 上空から落ちてくるので、遠くからでも見えてほしい。 */
    @Override
    public boolean shouldRenderAtSqrDistance(double distanceSq) {
        return distanceSq < 192.0D * 192.0D;
    }

    @Override
    public boolean isPushable() {
        return false;
    }
}

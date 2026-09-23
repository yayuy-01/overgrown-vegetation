package com.yayuy.overgrownvegetation.siege;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.SiegeBombEntity;
import com.yayuy.overgrownvegetation.network.ShellFiredPayload;
import com.yayuy.overgrownvegetation.network.ShellImpactPayload;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;

/**
 * 砲台が撃った<b>砲弾</b>の追跡(ディメンション単位の {@link SavedData})。
 *
 * <h2>砲弾はエンティティではない</h2>
 * <p>砲弾は 1000 ブロック先のボスへ向かって未ロードのチャンクの上空を飛ぶ。エンティティにすると
 * 「移動する砲弾に追従してチャンクを強制ロードする」ことになり、未生成チャンクの生成が毎 tick 走って
 * 確実にスタッターする。そこで砲弾は「発射位置・初速・発射時刻」だけのデータとして持ち、
 * 位置は {@link CannonBallistics#positionAt} の放物線の式から求める。クライアントには
 * {@link ShellFiredPayload} で同じパラメータを配り、描画も同じ式から出す(座標同期なし)。
 *
 * <h2>毎 tick の判定</h2>
 * <ol>
 *   <li><b>ボス</b>: 攻城戦が進行中なら、当たり判定パーツ 28 個の箱({@link SiegeData#partBoxes})と
 *       1 tick ぶんの線分の交差。実エンティティが居ても居なくても同じ式で判定し、命中したら
 *       {@link SiegeData#hurt(ServerLevel, float, DamageSource)} へ(防御しきい値あり)。</li>
 *   <li><b>地形</b>: 線分の両端が<b>ロード済みチャンク</b>にあるときだけ {@link Level#clip}。
 *       未ロードの遠方では地形判定しない(誰も居ないし、そこにブロックは存在しない)。</li>
 *   <li><b>生物</b>: ロード済みの範囲で線分の近くの生物に直撃。発射直後の数 tick は砲台のそばの
 *       射手を巻き込まないよう判定しない。</li>
 * </ol>
 * 何にも当たらない砲弾は {@code cannonShellLifetimeTicks} で消える。
 */
public final class ShellTracker extends SavedData {

    private static final String ID = "overgrown_vegetation_shells";

    public static final SavedData.Factory<ShellTracker> FACTORY =
            new SavedData.Factory<>(ShellTracker::new, (tag, registries) -> load(tag), null);

    /** 弾の種類: 砲台が撃った砲弾。 */
    public static final int KIND_CANNON = 0;
    /**
     * 弾の種類: ボスが曲射してくる<b>胞子爆弾</b>。同じ放物線の仕組みに相乗りしているが、
     * 重力は {@code siegeMortarGravity}(砲弾よりずっと強い = 山なり)で、ボスには当たらず、
     * 着弾すると爆撃の {@link SiegeBombEntity} とまったく同じ爆発と地表の汚染を起こす。
     */
    public static final int KIND_SPORE = 1;

    /** 着弾の種類。 */
    public static final int IMPACT_EXPIRE = 0;
    public static final int IMPACT_TERRAIN = 1;
    public static final int IMPACT_ENTITY = 2;
    public static final int IMPACT_BOSS = 3;
    /** 機関砲台に空中で撃ち落とされた({@link #intercept})。 */
    public static final int IMPACT_INTERCEPT = 4;

    /** 発射直後、この tick 数のあいだは生物への直撃を判定しない(砲台のそばの射手を撃たないため)。 */
    private static final int ENTITY_GRACE_TICKS = 3;

    /** 飛行中の砲弾 1 発。 */
    public static final class Shell {
        public final int id;
        /** {@link #KIND_CANNON} / {@link #KIND_SPORE}。 */
        public final int kind;
        public final Vec3 origin;
        public final Vec3 velocity;
        public final double gravity;
        public final long startGameTime;
        public final float bossDamage;
        public final float entityDamage;
        /**
         * 着弾地点に湧かせるグループ4 の数({@link SporeBombReinforcements})。0 なら何も湧かない。
         *
         * <p>普段の曲射と<b>砲撃への反撃</b>({@link SiegeData#onShelled})を区別しているのはこの値だけ —
         * 弾そのものの飛び方・着弾の爆発はまったく同じで、増援を連れてくるかどうかだけが違う。
         */
        public final int reinforcements;

        Shell(int id, int kind, Vec3 origin, Vec3 velocity, double gravity, long startGameTime,
              float bossDamage, float entityDamage, int reinforcements) {
            this.id = id;
            this.kind = kind;
            this.origin = origin;
            this.velocity = velocity;
            this.gravity = gravity;
            this.startGameTime = startGameTime;
            this.bossDamage = bossDamage;
            this.entityDamage = entityDamage;
            this.reinforcements = reinforcements;
        }

        public Vec3 positionAt(double t) {
            return CannonBallistics.positionAt(origin, velocity, gravity, t);
        }

        CompoundTag save() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("id", id);
            tag.putInt("kind", kind);
            tag.putDouble("ox", origin.x);
            tag.putDouble("oy", origin.y);
            tag.putDouble("oz", origin.z);
            tag.putDouble("vx", velocity.x);
            tag.putDouble("vy", velocity.y);
            tag.putDouble("vz", velocity.z);
            tag.putDouble("gravity", gravity);
            tag.putLong("start", startGameTime);
            tag.putFloat("bossDamage", bossDamage);
            tag.putFloat("entityDamage", entityDamage);
            tag.putInt("reinforcements", reinforcements);
            return tag;
        }

        static Shell load(CompoundTag tag) {
            return new Shell(tag.getInt("id"), tag.getInt("kind"),
                    new Vec3(tag.getDouble("ox"), tag.getDouble("oy"), tag.getDouble("oz")),
                    new Vec3(tag.getDouble("vx"), tag.getDouble("vy"), tag.getDouble("vz")),
                    tag.getDouble("gravity"), tag.getLong("start"),
                    tag.getFloat("bossDamage"), tag.getFloat("entityDamage"),
                    tag.getInt("reinforcements"));
        }
    }

    private final List<Shell> shells = new ArrayList<>();
    private int nextId = 1;

    private ShellTracker() {
    }

    public static ShellTracker get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    private static ShellTracker load(CompoundTag tag) {
        ShellTracker tracker = new ShellTracker();
        tracker.nextId = Math.max(1, tag.getInt("nextId"));
        ListTag list = tag.getList("shells", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            tracker.shells.add(Shell.load(list.getCompound(i)));
        }
        return tracker;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("nextId", nextId);
        ListTag list = new ListTag();
        for (Shell shell : shells) {
            list.add(shell.save());
        }
        tag.put("shells", list);
        return tag;
    }

    public List<Shell> shells() {
        return shells;
    }

    /**
     * 砲弾を撃つ。発射位置・初速を確定してリストに入れ、ディメンション内の全プレイヤーへ
     * {@link ShellFiredPayload} を送る。
     *
     * @return 砲弾の識別子
     */
    public int fire(ServerLevel level, Vec3 origin, Vec3 velocity) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        return add(level, KIND_CANNON, origin, velocity, cfg.cannonGravity.get(),
                cfg.cannonShellDamage.get().floatValue(), cfg.cannonShellEntityDamage.get().floatValue(), 0);
    }

    /**
     * ボスの<b>胞子爆弾</b>を曲射する。砲弾とまったく同じ仕組みに乗せるので、山なりの弾道が
     * そのままプレイヤーからも見える。ボスには当たらず、着弾すると爆撃と同じ爆発と地表の汚染を起こす。
     *
     * @param reinforcements 着弾地点に湧かせるグループ4 の数(0 = 湧かせない)。
     *                       砲撃への反撃({@link SiegeData#onShelled})だけがここに正の数を渡す。
     */
    public int fireSpore(ServerLevel level, Vec3 origin, Vec3 velocity, double gravity, float directDamage,
                         int reinforcements) {
        return add(level, KIND_SPORE, origin, velocity, gravity, 0.0F, directDamage, reinforcements);
    }

    private int add(ServerLevel level, int kind, Vec3 origin, Vec3 velocity, double gravity,
                    float bossDamage, float entityDamage, int reinforcements) {
        long now = level.getGameTime();
        Shell shell = new Shell(nextId++, kind, origin, velocity, gravity, now, bossDamage, entityDamage,
                reinforcements);
        shells.add(shell);
        setDirty();
        PacketDistributor.sendToPlayersInDimension(level,
                new ShellFiredPayload(shell.id, kind, origin, velocity, (float) gravity, now));
        return shell.id;
    }

    // ------------------------------------------------------------------ 迎撃(機関砲台)

    /** 迎撃の候補 1 発。{@code pos} はいまの位置、{@code reinforcements} は増援を運んでいるか。 */
    public record Incoming(int id, Vec3 pos, boolean reinforcements) {
    }

    /**
     * {@code center} から半径 {@code radius} 以内を飛んでいる<b>胞子弾</b>({@link #KIND_SPORE})を
     * <b>近い順</b>に。砲弾({@link #KIND_CANNON} = プレイヤーが撃った弾)は対象にしない。
     *
     * <p>機関砲台({@code block/GunTurretBlockEntity})の目標探しから呼ぶ。位置はエンティティのように
     * 持っていないので、その弾の放物線の式から<b>いまの位置を計算して</b>比べる。
     */
    public List<Incoming> sporeShellsNear(ServerLevel level, Vec3 center, double radius) {
        if (shells.isEmpty()) {
            return List.of();
        }
        long now = level.getGameTime();
        double radiusSq = radius * radius;
        List<Incoming> found = new ArrayList<>();
        for (Shell shell : shells) {
            if (shell.kind != KIND_SPORE) {
                continue;
            }
            Vec3 at = shell.positionAt(Math.max(0L, now - shell.startGameTime));
            if (at.distanceToSqr(center) <= radiusSq) {
                found.add(new Incoming(shell.id, at, shell.reinforcements > 0));
            }
        }
        found.sort(java.util.Comparator.comparingDouble(in -> in.pos().distanceToSqr(center)));
        return found;
    }

    /** {@code id} の弾のいまの位置。もう飛んでいなければ {@code null}。 */
    @Nullable
    public Vec3 shellPosition(ServerLevel level, int id) {
        Shell shell = shell(id);
        return shell == null ? null : shell.positionAt(Math.max(0L, level.getGameTime() - shell.startGameTime));
    }

    /** {@code id} の弾。もう飛んでいなければ {@code null}。 */
    @Nullable
    public Shell shell(int id) {
        for (Shell shell : shells) {
            if (shell.id == id) {
                return shell;
            }
        }
        return null;
    }

    /**
     * <b>空中で撃ち落とす</b>(機関砲台)。弾はその場で消え、着弾の爆発も汚染も起こさない。
     *
     * <p>ただし<b>増援を運んでいた弾</b>({@link Shell#reinforcements} > 0)は、割れた拍子に
     * <b>小さな胞子爆弾をばらまく</b>(ユーザー指定 2026-09-21)——
     * 数と大きさは {@code defense.json} の {@code gunTurret.clusterBomblets} /
     * {@code clusterBombletSize}。落とした増援そのものは湧かない(運んでいた弾ごと潰したので)。
     *
     * @return 撃ち落とせたら true(もう飛んでいなければ false)
     */
    public boolean intercept(ServerLevel level, int id) {
        Shell shell = shell(id);
        if (shell == null) {
            return false;
        }
        Vec3 at = shell.positionAt(Math.max(0L, level.getGameTime() - shell.startGameTime));
        shells.remove(shell);
        setDirty();

        if (shell.reinforcements > 0) {
            scatterBomblets(level, at);
        }
        level.sendParticles(net.minecraft.core.particles.ParticleTypes.EXPLOSION, at.x, at.y, at.z, 1,
                0.0D, 0.0D, 0.0D, 0.0D);
        level.playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.GENERIC_EXPLODE.value(),
                net.minecraft.sounds.SoundSource.BLOCKS, 2.0F, 1.6F);
        impact(level, shell, at, IMPACT_INTERCEPT, -1);
        return true;
    }

    /** 割れた胞子弾から小さな爆弾を四方へ散らす。 */
    private static void scatterBomblets(ServerLevel level, Vec3 at) {
        int count = com.yayuy.overgrownvegetation.data.DesignData.defense().turretClusterBomblets();
        if (count <= 0) {
            return;
        }
        float size = (float) com.yayuy.overgrownvegetation.data.DesignData.defense().turretClusterBombletSize();
        double baseAngle = level.random.nextDouble() * Math.PI * 2.0D;
        for (int i = 0; i < count; i++) {
            // 等間隔に振り分けてから少しだけ乱す(まとまって落ちると 1 発と変わらないので)。
            double angle = baseAngle + (Math.PI * 2.0D * i) / count + (level.random.nextDouble() - 0.5D) * 0.4D;
            double speed = 0.30D + level.random.nextDouble() * 0.15D;
            Vec3 velocity = new Vec3(Math.cos(angle) * speed, 0.12D, Math.sin(angle) * speed);
            level.addFreshEntity(new com.yayuy.overgrownvegetation.entity.VelophyllBombEntity(
                    level, at, velocity, null, size));
        }
    }

    /** {@link SiegeTicker} から毎 tick。 */
    public void tick(ServerLevel level) {
        if (shells.isEmpty()) {
            return;
        }
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        long now = level.getGameTime();
        int lifetime = cfg.cannonShellLifetimeTicks.get();

        // ボスの当たり判定パーツの箱はこの tick で 1 回だけ求める(砲弾が何発あっても共通)。
        SiegeData siege = level.dimension() == Level.OVERWORLD ? SiegeData.get(level) : null;
        List<AABB> bossBoxes = siege != null ? siege.partBoxes(level) : null;

        Iterator<Shell> iterator = shells.iterator();
        boolean changed = false;
        while (iterator.hasNext()) {
            Shell shell = iterator.next();
            long age = now - shell.startGameTime;
            if (age <= 0L) {
                continue;
            }
            Vec3 from = shell.positionAt(age - 1);
            Vec3 to = shell.positionAt(age);
            changed = true;

            if (age > lifetime || to.y < level.getMinBuildHeight() - 64) {
                iterator.remove();
                impact(level, shell, to, IMPACT_EXPIRE, -1);
                continue;
            }

            // 1. ボス(ボス自身が撃った胞子爆弾は当たらない)。
            if (bossBoxes != null && shell.kind == KIND_CANNON) {
                int hitPart = -1;
                double best = Double.MAX_VALUE;
                Vec3 hitPos = null;
                for (int i = 0; i < bossBoxes.size(); i++) {
                    AABB box = bossBoxes.get(i);
                    Vec3 hit;
                    if (box.contains(from)) {
                        hit = from;
                    } else {
                        Optional<Vec3> clip = box.clip(from, to);
                        if (clip.isEmpty()) {
                            continue;
                        }
                        hit = clip.get();
                    }
                    double d = hit.distanceToSqr(from);
                    if (d < best) {
                        best = d;
                        hitPart = i;
                        hitPos = hit;
                    }
                }
                if (hitPart >= 0) {
                    iterator.remove();
                    siege.hurt(level, shell.bossDamage, ModDamageTypes.siegeShell(level));
                    // 撃たれたら撃ち返す — 狙点は砲弾が出てきた場所、つまり砲台そのもの。
                    siege.onShelled(level, shell.origin);
                    siege.sync(level);
                    impact(level, shell, hitPos, IMPACT_BOSS, hitPart);
                    continue;
                }
            }

            // 2. 地形(ロード済みのときだけ)。
            boolean loaded = level.hasChunkAt(BlockPos.containing(from)) && level.hasChunkAt(BlockPos.containing(to));
            if (loaded) {
                BlockHitResult hit = level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER,
                        ClipContext.Fluid.NONE, CollisionContext.empty()));
                if (hit.getType() == HitResult.Type.BLOCK) {
                    iterator.remove();
                    detonate(level, shell, hit.getLocation());
                    impact(level, shell, hit.getLocation(), IMPACT_TERRAIN, -1);
                    continue;
                }

                // 3. 生物への直撃。
                if (age > ENTITY_GRACE_TICKS) {
                    Entity victim = findVictim(level, from, to);
                    if (victim != null) {
                        iterator.remove();
                        if (shell.entityDamage > 0.0F) {
                            // 胞子爆弾の直撃は「ボスの攻撃」、砲弾の直撃はプレイヤー側の攻撃。
                            victim.hurt(shell.kind == KIND_SPORE
                                    ? ModDamageTypes.siegeBomb(level) : ModDamageTypes.siegeShell(level),
                                    shell.entityDamage);
                        }
                        Vec3 at = victim.getBoundingBox().getCenter();
                        detonate(level, shell, at);
                        impact(level, shell, at, IMPACT_ENTITY, -1);
                        continue;
                    }
                }
            }
        }
        if (changed) {
            setDirty();
        }
    }

    @Nullable
    private static Entity findVictim(ServerLevel level, Vec3 from, Vec3 to) {
        AABB search = new AABB(from, to).inflate(1.0D);
        Entity best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Entity entity : level.getEntities((Entity) null, search,
                e -> e instanceof LivingEntity && e.isAlive() && !(e instanceof DorgaArchelausTitanicaEntity))) {
            AABB box = entity.getBoundingBox().inflate(0.3D);
            Vec3 hit;
            if (box.contains(from)) {
                hit = from;
            } else {
                Optional<Vec3> clip = box.clip(from, to);
                if (clip.isEmpty()) {
                    continue;
                }
                hit = clip.get();
            }
            double d = hit.distanceToSqr(from);
            if (d < bestDistance) {
                bestDistance = d;
                best = entity;
            }
        }
        return best;
    }

    /**
     * 着弾。砲弾は砲弾の爆発、胞子爆弾は爆撃とまったく同じ爆発 + 地表の汚染。
     *
     * <p>砲撃への反撃で撃たれた胞子爆弾({@link Shell#reinforcements} > 0)は、そのうえで
     * <b>着弾地点にグループ4 の増援</b>を置く。爆発を先に起こしてから湧かせるので、湧いた個体が
     * 自分を連れてきた爆風に巻かれることはない。
     */
    private static void detonate(ServerLevel level, Shell shell, Vec3 at) {
        if (shell.kind == KIND_SPORE) {
            SiegeBombEntity.detonateAt(level, at);
            if (shell.reinforcements > 0) {
                SporeBombReinforcements.spawn(level, at, shell.reinforcements);
            }
        } else {
            explodeAt(level, at);
        }
    }

    /** 着弾の爆発(ブロックは壊さない)と、設定されていれば小さなクレーター。 */
    private static void explodeAt(ServerLevel level, Vec3 at) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        float power = cfg.cannonShellExplosionPower.get().floatValue();
        if (power > 0.0F) {
            level.explode(null, ModDamageTypes.siegeShell(level), null, at.x, at.y, at.z, power, false,
                    Level.ExplosionInteraction.NONE);
        }
        int crater = cfg.cannonShellCraterRadius.get();
        if (crater > 0) {
            SiegeBombEntity.carveMinimalCrater(level, BlockPos.containing(at), crater,
                    cfg.siegeBombCraterMaxResistance.get().floatValue());
        }
    }

    private static void impact(ServerLevel level, Shell shell, Vec3 at, int kind, int part) {
        PacketDistributor.sendToPlayersInDimension(level, new ShellImpactPayload(shell.id, at, kind, part));
    }
}

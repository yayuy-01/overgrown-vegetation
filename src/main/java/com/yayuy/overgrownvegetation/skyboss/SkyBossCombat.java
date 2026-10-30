package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 空の巨大生物の当たり判定(サーバー、2026-10-18 夕方)。毎 tick、エンティティが動く<b>前</b>({@link LevelTickEvent.Pre})に今の姿勢({@link SkyBossPose})を出し、
 * <ul>
 *   <li><b>飛び道具</b>: 読み込まれている飛び道具のうちボスの近くのものについて、この tick に進む線分(今の位置 → + 速さ。バニラが当たりを調べる線分と同じ)と形の当たりを調べ、
 *       当たる所に受け手({@link SkyBossHitboxEntity#KIND_SHOT})を置く。飛び道具はそのあと自分の tick で受け手に当たり、ダメージはバニラが計算する。</li>
 *   <li><b>剣</b>: 近くのプレイヤーごとに、視線と形の交わる所(届く距離の中)へ受け手({@link SkyBossHitboxEntity#KIND_MELEE})を置く。</li>
 *   <li><b>ぶつかる</b>: 指定(10-18)「体にぶつかるとダメージを受けて弾かれる」→ プレイヤーが形にめり込んだら、ダメージを与えて外へはじく
 *       ({@link SkyBossTuning#CONTACT_DAMAGE}・{@link SkyBossTuning#CONTACT_PUSH}、続けては {@link SkyBossTuning#CONTACT_COOLDOWN} tick あける)。</li>
 *   <li><b>ボスバー</b>と、近くを飛ばれたら戦いに入る(20 tick ごと)。</li>
 *   <li><b>放電</b>(10-19): 放電の 30 秒の間、体の表面から {@link SkyBossTuning#DISCHARGE_RADIUS} の中のプレイヤーへ、いちばん近い体の所から雷が飛ぶ
 *       (近いほど間が短い。{@link SkyBossTuning#ARC_DAMAGE}、バニラの雷のダメージ)。飛んだ雷は {@link com.yayuy.overgrownvegetation.network.SkyBossArcPayload} で近くの全員へ。</li>
 * </ul>
 * 形の元({@link SkyBossShapes})が無い(公開のソースだけでビルドした)ときは当たり判定なし(ボスバーだけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SkyBossCombat {

    /** これより遠いプレイヤーのためには姿勢を出さない(ボスバーの距離 + 体の半分)。 */
    private static final double POSE_RANGE = SkyBossTuning.BOSS_BAR_RANGE + 1500.0;

    private static SkyBossPose pose;
    private static final SkyBossPose.Hit HIT = new SkyBossPose.Hit();
    private static final SkyBossPose.Contact CONTACT = new SkyBossPose.Contact();
    /** プレイヤーごとの剣の受け手。 */
    private static final Map<UUID, SkyBossHitboxEntity> MELEE = new HashMap<>();
    /** プレイヤーごとに、最後にぶつかってはじいた tick。 */
    private static final Map<UUID, Long> LAST_CONTACT = new HashMap<>();
    /** 放電の雷が次にそのプレイヤーへ飛ぶ tick。 */
    private static final Map<UUID, Long> NEXT_ARC = new HashMap<>();
    private static final double[] ARC_FROM = new double[3];
    /** 雷を見せる距離(当たった所から)。 */
    private static final double ARC_SEND_RANGE = 3000.0;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        SkyBossData data = SkyBossData.get(level);
        if (!data.isActive()) {
            clearMelee();
            return;
        }
        tick(level, data);
    }

    static void tick(ServerLevel level, SkyBossData data) {
        SkyBossFlight f = data.flight();
        long now = level.getGameTime();
        List<ServerPlayer> players = level.players();
        // 頭の近くで大まかに: 体は頭から 約 2100 ブロック後ろまで
        double hx = SkyBossWorld.worldX(f.x, f.tick);
        double hz = SkyBossWorld.worldZ(f.z, f.tick);
        boolean anyone = false;
        for (ServerPlayer p : players) {
            if (Math.hypot(p.getX() - hx, p.getZ() - hz) < POSE_RANGE + SkyBossBody.LENGTH) {
                anyone = true;
                break;
            }
        }
        if (!anyone && !SkyBossTestHooks.forcePose) {
            clearMelee();
            if (now % 20L == 0L) {
                data.updateBossBar(List.of());
            }
            return;
        }
        if (pose == null) {
            pose = new SkyBossPose();
        }
        SkyBossPose ps = pose;
        SkyBossParts parts = data.parts();
        ps.parts = parts;
        ps.compute(f, f.x, f.y, f.z, f.bank, f.tick, true);
        // 避雷針が壊れたら、rod_short の爆発の時刻に爆発の粒と音(遅れて来たプレイヤーには出し直さない)
        if (!parts.rodBoomDone && now >= parts.rodBoomTick()) {
            parts.rodBoomDone = true;
            if (ps.hasShapes() && now - parts.rodBoomTick() < 20L) {
                SkyBossEffects.rodBoom(level, ps);
            }
        }
        // ボスバーと、近くを飛ばれたら戦い(20 tick ごと)
        if (now % 20L == 0L) {
            List<ServerPlayer> inRange = new ArrayList<>();
            for (ServerPlayer p : players) {
                double d = ps.hasShapes() ? ps.nearest(p.getX(), p.getY() + p.getBbHeight() * 0.5, p.getZ()) : polyDistance(ps, p.position());
                if (d < SkyBossTuning.BOSS_BAR_RANGE) {
                    inRange.add(p);
                }
                if (d < SkyBossTuning.COMBAT_NEAR && !p.isSpectator()) {
                    data.markCombat(now);
                }
            }
            data.updateBossBar(inRange);
        }
        if (!ps.hasShapes()) {
            return;
        }
        shots(level, ps);
        melee(level, ps, players);
        contact(level, ps, players, now);
        discharge(level, data, ps, players, now);
        // 技(10-19)
        data.attacks().tick(level, data, ps, players, now);
    }

    /** 放電の雷(10-19)。範囲の中のプレイヤーへ、近いほど短い間で(決まった間 + ばらつき)。 */
    private static void discharge(ServerLevel level, SkyBossData data, SkyBossPose ps, List<ServerPlayer> players, long now) {
        if (!data.flight().discharging(now)) {
            NEXT_ARC.clear();
            return;
        }
        net.minecraft.util.RandomSource r = level.getRandom();
        for (ServerPlayer p : players) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            double cx = p.getX();
            double cy = p.getY() + p.getBbHeight() * 0.5;
            double cz = p.getZ();
            double d = ps.nearestPoint(cx, cy, cz, ARC_FROM);
            if (d > SkyBossTuning.DISCHARGE_RADIUS) {
                NEXT_ARC.remove(p.getUUID());
                continue;
            }
            Long next = NEXT_ARC.get(p.getUUID());
            int gap = arcInterval(d, r.nextDouble());
            if (next == null) {
                // 範囲に入ってすぐは少し待つ(入った瞬間に当たらない)
                NEXT_ARC.put(p.getUUID(), now + gap / 2);
                continue;
            }
            if (now < next) {
                continue;
            }
            NEXT_ARC.put(p.getUUID(), now + gap);
            p.hurt(level.damageSources().lightningBolt(), SkyBossTuning.ARC_DAMAGE);
            data.markCombat(now);
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersNear(level, null, cx, cy, cz, ARC_SEND_RANGE,
                    new com.yayuy.overgrownvegetation.network.SkyBossArcPayload(ARC_FROM[0], ARC_FROM[1], ARC_FROM[2], cx, cy, cz, r.nextInt()));
            level.playSound(null, cx, cy, cz, net.minecraft.sounds.SoundEvents.LIGHTNING_BOLT_IMPACT, net.minecraft.sounds.SoundSource.HOSTILE,
                    2.0F, 0.8F + 0.4F * r.nextFloat());
        }
    }

    /** 体からの距離 {@code d} の相手へ雷が飛ぶ間(tick)。{@code u} = 0〜1 のばらつき(± 25%)。 */
    public static int arcInterval(double d, double u) {
        double k = Math.max(0.0, Math.min(1.0, d / SkyBossTuning.DISCHARGE_RADIUS));
        double base = SkyBossTuning.ARC_INTERVAL_NEAR + (SkyBossTuning.ARC_INTERVAL_FAR - SkyBossTuning.ARC_INTERVAL_NEAR) * k;
        return Math.max(1, (int) Math.round(base * (0.75 + 0.5 * u)));
    }

    /** 飛び道具: この tick に当たる所へ受け手を置く。 */
    private static void shots(ServerLevel level, SkyBossPose ps) {
        double r2 = ps.boundR + 64.0;
        r2 *= r2;
        // 当たる所を先に全部集めてから受け手を足す(読みながら足すと一覧が壊れる)
        List<double[]> hits = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Projectile proj) || e.isRemoved()) {
                continue;
            }
            Vec3 pos = proj.position();
            if (sq(pos.x - ps.boundX) + sq(pos.y - ps.boundY) + sq(pos.z - ps.boundZ) > r2) {
                continue;
            }
            Vec3 mv = proj.getDeltaMovement();
            // 刺さって止まった矢・エリトラの加速に付いた花火(斜めに撃ったものでない)は数えない(毎 tick 受け手を置き続けてしまう)
            if (mv.lengthSqr() < 1.0E-6
                    || (proj instanceof net.minecraft.world.entity.projectile.FireworkRocketEntity rocket && !rocket.isShotAtAngle())) {
                continue;
            }
            // バニラは箱の真ん中ではなく位置(足元)から進む線分で当たりを調べる。少し先まで見て、受け手を線分の上に置く
            if (!ps.raycast(pos.x, pos.y, pos.z, pos.x + mv.x, pos.y + mv.y, pos.z + mv.z, HIT)) {
                continue;
            }
            hits.add(new double[]{HIT.x, HIT.y, HIT.z, HIT.part, HIT.index});
        }
        for (double[] h : hits) {
            SkyBossHitboxEntity proxy = SkyBossHitboxEntity.create(level, SkyBossHitboxEntity.KIND_SHOT, null);
            if (proxy != null) {
                proxy.place(h[0], h[1], h[2], (int) h[3], (int) h[4], true);
                level.addFreshEntity(proxy);
            }
        }
    }

    /** 剣: 近くのプレイヤーの視線と形の交わる所へ、そのプレイヤーの受け手を置く。 */
    private static void melee(ServerLevel level, SkyBossPose ps, List<ServerPlayer> players) {
        java.util.Set<UUID> seen = new java.util.HashSet<>();
        for (ServerPlayer p : players) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            Vec3 eye = p.getEyePosition();
            double reach = p.entityInteractionRange() + 0.5;
            if (sq(eye.x - ps.boundX) + sq(eye.y - ps.boundY) + sq(eye.z - ps.boundZ) > sq(ps.boundR + reach)) {
                continue;
            }
            Vec3 look = p.getViewVector(1.0F);
            boolean hit = ps.raycast(eye.x, eye.y, eye.z, eye.x + look.x * reach, eye.y + look.y * reach, eye.z + look.z * reach, HIT);
            if (!hit && ps.nearest(eye.x, eye.y, eye.z) > 32.0) {
                continue;
            }
            seen.add(p.getUUID());
            SkyBossHitboxEntity proxy = MELEE.get(p.getUUID());
            if (proxy == null || proxy.isRemoved() || proxy.level() != level) {
                proxy = SkyBossHitboxEntity.create(level, SkyBossHitboxEntity.KIND_MELEE, p.getUUID());
                if (proxy == null) {
                    continue;
                }
                proxy.place(eye.x, eye.y, eye.z, SkyBossPose.PART_MOSS, false);
                level.addFreshEntity(proxy);
                MELEE.put(p.getUUID(), proxy);
            }
            if (hit) {
                proxy.place(HIT.x, HIT.y, HIT.z, HIT.part, HIT.index, true);
            } else {
                // 届かない間はプレイヤーの目の所で当たらないまま待つ
                proxy.place(eye.x, eye.y, eye.z, SkyBossPose.PART_MOSS, false);
            }
        }
        MELEE.entrySet().removeIf(en -> {
            if (!seen.contains(en.getKey())) {
                en.getValue().discard();
                return true;
            }
            return false;
        });
    }

    /**
     * ぶつかる: めり込んでいる間は毎 tick 外へ押し出し(外向きの速さを {@link SkyBossTuning#CONTACT_PUSH} + 深さ × 0.08 以上に。横の速さはそのまま)、
     * ダメージとはじきは {@link SkyBossTuning#CONTACT_COOLDOWN} tick に 1 回(10-18 夜のテストクライアントで、1 回のはじきだけでは深くめり込んだとき抜けきらなかった)。
     */
    private static void contact(ServerLevel level, SkyBossPose ps, List<ServerPlayer> players, long now) {
        for (ServerPlayer p : players) {
            if (p.isSpectator() || !p.isAlive()) {
                continue;
            }
            double cx = p.getX();
            double cy = p.getY() + p.getBbHeight() * 0.5;
            double cz = p.getZ();
            if (!ps.contact(cx, cy, cz, 0.6, CONTACT)) {
                continue;
            }
            double push = SkyBossTuning.CONTACT_PUSH + CONTACT.depth * 0.08;
            Vec3 v = p.getDeltaMovement();
            double out = v.x * CONTACT.nx + v.y * CONTACT.ny + v.z * CONTACT.nz;
            Long last = LAST_CONTACT.get(p.getUUID());
            boolean hit = last == null || now - last >= SkyBossTuning.CONTACT_COOLDOWN;
            if (out < push) {
                double add = push - out;
                p.setDeltaMovement(v.x + CONTACT.nx * add, v.y + CONTACT.ny * add + (hit ? 0.2 : 0.0), v.z + CONTACT.nz * add);
                p.hurtMarked = true;
            }
            p.fallDistance = 0.0F;
            if (hit) {
                LAST_CONTACT.put(p.getUUID(), now);
                p.hurt(ModDamageTypes.skyBoss(level), SkyBossTuning.CONTACT_DAMAGE);
                SkyBossData.get(level).markCombat(now);
                if (Boolean.getBoolean("ov.devbridge")) {
                    com.yayuy.overgrownvegetation.OvergrownVegetation.LOGGER.info("[sky boss] contact {}", p.getName().getString());
                }
            }
        }
        if (LAST_CONTACT.size() > 64) {
            LAST_CONTACT.values().removeIf(t -> now - t > SkyBossTuning.CONTACT_COOLDOWN);
        }
    }

    private static double polyDistance(SkyBossPose ps, Vec3 p) {
        double best = Double.MAX_VALUE;
        for (double[] q : ps.points) {
            best = Math.min(best, Math.sqrt(sq(p.x - q[0]) + sq(p.y - q[1]) + sq(p.z - q[2])));
        }
        return best;
    }

    private static void clearMelee() {
        if (!MELEE.isEmpty()) {
            for (SkyBossHitboxEntity e : MELEE.values()) {
                e.discard();
            }
            MELEE.clear();
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        reset();
    }

    /** サーバーが止まったとき(ワールドを閉じたとき)に受け手の覚えを捨てる。 */
    public static void reset() {
        MELEE.clear();
        LAST_CONTACT.clear();
        pose = null;
    }

    private static double sq(double v) {
        return v * v;
    }

    private SkyBossCombat() {
    }
}

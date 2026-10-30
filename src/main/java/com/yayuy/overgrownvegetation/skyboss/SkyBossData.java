package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SkyBossPartsPayload;
import com.yayuy.overgrownvegetation.network.SkyBossStatePayload;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 空の巨大生物(G10)の本体の状態(サーバー、オーバーワールドに 1 体)。2026-10-18 から。
 *
 * <p>ボスは<b>エンティティではない</b>: 全長 約 2100 ブロックで、遠くから見える目印でもあるので、攻城戦のボス(ティタニカ、{@code SiegeData})と同じく
 * 状態をここに持ち、クライアントは {@link SkyBossStatePayload} から自分で位置を出して描く。ティタニカとの違いは、動きが決まった式ではなく
 * 頭({@link SkyBossBrain})が区間ごとに決めること —— 区間の操作と頭の通った道を送り、両側が同じ式({@link SkyBossFlight})で進める。
 * チャンクが読み込まれていなくても毎 tick 進む(世界を渡り歩く)。
 *
 * <p>10-18 夕方から体力・戦い・ボスバーを持つ。当たり判定は {@link SkyBossCombat}(形との当たりを計算し、当たる所にだけ見えない受け手を置く)。
 * 体力 0 で倒れる(今は消える。最期の動きと骸は次の段)。
 *
 * <p>10-18 夜から弱点(避雷針・蕾)を壊せる({@link SkyBossParts})。壊れた・生え直したら {@link SkyBossPartsPayload} で全員へ。
 *
 * <p>1 段目は {@code /ov sky summon} でだけ出る(自然には湧かない)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class SkyBossData extends SavedData {

    private static final String ID = "overgrown_vegetation_sky_boss";
    /** Claude のテストクライアント(-Dov.devbridge=true)のときだけ旋回をログへ。 */
    private static final boolean DEBUG_LOG = Boolean.getBoolean("ov.devbridge");

    public static final SavedData.Factory<SkyBossData> FACTORY =
            new SavedData.Factory<>(SkyBossData::new, (tag, registries) -> load(tag), null);

    private boolean active;
    private final SkyBossFlight flight = new SkyBossFlight();
    /** 何番目の区間か(気まぐれの振れに使う)。 */
    private long segmentIndex;
    /** この個体の決まった種。 */
    private long seed;
    /** 頭(今の旋回の状態)。 */
    private final SkyBossBrain brain = new SkyBossBrain();
    /** 体力。 */
    private float health = SkyBossTuning.MAX_HEALTH;
    /** 弱点(避雷針・蕾)の壊れ具合。 */
    private final SkyBossParts parts = new SkyBossParts();
    /** 雷雲に寄る・背を上にして留まる・出ていく(10-19)と、頭への指示。 */
    private final SkyBossFeeding feeding = new SkyBossFeeding();
    private final SkyBossFeeding.Orders orders = new SkyBossFeeding.Orders();
    /** 放電(大技 2。10-19)。値の多くは {@link SkyBossFlight}(両側が同じ時刻で描く)、進め方はこちら。 */
    private final SkyBossDischarge discharge = new SkyBossDischarge();
    /** 技の頭と弾(10-19。保存しない)。 */
    private final SkyBossAttacks attacks = new SkyBossAttacks();
    /** 一日のリズム・ときどきの身震い(10-19 夕方)。 */
    private final SkyBossRhythm rhythm = new SkyBossRhythm();
    /** 雲海に潜る・飛び出す(10-19 夕方)。 */
    private SkyBossCloudSea cloudSea = new SkyBossCloudSea();
    /** 雷を落とす先(避雷針の先)を出すための姿勢(保存しない)。 */
    private SkyBossPose strikePose;
    /** 最後に戦った(傷を受けた・近くを飛ばれた)tick。保存しない(読み込み直したら戦っていないことにする)。 */
    private long lastCombatTick = Long.MIN_VALUE / 2;
    /** 同じ弾・同じ相手から続けて受けたダメージを 1 回に数えるための、直前に当てたものと tick。 */
    private final java.util.Map<Integer, Long> recentHits = new java.util.HashMap<>();
    /**
     * ボスバー(保存しない)。指定(10-18)「見える距離なら常に」→ 体から {@link SkyBossTuning#BOSS_BAR_RANGE} の中のプレイヤーに出す({@link SkyBossCombat} が決める)。
     * 刻みは 6 つ(3 段階の境 67% と 34% にほぼ合う)。
     */
    private final ServerBossEvent bossEvent = new ServerBossEvent(
            Component.translatable("event.overgrown_vegetation.sky_boss.name"), BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.NOTCHED_6);

    private SkyBossData() {
    }

    public static SkyBossData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public boolean isActive() {
        return active;
    }

    public SkyBossFlight flight() {
        return flight;
    }

    public SkyBossParts parts() {
        return parts;
    }

    public SkyBossFeeding feeding() {
        return feeding;
    }

    public SkyBossDischarge discharge() {
        return discharge;
    }

    public SkyBossAttacks attacks() {
        return attacks;
    }

    public SkyBossRhythm rhythm() {
        return rhythm;
    }

    public SkyBossCloudSea cloudSea() {
        return cloudSea;
    }

    /** 技が飛び方の値(技の時刻)を変えた: 保存の印と、全員へ。 */
    void sync(ServerLevel level) {
        setDirty();
        broadcast(level);
    }

    // ------------------------------------------------------------------ 出す・消す
    /**
     * プレイヤーの風上 {@link SkyBossTuning#SUMMON_UPWIND} の空に出す(頭を風下へ向けて、ふだんの高さで)。風に流されてプレイヤーの上を通る。
     * 居れば置き直す。
     */
    public void summon(ServerLevel level, Vec3 near) {
        long now = level.getGameTime();
        double wx = SkyBossWorld.windX(now);
        double wz = SkyBossWorld.windZ(now);
        double wl = Math.max(1.0E-6, Math.hypot(wx, wz));
        double hx = near.x - wx / wl * SkyBossTuning.SUMMON_UPWIND;
        double hz = near.z - wz / wl * SkyBossTuning.SUMMON_UPWIND;
        flight.tick = now;
        flight.x = SkyBossWorld.airX(hx, now);
        flight.y = SkyBossTuning.CRUISE_Y;
        flight.z = SkyBossWorld.airZ(hz, now);
        flight.yaw = (float) Math.toDegrees(Math.atan2(-wx, wz));
        flight.pitch = 0.0F;
        flight.speed = (float) SkyBossTuning.AIRSPEED;
        flight.straightTrail(SkyBossTuning.TRAIL_KEEP);
        seed = level.getRandom().nextLong();
        segmentIndex = 0;
        brain.reset();
        feeding.reset();
        discharge.reset();
        attacks.reset();
        cloudSea = new SkyBossCloudSea();
        nextSegment(level);
        health = SkyBossTuning.MAX_HEALTH;
        lastCombatTick = Long.MIN_VALUE / 2;
        recentHits.clear();
        bossEvent.setProgress(1.0F);
        parts.reset();
        active = true;
        setDirty();
        broadcast(level);
        broadcastParts(level);
    }

    /** GameTest 用: 頭を世界の (x, y, z) に、向き {@code yaw} で置く(後ろへまっすぐの道。最初の区間はまっすぐ飛ぶ)。 */
    public void placeForTest(ServerLevel level, double x, double y, double z, float yaw) {
        long now = level.getGameTime();
        flight.tick = now;
        flight.x = SkyBossWorld.airX(x, now);
        flight.y = y;
        flight.z = SkyBossWorld.airZ(z, now);
        flight.yaw = yaw;
        flight.pitch = 0.0F;
        flight.speed = (float) SkyBossTuning.AIRSPEED;
        flight.straightTrail(SkyBossTuning.TRAIL_KEEP);
        seed = 1L;
        segmentIndex = 0;
        brain.reset();
        feeding.reset();
        discharge.reset();
        attacks.reset();
        cloudSea = new SkyBossCloudSea();
        nextSegment(null);
        health = SkyBossTuning.MAX_HEALTH;
        lastCombatTick = Long.MIN_VALUE / 2;
        recentHits.clear();
        parts.reset();
        active = true;
        setDirty();
    }

    public void remove(ServerLevel level) {
        active = false;
        bossEvent.removeAllPlayers();
        setDirty();
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(), SkyBossStatePayload.inactive());
    }

    // ------------------------------------------------------------------ 進める
    private void tick(ServerLevel level) {
        if (!active) {
            return;
        }
        long now = level.getGameTime();
        if (flight.tick >= now) {
            return;
        }
        // 読み込み直したあとなど大きく遅れていたら、その分は進めずに時刻だけ合わせる(区間も今から決め直す)
        if (now - flight.tick > SkyBossTuning.SEGMENT_TICKS * 4L) {
            flight.tick = now - 1;
            flight.segmentEnd = flight.tick;
        }
        while (flight.tick < now) {
            if (flight.tick >= flight.segmentEnd) {
                segmentIndex++;
                nextSegment(level);
                broadcast(level);
            }
            flight.step();
        }
        // 背を上にして雷雲に留まっている間、避雷針へ雷が落ちる(10-19 指定「雷が避雷針に落ちて身震いする」)
        if (feeding.strikeDue(flight, now)) {
            strikeRod(level, now);
        }
        dischargeTick(level, now);
        rhythmTick(level, now);
        cloudSeaTick(level, now);
        regen(level, now);
        if (parts.tick(now, inCombat(now), lastCombatTick + SkyBossTuning.COMBAT_TIMEOUT, seed)) {
            setDirty();
            broadcastParts(level);
        }
        if (now % 200L == 0L) {
            setDirty();
        }
    }

    /**
     * 次の区間を決める(旋回を決めたら確かめ用のクライアントのときだけログへ)。10-19 から、先に雷雲に寄る・留まる・出ていくを決め({@link SkyBossFeeding})、
     * その指示で頭が進路を決める。雲はここで探す(区間の頭だけ。{@code level} が無い GameTest の置き直しでは雲なし)。
     */
    private void nextSegment(ServerLevel level) {
        long before = brain.maneuver;
        // 雷雲の外で放電している間は雷雲に寄らない(転がりが重ならないように)
        // 転がりのビームの間も寄らない(10-19)
        // 雲海に潜っている間も寄らない(10-19 夕方)
        java.util.List<SkyBossFeeding.Storm> storms = level == null || discharge.blocksFeeding() || flight.beaming(flight.tick) || cloudSea.submerged()
                ? java.util.List.of()
                : SkyBossWorld.giantStorms(level, flight.x, flight.z, SkyBossTuning.STORM_DETECT * 1.7);
        int mode = feeding.mode;
        orders.altitude = Double.NaN;
        orders.maxPitch = 0.0F;
        orders.maxPitchRate = 0.0F;
        feeding.segment(flight, flight.tick, storms, orders);
        discharge.orders(flight, orders);
        int seaBefore = cloudSea.mode;
        if (level != null) {
            long now = flight.tick;
            boolean calm = !inCombat(now) && feeding.mode == SkyBossFeeding.CRUISE && !discharge.busy() && !flight.beaming(now);
            double hx = SkyBossWorld.worldX(flight.x, now);
            double hz = SkyBossWorld.worldZ(flight.z, now);
            java.util.List<com.yayuy.overgrownvegetation.sky.CloudField.Storm> near = SkyBossWorld.stormsNear(level, hx, hz, SkyBossTuning.SEA_AHEAD + 6000.0);
            cloudSea.segment(flight, now, (x, z) -> SkyBossWorld.sea(level, x, z, near), calm, inCombat(now), level.getRandom().nextDouble(), orders);
        }
        brain.next(flight, segmentIndex, seed, orders);
        if (cloudSea.mode != seaBefore) {
            setDirty();
            OvergrownVegetation.LOGGER.info("[sky boss] cloud sea {} -> {} (hide y {}, sea top {})", SkyBossCloudSea.name(seaBefore),
                    SkyBossCloudSea.name(cloudSea.mode), Math.round(cloudSea.hideY), Math.round(cloudSea.seaTop));
        }
        if (orders.eager && flight.fanOverride < 0) {
            // 雷雲へ向かう・転がり戻る間は漕ぎ続ける(曲がって深く傾く間は旋回の扇)
            flight.fanOverride = Math.abs(flight.bankTarget) > SkyBossTuning.TURN_ANIM_BANK ? -1 : SkyBossFlight.FAN_EAGER;
        }
        if (feeding.mode != mode) {
            setDirty();
            OvergrownVegetation.LOGGER.info("[sky boss] {} -> {} (storm {} at {} {}, radius {}, maturity {})", SkyBossFeeding.name(mode),
                    SkyBossFeeding.name(feeding.mode), feeding.stormId, Math.round(feeding.tx), Math.round(feeding.tz), Math.round(feeding.tRadius),
                    String.format("%.2f", feeding.lastMaturity));
        }
        if (DEBUG_LOG && brain.maneuver != before) {
            OvergrownVegetation.LOGGER.info("[sky boss] turn #{}: delta {} deg, radius {} blocks, bank {} deg, {} segments, then {} straight",
                    brain.maneuver, String.format("%.1f", brain.turnDelta), String.format("%.0f", brain.turnRadius),
                    String.format("%.1f", brain.turnBank), brain.turnSegs, brain.straightLeft);
        }
    }

    /**
     * 放電(10-19): 避雷針へ届いた雷を溜めに数え、始める・終える。始めるのは戦いの中で体力が段階 {@link SkyBossTuning#DISCHARGE_STAGE} から
     * (決定「ボスの好きなタイミング」→ 仮に、攻撃された・体の {@link SkyBossTuning#COMBAT_NEAR} の中を飛ばれた間)、渡りか雷雲で留まっている間だけ
     * (寄る・出ていく途中は始めない)。状態が変わったら次の tick に頭への指示を出し直す。
     */
    /**
     * 雲海(10-19 夕方): 下りきったら隠れる・奇襲・飛び出し・上がる途中の潮吹き。雲の側へ頼む演出(盛り上がり・穴)と、唸り・潮吹きの霧(吐いた雲の柱と粒)。
     */
    private void cloudSeaTick(ServerLevel level, long now) {
        boolean above = false;
        if (cloudSea.mode == SkyBossCloudSea.HIDDEN && now % 10L == 0L) {
            double hx = SkyBossWorld.worldX(flight.x, now);
            double hz = SkyBossWorld.worldZ(flight.z, now);
            for (ServerPlayer p : level.players()) {
                if (p.isSpectator() || p.isCreative() || !p.isAlive()) {
                    continue;
                }
                double dy = p.getY() - cloudSea.seaTop;
                if (Math.hypot(p.getX() - hx, p.getZ() - hz) < SkyBossTuning.SEA_AMBUSH_RANGE && dy > 0.0 && dy < SkyBossTuning.SEA_AMBUSH_HEIGHT) {
                    above = true;
                    break;
                }
            }
        }
        int before = cloudSea.mode;
        if (cloudSea.tick(flight, now, above, level.getRandom().nextDouble())) {
            setDirty();
            broadcast(level);
            if (cloudSea.mode != before) {
                OvergrownVegetation.LOGGER.info("[sky boss] cloud sea {} -> {}", SkyBossCloudSea.name(before), SkyBossCloudSea.name(cloudSea.mode));
            }
        }
        for (SkyBossCloudSea.Event e : cloudSea.events) {
            switch (e.kind()) {
                case SkyBossCloudSea.Event.BULGE -> com.yayuy.overgrownvegetation.sky.CloudSurface.bulge(level, e.x(), e.y(), e.z(),
                        SkyBossTuning.BREACH_BULGE_RADIUS, SkyBossTuning.BREACH_TELEGRAPH, 30);
                case SkyBossCloudSea.Event.HOLE -> com.yayuy.overgrownvegetation.sky.CloudSurface.hole(level, e.x(), e.z(), Double.NaN, Double.NaN, 170.0F, 15, 200, 900);
                default -> level.playSound(null, e.x(), e.y(), e.z(), net.minecraft.sounds.SoundEvents.ENDER_DRAGON_GROWL,
                        net.minecraft.sounds.SoundSource.HOSTILE, 40.0F, 0.4F);
            }
        }
        // 潮吹き(上がって雲を抜けたとき・飛び出しで頭が雲を抜けた後): 顎から上へ霧の柱
        long breachSpout = flight.breachAt == SkyBossFlight.NONE ? SkyBossFlight.NONE
                : flight.breachAt + SkyBossFanClock.TRANSITION + Math.round(2.0 * SkyBossTuning.ANIM_SLOW * 20.0);
        long riseSpout = flight.spoutAt == SkyBossFlight.NONE ? SkyBossFlight.NONE : flight.spoutAt + Math.round(0.5 * SkyBossTuning.ANIM_SLOW * 20.0);
        if (now == breachSpout || now == riseSpout) {
            SkyBossPose ps = new SkyBossPose();
            ps.parts = parts;
            ps.compute(flight, flight.x, flight.y, flight.z, flight.bank, now, true);
            double[] m = new double[3];
            if (ps.spotWorld("maw", m)) {
                com.yayuy.overgrownvegetation.sky.CloudPuffs.puff(level, m[0], m[1] + 140.0, m[2], 45.0F, 280.0F, 420, 25, 220);
                SkyBossEffects.spout(level, m[0], m[1], m[2]);
            }
        }
    }

    /** 一日のリズム(10-19 夕方): 戦いの外で渡っている間だけ休む。放電・ビーム・技・体の動きの間は休み方を変えない。 */
    private void rhythmTick(ServerLevel level, long now) {
        boolean calm = !inCombat(now) && feeding.mode == SkyBossFeeding.CRUISE && !discharge.busy();
        boolean busy = flight.beaming(now) || flight.downdrafting(now) || SkyBossActClock.bodyActing(flight, now) || flight.rolling(now)
                || flight.backUp(now);
        if (rhythm.tick(flight, now, level.getDayTime(), !level.isRaining(), calm, busy, level.getRandom().nextDouble())) {
            setDirty();
            broadcast(level);
        }
    }

    private void dischargeTick(ServerLevel level, long now) {
        if (flight.shudderAt != SkyBossFlight.NONE && flight.shudderAt <= now && parts.rodSolid() && discharge.struck(flight, flight.shudderAt)) {
            setDirty();
            broadcast(level);
        }
        // 転がりのビームの間は始めない(転がりが重ならないように)
        boolean want = inCombat(now) && stage() >= SkyBossTuning.DISCHARGE_STAGE && !flight.beaming(now) && !cloudSea.submerged()
                && (feeding.mode == SkyBossFeeding.CRUISE || feeding.mode == SkyBossFeeding.FEED);
        int before = discharge.mode;
        if (discharge.tick(flight, now, want, feeding.mode == SkyBossFeeding.FEED, parts.rodSolid())) {
            flight.segmentEnd = flight.tick;
            setDirty();
            broadcast(level);
            OvergrownVegetation.LOGGER.info("[sky boss] discharge {} -> {} (charge {}, in storm {})", SkyBossDischarge.name(before),
                    SkyBossDischarge.name(discharge.mode), String.format("%.2f", flight.charge), discharge.inStorm);
        }
        feeding.hold = discharge.holdsFeeding();
    }

    /** 避雷針の先へ雷を 1 本(雲の上の方から)。届く tick に身震いと体の震え({@link SkyBossFlight#shudderAt})。 */
    private void strikeRod(ServerLevel level, long now) {
        net.minecraft.util.RandomSource r = level.getRandom();
        if (!parts.rodSolid()) {
            // 避雷針が壊れていれば雷を受けられない(生え直すまで)
            feeding.struck(now, 0, r.nextDouble());
            return;
        }
        if (strikePose == null) {
            strikePose = new SkyBossPose();
        }
        SkyBossPose ps = strikePose;
        if (!ps.hasShapes()) {
            feeding.struck(now, 0, r.nextDouble());
            return;
        }
        ps.parts = parts;
        ps.compute(flight, flight.x, flight.y, flight.z, flight.bank, flight.tick, true);
        int o = (ps.capCount - 1) * 3;
        double x = ps.cB[o];
        double y = ps.cB[o + 1];
        double z = ps.cB[o + 2];
        double h = SkyBossTuning.STRIKE_HEIGHT_MIN + (SkyBossTuning.STRIKE_HEIGHT_MAX - SkyBossTuning.STRIKE_HEIGHT_MIN) * r.nextDouble();
        int charge = SkyBossWorld.strike(level, x + (r.nextDouble() - 0.5) * 120.0, y + h, z + (r.nextDouble() - 0.5) * 120.0, x, y, z);
        if (charge > 0) {
            flight.shudderAt = now + charge;
            broadcast(level);
        }
        feeding.struck(now, charge, r.nextDouble());
        if (DEBUG_LOG) {
            OvergrownVegetation.LOGGER.info("[sky boss] lightning to the rod at ({}, {}, {}) in {} ticks", (int) x, (int) y, (int) z, charge);
        }
    }

    /** 確かめ用(devbridge): 今の寄り道の状態を 1 行で。 */
    public String feedingStatus(long now) {
        double d = Math.hypot(feeding.tx - flight.x, feeding.tz - flight.z);
        return String.format(java.util.Locale.ROOT, "%s 雲 %d(真ん中まで %.0f・半径 %.0f・育ち %.2f)向き %.0f 速さ %.2f→%.2f 扇 %d 頭 %.1f 次の雷 %d 寄らない残り %d",
                SkyBossFeeding.name(feeding.mode), feeding.stormId, d, feeding.tRadius, feeding.lastMaturity, flight.rollAt(now), flight.speed,
                flight.speedTarget, flight.fanMode, flight.look, feeding.nextStrike == SkyBossFlight.NONE ? -1 : feeding.nextStrike - now,
                Math.max(0L, feeding.cooldownUntil - now));
    }

    /** 確かめ用(devbridge): 範囲 {@code range} の中でいちばん近い巨大な積乱雲へ今すぐ寄らせる(気づく距離・寄らない間を無視)。寄れたら true。 */
    public boolean debugSeek(ServerLevel level, double range) {
        SkyBossFeeding.Storm best = null;
        for (SkyBossFeeding.Storm st : SkyBossWorld.giantStorms(level, flight.x, flight.z, range)) {
            if (best == null || Math.hypot(st.x() - flight.x, st.z() - flight.z) < Math.hypot(best.x() - flight.x, best.z() - flight.z)) {
                best = st;
            }
        }
        if (best == null) {
            return false;
        }
        feeding.force(best, flight.tick);
        flight.segmentEnd = flight.tick;
        setDirty();
        return true;
    }

    /** 確かめ用({@code /ov sky charge}・devbridge): 溜めを {@code c}(0〜1)にする(10-19。放電を見る)。 */
    public void debugCharge(ServerLevel level, float c) {
        flight.charge = Math.max(0.0F, Math.min(1.0F, c));
        setDirty();
        broadcast(level);
    }

    /** 確かめ用({@code /ov sky discharge}・devbridge): 今すぐ放電を始める(溜め・戦い・段階・隙を見ない。転がっている途中・もう放電中なら false)。 */
    public boolean debugDischarge(ServerLevel level) {
        long now = flight.tick;
        if (discharge.busy() || flight.rolling(now)) {
            return false;
        }
        flight.charge = 1.0F;
        discharge.start(flight, now, feeding.mode == SkyBossFeeding.FEED);
        flight.segmentEnd = flight.tick;
        setDirty();
        broadcast(level);
        return true;
    }

    /** 確かめ用(devbridge): 放電の状態を 1 行で。 */
    public String dischargeStatus(long now) {
        return String.format(java.util.Locale.ROOT, "放電 %s 溜め %.2f 予告まで %d 雷の残り %d 隙の残り %d 次まで %d / 段階 %d 日の光 %.2f ビームの残り %d 転がり %.0f°",
                SkyBossDischarge.name(discharge.mode), flight.charge,
                flight.dischargeAt == SkyBossFlight.NONE ? -1 : Math.max(0L, flight.dischargeAt - now),
                flight.discharging(now) ? flight.dischargeEnd - now : 0L,
                flight.stunUntil == SkyBossFlight.NONE ? 0L : Math.max(0L, flight.stunUntil - now),
                Math.max(0L, discharge.cooldownUntil - now), stage(), flight.light,
                flight.beaming(now) ? flight.beamEnd - now : 0L, SkyBossBeam.roll(flight, now))
                + String.format(java.util.Locale.ROOT, " / 雲海 %s 隠れる高さ %.0f 天辺 %.0f 頭 y %.0f 機首 %.1f", SkyBossCloudSea.name(cloudSea.mode), cloudSea.hideY,
                cloudSea.seaTop, flight.y, flight.pitch)
                + String.format(java.util.Locale.ROOT, " / 休み %d(%d tick 前から)日光浴の傾き %.1f° 扇 %d 体 %s", flight.restKind,
                flight.restSince == SkyBossFlight.NONE ? -1L : now - flight.restSince, SkyBossRhythm.baskRoll(flight, now), flight.fanMode,
                SkyBossActClock.bodyActing(flight, now) ? "動き中" : "うねり");
    }

    /**
     * 確かめ用(devbridge): 次の tick に絡め取るを始め、{@code ahead} tick 後に触手 {@code tn} の先の方が来る所(飛び方を写して先へ進めた姿勢から)を返す
     * (絡め取るの当たりを試すとき、そこへ移って待つ)。形が無ければ null。
     */
    public double[] debugGrabSpot(ServerLevel level, int tn, int ahead) {
        long now = level.getGameTime();
        markCombat(now);
        attacks.forceNext("grab", now);
        SkyBossFlight c = new SkyBossFlight();
        SkyBossStatePayload.of(flight, seed).applyTo(c);
        SkyBossActClock.startTentacle(c, now + 1, 1);
        c.advanceTo(now + 1 + ahead, 400);
        SkyBossPose ps = new SkyBossPose();
        ps.parts = parts;
        ps.compute(c, c.x, c.y, c.z, c.bank, c.tick, true);
        int each = ps.tentCapsulesEach();
        if (!ps.hasShapes() || tn < 0 || tn >= ps.tentCount()) {
            return null;
        }
        int cap = ps.tentFirstCapsule() + tn * each + each - 3;
        int o = cap * 3;
        return new double[]{(ps.cA[o] + ps.cB[o]) * 0.5, (ps.cA[o + 1] + ps.cB[o + 1]) * 0.5 + ps.cR[cap] + 3.0, (ps.cA[o + 2] + ps.cB[o + 2]) * 0.5};
    }

    /** 確かめ用(devbridge): 雲海に潜ろうとする(次の区間で。深い雲の海の上でだけ)・今すぐ飛び出す・上がる。 */
    public String debugSea(ServerLevel level, String what) {
        long now = level.getGameTime();
        switch (what) {
            case "dive" -> {
                cloudSea.forceDive = true;
                flight.segmentEnd = flight.tick;
                double[] s = SkyBossWorld.sea(level, SkyBossWorld.worldX(flight.x, now), SkyBossWorld.worldZ(flight.z, now),
                        SkyBossWorld.stormsNear(level, SkyBossWorld.worldX(flight.x, now), SkyBossWorld.worldZ(flight.z, now), 6000.0));
                return s == null ? "頭の下に雲の海が無い" : String.format(java.util.Locale.ROOT, "頭の下の雲の海 %.0f〜%.0f(厚さ %.0f)", s[0], s[1], s[1] - s[0]);
            }
            case "breach" -> {
                cloudSea.forceBreach(flight, now);
                return "予告の後に飛び出す";
            }
            default -> {
                return "dive|breach";
            }
        }
    }

    /** 確かめ用(devbridge): 日の光の溜まり(0〜1。1 で転がりのビームを出せる)。 */
    public void debugLight(ServerLevel level, float v) {
        flight.light = Math.max(0.0F, Math.min(1.0F, v));
        sync(level);
    }

    /** 確かめ用(devbridge): 体の軸のまわりの向き {@code to}(度。180 = 腹が上・0 = 背が上)へ今すぐ転がり始める(10-19。背の垂れ草を見る)。 */
    public void debugRoll(float to) {
        flight.startRoll(to);
        flight.segmentEnd = flight.tick;
        setDirty();
    }

    /** 今の動きを全員へ。 */
    private void broadcast(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(), SkyBossStatePayload.of(flight, seed));
    }

    /** 弱点の壊れ具合を全員へ。 */
    private void broadcastParts(ServerLevel level) {
        PacketDistributor.sendToPlayersInDimension(level.getServer().overworld(), SkyBossPartsPayload.of(parts));
    }

    // ------------------------------------------------------------------ 体力と戦い
    public float health() {
        return health;
    }

    /** 体力の段階(1 = 100〜67%、2 = 67〜34%、3 = 34% 以下。行動パターンの 4 節)。 */
    public int stage() {
        float r = health / SkyBossTuning.MAX_HEALTH;
        return r > 0.67F ? 1 : r > 0.34F ? 2 : 3;
    }

    public boolean inCombat(long now) {
        return now - lastCombatTick < SkyBossTuning.COMBAT_TIMEOUT;
    }

    /** 戦いに入った・続いている(傷を受けた・近くを飛ばれた)。 */
    public void markCombat(long now) {
        lastCombatTick = now;
    }

    /**
     * 当たりの受け手({@link SkyBossHitboxEntity})が受けたダメージ。部位の倍率を掛けて体力を減らす。同じ弾・同じ相手からの同じ tick のダメージは 1 回に数え、
     * 同じ弾は {@link #SAME_SHOT_TICKS} の間に 1 回だけ(刺さらずにすり抜ける弾が、次の tick に体の中でもう一度当たらないように)。
     * 弱点(避雷針・蕾 {@code index})に当たった分は、その弱点の体力も同じだけ減る({@link SkyBossParts#damage})。
     */
    public void hurt(ServerLevel level, DamageSource source, float amount, int part, int index, double x, double y, double z) {
        if (!active || amount <= 0.0F) {
            return;
        }
        long now = level.getGameTime();
        Entity direct = source.getDirectEntity();
        if (direct != null) {
            Long last = recentHits.get(direct.getId());
            boolean shot = !(direct instanceof net.minecraft.world.entity.player.Player);
            if (last != null && (last == now || (shot && now - last < SAME_SHOT_TICKS))) {
                return;
            }
            recentHits.put(direct.getId(), now);
            if (recentHits.size() > 256) {
                recentHits.values().removeIf(t -> now - t > SAME_SHOT_TICKS);
            }
        }
        float mul = part == SkyBossPose.PART_MOSS ? SkyBossTuning.MOSS_DAMAGE : SkyBossTuning.WEAK_DAMAGE;
        if (part == SkyBossPose.PART_MOSS && direct instanceof net.minecraft.world.entity.projectile.Projectile) {
            // 弾を吸う(決定 10-17「当たった所の苔が一瞬波打ち、弾が沈み込んで消える」。弾は受け手が消す)
            SkyBossEffects.ripple(level, x, y, z);
        }
        int before = stage();
        health = Math.max(0.0F, health - amount * mul);
        markCombat(now);
        if (part != SkyBossPose.PART_MOSS && parts.damage(part, index, amount * mul, now)) {
            onBroken(level, part, index, x, y, z);
        }
        bossEvent.setProgress(health / SkyBossTuning.MAX_HEALTH);
        setDirty();
        if (DEBUG_LOG) {
            OvergrownVegetation.LOGGER.info("[sky boss] hurt {} x{} = {} at ({}, {}, {}) part {} by {} -> {} / {}", String.format("%.1f", amount), mul,
                    String.format("%.1f", amount * mul), (int) x, (int) y, (int) z, part, source.getMsgId(), String.format("%.0f", health),
                    (int) SkyBossTuning.MAX_HEALTH);
        }
        if (health <= 0.0F) {
            die(level);
        } else if (stage() != before) {
            OvergrownVegetation.LOGGER.info("[sky boss] stage {} -> {}", before, stage());
        }
    }

    /** 同じ弾を 2 回数えない間(tick)。 */
    private static final long SAME_SHOT_TICKS = 40L;

    /** 弱点が壊れた: 全員へ知らせ、蕾ならその場ではじける(避雷針の爆発は rod_short の爆発の時刻に {@link SkyBossCombat} が出す)。 */
    private void onBroken(ServerLevel level, int part, int index, double x, double y, double z) {
        setDirty();
        broadcastParts(level);
        if (part == SkyBossPose.PART_ROD) {
            // ショート(10-19 指定「避雷針の破壊で体が下に沈み、そこから一定時間攻撃がストップする」): 溜めは消え、隙。放電の途中なら止まる
            int before = discharge.mode;
            discharge.shorted(flight, level.getGameTime());
            flight.segmentEnd = flight.tick;
            broadcast(level);
            OvergrownVegetation.LOGGER.info("[sky boss] short ({} -> {}), stunned until {}", SkyBossDischarge.name(before),
                    SkyBossDischarge.name(discharge.mode), flight.stunUntil);
        }
        if (part == SkyBossPose.PART_BUD) {
            SkyBossShapes s = SkyBossShapes.get();
            double r = s != null && index >= 0 && index < s.budCount ? s.budRadius[index] * SkyBossTuning.BLOCKS_PER_UNIT : 6.0;
            SkyBossEffects.budBurst(level, x, y, z, r);
        }
        OvergrownVegetation.LOGGER.info("[sky boss] {} broken ({} buds broken)", part == SkyBossPose.PART_ROD ? "rod" : "bud " + index, parts.brokenBuds());
    }

    /** 確かめ用(テストクライアントの devbridge・GameTest): 弱点 1 つを今すぐ壊す(ボスの体力は減らさない)。壊れたら true。 */
    public boolean breakPart(ServerLevel level, int part, int index, double x, double y, double z) {
        if (!active || !parts.damage(part, index, Float.MAX_VALUE, level.getGameTime())) {
            return false;
        }
        markCombat(level.getGameTime());
        onBroken(level, part, index, x, y, z);
        return true;
    }

    /** {@code /ov sky health}: 体力を上限の {@code fraction}(0〜1)にする。0 なら倒れる。 */
    public void setHealthFraction(ServerLevel level, float fraction) {
        int before = stage();
        health = Math.max(0.0F, Math.min(1.0F, fraction)) * SkyBossTuning.MAX_HEALTH;
        bossEvent.setProgress(health / SkyBossTuning.MAX_HEALTH);
        setDirty();
        if (health <= 0.0F) {
            die(level);
        } else if (stage() != before) {
            OvergrownVegetation.LOGGER.info("[sky boss] stage {} -> {} (command)", before, stage());
        }
    }

    /** 体力 0: 倒れる。今は告げて消える(最期の動き・落ちて骸になるのは次の段)。 */
    private void die(ServerLevel level) {
        for (ServerPlayer p : bossEvent.getPlayers()) {
            p.sendSystemMessage(Component.translatable("event.overgrown_vegetation.sky_boss.slain"));
        }
        OvergrownVegetation.LOGGER.info("[sky boss] slain");
        remove(level);
    }

    /** ボスバーを見せるプレイヤーを入れ替える({@link SkyBossCombat} が 20 tick ごとに呼ぶ)。 */
    void updateBossBar(java.util.Collection<ServerPlayer> inRange) {
        java.util.Set<ServerPlayer> want = new java.util.HashSet<>(inRange);
        for (ServerPlayer p : new java.util.ArrayList<>(bossEvent.getPlayers())) {
            if (!want.contains(p) || p.isRemoved()) {
                bossEvent.removePlayer(p);
            }
        }
        for (ServerPlayer p : want) {
            bossEvent.addPlayer(p);
        }
        bossEvent.setVisible(active);
        bossEvent.setProgress(health / SkyBossTuning.MAX_HEALTH);
    }

    /** 戦っていない昼・晴れの間、日の光で体力がゆっくり戻る(毎 tick)。 */
    private void regen(ServerLevel level, long now) {
        if (health >= SkyBossTuning.MAX_HEALTH || inCombat(now) || !level.isDay() || level.isRaining()) {
            return;
        }
        health = Math.min(SkyBossTuning.MAX_HEALTH, health + SkyBossTuning.MAX_HEALTH * SkyBossTuning.REGEN_PER_MINUTE / 1200.0F);
        if (now % 20L == 0L) {
            bossEvent.setProgress(health / SkyBossTuning.MAX_HEALTH);
        }
    }

    /** 体の真ん中(腕の付いた所)のいまの世界の座標。 */
    public Vec3 bodyCenter(long gameTime) {
        double[][] out = {new double[3]};
        flight.trail.pointsBehind(flight.x, flight.y, flight.z, new double[]{SkyBossBody.ARM_CENTER}, out);
        return new Vec3(SkyBossWorld.worldX(out[0][0], gameTime), out[0][1], SkyBossWorld.worldZ(out[0][2], gameTime));
    }

    // ------------------------------------------------------------------ 時刻とプレイヤー
    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            get(level).tick(level);
        }
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        sendTo(event.getEntity());
    }

    @SubscribeEvent
    public static void onChangeDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        sendTo(event.getEntity());
    }

    private static void sendTo(net.minecraft.world.entity.player.Player player) {
        if (!(player instanceof ServerPlayer sp) || sp.getServer() == null) {
            return;
        }
        ServerLevel overworld = sp.getServer().overworld();
        SkyBossData data = get(overworld);
        if (sp.level().dimension() == Level.OVERWORLD && data.active) {
            PacketDistributor.sendToPlayer(sp, SkyBossStatePayload.of(data.flight, data.seed));
            PacketDistributor.sendToPlayer(sp, SkyBossPartsPayload.of(data.parts));
        } else {
            PacketDistributor.sendToPlayer(sp, SkyBossStatePayload.inactive());
        }
    }

    // ------------------------------------------------------------------ 保存
    private static SkyBossData load(CompoundTag tag) {
        SkyBossData d = new SkyBossData();
        d.active = tag.getBoolean("active");
        d.segmentIndex = tag.getLong("segmentIndex");
        d.health = tag.contains("health") ? tag.getFloat("health") : SkyBossTuning.MAX_HEALTH;
        d.parts.load(tag.getCompound("parts"));
        loadFeeding(d.feeding, tag.getCompound("feeding"));
        loadDischarge(d.discharge, tag.getCompound("discharge"));
        loadCloudSea(d.cloudSea, tag.getCompound("cloudSea"));
        d.bossEvent.setProgress(Math.max(0.0F, Math.min(1.0F, d.health / SkyBossTuning.MAX_HEALTH)));
        d.seed = tag.getLong("seed");
        SkyBossFlight f = d.flight;
        f.tick = tag.getLong("tick");
        f.x = tag.getDouble("x");
        f.y = tag.getDouble("y");
        f.z = tag.getDouble("z");
        f.px = f.x;
        f.py = f.y;
        f.pz = f.z;
        f.yaw = tag.getFloat("yaw");
        f.pitch = tag.getFloat("pitch");
        f.speed = tag.getFloat("speed");
        f.yawRate = tag.getFloat("yawRate");
        f.pitchRate = tag.getFloat("pitchRate");
        f.bankTarget = tag.getFloat("bankTarget");
        f.bank = f.pbank = tag.getFloat("bank");
        f.bankMid = tag.getFloat("bankMid");
        f.fanMode = tag.getInt("fanMode");
        f.fanModeSince = tag.getLong("fanModeSince");
        f.prevFanMode = tag.getInt("prevFanMode");
        f.prevFanModeSince = tag.getLong("prevFanModeSince");
        f.segmentEnd = tag.getLong("segmentEnd");
        // 10-19 から(前の版の保存には無い = 今のまま。速さの目標は今の速さ)
        f.speedTarget = f.speed;
        int[] ef = tag.getIntArray("flightExtraF");
        float[] efl = new float[ef.length];
        for (int i = 0; i < ef.length; i++) {
            efl[i] = Float.intBitsToFloat(ef[i]);
        }
        f.setExtra(efl, tag.getLongArray("flightExtraL"));
        if (!tag.contains("discharge")) {
            // 10-19 より前の保存: 最後に落ちた雷はもう数えたことにする(読み込んだ途端に溜めが増えないように)
            d.discharge.lastStrike = f.shudderAt;
        }
        // 頭(前の版の保存には無い = 0 のまま。旋回もまっすぐの残りも 0 なので、次の区間で旋回を決め直す)
        SkyBossBrain b = d.brain;
        CompoundTag bt = tag.getCompound("brain");
        b.turnSegs = bt.getInt("turnSegs");
        b.turnStep = bt.getInt("turnStep");
        b.turnRamp = bt.getInt("turnRamp");
        b.turnRate = bt.getFloat("turnRate");
        b.turnBank = bt.getFloat("turnBank");
        b.turnDelta = bt.getFloat("turnDelta");
        b.turnRadius = bt.getFloat("turnRadius");
        b.straightLeft = bt.getInt("straightLeft");
        b.maneuver = bt.getLong("maneuver");
        ListTag trail = tag.getList("trail", Tag.TAG_COMPOUND);
        // 古い点から足す
        for (int i = trail.size() - 1; i >= 0; i--) {
            CompoundTag p = trail.getCompound(i);
            f.trail.push(p.getDouble("x"), p.getDouble("y"), p.getDouble("z"));
        }
        if (d.active && f.trail.size() == 0) {
            f.straightTrail(SkyBossTuning.TRAIL_KEEP);
        }
        return d;
    }

    /** 寄り道の状態を保存へ(10-19)。{@link SkyBossFeeding} は Minecraft のクラスを使わないので、ここで書く。 */
    private static CompoundTag saveFeeding(SkyBossFeeding fd) {
        CompoundTag t = new CompoundTag();
        t.putInt("mode", fd.mode);
        t.putLong("modeSince", fd.modeSince);
        t.putLong("stormId", fd.stormId);
        t.putDouble("tx", fd.tx);
        t.putDouble("tz", fd.tz);
        t.putFloat("tRadius", fd.tRadius);
        t.putFloat("lastMaturity", fd.lastMaturity);
        t.putLong("cooldownUntil", fd.cooldownUntil);
        t.putLongArray("fed", fd.fed);
        t.putInt("fedNext", fd.fedNext);
        t.putInt("lookLead", fd.lookLead);
        t.putDouble("lastArmDist", fd.lastArmDist);
        t.putLong("nextStrike", fd.nextStrike);
        t.putFloat("leaveRoll", fd.leaveRoll);
        return t;
    }

    private static void loadFeeding(SkyBossFeeding fd, CompoundTag t) {
        fd.reset();
        if (!t.contains("mode")) {
            return;
        }
        fd.mode = t.getInt("mode");
        fd.modeSince = t.getLong("modeSince");
        fd.stormId = t.getLong("stormId");
        fd.tx = t.getDouble("tx");
        fd.tz = t.getDouble("tz");
        fd.tRadius = t.getFloat("tRadius");
        fd.lastMaturity = t.getFloat("lastMaturity");
        fd.cooldownUntil = t.getLong("cooldownUntil");
        long[] fed = t.getLongArray("fed");
        System.arraycopy(fed, 0, fd.fed, 0, Math.min(fed.length, fd.fed.length));
        fd.fedNext = Math.floorMod(t.getInt("fedNext"), fd.fed.length);
        fd.lookLead = t.getInt("lookLead");
        fd.lastArmDist = t.getDouble("lastArmDist");
        fd.nextStrike = t.getLong("nextStrike");
        // 10-19 から(前の版の保存には無い = 腹が上へ)
        fd.leaveRoll = t.contains("leaveRoll") ? t.getFloat("leaveRoll") : 180.0F;
    }

    /** 放電の状態を保存へ(10-19)。 */
    private static CompoundTag saveDischarge(SkyBossDischarge dc) {
        CompoundTag t = new CompoundTag();
        t.putInt("mode", dc.mode);
        t.putLong("modeSince", dc.modeSince);
        t.putBoolean("inStorm", dc.inStorm);
        t.putFloat("returnRoll", dc.returnRoll);
        t.putLong("rollBackAt", dc.rollBackAt);
        t.putLong("cooldownUntil", dc.cooldownUntil);
        t.putLong("lastStrike", dc.lastStrike);
        return t;
    }

    private static CompoundTag saveCloudSea(SkyBossCloudSea c) {
        CompoundTag t = new CompoundTag();
        t.putInt("mode", c.mode);
        t.putLong("modeSince", c.modeSince);
        t.putDouble("hideY", c.hideY);
        t.putDouble("seaTop", c.seaTop);
        t.putLong("until", c.until);
        t.putLong("nextTry", c.nextTry);
        t.putBoolean("ambush", c.ambush);
        t.putLong("telegraphAt", c.telegraphAt);
        return t;
    }

    private static void loadCloudSea(SkyBossCloudSea c, CompoundTag t) {
        if (!t.contains("mode")) {
            return;
        }
        c.mode = t.getInt("mode");
        c.modeSince = t.getLong("modeSince");
        c.hideY = t.getDouble("hideY");
        c.seaTop = t.getDouble("seaTop");
        c.until = t.getLong("until");
        c.nextTry = t.getLong("nextTry");
        c.ambush = t.getBoolean("ambush");
        c.telegraphAt = t.getLong("telegraphAt");
    }

    private static void loadDischarge(SkyBossDischarge dc, CompoundTag t) {
        dc.reset();
        if (!t.contains("mode")) {
            return;
        }
        dc.mode = t.getInt("mode");
        dc.modeSince = t.getLong("modeSince");
        dc.inStorm = t.getBoolean("inStorm");
        dc.returnRoll = t.getFloat("returnRoll");
        dc.rollBackAt = t.getLong("rollBackAt");
        dc.cooldownUntil = t.getLong("cooldownUntil");
        dc.lastStrike = t.getLong("lastStrike");
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putBoolean("active", active);
        tag.putLong("segmentIndex", segmentIndex);
        tag.putFloat("health", health);
        tag.put("parts", parts.save());
        tag.put("feeding", saveFeeding(feeding));
        tag.put("discharge", saveDischarge(discharge));
        tag.put("cloudSea", saveCloudSea(cloudSea));
        tag.putLong("seed", seed);
        SkyBossFlight f = flight;
        tag.putLong("tick", f.tick);
        tag.putDouble("x", f.x);
        tag.putDouble("y", f.y);
        tag.putDouble("z", f.z);
        tag.putFloat("yaw", f.yaw);
        tag.putFloat("pitch", f.pitch);
        tag.putFloat("speed", f.speed);
        tag.putFloat("yawRate", f.yawRate);
        tag.putFloat("pitchRate", f.pitchRate);
        tag.putFloat("bankTarget", f.bankTarget);
        tag.putFloat("bank", f.bank);
        tag.putFloat("bankMid", f.bankMid);
        tag.putInt("fanMode", f.fanMode);
        tag.putLong("fanModeSince", f.fanModeSince);
        tag.putInt("prevFanMode", f.prevFanMode);
        tag.putLong("prevFanModeSince", f.prevFanModeSince);
        tag.putLong("segmentEnd", f.segmentEnd);
        float[] xf = f.extraFloats();
        int[] xi = new int[xf.length];
        for (int i = 0; i < xf.length; i++) {
            xi[i] = Float.floatToIntBits(xf[i]);
        }
        tag.putIntArray("flightExtraF", xi);
        tag.putLongArray("flightExtraL", f.extraLongs());
        CompoundTag bt = new CompoundTag();
        bt.putInt("turnSegs", brain.turnSegs);
        bt.putInt("turnStep", brain.turnStep);
        bt.putInt("turnRamp", brain.turnRamp);
        bt.putFloat("turnRate", brain.turnRate);
        bt.putFloat("turnBank", brain.turnBank);
        bt.putFloat("turnDelta", brain.turnDelta);
        bt.putFloat("turnRadius", brain.turnRadius);
        bt.putInt("straightLeft", brain.straightLeft);
        bt.putLong("maneuver", brain.maneuver);
        tag.put("brain", bt);
        ListTag trail = new ListTag();
        for (int i = 0; i < f.trail.size(); i++) {
            CompoundTag p = new CompoundTag();
            p.putDouble("x", f.trail.x(i));
            p.putDouble("y", f.trail.y(i));
            p.putDouble("z", f.trail.z(i));
            trail.add(p);
        }
        tag.put("trail", trail);
        return tag;
    }
}

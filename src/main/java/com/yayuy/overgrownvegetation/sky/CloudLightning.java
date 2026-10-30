package com.yayuy.overgrownvegetation.sky;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.network.CloudBoltPayload;
import com.yayuy.overgrownvegetation.registry.ModDamageTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 積乱雲の中を走る雷(2026-10-17 深夜。ユーザー指定「雷雨の際の積乱雲のみで、雲の中に雷が走る。触れたら即死」)。サーバーが決めて当たりも調べる
 * (クライアントは {@link CloudBoltPayload} を受けて描くだけ。見えた雷と当たった雷が必ず同じ)。
 *
 * <ul>
 *   <li>走る条件: 本当の雷雨(雷の強さ 0.5 超え)・設定 {@code cloudLightning}・プレイヤーが積乱雲の塊の中({@link CloudSky#storms}・{@link CloudField#inside})。
 *       下の層・雲の海・雷雨でないときは走らない。観戦モードのまわりには走らせない。</li>
 *   <li>頻度: 1 人のまわりに 30〜80 tick に 1 本(設定 {@code cloudLightningRate} で割る)。場所は積乱雲の中のランダムな所(その人から水平 10〜120 ブロック、
 *       上下 ±50。距離は一様なので近い所ほど密)。10-18 ユーザー「プレイヤーの周辺に雷が走るのを、積乱雲の中でランダムな場所に発生する感じに」で、
 *       それまでの「3 割はその人の今の位置を通る線(狙い)」をやめた。</li>
 *   <li>1 本: 長さ 60〜140 ブロックのぎざぎざの線(12 節)と枝 1 本。前兆 {@link #CHARGE_MIN}〜{@link #CHARGE_MAX} tick(当たらない。クライアントが
 *       バチバチ鳴らし、細い雷が通り道を伸び、横へ探る枝がちらつく。10-18 ユーザー「前兆は先にバチバチなりながら細い雷が走り、そこから太い雷が走る」)
 *       → 太い雷が走る {@link #STRIKE} tick(触れると即死)。前兆を見て離れれば逃げられる。</li>
 *   <li>当たり: 走っている間、線の節ごとに、プレイヤーの当たりの箱を {@link #REACH} 広げた箱と交わるか。ダメージは防具・効果・エンチャント・耐性を貫通
 *       ({@link ModDamageTypes#CLOUD_LIGHTNING})。クリエイティブは死なない。不死のトーテムは効く(バニラの決まりに合わせた仮決め)。</li>
 * </ul>
 * 決めた 2 点の間の雷 {@link #strikeBetween}(2026-10-19。空の巨大生物の避雷針へ落とす雷。空の巨大生物のセッションからの依頼、ユーザー指定 10-19
 * 「雷が避雷針に落ちて体じゅうが身震いするところも入れる」): 見た目・前兆・当たりは同じで、天気と設定 {@code cloudLightning} に関係なく呼んだら走る。
 * 数値はすべて仮決め。サーバーとクライアントの設定(COMMON)が違うと、雲の無い所に雷が見えることがある(1 人で遊ぶときは同じ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class CloudLightning {
    /** 前兆の長さ(tick。1 本ごとにこの間で決める。最初は 16 tick の「ちらちら」だけだった)。 */
    static final int CHARGE_MIN = 36;
    static final int CHARGE_MAX = 50;
    static final int STRIKE = 10;
    /** 当たりの太さ(プレイヤーの箱を広げる幅、ブロック)。 */
    static final double REACH = 1.2;
    /** 雷を受け取るプレイヤーの距離。 */
    static final double SEND_RANGE = 640.0;
    /** 決めた 2 点の間の雷を受け取るプレイヤーの距離の既定(空の巨大生物のボスバーの距離。雷は遠いほど太く描くので 3000 先でも見える)。 */
    public static final double SEND_RANGE_FAR = 3000.0;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final Map<UUID, Integer> NEXT = new HashMap<>();
    private static final List<Bolt> BOLTS = new ArrayList<>();

    private static final class Bolt {
        final List<Vec3> points;
        final int charge;
        final boolean harmful;
        final Vec3 center;
        final double radius;
        final Set<UUID> hit = new HashSet<>();
        int age;

        Bolt(List<Vec3> points, int charge) {
            this(points, charge, true);
        }

        Bolt(List<Vec3> points, int charge, boolean harmful) {
            this.points = points;
            this.charge = charge;
            this.harmful = harmful;
            // 当たりを調べるプレイヤーの範囲(線を囲む球。長い雷でも終わりの方のプレイヤーを落とさない)。
            double x0 = Double.MAX_VALUE;
            double y0 = Double.MAX_VALUE;
            double z0 = Double.MAX_VALUE;
            double x1 = -Double.MAX_VALUE;
            double y1 = -Double.MAX_VALUE;
            double z1 = -Double.MAX_VALUE;
            for (Vec3 p : points) {
                x0 = Math.min(x0, p.x);
                y0 = Math.min(y0, p.y);
                z0 = Math.min(z0, p.z);
                x1 = Math.max(x1, p.x);
                y1 = Math.max(y1, p.y);
                z1 = Math.max(z1, p.z);
            }
            this.center = new Vec3((x0 + x1) * 0.5, (y0 + y1) * 0.5, (z0 + z1) * 0.5);
            this.radius = 0.5 * Math.sqrt((x1 - x0) * (x1 - x0) + (y1 - y0) * (y1 - y0) + (z1 - z0) * (z1 - z0)) + 8.0;
        }
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        try {
            tick(level);
        } catch (RuntimeException e) {
            BOLTS.clear();
            OvergrownVegetation.LOGGER.error("積乱雲の中の雷で例外", e);
        }
    }

    private static void tick(ServerLevel level) {
        for (Iterator<Bolt> it = BOLTS.iterator(); it.hasNext(); ) {
            Bolt b = it.next();
            b.age++;
            if (b.age > b.charge && b.age <= b.charge + STRIKE) {
                strike(level, b);
            }
            if (b.age > b.charge + STRIKE) {
                it.remove();
            }
        }
        if (!OvergrownVegetationConfig.COMMON.cloudLightning.get() || level.getThunderLevel(1.0F) <= 0.5F
                || OvergrownVegetationConfig.COMMON.cumulonimbus.get() <= 0.0) {
            NEXT.clear();
            return;
        }
        if (level.getGameTime() % 5 != 0) {
            return;
        }
        double rate = OvergrownVegetationConfig.COMMON.cloudLightningRate.get();
        if (rate <= 0.0) {
            return;
        }
        float coverage = CloudSky.coverage(level, 1.0F);
        long gameTime = level.getGameTime();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator() || player.getY() < CloudField.LOW_BASE || player.getY() > CloudField.GIANT_TOP + 50) {
                NEXT.remove(player.getUUID());
                continue;
            }
            double fx = CloudField.fieldX(player.getX(), gameTime, 1.0F);
            double fz = CloudField.fieldZ(player.getZ(), gameTime, 1.0F);
            List<CloudField.Storm> storms = CloudSky.storms(level, 1.0F, coverage, fx - 3200.0, fz - 3200.0, fx + 3200.0, fz + 3200.0);
            if (!inStorm(storms, fx, player.getY(), fz)) {
                NEXT.remove(player.getUUID());
                continue;
            }
            int now = (int) (gameTime & 0x3FFFFFFF);
            Integer next = NEXT.get(player.getUUID());
            if (next == null) {
                // 入ってすぐには走らせない(1〜3 秒)。
                NEXT.put(player.getUUID(), now + 20 + RANDOM.nextInt(40));
                continue;
            }
            if (now < next) {
                continue;
            }
            NEXT.put(player.getUUID(), now + (int) Math.round((30 + RANDOM.nextInt(50)) / rate));
            spawn(level, player, storms, gameTime);
        }
    }

    private static boolean inStorm(List<CloudField.Storm> storms, double fx, double y, double fz) {
        for (CloudField.Storm s : storms) {
            // 入道雲(10-18)では雷を走らせない。
            if (s.congestus()) {
                continue;
            }
            double r = s.reach();
            if (Math.abs(fx - s.x()) > r || Math.abs(fz - s.z()) > r || y > s.topY() + 20) {
                continue;
            }
            if (CloudField.inside(CloudField.puffs(s), fx, y, fz)) {
                return true;
            }
        }
        return false;
    }

    private static void spawn(ServerLevel level, ServerPlayer player, List<CloudField.Storm> storms, long gameTime) {
        Vec3 at = player.position().add(0.0, player.getBbHeight() * 0.5, 0.0);
        double length = 60.0 + 80.0 * RANDOM.nextDouble();
        // 向き: 横向きが多い(雲の中を這う雷)。上下は ±35° ほど。
        double yaw = RANDOM.nextDouble() * Math.PI * 2.0;
        double pitch = (RANDOM.nextDouble() - 0.5) * 1.2;
        Vec3 dir = new Vec3(Math.cos(yaw) * Math.cos(pitch), Math.sin(pitch), Math.sin(yaw) * Math.cos(pitch));
        // 通る点: 積乱雲の中のランダムな所(その人を狙わない)。
        Vec3 through = null;
        for (int i = 0; i < 12 && through == null; i++) {
            double a = RANDOM.nextDouble() * Math.PI * 2.0;
            double d = 10.0 + 110.0 * RANDOM.nextDouble();
            Vec3 p = at.add(Math.cos(a) * d, (RANDOM.nextDouble() - 0.5) * 100.0, Math.sin(a) * d);
            if (inStorm(storms, CloudField.fieldX(p.x, gameTime, 1.0F), p.y, CloudField.fieldZ(p.z, gameTime, 1.0F))) {
                through = p;
            }
        }
        if (through == null) {
            return;
        }
        int charge = CHARGE_MIN + RANDOM.nextInt(CHARGE_MAX - CHARGE_MIN + 1);
        // 通る点は線のどこか(3〜7 割の所)。その節だけずらさない。
        int segments = 12;
        int pass = 4 + RANDOM.nextInt(5);
        Vec3 start = through.subtract(dir.scale(length * pass / segments));
        Vec3 side = dir.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0E-4) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            Vec3 p = start.add(dir.scale(length * i / segments));
            if (i != pass && i != 0) {
                double j = 3.0 + 4.0 * RANDOM.nextDouble();
                p = p.add(side.scale((RANDOM.nextDouble() - 0.5) * 2.0 * j)).add(up.scale((RANDOM.nextDouble() - 0.5) * 2.0 * j));
            }
            points.add(p);
        }
        // 枝 1 本(途中の節から 4 節、当たりもある)。
        int from = 2 + RANDOM.nextInt(segments - 4);
        Vec3 bdir = dir.add(side.scale((RANDOM.nextDouble() - 0.5) * 1.6)).add(up.scale((RANDOM.nextDouble() - 0.5) * 1.2)).normalize();
        List<Vec3> branch = new ArrayList<>();
        Vec3 bp = points.get(from);
        branch.add(bp);
        for (int i = 1; i <= 4; i++) {
            bp = bp.add(bdir.scale(length / segments * 0.8)).add(side.scale((RANDOM.nextDouble() - 0.5) * 6.0)).add(up.scale((RANDOM.nextDouble() - 0.5) * 6.0));
            branch.add(bp);
        }
        Bolt bolt = new Bolt(points, charge);
        BOLTS.add(bolt);
        Bolt twig = new Bolt(branch, charge);
        BOLTS.add(twig);
        List<Vec3> all = new ArrayList<>(points);
        all.addAll(branch);
        send(level, all, points.size(), charge);
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CloudLightning {} 通る点 {} {} {} 距離 {} 前兆 {}", player.getName().getString(),
                    Math.round(through.x), Math.round(through.y), Math.round(through.z), Math.round(through.distanceTo(at)), charge);
        }
    }

    private static final boolean DEV_LOG = Boolean.getBoolean("ov.devbridge");

    private static void send(ServerLevel level, List<Vec3> points, int branchStart, int charge) {
        send(level, points, branchStart, charge, SEND_RANGE);
    }

    private static void send(ServerLevel level, List<Vec3> points, int branchStart, int charge, double range) {
        Vec3 o = points.get(0);
        float[] rel = new float[points.size() * 3];
        for (int i = 0; i < points.size(); i++) {
            Vec3 p = points.get(i);
            rel[i * 3] = (float) (p.x - o.x);
            rel[i * 3 + 1] = (float) (p.y - o.y);
            rel[i * 3 + 2] = (float) (p.z - o.z);
        }
        PacketDistributor.sendToPlayersNear(level, null, o.x, o.y, o.z, range, new CloudBoltPayload(o.x, o.y, o.z, rel, branchStart, charge, STRIKE));
    }

    private static void strike(ServerLevel level, Bolt b) {
        for (ServerPlayer player : level.players()) {
            if (!b.harmful || player.isSpectator() || b.hit.contains(player.getUUID()) || player.distanceToSqr(b.center) > b.radius * b.radius) {
                continue;
            }
            AABB box = player.getBoundingBox().inflate(REACH);
            for (int i = 0; i + 1 < b.points.size(); i++) {
                Vec3 a = b.points.get(i);
                Vec3 c = b.points.get(i + 1);
                if (box.contains(a) || box.clip(a, c).isPresent()) {
                    b.hit.add(player.getUUID());
                    if (DEV_LOG) {
                        OvergrownVegetation.LOGGER.info("CloudLightning {} に当たった", player.getName().getString());
                    }
                    // 即死: 残りの体力と吸収を超える量(Float.MAX_VALUE だと受けたダメージの統計が飛ぶ)。
                    player.hurt(ModDamageTypes.cloudLightning(level), player.getHealth() + player.getAbsorptionAmount() + 100.0F);
                    break;
                }
            }
        }
    }

    /**
     * 決めた 2 点の間に雷を 1 本走らせる(サーバーから呼ぶ。2026-10-19、空の巨大生物の避雷針へ落とす雷)。from から to へ、積乱雲の中の雷と同じ見た目
     * (前兆のバチバチと to へ伸びる細い雷 → 太い雷)。天気・設定 {@code cloudLightning} には関係なく走る(呼んだ側が決める)。
     * 節は長さ 12 ブロックに 1 つ(12〜48 節)、ぎざぎざの幅は長さの 4%(3〜14 ブロック)で両端ほど小さく、両端はずらさない(to に必ず届く)。
     * 枝は 1〜2 本(途中の節から to の向きへ斜めに、当たりもある)。数値は仮決め。
     *
     * @param sendRange 雷を受け取るプレイヤーの距離(from から)
     * @param harmful   true = 太い雷に触れたら即死(積乱雲の中の雷と同じ)、false = 見た目だけ
     * @return 太い雷が走り始めて to に届くまでの tick(前兆の長さ。この tick 後から {@value #STRIKE} tick 当たりがある)。from と to が近すぎれば 0(走らない)
     */
    public static int strikeBetween(ServerLevel level, Vec3 from, Vec3 to, boolean harmful, double sendRange) {
        Vec3 axis = to.subtract(from);
        double length = axis.length();
        if (length < 4.0) {
            return 0;
        }
        Vec3 dir = axis.scale(1.0 / length);
        Vec3 side = dir.cross(new Vec3(0.0, 1.0, 0.0));
        if (side.lengthSqr() < 1.0E-4) {
            side = new Vec3(1.0, 0.0, 0.0);
        }
        side = side.normalize();
        Vec3 up = side.cross(dir).normalize();
        int segments = Mth.clamp((int) Math.round(length / 12.0), 12, 48);
        double jag = Mth.clamp(length * 0.04, 3.0, 14.0);
        List<Vec3> points = new ArrayList<>();
        for (int i = 0; i <= segments; i++) {
            Vec3 p = from.add(dir.scale(length * i / segments));
            if (i != 0 && i != segments) {
                double taper = Math.sqrt(Math.sin(Math.PI * i / segments));
                double j = jag * (0.6 + 0.4 * RANDOM.nextDouble()) * taper;
                p = p.add(side.scale((RANDOM.nextDouble() - 0.5) * 2.0 * j)).add(up.scale((RANDOM.nextDouble() - 0.5) * 2.0 * j));
            }
            points.add(p);
        }
        int charge = CHARGE_MIN + RANDOM.nextInt(CHARGE_MAX - CHARGE_MIN + 1);
        BOLTS.add(new Bolt(points, charge, harmful));
        List<Vec3> all = new ArrayList<>(points);
        int branches = 1 + RANDOM.nextInt(2);
        double step = length / segments;
        for (int k = 0; k < branches; k++) {
            int at = 2 + RANDOM.nextInt(Math.max(1, segments - 5));
            Vec3 bdir = dir.add(side.scale((RANDOM.nextDouble() - 0.5) * 1.6)).add(up.scale((RANDOM.nextDouble() - 0.5) * 1.2)).normalize();
            List<Vec3> branch = new ArrayList<>();
            Vec3 bp = points.get(at);
            branch.add(bp);
            int n = 3 + RANDOM.nextInt(3);
            for (int i = 1; i <= n; i++) {
                bp = bp.add(bdir.scale(step * 0.8)).add(side.scale((RANDOM.nextDouble() - 0.5) * jag)).add(up.scale((RANDOM.nextDouble() - 0.5) * jag));
                branch.add(bp);
            }
            BOLTS.add(new Bolt(branch, charge, harmful));
            all.addAll(branch);
        }
        send(level, all, points.size(), charge, sendRange);
        if (DEV_LOG) {
            OvergrownVegetation.LOGGER.info("CloudLightning 2 点の間 {} {} {} → {} {} {} 長さ {} 節 {} 前兆 {} 即死 {}", Math.round(from.x), Math.round(from.y), Math.round(from.z),
                    Math.round(to.x), Math.round(to.y), Math.round(to.z), Math.round(length), segments, charge, harmful);
        }
        return charge;
    }

    /** {@link #strikeBetween(ServerLevel, Vec3, Vec3, boolean, double)} を {@link #SEND_RANGE_FAR} で。 */
    public static int strikeBetween(ServerLevel level, Vec3 from, Vec3 to, boolean harmful) {
        return strikeBetween(level, from, to, harmful, SEND_RANGE_FAR);
    }

    private CloudLightning() {
    }
}

package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.RandomSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 胞子の噴き出し(2026-10-23、萌芽体の胞子嚢)。1 点から上向きの細い円錐へ、小さな粒({@link SporeJetParticle})を
 * 数 tick かけて勢いよく噴く —— 地割れの裂け目から胞子が吹き上がる絵(ユーザー指定)。噴き始めがいちばん強く、だんだん弱まる。
 *
 * <p>粒は 1 つずつ {@link ParticleThrottle#allowFineSpore} を通す(距離 24 ブロックより先は 3 粒に 1 粒・全体の上限・粒子設定)。
 * 数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class SporeJets {

    /** 円錐の半分の開き(ラジアン、約 11 度)。 */
    private static final double CONE = 0.19D;
    /** 噴く速さ(ブロック/tick)の下限・上限。 */
    private static final double SPEED_MIN = 0.15D;
    private static final double SPEED_MAX = 0.30D;
    /** 同時に噴いている数の上限(株が密集したとき)。 */
    private static final int MAX_JETS = 64;

    private record Jet(double x, double y, double z, double ax, double ay, double az, int total, int ticks, int[] state) {
    }

    private static final List<Jet> JETS = new ArrayList<>();
    private static ClientLevel jetLevel;

    private SporeJets() {
    }

    /**
     * (x, y, z) から向き (dx, dy, dz)(胞子嚢の付け根 → 先。上へ寄せて使う)へ、{@code count} 粒を {@code ticks} tick かけて噴く。
     */
    public static void start(double x, double y, double z, double dx, double dy, double dz, int count, int ticks) {
        if (JETS.size() >= MAX_JETS || count <= 0) {
            return;
        }
        // 円錐の軸: ほぼ真上、胞子嚢の向きへ少しだけ傾ける
        double ax = dx * 0.35D, ay = 1.0D, az = dz * 0.35D;
        double len = Math.sqrt(ax * ax + ay * ay + az * az);
        JETS.add(new Jet(x, y, z, ax / len, ay / len, az / len, count, Math.max(1, ticks), new int[]{0, 0}));
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level != jetLevel) {
            JETS.clear();
            jetLevel = level;
        }
        if (level == null || mc.isPaused() || JETS.isEmpty()) {
            return;
        }
        RandomSource r = level.getRandom();
        Iterator<Jet> it = JETS.iterator();
        while (it.hasNext()) {
            Jet j = it.next();
            int tick = j.state()[0]++;
            // 噴き始めが強い: tick ごとの数は (ticks − tick) に比例
            int sum = j.ticks() * (j.ticks() + 1) / 2;
            int want = (int) Math.round((double) j.total() * (j.ticks() - tick) / sum);
            if (tick == j.ticks() - 1) {
                want = j.total() - j.state()[1];
            }
            double strength = 1.0D - 0.5D * tick / j.ticks();
            for (int i = 0; i < want; i++) {
                j.state()[1]++;
                double x = j.x() + (r.nextDouble() - 0.5D) * 0.04D;
                double y = j.y() + r.nextDouble() * 0.04D;
                double z = j.z() + (r.nextDouble() - 0.5D) * 0.04D;
                if (!ParticleThrottle.allowFineSpore(x, y, z, r)) {
                    continue;
                }
                double[] v = cone(j.ax(), j.ay(), j.az(), r);
                double sp = (SPEED_MIN + r.nextDouble() * (SPEED_MAX - SPEED_MIN)) * strength;
                level.addParticle(ModParticles.SPORE_JET.get(), x, y, z, v[0] * sp, v[1] * sp, v[2] * sp);
            }
            if (j.state()[0] >= j.ticks()) {
                it.remove();
            }
        }
    }

    /** 軸 a のまわりの円錐の中のランダムな向き(単位ベクトル)。 */
    private static double[] cone(double ax, double ay, double az, RandomSource r) {
        // 軸に直交する 2 本
        double ux, uy, uz;
        if (Math.abs(ay) < 0.9D) {
            ux = -az; uy = 0; uz = ax;
        } else {
            ux = 0; uy = az; uz = -ay;
        }
        double ul = Math.sqrt(ux * ux + uy * uy + uz * uz);
        ux /= ul; uy /= ul; uz /= ul;
        double wx = ay * uz - az * uy, wy = az * ux - ax * uz, wz = ax * uy - ay * ux;
        double t = CONE * Math.sqrt(r.nextDouble());
        double p = r.nextDouble() * Math.PI * 2;
        double s = Math.sin(t), c = Math.cos(t);
        return new double[]{
                ax * c + (ux * Math.cos(p) + wx * Math.sin(p)) * s,
                ay * c + (uy * Math.cos(p) + wy * Math.sin(p)) * s,
                az * c + (uz * Math.cos(p) + wz * Math.sin(p)) * s};
    }
}

package com.yayuy.overgrownvegetation.client.skyboss;

import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFanClock;
import com.yayuy.overgrownvegetation.skyboss.SkyBossFlight;
import com.yayuy.overgrownvegetation.skyboss.SkyBossPose;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * 空の巨大生物のまわりの小さな演出(クライアント、2026-10-19 夕方。行動パターン「生き物らしさ」)。粒はカメラの近くにだけ出す
 * (サーバーから送る粒は 512 ブロックより先へ届かない。体が 2 km あるので、見ている人のそばだけで足りる)。数・大きさはすべて仮。
 * <ul>
 *   <li>ときどきの身震い({@link SkyBossFlight#SHAKE_CASUAL})の間、扇の刃の先から雨粒(雨の日)・苔のかけらと花粉(晴れの日)が落ちる
 *       (決定「ときどき扇の刃を前から後ろへ波打たせ、雨粒や枯れ葉を振り落とす」)。</li>
 *   <li>落とし物: 体の下側から胞子の雪・苔のかけら・花粉がいつも少しずつ降る(決定「通り道に胞子の雪・枯れ葉・剥がれた樹皮のかけらが降る」。
 *       たどれる跡としての残し方は、遠くでの動きと一緒にユーザーと相談中 = 今は降るだけ)。</li>
 *   <li>雨の日の滝: 雨の間、体の下側の決まった所から、細い水の筋が地上へ降る(決定「体に溜まった雨水が、垂れ草の先から滝のように地上へ降り注ぐ」)。
 *       筋の見える所 = カメラから上下 48 ブロックの所だけに粒を出す(下から見上げると水が降ってくる)。</li>
 * </ul>
 */
final class SkyBossAmbience {

    /** これより遠い体には何も出さない(ブロック。体の表面から)。 */
    private static final double RANGE = 600.0;
    /** 落とし物を出す距離(体の下側の点から)と、1 tick の数。 */
    private static final double DROP_RANGE = 260.0;
    private static final int DROPS_PER_TICK = 5;
    /** 滝の筋の数(体の輪切りを等間隔に)と、筋のまわりで粒を出す横の距離・上下の幅・1 tick の数。 */
    private static final int STREAMS = 18;
    private static final double STREAM_NEAR = 96.0;
    private static final double STREAM_SPAN = 48.0;
    private static final int STREAM_PER_TICK = 6;

    static void tick(ClientLevel level) {
        SkyBossPose ps = ClientSkyBoss.pose();
        if (ps == null || !ps.hasShapes()) {
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        if (ps.nearest(cam.x, cam.y, cam.z) > RANGE) {
            return;
        }
        SkyBossFlight f = ClientSkyBoss.flight();
        long now = level.getGameTime();
        RandomSource r = level.random;
        boolean wet = level.isRaining();
        shiverShower(level, ps, f, now, cam, r, wet);
        droppings(level, ps, cam, r);
        if (wet) {
            waterfalls(level, ps, cam, r, now);
        }
    }

    /** ときどきの身震いの間、扇の刃の先から雨粒・苔のかけらが落ちる。 */
    private static void shiverShower(ClientLevel level, SkyBossPose ps, SkyBossFlight f, long now, Vec3 cam, RandomSource r, boolean wet) {
        if (f.shakeKind != SkyBossFlight.SHAKE_CASUAL || f.shakeAt == SkyBossFlight.NONE || ps.bladeTips == null) {
            return;
        }
        double from = f.shakeAt + SkyBossFanClock.TRANSITION;
        double to = from + SkyBossFanClock.length(SkyBossFanClock.SHIVER);
        if (now < from || now > to) {
            return;
        }
        for (double[] tips : ps.bladeTips) {
            if (tips == null) {
                continue;
            }
            for (int j = 0; j + 2 < tips.length; j += 3) {
                double x = tips[j];
                double y = tips[j + 1];
                double z = tips[j + 2];
                if (sq(x - cam.x) + sq(y - cam.y) + sq(z - cam.z) > 400.0 * 400.0) {
                    continue;
                }
                for (int k = 0; k < 2; k++) {
                    ParticleOptions p = wet ? ParticleTypes.FALLING_WATER
                            : k == 0 ? new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()) : ParticleTypes.FALLING_SPORE_BLOSSOM;
                    level.addAlwaysVisibleParticle(p, true, x + (r.nextDouble() - 0.5) * 8.0, y - r.nextDouble() * 3.0, z + (r.nextDouble() - 0.5) * 8.0,
                            0.0, -0.3, 0.0);
                }
            }
        }
    }

    /** 落とし物: 体の下側(いま下を向いている側)から、胞子・苔のかけら・花粉が少しずつ降る。 */
    private static void droppings(ClientLevel level, SkyBossPose ps, Vec3 cam, RandomSource r) {
        if (ps.sliceCount <= 0) {
            return;
        }
        int made = 0;
        for (int k = 0; k < DROPS_PER_TICK * 3 && made < DROPS_PER_TICK; k++) {
            int i = r.nextInt(ps.sliceCount);
            int o = i * 3;
            double half = ps.sLen[i] * 0.5;
            double cx = ps.sA[o] + ps.sEz[o] * half;
            double cy = ps.sA[o + 1] + ps.sEz[o + 1] * half;
            double cz = ps.sA[o + 2] + ps.sEz[o + 2] * half;
            double rr = Math.max(ps.sRx[i], ps.sRy[i]);
            double side = (r.nextDouble() - 0.5) * 1.2;
            double x = cx + ps.sEx[o] * ps.sRx[i] * side;
            double y = cy - rr * 0.85;
            double z = cz + ps.sEx[o + 2] * ps.sRx[i] * side;
            if (sq(x - cam.x) + sq(y - cam.y) + sq(z - cam.z) > DROP_RANGE * DROP_RANGE) {
                continue;
            }
            int kind = r.nextInt(4);
            ParticleOptions p = kind == 0 ? new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState())
                    : kind == 1 ? ParticleTypes.FALLING_SPORE_BLOSSOM : ModParticles.FINE_SPORE.get();
            level.addAlwaysVisibleParticle(p, true, x, y, z, 0.0, -0.05, 0.0);
            made++;
        }
    }

    /** 雨の日の滝: 決まった所(体の輪切りを等間隔に)の下側から、真下へ細い水の筋。カメラの近くの高さにだけ粒を出す。 */
    private static void waterfalls(ClientLevel level, SkyBossPose ps, Vec3 cam, RandomSource r, long now) {
        int n = ps.sliceCount;
        if (n <= 0) {
            return;
        }
        for (int s = 0; s < STREAMS; s++) {
            int i = Math.min(n - 1, (int) ((s + 0.5) * n / STREAMS));
            int o = i * 3;
            double half = ps.sLen[i] * 0.5;
            double rr = Math.max(ps.sRx[i], ps.sRy[i]);
            // 左右へ交互に(垂れ草の先の辺り)
            double side = (s % 2 == 0 ? 0.45 : -0.45);
            double x = ps.sA[o] + ps.sEz[o] * half + ps.sEx[o] * ps.sRx[i] * side;
            double top = ps.sA[o + 1] + ps.sEz[o + 1] * half - rr * 0.8;
            double z = ps.sA[o + 2] + ps.sEz[o + 2] * half + ps.sEx[o + 2] * ps.sRx[i] * side;
            if (sq(x - cam.x) + sq(z - cam.z) > STREAM_NEAR * STREAM_NEAR || cam.y > top + STREAM_SPAN) {
                continue;
            }
            double lo = Math.max(level.getMinBuildHeight(), cam.y - STREAM_SPAN);
            double hi = Math.min(top, cam.y + STREAM_SPAN);
            if (hi <= lo) {
                continue;
            }
            for (int k = 0; k < STREAM_PER_TICK; k++) {
                double y = lo + r.nextDouble() * (hi - lo);
                level.addAlwaysVisibleParticle(ParticleTypes.FALLING_WATER, true, x + (r.nextDouble() - 0.5) * 2.5, y, z + (r.nextDouble() - 0.5) * 2.5,
                        0.0, -1.0, 0.0);
            }
        }
    }

    private static double sq(double v) {
        return v * v;
    }

    private SkyBossAmbience() {
    }
}

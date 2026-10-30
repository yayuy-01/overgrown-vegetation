package com.yayuy.overgrownvegetation.skyboss;

import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.particles.SimpleParticleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;

/**
 * 弱点が壊れたときの粒と音(サーバーから。2026-10-18 夜、仮)。技・エフェクトの段で作り直す前の、壊れたと分かるだけの印。
 * 体が大きいので、大きさを持たせた粒({@link ModParticles#SKY_BOSS_BLAST}・{@link ModParticles#SKY_BOSS_SMOKE}、バニラの爆発・煙の絵)を遠く(512 ブロック)まで送る。
 * 近くで見たときの細かさにバニラの火花・苔のかけらも少し。
 */
final class SkyBossEffects {

    /** 蕾がはじける: 小さな爆発と煙、苔のかけらと胞子。 */
    static void budBurst(ServerLevel level, double x, double y, double z, double r) {
        RandomSource rnd = level.getRandom();
        for (int i = 0; i < 2; i++) {
            big(level, ModParticles.SKY_BOSS_BLAST.get(), x + spread(rnd, r * 0.4), y + spread(rnd, r * 0.4), z + spread(rnd, r * 0.4), r * 1.4, i * 2);
        }
        for (int i = 0; i < 4; i++) {
            big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, r * 0.6), y + spread(rnd, r * 0.6), z + spread(rnd, r * 0.6), r * 1.6, 2 + rnd.nextInt(4));
        }
        send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), x, y, z, 120, r * 0.6, 0.3);
        send(level, ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 80, r, 0.05);
        level.playSound(null, x, y, z, SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 8.0F, 0.5F);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 4.0F, 1.5F);
    }

    /**
     * 避雷針が爆発する(rod_short の爆発の時刻に): 根元の蓄電の球で大きな閃き、尖塔は先の節から根元へ順に爆発して消え(アニメと同じ順)、あとに煙が残る。
     */
    static void rodBoom(ServerLevel level, SkyBossPose ps) {
        SkyBossShapes s = SkyBossShapes.get();
        int first = ps.rodFirstCapsule();
        if (s == null || first < 0) {
            return;
        }
        RandomSource rnd = level.getRandom();
        int n = ps.capCount - first;
        double cx = 0.0;
        double cy = 0.0;
        double cz = 0.0;
        for (int i = first; i < ps.capCount; i++) {
            int k = i - first;
            int o = i * 3;
            // 壊れた避雷針は形から外れて半径 0 なので、太さは形の元から
            double r = Math.max(4.0, Math.max(s.rodRadius[k], 1.0F) * SkyBossTuning.BLOCKS_PER_UNIT);
            // 先(k が大きい)ほど早く
            int delay = n > 1 ? (int) Math.round((n - 1 - k) * 7.0 / (n - 1)) : 0;
            for (int j = 0; j < 3; j++) {
                double u = 0.2 + 0.3 * j;
                double x = ps.cA[o] + (ps.cB[o] - ps.cA[o]) * u + spread(rnd, r);
                double y = ps.cA[o + 1] + (ps.cB[o + 1] - ps.cA[o + 1]) * u + spread(rnd, r);
                double z = ps.cA[o + 2] + (ps.cB[o + 2] - ps.cA[o + 2]) * u + spread(rnd, r);
                big(level, ModParticles.SKY_BOSS_BLAST.get(), x, y, z, r * 2.2, delay + rnd.nextInt(3));
            }
            for (int j = 0; j < 1; j++) {
                double u = rnd.nextDouble();
                big(level, ModParticles.SKY_BOSS_SMOKE.get(), ps.cA[o] + (ps.cB[o] - ps.cA[o]) * u + spread(rnd, r),
                        ps.cA[o + 1] + (ps.cB[o + 1] - ps.cA[o + 1]) * u + spread(rnd, r),
                        ps.cA[o + 2] + (ps.cB[o + 2] - ps.cA[o + 2]) * u + spread(rnd, r), r * 2.4, delay + 6 + rnd.nextInt(6));
            }
            double mx = (ps.cA[o] + ps.cB[o]) * 0.5;
            double my = (ps.cA[o + 1] + ps.cB[o + 1]) * 0.5;
            double mz = (ps.cA[o + 2] + ps.cB[o + 2]) * 0.5;
            send(level, ParticleTypes.ELECTRIC_SPARK, mx, my, mz, 80, r, 0.8);
            cx += mx;
            cy += my;
            cz += mz;
        }
        // 根元の蓄電の球が弾ける(粒の画素がボスの絵の画素 = 3 ブロックほどになる大きさまで)
        int o = first * 3;
        double r0 = Math.max(6.0, Math.max(s.rodRadius[0], 1.0F) * SkyBossTuning.BLOCKS_PER_UNIT);
        for (int j = 0; j < 4; j++) {
            big(level, ModParticles.SKY_BOSS_BLAST.get(), ps.cA[o] + spread(rnd, r0), ps.cA[o + 1] + spread(rnd, r0), ps.cA[o + 2] + spread(rnd, r0),
                    Math.min(48.0, r0 * (j == 0 ? 2.0 : 1.3)), j);
        }
        cx /= n;
        cy /= n;
        cz /= n;
        level.playSound(null, cx, cy, cz, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 30.0F, 0.5F);
        level.playSound(null, cx, cy, cz, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.HOSTILE, 20.0F, 0.6F);
    }

    // ------------------------------------------------------------------ 技(10-19。数・大きさは仮)
    /** 胞子の塊の炸裂(ユーザー指定 10-19「小規模な爆発を起こして周囲に胞子がばら撒かれる演出」): 小さな爆発と、胞子・花粉の散らばり。 */
    static void sporeBurst(ServerLevel level, double x, double y, double z, double r) {
        RandomSource rnd = level.getRandom();
        big(level, ModParticles.SKY_BOSS_BLAST.get(), x, y, z, r * 1.3, 0);
        big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, r * 0.3), y + spread(rnd, r * 0.3), z + spread(rnd, r * 0.3), r * 1.6, 2);
        send(level, ModParticles.OVERGROWN_SPORE_SHORT.get(), x, y, z, 60, r * 0.6, 0.12);
        send(level, ParticleTypes.SPORE_BLOSSOM_AIR, x, y, z, 24, r * 0.8, 0.0);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 2.5F, 1.7F);
        level.playSound(null, x, y, z, SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 3.0F, 0.6F);
    }

    /** 樹液の弾がはじける: 蜂蜜の滴(琥珀色)が飛び散る。 */
    static void sapSplash(ServerLevel level, double x, double y, double z, double r) {
        send(level, ParticleTypes.FALLING_HONEY, x, y, z, 40, r * 0.5, 0.0);
        send(level, ParticleTypes.DRIPPING_HONEY, x, y, z, 16, r * 0.3, 0.0);
        level.playSound(null, x, y, z, SoundEvents.HONEY_BLOCK_BREAK, SoundSource.HOSTILE, 3.0F, 0.6F);
    }

    /** 弾を吸う(決定「当たった所の苔が一瞬波打ち、弾が沈み込んで消える」): 苔のかけらの輪と小さな胞子。 */
    static void ripple(ServerLevel level, double x, double y, double z) {
        send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), x, y, z, 24, 1.6, 0.15);
        send(level, ModParticles.FINE_SPORE.get(), x, y, z, 10, 1.0, 0.02);
        level.playSound(null, x, y, z, SoundEvents.MOSS_STEP, SoundSource.HOSTILE, 1.5F, 0.6F);
    }

    /** 下降気流: 打ち下ろした扇の先から、雲の尾が下へ噴き出す(大きな煙を下へずらしながら少しずつ遅らせて並べる)。 */
    static void downdraftGust(ServerLevel level, SkyBossPose ps) {
        if (ps.bladeTips == null) {
            return;
        }
        RandomSource rnd = level.getRandom();
        for (double[] tips : ps.bladeTips) {
            if (tips == null) {
                continue;
            }
            int n = tips.length / 3;
            for (int i = 0; i < n; i += 3) {
                double x = tips[i * 3];
                double y = tips[i * 3 + 1];
                double z = tips[i * 3 + 2];
                for (int k = 0; k < 4; k++) {
                    big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, 6.0), y - 18.0 - k * 26.0, z + spread(rnd, 6.0), 18.0 + k * 7.0, k * 2);
                }
            }
        }
        double[] c = ps.points[(2 + 12) / 2 + 1];
        level.playSound(null, c[0], c[1] - 100.0, c[2], SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 40.0F, 0.3F);
    }

    /** 雲を吐く: 顎の前から胞子の混じった霧が噴き出す(雲そのものは雲の作業の CloudPuffs)。 */
    static void spitMist(ServerLevel level, double x, double y, double z, double[] forward) {
        RandomSource rnd = level.getRandom();
        for (int k = 0; k < 8; k++) {
            double d = 20.0 + k * 35.0;
            big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + forward[0] * d + spread(rnd, 8.0), y - k * 6.0 + spread(rnd, 8.0), z + forward[2] * d + spread(rnd, 8.0),
                    24.0 + k * 9.0, k * 2);
        }
        send(level, ModParticles.FINE_SPORE.get(), x, y, z, 80, 20.0, 0.05);
        level.playSound(null, x, y, z, SoundEvents.ELYTRA_FLYING, SoundSource.HOSTILE, 30.0F, 0.4F);
    }

    /** 種の爆弾を打ち上げる: 腹の扉から煙と胞子が噴き出し、低い破裂音。 */
    static void seedLaunch(ServerLevel level, double x, double y, double z, double[] out) {
        RandomSource rnd = level.getRandom();
        for (int k = 0; k < 3; k++) {
            big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + out[0] * (6 + k * 8) + spread(rnd, 4.0), y + out[1] * (6 + k * 8) + spread(rnd, 4.0),
                    z + out[2] * (6 + k * 8) + spread(rnd, 4.0), 10.0 + k * 5.0, k * 2);
        }
        send(level, ModParticles.FINE_SPORE.get(), x, y, z, 40, 6.0, 0.08);
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.HOSTILE, 20.0F, 0.4F);
        level.playSound(null, x, y, z, SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.HOSTILE, 20.0F, 0.3F);
    }

    /** 種の爆弾が地面で爆発する: 爆発と煙、苔のかけらと胞子が広がる(汚染そのものは SkyBossAttacks が)。 */
    static void seedBurst(ServerLevel level, double x, double y, double z, double r) {
        RandomSource rnd = level.getRandom();
        big(level, ModParticles.SKY_BOSS_BLAST.get(), x, y + 1.0, z, r * 1.2, 0);
        for (int i = 0; i < 3; i++) {
            big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, r * 0.5), y + 1.5 + i * 1.5, z + spread(rnd, r * 0.5), r * 1.4, 2 + i * 2);
        }
        send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), x, y + 0.5, z, 60, r * 0.4, 0.3);
        send(level, ModParticles.OVERGROWN_SPORE_SHORT.get(), x, y + 1.0, z, 80, r * 0.7, 0.1);
        send(level, ParticleTypes.SPORE_BLOSSOM_AIR, x, y + 1.0, z, 30, r, 0.0);
        level.playSound(null, x, y, z, SoundEvents.GENERIC_EXPLODE.value(), SoundSource.HOSTILE, 5.0F, 0.8F);
        level.playSound(null, x, y, z, SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 4.0F, 0.5F);
    }

    /** 転がりのビームの後、開いた蕾から熱を逃がす雲を噴く(案「蕾が閉じて熱を逃がす雲を噴く」)。 */
    static void beamSteam(ServerLevel level, SkyBossPose ps, SkyBossParts parts) {
        SkyBossShapes s = SkyBossShapes.get();
        if (s == null) {
            return;
        }
        RandomSource rnd = level.getRandom();
        double[] d = new double[3];
        for (int i = 0; i < s.budCount; i++) {
            if (!parts.budSolid(i)) {
                continue;
            }
            int o = i * 3;
            ps.budOutward(i, d);
            double r = s.budRadius[i] * SkyBossTuning.BLOCKS_PER_UNIT;
            for (int k = 0; k < 2; k++) {
                double u = r + 4.0 + k * 10.0;
                big(level, ModParticles.SKY_BOSS_SMOKE.get(), ps.spC[o] + d[0] * u, ps.spC[o + 1] + d[1] * u, ps.spC[o + 2] + d[2] * u,
                        r * 2.2 + k * 6.0, k * 3 + rnd.nextInt(12));
            }
        }
        double[] c = ps.points[ps.points.length / 2];
        level.playSound(null, c[0], c[1], c[2], SoundEvents.FIRE_EXTINGUISH, SoundSource.HOSTILE, 40.0F, 0.5F);
        level.playSound(null, c[0], c[1], c[2], SoundEvents.BEACON_DEACTIVATE, SoundSource.HOSTILE, 40.0F, 0.5F);
    }

    /** 段階が変わるとき: 体じゅうから苔のかけら・胞子・花粉が降る(決定「垂れ草と胞子を振り落とし」)。 */
    static void stageShower(ServerLevel level, SkyBossPose ps) {
        RandomSource rnd = level.getRandom();
        for (int i = 0; i < ps.sliceCount; i += 3) {
            int o = i * 3;
            double x = ps.sA[o] + ps.sEz[o] * ps.sLen[i] * 0.5;
            double y = ps.sA[o + 1] + ps.sEz[o + 1] * ps.sLen[i] * 0.5;
            double z = ps.sA[o + 2] + ps.sEz[o + 2] * ps.sLen[i] * 0.5;
            double r = Math.max(ps.sRx[i], ps.sRy[i]);
            send(level, new BlockParticleOption(ParticleTypes.BLOCK, Blocks.MOSS_BLOCK.defaultBlockState()), x, y, z, 50, r, 0.2);
            send(level, ParticleTypes.FALLING_SPORE_BLOSSOM, x, y - r * 0.5, z, 40, r, 0.0);
            send(level, ModParticles.FINE_SPORE.get(), x, y, z, 30, r, 0.05);
            if (rnd.nextInt(3) == 0) {
                big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, r), y - r, z + spread(rnd, r), r * 0.8, rnd.nextInt(10));
            }
        }
        double[] c = ps.points[ps.points.length / 2];
        level.playSound(null, c[0], c[1], c[2], SoundEvents.MOSS_BREAK, SoundSource.HOSTILE, 40.0F, 0.4F);
        level.playSound(null, c[0], c[1], c[2], SoundEvents.ENDER_DRAGON_FLAP, SoundSource.HOSTILE, 40.0F, 0.4F);
    }

    /** 潮吹き(雲海から出たとき。決定「鯨の潮吹きのように胞子の混じった霧を高く噴き上げる」): 顎から上へ胞子と煙、低い噴き出しの音(霧の柱は吐いた雲)。 */
    static void spout(ServerLevel level, double x, double y, double z) {
        RandomSource rnd = level.getRandom();
        for (int k = 0; k < 6; k++) {
            big(level, ModParticles.SKY_BOSS_SMOKE.get(), x + spread(rnd, 6.0), y + 20.0 + k * 30.0, z + spread(rnd, 6.0), 18.0 + k * 6.0, k * 2);
        }
        send(level, ModParticles.FINE_SPORE.get(), x, y + 40.0, z, 120, 12.0, 0.2);
        level.playSound(null, x, y, z, SoundEvents.PUFFER_FISH_BLOW_OUT, SoundSource.HOSTILE, 40.0F, 0.3F);
        level.playSound(null, x, y, z, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, SoundSource.HOSTILE, 30.0F, 0.3F);
    }

    /** 大きな粒 1 つ(数 0 で送ると速さがそのまま粒へ: x = 大きさ、y = 遅れ)。 */
    private static void big(ServerLevel level, SimpleParticleType type, double x, double y, double z, double size, int delay) {
        for (ServerPlayer p : level.players()) {
            level.sendParticles(p, type, true, x, y, z, 0, size, delay, 0.0, 1.0);
        }
    }

    private static void send(ServerLevel level, ParticleOptions type, double x, double y, double z, int count, double spread, double speed) {
        for (ServerPlayer p : level.players()) {
            level.sendParticles(p, type, true, x, y, z, count, spread, spread, spread, speed);
        }
    }

    private static double spread(RandomSource rnd, double r) {
        return (rnd.nextDouble() * 2.0 - 1.0) * r;
    }

    private SkyBossEffects() {
    }
}

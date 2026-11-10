package com.yayuy.overgrownvegetation.client.atmosphere.heat;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HeatFxTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/**
 * <b>熱水の森の中にいる度合い</b>(0〜1。2026-11-10 熱水の森のシェーダー段階 1)。暗さ({@link HeatFxShade})と霧({@link HeatFxFog})の強さに掛ける。
 *
 * <p><b>床だけを数える</b>(11-10 追記 1 で作り直し。ユーザー決定「境目をもっと広く、ゆっくりに」)。半径 {@code radius} の円の中の列を毎 tick
 * {@code samplesPerTick} 本選び、カメラの少し上から下へ {@code scanDown} まで、最初の床(上が空気の面。床の植物を含む)を探す。
 * その床が熱水の森のブロック({@code ModBlocks.heatForestBlocks()})かを、横の距離が近いほど重く(1 − (d / 半径)²)数え、なだらかに忘れる和にする。
 * 重み付きの割合を {@code ratioLow}〜{@code ratioHigh} のなめらかな段で 0〜1 にし、{@code followTicks} かけて追う。
 *
 * <p>前の数え方(半径 24 の箱の、上か下が空いたブロック全部)は、繁茂の森で床だけが熱水の森になり、壁と天井がネザーラックのまま残る所で
 * 割合が低く出た(x −76 で床が森なのに 0.07)。床だけを数え、近くを重くしたので、森の床に立てば効き、遠くに森が見えているだけでは効かない。
 * ネザーの次元の中だけ。適応の前(最初の 10 分)は熱水の森のブロックが無いので自然に 0。通信は使わない。
 */
public final class HeatFxPresence {

    private static final RandomSource RNG = RandomSource.create();
    private static Set<Block> heatBlocks;
    private static double heatSum;
    private static double floorSum;
    private static float ratio;
    private static float presence;
    /** 確かめ用: 0〜1 なら度合いをこの値に固定する(負 = 数える)。 */
    static float devForce = -1.0F;

    private HeatFxPresence() {
    }

    /** 今の度合い(0〜1)。 */
    public static float presence() {
        return devForce >= 0.0F ? Math.min(1.0F, devForce) : presence;
    }

    /** 数えた割合(確かめ用)。 */
    static float ratio() {
        return ratio;
    }

    static void clear() {
        heatSum = 0.0;
        floorSum = 0.0;
        ratio = 0.0F;
        presence = 0.0F;
    }

    private static Set<Block> heatBlocks() {
        if (heatBlocks == null) {
            Set<Block> set = Collections.newSetFromMap(new IdentityHashMap<>());
            ModBlocks.heatForestBlocks().forEach(b -> set.add(b.get()));
            heatBlocks = set;
        }
        return heatBlocks;
    }

    static void tick(ClientLevel level, Vec3 cam) {
        if (level.dimension() != Level.NETHER) {
            clear();
            return;
        }
        HeatFxTable t = DesignData.heatFx();
        double keep = 1.0 - 1.0 / t.memoryTicks();
        heatSum *= keep;
        floorSum *= keep;
        float r = t.radius();
        int n = Math.max(1, Math.round(t.samplesPerTick()));
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < n; i++) {
            // 円の中に一様に(棄却法)
            double dx;
            double dz;
            do {
                dx = (RNG.nextDouble() * 2.0 - 1.0) * r;
                dz = (RNG.nextDouble() * 2.0 - 1.0) * r;
            } while (dx * dx + dz * dz > r * r);
            double w = 1.0 - (dx * dx + dz * dz) / (r * r);
            BlockState floor = floorBelow(level, p, Mth.floor(cam.x + dx), Mth.floor(cam.y), Mth.floor(cam.z + dz), Math.round(t.scanDown()));
            if (floor == null) {
                continue;
            }
            floorSum += w;
            if (heatBlocks().contains(floor.getBlock())) {
                heatSum += w;
            }
        }
        ratio = floorSum > 0.5 ? (float) (heatSum / floorSum) : 0.0F;
        float target = smooth((ratio - t.ratioLow()) / (t.ratioHigh() - t.ratioLow()));
        presence += (target - presence) / t.followTicks();
        if (presence < 0.0005F) {
            presence = 0.0F;
        }
    }

    /**
     * 列 (x, z) の、カメラの高さ {@code cy} の 2 つ上から下へ {@code depth} までの最初の床(上が空気のブロック)。見つからない・
     * 先に液体(溶岩の海の表面)に当たった・読み込まれていないときは null。
     */
    private static BlockState floorBelow(ClientLevel level, BlockPos.MutableBlockPos p, int x, int cy, int z, int depth) {
        p.set(x, cy + 2, z);
        if (!level.isLoaded(p)) {
            return null;
        }
        boolean airAbove = false;
        int bottom = Math.max(level.getMinBuildHeight(), cy - depth);
        for (int y = Math.min(cy + 2, level.getMaxBuildHeight() - 1); y >= bottom; y--) {
            BlockState s = level.getBlockState(p.setY(y));
            if (s.isAir()) {
                airAbove = true;
                continue;
            }
            if (!s.getFluidState().isEmpty() && airAbove) {
                return null;
            }
            if (airAbove) {
                return s;
            }
        }
        return null;
    }

    /**
     * 確かめ用(DevBridge の {@code fog heat scan})。今の場所で、前の数え方(半径 24 の箱の、上か下が空いたブロック)と今の数え方(床の円)を
     * 漏れなく数えて比べる。前の数え方の熱水の森でないブロックは、床(上が空気)・天井(下が空気)・壁に分けて多い順に出す。
     */
    static String scan(ClientLevel level, Vec3 cam) {
        Set<Block> heat = heatBlocks();
        int cx = Mth.floor(cam.x);
        int cy = Mth.floor(cam.y);
        int cz = Mth.floor(cam.z);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int oldHeat = 0;
        int[] other = new int[3];
        Map<String, Integer> kinds = new HashMap<>();
        for (int x = -24; x <= 24; x++) {
            for (int y = -24; y <= 24; y++) {
                for (int z = -24; z <= 24; z++) {
                    p.set(cx + x, cy + y, cz + z);
                    if (level.isOutsideBuildHeight(p) || !level.isLoaded(p)) {
                        continue;
                    }
                    BlockState s = level.getBlockState(p);
                    if (s.isAir() || !s.getFluidState().isEmpty()) {
                        continue;
                    }
                    if (heat.contains(s.getBlock())) {
                        oldHeat++;
                        continue;
                    }
                    boolean up = level.getBlockState(p.setY(cy + y + 1)).isAir();
                    boolean down = level.getBlockState(p.setY(cy + y - 1)).isAir();
                    if (!up && !down) {
                        continue;
                    }
                    int k = up ? 0 : 1;
                    if (up && down) {
                        k = 2;
                    }
                    other[k]++;
                    kinds.merge(BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath() + (up ? "(床)" : "(天井)"), 1, Integer::sum);
                }
            }
        }
        int oldAll = oldHeat + other[0] + other[1] + other[2];
        StringBuilder top = new StringBuilder();
        kinds.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue()).limit(6)
                .forEach(e -> top.append(e.getKey()).append(' ').append(e.getValue()).append(' '));
        HeatFxTable t = DesignData.heatFx();
        float r = t.radius();
        double hw = 0;
        double fw = 0;
        int floors = 0;
        int heatFloors = 0;
        int ri = (int) r;
        for (int x = -ri; x <= ri; x += 2) {
            for (int z = -ri; z <= ri; z += 2) {
                double d2 = x * x + z * z;
                if (d2 > r * r) {
                    continue;
                }
                BlockState f = floorBelow(level, p, cx + x, cy, cz + z, Math.round(t.scanDown()));
                if (f == null) {
                    continue;
                }
                double w = 1.0 - d2 / (r * r);
                floors++;
                fw += w;
                if (heat.contains(f.getBlock())) {
                    heatFloors++;
                    hw += w;
                }
            }
        }
        return String.format("前の数え方 %.3f(熱 %d / 表 %d。熱でない: 床 %d 天井 %d 両方 %d: %s)/ 今の数え方 重み付き %.3f(床 %d 本のうち熱 %d)",
                oldAll == 0 ? 0.0 : oldHeat / (double) oldAll, oldHeat, oldAll, other[0], other[1], other[2], top.toString().trim(),
                fw == 0 ? 0.0 : hw / fw, floors, heatFloors);
    }

    static float smooth(float x) {
        x = Mth.clamp(x, 0.0F, 1.0F);
        return x * x * (3.0F - 2.0F * x);
    }
}

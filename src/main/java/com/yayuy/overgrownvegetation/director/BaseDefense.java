package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Locale;

/**
 * 拠点のまわりの「守りの方角」(2026-11-14、司令塔 第 3 段 区切り 2「弱い所を突く」。11-13 ユーザー「火・明かりで守られた方角を避け、
 * 守りの薄い方角から伸ばす」、11-14 ユーザー W1 = 明るさ + 危ない所の方位)。
 *
 * <p>拠点の真ん中から {@code weakSectors} 方位に分け、方位ごとに輪(真ん中から {@code weakRingMin}〜{@code weakRingMax} ブロック)の上の
 * 何点かで、地面のすぐ上のブロックの明るさ(松明・ランタン・かまど・焚き火など。空の光は数えない)を読む。方位の守り =
 * 明るさの平均(0〜1)+ その方位に危ない所があれば {@code weakDangerWeight}。いちばん小さい方位が「弱い方角」。
 * 読み込まれていない所の点は読まない(その方位の点が 1 つも読めなければ、守りは分からない = 選ばない)。
 * 防衛設備(退避中)は、戻したら守りに足す。
 */
public final class BaseDefense {

    private BaseDefense() {
    }

    /** 方位 i の向き(ラジアン。0 = 東、反時計回りではなく Minecraft の x・z の atan2 の向き)。 */
    public static double angleOf(int i, int sectors) {
        return 2.0D * Math.PI * i / sectors;
    }

    /** 方位ごとの守り(読めなかった方位は NaN)。 */
    public static double[] measure(ServerLevel level, BaseMemory.Base b, double day) {
        BaseMemoryTable t = DesignData.director().base;
        int n = t.weakSectors;
        double[] out = new double[n];
        KillMemory kills = KillMemory.get(level.getServer());
        var dangers = kills.dangers(day);
        for (int i = 0; i < n; i++) {
            double sum = 0.0D;
            int read = 0;
            for (int s = 0; s < t.weakSamplesPerSector; s++) {
                // 方位の幅の中を、角度と距離を少しずつずらして読む
                double frac = (s + 0.5D) / t.weakSamplesPerSector;
                double ang = angleOf(i, n) + (frac - 0.5D) * (2.0D * Math.PI / n);
                double r = t.weakRingMin + (t.weakRingMax - t.weakRingMin) * ((s * 7 % t.weakSamplesPerSector) + 0.5D) / t.weakSamplesPerSector;
                int x = (int) Math.floor(b.centerX() + Math.cos(ang) * r);
                int z = (int) Math.floor(b.centerZ() + Math.sin(ang) * r);
                if (!level.hasChunk(x >> 4, z >> 4)) continue;
                int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos p = new BlockPos(x, y, z);
                int light = Math.max(level.getBrightness(LightLayer.BLOCK, p), level.getBrightness(LightLayer.BLOCK, p.above()));
                sum += light / 15.0D;
                read++;
            }
            if (read == 0) {
                out[i] = Double.NaN;
                continue;
            }
            double defense = sum / read;
            for (KillMemory.Danger d : dangers) {
                double dx = d.x() - b.centerX(), dz = d.z() - b.centerZ();
                if (dx * dx + dz * dz > t.weakRingMax * t.weakRingMax * 2.25D) continue; // 拠点から遠い危ない所は数えない
                if (sectorOf(Math.atan2(dz, dx), n) == i) defense += t.weakDangerWeight;
            }
            out[i] = defense;
        }
        return out;
    }

    /** 角度(ラジアン)の入る方位。 */
    public static int sectorOf(double angle, int sectors) {
        double a = angle;
        double w = 2.0D * Math.PI / sectors;
        a = ((a + w / 2.0D) % (2.0D * Math.PI) + 2.0D * Math.PI) % (2.0D * Math.PI);
        return (int) Math.floor(a / w) % sectors;
    }

    /** いちばん守りの薄い方位(読めた方位が無ければ −1)。同じなら番号の小さい方。 */
    public static int weakest(double[] defense) {
        int best = -1;
        for (int i = 0; i < defense.length; i++) {
            if (Double.isNaN(defense[i])) continue;
            if (best < 0 || defense[i] < defense[best] - 1.0E-9D) best = i;
        }
        return best;
    }

    public static String describe(double[] defense, int weak) {
        StringBuilder sb = new StringBuilder();
        String[] names = {"東", "南東", "南", "南西", "西", "北西", "北", "北東"};
        for (int i = 0; i < defense.length; i++) {
            if (i > 0) sb.append("・");
            String name = defense.length == 8 ? names[i] : String.valueOf(i);
            sb.append(name).append(Double.isNaN(defense[i]) ? " ?" : String.format(Locale.ROOT, " %.2f", defense[i]));
            if (i == weak) sb.append("(弱い)");
        }
        return sb.toString();
    }
}

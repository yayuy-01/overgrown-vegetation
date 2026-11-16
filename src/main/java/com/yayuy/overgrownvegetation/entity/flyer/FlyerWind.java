package com.yayuy.overgrownvegetation.entity.flyer;

import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.WindWeatherData;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

/**
 * 飛ぶ型が流される風(2026-11-15)。世界に 1 つの卓越風({@link PrevailingWind})を、雲の流れ({@code sky/CloudDrift})と同じ口
 * ({@link WindWeatherData})から読む。胞子の場({@code SporeFieldServer#windNow})は場が無いと空になるので使わない。
 * オーバーワールドだけ(ほかの次元は風 0)。同じ tick の中は覚えておく(サーバースレッドから)。
 */
public final class FlyerWind {

    /** 風の強さ 1(ふだんの午後)の速さ(ブロック/tick)【仮】。巡航 0.7 に対して風上 0.6・風下 0.8。 */
    public static final double BLOCKS_PER_STRENGTH = 0.1D;
    /** 強さの上限【仮】(嵐 3 は雨の間 = 止まり木にいるので効かないが、飛び立った直後の暴れを抑える)。 */
    public static final double STRENGTH_CAP = 1.5D;

    /**
     * 強風の日(2026-11-16 weather-system、【仮】): 流される型({@link #drift})の強さの上限(強風の度合いで {@link #STRENGTH_CAP} からこれへ)。
     * 強風の日の強さは 1.95 なので、上限 1.5 のままだと「速く流される」が出ない。
     */
    public static final double GALE_CAP = 2.0D;

    private static final PrevailingWind.Sample SAMPLE = new PrevailingWind.Sample();
    private static long cachedTick = Long.MIN_VALUE;
    private static double cx, cz, dirX, dirZ, groundStrength, gale;

    private FlyerWind() {
    }

    private static boolean refresh(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) return false;
        long t = level.getGameTime();
        if (t != cachedTick) {
            MinecraftServer server = level.getServer();
            PrevailingWind.sample(WindWeatherData.windSeed(server), level.getDayTime(), WindWeatherData.get(server).weather(), SAMPLE);
            // 凪は飛ぶ型には効かせない(11-16 全体管理了承の案「飛ぶ Mob への効きは強風の日だけ・ほかの種は今のまま」)。上空の風 strength のまま
            groundStrength = SAMPLE.strength;
            gale = SAMPLE.gale;
            dirX = SAMPLE.dirX;
            dirZ = SAMPLE.dirZ;
            double s = Math.min(groundStrength, STRENGTH_CAP) * BLOCKS_PER_STRENGTH;
            cx = dirX * s;
            cz = dirZ * s;
            cachedTick = t;
        }
        return true;
    }

    /** 今の風(ブロック/tick)を out[0..1] へ。 */
    public static void now(ServerLevel level, double[] out) {
        if (!refresh(level)) {
            out[0] = out[1] = 0.0D;
            return;
        }
        out[0] = cx;
        out[1] = cz;
    }

    /** 強風の日の度合い 0〜1(オーバーワールド以外は 0)。 */
    public static double gale(ServerLevel level) {
        return refresh(level) ? gale : 0.0D;
    }

    /**
     * 流される型(セミナリア。ペディキュラは 11-16 の全体管理の決定で今の上限のまま)の風(2026-11-16)。強風の日でなければ {@link #now} と同じ。強風の日は上限を {@link #GALE_CAP} へ上げ、
     * さらに × (1 + boost × 強風の度合い)。
     */
    public static void drift(ServerLevel level, double[] out, double boost) {
        if (!refresh(level)) {
            out[0] = out[1] = 0.0D;
            return;
        }
        if (gale <= 0.0D) {
            out[0] = cx;
            out[1] = cz;
            return;
        }
        double cap = STRENGTH_CAP + (GALE_CAP - STRENGTH_CAP) * gale;
        double s = Math.min(groundStrength, cap) * BLOCKS_PER_STRENGTH * (1.0D + boost * gale);
        out[0] = dirX * s;
        out[1] = dirZ * s;
    }

    /**
     * 高さ y の風(ブロック/tick)を out[0..1] へ(2026-11-15 ペディキュラで足した。漂う型が上下して行き先を選ぶため)。
     * {@link #now} を {@link BalloonDrift#layerWind} で回して強める。世界の風そのもの(胞子の場・雲)は変えない。
     */
    public static void atHeight(ServerLevel level, double y, double[] out) {
        now(level, out);
        if (out[0] == 0.0D && out[1] == 0.0D) return;
        BalloonDrift.layerWind(WindWeatherData.windSeed(level.getServer()), level.getDayTime(), y, out[0], out[1], out);
    }
}

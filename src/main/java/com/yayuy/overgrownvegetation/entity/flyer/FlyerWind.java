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

    private static final PrevailingWind.Sample SAMPLE = new PrevailingWind.Sample();
    private static long cachedTick = Long.MIN_VALUE;
    private static double cx, cz;

    private FlyerWind() {
    }

    /** 今の風(ブロック/tick)を out[0..1] へ。 */
    public static void now(ServerLevel level, double[] out) {
        if (level.dimension() != Level.OVERWORLD) {
            out[0] = out[1] = 0.0D;
            return;
        }
        long t = level.getGameTime();
        if (t != cachedTick) {
            MinecraftServer server = level.getServer();
            PrevailingWind.sample(WindWeatherData.windSeed(server), level.getDayTime(), WindWeatherData.get(server).weather(), SAMPLE);
            double s = Math.min(SAMPLE.strength, STRENGTH_CAP) * BLOCKS_PER_STRENGTH;
            cx = SAMPLE.dirX * s;
            cz = SAMPLE.dirZ * s;
            cachedTick = t;
        }
        out[0] = cx;
        out[1] = cz;
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

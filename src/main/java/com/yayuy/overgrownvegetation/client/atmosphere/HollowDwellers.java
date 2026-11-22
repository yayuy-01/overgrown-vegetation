package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.CoverTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>木の洞の住人の気配</b>(案 H の 43、2026-10-31。架空)。洞のある幹({@code block/OvergrownLogBlock#HOLLOW})のランダム表示 tick から呼ばれる:
 * <ul>
 *     <li>プレイヤーが {@code cover.json} の {@code hollowReach} ブロックに入ると、穴から暗い虫の点が {@code hollowBugsMin}〜{@code hollowBugsMax} 匹飛び出す
 *     (羽虫 {@link GnatSwarms} と同じ暗い点。1 回出たら {@code hollowCooldownSeconds} 秒は出ない)</li>
 *     <li>ときどき(平均 {@code hollowEyesPerMinute} 回/分)穴の中で小さな光が 2 つ {@code hollowEyesSeconds} 秒瞬く(目。黄緑 #ace300 寄り、1 画素ほど)</li>
 * </ul>
 * 設定: 説明しない恐怖の、明るい森での薄い版。森に住人がいる気配。音は付けない(環境音は虫・爬虫類だけ)。ブロックエンティティは作らない(時刻は座標ごとの表)。
 * 値はすべて仮決め。
 */
public final class HollowDwellers {

    /** 虫が飛んでいる秒。 */
    private static final double BUG_LIFE = 1.6;
    private static final int MAX_KEYS = 512;
    private static final Long2LongOpenHashMap NEXT_BUGS = new Long2LongOpenHashMap();
    private static final Long2LongOpenHashMap NEXT_EYES = new Long2LongOpenHashMap();
    private static final List<Burst> BURSTS = new ArrayList<>();
    private static final List<Eyes> EYES = new ArrayList<>();

    private record Burst(double x, double y, double z, Direction face, long start, int count, float seed) {
    }

    private record Eyes(double x, double y, double z, Direction face, long start, long length) {
    }

    private HollowDwellers() {
    }

    /** 洞の気配を出すか決める(ランダム表示 tick。クライアントのメインスレッド)。 */
    public static void near(Level level, BlockPos pos, Direction face, RandomSource random) {
        Player player = Minecraft.getInstance().player;
        if (player == null || face == null || !VisualQuality.critters()) {
            return;
        }
        CoverTable t = DesignData.cover();
        long now = System.currentTimeMillis();
        long key = pos.asLong();
        if (NEXT_BUGS.size() > MAX_KEYS || NEXT_EYES.size() > MAX_KEYS) {
            NEXT_BUGS.clear();
            NEXT_EYES.clear();
        }
        // 穴の真ん中(面の少し外)。
        double x = pos.getX() + 0.5 + face.getStepX() * 0.51;
        double y = pos.getY() + 0.5;
        double z = pos.getZ() + 0.5 + face.getStepZ() * 0.51;
        if (player.distanceToSqr(x, y, z) <= t.hollowReach() * t.hollowReach() && now >= NEXT_BUGS.getOrDefault(key, 0L)) {
            int count = t.hollowBugsMin() + (t.hollowBugsMax() > t.hollowBugsMin() ? random.nextInt(t.hollowBugsMax() - t.hollowBugsMin() + 1) : 0);
            if (count > 0) {
                BURSTS.add(new Burst(x, y, z, face, now, count, random.nextFloat() * 100.0F));
            }
            NEXT_BUGS.put(key, now + Math.round(t.hollowCooldownSeconds() * 1000.0D));
        }
        if (t.hollowEyesPerMinute() > 0) {
            long next = NEXT_EYES.getOrDefault(key, -1L);
            if (next < 0L) {
                NEXT_EYES.put(key, now + nextEyes(t, random)); // 初めて見た洞: 次の瞬きまでの時間から
            } else if (now >= next) {
                EYES.add(new Eyes(x, y + 0.03, z, face, now, Math.round(t.hollowEyesSeconds() * 1000.0D)));
                NEXT_EYES.put(key, now + nextEyes(t, random));
            }
        }
    }

    /** 次の瞬きまで(ms、平均 60 / hollowEyesPerMinute 秒の指数分布)。 */
    private static long nextEyes(CoverTable t, RandomSource random) {
        double mean = 60000.0D / t.hollowEyesPerMinute();
        return Math.round(-Math.log(1.0D - random.nextDouble() * 0.999D) * mean);
    }

    static void clear() {
        NEXT_BUGS.clear();
        NEXT_EYES.clear();
        BURSTS.clear();
        EYES.clear();
    }

    static boolean hasEyes() {
        return !EYES.isEmpty();
    }

    /** 飛び出す虫(暗い点。{@link LifeSprites} の普通の重ね)。 */
    static void render(Vector3f left, Vector3f up) {
        if (!VisualQuality.critters()) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Burst> it = BURSTS.iterator(); it.hasNext(); ) {
            Burst b = it.next();
            double age = (now - b.start()) / 1000.0D;
            if (age > BUG_LIFE) {
                it.remove();
                continue;
            }
            float fade = (float) Mth.clamp((BUG_LIFE - age) / 0.4D, 0.0D, 1.0D) * (float) Mth.clamp(age / 0.08D, 0.0D, 1.0D);
            Direction f = b.face();
            // 面に沿った横向き(東西の面なら z、南北の面なら x)。
            double sx = f.getStepZ() != 0 ? 1.0 : 0.0;
            double sz = f.getStepX() != 0 ? 1.0 : 0.0;
            for (int k = 0; k < b.count(); k++) {
                float h1 = hash(b.seed() + k * 1.7F);
                float h2 = hash(b.seed() + k * 3.1F + 11.0F);
                float h3 = hash(b.seed() + k * 5.3F + 23.0F);
                // 外へ 1.5〜3 ブロック/秒、横と上へばらけ、だんだん遅くなる(虫が散る)。
                double out = (1.5 + 1.5 * h1) * easeOut(age);
                double side = (h2 - 0.5) * 2.4 * easeOut(age);
                double rise = (h3 - 0.3) * 1.6 * easeOut(age);
                double wob = Math.sin(age * (18.0 + 10.0 * h1) + k) * 0.05;
                double x = b.x() + f.getStepX() * out + sx * (side + wob);
                double y = b.y() + rise + Math.cos(age * (15.0 + 8.0 * h2) + k) * 0.04;
                double z = b.z() + f.getStepZ() * out + sz * (side + wob);
                LifeSprites.dot(x, y, z, 0.022F, left, up, 0.05F, 0.05F, 0.03F, 0.9F * fade);
            }
        }
    }

    /** 穴の中の目(足し算で光らせる)。 */
    static void renderGlow(Vector3f left, Vector3f up) {
        if (!VisualQuality.critters()) {
            clear();
            return;
        }
        long now = System.currentTimeMillis();
        for (Iterator<Eyes> it = EYES.iterator(); it.hasNext(); ) {
            Eyes e = it.next();
            double t = (now - e.start()) / (double) e.length();
            if (t > 1.0) {
                it.remove();
                continue;
            }
            // すっと点いて、すっと消える(真ん中で 1)。
            float a = (float) Math.sin(Math.PI * t);
            a = Math.min(1.0F, a * 1.6F);
            Direction f = e.face();
            double sx = f.getStepZ() != 0 ? 1.0 : 0.0;
            double sz = f.getStepX() != 0 ? 1.0 : 0.0;
            for (int side = -1; side <= 1; side += 2) {
                double x = e.x() + sx * side * 0.09;
                double z = e.z() + sz * side * 0.09;
                LifeSprites.dot(x, e.y(), z, 1.0F / 32.0F, left, up, 0.72F, 0.95F, 0.12F, a);
                LifeSprites.blob(x, e.y(), z, 0.06F, 1.0F, left, up, 0.67F, 0.89F, 0.0F, 0.25F * a);
            }
        }
    }

    private static double easeOut(double age) {
        double t = Math.min(1.0, age / BUG_LIFE);
        return 1.0 - (1.0 - t) * (1.0 - t);
    }

    private static float hash(float v) {
        float s = Mth.sin(v * 12.9898F) * 43758.547F;
        return s - Mth.floor(s);
    }
}

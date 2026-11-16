package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AssaultTable;
import com.yayuy.overgrownvegetation.data.BaseMemoryTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 貯めた襲撃の継ぎ足し(2026-11-16 F5 の襲撃。ユーザー「貯めた襲撃は普通の襲撃より難易度を高くしたいので、同時に湧くのは 40。そこから継ぎ足し継ぎ足しで、最大 80 体まで」)。
 *
 * <ul>
 *   <li>始まり({@link #begin}): 貯める間が明けたら({@link ForestMoods#spend})、認識した拠点ごとに「まとめた 1 回」(進化体と G5 は 1.5 倍)を出す。出した個体に印 {@link #TAG}。</li>
 *   <li>継ぎ足し({@link #step}): {@code hoardRefillTicks} ごとに拠点のまわりで印のある生きた個体を数え、{@code hoardMaxAlive}(40)に足りない分を
 *   同じ割合で出す。その回の合計が {@code hoardMaxTotal}(80)に届くまで。予算は払える限り(大きな襲撃と同じ)。</li>
 *   <li>終わり: 合計 80 / 1 回の継ぎ足しで 1 体も出せなかった / 始めて {@code hoardMaxDays} / 拠点を忘れた / 大きな襲撃の前触れ。出した個体は残る。</li>
 *   <li>続いている間は、ふつうの送り込みを止める({@link BaseWatch})。</li>
 * </ul>
 * 状態はサーバーの中だけに持つ(再起動で途中のものは終わる。出した個体は残る)【仮】。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class HoardRaids {

    /** 貯めた襲撃の個体の印。 */
    public static final String TAG = "ov_hoard_raid";

    private HoardRaids() {
    }

    /** 拠点 1 つぶんの、続いている貯めた襲撃。 */
    public static final class Raid {
        public final int baseId;
        public final double startDay;
        public int total;
        public int peakAlive;
        public int steps;
        /** 1 回目の顔ぶれ(継ぎ足しはこの割合を縮める)。 */
        final BaseMemoryTable.Wave shape;

        Raid(int baseId, double startDay, BaseMemoryTable.Wave shape) {
            this.baseId = baseId;
            this.startDay = startDay;
            this.shape = shape;
        }
    }

    private static final Map<Integer, Raid> RAIDS = new LinkedHashMap<>();

    /** 確かめ用: 最後に終わった回(合計・同時の最大・終わった理由)。 */
    @Nullable
    public static String lastEnded;
    public static int lastTotal;
    public static int lastPeakAlive;

    public static boolean active() {
        return !RAIDS.isEmpty();
    }

    @Nullable
    public static Raid raid(int baseId) {
        return RAIDS.get(baseId);
    }

    /** テスト用: 全部やめる(個体は残す)。 */
    public static void clearForTests() {
        RAIDS.clear();
    }

    /** 始める: まとめた 1 回目を出して、継ぎ足しを始める。出した数を返す。 */
    public static int begin(ServerLevel level, BaseMemory.Base b, double day, RandomSource random) {
        BaseRaids.setWaveTag(TAG);
        BaseRaids.Result r;
        try {
            r = BaseRaids.sendWave(level, b, day, random, true);
        } finally {
            BaseRaids.setWaveTag(null);
        }
        Raid raid = new Raid(b.id(), day, new BaseMemoryTable.Wave(0.0D, r.wantStageZero(), r.wantDeveloped(), r.wantG4(), r.wantG5plus()));
        raid.total = r.total();
        raid.peakAlive = r.total();
        RAIDS.put(b.id(), raid);
        ForestDirector.get(level).note(String.format(Locale.ROOT, "貯めた襲撃が始まった: 拠点 #%d へ %d 体(%s)", b.id(), r.total(), r.summary()));
        return r.total();
    }

    /** サーバーを止めたら空にする(static なので、別のワールドを開くと続きが走っていた。見直し ②9)。 */
    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        RAIDS.clear();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || RAIDS.isEmpty()) return;
        if (level.getServer() instanceof net.minecraft.gametest.framework.GameTestServer) return; // テストは step を直に呼ぶ
        if (level.getGameTime() % DesignData.director().assault.hoardRefillTicks != 0) return;
        step(level, BaseMemory.today(level.getServer()), level.getRandom(), BaseMemory.get(level.getServer()).bases());
    }

    /** 継ぎ足しを 1 回(拠点の一覧を渡す。テストは自分の記憶を渡す)。 */
    public static void step(ServerLevel level, double day, RandomSource random, List<BaseMemory.Base> bases) {
        AssaultTable a = DesignData.director().assault;
        for (Raid raid : List.copyOf(RAIDS.values())) {
            BaseMemory.Base b = null;
            for (BaseMemory.Base c : bases) {
                if (c.id() == raid.baseId) b = c;
            }
            if (b == null || b.stage() != BaseMemory.Stage.RECOGNIZED) {
                end(raid, "拠点を忘れた");
                continue;
            }
            if (DirectorGates.largeRaidOmen(level)) {
                end(raid, "大きな襲撃の前触れ");
                continue;
            }
            if (DirectorGates.peaceful(level)) {
                end(raid, "ピースフル");
                continue;
            }
            if (day - raid.startDay > a.hoardMaxDays) {
                end(raid, "時間切れ");
                continue;
            }
            int alive = alive(level, b);
            raid.peakAlive = Math.max(raid.peakAlive, alive);
            int want = Math.min(a.hoardMaxAlive - alive, a.hoardMaxTotal - raid.total);
            if (raid.total >= a.hoardMaxTotal) {
                end(raid, "合計 " + a.hoardMaxTotal + " 体を出した");
                continue;
            }
            if (want <= 0) continue;
            BaseRaids.setWaveTag(TAG);
            BaseRaids.Result r;
            try {
                r = BaseRaids.sendWave(level, b, day, random, true, shrink(raid.shape, want));
            } finally {
                BaseRaids.setWaveTag(null);
            }
            raid.steps++;
            raid.total += r.total();
            raid.peakAlive = Math.max(raid.peakAlive, alive + r.total());
            if (r.total() == 0) {
                end(raid, "継ぎ足しを払えなかった");
            } else if (raid.total >= a.hoardMaxTotal) {
                end(raid, "合計 " + a.hoardMaxTotal + " 体を出した");
            }
        }
    }

    private static void end(Raid raid, String why) {
        RAIDS.remove(raid.baseId);
        lastEnded = why;
        lastTotal = raid.total;
        lastPeakAlive = raid.peakAlive;
    }

    /** 拠点のまわり(大きな襲撃の数える半径)の、印のある生きた個体の数。 */
    public static int alive(ServerLevel level, BaseMemory.Base b) {
        double r = DesignData.director().largeRaid.countRadius;
        return level.getEntitiesOfClass(Mob.class,
                new AABB(b.centerX() - r, level.getMinBuildHeight(), b.centerZ() - r, b.centerX() + r, level.getMaxBuildHeight(), b.centerZ() + r),
                m -> m.isAlive() && m.getTags().contains(TAG)).size();
    }

    /** 顔ぶれの割合を保って n 体に縮める(端数は大きい余りから)。純粋な計算。 */
    public static BaseMemoryTable.Wave shrink(BaseMemoryTable.Wave w, int n) {
        int[] c = {w.stageZero(), w.developed(), w.g4(), w.g5plus()};
        int sum = c[0] + c[1] + c[2] + c[3];
        if (sum <= 0 || n <= 0) return new BaseMemoryTable.Wave(0.0D, Math.max(0, n), 0, 0, 0);
        if (n >= sum) return w;
        int[] out = new int[4];
        double[] rem = new double[4];
        int used = 0;
        for (int i = 0; i < 4; i++) {
            double v = c[i] * (double) n / sum;
            out[i] = (int) Math.floor(v);
            rem[i] = v - out[i];
            used += out[i];
        }
        while (used < n) {
            int best = 0;
            for (int i = 1; i < 4; i++) if (rem[i] > rem[best]) best = i;
            out[best]++;
            rem[best] = -1.0D;
            used++;
        }
        return new BaseMemoryTable.Wave(0.0D, out[0], out[1], out[2], out[3]);
    }
}

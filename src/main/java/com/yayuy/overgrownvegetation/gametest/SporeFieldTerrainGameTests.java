package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.spore.field.PrevailingWind;
import com.yayuy.overgrownvegetation.spore.field.SporeField;
import com.yayuy.overgrownvegetation.spore.field.SporeFieldServer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 胞子の場の改善(2026-10-30): 息をする地割れ・谷風と逆転層・山の壁・土地の倍率と、地表の高さの保存。
 * 場の式は素の Java なのでワールドを使わずに回す。数値の期待値は設計データ({@code spore_field.json})の値から。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SporeFieldTerrainGameTests {

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterSporeFieldTerrainGameTests(RegisterGameTestsEvent event) // 1.20.1: Forge の受け手の包みは名前で使い回すので、クラスごとに名前を変える {
            event.register(SporeFieldTerrainGameTests.class);
        }

        private Registration() {
        }
    }

    private SporeFieldTerrainGameTests() {
    }

    /** 正午と真夜中(tod 0 = 6 時)。 */
    private static final long NOON = 6000L, MIDNIGHT = 18000L;

    /** 設計データの場の数値の写し(テストで書き換えても設計データに響かない)。 */
    private static SporeField.Params params() {
        SporeField.Params src = DesignData.sporeField().field();
        SporeField.Params p = new SporeField.Params();
        try {
            for (var fd : SporeField.Params.class.getFields()) fd.set(p, fd.get(src));
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return p;
    }

    /** 高さと土地の倍率を決めた合成の土地。 */
    private interface Height {
        float at(int cx, int cz);
    }

    private static SporeField.Ground ground(Height h, java.util.function.ToDoubleBiFunction<Integer, Integer> mul, boolean wet) {
        return new SporeField.Ground() {
            @Override
            public float germFactor(int cx, int cz) {
                return 1;
            }

            @Override
            public boolean wet(int cx, int cz) {
                return wet;
            }

            @Override
            public float height(int cx, int cz) {
                return h.at(cx, cz);
            }

            @Override
            public float terrainMul(int cx, int cz, float height) {
                return (float) mul.applyAsDouble(cx, cz);
            }
        };
    }

    private static SporeField field(SporeField.Params p, Height h) {
        SporeField f = new SporeField(4242L, p);
        f.ground = ground(h, (x, z) -> 1, false);
        return f;
    }

    private static void step(SporeField f, long dayTime) {
        f.step(new SporeField.Conditions(dayTime, dayTime, false, false, false), new PrevailingWind.Sample());
    }

    private static double dt(SporeField.Params p) {
        return p.stepTicks / (double) PrevailingWind.DAY;
    }

    /** 息をする地割れ: 昼は浮遊へ吐き、真夜中は吐かない。夜は半径の内側の浮遊だけが exp(−inhalePerDay × dt) に減る。 */
    @GameTest(template = "gametest_empty7")
    public static void riftExhalesByDayInhalesAtNight(GameTestHelper helper) {
        SporeField.Params p = params();
        double exhale = p.exhalePerDay * Math.PI * dt(p); // 正午は sin = 1
        SporeField day = field(p, (x, z) -> 64);
        day.breathers = new long[]{ChunkPos.asLong(0, 0)};
        step(day, NOON);
        double sum = 0;
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) sum += day.air(x, z);
        helper.assertTrue(sum > 0.5 * exhale && sum <= exhale * 1.0001,
                "昼に吐いた量が違う: " + sum + "(吐く量 " + exhale + ")");
        SporeField night = field(p, (x, z) -> 64);
        night.breathers = new long[]{ChunkPos.asLong(0, 0)};
        step(night, MIDNIGHT);
        helper.assertTrue(night.air(0, 0) == 0, "真夜中に吐いた: " + night.air(0, 0));
        // 吸い込み: 一様な浮遊の中で、半径の内側(0, 0)と外側(12, 0)を比べる
        SporeField in = field(p, (x, z) -> 64);
        for (int x = -15; x <= 15; x++) for (int z = -15; z <= 15; z++) in.emitPoint(x, z, 1.0);
        in.breathers = new long[]{ChunkPos.asLong(0, 0)};
        step(in, MIDNIGHT);
        double ratio = in.air(0, 0) / in.air(12, 0);
        double keep = Math.exp(-p.inhalePerDay * dt(p));
        helper.assertTrue(Math.abs(ratio - keep) < 0.01, "夜の吸い込みの割合が違う: " + ratio + "(期待 " + keep + ")");
        helper.succeed();
    }

    /** 谷風・山風: 東ほど高い斜面で、昼は東(高い隣)へ、夜は西(低い隣)へ多く移る。 */
    @GameTest(template = "gametest_empty7")
    public static void valleyWindClimbsByDayDescendsAtNight(GameTestHelper helper) {
        SporeField.Params p = params();
        Height slope = (x, z) -> 64 + 10 * x;
        SporeField day = field(p, slope);
        day.emitPoint(0, 0, 1.0);
        step(day, NOON);
        SporeField night = field(p, slope);
        night.emitPoint(0, 0, 1.0);
        step(night, MIDNIGHT);
        double up = day.air(1, 0) - day.air(-1, 0), down = night.air(-1, 0) - night.air(1, 0);
        double expectUp = 1 - Math.exp(-p.valleyUpPerDay * dt(p)), expectDown = 1 - Math.exp(-p.valleyDownPerDay * dt(p));
        helper.assertTrue(up > 0.5 * expectUp && up < 1.1 * expectUp, "昼に高い隣へ移った量が違う: " + up + "(期待 ≈ " + expectUp + ")");
        helper.assertTrue(down > 0.5 * expectDown && down < 1.1 * expectDown, "夜に低い隣へ移った量が違う: " + down + "(期待 ≈ " + expectDown + ")");
        helper.succeed();
    }

    /** 逆転層: 逆転層より高い隣(y 300)へは地上の浮遊が入らず、送り元に降り積もる。 */
    @GameTest(template = "gametest_empty7")
    public static void inversionKeepsGroundSporesOut(GameTestHelper helper) {
        SporeField.Params p = params();
        helper.assertTrue(p.inversionY + p.inversionJitter < 300, "逆転層が y 300 より高い(テストの前提): " + p.inversionY);
        SporeField blocked = field(p, (x, z) -> x == 1 && z == 0 ? 300 : 64);
        SporeField open = field(p, (x, z) -> 64);
        blocked.emitPoint(0, 0, 1.0);
        open.emitPoint(0, 0, 1.0);
        step(blocked, NOON);
        step(open, NOON);
        helper.assertTrue(blocked.air(1, 0) == 0, "逆転層より上の升に浮遊が入った: " + blocked.air(1, 0));
        helper.assertTrue(blocked.air(-1, 0) > 0, "低い隣には入るはず");
        double extra = blocked.deposited(0, 0) - open.deposited(0, 0);
        helper.assertTrue(Math.abs(extra - open.air(1, 0)) < 0.05 * open.air(1, 0) + 1e-7,
                "入れなかった分が送り元に降り積もっていない: " + extra + "(入るはずだった " + open.air(1, 0) + ")");
        helper.succeed();
    }

    /** 山の壁: 山(高さ ≥ mountainY)は昼だけ沈着が mountTrap 倍。夜は平地と同じ。 */
    @GameTest(template = "gametest_empty7")
    public static void mountainTrapsSporesByDayOnly(GameTestHelper helper) {
        SporeField.Params p = params();
        p.valleyUpPerDay = p.valleyDownPerDay = 0; // 沈着だけを比べる
        Height h = (x, z) -> x >= 0 ? (float) p.mountainY + 10 : 64;
        double[] ratio = new double[2];
        long[] times = {NOON, MIDNIGHT};
        for (int k = 0; k < 2; k++) {
            SporeField f = field(p, h);
            for (int x = -10; x <= 9; x++) for (int z = -10; z <= 9; z++) f.emitPoint(x, z, 0.01);
            step(f, times[k]);
            ratio[k] = f.deposited(5, 0) / f.deposited(-5, 0);
        }
        helper.assertTrue(ratio[0] > p.mountTrap * 0.9 && ratio[0] < p.mountTrap * 1.05,
                "昼の山の沈着が mountTrap 倍でない: " + ratio[0] + "(mountTrap " + p.mountTrap + ")");
        helper.assertTrue(Math.abs(ratio[1] - 1) < 0.02, "夜も山の沈着が増えた: " + ratio[1]);
        helper.succeed();
    }

    /** 土地の倍率: 湿った土地(wetMul)はしきい値の 0.8 倍で発芽し、乾いた土地(dryMul)はしきい値の 2 倍でも発芽しない。 */
    @GameTest(template = "gametest_empty7")
    public static void terrainMultiplierMovesTheThreshold(GameTestHelper helper) {
        SporeField.Params p = params();
        helper.assertTrue(p.wetMul * 0.8 > 1.3 && p.dryMul * 2 < 1, "設計データの倍率がテストの前提と合わない: wet " + p.wetMul + " dry " + p.dryMul);
        SporeField f = new SporeField(7L, p);
        f.ground = ground((x, z) -> Float.NaN, (x, z) -> x == 0 ? p.wetMul : x == 2 ? p.dryMul : 1, true);
        f.depositPoint(0, 0, p.germThreshold * 0.8);
        f.depositPoint(2, 0, p.germThreshold * 2);
        f.depositPoint(4, 0, p.germThreshold * 0.8);
        for (int k = 0; k < 300; k++) step(f, (long) k * p.stepTicks);
        helper.assertTrue(f.isCorrupted(0, 0), "湿った土地がしきい値の 0.8 倍で発芽しない");
        helper.assertFalse(f.isCorrupted(2, 0), "乾いた土地がしきい値の 2 倍で発芽した");
        helper.assertFalse(f.isCorrupted(4, 0), "ふつうの土地がしきい値の 0.8 倍で発芽した");
        helper.succeed();
    }

    /** 保存: 版 2 は地表の高さを持ち、そのまま読める。高さの無い前の版(ver なし)は読み込むときに土地から高さを引き直す。 */
    @GameTest(template = "gametest_empty7")
    public static void oldSaveWithoutHeightStillLoads(GameTestHelper helper) {
        SporeField.Params p = params();
        SporeField a = field(p, (x, z) -> 70);
        a.emitPoint(5, 5, 0.75);
        CompoundTag v2 = SporeFieldServer.tileTagForTests(a, 0, 0);
        helper.assertTrue(v2 != null && v2.getInt("ver") >= 2 && v2.contains("height"), "保存に版と高さが無い");
        CompoundTag old = v2.copy();
        old.remove("ver");
        old.remove("height");
        SporeField b = field(p, (x, z) -> 71);
        SporeFieldServer.loadTileForTests(b, 0, 0, old);
        helper.assertTrue(b.heightAt(5, 5) == 71, "前の版の高さを土地から引き直していない: " + b.heightAt(5, 5));
        helper.assertTrue(b.air(5, 5) == a.air(5, 5), "前の版の浮遊が読めていない: " + b.air(5, 5));
        SporeField c = field(p, (x, z) -> 72);
        SporeFieldServer.loadTileForTests(c, 0, 0, v2);
        helper.assertTrue(c.heightAt(5, 5) == 70, "版 2 の高さが保存の値でない: " + c.heightAt(5, 5));
        helper.succeed();
    }
}

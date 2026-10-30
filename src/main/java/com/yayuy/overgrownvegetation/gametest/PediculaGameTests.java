package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.BlightFernEntity;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllBombEntity;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.spore.SporeConcentration;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * ペディキュラ(グループ4・漂流種、2026-09-23)のテスト。守りたいのはユーザー指定の 4 つ:
 * <ul>
 *     <li><b>過繫茂バイオームの無い方へ</b>漂う({@link PediculaEntity#calmestHeading} —— 地形を作らず、
 *     地点ごとの過繫茂の度合いを差し替えて向きの式だけを見る)。</li>
 *     <li><b>いるチャンクの胞子濃度を、いる間だけ 100% にする</b>(立ち去れば戻り、繁茂化・シダの湧きには効かない)。</li>
 *     <li><b>撃ち落とすと小さな胞子爆弾を 4 つ散らし、着弾点に汚染シダが湧く</b>。</li>
 *     <li>sub / sub2 / sub3 が<b>個体ごとに付いたり付かなかったり</b>し、それがセーブを挟んでも変わらない。</li>
 * </ul>
 *
 * <p>爆弾・シダを数えるテストがあるので、ほかのテストと同時に走らないバッチに分けてある
 * (同じバッチは同じ世界に並んで同時に走る)。撃ち落としのテストは胞子除去装置の台帳を外して走るので、
 * さらに自分だけのバッチ({@value #BOMB_BATCH})にしてある。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class PediculaGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "pedicula";
    /** {@link #shotDownScattersFernBombs} だけのバッチ(台帳を外している間に、ほかのテストの胞子濃度を変えないため)。 */
    private static final String BOMB_BATCH = "pedicula_bombs";
    /** {@link #startsDriftingRightAway} だけのバッチ(生きたまま漂う個体が、隣のテスト場のチャンクまで 100% にしないため)。 */
    private static final String DRIFT_BATCH = "pedicula_drift";
    /** 床(相対 y = 1)の上、テスト場の真ん中。 */
    private static final BlockPos FEET = new BlockPos(3, 2, 3);
    /**
     * 撃ち落としたときの散らばり方(向き・速さはペディキュラ本体の乱数で決まる)を毎回同じにする種。
     * この種だと 4 つとも別の壁に当たり、4 株とも別のマスに湧く(炸裂点はマスの境目から 0.1 ブロック以上離れている。
     * 2026-09-26、テスト場が置かれる場所を変えた 200 回で炸裂点がすべて同じだった)。
     */
    private static final long SCATTER_SEED = 20260926L;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(PediculaGameTests.class);
        }

        private Registration() {
        }
    }

    // --- 向き ---

    /**
     * 過繫茂が西(-X)に広がっていれば東(+X)へ、北(-Z)に広がっていれば南(+Z)へ流れる。
     * 境目から 10 ブロック外にいる想定。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void headingLeavesOvergrowth(GameTestHelper helper) {
        double east = PediculaEntity.calmestHeading(10.0D, 0.0D, (x, z) -> x < 0 ? 1.0D : 0.0D);
        if (!(Math.abs(Math.toDegrees(east)) < 10.0D)) {
            helper.fail("西が過繫茂なのに東へ向かない: " + Math.toDegrees(east) + " 度");
            return;
        }
        double south = PediculaEntity.calmestHeading(0.0D, 10.0D, (x, z) -> z < 0 ? 1.0D : 0.0D);
        if (!(Math.abs(Math.toDegrees(south) - 90.0D) < 10.0D)) {
            helper.fail("北が過繫茂なのに南(+Z = 90 度)へ向かない: " + Math.toDegrees(south) + " 度");
            return;
        }
        // 繁茂(0.5)より過繫茂(1.0)から強く離れる: 西が過繫茂・東が繁茂なら東寄りには向かない。
        double away = PediculaEntity.calmestHeading(0.0D, 0.0D,
                (x, z) -> x < -8 ? 1.0D : x > 8 ? 0.5D : 0.0D);
        if (!(Math.abs(Math.toDegrees(away)) < 10.0D)) {
            helper.fail("過繫茂(西)と繁茂(東)に挟まれたら、薄い繁茂の側(東)へ抜けるはず: "
                    + Math.toDegrees(away) + " 度");
            return;
        }
        helper.succeed();
    }

    /**
     * 向きが決まらないときは {@code NaN}(= 今の向きのまま)。周りに過繫茂が無いとき・四方が同じとき、
     * そして<b>読めない先を「外」と取り違えない</b>こと —— 北側が未読み込みでも、そちらへ吸い寄せられない。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void headingHoldsWhenUndecided(GameTestHelper helper) {
        if (!Double.isNaN(PediculaEntity.calmestHeading(0.0D, 0.0D, (x, z) -> 0.0D))) {
            helper.fail("周りに過繫茂が無いのに向きを変えようとした");
            return;
        }
        if (!Double.isNaN(PediculaEntity.calmestHeading(0.0D, 0.0D, (x, z) -> 1.0D))) {
            helper.fail("四方が過繫茂(内陸)なのに向きを決めた");
            return;
        }
        double unknownNorth = PediculaEntity.calmestHeading(0.0D, 0.0D, (x, z) -> z < -20 ? Double.NaN : 1.0D);
        if (!Double.isNaN(unknownNorth)) {
            helper.fail("読み込まれていない北へ吸い寄せられた: " + Math.toDegrees(unknownNorth) + " 度");
            return;
        }
        helper.succeed();
    }

    // --- 漂う ---

    /**
     * 湧いた直後から自分で流れ始める(2026-09-26 ユーザー報告「湧いてから全く移動しない」)。
     *
     * <p>速度は 1 tick に 5% ずつ狙いへ寄せる。以前は今の速度({@code getDeltaMovement})から寄せていたので、
     * 止まった状態からの 1 tick 目は漂う速さの 5%(既定 0.05 × 0.05 = 0.0025)しか無く、バニラが毎 tick
     * 0.003 未満の速度を 0 に切り捨てる({@code LivingEntity#aiStep})せいで、横へは永久に動き出せなかった
     * (30 tick で 0.075 ブロック)。寄せ方どおりに加速すれば 30 tick で漂う速さ × 約 15 進むので、その半分以上。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = DRIFT_BATCH)
    public static void startsDriftingRightAway(GameTestHelper helper) {
        PediculaEntity pedicula = helper.spawn(ModEntities.PEDICULA.get(), FEET);
        Vec3 start = pedicula.position();
        // 中心から壁まで 1.7 ブロック(体の幅 3.6)なので、速さを上げても壁より手前で判定できるよう 1 ブロックで頭打ち。
        double expected = Math.min(0.5D * MobStats.drift("pedicula").driftSpeed() * 15.0D, 1.0D);
        helper.runAfterDelay(30L, () -> {
            double moved = pedicula.position().subtract(start).horizontalDistance();
            pedicula.discard(); // 生きている間はチャンクを 100% にするので、落ちたときも残さない
            if (moved < expected) {
                helper.fail(String.format(Locale.ROOT, "湧いてから 30 tick で横へ %.3f ブロックしか動いていない(%.3f 以上のはず)",
                        moved, expected));
                return;
            }
            helper.succeed();
        });
    }

    // --- 胞子濃度 ---

    /**
     * <b>いる間だけ</b> 100%(ユーザー指定 2026-09-23)。いる間は胞子濃度が 100%(汚染シダの自然発生もこれを見る)で、
     * 繁茂バイオーム化の判定({@link SporeConcentration#lastingPercentAt})には効かず、チャンクには何も積まれない。
     * 消えたら元の濃度に戻る。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 60, batch = BATCH)
    public static void coversTheChunkOnlyWhileThere(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(FEET);
        ChunkPos chunk = new ChunkPos(at);
        if (SporeDeviceData.insidePurifier(level, at)) {
            helper.fail("テスト場が胞子除去装置の圏内にある(ほかのテストの装置が残っている?)");
            return;
        }
        double before = SporeConcentration.percentAt(level, chunk);
        if (before >= 100.0D) {
            helper.fail("テスト場のチャンクが最初から 100%(確かめられない): " + before);
            return;
        }

        // AI は要らない(いる間の 100% は AI と関係なく aiStep で効く)。止めておけば隣のチャンクへ流れていかない
        // (spawnWithNoFreeWill は Goal を外すだけで、漂う customServerAiStep は止まらない)。
        PediculaEntity pedicula = helper.spawnWithNoFreeWill(ModEntities.PEDICULA.get(), FEET);
        pedicula.setNoAi(true);
        helper.runAfterDelay(5L, () -> {
            double during = SporeConcentration.percentAt(level, chunk);
            if (during < 100.0D) {
                helper.fail("上にいるのに胞子濃度が 100% になっていない: " + during);
                return;
            }
            double lasting = SporeConcentration.lastingPercentAt(level, chunk);
            if (lasting >= 100.0D) {
                helper.fail("いる間だけの 100% が繁茂バイオーム化の判定にまで効いている: " + lasting);
                return;
            }
            pedicula.discard();
        });
        helper.runAfterDelay(10L, () -> {
            double after = SporeConcentration.percentAt(level, chunk);
            if (after >= 100.0D) {
                helper.fail("いなくなったのに胞子濃度が 100% のまま: " + after);
                return;
            }
            helper.succeed();
        });
    }

    // --- 撃ち落とされたとき ---

    /**
     * 倒すとその場で消え、汚染シダを植える小さな胞子爆弾が 4 つ散る。爆弾はテスト場の壁(バリア)に当たるか
     * 床に落ちて炸裂し、炸裂点の真下の床の上に汚染シダが 1 株ずつ湧く。
     *
     * <p>確かめるのは<b>着弾ごとの結果</b>(どの爆弾の真下にも 1 株湧いたか)で、湧いたシダは<b>湧いた時点のマス</b>で
     * 覚えておく —— 後で残っている株を数えるのではない。汚染シダは汚染し終えると消えるが、汚染できるのが 7×7 の
     * 床だけのこのテスト場では、壁際の株のコアと触手の筋がバリア・空気に当たって全部途切れ、床が残っていても
     * 数十 tick で「汚染し終えた」ことになって消えることがある(2026-09-26 に同じ場面 200 回で 8 回、
     * どれも自分で汚染したのは 3〜6 ブロック)。120 tick 目に残っている株を数えていた頃は、これで約 4% 落ちていた。
     *
     * <p>散らばり方は種を決めて毎回同じにする({@link #SCATTER_SEED})。ほかのテストが残した胞子除去装置の
     * 圏内だとシダの湧きが確率で不発になるので、台帳を外して走り、終わりに戻す。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 200, batch = BOMB_BATCH)
    public static void shotDownScattersFernBombs(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        long[][] devices = SporeDeviceData.isolateForTests(level);
        PediculaEntity pedicula = helper.spawnWithNoFreeWill(ModEntities.PEDICULA.get(), FEET);
        pedicula.getRandom().setSeed(SCATTER_SEED);
        pedicula.kill();

        // 爆弾は倒した瞬間に世界へ入っている(壁まで 2.5 ブロック以上あるので、まだ全部飛んでいる)。
        List<VelophyllBombEntity> bombs = level.getEntitiesOfClass(VelophyllBombEntity.class, helper.getBounds());
        long planting = bombs.stream().filter(VelophyllBombEntity::plantsFern).count();
        if (bombs.size() != 4 || planting != 4) {
            SporeDeviceData.restoreForTests(level, devices);
            helper.fail("散った胞子爆弾が 4 つでない(爆弾 " + bombs.size() + " / うちシダを植えるもの " + planting + ")");
            return;
        }
        if (bombs.stream().anyMatch(b -> b.size() >= 1.0F)) {
            SporeDeviceData.restoreForTests(level, devices);
            helper.fail("散る爆弾がヴェロフィルの爆弾と同じ大きさ(小さくなっていない)");
            return;
        }

        // 湧いたシダを、湧いた時点のマスで覚えておく(あとで汚染し終えて消えても数えられるように)。
        Map<UUID, BlockPos> ferns = new LinkedHashMap<>();
        helper.onEachTick(() -> {
            for (BlightFernEntity fern : level.getEntitiesOfClass(BlightFernEntity.class, helper.getBounds().inflate(1.0D))) {
                ferns.putIfAbsent(fern.getUUID(), fern.blockPosition());
            }
        });
        helper.runAfterDelay(3L, () -> {
            if (!pedicula.isRemoved()) {
                SporeDeviceData.restoreForTests(level, devices);
                helper.fail("倒したのに消えていない(バニラの 20 tick 倒れる演出のまま?)");
            }
        });
        // この種では 4 つとも 11 tick 目までに壁に当たって炸裂する。
        helper.runAfterDelay(60L, () -> {
            SporeDeviceData.restoreForTests(level, devices);
            int floorY = helper.absolutePos(FEET).getY();
            Map<BlockPos, Integer> expected = new LinkedHashMap<>();
            List<String> bursts = new ArrayList<>();
            for (VelophyllBombEntity bomb : bombs) {
                if (!bomb.isRemoved()) {
                    helper.fail("60 tick 経っても炸裂していない爆弾がある(いま " + relative(helper, bomb.position()) + ")");
                    return;
                }
                // 消えた爆弾は炸裂した場所に留まっている。その真下の床の上に 1 株湧くはず。
                expected.merge(BlockPos.containing(bomb.getX(), floorY, bomb.getZ()), 1, Integer::sum);
                bursts.add(relative(helper, bomb.position()));
            }
            Map<BlockPos, Integer> spawned = new LinkedHashMap<>();
            for (BlockPos at : ferns.values()) {
                spawned.merge(at, 1, Integer::sum);
            }
            if (!spawned.equals(expected)) {
                helper.fail("着弾点ごとに汚染シダが 1 株ずつ湧いていない: 炸裂点 " + bursts
                        + " / 湧くはずのマス " + relative(helper, expected) + " / 湧いたマス " + relative(helper, spawned));
                return;
            }
            helper.succeed();
        });
    }

    /** 失敗メッセージ用: テスト場の中の座標(小数 2 桁)。 */
    private static String relative(GameTestHelper helper, Vec3 absolute) {
        Vec3 v = helper.relativeVec(absolute);
        return String.format(Locale.ROOT, "(%.2f, %.2f, %.2f)", v.x, v.y, v.z);
    }

    /** 失敗メッセージ用: マスごとの株の数を、テスト場の中の座標で。 */
    private static String relative(GameTestHelper helper, Map<BlockPos, Integer> counts) {
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        List<String> out = new ArrayList<>();
        counts.forEach((pos, n) -> out.add(pos.subtract(origin).toShortString() + (n > 1 ? " ×" + n : "")));
        return out.toString();
    }

    // --- sub の個体差 ---

    /**
     * sub / sub2 / sub3 はそれぞれ独立に付いたり付かなかったりする(既定 50%)。
     * 付き方の 8 通りがすべて出ること、付き方がセーブを挟んでも変わらないこと。
     */
    @GameTest(template = TEMPLATE, timeoutTicks = 20, batch = BATCH)
    public static void subsVaryAndSurviveSave(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260923L);
        boolean[] seen = new boolean[8];
        int[] perBone = new int[PediculaEntity.SUB_BONE_COUNT];
        int rolls = 800;
        for (int i = 0; i < rolls; i++) {
            int mask = PediculaEntity.rollSubs(random, 0.5D);
            seen[mask] = true;
            for (int b = 0; b < PediculaEntity.SUB_BONE_COUNT; b++) {
                if ((mask & (1 << b)) != 0) {
                    perBone[b]++;
                }
            }
        }
        for (int mask = 0; mask < 8; mask++) {
            if (!seen[mask]) {
                helper.fail("sub の付き方 " + Integer.toBinaryString(mask) + " が 800 体で 1 度も出ない");
                return;
            }
        }
        for (int b = 0; b < PediculaEntity.SUB_BONE_COUNT; b++) {
            if (perBone[b] < rolls * 0.4 || perBone[b] > rolls * 0.6) {
                helper.fail("sub" + (b + 1) + " の付く割合が 50% から外れている: " + perBone[b] + " / " + rolls);
                return;
            }
        }

        ServerLevel level = helper.getLevel();
        PediculaEntity before = ModEntities.PEDICULA.get().create(level);
        PediculaEntity after = ModEntities.PEDICULA.get().create(level);
        if (before == null || after == null) {
            helper.fail("ペディキュラを作れない");
            return;
        }
        before.setSubMask(0b101);
        CompoundTag tag = new CompoundTag();
        before.saveWithoutId(tag);
        after.setSubMask(0b010);
        after.load(tag);
        if (after.subMask() != 0b101) {
            helper.fail("セーブを挟んだら sub の付き方が変わった: " + Integer.toBinaryString(after.subMask()));
            return;
        }
        helper.succeed();
    }

    private PediculaGameTests() {
    }
}

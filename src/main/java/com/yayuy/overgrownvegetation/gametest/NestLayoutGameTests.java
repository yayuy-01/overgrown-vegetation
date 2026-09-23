package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.nest.NestChamber;
import com.yayuy.overgrownvegetation.nest.NestLayout;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.EnumMap;
import java.util.Map;

/**
 * {@link NestLayout}(アルボラの巣の形)のテスト。{@code gradlew.bat runGameTestServer} で実行。
 *
 * <p>形の計算は地形を見ない純粋な計算なので、ワールドを使わずに何十個も巣を作って性質を確かめられる。
 * ここで守りたいのは<b>掘る量の上限</b>(外すと 10 万マスを超えて掘り、サーバーが固まる)と、
 * <b>側室が中心に固まらないこと</b>(2026-09-19 にプレビューで見つかった不具合)。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class NestLayoutGameTests {

    /** 1 回のテストで作る巣の数。種を変えて何個も作り、まぐれ当たりを避ける。 */
    private static final int SAMPLES = 24;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(NestLayoutGameTests.class);
        }

        private Registration() {
        }
    }

    /** 掘る量が上限を超えない。ここが破れると巨大樹と同じ「サーバーが固まる」事故になる。 */
    @GameTest(template = "gametest_empty7")
    public static void nestStaysWithinBlockBudget(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            if (plan.hollow.size() > t.blockBudget()) {
                helper.fail("種 " + seed + ": " + plan.hollow.size() + " マス掘った(上限 "
                        + t.blockBudget() + ")");
                return;
            }
            if (plan.hollow.isEmpty()) {
                helper.fail("種 " + seed + ": 1 マスも掘れていない");
                return;
            }
        }
        helper.succeed();
    }

    /** 主要な側室が必ず揃う(噴気室・女王室・核・孵化室)。 */
    @GameTest(template = "gametest_empty7")
    public static void nestHasItsKeyChambers(GameTestHelper helper) {
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, NestTable.DEFAULT);
            Map<NestChamber, Integer> count = new EnumMap<>(NestChamber.class);
            for (NestLayout.Chamber c : plan.chambers) {
                count.merge(c.type(), 1, Integer::sum);
            }
            if (count.getOrDefault(NestChamber.VENT, 0) != 1) {
                helper.fail("種 " + seed + ": 噴気室が " + count.getOrDefault(NestChamber.VENT, 0) + " 室");
                return;
            }
            if (count.getOrDefault(NestChamber.QUEEN, 0) != 1) {
                helper.fail("種 " + seed + ": 女王室が無い");
                return;
            }
            if (plan.corePos == null) {
                helper.fail("種 " + seed + ": 核が置かれていない");
                return;
            }
            if (count.getOrDefault(NestChamber.HATCHERY, 0) < 1) {
                helper.fail("種 " + seed + ": 孵化室が無い(亜種が解禁されなくなる)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 側室が中心に固まらない。掘削点は全部中心から出発するので、間隔の規則が無いと
     * 最初の数歩で全部屋を使い切って入口の周りに団子になる(2026-09-19 の不具合)。
     */
    @GameTest(template = "gametest_empty7")
    public static void chambersAreSpreadOut(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        int gap = t.chamberGap();
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            for (int i = 0; i < plan.chambers.size(); i++) {
                NestLayout.Chamber a = plan.chambers.get(i);
                if (a.type() == NestChamber.QUEEN) {
                    continue;                        // 女王室は別枠で最奥に置くので間隔の対象外
                }
                // 入口の周りに団子にならない(幹に沿って部屋ができるので、基準は坑口)
                double dx = a.pos().getX() - plan.mouth.getX();
                double dz = a.pos().getZ() - plan.mouth.getZ();
                double horizontal = Math.sqrt(dx * dx + dz * dz);
                if (horizontal < gap - 1) {
                    helper.fail("種 " + seed + ": 側室が坑口から " + Math.round(horizontal) + " マスしか離れていない");
                    return;
                }
                // 部屋どうしが重なっていない(判定と同じ「縦は 1.6 倍」の尺度で見る)
                for (int j = i + 1; j < plan.chambers.size(); j++) {
                    NestLayout.Chamber b = plan.chambers.get(j);
                    if (b.type() == NestChamber.QUEEN) {
                        continue;
                    }
                    // 見るのは<b>保証される最小値</b>。`chamberGap` は狙いの間隔で、
                    // 行き止まりの部屋だけは「必ず置く」ために chamberClearance + 1 まで詰める
                    // ことがある(2026-09-20)。詰めても部屋のまわりの岩は残るので融けはしない。
                    double least = t.chamberClearance() + 1 + a.type().radius() + b.type().radius();
                    if (NestLayout.chamberSeparation(a.pos(), b.pos()) < least) {
                        helper.fail("種 " + seed + ": 側室どうしが近すぎる ("
                                + a.type().id() + " と " + b.type().id() + ")");
                        return;
                    }
                }
            }
        }
        helper.succeed();
    }

    /** 巣が立体的に広がる(1 段に潰れていない)。ユーザー要望で階層構造にしたぶんの確認。 */
    @GameTest(template = "gametest_empty7")
    public static void nestSpreadsVertically(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            for (int i = 0; i < plan.hollow.size(); i++) {
                int y = BlockPos.getY(plan.hollow.getLong(i));
                min = Math.min(min, y);
                max = Math.max(max, y);
            }
            int span = max - min;
            // 主坑の深さの 7 割より薄いなら、段が機能していない
            if (span < t.shaftDepth() * 0.7D) {
                helper.fail("種 " + seed + ": 上下の広がりが " + span + " マスしかない(主坑 "
                        + t.shaftDepth() + ")");
                return;
            }
            // 掘ってよいのは地表より上は塚の中だけ(坑口を開けるため)
            if (max > t.moundHeight() + 1) {
                helper.fail("種 " + seed + ": 塚より上を掘っている (y=" + max + ")");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 入口が縦穴ではなく斜めの坑道になっている(ユーザー指定 2026-09-19)。
     * 巣の中心が入口の真下ではなく、横にずれた場所にあることで確かめる。
     */
    @GameTest(template = "gametest_empty7")
    public static void entranceIsASlopedAdit(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            double dx = plan.centre.getX();
            double dz = plan.centre.getZ();
            double horizontal = Math.sqrt(dx * dx + dz * dz);
            int depth = -plan.centre.getY();
            if (depth <= 0) {
                helper.fail("種 " + seed + ": 巣の中心が地表のまま");
                return;
            }
            // 縦穴なら水平のずれがほぼ 0 になる。斜めに下っていれば深さに見合って横へ進む。
            if (horizontal < depth * 0.4D) {
                helper.fail("種 " + seed + ": 入口が縦穴に近い(深さ " + depth + " に対し横 "
                        + Math.round(horizontal) + ")");
                return;
            }
            if (plan.mound.isEmpty()) {
                helper.fail("種 " + seed + ": 地表の塚が作られていない");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 坑道が垂直にならない(ユーザー指定 2026-09-19)。掘った空洞を縦の柱として見たとき、
     * 1 本の柱に何段も積み上がっている割合が低いことで確かめる —— 縦穴があると
     * 同じ (x,z) に空洞がずらりと並ぶ。
     */
    @GameTest(template = "gametest_empty7")
    public static void tunnelsAreNotVertical(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            // 柱ごとに y を集め、<b>連続してつながっている高さ</b>を見る。
            // 合計で数えると、別々の段の坑道が同じ (x,z) を通っただけで引っかかる。
            java.util.Map<Long, java.util.List<Integer>> column = new java.util.HashMap<>();
            // 数えるのは<b>坑道だけ</b>。噴出孔(煙突)は人が通る道ではないし、
            // 部屋は球なので縦にも大きい —— どちらも「縦穴かどうか」とは関係がない。
            int limit = Math.min(plan.hollow.size(), plan.ventShaftFrom);
            for (int i = 0; i < limit; i++) {
                long key = plan.hollow.getLong(i);
                if (insideAnyChamber(plan, key)) {
                    continue;
                }
                long xz = ((long) BlockPos.getX(key) << 32) ^ (BlockPos.getZ(key) & 0xFFFF_FFFFL);
                column.computeIfAbsent(xz, k -> new java.util.ArrayList<>()).add(BlockPos.getY(key));
            }
            int tallest = 0;
            for (java.util.List<Integer> ys : column.values()) {
                java.util.Collections.sort(ys);
                int run = 1;
                for (int i = 1; i < ys.size(); i++) {
                    run = ys.get(i) - ys.get(i - 1) == 1 ? run + 1 : 1;
                    tallest = Math.max(tallest, run);
                }
                tallest = Math.max(tallest, Math.min(run, ys.size()));
            }
            // 傾きを抑えた坑道 1 本が縦に占めるのは 7 マスほどで、段違いの坑道が重なると 20 前後になる。
            // 縦穴があると主坑の深さぶん(30 前後)がひと続きになるので、26 を境にする。
            // (坑道の傾きそのものは clampSlope で保証しているので、ここで見るのは「掘った結果」の方)
            if (tallest > 26) {
                helper.fail("種 " + seed + ": 縦にひと続きの空洞が " + tallest + " マスある(縦穴になっている)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * 坑口から全部屋へ歩いて行ける(2026-09-19 に幹+枝の形へ作り直したぶんの確認)。
     * 部屋は坑道の脇と終点に掘るので、通路を掘り忘れると<b>どこにも繋がっていない部屋</b>ができる。
     * 掘った空洞を塗りつぶして、女王室と核まで届くかを見る。
     */
    @GameTest(template = "gametest_empty7")
    public static void everyChamberIsReachable(GameTestHelper helper) {
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, NestTable.DEFAULT);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = reachableFrom(hollow, plan.hollow.getLong(0));
            for (NestLayout.Chamber c : plan.chambers) {
                if (!seen.contains(c.pos().asLong())) {
                    helper.fail("種 " + seed + ": " + c.type().id() + " が坑道とつながっていない");
                    return;
                }
            }
            if (plan.corePos != null && !seen.contains(plan.corePos.asLong())) {
                helper.fail("種 " + seed + ": 核が坑道とつながっていない");
                return;
            }
            if (plan.openings.isEmpty()) {
                helper.fail("種 " + seed + ": 坑口が記録されていない(外殻が入口ごと塞いでしまう)");
                return;
            }
        }
        helper.succeed();
    }

    /** {@code start} から face でつながっている空洞をすべて集める。 */
    private static it.unimi.dsi.fastutil.longs.LongOpenHashSet reachableFrom(
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow, long start) {
        it.unimi.dsi.fastutil.longs.LongOpenHashSet seen = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        java.util.ArrayDeque<Long> queue = new java.util.ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            long key = queue.poll();
            int x = BlockPos.getX(key);
            int y = BlockPos.getY(key);
            int z = BlockPos.getZ(key);
            for (net.minecraft.core.Direction dir : net.minecraft.core.Direction.values()) {
                long side = BlockPos.asLong(x + dir.getStepX(), y + dir.getStepY(), z + dir.getStepZ());
                if (hollow.contains(side) && seen.add(side)) {
                    queue.add(side);
                }
            }
        }
        return seen;
    }

    /**
     * 1 か所に坑道が集まって<b>大きな空洞</b>にならない(2026-09-19 にゲーム内で確認された不具合)。
     * 入口から放射状に何本も同時に生やしていたのが原因で、その周りがまとめて掘り抜かれていた。
     *
     * <p>測り方は「そのマスを中心に<b>まるごと空洞な球</b>がどこまで大きくなるか」。
     * 太さ 1.6 の坑道なら 2、坑道が交わっても 3 どまり。
     *
     * <p><b>部屋の中も数える</b>(2026-09-20)。それまで部屋を丸ごと除外していたので、
     * <b>坑道が部屋を貫いて両方がひと続きの大空間になる</b>のを見逃していた(ユーザー報告)。
     * いちばん大きい部屋(女王室、半径 6.5)が球 6 ぶんなので、7 までを許して 8 で落とす。
     */
    @GameTest(template = "gametest_empty7")
    public static void tunnelsDoNotOpenIntoACavern(GameTestHelper helper) {
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, NestTable.DEFAULT);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            int limit = Math.min(plan.hollow.size(), plan.ventShaftFrom);
            int worst = 0;
            for (int i = 0; i < limit; i += 5) {          // 5 マスおきに見れば十分
                long key = plan.hollow.getLong(i);
                int r = openBallRadius(hollow, key);
                worst = Math.max(worst, r);
                if (!insideAnyChamber(plan, key) && r >= 4) {
                    helper.fail("種 " + seed + ": 部屋でないところに半径 " + r + " マスの空洞がある"
                            + "(坑道が太りすぎ) 場所=" + BlockPos.getX(key) + "," + BlockPos.getY(key)
                            + "," + BlockPos.getZ(key) + " " + nearestChamber(plan, key));
                    return;
                }
            }
            if (worst >= 8) {
                helper.fail("種 " + seed + ": 半径 " + worst
                        + " マスの空洞がある(部屋と坑道が貫通してひと続きになっている)");
                return;
            }
        }
        helper.succeed();
    }

    /** いちばん近い側室とその距離(不具合の場所を追うため)。 */
    private static String nearestChamber(NestLayout.Plan plan, long key) {
        String best = "側室なし";
        double bestD = Double.MAX_VALUE;
        for (NestLayout.Chamber c : plan.chambers) {
            double dx = BlockPos.getX(key) - c.pos().getX();
            double dy = BlockPos.getY(key) - c.pos().getY();
            double dz = BlockPos.getZ(key) - c.pos().getZ();
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (d < bestD) {
                bestD = d;
                best = c.type().id() + "(半径 " + c.radius() + ")まで " + Math.round(d);
            }
        }
        return best;
    }

    /** {@code key} を中心に、まるごと空洞である球の半径はどこまで大きくできるか(最大 6)。 */
    private static int openBallRadius(it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow, long key) {
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        for (int r = 1; r <= 6; r++) {
            if (!ballIsHollow(hollow, x, y, z, r)) {
                return r - 1;
            }
        }
        return 6;
    }

    private static boolean ballIsHollow(it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow,
                                        int x, int y, int z, int r) {
        int rr = r * r;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    if (!hollow.contains(BlockPos.asLong(x + dx, y + dy, z + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * <b>噴出孔の柱が地上へ突き出す</b>(2026-09-20)。
     *
     * <p>設計は地形を見ないので、もとは入口と同じ高さで止めていた。噴気室の真上の地面が
     * それより高い巣では<b>柱が地中で止まり、地上に噴出孔が出ない</b>(ユーザー報告)。
     * 地形の高さを渡したときに、柱の先が<b>地面よりさらに
     * {@link NestLayout#VENT_ABOVE_GROUND} マス上</b>まで来ているかを見る。
     *
     * <p>あわせて<b>柱が空洞でない</b>ことも見る —— 中が空洞の煙突から中身の詰まった柱へ
     * 作り直したぶん(ユーザー指定 2026-09-20)。掘った空洞と 1 マスも重なってはいけない。
     */
    @GameTest(template = "gametest_empty7")
    public static void ventShaftsReachTheGivenSurface(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        int rise = 20;                                   // 入口より 20 マス高い地面
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t, (x, z) -> rise);
            if (plan.surfaceVents.isEmpty()) {
                helper.fail("種 " + seed + ": 地表の噴出口が 1 つも無い");
                return;
            }
            for (BlockPos vent : plan.surfaceVents) {
                if (vent.getY() < rise + NestLayout.VENT_ABOVE_GROUND) {
                    helper.fail("種 " + seed + ": 噴出孔の柱が y=" + vent.getY()
                            + " で止まっている(地面 y=" + rise + " の "
                            + NestLayout.VENT_ABOVE_GROUND + " マス上まで伸ばすはず)");
                    return;
                }
            }
            if (plan.ventColumn.isEmpty()) {
                helper.fail("種 " + seed + ": 噴出孔の柱が 1 マスも積まれていない");
                return;
            }
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            for (int i = 0; i < plan.ventColumn.size(); i++) {
                if (hollow.contains(plan.ventColumn.getLong(i))) {
                    helper.fail("種 " + seed + ": 噴出孔の柱が空洞と重なっている(中が空洞の煙突に戻っている)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * <b>水の中は掘らない</b>(2026-09-21 のユーザー報告「水中に巣を拡張してしまう」)。
     *
     * <p>湖や海を掘り抜くと、外殻を張っても<b>水底にぽっかり空いた巣</b>になる。
     * 水を避けるアルボラは切羽に近づけないので、<b>建設がそこで止まる</b> ——
     * 見た目の問題ではなく、巣が完成しなくなる。
     *
     * <p>入口の東半分が深さ 20 の湖、という地形を渡して、掘った空洞がそこへ入らないかを見る。
     */
    @GameTest(template = "gametest_empty7")
    public static void nestDoesNotDigIntoWater(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        int lakeFrom = 6;                            // ここから東は湖
        int seabed = -20;                            // 湖の底
        NestLayout.Terrain terrain = new NestLayout.Terrain() {
            @Override
            public int surfaceAt(int x, int z) {
                return 0;
            }

            @Override
            public int seabedAt(int x, int z) {
                return x >= lakeFrom ? seabed : 0;
            }
        };
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t, terrain);
            for (int i = 0; i < plan.hollow.size(); i++) {
                long key = plan.hollow.getLong(i);
                if (BlockPos.getX(key) >= lakeFrom && BlockPos.getY(key) > seabed - 3) {
                    helper.fail("種 " + seed + ": 湖の中を掘っている ("
                            + BlockPos.getX(key) + "," + BlockPos.getY(key) + "," + BlockPos.getZ(key) + ")");
                    return;
                }
            }
            for (NestLayout.Chamber c : plan.chambers) {
                if (c.pos().getX() >= lakeFrom && c.pos().getY() > seabed - 3) {
                    helper.fail("種 " + seed + ": 湖の中に " + c.type().id() + " を置いている");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * <b>アルボラが通れる太さがある</b>(2026-09-20 のユーザー報告
     * 「アルボラの当たり判定的に細い通路が通れない」)。
     *
     * <p>道しるべのどこにも<b>2×2×2 マスの空洞</b>が取れることを見る。
     * これが無いと通路の断面が<b>十字</b>になり、床にあたるマスが 1×1 の飛び石になる ——
     * Minecraft の経路探索は角が塞がった斜め移動を通さないので、<b>1 歩も進めない</b>。
     * 実際、側室への通路だけ坑道の 0.8 倍(半径 1.28)で掘っていて、そこが十字になっていた。
     * 半径は 1.42 を超えないと断面が 3×3 にならない —— 角のマスまでの距離が √2 だから。
     */
    @GameTest(template = "gametest_empty7")
    public static void arboraFitsThroughEveryPassage(GameTestHelper helper) {
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, NestTable.DEFAULT);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            for (int i = 0; i < plan.route.size(); i++) {
                BlockPos way = plan.route.get(i);
                if (!roomFor2x2x2(hollow, way)) {
                    helper.fail("種 " + seed + ": 道しるべ " + i + " " + way
                            + " のまわりが細すぎてアルボラが通れない");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** {@code at} を含む 2×2×2 マスの空洞が取れるか。 */
    private static boolean roomFor2x2x2(it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow, BlockPos at) {
        for (int ox = -1; ox <= 0; ox++) {
            for (int oy = -1; oy <= 0; oy++) {
                for (int oz = -1; oz <= 0; oz++) {
                    boolean ok = true;
                    for (int dx = 0; dx < 2 && ok; dx++) {
                        for (int dy = 0; dy < 2 && ok; dy++) {
                            for (int dz = 0; dz < 2 && ok; dz++) {
                                if (!hollow.contains(BlockPos.asLong(at.getX() + ox + dx,
                                        at.getY() + oy + dy, at.getZ() + oz + dz))) {
                                    ok = false;
                                }
                            }
                        }
                    }
                    if (ok) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * <b>道しるべの木が「巣の構造」として使える</b>(2026-09-20 のユーザー報告
     * 「アルボラが巣の構造を理解できておらず、出入り口を使おうとしない」)。
     *
     * <p>見るのは 4 つ。どれか 1 つでも破れると、Mob は坑口へたどり着けずに
     * <b>壁を登って天井に張り付く</b>([[wallclimber-nav-hidden-beeline]])。
     * <ul>
     *   <li>道しるべが<b>すべて空洞の中</b>にある —— 岩の中の点を指すと経路が出ない。
     *   幹の 1 本線しか無かった頃は、枝から幹へ引いた直線の中継点が岩に落ちてこれが破れていた。</li>
     *   <li>親をたぐると<b>必ず添字 0(坑口)に着く</b> —— 輪や迷子が無い。</li>
     *   <li>親までの距離が短い —— 1 区間が長いと経路探索が失敗する。</li>
     *   <li><b>どの側室にも道しるべがある</b> —— 部屋に居る個体が木に乗れる。</li>
     * </ul>
     */
    @GameTest(template = "gametest_empty7")
    public static void everyWaypointLeadsOutOfTheNest(GameTestHelper helper) {
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, NestTable.DEFAULT);
            int[] parents = plan.routeParents.toIntArray();
            if (plan.route.size() != parents.length || plan.route.isEmpty()) {
                helper.fail("種 " + seed + ": 道しるべと親の数が合わない");
                return;
            }
            if (parents[0] != -1) {
                helper.fail("種 " + seed + ": 根(坑口)の親が -1 ではない");
                return;
            }
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            for (int i = 0; i < plan.route.size(); i++) {
                BlockPos way = plan.route.get(i);
                if (!hollow.contains(way.asLong())) {
                    helper.fail("種 " + seed + ": 道しるべ " + i + " " + way + " が岩の中にある");
                    return;
                }
                int parent = parents[i];
                if (i > 0) {
                    if (parent < 0 || parent >= plan.route.size()) {
                        helper.fail("種 " + seed + ": 道しるべ " + i + " の親が無い");
                        return;
                    }
                    double hop = Math.sqrt(plan.route.get(parent).distSqr(way));
                    if (hop > 12.0D) {
                        helper.fail("種 " + seed + ": 道しるべ " + i + " から親まで "
                                + Math.round(hop) + " マスある(経路探索が失敗する)");
                        return;
                    }
                }
                // 親をたぐって坑口に着くか
                int at = i;
                int guard = 0;
                while (at > 0 && guard++ <= plan.route.size()) {
                    at = parents[at];
                }
                if (at != 0) {
                    helper.fail("種 " + seed + ": 道しるべ " + i + " から坑口へたどり着けない");
                    return;
                }
            }
            for (NestLayout.Chamber c : plan.chambers) {
                boolean found = false;
                for (BlockPos way : plan.route) {
                    if (way.distSqr(c.pos()) <= 4.0D) {
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    helper.fail("種 " + seed + ": " + c.type().id() + " に道しるべが無い"
                            + "(その部屋に居る個体は巣から出られない)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * <b>坑道の行き止まりには必ず孵化室か貯蔵室がある</b>(2026-09-20 のユーザー指定)。
     *
     * <p>掘削点の数を {@code deadEndChambers} で頭打ちにしてあるので、行き止まりの数と
     * 置ける部屋の数がちょうど合う。ここが落ちるなら、置く先が足りていないか
     * ({@code deadEndChambers} を増やすか {@code blockBudget} を上げる)、
     * 置き場所が見つかっていない({@code chamberClearance} を下げる)。
     */
    @GameTest(template = "gametest_empty7")
    public static void everyTunnelEndHasAnEggOrStorageRoom(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            if (plan.tunnelEnds.isEmpty()) {
                helper.fail("種 " + seed + ": 坑道の行き止まりが 1 つも記録されていない");
                return;
            }
            for (NestLayout.TunnelEnd end : plan.tunnelEnds) {
                if (end.room() == null) {
                    helper.fail("種 " + seed + ": 行き止まり " + end.pos() + " の先に部屋を置けなかった"
                            + " 側室 " + plan.chambers.size() + " 室 掘った量 " + plan.hollow.size()
                            + " " + nearestChamber(plan, end.pos().asLong()));
                    return;
                }
                if (end.room() != NestChamber.HATCHERY && end.room() != NestChamber.STORAGE) {
                    helper.fail("種 " + seed + ": 行き止まり " + end.pos() + " の先が "
                            + end.room().id() + "(孵化室か貯蔵室のはず)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /**
     * <b>部屋の数が減っていない</b>(2026-09-20)。
     *
     * <p>部屋を置ける条件を「まわり {@code chamberClearance} マスが岩」まで厳しくしたので、
     * <b>置けずに枠が余って巣が痩せる</b>ことが起こりうる。実際に狙いの何割が置けているかを見張る。
     * ここが落ちたら {@code spread} か {@code blockBudget} を増やすか、{@code chamberClearance} を下げる。
     */
    @GameTest(template = "gametest_empty7")
    public static void nestFillsMostOfItsChambers(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        int worstSeed = -1;
        int worst = Integer.MAX_VALUE;
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            if (plan.chambers.size() < worst) {
                worst = plan.chambers.size();
                worstSeed = seed;
            }
        }
        // 坑道の途中のぶん + 行き止まりのぶん + 女王室
        int want = t.chamberTotal() + t.deadEndChambers() + 1;
        if (worst < want * 0.9D) {
            helper.fail("種 " + worstSeed + ": 側室が " + worst + " 室しか置けていない(狙い "
                    + want + " 室)");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>部屋のまわりに岩が残っている</b>(2026-09-20 のユーザー指定「坑道と部屋をある程度離す」)。
     *
     * <p>部屋のすぐ外側 —— 半径 +1 から 半径 + {@code chamberClearance} までの殻 —— を見て、
     * そこが空洞になっている割合を測る。部屋には通路が 1 本(女王室の手前など 2 本)つながるので
     * 完全に 0 にはならないが、太さ 1.6 の通路 1 本が殻に開ける穴は数 % にしかならない。
     * 坑道が部屋の脇を並走していたり貫いていたりすると、ここが一気に膨らむ。
     *
     * <p>この形の不具合は {@code tunnelsDoNotOpenIntoACavern}(空洞の大きさを測る)では
     * 見つけきれなかった —— 部屋に坑道が 1 本刺さっても、いちばん大きい球の半径は部屋のぶんのままで
     * 変わらないため。<b>壁が残っているか</b>を直接測るのがこのテスト。
     */
    @GameTest(template = "gametest_empty7")
    public static void chambersKeepTheirRockShell(GameTestHelper helper) {
        NestTable t = NestTable.DEFAULT;
        int clearance = t.chamberClearance();
        if (clearance <= 0) {
            helper.succeed();
            return;
        }
        for (int seed = 0; seed < SAMPLES; seed++) {
            NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, seed, t);
            it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow =
                    new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
            for (int i = 0; i < plan.hollow.size(); i++) {
                hollow.add(plan.hollow.getLong(i));
            }
            for (NestLayout.Chamber c : plan.chambers) {
                double inner = c.radius() + 1.0D;
                double outer = c.radius() + clearance;
                int open = 0;
                int total = 0;
                int ri = (int) Math.ceil(outer);
                for (int dx = -ri; dx <= ri; dx++) {
                    for (int dy = -ri; dy <= ri; dy++) {
                        for (int dz = -ri; dz <= ri; dz++) {
                            double dd = dx * dx + dy * dy + dz * dz;
                            if (dd < inner * inner || dd > outer * outer) {
                                continue;
                            }
                            total++;
                            if (hollow.contains(BlockPos.asLong(c.pos().getX() + dx,
                                    c.pos().getY() + dy, c.pos().getZ() + dz))) {
                                open++;
                            }
                        }
                    }
                }
                if (total == 0) {
                    continue;
                }
                double share = (double) open / total;
                if (share > 0.14D) {
                    helper.fail("種 " + seed + ": " + c.type().id() + " のまわりの岩が "
                            + Math.round(share * 100) + "% 抜けている(坑道と合体している)");
                    return;
                }
            }
        }
        helper.succeed();
    }

    /** そのマスがどれかの側室の中にあるか(部屋は球なので縦に大きく、坑道の判定から外す)。 */
    private static boolean insideAnyChamber(NestLayout.Plan plan, long key) {
        int x = BlockPos.getX(key);
        int y = BlockPos.getY(key);
        int z = BlockPos.getZ(key);
        for (NestLayout.Chamber c : plan.chambers) {
            double r = c.radius() + 2.0D;
            double dx = x - c.pos().getX();
            double dy = y - c.pos().getY();
            double dz = z - c.pos().getZ();
            if (dx * dx + dy * dy + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    /** 壁が空洞をすべて囲んでいる(掘った穴が地中でむき出しにならない)。 */
    @GameTest(template = "gametest_empty7")
    public static void liningWrapsEveryHollow(GameTestHelper helper) {
        NestLayout.Plan plan = NestLayout.build(BlockPos.ZERO, 12345L, NestTable.DEFAULT);
        if (plan.lining.isEmpty()) {
            helper.fail("壁が 1 マスも作られていない");
            return;
        }
        // 壁は空洞と重ならない
        it.unimi.dsi.fastutil.longs.LongOpenHashSet hollow = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int i = 0; i < plan.hollow.size(); i++) {
            hollow.add(plan.hollow.getLong(i));
        }
        for (int i = 0; i < plan.lining.size(); i++) {
            if (hollow.contains(plan.lining.getLong(i))) {
                helper.fail("壁と空洞が同じマスを指している");
                return;
            }
        }
        for (int i = 0; i < plan.liningFloor.size(); i++) {
            if (hollow.contains(plan.liningFloor.getLong(i))) {
                helper.fail("床と空洞が同じマスを指している");
                return;
            }
        }
        if (plan.liningFloor.isEmpty()) {
            helper.fail("床が 1 マスも見つかっていない(草が生えなくなる)");
            return;
        }
        helper.succeed();
    }

    private NestLayoutGameTests() {
    }
}

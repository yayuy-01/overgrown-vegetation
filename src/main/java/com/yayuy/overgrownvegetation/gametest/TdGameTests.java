package com.yayuy.overgrownvegetation.gametest;

import com.google.gson.Gson;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.OvergrownLayerData;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemData;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMembership;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.td.TargetBlock;
import com.yayuy.overgrownvegetation.td.TargetBlockEntity;
import com.yayuy.overgrownvegetation.td.TdData;
import com.yayuy.overgrownvegetation.td.TdDirection;
import com.yayuy.overgrownvegetation.td.TdEvents;
import com.yayuy.overgrownvegetation.td.TdHostiles;
import com.yayuy.overgrownvegetation.td.TdSiege;
import com.yayuy.overgrownvegetation.td.TdSight;
import com.yayuy.overgrownvegetation.td.TdTable;
import com.yayuy.overgrownvegetation.td.TdTargets;
import com.yayuy.overgrownvegetation.td.TdTestAccess;
import com.yayuy.overgrownvegetation.td.TdTicker;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeFeature;
import com.yayuy.overgrownvegetation.worldgen.GiantTreeGrowthQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestListener;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ExperienceOrb;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SaplingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.PositionalRandomFactory;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * <b>TD イベント(森の侵攻)</b>の GameTest(2026-09-25、{@code td/})。
 *
 * <h2>守りたいこと(TD 設計 v2 の「Tests」)</h2>
 * <ul>
 *     <li>前衛(アルボラ)が本物の AI でターゲットを囲み、1 回の攻撃力ぶん耐久を削る。支援・斥候が同じ群れで隣にいても落ちない
 *     ({@link #frontMemberStrikeDamagesTarget})。</li>
 *     <li>1 回の削りの量は役ごと(前衛 = 攻撃力の属性、支援・斥候 = td.json、空・群れに入らない種 = 0)。攻撃力の属性を持たない種
 *     (フィロセンス)でも落ちない。増援の一時停止中でも削る。今の侵攻のものでない塊は削らない({@link #strikeAmountFollowsRole})。</li>
 *     <li><b>負け</b>(ユーザー指定 2026-09-24「ターゲットが壊れたら負け、拠点の周りに汚染」): ターゲットが消え、切り出した
 *     バイオマス(予算 + 仮想の群れ)が世界へ返り、汚染が<b>積んで</b>噴き出す({@link #defeatRemovesTargetRefundsAndBursts})。
 *     進行中のターゲットはサバイバルのプレイヤーには掘れず、侵攻中に無くなれば(爆発・/setblock)負け、予告中なら取りやめ
 *     ({@link #liveTargetCannotBeMinedAndRemovalIsDefeat})。</li>
 *     <li>壁のすぐ裏のターゲット・1 段の台の上のターゲットも、本物の AI の攻め手(アルボラだけ)が削る
 *     ({@link #targetBehindWallIsBreached}・{@link #targetOnPedestalIsStruck})。手の届く高さ({@link #blockReachCoversFloorAndPedestal})、
 *     床の下のターゲットへ掘る列({@link #coverScanDigsDownToSunkenTarget})、ガラスの向こう・ターゲットの下の灯り
 *     ({@link #lampBehindGlassAndUnderTargetIsHandled})。</li>
 *     <li><b>勝ち</b>(ユーザー指定「予算が 0 で攻め手が全滅したら勝ち」): 予算がもう 1 群れも買えない・仮想の群れなし・生きている
 *     攻め手 0 がそろったときだけ。1 つでも欠ければ続く({@link #victoryOnlyWhenBudgetVirtualAndAliveAreGone})。その境目は
 *     群れの先頭になれる種の値段で、境目ちょうどの予算なら必ず 1 群れ買える(端数が残って終わらない侵攻にしない。
 *     {@link #budgetAtTheBorderStillBuysASwarm})。</li>
 *     <li>ボスバーの「残り N バイオマス」(ユーザー指定 2026-09-26)は群れを買っても実体にしても変わらず、倒した攻め手の値段だけ減り、
 *     もう使えない予算の端数は数えない(最後の 1 体を倒すと 0)({@link #remainingBiomassFollowsCostsNotHeads})。</li>
 *     <li>仮想の群れは近くのプレイヤーの見える範囲(描画距離と霧の終わりの近い方)の外で生まれる(ユーザー指定 2026-09-26「外周の霧の
 *     中から出てくる感じに」。{@link #swarmsAreBornOutOfSight})。始めの一斉投入も霧の外で生まれ、着くまで毎 tick 実体にする
 *     ({@link #openingWaveIsBornOutOfSight})。</li>
 *     <li>攻めてくる向き(ユーザー指定 2026-09-26「全周囲 4 割・特定の方向 6 割、過繁茂したバイオームがある方向からになりやすく、
 *     /ov td start north で指定」): 全方位と一方向の割合・過繁茂の多い向きへの寄り方・過繁茂の数え方({@link #directionRollFollowsShareAndOvergrowth})、
 *     一方向の群れは扇の中に広がる({@link #fanPickStaysInsideAndSpreads})、本物のコマンドで北から始まり群れも横歩きも北の扇の中
 *     ({@link #commandStartsInvasionFromTheNorth})。ほかの侵攻のテストは全方位に決めて始める({@link TdStage#start})。</li>
 *     <li>生きている攻め手の数は、倒された・消されたときだけ減る。読み込み外れ・ディメンションの移動・区画が見えなくなった
 *     だけでは減らない({@link #aliveCountIgnoresUnloadAndDimensionChange})。</li>
 *     <li>仮想の群れは同時の上限(ユーザーが受け入れた仮の数値 200、2026-09-25 に 300。読み込まれている攻め手で数える)の内側でだけ実体になる
 *     ({@link #virtualSwarmMaterializesOnlyUnderTheCap})。</li>
 *     <li>壁を選ぶ DDA は最初の「壊してよい」塊を返す —— 岩盤・溶岩に面した塊は飛ばし
 *     (壊せない断面の奥は探さない)、予備のターゲット・中身の無いブロックエンティティ・水に面した塊は壊し、
 *     登れる段差・カーペットは壁と数えない({@link #breachScanPicksFirstBreakableWall})。</li>
 *     <li>侵攻の間(ユーザー指定 2026-09-26): 汚染されたブロック(タグ {@code #corrupted}・苔で覆ったマス)は壊すのに要る量が
 *     {@code corruptedBreakTimeMultiplier} 倍で、叩いて壊すまでの回数もそのぶん減る({@link #corruptedBlocksBreakFasterDuringInvasion})。
 *     苗木は巨大樹にならない —— 巨大樹の苗木は育たずに待ち、木の抽選の巨大樹の目は失敗する
 *     ({@link #saplingsDoNotBecomeGiantTreesDuringInvasion})。</li>
 *     <li>進行中の侵攻のターゲットから {@code defenseSuppressRadius} 以内では生態系防衛を出さない
 *     ({@link #ecosystemDefenseSuppressedNearLiveInvasion})。</li>
 *     <li>誰もいないまま止まり続けた侵攻は取りやめになり、予算 + 仮想の群れを返す。止まっている間、仮想の群れは進まない
 *     ({@link #abandonedInvasionRefundsBudgetAndVirtual})。</li>
 *     <li>予告のときに攻めてくる向きを振り、保存しても残り、侵攻もその向きで来る({@link #warningRollsDirectionAndInvasionKeepsIt}。
 *     2026-10-08 順 2 の着手順 8。見張り台が知らせる基地と文は {@code BaseWatchGameTests})。</li>
 *     <li>同梱の {@code td.json} と Java の既定値が同じ({@link #bundledJsonMatchesDefault})。</li>
 * </ul>
 *
 * <h2>バッチの分け方</h2>
 * TD の台帳({@link TdData})・世界のバイオマス({@link EcosystemData})・「プレイヤーを無視する」印は世界に 1 つで、
 * <b>同じバッチのテストは同時に走る</b>(メモリ「GameTest で遠くのチャンクを使うとき」)。侵攻を始めるテストは互いの侵攻を
 * 中止・払い戻しし合うので、<b>1 本 1 バッチ</b>({@code td_*})にしてある(バッチは順番に走る。{@code ecosystem_kills} と同じ考え)。
 * 世界に触らない検査(json・壁の DDA)だけがバッチ {@value #BATCH} を共有する。
 * 侵攻を始めるテストは {@link TdStage} で台帳を借り({@link TdTestAccess#isolate})、成功・失敗どちらでも終わるときに
 * イベントを止め、置いたもの・バイオマス・台帳・設計値・プレイヤーを無視する印を元に戻す。
 *
 * <h2>確かめられないこと</h2>
 * GameTest のサーバーにはプレイヤーがいない({@code level.players()} が空)。そのため
 * <ul>
 *     <li>一時停止しないテストは {@link TdTicker#setIgnorePlayersForTests} を立てる(取りやめのテストだけは立てずに、本当に止まるのを見る)。</li>
 *     <li>生態系防衛はプレイヤーごとに回るので、防衛の本体は走らない。止める判定({@link TdEvents#suppressesEcosystemDefense})を直に見る。</li>
 *     <li>自動の始まりは見ない(コマンドと同じ {@link TdTicker#forceStart} で侵攻から始める。予告から始めるのは
 *     {@link #warningRollsDirectionAndInvasionKeepsIt} だけで、全員へのチャット・見張り台の知らせが届くところは見ない)。ボスバーはサーバーが
 *     持つ名前と長さだけ見る(プレイヤーに送られるところは見ない)。</li>
 * </ul>
 * 台帳の数え(生きている数・仮想の群れ・次に群れを作る時刻)は {@link TdTestAccess} で「その状態」を作り、判定は本物の帳簿
 * (20 tick ごと)に任せる。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class TdGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    /** 世界に触らない検査のバッチ。 */
    private static final String BATCH = "td";
    private static final float EPS = 1.0E-3F;
    private static final double BIOMASS_EPS = 1.0E-6D;
    /** 帳簿(20 tick ごと)を少なくとも 2 回はさむ長さ(tick)。 */
    private static final int TWO_BOOKKEEPINGS = 45;
    /** 負けの汚染を見るターゲットの相対の高さ。汚染の球(半径 24 + 揺らぎ 2)がテスト場の天井(相対 8)に届かない高さ。 */
    private static final int SKY_Y = 40;
    /** 負けの汚染の「遠い見張り」をターゲットから置く距離(ブロック、+X)。最初の 3 個の小球(中心と -X・-Y)では届かない。 */
    private static final int FAR_PROBE = 16;
    /** 負けの汚染の小球(81 個)を 1 tick 3 個ずつ作り終えるまで待つ長さ(tick)。 */
    private static final int BURST_DRAIN_TICKS = 30;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(TdGameTests.class);
        }

        private Registration() {
        }
    }

    // ------------------------------------------------------------------ 設計値

    /** 同梱の {@code design/td.json} と Java の既定値が同じ(設計値は 3 か所 —— 同梱・run/config・Java)。 */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void bundledJsonMatchesDefault(GameTestHelper helper) {
        TdTable bundled = null;
        String error = null;
        try (InputStream in = TdGameTests.class.getResourceAsStream("/overgrown_vegetation/design/td.json")) {
            if (in == null) {
                error = "design/td.json が同梱されていない";
            } else {
                bundled = TdTable.fromJson(new Gson().fromJson(
                        new InputStreamReader(in, StandardCharsets.UTF_8), TdTable.Json.class));
            }
        } catch (Exception e) {
            error = "design/td.json が読めない: " + e;
        }
        if (error != null || bundled == null) {
            helper.fail(error != null ? error : "design/td.json が空");
            return;
        }
        String json = bundled.describe();
        String java = TdTable.DEFAULT.describe();
        if (!json.equals(java)) {
            helper.fail("td.json が Java の既定値と違う\njson: " + json + "\njava: " + java);
            return;
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------ 壁を選ぶ(BreachPicker の DDA)

    /**
     * 先導役の足(相対 0.5, 2, 3.5)から +X のターゲット(相対 6, 2, 3)へ、包絡 1 × 2 で壁を探す({@link TdSiege#scanWall}。
     * 侵攻の状態を見ない形の口なので世界の台帳に触らない)。床は相対 y = 1 の石。
     * <ul>
     *     <li>何も無ければ null。石の壁は下の段から。</li>
     *     <li>チェスト・樽(中身を持つブロックエンティティ = プレイヤーの持ち物)も壊す(ユーザー指定 2026-09-25「TD では持ち物も容赦なく壊す」)。</li>
     *     <li>予備のターゲットブロック(侵攻のものでない)・エンチャント台(中身を持たないブロックエンティティ)は壊す
     *     (レビュー指摘 2026-09-25: 壊さないと、それでターゲットを 1 枚囲うだけで無敵になった)。</li>
     *     <li>岩盤は飛ばして横の列(2 列先まで)の石。断面が全部岩盤なら<b>その奥は探さない</b>(null。レビュー指摘 2026-09-25:
     *     奥の石には誰も届かない)。</li>
     *     <li>水に面した塊は壊す(堀・水浸しの裏打ちで壁を守れないように。レビュー指摘 2026-09-25)。溶岩に面した塊は壊さない。</li>
     *     <li>1 段の段差(上に包絡ぶんの空き)・カーペットは壁と数えない(登れる・踏み越える)。</li>
     *     <li>包絡の幅 3 なら線の横の列の塊も壁、幅 1 なら素通り。</li>
     * </ul>
     * 水・溶岩は置いたその tick のうちに片付ける(流れる前に)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void breachScanPicksFirstBreakableWall(GameTestHelper helper) {
        WallScan scan = new WallScan(helper, new Vec3(0.5D, 2.0D, 3.5D), new BlockPos(6, 2, 3));
        BlockState stone = Blocks.STONE.defaultBlockState();
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        try {
            scan.expect("何も無い線", 1, 2, null);

            scan.put(3, 2, 3, stone);
            scan.put(3, 3, 3, stone);
            scan.expect("石の壁 2 段", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(3, 2, 3, Blocks.CHEST.defaultBlockState());
            scan.put(3, 3, 3, stone);
            scan.expect("チェストの上に石(チェストから壊す)", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(3, 2, 3, Blocks.BARREL.defaultBlockState());
            scan.put(3, 3, 3, stone);
            scan.expect("樽の上に石(樽から壊す)", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(3, 2, 3, ModBlocks.TARGET_BLOCK.get().defaultBlockState());
            scan.put(3, 3, 3, stone);
            scan.expect("予備のターゲットブロック(侵攻のものでない)の上に石", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(3, 2, 3, Blocks.ENCHANTING_TABLE.defaultBlockState());
            scan.put(3, 3, 3, stone);
            scan.expect("エンチャント台(中身を持たないブロックエンティティ)の上に石", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(3, 2, 3, bedrock);
            scan.put(3, 3, 3, bedrock);
            scan.put(3, 2, 5, stone);
            scan.expect("線の上は岩盤、2 列横に石", 1, 2, new BlockPos(3, 2, 5));
            scan.clear();

            for (int z = 1; z <= 5; z++) {
                scan.put(3, 2, z, bedrock);
                scan.put(3, 3, z, bedrock);
            }
            scan.put(4, 2, 3, stone);
            scan.put(4, 3, 3, stone);
            scan.expect("断面が全部岩盤(奥の石には届かない)", 1, 2, null);
            scan.clear();

            scan.put(2, 2, 3, Blocks.WATER.defaultBlockState());
            scan.put(3, 2, 3, stone);
            scan.put(3, 3, 3, stone);
            scan.expect("下の段の石が水に面している", 1, 2, new BlockPos(3, 2, 3));
            scan.clear();

            scan.put(2, 2, 3, Blocks.LAVA.defaultBlockState());
            scan.put(3, 2, 3, stone);
            scan.put(3, 3, 3, stone);
            scan.expect("下の段の石が溶岩に面している", 1, 2, new BlockPos(3, 3, 3));
            scan.clear();

            scan.put(3, 2, 3, stone);
            scan.expect("1 段の段差(上は空き)", 1, 2, null);
            scan.clear();

            scan.put(3, 2, 3, Blocks.WHITE_CARPET.defaultBlockState());
            scan.expect("カーペット", 1, 2, null);
            scan.clear();

            scan.put(3, 2, 2, stone);
            scan.put(3, 3, 2, stone);
            scan.expect("線の横の石(包絡の幅 1)", 1, 2, null);
            scan.expect("線の横の石(包絡の幅 3)", 3, 2, new BlockPos(3, 2, 2));
            scan.clear();

            helper.succeed();
        } finally {
            scan.clear();
        }
    }

    // ------------------------------------------------------------------ ターゲットを削る

    /**
     * 本物の AI で: TD の攻め手の群れ(アルボラ + スポロフィル + フィロセンス、出どころ TD・侵攻の印付き)をターゲットの横に置くと、
     * <ol>
     *     <li>登録の瞬間に攻城(SIEGE、ターゲット)になる(組み直しの口 {@code TdTicker} の SwarmListener)。</li>
     *     <li>侵攻の始まりに耐久は満タン。</li>
     *     <li>前衛のアルボラが塊の輪に入り、囲む Goal → 攻城のフック → {@link TargetBlockEntity#damage} で、最初に削れる量が
     *     アルボラ 1 回ぶん(攻撃力の属性)。スポロフィル・フィロセンスは塊の輪に入らない(前衛だけ)。</li>
     * </ol>
     * 支援・斥候がターゲットの隣で動いていてもサーバーが落ちないことも、ここで見る(役ごとの量は {@link #strikeAmountFollowsRole})。
     */
    @GameTest(template = TEMPLATE, batch = "td_strike", timeoutTicks = 400)
    public static void frontMemberStrikeDamagesTarget(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        Mob arbora = s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 3, eventId, false);
        s.member(ModEntities.SPOROPHYLL.get(), new Vec3(5.5D, 2.0D, 1.5D), id, 1, 3, eventId, false);
        s.member(ModEntities.PHYLLOSENS.get(), new Vec3(5.5D, 2.0D, 5.5D), id, 2, 3, eventId, false);
        // 実体化を通さずに置いた 3 体ぶんを数えておく(0 のままだと次の帳簿で勝ちになって侵攻が終わる)。
        TdTestAccess.setAlive(s.server, 3);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        if (!target.equals(swarm.targetBlock()) || swarm.mode() != Swarm.Mode.SIEGE) {
            s.fail("侵攻の印の付いた群れが攻城にならない: " + swarm.mode() + " / 塊 " + swarm.targetBlock());
            return;
        }
        float max = be.maxHp();
        if (be.hp() < max - EPS) {
            s.fail("侵攻の始まりに耐久が満タンでない: " + be.hp() + " / " + max);
            return;
        }
        float[] before = {frontDamage(arbora, DesignData.td())};
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            float lost = max - be.hp();
            float current = frontDamage(arbora, DesignData.td());
            if (lost > EPS) {
                // 量は<b>削れた tick の</b>攻撃力の属性で見る: 隣のスポロフィルのオーラが味方に力(DAMAGE_BOOST II、+6)を配るので、
                // 置いた直後の値(8)で比べると 14 になって落ちた(2026-09-25 の初回実行)。力も属性の補正なので攻城の一撃に乗るのが仕様。
                // 叩いたのと同じ tick にオーラが初めて掛かることがある(一員の tick の順)ので、その tick の前後どちらかの値と合えばよい
                // (2026-09-25、群れが塊に早く着くようになってから重なった)。
                if (Math.abs(lost - current) > EPS && Math.abs(lost - before[0]) > EPS) {
                    s.fail("最初に削れた量が " + lost + "(アルボラ 1 回ぶん " + before[0] + " か " + current + " のはず。前衛以外も叩いている?)");
                    return;
                }
                s.pass();
                return;
            }
            before[0] = current;
            if (helper.getTick() >= 360L) {
                Swarm now = SwarmManager.get(s.level, id);
                s.fail("前衛のアルボラが 360 tick のうちにターゲットを削らない: 群れ "
                        + (now == null ? "なし" : now.mode() + " / 塊 " + now.targetBlock() + " / 先導役 "
                        + (now.vanguard() == arbora) + " / 輪の位置 " + now.blockEngageSlot(arbora))
                        + " / アルボラ " + helper.relativeVec(arbora.position()) + " / 一時停止 " + TdEvents.isPaused(s.level));
            }
        });
    }

    /**
     * 攻城のフック({@code SwarmSiegeHooks.BlockStrike} = {@link TdSiege#strike})を群れの Goal と同じ形で直に呼び、役ごとの 1 回の量を見る
     * (どれも NoAI。AI の動きに頼らない)。
     * <ul>
     *     <li>前衛(アルボラ・スカトゥリウム)= 攻撃力の属性(持っていなければ支援の値)。</li>
     *     <li>支援(スポロフィル)= {@code siegeDamage.support}、斥候(フィロセンス)= {@code siegeDamage.rangedScout}(仮決め 0 なら叩かない)。
     *     <b>攻撃力の属性を持たない種で {@code getAttributeValue} を呼んで落ちない</b>こと(属性の確かめ)。</li>
     *     <li>空(ヴェロフィル)・群れに入らない種(ハスク)= 0 で叩かない。</li>
     *     <li>増援の一時停止中(近くにプレイヤーがいない)でも、攻め手の一撃は削る(レビュー指摘 2026-09-25: 削りまで止めると、
     *     少し離れて砲台に片付けさせるだけで負けなくなる)。</li>
     *     <li>叩く座標がターゲットでない・群れの塊が今の侵攻のものでない —— どちらも削らない。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_roles", timeoutTicks = 40)
    public static void strikeAmountFollowsRole(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        Mob arbora = s.member(ModEntities.ARBORA.get(), new Vec3(0.5D, 2.0D, 0.5D), id, 0, 4, eventId, true);
        Mob shielder = s.member(ModEntities.OVERGROWN_SHIELDER.get(), new Vec3(5.5D, 2.0D, 0.5D), id, 1, 4, eventId, true);
        Mob sporophyll = s.member(ModEntities.SPOROPHYLL.get(), new Vec3(0.5D, 2.0D, 5.5D), id, 2, 4, eventId, true);
        Mob phyllosens = s.member(ModEntities.PHYLLOSENS.get(), new Vec3(5.5D, 2.0D, 5.5D), id, 3, 4, eventId, true);
        Mob velophyll = s.mob(ModEntities.VELOPHYLL.get(), new Vec3(3.5D, 5.0D, 0.5D), true, null, null);
        Mob husk = s.mob(EntityType.HUSK, new Vec3(0.5D, 2.0D, 3.5D), true, null, null);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null || !target.equals(swarm.targetBlock())) {
            s.fail("TD の攻め手の群れが登録されない・攻城にならない: " + (swarm == null ? "なし" : swarm.targetBlock()));
            return;
        }
        TdTable td = DesignData.td();
        expectStrike(s, be, swarm, target, arbora, frontDamage(arbora, td), "アルボラ(前衛)");
        expectStrike(s, be, swarm, target, shielder, frontDamage(shielder, td), "スカトゥリウム(前衛)");
        expectStrike(s, be, swarm, target, sporophyll, td.siegeDamageSupport(), "スポロフィル(支援)");
        expectStrike(s, be, swarm, target, phyllosens, td.siegeDamageRangedScout(), "フィロセンス(斥候)");
        expectStrike(s, be, swarm, target, velophyll, 0.0F, "ヴェロフィル(空)");
        expectStrike(s, be, swarm, target, husk, 0.0F, "ハスク(群れに入らない)");

        float before = be.hp();
        TdTestAccess.setPausedSince(s.server, s.level.getGameTime());
        boolean pausedHit = TdTestAccess.strike(arbora, swarm, target, false);
        boolean pausedFlag = TdEvents.isPaused(s.level);
        TdTestAccess.setPausedSince(s.server, -1L);
        float pausedLost = before - be.hp();
        if (!pausedFlag) {
            s.fail("一時停止の時刻を入れたのに TdEvents.isPaused が false");
            return;
        }
        if (!pausedHit || Math.abs(pausedLost - frontDamage(arbora, td)) > EPS) {
            s.fail("増援の一時停止中にアルボラの一撃が " + pausedLost + " しか削らない(攻城は一時停止を見ないはず。当てた: "
                    + pausedHit + ")");
            return;
        }
        before = be.hp();
        BlockPos other = target.offset(0, 0, 2);
        if (TdTestAccess.strike(arbora, swarm, other, false) || be.hp() != before) {
            s.fail("ターゲットでない座標を叩いて削れた");
            return;
        }
        swarm.setSiege(other);
        boolean staleHit = TdTestAccess.strike(arbora, swarm, target, false);
        swarm.setSiege(target);
        if (staleHit || be.hp() != before) {
            s.fail("群れの塊が今の侵攻のターゲットでないのに削れた");
            return;
        }
        s.pass();
    }

    // ------------------------------------------------------------------ 負け・撤去

    /**
     * <b>負け</b>: 世界のバイオマス 500 を切り出して侵攻を始め(予算 500・払い戻せる)、仮想の群れ 7 体を足してから、耐久を 0 にする一撃。
     * <ul>
     *     <li>その一撃は true を返し、侵攻は負けで終わる。ターゲットブロックは消え(台帳からも)、「負けの処理中」の印は残らない。</li>
     *     <li>世界へ返るのは予算 + 仮想の群れの値段(アルボラ 7 体 × {@code tdCost}。{@link #arboraCost})ちょうど
     *     (ブロックが消えたときの「撤去 = 中止」で二重に返さない)。</li>
     *     <li>汚染は小さな球に分けて<b>積む</b>: 負けたその tick には 16 ブロック先はまだ石のまま、{@value #BURST_DRAIN_TICKS} tick
     *     のうちに真下も 16 ブロック先も汚染される。</li>
     * </ul>
     * 汚染がテスト場や地面に残らないよう、ターゲットと見張りの石はテスト場の上空(相対 y = {@value #SKY_Y})に浮かべる
     * (球は空気を汚染しないので、汚れるのは見張りの石だけ)。そのチャンクは強制読み込みにする(外すのはバッチの終わりのバニラ)。
     */
    @GameTest(template = TEMPLATE, batch = "td_defeat", timeoutTicks = 200)
    public static void defeatRemovesTargetRefundsAndBursts(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        SkyBase base = SkyBase.build(s);
        TargetBlockEntity be = s.target(base.target);
        if (be == null) {
            return;
        }
        EcosystemData eco = EcosystemData.get(s.server);
        eco.setBiomass(500.0D);
        if (!s.start(base.target, null)) {
            return;
        }
        TdData.Event event = TdData.get(s.server).active();
        if (event == null || !event.refundable() || Math.abs(event.budget() - 500.0D) > BIOMASS_EPS
                || Math.abs(eco.biomass()) > BIOMASS_EPS) {
            s.fail("侵攻の始まりに世界のバイオマス 500 を全部予算へ移していない: " + describe(event) + " / 世界 " + eco.biomass());
            return;
        }
        TdTestAccess.addVirtualSwarm(s.server, Vec3.atCenterOf(base.target).add(150.0D, 0.0D, 0.0D),
                ModEntities.ARBORA.get(), 7, 0.0D);
        double before = eco.biomass();

        boolean destroyed = be.damage(be.hp() + 1.0F, null);
        TdData data = TdData.get(s.server);
        if (!destroyed) {
            s.fail("耐久を 0 にした一撃が true を返さない");
            return;
        }
        if (data.live() != null || data.lastOutcome() != TdData.Outcome.DEFEAT) {
            s.fail("負けにならない: " + describe(data.live()) + " / 結果 " + data.lastOutcome());
            return;
        }
        if (s.level.getBlockState(base.target).getBlock() instanceof TargetBlock || TdTargets.get(s.level, base.target) != null) {
            s.fail("負けたのにターゲットブロックが残っている: " + s.level.getBlockState(base.target));
            return;
        }
        if (TdEvents.isDefeating(s.level, base.target)) {
            s.fail("「負けの処理中」の印が残っている");
            return;
        }
        double refund = eco.biomass() - before;
        double expected = 500.0D + arboraCost(7);
        if (Math.abs(refund - expected) > BIOMASS_EPS) {
            s.fail("負けの払い戻しが " + refund + "(予算 500 + 仮想の群れ アルボラ 7 体 × " + arboraCost(1) + " = " + expected
                    + " のはず。撤去の中止と二重に返していないか)");
            return;
        }
        if (!base.farIsStone()) {
            s.fail(FAR_PROBE + " ブロック先まで負けたその tick に汚染された(小さな球を積まずに一度に作っている)");
            return;
        }
        long start = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long t = helper.getTick() - start;
            boolean near = !base.nearIsStone();
            boolean far = !base.farIsStone();
            if (near && far && t >= BURST_DRAIN_TICKS) {
                s.pass();
                return;
            }
            if (t > 120L) {
                // 手がかり(2026-09-25 に 4 回に 1 回「真下は汚染・16 先は石のまま」で落ちた。原因の切り分け用):
                // 16 先のチャンクが読み込まれているか・胞子除去装置の阻害率・設計値の半径。
                s.fail("負けの汚染が噴き出さない: 真下 " + (near ? "汚染" : "石のまま") + " / " + FAR_PROBE + " 先 "
                        + (far ? "汚染" : "石のまま") + "(読み込み " + s.level.isLoaded(base.farProbe)
                        + " / 阻害率 " + com.yayuy.overgrownvegetation.spore.SporeDeviceData.inhibitionAt(s.level,
                        base.farProbe, com.yayuy.overgrownvegetation.spore.SporeDeviceData.Inhibit.CORRUPTION)
                        + " / 半径 " + DesignData.td().defeatCorruptionRadius() + ")");
            }
        });
    }

    /**
     * <b>進行中のターゲットは掘れず、侵攻中に無くなれば負け</b>(仮決め 2026-09-25、レビュー指摘: 掘れば「中止・払い戻し」で
     * ユーザー指定「ターゲットが壊れたら負け・拠点の周りに汚染」を逃れられ、予告のたびに掘れば侵攻そのものを避けられた)。
     * <ol>
     *     <li>予告中: サバイバルのプレイヤーの掘る速さが 0(アドベンチャー・クリエイティブの扱いはバニラ任せ)。止めると掘れる(&gt; 0)。</li>
     *     <li>予告中にブロックが無くなった → 取りやめ(CANCELLED)。まだ何も切り出していないので世界のバイオマスは変わらない。</li>
     *     <li>侵攻中: 掘る速さ 0。ブロックを別のブロックに替える(爆発・/setblock・クリエイティブと同じ {@link TargetBlock#onRemove} の入口)
     *     → <b>負け</b>(DEFEAT)で、予算 + 仮想の群れの値段(200 + アルボラ 5 体 × {@code tdCost})を世界へ返し、汚染が噴き出す。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = "td_removed", timeoutTicks = 100)
    public static void liveTargetCannotBeMinedAndRemovalIsDefeat(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        SkyBase base = SkyBase.build(s);
        if (s.target(base.target) == null) {
            return;
        }
        Player miner = helper.makeMockPlayer(GameType.SURVIVAL);
        EcosystemData eco = EcosystemData.get(s.server);
        eco.setBiomass(200.0D);

        TdTestAccess.startWarning(s.level, base.target);
        if (s.level.getBlockState(base.target).getDestroyProgress(miner, s.level, base.target) != 0.0F) {
            s.fail("予告中のターゲットをサバイバルのプレイヤーが掘れる");
            return;
        }
        TdTicker.forceStop(s.server);
        if (!(s.level.getBlockState(base.target).getDestroyProgress(miner, s.level, base.target) > 0.0F)) {
            s.fail("予告を止めたのにターゲットが掘れないまま");
            return;
        }
        TdTestAccess.startWarning(s.level, base.target);
        s.level.setBlock(base.target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        TdData data = TdData.get(s.server);
        if (data.live() != null || data.lastOutcome() != TdData.Outcome.CANCELLED) {
            s.fail("予告中にターゲットが無くなったのに取りやめにならない: " + describe(data.live()) + " / 結果 " + data.lastOutcome());
            return;
        }
        if (Math.abs(eco.biomass() - 200.0D) > BIOMASS_EPS) {
            s.fail("予告中の取りやめで世界のバイオマスが変わった: " + eco.biomass());
            return;
        }

        if (s.target(base.target) == null || !s.start(base.target, null)) {
            return;
        }
        TdTestAccess.addVirtualSwarm(s.server, Vec3.atCenterOf(base.target).add(150.0D, 0.0D, 0.0D),
                ModEntities.ARBORA.get(), 5, 0.0D);
        if (s.level.getBlockState(base.target).getDestroyProgress(miner, s.level, base.target) != 0.0F) {
            s.fail("侵攻中のターゲットをサバイバルのプレイヤーが掘れる");
            return;
        }
        double before = eco.biomass();
        s.level.setBlock(base.target, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        data = TdData.get(s.server);
        if (data.live() != null || data.lastOutcome() != TdData.Outcome.DEFEAT) {
            s.fail("侵攻中にターゲットが無くなったのに負けにならない: " + describe(data.live()) + " / 結果 " + data.lastOutcome());
            return;
        }
        double refund = eco.biomass() - before;
        double expected = 200.0D + arboraCost(5);
        if (Math.abs(refund - expected) > BIOMASS_EPS) {
            s.fail("負けの払い戻しが " + refund + "(予算 200 + 仮想の群れ アルボラ 5 体 × " + arboraCost(1) + " = " + expected
                    + " のはず)");
            return;
        }
        if (TdTargets.get(s.level, base.target) != null || TdEvents.isDefeating(s.level, base.target)) {
            s.fail("無くなったターゲットが台帳に残っている・「負けの処理中」の印が残っている");
            return;
        }
        helper.runAfterDelay(BURST_DRAIN_TICKS, () -> {
            if (base.nearIsStone()) {
                s.fail("侵攻中にターゲットが無くなった(負け)のに汚染が噴き出さない");
                return;
            }
            s.pass();
        });
    }

    // ------------------------------------------------------------------ 勝ち

    /**
     * <b>勝ち</b>は「予算がもう 1 群れも買えない(境目 = {@code TdTicker#cheapest} 未満)・仮想の群れなし・生きている攻め手 0」がそろった
     * ときだけ(ユーザー指定「予算が 0 で攻め手が全滅したら勝ち」。1 体の値段が種ごとになる前は境目 1)。段階ごとに帳簿を 2 回待つ:
     * <ol>
     *     <li>予算ちょうど境目(まだ 1 群れ買える)・仮想なし・生きている 0 → 続く。</li>
     *     <li>予算 境目 − 0.5・仮想の群れ 1 つ(遠くで止まっている)・生きている 0 → 続く。</li>
     *     <li>予算 境目 − 0.5・仮想なし・生きている 2 → 続く。</li>
     *     <li>予算 境目 − 0.5・仮想なし・生きている 0 → 次の帳簿で勝ち。予算の端数(境目 − 0.5)を世界へ返す。</li>
     * </ol>
     * 境目がちょうど「群れの先頭になれる種の値段」であること(境目なら買える・境目未満なら買えない)は
     * {@link #budgetAtTheBorderStillBuysASwarm}。
     * 予算が群れに変わらないよう、次に群れを作る時刻を先へ送っておく。払い戻しの量は帳簿の直前の tick との差で見る
     * (収入は 1200 tick ごとの 20 の倍数の tick、帳簿は 20 で割って 7 余る tick なので同じ tick に重ならない)。
     */
    @GameTest(template = TEMPLATE, batch = "td_victory", timeoutTicks = 400)
    public static void victoryOnlyWhenBudgetVirtualAndAliveAreGone(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null) {
            return;
        }
        EcosystemData eco = EcosystemData.get(s.server);
        eco.setBiomass(0.0D);
        if (!s.start(target, null)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        TdTestAccess.setNextSwarmTick(s.server, Long.MAX_VALUE);
        // 世界のバイオマスから切り出した(払い戻す)侵攻のまま、予算だけ境目に。帳簿の前なのでまだ勝ちにならない。
        double border = TdTestAccess.cheapest(s.level);
        double leftover = border - 0.5D;
        TdTicker.setBudget(s.server, border);
        String[] names = {"予算 = 境目 " + border + "・仮想なし・生きている 0", "予算 " + leftover + "・仮想の群れ 1 つ・生きている 0",
                "予算 " + leftover + "・仮想なし・生きている 2", "予算 " + leftover + "・仮想なし・生きている 0"};
        int[] stage = {0};
        double[] last = {eco.biomass()};
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long t = helper.getTick() - begin;
            TdData data = TdData.get(s.server);
            TdData.Event live = data.live();
            double now = eco.biomass();
            if (stage[0] < 3) {
                if (live == null) {
                    s.fail("「" + names[stage[0]] + "」で侵攻が終わった(" + data.lastOutcome() + ")");
                    return;
                }
                if (t >= (long) TWO_BOOKKEEPINGS * (stage[0] + 1)) {
                    stage[0]++;
                    switch (stage[0]) {
                        case 1 -> {
                            TdTicker.setBudget(s.server, leftover);
                            TdTestAccess.addVirtualSwarm(s.server, Vec3.atCenterOf(target).add(150.0D, 0.0D, 0.0D),
                                    ModEntities.ARBORA.get(), 3, 0.0D);
                        }
                        case 2 -> {
                            TdTestAccess.clearVirtual(s.server);
                            TdTestAccess.setAlive(s.server, 2);
                        }
                        default -> TdTestAccess.setAlive(s.server, 0);
                    }
                }
            } else if (live == null) {
                if (data.lastOutcome() != TdData.Outcome.VICTORY) {
                    s.fail("「" + names[3] + "」で勝ち以外の終わり方: " + data.lastOutcome());
                    return;
                }
                double refund = now - last[0];
                if (Math.abs(refund - leftover) > BIOMASS_EPS) {
                    s.fail("勝ったときに予算の端数 " + leftover + " を世界へ返していない: " + refund);
                    return;
                }
                s.pass();
                return;
            } else if (t > TWO_BOOKKEEPINGS * 3L + 60L) {
                s.fail("「" + names[3] + "」なのに勝ちにならない: " + describe(live));
                return;
            }
            last[0] = now;
        });
    }

    /**
     * <b>予算の境目</b>({@code TdTicker#cheapest})はちょうど「群れの先頭になれる種」のいちばん安い値段(2026-09-26。それまでは侵攻に
     * 出ないペディキュラなども含めた「そのフェーズに湧く全部の種」のいちばん安い値段 = 1 を見ていたので、アルボラ(10)を買えない
     * 端数 1〜9 が残ると、攻め手を全滅させても勝ちにならず侵攻が終わらなかった)。
     * <ol>
     *     <li>予算 = 境目 − 0.5 → 何度振っても 1 群れも買えない(= 勝ちの条件に入ってよい)。</li>
     *     <li>予算 = 境目 → 振り直すうちに必ず 1 群れ買える(編成は振るたびに変わる。空の群れのヴェロフィルは高くて買えない)。
     *     使った額は境目以下で、残りは境目未満。</li>
     * </ol>
     * 群れは {@link TdTestAccess#buyVirtualSwarm} で直に買わせる(テスト場から 150 以上離れた輪 = 実体にならない)。帳簿を待たずに
     * 同じ tick で終える(予算 0 で始めた侵攻は次の帳簿で勝ちになる)。
     */
    @GameTest(template = TEMPLATE, batch = "td_border", timeoutTicks = 40)
    public static void budgetAtTheBorderStillBuysASwarm(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTestAccess.setNextSwarmTick(s.server, Long.MAX_VALUE);
        double border = TdTestAccess.cheapest(s.level);
        TdTicker.setBudget(s.server, border - 0.5D);
        for (int i = 0; i < 64; i++) {
            if (TdTestAccess.buyVirtualSwarm(s.level)) {
                s.fail("予算が境目 " + border + " 未満(" + (border - 0.5D) + ")なのに群れを買えた: "
                        + describe(TdData.get(s.server).active()));
                return;
            }
        }
        TdTicker.setBudget(s.server, border);
        boolean bought = false;
        for (int i = 0; i < 64 && !bought; i++) {
            bought = TdTestAccess.buyVirtualSwarm(s.level);
        }
        TdData.Event e = TdData.get(s.server).active();
        if (!bought || e == null) {
            s.fail("予算がちょうど境目 " + border + " なのに 64 回振っても 1 群れも買えない(端数が残ったまま侵攻が終わらない): "
                    + describe(e));
            return;
        }
        double spent = border - e.budget();
        if (e.virtualSize() <= 0 || spent <= 0.0D || spent > border + BIOMASS_EPS || e.budget() >= border) {
            s.fail("買った群れの額がおかしい: 使った " + spent + " / 残りの予算 " + e.budget() + "(境目 " + border + ")/ "
                    + describe(e));
            return;
        }
        s.pass();
    }

    /**
     * <b>残りのバイオマス</b>(ボスバーの「残り N バイオマス」。ユーザー指定 2026-09-26「残り何体ではなく、あと何バイオマス分出てくるか」)
     * = まだ買える予算 + 行進中の群れの値段 + 生きている攻め手の値段({@link TdTicker#remainingBiomass})。
     * 予算 1000 で始めて 500 に下げ(ボスバーの長さの分母は 1000 のまま)、群れは作らせずに、ターゲットのそばへアルボラ 3 体の仮想の
     * 群れを足す(値段は mobs.json の {@code tdCost} から引く):
     * <ol>
     *     <li>足した直後: 残り = 500 + 3 体の値段、まだ出てきていない分も同じ、戦っている分 0。</li>
     *     <li>実体になった(帳簿): 残りは変わらない(置けなかった分は予算へ戻るので、何体置けても同じ)。戦っている分 = 実体の数 × 値段。</li>
     *     <li>1 体消す: 残りが 1 体の値段だけ減る。帳簿でボスバーの名前がその数(3 桁区切り)に、長さが残り ÷ 1000 になる。</li>
     *     <li>予算を境目 − 0.5(もう 1 群れも買えない端数)にする: 残り = 戦っている分だけ(端数は数えない)。まだ出てきていない分 0。</li>
     *     <li>残りを全部消す: 残り 0 → 次の帳簿で勝ち(端数が残っていても終わる)。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = "td_biomass", timeoutTicks = 400)
    public static void remainingBiomassFollowsCostsNotHeads(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 1000.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        TdTestAccess.setNextSwarmTick(s.server, Long.MAX_VALUE);
        double budget = 500.0D;
        TdTicker.setBudget(s.server, budget);
        String id = s.eventId();
        double cost = arboraCost(1);
        double border = TdTestAccess.cheapest(s.level);
        TdTestAccess.addVirtualSwarm(s.server, helper.absoluteVec(new Vec3(6.5D, 2.0D, 6.5D)), ModEntities.ARBORA.get(),
                3, 0.0D);
        if (!expectLeft(s, "仮想の群れを足した直後", budget + 3 * cost, budget + 3 * cost, 0.0D)) {
            return;
        }
        int[] stage = {0};
        double[] total = {budget + 3 * cost};
        double[] coming = {0.0D};
        long[] since = {helper.getTick()};
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long t = helper.getTick() - since[0];
            TdData data = TdData.get(s.server);
            TdData.Event e = data.active();
            if (e == null) {
                if (stage[0] < 3) {
                    s.fail("段階 " + stage[0] + " で侵攻が終わった(" + data.lastOutcome() + ")");
                } else if (data.lastOutcome() != TdData.Outcome.VICTORY) {
                    s.fail("全部消したのに勝ち以外の終わり方: " + data.lastOutcome());
                } else {
                    s.pass();
                }
                return;
            }
            switch (stage[0]) {
                case 0 -> {
                    if (!e.virtualSwarms().isEmpty()) {
                        if (t > TWO_BOOKKEEPINGS * 2L) {
                            s.fail("そばの仮想の群れが実体にならない: " + describe(e));
                        }
                        return;
                    }
                    int placed = e.alive();
                    coming[0] = budget + (3 - placed) * cost;
                    if (placed <= 0 || !expectLeft(s, "実体になった(" + placed + " 体)", total[0], coming[0], placed * cost)) {
                        if (placed <= 0) {
                            s.fail("実体になったのに生きている攻め手が 0: " + describe(e));
                        }
                        return;
                    }
                    List<Mob> attackers = tdArbora(s, id);
                    if (attackers.isEmpty()) {
                        s.fail("実体になったアルボラが見つからない: " + describe(e));
                        return;
                    }
                    attackers.get(0).discard();
                    total[0] -= cost;
                    stage[0] = 1;
                    since[0] = helper.getTick();
                }
                case 1 -> {
                    if (!expectLeft(s, "1 体消した", total[0], coming[0], e.alive() * cost)) {
                        return;
                    }
                    Object[] bar = TdTestAccess.bossBar(s.server);
                    String shown = bossArg((Component) bar[0]);
                    if (!TdTicker.biomassText(total[0]).equals(shown)) {
                        if (t > TWO_BOOKKEEPINGS) {
                            s.fail("ボスバーの名前の数が " + shown + "(" + TdTicker.biomassText(total[0]) + " のはず): " + bar[0]);
                        }
                        return;
                    }
                    float progress = (Float) bar[1];
                    float want = Mth.clamp((float) (total[0] / e.initialTotal()), 0.0F, 1.0F);
                    if (Math.abs(progress - want) > 1.0E-4F || Math.abs(e.initialTotal() - 1000.0D) > BIOMASS_EPS) {
                        s.fail("ボスバーの長さが " + progress + "(残り " + total[0] + " ÷ 開始時 " + e.initialTotal() + " = " + want
                                + " のはず。開始時は 1000 のはず)");
                        return;
                    }
                    TdTicker.setBudget(s.server, border - 0.5D);
                    stage[0] = 2;
                    since[0] = helper.getTick();
                }
                case 2 -> {
                    double fighting = e.alive() * cost;
                    if (!expectLeft(s, "予算を境目未満の端数(" + (border - 0.5D) + ")にした", fighting, 0.0D, fighting)) {
                        return;
                    }
                    List<Mob> attackers = tdArbora(s, id);
                    if (attackers.size() != e.alive()) {
                        s.fail("見つかった攻め手 " + attackers.size() + " 体と生きている数が合わない: " + describe(e));
                        return;
                    }
                    for (Mob mob : attackers) {
                        mob.discard();
                    }
                    stage[0] = 3;
                    since[0] = helper.getTick();
                }
                default -> {
                    if (e.alive() == 0 && !expectLeft(s, "全部消した", 0.0D, 0.0D, 0.0D)) {
                        return;
                    }
                    if (t > TWO_BOOKKEEPINGS) {
                        s.fail("攻め手を全部消して端数だけ残った侵攻が勝ちにならない: " + describe(e));
                    }
                }
            }
        });
    }

    /** 残りのバイオマス・まだ出てきていない分・戦っている分({@link TdTestAccess#biomassLeft})を確かめる。違えば失敗にして false。 */
    private static boolean expectLeft(TdStage s, String when, double remaining, double coming, double fighting) {
        double[] left = TdTestAccess.biomassLeft(s.level);
        if (Math.abs(left[0] - remaining) > BIOMASS_EPS || Math.abs(left[1] - coming) > BIOMASS_EPS
                || Math.abs(left[2] - fighting) > BIOMASS_EPS) {
            s.fail(when + ": 残り " + left[0] + "・まだ出てきていない " + left[1] + "・戦っている " + left[2]
                    + "(" + remaining + "・" + coming + "・" + fighting + " のはず)");
            return false;
        }
        return true;
    }

    /** この侵攻の印を持つ、生きているアルボラ(片付けの一覧にも足す)。 */
    private static List<Mob> tdArbora(TdStage s, String eventId) {
        List<Mob> found = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(), m -> m.isAlive()
                && m.getType() == ModEntities.ARBORA.get() && EventMobs.has(m, EventMobs.Kind.TD, eventId));
        s.spawned.addAll(found);
        return found;
    }

    /** ボスバーの名前(翻訳キー + 引数)の 1 つ目の引数。侵攻のボスバーの名前でなければ null。 */
    @Nullable
    private static String bossArg(Component name) {
        if (name.getContents() instanceof TranslatableContents contents
                && "event.overgrown_vegetation.td.boss".equals(contents.getKey()) && contents.getArgs().length > 0) {
            return String.valueOf(contents.getArgs()[0]);
        }
        return null;
    }

    // ------------------------------------------------------------------ 生きている攻め手の数

    /**
     * 生きている攻め手の数(保存する。読み込まれていない攻め手も数に入る)は、侵攻の印を持つ個体が<b>倒された・消された</b>ときだけ減る。
     * 始めに 10 と数え、印付きの NoAI のハスク 5 体と印の無いハスク 1 体を置く。
     * <ol>
     *     <li>チャンクごと読み込み外れた({@code UNLOADED_TO_CHUNK} で外す)→ 10 のまま。</li>
     *     <li>ディメンションを移った({@code CHANGED_DIMENSION})→ 10 のまま。</li>
     *     <li>区画が見えなくなった(理由なしの Leave を {@link TdTicker#onLeave} へ直に渡す)→ 10 のまま。読み込み直し(Join)でも増えない。</li>
     *     <li>印の無い個体が消えた → 10 のまま。</li>
     *     <li>読み込み直した攻め手・もう 1 体を消した({@code DISCARDED})→ 9、8。倒した数は 0 のまま。</li>
     *     <li>倒した(本物の死 → 20 tick 後に {@code KILLED})→ 7、倒した数 1。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = "td_alive", timeoutTicks = 120)
    public static void aliveCountIgnoresUnloadAndDimensionChange(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        TdTestAccess.setAlive(s.server, 10);
        String id = s.eventId();
        Husk unloaded = s.husk(0.5D, 0.5D, id);
        Husk moved = s.husk(2.5D, 0.5D, id);
        Husk hidden = s.husk(4.5D, 0.5D, id);
        Husk discarded = s.husk(6.5D, 0.5D, id);
        Husk killed = s.husk(0.5D, 6.5D, id);
        Husk stranger = s.husk(6.5D, 6.5D, null);
        expectLoaded(s, 5, "印の付いた 5 体を置いた");

        unloaded.remove(Entity.RemovalReason.UNLOADED_TO_CHUNK);
        expectAlive(s, 10, 0, "チャンクごと読み込み外れた(UNLOADED_TO_CHUNK)");
        moved.remove(Entity.RemovalReason.CHANGED_DIMENSION);
        expectAlive(s, 10, 0, "ディメンションを移った(CHANGED_DIMENSION)");
        TdTicker.onLeave(new EntityLeaveLevelEvent(hidden, s.level));
        expectAlive(s, 10, 0, "区画が見えなくなった(理由なしの Leave)");
        expectLoaded(s, 2, "読み込み外れ・移動・見えなくなった 3 体を表から外した");
        TdTicker.onJoin(new EntityJoinLevelEvent(hidden, s.level, true));
        expectAlive(s, 10, 0, "見えなくなった攻め手が読み込み直された(Join)");
        expectLoaded(s, 3, "読み込み直した攻め手を表へ戻した");
        stranger.discard();
        expectAlive(s, 10, 0, "印の無い個体が消えた");
        hidden.discard();
        expectAlive(s, 9, 0, "読み込み直した攻め手を消した(DISCARDED)");
        discarded.discard();
        expectAlive(s, 8, 0, "攻め手を消した(DISCARDED)");
        killed.kill();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (killed.isRemoved()) {
                expectAlive(s, 7, 1, "攻め手を倒した(20 tick 後に KILLED)");
                expectLoaded(s, 0, "全部いなくなった");
                s.pass();
                return;
            }
            if (helper.getTick() >= 80L) {
                s.fail("倒したハスクが 80 tick たっても消えない(死んでいる: " + killed.isDeadOrDying() + ")");
            }
        });
    }

    // ------------------------------------------------------------------ 仮想の群れの実体化と上限

    /**
     * 仮想の群れ(アルボラ 2 体、ターゲットから 6 ブロック = 実体化の距離の内側、動かない)は、同時の上限を超えるなら輪の上で待ち、
     * 内側に入った次の帳簿で実体になる。上限は<b>読み込まれている攻め手</b>で数える(レビュー指摘 2026-09-25: 保存している
     * 生きている数で数えていたので、読み込まれていない・置き去りの攻め手が上限を埋めて侵攻が止まった)。
     * 本物の数を揃えるため、このテストの間だけ上限を 3 にする(ほかの値は Java の既定値)。
     * <ol>
     *     <li>侵攻の印付きの NoAI のハスク 2 体(読み込まれている攻め手 2)+ 群れ 2 &gt; 3 → 帳簿を 2 回待っても仮想のまま。
     *     このとき生きている数を大きくしても(読み込まれていない攻め手の数えのつもり)上限には効かない。</li>
     *     <li>ハスクを 1 体消す(読み込まれている 1)→ 1 + 2 = 3 で実体になる。生きている数・読み込まれている攻め手はちょうど 3、
     *     実体になったアルボラ 2 体は侵攻の印を持ち、群れはこの侵攻のターゲットを攻める(攻城)。</li>
     * </ol>
     * 湧く場所は {@code SwarmSpawner} 任せ(テスト場の天井のバリアの上に立つことがある)。終わるとき、侵攻の印の付いた個体を消す。
     */
    @GameTest(template = TEMPLATE, batch = "td_cap", timeoutTicks = 300)
    public static void virtualSwarmMaterializesOnlyUnderTheCap(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        int cap = 3;
        TdTable.Json json = new TdTable.Json();
        json.concurrentCap = cap;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(1, 2, 1));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String id = s.eventId();
        int size = 2;
        Husk first = s.husk(0.5D, 5.5D, id);
        s.husk(2.5D, 5.5D, id);
        // 読み込まれていない攻め手が 50 体いるつもりの数え(上限には効かない)。ハスク 2 体を数えたうえで。
        TdTestAccess.setAlive(s.server, 52);
        TdTestAccess.addVirtualSwarm(s.server, helper.absoluteVec(new Vec3(5.5D, 2.0D, 5.5D)), ModEntities.ARBORA.get(),
                size, 0.0D);
        boolean[] opened = {false};
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long t = helper.getTick() - begin;
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (!opened[0]) {
                if (e.virtualSize() != size || TdTicker.loadedAttackers(s.level) != 2) {
                    s.fail("上限(" + cap + ")を超えるのに実体化した: " + describe(e) + " / 読み込まれた攻め手 "
                            + TdTicker.loadedAttackers(s.level));
                    return;
                }
                if (t >= TWO_BOOKKEEPINGS) {
                    first.discard();
                    TdTestAccess.setAlive(s.server, 1);
                    opened[0] = true;
                }
                return;
            }
            if (e.virtualSwarms().isEmpty()) {
                List<Mob> attackers = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(),
                        m -> m.getType() == ModEntities.ARBORA.get() && EventMobs.has(m, EventMobs.Kind.TD, id));
                s.spawned.addAll(attackers);
                if (e.alive() != cap) {
                    s.fail("実体化したあとの生きている数が " + e.alive() + "(上限ちょうど " + cap + " のはず): " + describe(e));
                    return;
                }
                if (attackers.size() != size || TdTicker.loadedAttackers(s.level) != cap) {
                    s.fail("実体になったアルボラが " + attackers.size() + " 体(" + size + " 体のはず)・読み込まれた表に "
                            + TdTicker.loadedAttackers(s.level) + " 体(ハスク 1 + アルボラ " + size + " = " + cap + " のはず)");
                    return;
                }
                for (Mob m : attackers) {
                    Swarm swarm = SwarmManager.get(m);
                    if (swarm == null || !target.equals(swarm.targetBlock())) {
                        s.fail("実体になった群れがこの侵攻のターゲットを攻めていない: "
                                + (swarm == null ? "群れなし" : swarm.mode() + " / 塊 " + swarm.targetBlock()));
                        return;
                    }
                }
                s.pass();
                return;
            }
            if (t > TWO_BOOKKEEPINGS + 200L) {
                s.fail("上限の内側に入ったのに実体化しない: " + describe(e));
            }
        });
    }

    // ------------------------------------------------------------------ 届かない所のターゲット(レビュー指摘 2026-09-25)

    /**
     * <b>壁のすぐ裏のターゲット</b>(本物の AI): 石の壁(x = 4、テスト場の幅いっぱい・天井まで)のすぐ裏(x = 5)にターゲットを置き、
     * 壁の前にアルボラ 2 体(TD の攻め手の群れ)。先導役は壁の外で塊の輪の距離に入る(= 「着いた」)ので、はじめは先導の Goal が止まって
     * 壁選びが二度と呼ばれず、叩けないまま止まっていた。着いたのに叩けない見張り({@code TdSiege#watchSiege})が壁を選び、
     * 穴を開けてターゲットを削るまで待つ。
     */
    @GameTest(template = TEMPLATE, batch = "td_wall", timeoutTicks = 1200)
    public static void targetBehindWallIsBreached(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                s.put(s.abs(new BlockPos(4, y, z)), stone);
            }
        }
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.member(ModEntities.ARBORA.get(), new Vec3(2.5D, 2.0D, 3.5D), id, 0, 2, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 1, 2, eventId, false);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (be.hp() < max - EPS) {
                // アルボラだけの群れでも 3 × 3 の穴(ユーザー指定 2026-09-25「G5 なども通れる大きな穴」。TdSiege.SWARM_HOLE_CELLS)。
                // 先導役(2.5, 3.5)からターゲット(5, 2, 3)への線は x の向き、線の列 z = 3 と両側、足の段 y = 2 から 3 段。
                StringBuilder left = new StringBuilder();
                for (int z = 2; z <= 4; z++) {
                    for (int y = 2; y <= 4; y++) {
                        if (s.level.getBlockState(s.abs(new BlockPos(4, y, z))).is(Blocks.STONE)) {
                            left.append(" (4, ").append(y).append(", ").append(z).append(')');
                        }
                    }
                }
                if (!left.isEmpty()) {
                    s.fail("アルボラだけの群れの穴が 3 × 3 に満たない(残った石:" + left + ")");
                    return;
                }
                s.pass();
                return;
            }
            if (helper.getTick() >= 1100L) {
                Swarm now = SwarmManager.get(s.level, id);
                int holes = 0;
                for (int z = 0; z <= 6; z++) {
                    for (int y = 2; y <= 7; y++) {
                        if (!s.level.getBlockState(s.abs(new BlockPos(4, y, z))).is(Blocks.STONE)) {
                            holes++;
                        }
                    }
                }
                s.fail("壁のすぐ裏のターゲットを 1100 tick のうちに削らない: 群れ "
                        + (now == null ? "なし" : now.mode() + " / 壊す壁 " + now.breachPos() + " / 先導役 "
                        + (now.vanguard() == null ? "なし" : helper.relativeVec(now.vanguard().position())))
                        + " / 壁に開いた穴 " + holes);
            }
        });
    }

    /**
     * <b>壁を叩く量と、群れの外の攻め手の穴の大きさ</b>(ユーザー指定 2026-09-25「TD 時のみ、すべての mob がブロックを破壊できる。
     * アルボラ以外はアルボラより 5 倍の時間。体格の大きな mob は自身が通れるサイズの大きな穴」)。
     * <ul>
     *     <li>アルボラは攻撃力、ファルキムネスはアルボラの攻撃力の基礎値 ÷ 5、ヴェロフィルは 0。</li>
     *     <li>土の壁(x = 4)の手前のファルキムネス(幅 2.97・高さ 3.06)がターゲットへ崩す穴は、3 列 × 4 段の 12 マス。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_dig_rule", timeoutTicks = 40)
    public static void wallDamageAndOwnSizeHole(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                s.put(s.abs(new BlockPos(4, y, z)), dirt);
            }
        }
        BlockPos target = s.abs(new BlockPos(6, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        String eventId = s.eventId();
        Mob arbora = s.mob(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 0.5D), true, null, eventId);
        Mob falcimnes = s.mob(ModEntities.FALCIMNES.get(), new Vec3(2.0D, 2.0D, 3.5D), true, null, eventId);
        // 体格(属性 SCALE の基礎値 1.8)は、最初の tick(refreshDirtyAttributes)までは当たり判定にまだ乗っていない。
        falcimnes.refreshDimensions();
        Mob velophyll = s.mob(ModEntities.VELOPHYLL.get(), new Vec3(0.5D, 5.0D, 6.0D), true, null, eventId);
        float arboraHit = TdTestAccess.wallDamage(arbora);
        float falcHit = TdTestAccess.wallDamage(falcimnes);
        float expectFalc = (float) (TdTestAccess.arboraBaseAttack() / DesignData.td().otherBreakTimeMultiplier());
        if (Math.abs(arboraHit - (float) arbora.getAttributeValue(Attributes.ATTACK_DAMAGE)) > EPS
                || Math.abs(falcHit - expectFalc) > EPS || TdTestAccess.wallDamage(velophyll) != 0.0F) {
            s.fail("壁を叩く量: アルボラ " + arboraHit + "(攻撃力 " + arbora.getAttributeValue(Attributes.ATTACK_DAMAGE)
                    + " のはず)・ファルキムネス " + falcHit + "(" + expectFalc + " のはず)・ヴェロフィル " + TdTestAccess.wallDamage(velophyll));
            return;
        }
        java.util.Set<BlockPos> want = new java.util.HashSet<>();
        for (int z = 2; z <= 4; z++) {
            for (int y = 2; y <= 5; y++) {
                want.add(s.abs(new BlockPos(4, y, z)));
            }
        }
        java.util.Set<BlockPos> got = new java.util.HashSet<>(TdTestAccess.digHole(falcimnes, target));
        if (!got.equals(want)) {
            s.fail("ファルキムネスの穴が 3 列 × 4 段でない: " + got.stream().map(helper::relativePos).toList());
            return;
        }
        s.pass();
    }

    /**
     * <b>侵攻の間、汚染されたブロックは壊しやすい</b>(ユーザー指定 2026-09-26「TD 時のみ汚染されたブロックは mob が破壊しやすくなるように」)。
     * 硬さの同じ組 —— オークの板材と汚染された板材、白いコンクリートと苔で覆った白いコンクリート —— で、汚染された側だけ壊すのに要る量が
     * {@code corruptedBreakTimeMultiplier} 倍になり、群れの外の攻め手(ファルキムネス)が叩いて壊すまでの回数もその量どおりに減る。
     * 期待値は設計値(td.json)から引く。覆った記録は run/world に残るので、成功・失敗どちらでも捨てる。
     */
    @GameTest(template = TEMPLATE, batch = "td_rot", timeoutTicks = 40)
    public static void corruptedBlocksBreakFasterDuringInvasion(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos oak = s.abs(new BlockPos(3, 2, 1));
        BlockPos rotten = s.abs(new BlockPos(3, 2, 2));
        BlockPos concrete = s.abs(new BlockPos(3, 2, 4));
        BlockPos covered = s.abs(new BlockPos(3, 2, 5));
        s.put(oak, Blocks.OAK_PLANKS.defaultBlockState());
        s.put(rotten, ModBlocks.OVERGROWN_PLANKS.get().defaultBlockState());
        s.put(concrete, Blocks.WHITE_CONCRETE.defaultBlockState());
        s.put(covered, Blocks.WHITE_CONCRETE.defaultBlockState());
        OvergrownLayerData.cover(s.level, covered);
        try {
            BlockPos target = s.abs(new BlockPos(6, 2, 3));
            if (s.target(target) == null || !s.start(target, 0.0D)) {
                return;
            }
            Mob falcimnes = s.mob(ModEntities.FALCIMNES.get(), new Vec3(1.5D, 2.0D, 3.5D), true, null, s.eventId());
            float hit = TdTestAccess.wallDamage(falcimnes);
            if (!(hit > 0.0F)) {
                s.fail("ファルキムネスが壁を削らない: " + hit);
                return;
            }
            TdTable table = DesignData.td();
            List<BlockPos> walls = List.of(oak, rotten, concrete, covered);
            List<String> names = List.of("オークの板材", "汚染された板材", "白いコンクリート", "苔で覆った白いコンクリート");
            StringBuilder bad = new StringBuilder();
            for (int i = 0; i < walls.size(); i++) {
                BlockPos pos = walls.get(i);
                boolean rot = pos.equals(rotten) || pos.equals(covered);
                if (TdTestAccess.corrupted(s.level, pos) != rot) {
                    bad.append(names.get(i)).append(rot ? " を汚染と数えない" : " を汚染と数える").append(" / ");
                    continue;
                }
                double hardness = Math.max(1.0D, s.level.getBlockState(pos).getDestroySpeed(s.level, pos));
                double want = Math.max(1.0D, hardness * table.breakHardnessFactor()
                        * (rot ? table.corruptedBreakTimeMultiplier() : 1.0D));
                double got = TdTestAccess.requiredToBreak(s.level, pos);
                if (Math.abs(got - want) > 1.0e-6D) {
                    bad.append(names.get(i)).append(" の要る量 ").append(got).append("(").append(want).append(" のはず) / ");
                    continue;
                }
                int expect = 0;
                for (double done = 0.0D; done < want; done += hit) {
                    expect++;
                }
                int strikes = 0;
                String last = "";
                while (strikes < 1000 && !"BROKEN".equals(last) && !"INVALID".equals(last)) {
                    last = TdTestAccess.strikeWall(falcimnes, pos);
                    strikes++;
                }
                if (!"BROKEN".equals(last) || strikes != expect) {
                    bad.append(names.get(i)).append(" を ").append(strikes).append(" 回叩いて ").append(last)
                            .append("(").append(expect).append(" 回で壊れるはず) / ");
                }
            }
            if (bad.length() > 0) {
                s.fail("汚染されたブロックの壊しやすさ(1 回 " + hit + "、係数 " + table.corruptedBreakTimeMultiplier() + "): " + bad);
                return;
            }
            s.pass();
        } finally {
            forgetCover(s.level, covered);
        }
    }

    /**
     * <b>侵攻の間は苗木が巨大樹にならない</b>(ユーザー指定 2026-09-26「TD 時のみ苗木が巨大樹にならないように」)。
     * <ul>
     *     <li>侵攻の前は止めない({@link GiantTreeFeature#pausedByTd} が false)。</li>
     *     <li>巨大樹の苗木(必ず巨大樹になる)は、侵攻の間は育たずに苗木のまま待つ。</li>
     *     <li>汚染された苗木の木の抽選({@code overgrown_tree.json})で巨大樹の目(0.25%)を引かせても、何も生えずに失敗する
     *     (苗木ならバニラの {@code TreeGrower#growTree} が苗木を戻し、次の抽選でふつうの木になる)。苗木でなく抽選を直に置くのは、苗木は
     *     場所の環境({@code OvergrowthRegionMap})で湿地・菌湿地帯の木に変わり、テスト場によっては巨大樹の抽選に届かないため。
     *     巨大樹の間隔・屋根の判定は苗木の強制成長と同じく飛ばす(テスト場はバリアの天井の下なので、飛ばさないと TD と関係なく止まる)。</li>
     * </ul>
     * どちらも巨大樹の設置待ちを増やさない。誤って積まれたら、1 ブロックも置かれないうちに捨てる
     * ({@code GiantTreeGrowthQueue#discardJobsForTests}。テストのワールドに高さ 120 の木を生やさない)。
     */
    @GameTest(template = TEMPLATE, batch = "td_giant", timeoutTicks = 40)
    public static void saplingsDoNotBecomeGiantTreesDuringInvasion(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        if (GiantTreeFeature.pausedByTd(s.level)) {
            s.fail("侵攻の前から巨大樹を止めている");
            return;
        }
        BlockPos sapling = s.abs(new BlockPos(1, 3, 1));
        BlockPos roll = s.abs(new BlockPos(1, 3, 5));
        s.put(sapling.below(), Blocks.DIRT.defaultBlockState());
        BlockState giantSapling = ModBlocks.GIANT_OVERGROWN_SAPLING.get().defaultBlockState().setValue(SaplingBlock.STAGE, 1);
        s.put(sapling, giantSapling);
        BlockPos target = s.abs(new BlockPos(6, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        if (!GiantTreeFeature.pausedByTd(s.level)) {
            s.fail("侵攻中なのに巨大樹を止めていない");
            return;
        }
        int queued = GiantTreeGrowthQueue.pendingJobs(s.level);
        ((SaplingBlock) giantSapling.getBlock()).advanceTree(s.level, sapling, giantSapling, s.level.random);
        ConfiguredFeature<?, ?> treeRoll = s.level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getOrThrow(ModBlocks.OVERGROWN_TREE_FEATURE);
        boolean grew;
        GiantTreeFeature.beginSaplingGrowth();
        try {
            grew = treeRoll.place(s.level, s.level.getChunkSource().getGenerator(), zeroFloats(1), roll);
        } finally {
            GiantTreeFeature.endSaplingGrowth();
        }
        int added = GiantTreeGrowthQueue.pendingJobs(s.level) - queued;
        if (added > 0) {
            GiantTreeGrowthQueue.discardJobsForTests(s.level);
        }
        BlockState saplingNow = s.level.getBlockState(sapling);
        BlockState rollNow = s.level.getBlockState(roll);
        if (added > 0 || grew || !saplingNow.is(ModBlocks.GIANT_OVERGROWN_SAPLING.get()) || !rollNow.isAir()) {
            s.fail("侵攻中に巨大樹が育つ: 積まれた巨大樹 " + added + " / 木の抽選 " + (grew ? "生えた" : "失敗")
                    + " / 巨大樹の苗木の場所 " + saplingNow + " / 抽選の場所 " + rollNow);
            return;
        }
        s.pass();
    }

    /** 始めの {@code zeros} 回の {@code nextFloat} だけ 0 を返す乱数(木の抽選で先頭の目 = 巨大樹を必ず引かせる)。ほかは本物(種 0)。 */
    private static RandomSource zeroFloats(int zeros) {
        RandomSource real = RandomSource.create(0L);
        return new RandomSource() {
            private int left = zeros;

            @Override
            public RandomSource fork() {
                return real.fork();
            }

            @Override
            public PositionalRandomFactory forkPositional() {
                return real.forkPositional();
            }

            @Override
            public void setSeed(long seed) {
                real.setSeed(seed);
            }

            @Override
            public int nextInt() {
                return real.nextInt();
            }

            @Override
            public int nextInt(int bound) {
                return real.nextInt(bound);
            }

            @Override
            public long nextLong() {
                return real.nextLong();
            }

            @Override
            public boolean nextBoolean() {
                return real.nextBoolean();
            }

            @Override
            public float nextFloat() {
                if (this.left > 0) {
                    this.left--;
                    return 0.0F;
                }
                return real.nextFloat();
            }

            @Override
            public double nextDouble() {
                return real.nextDouble();
            }

            @Override
            public double nextGaussian() {
                return real.nextGaussian();
            }
        };
    }

    /** 覆った記録を捨てる(記録は run/world に残り続ける。空気にしてから読むと捨てられる。メモリ「覆ったマスは #corrupted を持たない」)。 */
    private static void forgetCover(ServerLevel level, BlockPos pos) {
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
        OvergrownLayerData.isCovered(level, pos);
    }

    /**
     * <b>護衛も壁を崩す</b>(ユーザー指定 2026-09-25「TD 時のみ、すべての mob がブロックを破壊できる」)。土の壁(x = 4、全面)の向こうの
     * ターゲットへ、ファルキムネス(護衛。群れに入らない)が引き直しで進めなくなったら、自分の体の穴で壁を崩し始める。
     */
    @GameTest(template = TEMPLATE, batch = "td_dig", timeoutTicks = 600)
    public static void escortDigsThroughWall(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState dirt = Blocks.DIRT.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                s.put(s.abs(new BlockPos(4, y, z)), dirt);
            }
        }
        BlockPos target = s.abs(new BlockPos(6, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        Mob escort = s.mob(ModEntities.FALCIMNES.get(), new Vec3(2.0D, 2.0D, 3.5D), false, null, s.eventId());
        escort.refreshDimensions(); // 体格を当たり判定に乗せる(最初の tick を待たずに。置いた位置で壁に食い込まないか確かめやすいように)
        TdTestAccess.setAlive(s.server, 1);
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            for (int z = 0; z <= 6; z++) {
                for (int y = 2; y <= 7; y++) {
                    if (!s.level.getBlockState(s.abs(new BlockPos(4, y, z))).is(Blocks.DIRT)) {
                        s.pass();
                        return;
                    }
                }
            }
            if (helper.getTick() >= 550L) {
                StringBuilder goals = new StringBuilder();
                escort.goalSelector.getAvailableGoals().forEach(g -> {
                    if (g.isRunning()) {
                        goals.append(' ').append(g.getGoal().getClass().getSimpleName());
                    }
                    if (g.getGoal() instanceof com.yayuy.overgrownvegetation.td.TdAssaultGoal assault) {
                        goals.append(" [").append(assault.debugState()).append(']');
                    }
                });
                s.fail("護衛が 550 tick のうちに壁を 1 マスも崩さない: 位置 " + helper.relativeVec(escort.position())
                        + " / 標的 " + escort.getTarget() + " / 経路 " + escort.getNavigation().getPath() + " / Goal" + goals
                        + " / 幅 " + escort.getBbWidth());
            }
        });
    }

    /**
     * <b>アルボラは有刺鉄線を切る</b>(ユーザー指定 2026-09-25「アルボラが他の mob が通れるよう有刺鉄線を破壊するように」)。テスト場を
     * 横切る鉄線(x = 3)の向こうのターゲットへ向かうアルボラ(群れの先導役)が、踏み込んだ鉄線を切る(鉄線は当たりが無いので経路は通る)。
     */
    @GameTest(template = TEMPLATE, batch = "td_wire", timeoutTicks = 400)
    public static void arboraCutsBarbedWire(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState wire = com.yayuy.overgrownvegetation.registry.ModBlocks.BARBED_WIRE.get().defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            s.put(s.abs(new BlockPos(3, 2, z)), wire);
        }
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        UUID id = UUID.randomUUID();
        Mob arbora = s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 1, s.eventId(), false);
        // 見るのは「切る」ことなので、鉄線のダメージで先に倒れさせない(体力の設計値に左右されないように)。2026-09-26 に mobs.json の
        // アルボラの体力が 8 になり、3 回目の削り(踏み込んで 40 tick)と 5 回目の鉄線のダメージ(2 / 10 tick、踏み込んで 40〜49 tick)が
        // 同じ tick に重なると、切る前に倒れて勝ちで終わっていた(1 割ほど)。
        arbora.setInvulnerable(true);
        TdTestAccess.setAlive(s.server, 1);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            for (int z = 0; z <= 6; z++) {
                if (!s.level.getBlockState(s.abs(new BlockPos(3, 2, z))).is(wire.getBlock())) {
                    s.pass();
                    return;
                }
            }
            if (helper.getTick() >= 350L) {
                s.fail("アルボラが 350 tick のうちに鉄線を切らない: 位置 " + helper.relativeVec(arbora.position())
                        + " / 生きている " + arbora.isAlive() + " / 侵攻の結果 " + TdData.get(s.server).lastOutcome());
            }
        });
    }

    /**
     * <b>スクティムネスは機関砲台に撃たれても止まらず、盾を向けたまま目標へ進む</b>(ユーザー指定 2026-09-25「機関砲に攻撃されている間は
     * 機関砲台のほうを向き、攻撃を防ぎながら目標に前進するように」)。護衛のスクティムネスがターゲット(東)へ向かう間、北の砲耳から
     * 5 tick ごとに機関砲台の弾を当て続ける(進む向きから 80° ほど横)。止まらずにターゲットを叩き、叩いたときに盾を下ろしている。
     * はじめは撃たれている間ずっと経路を止めていたので、一歩も進まなかった。
     */
    @GameTest(template = TEMPLATE, batch = "td_brace", timeoutTicks = 500)
    public static void scutimnesAdvancesUnderTurretFire(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        com.yayuy.overgrownvegetation.entity.ScutimnesEntity escort =
                s.mob(ModEntities.SCUTIMNES.get(), new Vec3(1.5D, 2.0D, 3.5D), false, null, s.eventId());
        TdTestAccess.setAlive(s.server, 1);
        Vec3 trunnion = helper.absoluteVec(new Vec3(3.5D, 3.2D, -9.5D));
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (helper.getTick() % 5 == 0 && escort.isAlive()) {
                escort.hurt(com.yayuy.overgrownvegetation.registry.ModDamageTypes.autocannon(s.level, trunnion), 1.0F);
            }
            if (be.hp() < max - EPS) {
                if (!escort.isShieldDown()) {
                    s.fail("撃たれながらターゲットを叩いたのに盾を下ろしていない");
                    return;
                }
                s.pass();
                return;
            }
            if (helper.getTick() >= 450L) {
                s.fail("撃たれている間に 450 tick のうちにターゲットへ進まない: 位置 " + helper.relativeVec(escort.position())
                        + " / 向き " + escort.getYRot() + " / 盾 " + escort.isShieldDown() + " / 動作 " + escort.getAction());
            }
        });
    }

    /**
     * <b>水面より 1 段高い岸へ上がれる</b>(2026-09-25、ユーザー報告「湿地で水の中へ入るも、陸地に上がろうとして失敗し続けスタックする」)。
     * テスト場の床(y = 1)の上を石で 2 段(y = 2・3)埋め、真ん中に深さ 1 の水たまり(x = 1〜3、z = 1〜5、水は y = 2、y = 3 は空気)。
     * 岸の上面(y = 4)は水面より約 1.1 高い。水たまりの中のアルボラ(群れの外。水から上がる Goal が岸へ向かう)が、跳ね上げ
     * ({@code WaterLedgeHop})で岸へ上がる。はじめは浮いたまま岸へ押し付けて上がれなかった(浮いている間は段を登れない)。
     * 2026-09-27 まで 5% ほど落ちていた(跳ね上げが水の底で出ると打ち消される)。その時機だけを確かめるのは
     * {@link #ledgeHopFromPoolBottomClearsHighBank}。
     */
    @GameTest(template = TEMPLATE, batch = "water_hop", timeoutTicks = 260)
    public static void landMobHopsOutOfPoolOntoHighBank(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean pool = x >= 1 && x <= 3 && z >= 1 && z <= 5;
                if (pool) {
                    s.put(s.abs(new BlockPos(x, 2, z)), Blocks.WATER.defaultBlockState());
                } else {
                    s.put(s.abs(new BlockPos(x, 2, z)), stone);
                    s.put(s.abs(new BlockPos(x, 3, z)), stone);
                }
            }
        }
        Mob arbora = s.mob(ModEntities.ARBORA.get(), new Vec3(2.5D, 2.2D, 3.5D), false, null, null);
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (!arbora.isInWater() && arbora.onGround() && arbora.getY() >= s.abs(BlockPos.ZERO).getY() + 3.9D) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 240L) {
                s.fail("水たまりから 240 tick のうちに岸へ上がらない: 位置 " + helper.relativeVec(arbora.position())
                        + " / 水の中 " + arbora.isInWater() + " / 横にぶつかっている " + arbora.horizontalCollision);
            }
        });
    }

    /**
     * 跳ね上げが<b>水の底で出ても</b>岸へ上がりきる(2026-09-27)。上の水たまりのテストは 5% ほど落ちていた: 跳ね上げは 8 tick おきで
     * 浮き沈みと無関係なので、底(足 y ≈ 2.0)で跳ねると、次の tick のバニラの水中の処理(上向き ×0.8、壁際では上向きを 0.3 に置き換え)に
     * 打ち消され、岸の上面(y = 4)に 0.2〜0.3 届かずに落ちていた。同じ水たまりの底に、西の岸へ押し付けて西を向けたアルボラを置き、
     * その場で跳ね上げさせる(時機を選ばない)。この 1 回で西の岸(幅 1)の上に乗ること —— 12 tick で見る(直す前の跳ね上げは 4 tick 目に
     * 3.8 まで上がって水へ落ち直し、その後の 8 tick おきの跳ね上げがたまたま良い時機に当たると 20 tick 以内に上がることがあった)。
     */
    @GameTest(template = TEMPLATE, batch = "water_hop", timeoutTicks = 40)
    public static void ledgeHopFromPoolBottomClearsHighBank(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                boolean pool = x >= 1 && x <= 3 && z >= 1 && z <= 5;
                if (pool) {
                    s.put(s.abs(new BlockPos(x, 2, z)), Blocks.WATER.defaultBlockState());
                } else {
                    s.put(s.abs(new BlockPos(x, 2, z)), stone);
                    s.put(s.abs(new BlockPos(x, 3, z)), stone);
                }
            }
        }
        // 体の幅 0.98。西の岸の側面(x = 1)から 0.01 だけ離す —— ぴったり 1.49 に置くと、テスト場の座標(±1500 万)の丸めで
        // 岸へ 1e-8 ほど食い込むことがあり、SpawnFit が「埋まっている」と見て東へ 1 マス押し出す。足は水たまりの底(y = 2)。
        Mob arbora = s.mob(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), false, null, null);
        if (helper.relativeVec(arbora.position()).distanceTo(new Vec3(1.5D, 2.0D, 3.5D)) > 1.0E-6D) {
            s.fail("置いた場所から動かされた(前提が崩れている): " + helper.relativeVec(arbora.position()));
            return;
        }
        arbora.setYRot(90.0F); // 西(−X)を向く
        arbora.setYBodyRot(90.0F);
        arbora.setYHeadRot(90.0F);
        if (!(arbora instanceof net.minecraft.world.entity.PathfinderMob mob)
                || !com.yayuy.overgrownvegetation.entity.WaterLedgeHop.tryHop(mob)) {
            s.fail("水たまりの底から西の岸へ跳ね上げられない(岸の上に体が出られる高さが見つからない)");
            return;
        }
        int baseY = s.abs(BlockPos.ZERO).getY();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (!arbora.isInWater() && arbora.onGround() && arbora.getY() >= baseY + 3.9D) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 12L) {
                s.fail("水たまりの底から跳ねて 12 tick のうちに岸へ上がらない: 位置 " + helper.relativeVec(arbora.position())
                        + " / 水の中 " + arbora.isInWater());
            }
        });
    }

    /**
     * <b>幅の広い体の経路が北・西向きの 1 段を上れる</b>(2026-09-25、湿地の調べで見つけた不具合: 包絡の無い探索では上りの箱の中心を
     * ノード + 0.5 に固定していたので、幅 1 以上の体では箱が 1 マス −X・−Z 側へずれて段そのものに重なり、北・西向きの上りが
     * すべて塞がれていた —— 護衛の経路・後続の立て直し・水たまりから北・西の岸への上り)。北の帯(z = 0〜2)と西の帯(x = 0〜2)を
     * 1 段高くし、南東のファルキムネス(幅 2.97)から両方の帯の上へ経路が届く(行き先はノード = 体の北西の角。3 マスの体が収まる (3, 3, 0)・(0, 3, 3))。
     */
    @GameTest(template = TEMPLATE, batch = "wide_climb", timeoutTicks = 20)
    public static void wideMobPathClimbsNorthAndWest(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                if (z <= 2 || x <= 2) {
                    s.put(s.abs(new BlockPos(x, 2, z)), stone);
                }
            }
        }
        Mob falcimnes = s.mob(ModEntities.FALCIMNES.get(), new Vec3(5.0D, 2.0D, 5.0D), true, null, null);
        falcimnes.refreshDimensions(); // 体格を当たり判定に乗せる(最初の tick の前)
        falcimnes.setOnGround(true); // 地上の経路は足が地面にあるときだけ引ける(置いた tick の NoAI の個体はまだ立っていない)
        net.minecraft.world.level.pathfinder.Path north = falcimnes.getNavigation().createPath(s.abs(new BlockPos(3, 3, 0)), 0);
        net.minecraft.world.level.pathfinder.Path west = falcimnes.getNavigation().createPath(s.abs(new BlockPos(0, 3, 3)), 0);
        if (north == null || !north.canReach() || west == null || !west.canReach()) {
            s.fail("幅 " + falcimnes.getBbWidth() + " の体の経路が 1 段を上れない: 北 " + (north != null && north.canReach())
                    + " / 西 " + (west != null && west.canReach()));
            return;
        }
        s.pass();
    }

    /**
     * <b>いちばん大きい一員が通れない隙間も、先導役が通れれば通る</b>(2026-09-25、ユーザー報告「TD 中にターゲットへ行かず、湧いた場所で
     * ずっとくるくるしてスタック」)。テスト場を横切る石の壁(x = 4)に幅 2・高さ 2 の隙間(z = 3〜4、y = 2〜3)を開け、向こう(6, 2, 3)に
     * ターゲット。群れはアルボラ(先導役、幅 1)とスカトゥリウム(幅 2.5 × 体格 = 包絡 3 マス)。包絡では隙間を通れないので、先導役の体の幅の
     * 区間に切り替えて({@code SwarmPaths#requestLeg} の NARROWED)隙間を抜け、<b>壁を壊さずに</b>ターゲットを削る。
     * はじめは包絡のまま区間を引き続けて塞がれ、壁を壊していた(森では湧いた場所の木を壊し続けて進まなかった)。
     */
    @GameTest(template = TEMPLATE, batch = "td_gap", timeoutTicks = 900)
    public static void mixedSwarmSlipsThroughGapItsLeaderFits(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                if ((z == 3 || z == 4) && y <= 3) {
                    continue;
                }
                s.put(s.abs(new BlockPos(4, y, z)), stone);
            }
        }
        BlockPos target = s.abs(new BlockPos(6, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        Mob gapArbora = s.member(ModEntities.ARBORA.get(), new Vec3(2.5D, 2.0D, 5.5D), id, 0, 2, eventId, false);
        Mob gapShielder = s.member(ModEntities.OVERGROWN_SHIELDER.get(), new Vec3(1.5D, 2.0D, 1.5D), id, 1, 2, eventId, false);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            int holes = 0;
            StringBuilder where = new StringBuilder();
            for (int z = 0; z <= 6; z++) {
                for (int y = 2; y <= 7; y++) {
                    BlockPos cell = s.abs(new BlockPos(4, y, z));
                    BlockState here = s.level.getBlockState(cell);
                    // 壊れた(当たりが無くなった)マスだけ数える。テスト場の外から来た汚染で壁の上の角の石が過繁茂の石・草に変わる
                    // ことがあるが、壁は残っている(2026-09-25 に 6 回中 4 回。群れの 2 体は無傷で、出どころは突き止めていない。
                    // 攻め手が死んだときの汚染と同じ形)。
                    if (!((z == 3 || z == 4) && y <= 3) && here.getCollisionShape(s.level, cell).isEmpty()) {
                        holes++;
                        if (holes <= 4) {
                            where.append(" (4, ").append(y).append(", ").append(z).append(")=")
                                    .append(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(here.getBlock()).getPath());
                        }
                    }
                }
            }
            Swarm now = SwarmManager.get(s.level, id);
            String state = now == null ? "群れなし" : now.mode() + " / 壊す壁 " + now.breachPos() + " / 先導役 "
                    + (now.vanguard() == null ? "なし" : now.vanguard().getType().toShortString() + " "
                    + helper.relativeVec(now.vanguard().position()));
            if (holes > 0) {
                s.fail("隙間を先導役が通れるのに壁を " + holes + " マス壊した:" + where + " / " + state + " / アルボラ "
                        + gapArbora.isAlive() + " " + gapArbora.getHealth() + " " + gapArbora.getRemovalReason() + " " + gapArbora.getLastDamageSource()
                        + " / スカトゥリウム " + gapShielder.isAlive() + " " + gapShielder.getHealth() + " " + gapShielder.getRemovalReason()
                        + " " + gapShielder.getLastDamageSource() + " " + helper.relativeVec(gapShielder.position()));
                return;
            }
            if (be.hp() < max - EPS) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 850L) {
                s.fail("隙間の向こうのターゲットを 850 tick のうちに削らない: " + state);
            }
        });
    }

    /**
     * <b>1 段の台の上のターゲット</b>(本物の AI): 石の台(相対 3, 2, 3)の上(3, 3, 3)にターゲットを置き、アルボラ 2 体だけの群れで削る。
     * はじめは手の届く箱を横にしか広げていなかったので、背の低いアルボラ(フェーズ 5 の編成のただ一つの前衛)は台の上の塊に
     * 一度も届かなかった({@code Swarm#canReachBlock})。
     */
    @GameTest(template = TEMPLATE, batch = "td_pedestal", timeoutTicks = 500)
    public static void targetOnPedestalIsStruck(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        s.put(s.abs(new BlockPos(3, 2, 3)), Blocks.STONE.defaultBlockState());
        BlockPos target = s.abs(new BlockPos(3, 3, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 2, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(5.5D, 2.0D, 3.5D), id, 1, 2, eventId, false);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (be.hp() < max - EPS) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 450L) {
                Swarm now = SwarmManager.get(s.level, id);
                s.fail("1 段の台の上のターゲットを 450 tick のうちに削らない: 群れ "
                        + (now == null ? "なし" : now.mode() + " / 先導役 "
                        + (now.vanguard() == null ? "なし" : helper.relativeVec(now.vanguard().position())
                        + " / 輪の位置 " + now.blockEngageSlot(now.vanguard()))));
            }
        });
    }

    /**
     * <b>登る群れは壁を壊さずに越える</b>(ユーザー指定 2026-09-25「アルボラは壁や天井を通ってターゲットブロックへ行くルートも。
     * 壁を壊して入るグループと、壁を登るグループ」)。テスト場を横切る高さ 4 の石の壁(x = 3、y = 2〜5)の向こう(5, 2, 3)に
     * ターゲットを置き、手前に登る群れ(アルボラ 2 体、所属の climber)を置く。本物の AI で、壁に穴を開けずに(BREACH に入らずに)
     * ターゲットを削る —— 先導役は区間の縦の辺で登り、壁の上の縁を乗り越え、向こう側の面を這って降りる
     * ({@code ClimbNodeEvaluator} の這う辺)。後続は足跡の CLIMB の点で張り付く。
     * 同じ壁で登らない群れが壁を壊すのは {@link #targetBehindWallIsBreached}。
     */
    @GameTest(template = TEMPLATE, batch = "td_climb", timeoutTicks = 1400)
    public static void climberSwarmCrossesWallWithoutBreaking(GameTestHelper helper) {
        climberCrossing(helper, false);
    }

    /**
     * <b>登る群れは庇(ねずみ返し)も回り込む</b>(ユーザー指定 2026-09-25「壁や天井を通って」)。{@link #climberSwarmCrossesWallWithoutBreaking}
     * の壁の上端に、手前へ 1 マス張り出した庇(2, 5, *)を足したもの。先導役は壁を登って庇の下に着き、庇の裏から庇の外の面へ回り込み
     * ({@code SurfaceCrawl} の「外側の角を巻く」・経路の「外側の角を上へ」)、庇の上へ跳び上がって壁の上を渡る。壁にも庇にも穴を開けない。
     */
    @GameTest(template = TEMPLATE, batch = "td_climb_lip", timeoutTicks = 1400)
    public static void climberSwarmRoundsOverhang(GameTestHelper helper) {
        climberCrossing(helper, true);
    }

    /** 登る群れのテストの本体。{@code lip} なら壁の上端に手前へ張り出した庇を足す。 */
    private static void climberCrossing(GameTestHelper helper, boolean lip) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        List<BlockPos> wall = new ArrayList<>();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 5; y++) {
                wall.add(s.abs(new BlockPos(3, y, z)));
            }
            if (lip) {
                wall.add(s.abs(new BlockPos(2, 5, z)));
            }
        }
        for (BlockPos p : wall) {
            s.put(p, stone);
        }
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.climber(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 2, eventId);
        s.climber(ModEntities.ARBORA.get(), new Vec3(0.5D, 2.0D, 2.5D), id, 1, 2, eventId);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null || !swarm.climber()) {
            s.fail("登る群れが登録されていない / climber が立っていない: " + (swarm == null ? "群れなし" : "climber=false"));
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        String what = lip ? "庇の付いた壁" : "壁";
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            Swarm now = SwarmManager.get(s.level, id);
            if (now != null && now.mode() == Swarm.Mode.BREACH) {
                s.fail("登る群れが" + what + "を壊しにかかった: 壊す壁 " + rel(helper, now.breachPos()));
                return;
            }
            int holes = 0;
            String first = "";
            for (BlockPos p : wall) {
                BlockState st = s.level.getBlockState(p);
                if (!st.is(Blocks.STONE)) {
                    if (holes == 0) {
                        first = rel(helper, p) + " が " + st;
                    }
                    holes++;
                }
            }
            if (holes > 0) {
                s.fail("登る群れの攻めで" + what + "に穴が開いた(" + holes + " マス。" + first + ") / "
                        + (now == null ? "群れなし" : climbDebug(helper, now)));
                return;
            }
            // 先導役がターゲットを削り、後続も壁を越えた(壁の向こう = 相対 x ≥ 4)ら成功。後続は足跡の CLIMB の点で張り付いて登り、
            // 壁の上の縁から向こう側の面を這って降りる。
            boolean allOver = now != null && !now.members().isEmpty();
            if (now != null) {
                for (Mob m : now.members()) {
                    allOver &= helper.relativeVec(m.position()).x >= 4.0D;
                }
            }
            if (be.hp() < max - EPS && allOver) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 1300L) {
                s.fail("登る群れが 1300 tick のうちに" + what + "の向こうのターゲットを削らない: 群れ "
                        + (now == null ? "なし" : "包絡 " + now.envelope() + " / " + climbDebug(helper, now)));
            }
        });
    }

    /**
     * <b>登る群れは基地の金網(鉄格子と同じ作り)を越える</b>(ユーザー報告 2026-10-02「アルボラがフェンスや塀を登って超えられない」)。
     * 基地の外周と同じ、高さ 3 の金網(x = 3、y = 2〜4)の上に有刺鉄線(y = 5)。金網の格子はマスの真ん中にあって体が面に
     * ぶつからないので、バニラの登り(上へ 0.2)が出ず、張り付きの寄せの最後の残りが 0.003 の丸めで消えて y = 2.999 のまま
     * 1 段も上がれずに剥がれていた({@code AbstractOvergrownInvaderEntity#moveOnSurface} の位置合わせ)。
     */
    @GameTest(template = TEMPLATE, batch = "td_climb_chain", timeoutTicks = 1400)
    public static void climberSwarmCrossesChainLinkFence(GameTestHelper helper) {
        climberCrossesObstacle(helper, ClimbObstacle.CHAIN_LINK);
    }

    /**
     * <b>登る群れは胸壁(塀のブロック、当たり判定の高さ 1.5)の付いた壁を越える</b>(同上)。基地の壁の上端と同じ、壁(y = 2〜4)の上に塀(y = 5)。
     * 塀の上の 0.5 が張り付く面にも立つ床にも数えられず、壁の上端の 1 つ下で登る列が途切れ、跳び上がり({@code SurfaceCrawl#mantleCell})も
     * 当たり判定に阻まれていた({@code SurfaceCrawl#raisedFromBelow})。
     */
    @GameTest(template = TEMPLATE, batch = "td_climb_parapet", timeoutTicks = 1400)
    public static void climberSwarmCrossesParapet(GameTestHelper helper) {
        climberCrossesObstacle(helper, ClimbObstacle.PARAPET);
    }

    /**
     * <b>登る群れは高さ 2 の塀を越える</b>(同上)。塀が低いと、後続が登り切る前に足跡のカーソルが塀の上の点を通り過ぎ、
     * 塀の向こうの低い点しか残らずに塀の前で跳び続けた(後続はぶつかった壁の真裏に点があれば張り付く)。20 回中 17 回落ちていた。
     */
    @GameTest(template = TEMPLATE, batch = "td_climb_low_wall", timeoutTicks = 1400)
    public static void climberSwarmCrossesLowWall(GameTestHelper helper) {
        climberCrossesObstacle(helper, ClimbObstacle.LOW_WALL);
    }

    /** {@link #climberCrossesObstacle} の障害物(x = 3 にテスト場を横切って置く)。 */
    private enum ClimbObstacle {
        /** 金網 3 段(y = 2〜4)+ 有刺鉄線(y = 5)。 */
        CHAIN_LINK,
        /** 鉄筋コンクリート 3 段(y = 2〜4)+ 塀(y = 5)。 */
        PARAPET,
        /** 塀 2 段(y = 2〜3)。 */
        LOW_WALL
    }

    /**
     * {@link #climberCrossing} と同じ場面で、障害物を基地のブロックにしたもの。壁に穴を開けず(BREACH に入らず)、
     * 2 体とも越えてターゲットを削れば成功。落ちたときは最後の 160 tick の一員の様子を添える。
     */
    private static void climberCrossesObstacle(GameTestHelper helper, ClimbObstacle obstacle) {
        TdStage s = new TdStage(helper);
        BlockState chain = com.yayuy.overgrownvegetation.base.BaseBlocks.CHAIN_LINK_FENCE.get().defaultBlockState();
        BlockState concrete = com.yayuy.overgrownvegetation.base.BaseBlocks.REINFORCED_CONCRETE.get().defaultBlockState();
        BlockState wallBlock = com.yayuy.overgrownvegetation.base.BaseBlocks.REINFORCED_CONCRETE_WALL.get().defaultBlockState();
        List<BlockPos> wall = new ArrayList<>();
        for (int z = 0; z <= 6; z++) {
            switch (obstacle) {
                case CHAIN_LINK -> {
                    for (int y = 2; y <= 4; y++) {
                        wall.add(s.abs(new BlockPos(3, y, z)));
                        s.put(wall.get(wall.size() - 1), chain);
                    }
                    s.put(s.abs(new BlockPos(3, 5, z)), ModBlocks.BARBED_WIRE.get().defaultBlockState());
                }
                case PARAPET -> {
                    for (int y = 2; y <= 4; y++) {
                        wall.add(s.abs(new BlockPos(3, y, z)));
                        s.put(wall.get(wall.size() - 1), concrete);
                    }
                    wall.add(s.abs(new BlockPos(3, 5, z)));
                    s.put(wall.get(wall.size() - 1), wallBlock);
                }
                case LOW_WALL -> {
                    for (int y = 2; y <= 3; y++) {
                        wall.add(s.abs(new BlockPos(3, y, z)));
                        s.put(wall.get(wall.size() - 1), wallBlock);
                    }
                }
            }
        }
        // つながり(金網・塀の腕)を整える。1 つずつ置くと、置いた側のブロックは隣を見ずに既定の形のまま。
        for (BlockPos p : wall) {
            BlockState st = s.level.getBlockState(p);
            BlockState fixed = Block.updateFromNeighbourShapes(st, s.level, p);
            if (fixed != st) {
                s.level.setBlock(p, fixed, Block.UPDATE_ALL);
            }
        }
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.climber(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 2, eventId);
        s.climber(ModEntities.ARBORA.get(), new Vec3(0.5D, 2.0D, 2.5D), id, 1, 2, eventId);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null || !swarm.climber()) {
            s.fail("登る群れが登録されていない / climber が立っていない: " + (swarm == null ? "群れなし" : "climber=false"));
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        java.util.ArrayDeque<String> recent = new java.util.ArrayDeque<>();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            Swarm now = SwarmManager.get(s.level, id);
            if (now != null && now.mode() == Swarm.Mode.BREACH) {
                s.fail("登る群れが " + obstacle + " を壊しにかかった: 壊す壁 " + rel(helper, now.breachPos())
                        + " / gt=" + s.level.getGameTime() + " / 直近: " + String.join(" ## ", recent));
                return;
            }
            for (BlockPos p : wall) {
                if (s.level.getBlockState(p).isAir()) {
                    s.fail("登る群れの攻めで " + obstacle + " に穴が開いた: " + rel(helper, p));
                    return;
                }
            }
            if (now != null && helper.getTick() % 4L == 0L) {
                recent.addLast("t=" + helper.getTick() + " " + climbDebug(helper, now));
                while (recent.size() > 40) {
                    recent.removeFirst();
                }
            }
            boolean allOver = now != null && !now.members().isEmpty();
            if (now != null) {
                for (Mob m : now.members()) {
                    allOver &= helper.relativeVec(m.position()).x >= 4.0D;
                }
            }
            if (be.hp() < max - EPS && allOver) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 1300L) {
                // 群れから外れた一員も見えるように、テスト場のアルボラ全員と、先導役の両隣の列の障害物の形(2026-10-19、
                // 先導役が金網の格子に体を付けたまま y = 4.0 − 背丈で止まった回の調べ)。
                StringBuilder extra = new StringBuilder();
                for (com.yayuy.overgrownvegetation.entity.ArboraEntity a : s.level.getEntitiesOfClass(
                        com.yayuy.overgrownvegetation.entity.ArboraEntity.class, helper.getBounds().inflate(2.0D))) {
                    extra.append(" [arbora ").append(helper.relativeVec(a.position())).append(" alive=").append(a.isAlive())
                            .append(" hp=").append(a.getHealth()).append(" inSwarm=").append(now != null && now.members().contains(a))
                            .append(" cling=").append(a.isClinging()).append(']');
                }
                Mob lead = now != null ? now.vanguard() : null;
                for (BlockPos p : wall) {
                    if (lead != null && Math.abs(p.getZ() + 0.5D - lead.getZ()) <= 1.5D) {
                        extra.append(" {").append(rel(helper, p)).append(' ').append(s.level.getBlockState(p)).append('}');
                    }
                }
                s.fail("登る群れが 1300 tick のうちに " + obstacle + " の向こうのターゲットを削らない(全員が越えない) / 様子:"
                        + extra + " / 直近: " + String.join(" ## ", recent));
            }
        });
    }

    // ------------------------------------------------------------------ 攻め手の顔ぶれ(2026-09-25)

    /**
     * 仮想の群れ(登る群れ: アルボラ 2 + 護衛のファルキムネス 1)が本物の帳簿で実体になると、
     * <ul>
     *     <li>アルボラ 2 体は同じ群れ(出どころ TD)で、群れは<b>登る群れ</b>(所属の climber → {@code Swarm#climber})、攻城のターゲットはこの侵攻の塊。</li>
     *     <li>ファルキムネスは群れに入らない護衛で、侵攻の印と、標的がいない間ターゲットへ向かう Goal({@code TdAssaultGoal})を持つ。</li>
     *     <li>生きている数は 3。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_materialize", timeoutTicks = 300)
    public static void virtualClimberAndEscortMaterialize(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(1, 2, 1));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String id = s.eventId();
        java.util.LinkedHashMap<EntityType<?>, Integer> counts = new java.util.LinkedHashMap<>();
        counts.put(ModEntities.ARBORA.get(), 2);
        counts.put(ModEntities.FALCIMNES.get(), 1);
        TdTestAccess.addVirtualSwarm(s.server, helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D)), counts, true);
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (!e.virtualSwarms().isEmpty()) {
                if (helper.getTick() - begin > 200L) {
                    s.fail("仮想の群れが実体にならない: " + describe(e));
                }
                return;
            }
            List<Mob> arboras = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(),
                    m -> m.getType() == ModEntities.ARBORA.get() && EventMobs.has(m, EventMobs.Kind.TD, id));
            List<Mob> escorts = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(),
                    m -> m.getType() == ModEntities.FALCIMNES.get() && EventMobs.has(m, EventMobs.Kind.TD, id));
            s.spawned.addAll(arboras);
            s.spawned.addAll(escorts);
            if (arboras.size() != 2 || escorts.size() != 1 || e.alive() != 3) {
                s.fail("実体になった数が違う: アルボラ " + arboras.size() + "(2)・ファルキムネス " + escorts.size()
                        + "(1)・生きている数 " + e.alive() + "(3)");
                return;
            }
            Swarm swarm = SwarmManager.get(arboras.get(0));
            if (swarm == null || swarm != SwarmManager.get(arboras.get(1)) || !swarm.climber()
                    || !target.equals(swarm.targetBlock())) {
                s.fail("アルボラが同じ登る群れでこの侵攻の塊を攻めていない: "
                        + (swarm == null ? "群れなし" : "climber=" + swarm.climber() + " / 塊 " + rel(helper, swarm.targetBlock())));
                return;
            }
            Mob escort = escorts.get(0);
            boolean assault = escort.goalSelector.getAvailableGoals().stream()
                    .anyMatch(g -> g.getGoal() instanceof com.yayuy.overgrownvegetation.td.TdAssaultGoal);
            if (SwarmManager.get(escort) != null || !assault) {
                s.fail("護衛が群れに入った / TdAssaultGoal が無い: 群れ " + SwarmManager.get(escort) + " / Goal " + assault);
                return;
            }
            s.pass();
        });
    }

    /**
     * 仮想の群れの顔ぶれ({@code TdTicker#rollRoster}。ユーザー指定 2026-09-25「TD で G4〜9 も。TD が起きたフェーズで湧く mob のみ」
     * 「壁を壊して入るグループと、壁を登るグループ」)。確率を 0 / 1 にした表で、決まった乱数で振る:
     * <ul>
     *     <li>フェーズ 5: 地上の群れはアルボラだけ、護衛は出ない(G5 のファルキムネス・スクティムネスは F7 から)。</li>
     *     <li>フェーズ 7: 護衛の確率 1 なら G5 の護衛が 1 体、並びの最後に付く(G6〜G9 は種が無いので付かない)。</li>
     *     <li>護衛から外した種(escortExcluded)は出ない。</li>
     *     <li>登る群れの割合 1: 登る群れで、アルボラだけ(護衛は付く)。</li>
     *     <li>空の群れの割合 1: ヴェロフィルだけで、護衛は付かない。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void rosterFollowsPhaseClimbersAndEscorts(GameTestHelper helper) {
        EntityType<?> arbora = ModEntities.ARBORA.get();
        EntityType<?> falcimnes = ModEntities.FALCIMNES.get();
        EntityType<?> scutimnes = ModEntities.SCUTIMNES.get();
        com.yayuy.overgrownvegetation.swarm.SwarmTable swarms = DesignData.swarm();
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(20260925L);
        for (int round = 0; round < 20; round++) {
            // G5(護衛)がまだ湧かないフェーズ 4(2026-10-10 から G4 は F4・G5 は F5)。
            Object[] f5 = TdTestAccess.rollRoster(4, rosterTable(0.0D, 0.0D, 1.0D, List.of()), swarms, random);
            List<?> f5types = f5 == null ? List.of() : (List<?>) f5[2];
            if (f5 == null || f5[0] != Swarm.Kind.GROUND || (Boolean) f5[1] || f5types.isEmpty()
                    || f5types.stream().anyMatch(t -> t != arbora)) {
                helper.fail("フェーズ 4 の地上の群れがアルボラだけでない / 護衛が付いた: " + describeRoster(f5));
                return;
            }
            Object[] f7 = TdTestAccess.rollRoster(7, rosterTable(0.0D, 0.0D, 1.0D, List.of()), swarms, random);
            List<?> f7types = f7 == null ? List.of() : (List<?>) f7[2];
            Object last = f7types.isEmpty() ? null : f7types.get(f7types.size() - 1);
            long g5 = f7types.stream().filter(TdGameTests::isG5).count();
            if (f7 == null || f7[0] != Swarm.Kind.GROUND || g5 != 1L || !isG5(last)) {
                helper.fail("フェーズ 7・護衛の確率 1 で G5 の護衛がちょうど 1 体、最後に付かない: " + describeRoster(f7));
                return;
            }
            Object[] noFalc = TdTestAccess.rollRoster(7, rosterTable(0.0D, 0.0D, 1.0D, List.of("falcimnes")), swarms, random);
            if (noFalc == null || ((List<?>) noFalc[2]).contains(falcimnes)) {
                helper.fail("護衛から外したファルキムネスが出た: " + describeRoster(noFalc));
                return;
            }
            Object[] climb = TdTestAccess.rollRoster(7, rosterTable(0.0D, 1.0D, 1.0D, List.of()), swarms, random);
            List<?> climbTypes = climb == null ? List.of() : (List<?>) climb[2];
            if (climb == null || climb[0] != Swarm.Kind.GROUND || !(Boolean) climb[1]
                    || climbTypes.stream().anyMatch(t -> t != arbora && !isG5(t))
                    || climbTypes.stream().noneMatch(t -> t == arbora)) {
                helper.fail("登る群れの割合 1 で、登る群れ(アルボラ + 護衛)にならない: " + describeRoster(climb));
                return;
            }
            Object[] air = TdTestAccess.rollRoster(7, rosterTable(1.0D, 0.0D, 1.0D, List.of()), swarms, random);
            List<?> airTypes = air == null ? List.of() : (List<?>) air[2];
            if (air == null || air[0] != Swarm.Kind.AIR || airTypes.isEmpty()
                    || airTypes.stream().anyMatch(t -> t != ModEntities.VELOPHYLL.get())) {
                helper.fail("空の群れがヴェロフィルだけでない / 護衛が付いた: " + describeRoster(air));
                return;
            }
        }
        helper.succeed();
    }

    /** G5(適応種)の種か(護衛の候補。2026-09-28 にアクムネスが加わったので、種の名前で決め打ちしない)。 */
    private static boolean isG5(@Nullable Object type) {
        return type instanceof EntityType<?> e && com.yayuy.overgrownvegetation.phase.MobGroup.GROUP_5.contains(e);
    }

    private static TdTable rosterTable(double airShare, double climberShare, double escort, List<String> excluded) {
        TdTable.Json json = new TdTable.Json();
        json.airSwarmShare = airShare;
        json.climberShare = climberShare;
        json.escortChance = java.util.Map.of("5", escort, "6", escort, "7", escort, "8", escort, "9", escort);
        json.escortExcluded = excluded;
        return TdTable.fromJson(json);
    }

    private static String describeRoster(@Nullable Object[] roster) {
        if (roster == null) {
            return "顔ぶれなし";
        }
        StringBuilder out = new StringBuilder().append(roster[0]).append(" climber=").append(roster[1]).append(" [");
        for (Object t : (List<?>) roster[2]) {
            out.append(' ').append(EntityType.getKey((EntityType<?>) t).getPath());
        }
        return out.append(" ]").toString();
    }

    /**
     * <b>護衛</b>(群れに入らない G5。侵攻の印だけ)は標的がいない間ターゲットへ歩いて叩く({@code TdAssaultGoal}。
     * 読み込みのときに {@code TdTicker#onJoin} が差し込む)。ファルキムネスを 1 体、ターゲットから 3.5 ブロック離して置く。
     */
    @GameTest(template = TEMPLATE, batch = "td_escort", timeoutTicks = 500)
    public static void escortWalksToTargetAndStrikes(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        Mob escort = s.mob(ModEntities.FALCIMNES.get(), new Vec3(1.5D, 2.0D, 3.5D), false, null, eventId);
        TdTestAccess.setAlive(s.server, 1);
        boolean installed = escort.goalSelector.getAvailableGoals().stream()
                .anyMatch(g -> g.getGoal() instanceof com.yayuy.overgrownvegetation.td.TdAssaultGoal);
        if (!installed) {
            s.fail("侵攻の印を持つ護衛に TdAssaultGoal が差し込まれていない");
            return;
        }
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (be.hp() < max - EPS) {
                float dealt = max - be.hp();
                float attack = (float) escort.getAttributeValue(Attributes.ATTACK_DAMAGE);
                if (Math.abs(dealt - attack) > 0.01F && dealt < attack) {
                    s.fail("護衛の 1 回の削りが攻撃力と違う: " + dealt + "(攻撃力 " + attack + ")");
                    return;
                }
                s.pass();
                return;
            }
            if (helper.getTick() >= 450L) {
                s.fail("護衛が 450 tick のうちにターゲットを叩かない: 位置 " + helper.relativeVec(escort.position())
                        + " / 標的 " + escort.getTarget() + " / 経路 " + escort.getNavigation().getPath());
            }
        });
    }

    /**
     * <b>樽の壁も壊す</b>(ユーザー指定 2026-09-25「TD では樽やチェストなどのプレイヤーの持ち物も容赦なく破壊していく」)。
     * {@link #targetBehindWallIsBreached} の石の壁を、土を 1 つずつ入れた樽の壁に替えたもの。登らない群れ(アルボラ 2 体)が
     * 樽を壊してターゲットを削り、壊れた樽は樽のまま落ち、中身は散らばる(消えない)。
     */
    @GameTest(template = TEMPLATE, batch = "td_barrel", timeoutTicks = 1200)
    public static void barrelWallIsBreachedAndSpills(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        List<BlockPos> barrels = new ArrayList<>();
        for (int z = 0; z <= 6; z++) {
            for (int y = 2; y <= 7; y++) {
                BlockPos p = s.abs(new BlockPos(4, y, z));
                s.put(p, Blocks.BARREL.defaultBlockState());
                if (s.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel) {
                    barrel.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIRT));
                }
                barrels.add(p);
            }
        }
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.member(ModEntities.ARBORA.get(), new Vec3(2.5D, 2.0D, 3.5D), id, 0, 2, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 1, 2, eventId, false);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        AABB near = helper.getBounds().inflate(3.0D);
        Runnable emptyBarrels = () -> {
            // 片付け(空気に戻す)で残った樽の中身が散らばらないように、先に空にする。
            for (BlockPos p : barrels) {
                if (s.level.getBlockEntity(p) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel) {
                    barrel.clearContent();
                }
            }
        };
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            List<ItemEntity> items = s.level.getEntitiesOfClass(ItemEntity.class, near);
            boolean dirt = items.stream().anyMatch(i -> i.getItem().is(net.minecraft.world.item.Items.DIRT));
            boolean barrelItem = items.stream().anyMatch(i -> i.getItem().is(net.minecraft.world.item.Items.BARREL));
            if (be.hp() < max - EPS && dirt && barrelItem) {
                emptyBarrels.run();
                s.pass();
                return;
            }
            if (helper.getTick() >= 1100L) {
                int broken = 0;
                for (BlockPos p : barrels) {
                    if (!s.level.getBlockState(p).is(Blocks.BARREL)) {
                        broken++;
                    }
                }
                emptyBarrels.run();
                s.fail("樽の壁の裏のターゲットを 1100 tick のうちに削らない / 中身が散らばらない: 壊れた樽 " + broken
                        + " / 土 " + dirt + " / 樽 " + barrelItem + " / 耐久 " + be.hp());
            }
        });
    }

    /**
     * <b>コマンドで置いた敵対 Mob は侵攻の範囲でも入る</b>(レビュー指摘 2026-09-25 の残り、2026-09-27 に直した)。NBT 付きの {@code /summon} は
     * finalizeSpawn を通らないので印が付かず、範囲の中で取り消されて「UUID が重複」と出ていた({@code SummonCommandTdExemptMixin})。
     * NBT 無しと NBT 付き(乗り手付き)の {@code /summon} のハスクが、乗り手も含めて世界に入って印を持つ。比べとして、印の無いハスクを足すと
     * 取り消される(侵攻と範囲は効いている)。
     */
    @GameTest(template = TEMPLATE, batch = "td_summon", timeoutTicks = 40)
    public static void summonedHostilesJoinDuringInvasion(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(6, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        Husk control = EntityType.HUSK.create(s.level);
        if (control == null) {
            s.fail("ハスクを作れない");
            return;
        }
        Vec3 controlAt = helper.absoluteVec(new Vec3(3.5D, 2.0D, 3.5D));
        control.moveTo(controlAt.x, controlAt.y, controlAt.z, 0.0F, 0.0F);
        if (s.level.addFreshEntity(control)) {
            s.spawned.add(control);
            s.fail("侵攻中なのに印の無いハスクが入った(範囲が効いていない)");
            return;
        }
        net.minecraft.commands.CommandSourceStack source = s.server.createCommandSourceStack().withLevel(s.level)
                .withPosition(Vec3.atCenterOf(target)).withPermission(4).withSuppressedOutput();
        Vec3 plain = helper.absoluteVec(new Vec3(1.5D, 2.0D, 1.5D));
        Vec3 withNbt = helper.absoluteVec(new Vec3(1.5D, 2.0D, 5.5D));
        s.server.getCommands().performPrefixedCommand(source, String.format(Locale.ROOT,
                "summon minecraft:husk %.3f %.3f %.3f", plain.x, plain.y, plain.z));
        s.server.getCommands().performPrefixedCommand(source, String.format(Locale.ROOT,
                "summon minecraft:husk %.3f %.3f %.3f {NoAI:1b,Passengers:[{id:\"minecraft:husk\",IsBaby:1b,NoAI:1b}]}",
                withNbt.x, withNbt.y, withNbt.z));
        List<Husk> husks = s.level.getEntitiesOfClass(Husk.class, helper.getBounds().inflate(1.0D));
        s.spawned.addAll(husks);
        long tagged = husks.stream().filter(h -> h.getTags().contains(TdHostiles.EXEMPT_TAG)).count();
        boolean rider = husks.stream().anyMatch(h -> h.isBaby() && h.isPassenger());
        if (husks.size() != 3 || tagged != 3 || !rider) {
            s.fail("侵攻中の /summon のハスク: 入った " + husks.size() + " 体(NBT 無し・NBT 付き・その乗り手の 3 体のはず)/ 印 "
                    + tagged + " 体 / NBT の乗り手 " + (rider ? "あり" : "なし"));
            return;
        }
        s.pass();
    }

    /**
     * <b>侵攻の範囲の敵対 Mob</b>(ユーザー指定 2026-09-25「TD 開始時には G4 以降の mob 以外の敵対 mob を消滅させて、TD 期間中は新規に
     * スポーンしないように(牛や豚などは残る)」、{@link TdHostiles})。
     * <ol>
     *     <li>種の判定: 巨大樹の巣の節・蔦(グループの無いこの MOD の種。湧きの区分は MONSTER)は消さない(レビュー指摘 2026-09-25)。</li>
     *     <li>始まる前に置いた ハスク・寄生されたゾンビ(G1)・アルトロプレウラ(G2。ユーザー指定 2026-09-25「追加で G2 も消す」)・
     *     ペトロラコサウルス(G3)・拾った剣を持つハスクは、始まった瞬間に消える(剣は落ちる)。名札付きのハスク・牛・アルボラ(G4)・
     *     ワールドの生成で置かれた形のハスク(永続・拾った物なし)・治療中の村人ゾンビ・始まる前にスポーンエッグで置いたハスクは残る。</li>
     *     <li>既定(td.json の hostileClearRadius = 0)は<b>ディメンション全体</b>(ユーザー指定 2026-09-25): ターゲットから 5000 先も範囲、
     *     バニラの自然湧きの敵対 Mob の回が止まる。</li>
     *     <li>侵攻中、ふつうに足したハスク(addFreshEntity)は入らない。スポーンエッグと同じ湧き方(SPAWN_EGG)のハスクは入る。</li>
     *     <li>範囲を 2 にすると、ターゲットから 4 ブロックのハスクは入る。</li>
     *     <li>侵攻を止めたら、ふつうに足したハスクも入り、自然湧きの敵対 Mob の回も戻る。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = "td_hostile", timeoutTicks = 60)
    public static void hostilesClearedAndBlockedDuringInvasion(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null) {
            return;
        }
        Mob husk = placeRaw(s, EntityType.HUSK, new Vec3(1.5D, 2.0D, 1.5D));
        Mob named = placeRaw(s, EntityType.HUSK, new Vec3(5.5D, 2.0D, 1.5D));
        named.setCustomName(net.minecraft.network.chat.Component.literal("pet"));
        Mob g1 = placeRaw(s, ModEntities.PARASITIZED_ZOMBIE.get(), new Vec3(1.5D, 2.0D, 5.5D));
        Mob g3 = placeRaw(s, ModEntities.PETROLACOSAURUS.get(), new Vec3(0.5D, 2.0D, 3.5D));
        Mob cow = placeRaw(s, EntityType.COW, new Vec3(5.5D, 2.0D, 5.5D));
        Mob g2 = placeRaw(s, ModEntities.ARTHROPLEURA.get(), new Vec3(6.5D, 2.0D, 3.5D));
        Mob g4 = placeRaw(s, ModEntities.ARBORA.get(), new Vec3(3.5D, 2.0D, 5.5D));
        for (EntityType<?> fixture : List.of(ModEntities.HIVE_NODE.get())) {
            if (TdHostiles.isClearableType(fixture)) {
                s.fail("巨大樹の仕掛けを敵対 Mob と判定した: " + EntityType.getKey(fixture));
                return;
            }
        }
        // ワールドの生成で置かれた形(永続・拾った物なし。エルダーガーディアン・洋館の邪悪な村人と同じ)。
        Mob worldPlaced = placeRaw(s, EntityType.HUSK, new Vec3(6.5D, 2.0D, 6.5D));
        worldPlaced.setPersistenceRequired();
        // 拾った剣を持つ(拾うとバニラは永続にし、落ちる確率を 2 にする)。
        Mob looter = placeRaw(s, EntityType.HUSK, new Vec3(0.5D, 2.0D, 6.5D));
        looter.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND,
                new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.IRON_SWORD));
        looter.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2.0F);
        looter.setPersistenceRequired();
        Vec3 looterAt = looter.position();
        net.minecraft.world.entity.monster.ZombieVillager curing = EntityType.ZOMBIE_VILLAGER.create(s.level);
        if (curing == null) {
            s.fail("村人ゾンビを作れない");
            return;
        }
        Vec3 curingAt = helper.absoluteVec(new Vec3(6.5D, 2.0D, 0.5D));
        curing.moveTo(curingAt.x, curingAt.y, curingAt.z, 0.0F, 0.0F);
        curing.setNoAi(true);
        CompoundTag curingTag = curing.saveWithoutId(new CompoundTag());
        curingTag.putInt("ConversionTime", 6000); // 読み込みで治療が始まる(金のリンゴを与えたのと同じ状態)
        curing.load(curingTag);
        s.level.addFreshEntity(curing);
        s.spawned.add(curing);
        if (!curing.isConverting()) {
            s.fail("テストの準備: 村人ゾンビの治療が始まらない");
            return;
        }
        Mob eggBefore = EntityType.HUSK.spawn(s.level, s.abs(new BlockPos(3, 2, 0)), net.minecraft.world.entity.MobSpawnType.SPAWN_EGG);
        if (eggBefore == null) {
            s.fail("テストの準備: スポーンエッグの湧き方のハスクを置けない");
            return;
        }
        s.spawned.add(eggBefore);
        eggBefore.setNoAi(true);
        if (!s.start(target, 0.0D)) {
            return;
        }
        for (Mob gone : List.of(husk, g1, g2, g3, looter)) {
            if (!gone.isRemoved()) {
                s.fail("侵攻の始まりに消えない: " + EntityType.getKey(gone.getType())
                        + (gone == looter ? "(拾った剣を持つ)" : ""));
                return;
            }
        }
        if (!TdHostiles.inZone(s.level, target.getX() + 5000.5D, target.getZ() - 5000.5D)) {
            s.fail("既定の範囲(0 = ディメンション全体)なのに、ターゲットから 5000 先が範囲の外");
            return;
        }
        if (!TdTestAccess.monsterSpawningOff(s.level, false)) {
            s.fail("ディメンション全体の侵攻が始まったのに、バニラの自然湧きの敵対 Mob の回が止まっていない");
            return;
        }
        if (s.level.getEntitiesOfClass(ItemEntity.class, new AABB(looterAt, looterAt).inflate(2.0D),
                e -> e.getItem().is(net.minecraft.world.item.Items.IRON_SWORD)).isEmpty()) {
            s.fail("消したハスクが拾っていた剣を落とさない");
            return;
        }
        for (Mob kept : List.of(named, cow, g4, worldPlaced, curing, eggBefore)) {
            if (kept.isRemoved()) {
                s.fail("侵攻の始まりに消えた(残すはず): " + EntityType.getKey(kept.getType())
                        + (kept.hasCustomName() ? "(名札付き)" : "")
                        + (kept == worldPlaced ? "(ワールドの生成で置かれた形)" : "")
                        + (kept == eggBefore ? "(始まる前にスポーンエッグで置いた)" : ""));
                return;
            }
        }
        Mob late = EntityType.HUSK.create(s.level);
        if (late == null) {
            s.fail("ハスクを作れない");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5D, 2.0D, 5.5D));
        late.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        late.setNoAi(true);
        if (s.level.addFreshEntity(late)) {
            s.spawned.add(late);
            s.fail("侵攻中に、ふつうに足したハスクが入った");
            return;
        }
        Mob egg = EntityType.HUSK.spawn(s.level, s.abs(new BlockPos(1, 2, 5)), net.minecraft.world.entity.MobSpawnType.SPAWN_EGG);
        if (egg != null) {
            s.spawned.add(egg);
            egg.setNoAi(true);
        }
        if (egg == null || egg.isRemoved()) {
            s.fail("侵攻中に、スポーンエッグの湧き方のハスクが入らない");
            return;
        }
        TdTable.Json narrow = new TdTable.Json();
        narrow.hostileClearRadius = 2.0D;
        s.useTable(TdTable.fromJson(narrow));
        Mob outside = placeRaw(s, EntityType.HUSK, new Vec3(0.5D, 2.0D, 0.5D));
        if (outside.isRemoved() || s.level.getEntity(outside.getUUID()) != outside) {
            s.fail("範囲(2)の外、ターゲットから 4 ブロックのハスクが入らない");
            return;
        }
        s.useTable(TdTable.DEFAULT);
        TdTicker.forceStop(s.server);
        Mob after = placeRaw(s, EntityType.HUSK, new Vec3(5.5D, 2.0D, 3.5D));
        if (after.isRemoved() || s.level.getEntity(after.getUUID()) != after) {
            s.fail("侵攻を止めたあと、ふつうに足したハスクが入らない");
            return;
        }
        if (TdTestAccess.monsterSpawningOff(s.level, true)) {
            s.fail("侵攻を止めたあと、バニラの自然湧きの敵対 Mob の回が戻らない");
            return;
        }
        s.pass();
    }

    /**
     * <b>行進中の群れをクライアントへ送る形</b>({@code TdHordeSync#views}、遠景の身代わりの 2 段目。ユーザー指定 2026-09-25
     * 「大群が迫ってくる演出を TD に組み込みたい」)。ターゲットの東 100 ブロックの群れ(アルボラ 5、速さ 0.2)と、止まる輪の内側
     * (南 10 ブロック)の群れ(アルボラ 3)。
     * <ul>
     *     <li>遠い群れ: 向きはターゲットへ(西 = (−1, 0))、速さ 0.2、進める距離 = 100 − 止まる輪、種の頭数 = アルボラ 5。</li>
     *     <li>輪の内側の群れ: 向きはターゲットへ(北 = (0, −1))、速さ 0・進める距離 0(もう歩かない)。</li>
     *     <li>一時停止中はどちらも速さ 0。</li>
     *     <li>パケットに書いて読み戻すと同じ中身(種はレジストリの番号で送る)。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_horde", timeoutTicks = 40)
    public static void hordeViewsFollowVirtualSwarms(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        TdTestAccess.addVirtualSwarm(s.server, new Vec3(tx + 100.0D, target.getY(), tz), ModEntities.ARBORA.get(), 5, 0.2D);
        TdTestAccess.addVirtualSwarm(s.server, new Vec3(tx, target.getY(), tz + 10.0D), ModEntities.ARBORA.get(), 3, 0.2D);
        List<com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm> views = TdTestAccess.hordeViews(s.server);
        if (views.size() != 2) {
            s.fail("送る群れの数が " + views.size() + "(2 のはず)");
            return;
        }
        com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm far = views.get(0);
        com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm near = views.get(1);
        double hold = TdTestAccess.holdRing();
        if (Math.abs(far.dirX() + 1.0F) > 1.0E-4F || Math.abs(far.dirZ()) > 1.0E-4F || Math.abs(far.speed() - 0.2F) > 1.0E-5F
                || Math.abs(far.travel() - (100.0D - hold)) > 1.0E-3D) {
            s.fail("遠い群れ: 向き (" + far.dirX() + ", " + far.dirZ() + ")・速さ " + far.speed() + "・進める距離 " + far.travel()
                    + "((−1, 0)・0.2・" + (100.0D - hold) + " のはず)");
            return;
        }
        if (far.counts().size() != 1 || far.counts().get(0).type() != ModEntities.ARBORA.get() || far.counts().get(0).count() != 5) {
            s.fail("遠い群れの顔ぶれ: " + far.counts());
            return;
        }
        if (Math.abs(near.dirX()) > 1.0E-4F || Math.abs(near.dirZ() + 1.0F) > 1.0E-4F || near.speed() != 0.0F || near.travel() != 0.0F) {
            s.fail("止まる輪の内側の群れ: 向き (" + near.dirX() + ", " + near.dirZ() + ")・速さ " + near.speed() + "・進める距離 "
                    + near.travel() + "((0, −1)・0・0 のはず)");
            return;
        }
        io.netty.buffer.ByteBuf raw = io.netty.buffer.Unpooled.buffer();
        try {
            net.minecraft.network.RegistryFriendlyByteBuf buf =
                    new net.minecraft.network.RegistryFriendlyByteBuf(raw, s.level.registryAccess());
            com.yayuy.overgrownvegetation.network.TdHordePayload sent = new com.yayuy.overgrownvegetation.network.TdHordePayload(views);
            com.yayuy.overgrownvegetation.network.TdHordePayload.STREAM_CODEC.encode(buf, sent);
            com.yayuy.overgrownvegetation.network.TdHordePayload back =
                    com.yayuy.overgrownvegetation.network.TdHordePayload.STREAM_CODEC.decode(buf);
            if (!back.equals(sent) || buf.readableBytes() != 0) {
                s.fail("パケットに書いて読み戻すと中身が変わる: " + back + "(" + sent + " のはず、残り " + buf.readableBytes() + " バイト)");
                return;
            }
        } finally {
            raw.release();
        }
        TdTestAccess.setPausedSince(s.server, s.level.getGameTime());
        for (com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm paused : TdTestAccess.hordeViews(s.server)) {
            if (paused.speed() != 0.0F || paused.travel() != 0.0F) {
                s.fail("一時停止中なのに群れが進む: 速さ " + paused.speed() + "・進める距離 " + paused.travel());
                return;
            }
        }
        s.pass();
    }

    /**
     * 攻めてくる向きとヴェロフィルの上限(2026-09-25 ユーザー報告「攻めてくる方向がほとんど 1 つ」「ヴェロフィルが多すぎる」、
     * 同日の runClient 4 回目のユーザー指定「8 方向だけでなく本当に全方位から攻めてくるように」)。
     * <ul>
     *     <li>空いている向き({@code TdTicker#pickAngle})は、いちばん大きな隙間の真ん中の半分から選ぶ: 0・90・180 度に群れ → 225〜315 度、
     *     350・10 度(0 度の継ぎ目をまたぐ)→ 95〜265 度(何度引いても)。</li>
     *     <li>何も無い所から 1 つずつ足すと、8 つ足した時点で隣り合う 2 つの向きの間はどこも 90 度を超えない(扇の数に縛られない)。</li>
     *     <li>侵攻の仮想の群れが 0・90・180 度にいれば、次の群れは 225〜315 度の、ターゲットから virtualSpawnMin〜Max の所に生まれる。</li>
     *     <li>同時に出ているヴェロフィルは行進中の空の群れの頭数も数える。空の群れを禁じた顔ぶれは、空の割合 1 でも地上になる。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_spread", timeoutTicks = 40)
    public static void virtualSwarmsComeFromEveryDirectionAndAirIsCapped(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double deg = Math.PI / 180.0D;
        net.minecraft.util.RandomSource random = net.minecraft.util.RandomSource.create(20260925L);
        for (int i = 0; i < 20; i++) {
            double a = TdTestAccess.pickAngle(List.of(0.0D, 90.0D * deg, 180.0D * deg), random);
            if (a < 225.0D * deg - 1.0E-9D || a > 315.0D * deg + 1.0E-9D) {
                s.fail("0・90・180 度に群れがいるのに次の向きが " + Math.toDegrees(a) + " 度(225〜315 度のはず)");
                return;
            }
            double w = TdTestAccess.pickAngle(List.of(350.0D * deg, -350.0D * deg), random);
            if (w < 95.0D * deg - 1.0E-9D || w > 265.0D * deg + 1.0E-9D) {
                s.fail("350・10 度に群れがいるのに次の向きが " + Math.toDegrees(w) + " 度(95〜265 度のはず)");
                return;
            }
        }
        List<Double> taken = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            taken.add(TdTestAccess.pickAngle(taken, random));
        }
        if (widestGapDegrees(taken) > 90.0D + 1.0E-6D) {
            s.fail("何も無い所から 8 つ足したのに、隣り合う向きの間が " + widestGapDegrees(taken) + " 度開いている(90 度以下のはず)");
            return;
        }
        for (double a : new double[]{0.0D, 90.0D, 180.0D}) {
            TdTestAccess.addVirtualSwarm(s.server, new Vec3(tx + Math.cos(a * deg) * 150.0D, target.getY(), tz + Math.sin(a * deg) * 150.0D),
                    ModEntities.ARBORA.get(), 4, 0.0D);
        }
        TdTable table = DesignData.td();
        for (int i = 0; i < 8; i++) {
            double[] xz = TdTestAccess.ringPoint(s.server, random);
            double angle = Math.toDegrees(Math.atan2(xz[1] - tz, xz[0] - tx));
            angle = angle < 0.0D ? angle + 360.0D : angle;
            double r = Math.sqrt((xz[0] - tx) * (xz[0] - tx) + (xz[1] - tz) * (xz[1] - tz));
            if (angle < 225.0D - 1.0E-6D || angle > 315.0D + 1.0E-6D
                    || r < table.virtualSpawnMin() - 1.0E-6D || r > table.virtualSpawnMax() + 1.0E-6D) {
                s.fail("0・90・180 度に群れがいるのに、次の群れが " + angle + " 度・距離 " + r + " に生まれる(225〜315 度・"
                        + table.virtualSpawnMin() + "〜" + table.virtualSpawnMax() + " のはず)");
                return;
            }
        }
        TdTestAccess.clearVirtual(s.server);
        if (TdTestAccess.airAttackers(s.level) != 0) {
            s.fail("空の群れが無いのにヴェロフィルを " + TdTestAccess.airAttackers(s.level) + " 体と数えた");
            return;
        }
        TdTestAccess.addAirVirtualSwarm(s.server, new Vec3(tx + 150.0D, target.getY() + 16.0D, tz), ModEntities.VELOPHYLL.get(), 5);
        TdTestAccess.addAirVirtualSwarm(s.server, new Vec3(tx - 150.0D, target.getY() + 16.0D, tz), ModEntities.VELOPHYLL.get(), 4);
        if (TdTestAccess.airAttackers(s.level) != 9) {
            s.fail("行進中の空の群れ 5 + 4 体を " + TdTestAccess.airAttackers(s.level) + " 体と数えた(9 のはず)");
            return;
        }
        com.yayuy.overgrownvegetation.swarm.SwarmTable swarms = DesignData.swarm();
        for (int i = 0; i < 10; i++) {
            Object[] blocked = TdTestAccess.rollRoster(7, rosterTable(1.0D, 0.0D, 0.0D, List.of()), swarms, random, false);
            if (blocked == null || blocked[0] != Swarm.Kind.GROUND) {
                s.fail("空の群れを禁じたのに地上にならない: " + describeRoster(blocked));
                return;
            }
            Object[] allowed = TdTestAccess.rollRoster(7, rosterTable(1.0D, 0.0D, 0.0D, List.of()), swarms, random, true);
            if (allowed == null || allowed[0] != Swarm.Kind.AIR) {
                s.fail("空の割合 1 で空の群れにならない: " + describeRoster(allowed));
                return;
            }
        }
        s.pass();
    }

    /** 向き(ラジアン)の並びの、隣り合う 2 つの間のいちばん大きな開き(度。0 度の継ぎ目をまたぐ間も)。空なら 360。 */
    private static double widestGapDegrees(List<Double> angles) {
        if (angles.isEmpty()) {
            return 360.0D;
        }
        double[] a = new double[angles.size()];
        for (int i = 0; i < a.length; i++) {
            double d = Math.toDegrees(angles.get(i)) % 360.0D;
            a[i] = d < 0.0D ? d + 360.0D : d;
        }
        java.util.Arrays.sort(a);
        double widest = 0.0D;
        for (int i = 0; i < a.length; i++) {
            double next = i + 1 < a.length ? a[i + 1] : a[0] + 360.0D;
            widest = Math.max(widest, next - a[i]);
        }
        return widest;
    }

    /**
     * <b>侵攻の始めの一斉投入</b>(2026-09-25 の runClient 4 回目のユーザー指定「TD が始まってすぐは一気に上限までの mob を湧かせて
     * 攻めてくるように」)。上限 60・同時に行進する群れ 2・控え 0・実体化の距離 200(待つ輪はテスト場から 196 ブロック = 読み込まれて
     * おらず実体にならないので、作った群れをそのまま数えられる)・予算は上限いっぱいを買える額({@link #priciestTdCost} × (上限 + 1))で
     * 侵攻を始め、最初の帳簿を待つ。
     * <ul>
     *     <li>仮想の群れが 2 つを超えて作られる(同時に行進する群れの数は見ない)。頭数の合計は上限 60 以下で、残りは 1 群れぶんより少ない。</li>
     *     <li>どれも実体化の輪の上(ターゲットから 196〜199)に生まれる。4 つ以上あれば、隣り合う向きの間はどこも 160 度を超えない(全方位)。</li>
     *     <li>帳簿を待たずに毎 tick 実体にする期限が、{@value TdTicker#OPENING_BURST_TICKS} tick 先までに立つ。</li>
     *     <li>そのあいだにテスト場の中へ足した 12 の群れ(アルボラ 1 体ずつ)は、1 tick に {@value TdTicker#OPENING_PER_TICK} 群れずつ実体になり、
     *     6 tick のうちに全部そろう(ふつうの帳簿 1 回 8 群れなら 20 tick かかる)。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_opening", timeoutTicks = 120)
    public static void invasionOpensWithFullWaveOnMaterializeRing(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        int cap = 60;
        TdTable.Json json = new TdTable.Json();
        json.concurrentCap = cap;
        json.maxVirtualSwarms = 2;
        json.virtualReserve = 0;
        json.materializeRadius = 200.0D;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(1, 2, 1));
        // 1 体の値段は種ごと(mobs.json の tdCost)。予算が先に尽きると上限に届く前に一斉投入が止まるので、
        // いちばん高い種ばかりでも上限 + 1 体ぶん買える額にする(止まるのは「次の群れが残りの空きに入らない」ときだけになる)。
        double budget = priciestTdCost() * (cap + 1);
        if (s.target(target) == null || !s.start(target, budget)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String id = s.eventId();
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double hold = TdTestAccess.holdRing();
        com.yayuy.overgrownvegetation.swarm.SwarmTable swarms = DesignData.swarm();
        // 1 群れの最大 = 編成の最大 + 護衛(グループ 5〜9 に 1 体ずつまで)。
        int biggest = Math.max(swarms.maxSize(Swarm.Kind.GROUND), swarms.maxSize(Swarm.Kind.AIR)) + 5;
        int arenaSwarms = 12;
        long[] addedAt = {-1L};
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (addedAt[0] < 0L) {
                Object[] opening = TdTestAccess.opening(s.server);
                if (!(Boolean) opening[0]) {
                    if (helper.getTick() - begin > TWO_BOOKKEEPINGS) {
                        s.fail("帳簿を 2 回待っても始めの一斉投入が来ない: " + describe(e));
                    }
                    return;
                }
                List<TdData.VirtualSwarm> made = e.virtualSwarms();
                int heads = e.virtualSize();
                if (made.size() <= 2 || heads > cap || heads <= cap - biggest) {
                    s.fail("一斉投入の群れ " + made.size() + " 個・" + heads + " 体(2 個より多く、" + (cap - biggest) + " 体より多く "
                            + cap + " 体以下のはず): " + describe(e));
                    return;
                }
                List<Double> angles = new ArrayList<>();
                for (TdData.VirtualSwarm v : made) {
                    double r = Math.sqrt((v.x() - tx) * (v.x() - tx) + (v.z() - tz) * (v.z() - tz));
                    if (r < hold - 1.0E-6D || r > 200.0D - 1.0D + 1.0E-6D) {
                        s.fail("一斉投入の群れがターゲットから " + r + " に生まれた(実体化の輪 " + hold + "〜199 のはず)");
                        return;
                    }
                    angles.add(Math.atan2(v.z() - tz, v.x() - tx));
                }
                if (made.size() >= 4 && widestGapDegrees(angles) > 160.0D) {
                    s.fail("一斉投入の " + made.size() + " 群れの向きが偏っている(隣り合う向きの間が " + widestGapDegrees(angles)
                            + " 度。160 度以下のはず)");
                    return;
                }
                long burstUntil = (Long) opening[1];
                long now = s.level.getGameTime();
                if (burstUntil <= now || burstUntil > now + TdTicker.OPENING_BURST_TICKS) {
                    s.fail("毎 tick 実体にする期限が " + (burstUntil - now) + " tick 先(1〜" + TdTicker.OPENING_BURST_TICKS + " のはず)");
                    return;
                }
                Vec3 inside = helper.absoluteVec(new Vec3(4.5D, 2.0D, 4.5D));
                for (int i = 0; i < arenaSwarms; i++) {
                    TdTestAccess.addVirtualSwarm(s.server, inside, ModEntities.ARBORA.get(), 1, 0.0D);
                }
                addedAt[0] = helper.getTick();
                return;
            }
            List<Mob> arboras = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(),
                    m -> m.getType() == ModEntities.ARBORA.get() && EventMobs.has(m, EventMobs.Kind.TD, id));
            long waited = helper.getTick() - addedAt[0];
            if (arboras.size() >= arenaSwarms) {
                s.spawned.addAll(arboras);
                s.pass();
            } else if (waited > 6L) {
                s.spawned.addAll(arboras);
                s.fail("一斉投入の直後なのに、テスト場の 12 の群れが " + waited + " tick で " + arboras.size()
                        + " 体しか実体にならない(1 tick に " + TdTicker.OPENING_PER_TICK + " 群れのはず): " + describe(e));
            }
        });
    }

    /**
     * <b>群れは見える範囲の外で生まれる</b>(2026-09-26 のユーザー指定「mob が絵として湧く場所をもっと遠くから。外周の霧の中から
     * 出てくる感じに」。{@code TdTicker#spawnDistance})。見ている人(位置と見える距離)を決め、乱数の向きで 400 回ずつ:
     * <ol>
     *     <li>見ている人なし・押し出しが無効(負)→ 前の輪 128〜192 のまま。</li>
     *     <li>ターゲットに見える距離 150 の人 → 150 + 16 以上 192 以下(輪がもう範囲の外なら輪のまま)。</li>
     *     <li>ターゲットから東へ 100 の人(見える距離 150)→ その人から 150 + 16 以上。</li>
     *     <li>2 人の円が向きの線の上でつながっている(ターゲットに 100、東へ 230 に 100)→ どちらからも 100 + 16 以上
     *     (1 人目の円を越えた先が 2 人目の円の中なら、そこも越える)。</li>
     *     <li>見える距離がとても長い(1000)→ 上限の 480 で止まる。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void swarmsAreBornOutOfSight(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260926L);
        double tx = 0.5D;
        double tz = 0.5D;
        double min = 128.0D;
        double max = 192.0D;
        double margin = 16.0D;
        double farthest = 480.0D;
        double eps = 1.0E-6D;
        TdSight.Viewer center = new TdSight.Viewer(tx, tz, 150.0D);
        TdSight.Viewer east = new TdSight.Viewer(tx + 100.0D, tz, 150.0D);
        List<TdSight.Viewer> chain = List.of(new TdSight.Viewer(tx, tz, 100.0D), new TdSight.Viewer(tx + 230.0D, tz, 100.0D));
        for (int i = 0; i < 400; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            double none = TdTestAccess.spawnDistance(tx, tz, angle, min, max, List.of(), margin, farthest, random);
            double off = TdTestAccess.spawnDistance(tx, tz, angle, min, max, List.of(center), -1.0D, farthest, random);
            if (none < min - eps || none > max + eps || off < min - eps || off > max + eps) {
                helper.fail("見ている人なし(" + none + ")・押し出し無効(" + off + ")なのに輪 " + min + "〜" + max + " の外");
                return;
            }
            double d = TdTestAccess.spawnDistance(tx, tz, angle, min, max, List.of(center), margin, farthest, random);
            if (d < 150.0D + margin - eps || d > max + eps) {
                helper.fail("ターゲットに見える距離 150 の人がいるのに " + d + "(" + (150.0D + margin) + "〜" + max + " のはず)");
                return;
            }
            d = TdTestAccess.spawnDistance(tx, tz, angle, min, max, List.of(east), margin, farthest, random);
            double fromEast = Math.hypot(tx + ux * d - east.x(), tz + uz * d - east.z());
            if (fromEast < 150.0D + margin - eps) {
                helper.fail("東へ 100 の人(見える距離 150)から " + fromEast + " の所で生まれた(向き "
                        + Math.round(Math.toDegrees(angle)) + " 度、ターゲットから " + d + ")");
                return;
            }
            d = TdTestAccess.spawnDistance(tx, tz, angle, min, max, chain, margin, farthest, random);
            for (TdSight.Viewer v : chain) {
                double from = Math.hypot(tx + ux * d - v.x(), tz + uz * d - v.z());
                if (from < v.sight() + margin - eps) {
                    helper.fail("つながった 2 人の円の中で生まれた: " + v + " から " + from + "(向き "
                            + Math.round(Math.toDegrees(angle)) + " 度、ターゲットから " + d + ")");
                    return;
                }
            }
            d = TdTestAccess.spawnDistance(tx, tz, angle, min, max, List.of(new TdSight.Viewer(tx, tz, 1000.0D)), margin,
                    farthest, random);
            if (Math.abs(d - farthest) > eps) {
                helper.fail("見える距離 1000 の人がいるとき上限 " + farthest + " で止まらない: " + d);
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>始めの一斉投入も霧の外で生まれ、着くまで毎 tick 実体にする</b>(2026-09-26)。ターゲットに見える距離 150 の人がいる
     * ことにして({@link TdTestAccess#setViewers}。GameTest のサーバーにはプレイヤーがいない)上限 30 で侵攻を始め、一斉投入の直後に:
     * <ul>
     *     <li>どの群れも、ターゲットから 150 + {@code virtualSpawnBeyondSight}〜その 2 倍(見える範囲の外)にいる(前は実体化の輪 92〜95)。</li>
     *     <li>帳簿を待たずに毎 tick 実体にする期限が、いちばん遅く着く群れが実体化の輪に着くまで + {@value TdTicker#OPENING_BURST_TICKS}
     *     tick 以上先。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_sight", timeoutTicks = 100)
    public static void openingWaveIsBornOutOfSight(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        int cap = 30;
        TdTable.Json json = new TdTable.Json();
        json.concurrentCap = cap;
        json.virtualReserve = 0;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double sight = 150.0D;
        TdTestAccess.setViewers(List.of(new TdSight.Viewer(tx, tz, sight)));
        if (s.target(target) == null || !s.start(target, priciestTdCost() * (cap + 1))) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        TdTable table = DesignData.td();
        double margin = table.virtualSpawnBeyondSight();
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            Object[] opening = TdTestAccess.opening(s.server);
            if (!(Boolean) opening[0]) {
                if (helper.getTick() - begin > 60L) {
                    s.fail("一斉投入が来ない: " + describe(TdData.get(s.server).live()));
                }
                return;
            }
            List<double[]> swarms = TdTestAccess.virtualPositions(s.server);
            if (swarms.isEmpty()) {
                s.fail("一斉投入の群れが無い: " + describe(TdData.get(s.server).live()));
                return;
            }
            long needed = 0L;
            for (double[] v : swarms) {
                double d = Math.hypot(v[0] - tx, v[1] - tz);
                if (d < sight + margin - 1.0E-6D || d > sight + 2.0D * margin + 1.0E-6D) {
                    s.fail("一斉投入の群れがターゲットから " + d + " で生まれた(見える距離 " + sight + " の外 "
                            + (sight + margin) + "〜" + (sight + 2.0D * margin) + " のはず)");
                    return;
                }
                needed = Math.max(needed, (long) Math.ceil((d - table.materializeRadius()) / v[2]));
            }
            long left = (Long) opening[1] - s.level.getGameTime();
            if (left < needed + TdTicker.OPENING_BURST_TICKS) {
                s.fail("毎 tick 実体にする期限があと " + left + " tick(いちばん遅い群れが着くまで " + needed + " + "
                        + TdTicker.OPENING_BURST_TICKS + " 以上のはず)");
                return;
            }
            s.pass();
        });
    }

    /**
     * <b>一方向の侵攻の群れの向き</b>(2026-09-26 のユーザー指定「北や西など一方向から攻めてくる場合も」。{@code TdTicker#pickAngleInFan})。
     * 北・西(角度が ±π をまたぐ)・北東の扇(± 30 度)それぞれで、扇の外の群れ 2 つを置いてから 12 回続けて選ぶ:
     * <ul>
     *     <li>どれも扇の中。最初の 1 つは扇の真ん中の半分(± 15 度)。</li>
     *     <li>12 回のあと、扇の中の隙間(両端を含む)はどれも 20 度より狭い(片寄らずに扇いっぱいの前線になる)。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void fanPickStaysInsideAndSpreads(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260926L);
        double half = Math.toRadians(30.0D);
        for (TdDirection d : List.of(TdDirection.NORTH, TdDirection.WEST, TdDirection.NORTHEAST)) {
            List<Double> taken = new ArrayList<>();
            taken.add(d.bearing() + Math.PI);
            taken.add(d.bearing() + Math.PI * 0.5D);
            List<Double> offsets = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                double a = TdTestAccess.pickAngleInFan(taken, d.bearing(), half, random);
                double off = angleDiff(d.bearing(), a);
                if (Math.abs(off) > half + 1.0E-9D || i == 0 && Math.abs(off) > half * 0.5D + 1.0E-9D) {
                    helper.fail(d.key() + " の扇の " + (i == 0 ? "真ん中の半分" : "中") + "から外れた: " + Math.toDegrees(off) + " 度");
                    return;
                }
                taken.add(a);
                offsets.add(off);
            }
            offsets.sort(null);
            double prev = -half;
            double widest = 0.0D;
            for (int i = 0; i <= offsets.size(); i++) {
                double next = i < offsets.size() ? offsets.get(i) : half;
                widest = Math.max(widest, next - prev);
                prev = next;
            }
            if (widest >= Math.toRadians(20.0D)) {
                helper.fail(d.key() + " の扇に " + Math.toDegrees(widest) + " 度の隙間が残る(12 群れで 20 度より狭いはず)");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>攻めてくる向きの振り方</b>(2026-09-26 のユーザー指定「大体全周囲 4 割、特定の方向からが 6 割。過繁茂したバイオームがある
     * 方向からになりやすいように」。{@code TdTicker#rollDirection} / {@code overgrownSectors})。決まった種の乱数で 4000 回ずつ:
     * <ol>
     *     <li>過繁茂が北だけ・一方向 0.6・寄せ 0.75 → 全方位 4 割(± 3%)、一方向のうち北 0.75 + 0.25 ÷ 8 ≈ 78%(± 3%)、ほかの 7 方位は
     *     それぞれ約 3%(± 1.5%)。</li>
     *     <li>過繁茂が無い・一方向 1.0 → 8 方位がそれぞれ 1/8(± 2.5%)。一方向 0 → いつも全方位。</li>
     *     <li>過繁茂の数え: ターゲットの真北に並ぶチャンクは北に数え、ターゲットのチャンク自身と範囲(192)の外は数えない。
     *     その数で寄せ 1.0 なら、いつも北。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void directionRollFollowsShareAndOvergrowth(GameTestHelper helper) {
        RandomSource random = RandomSource.create(20260926L);
        int rolls = 4000;
        double[] northOnly = new double[8];
        northOnly[TdTestAccess.sectorIndex(TdDirection.NORTH)] = 100.0D;
        int[] hits = new int[TdDirection.values().length];
        for (int i = 0; i < rolls; i++) {
            hits[TdTestAccess.rollDirection(northOnly, 0.6D, 0.75D, random).ordinal()]++;
        }
        double around = hits[TdDirection.AROUND.ordinal()] / (double) rolls;
        int directional = rolls - hits[TdDirection.AROUND.ordinal()];
        if (Math.abs(around - 0.4D) > 0.03D) {
            helper.fail("全方位が " + around + "(4 割のはず)");
            return;
        }
        for (TdDirection d : TdDirection.values()) {
            if (d.around()) {
                continue;
            }
            double share = hits[d.ordinal()] / (double) directional;
            double want = d == TdDirection.NORTH ? 0.75D + 0.25D / 8.0D : 0.25D / 8.0D;
            if (Math.abs(share - want) > (d == TdDirection.NORTH ? 0.03D : 0.015D)) {
                helper.fail("過繁茂が北だけのとき " + d.key() + " が一方向の " + share + "(" + want + " のはず)");
                return;
            }
        }
        java.util.Arrays.fill(hits, 0);
        for (int i = 0; i < rolls; i++) {
            hits[TdTestAccess.rollDirection(new double[8], 1.0D, 0.75D, random).ordinal()]++;
        }
        for (TdDirection d : TdDirection.values()) {
            double share = hits[d.ordinal()] / (double) rolls;
            double want = d.around() ? 0.0D : 1.0D / 8.0D;
            if (Math.abs(share - want) > 0.025D) {
                helper.fail("過繁茂が無いとき " + d.key() + " が " + share + "(" + want + " のはず)");
                return;
            }
        }
        for (int i = 0; i < 200; i++) {
            if (TdTestAccess.rollDirection(northOnly, 0.0D, 0.75D, random) != TdDirection.AROUND) {
                helper.fail("一方向の割合 0 なのに一方向になった");
                return;
            }
        }
        // チャンク (0, 0) の真ん中のターゲット。真北の 12 チャンク(192 まで)だけ数え、ターゲットのチャンクと遠いチャンクは数えない。
        double[] sectors = TdTestAccess.overgrownSectors(8.0D, 8.0D, 192.0D, c -> c.x == 0 && c.z <= 0 || c.x == 20 && c.z == -20);
        for (TdDirection d : TdDirection.values()) {
            if (d.around()) {
                continue;
            }
            double want = d == TdDirection.NORTH ? 12.0D : 0.0D;
            if (sectors[TdTestAccess.sectorIndex(d)] != want) {
                helper.fail("過繁茂の数え: " + d.key() + " が " + sectors[TdTestAccess.sectorIndex(d)] + "(" + want + " のはず): "
                        + java.util.Arrays.toString(sectors));
                return;
            }
        }
        for (int i = 0; i < 100; i++) {
            if (TdTestAccess.rollDirection(sectors, 1.0D, 1.0D, random) != TdDirection.NORTH) {
                helper.fail("過繁茂が北だけ・寄せ 1.0 なのに北以外になった");
                return;
            }
        }
        helper.succeed();
    }

    /**
     * <b>コマンドで向きを決めて始める</b>(2026-09-26 のユーザー指定「特定の方向から攻めてくるのをコマンドで指定できるように。
     * 例えば /ov td start north」)。本物のコマンド {@code /ov td start north <予算>} をテスト場のターゲットのそばで打ち(上限 30):
     * <ul>
     *     <li>侵攻が始まり、向きは北、予算はコマンドの値(世界から切り出さない = 払い戻しなし)。</li>
     *     <li>始めの一斉投入の群れはどれも北の扇(± {@code directionalSpread})の中。</li>
     *     <li>置けなかった群れが輪に沿って横へ歩くとき、扇の両端(± 29 度)にいる群れの行き先も扇の中(外へ向いたら向きを返す)。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_direction", timeoutTicks = 100)
    public static void commandStartsInvasionFromTheNorth(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        int cap = 30;
        TdTable.Json json = new TdTable.Json();
        json.concurrentCap = cap;
        json.virtualReserve = 0;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        long budget = (long) Math.ceil(priciestTdCost() * (cap + 1));
        // 予算を指定した開始は /ov td start <方向> <予算> だったが、2026-10-10 にコマンドを整理して予算の指定を消したので、同じ処理を直接呼ぶ。
        TdTicker.forceStart(s.level, target, (double) budget, com.yayuy.overgrownvegetation.td.TdDirection.NORTH);
        TdData.Event started = TdData.get(s.server).active();
        if (started == null) {
            s.fail("北から予算 " + budget + " で侵攻が始まらない(" + TdData.get(s.server).lastOutcome() + ")");
            return;
        }
        if (started.direction() != TdDirection.NORTH || started.refundable() || Math.abs(started.budget() - budget) > BIOMASS_EPS) {
            s.fail("コマンドの向き・予算にならない: 向き " + started.direction().key() + " / 予算 " + started.budget()
                    + " / 払い戻し " + started.refundable());
            return;
        }
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double north = TdDirection.NORTH.bearing();
        double half = Math.toRadians(DesignData.td().directionalSpread());
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (!(Boolean) TdTestAccess.opening(s.server)[0]) {
                if (helper.getTick() - begin > 60L) {
                    s.fail("一斉投入が来ない: " + describe(TdData.get(s.server).live()));
                }
                return;
            }
            List<double[]> swarms = TdTestAccess.virtualPositions(s.server);
            if (swarms.isEmpty()) {
                s.fail("一斉投入の群れが無い");
                return;
            }
            for (double[] v : swarms) {
                double off = angleDiff(north, Math.atan2(v[1] - tz, v[0] - tx));
                if (Math.abs(off) > half + 1.0E-6D) {
                    s.fail("北の侵攻の群れが北の扇(± " + Math.toDegrees(half) + " 度)の外: " + Math.toDegrees(off) + " 度");
                    return;
                }
            }
            for (int i = 0; i < 20; i++) {
                double edge = north + (i % 2 == 0 ? 1.0D : -1.0D) * (half - Math.toRadians(1.0D));
                TdTestAccess.addVirtualSwarm(s.server, new Vec3(tx + Math.cos(edge) * 150.0D, target.getY(),
                        tz + Math.sin(edge) * 150.0D), ModEntities.ARBORA.get(), 1, 0.0D);
                int index = TdTestAccess.virtualPositions(s.server).size() - 1;
                TdTestAccess.startSidestep(s.server, index, RandomSource.create(i));
                double off = angleDiff(north, TdTestAccess.sidestepAngle(s.server, index));
                if (Math.abs(off) > half + 1.0E-6D) {
                    s.fail("扇の端(" + Math.round(Math.toDegrees(edge - north)) + " 度)の群れが扇の外へ横歩きする: "
                            + Math.toDegrees(off) + " 度");
                    return;
                }
            }
            s.pass();
        });
    }

    /** 角度 {@code from} から {@code to} へ回る量(ラジアン、−π〜π)。 */
    private static double angleDiff(double from, double to) {
        double d = (to - from) % (Math.PI * 2.0D);
        if (d > Math.PI) {
            d -= Math.PI * 2.0D;
        } else if (d <= -Math.PI) {
            d += Math.PI * 2.0D;
        }
        return d;
    }

    /**
     * <b>予告のときに攻めてくる向きを振り、侵攻もその向きで来る</b>(2026-10-08 順 2 の着手順 8。近くの基地の見張り台が予告で
     * 向きを知らせるため、知らせた向きと違う向きから来てはいけない)。予告から始まるのはこのテストだけ({@link TdTicker#forceWarning}
     * = コマンド {@code /ov td warn} と同じ口)。予告の長さ 20 tick・一方向の割合 0(振ると必ず全方位)にする:
     * <ol>
     *     <li>予告が始まった時点で向きはもう決まっている(全方位、決めた印あり)。</li>
     *     <li>向きを北西に書き換える(振り直しでは全方位しか出ない)。保存して読み直しても北西・決めた印あり。</li>
     *     <li>予告の時間が過ぎて侵攻に入っても北西のまま(侵攻に入るときに振り直さない)。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = "td_warning", timeoutTicks = 200)
    public static void warningRollsDirectionAndInvasionKeepsIt(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        TdTable.Json json = new TdTable.Json();
        json.warningTicks = 20;
        json.directionalShare = 0.0D;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        EcosystemData.get(s.server).setBiomass(40.0D);
        if (!TdTicker.forceWarning(s.level, target)) {
            s.fail("予告を始められない(進行中のイベントが残っている?)");
            return;
        }
        TdData.Event warning = TdData.get(s.server).live();
        if (warning == null || warning.state() != TdData.State.WARNING || !warning.directionChosen()
                || warning.direction() != TdDirection.AROUND) {
            s.fail("予告のときに向きが決まらない: " + describe(warning)
                    + (warning != null ? " / 向き " + warning.direction().key() + " / 決めた " + warning.directionChosen() : ""));
            return;
        }
        if (!TdTestAccess.setEventDirection(s.server, TdDirection.NORTHWEST)) {
            s.fail("予告中のイベントの向きを書き換えられない");
            return;
        }
        Object[] reloaded = TdTestAccess.reloadedDirection(s.server);
        if (reloaded == null || reloaded[0] != TdDirection.NORTHWEST || !Boolean.TRUE.equals(reloaded[1])) {
            s.fail("保存して読み直すと予告の向きが消える: " + java.util.Arrays.toString(reloaded));
            return;
        }
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event live = TdData.get(s.server).live();
            if (live == null) {
                s.fail("侵攻に入る前に終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (live.state() != TdData.State.ACTIVE) {
                if (helper.getTick() - begin > 120L) {
                    s.fail("予告が侵攻に入らない: " + describe(live));
                }
                return;
            }
            if (live.direction() != TdDirection.NORTHWEST) {
                s.fail("侵攻に入るときに向きを振り直した: " + live.direction().key() + "(予告では北西)");
                return;
            }
            s.pass();
        });
    }

    /**
     * <b>空の群れは絵の位置のまま空中に実体化する</b>(2026-09-25 の runClient 4 回目のユーザー報告「ヴェロフィルが mob に変換される
     * とき、絵は空中にいるのに mob になった瞬間地面に湧いてしまう」「絵の状態のとき、突然横に高速移動し始める」)。
     * <ol>
     *     <li>{@code SwarmSpawner#spawnPlaced}: テスト場の上 28 ブロックの決まった位置・向き(45 度)に、その座標のまま湧く(地面を探さない)。
     *     群れの一員で、侵攻の印が付く。</li>
     *     <li>テスト場の上 24 ブロックの空の仮想の群れ(ヴェロフィル 3 体、ターゲットはテスト場の隅)の湧く位置は、クライアントへ送る形
     *     (群れの番号・頭数・向き)から遠景の身代わりと同じ式({@code HordeFormation})で作った隊列と同じ x・z で、高さは群れの高さ −2 以上。</li>
     *     <li>次の帳簿で実体になる: 湧いたヴェロフィルはどれも予定の位置(±2)の空中にいる(テスト場の天井のバリアの上 3 ブロックより高い)。
     *     置けずに横歩きを始めることもない。</li>
     * </ol>
     * 隊列はテスト場のまわりのチャンクへはみ出すので、まわり 3 × 3 チャンクを強制読み込みにする。
     */
    @GameTest(template = TEMPLATE, batch = "td_air", timeoutTicks = 120)
    public static void airSwarmMaterializesInTheAirWhereItsSpritesWere(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(0, 2, 0));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String id = s.eventId();
        BlockPos middle = s.abs(new BlockPos(3, 0, 3));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                s.forceChunk(middle.offset(dx * 16, 0, dz * 16));
            }
        }
        // 1. 決まった位置に湧かせる口。
        Vec3 spot = helper.absoluteVec(new Vec3(3.5D, 28.0D, 3.5D));
        com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Result placed = com.yayuy.overgrownvegetation.swarm.SwarmSpawner.spawnPlaced(
                s.level, List.of(new com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement(ModEntities.VELOPHYLL.get(),
                        spot.x, spot.y, spot.z, 45.0F)), SwarmOrigin.TD, id, null);
        s.spawned.addAll(placed.members());
        if (placed.members().size() != 1) {
            s.fail("決まった位置にヴェロフィルが湧かない: " + placed.members().size() + " 体");
            return;
        }
        Mob one = placed.members().get(0);
        if (one.position().distanceTo(spot) > 1.0E-6D || Math.abs(net.minecraft.util.Mth.wrapDegrees(one.getYRot() - 45.0F)) > 1.0E-3F
                || !EventMobs.has(one, EventMobs.Kind.TD, id) || !one.hasData(ModAttachments.SWARM)) {
            s.fail("決まった位置・向きに湧かない / 印・所属が無い: " + one.position() + "・向き " + one.getYRot() + "(" + spot + "・45 のはず)");
            return;
        }
        one.discard();
        // 2. 空の仮想の群れの湧く位置 = 身代わりの隊列。
        Vec3 center = helper.absoluteVec(new Vec3(3.5D, 24.0D, 3.5D));
        TdTestAccess.addAirVirtualSwarm(s.server, center, ModEntities.VELOPHYLL.get(), 3);
        List<com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement> expected = TdTestAccess.airPlacements(s.level, 0);
        List<com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm> views = TdTestAccess.hordeViews(s.server);
        if (expected.size() != 3 || views.size() != 1) {
            s.fail("空の群れの湧く位置が " + expected.size() + " つ・送る群れが " + views.size() + " つ(3 と 1 のはず)");
            return;
        }
        com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm view = views.get(0);
        List<com.yayuy.overgrownvegetation.td.HordeFormation.Slot> slots =
                com.yayuy.overgrownvegetation.td.HordeFormation.slots(view.id(), view.counts());
        for (int i = 0; i < expected.size(); i++) {
            com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement p = expected.get(i);
            double sx = com.yayuy.overgrownvegetation.td.HordeFormation.x(view.x(), view.dirX(), view.dirZ(), slots.get(i));
            double sz = com.yayuy.overgrownvegetation.td.HordeFormation.z(view.z(), view.dirX(), view.dirZ(), slots.get(i));
            if (Math.abs(p.x() - sx) > 1.0E-4D || Math.abs(p.z() - sz) > 1.0E-4D || p.y() < center.y - 2.0D - 1.0E-6D) {
                s.fail("空の群れの " + i + " 体目の湧く位置 (" + p.x() + ", " + p.y() + ", " + p.z() + ") が身代わりの隊列 (" + sx
                        + ", ≥" + (center.y - 2.0D) + ", " + sz + ") と違う");
                return;
            }
        }
        double ceiling = helper.absoluteVec(new Vec3(0.0D, 9.0D, 0.0D)).y;
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (!e.virtualSwarms().isEmpty()) {
                if (!Double.isNaN(TdTestAccess.sidestepAngle(s.server, 0))) {
                    s.fail("空の群れを置けずに横歩きを始めた(空中に湧かせていない?)");
                } else if (helper.getTick() - begin > TWO_BOOKKEEPINGS) {
                    s.fail("帳簿を 2 回待っても空の群れが実体にならない: " + describe(e));
                }
                return;
            }
            List<Mob> flyers = s.level.getEntitiesOfClass(Mob.class, s.attackerSweep(),
                    m -> m.getType() == ModEntities.VELOPHYLL.get() && EventMobs.has(m, EventMobs.Kind.TD, id) && !m.isRemoved());
            s.spawned.addAll(flyers);
            if (flyers.isEmpty()) {
                s.fail("空の群れが仮想の一覧から消えたのに、ヴェロフィルが 1 体もいない");
                return;
            }
            for (Mob m : flyers) {
                double best = Double.MAX_VALUE;
                com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement near = null;
                for (com.yayuy.overgrownvegetation.swarm.SwarmSpawner.Placement p : expected) {
                    double d = m.position().distanceTo(new Vec3(p.x(), p.y(), p.z()));
                    if (d < best) {
                        best = d;
                        near = p;
                    }
                }
                if (near == null || best > 2.0D || m.getY() < ceiling + 3.0D) {
                    s.fail("ヴェロフィルが予定の位置の空中にいない: " + m.position() + "(いちばん近い予定 "
                            + (near == null ? "なし" : "(" + near.x() + ", " + near.y() + ", " + near.z() + ")") + "、天井 " + ceiling + ")");
                    return;
                }
            }
            s.pass();
        });
    }

    /**
     * <b>置けなかった群れは輪に沿って歩いて場所を変える</b>(瞬間移動しない。2026-09-25 の runClient 4 回目のユーザー報告
     * 「ヴェロフィルが絵の状態のとき、突然横に高速移動し始める」: 置けないたびに輪の上で 16〜32 ブロック瞬間移動させていたので、
     * 絵が 0.5 秒で横へ滑っていた)。実体化の距離 400(待つ輪 396。着いた先のチャンクが読み込まれておらず実体にならない)で、
     * ターゲットの真東、待つ輪ちょうどの所のアルボラ 3 体の群れ(速さ 1)に「置けなかった」を起こす。
     * <ul>
     *     <li>行き先は輪の上の 10〜20 度先。クライアントへ送る形は接線の向き(南北)・速さ 1・残りの弧の長さ。</li>
     *     <li>1 回の行進(5 tick)で動くのは 5 ブロックまで(前は 70〜140 ブロックを一度に動かしていた)。ターゲットからの距離は変わらない
     *     (待つ輪の内へ入らない)。</li>
     *     <li>着いたら行き先が消え、着いた向き = 行き先。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = "td_sidestep", timeoutTicks = 260)
    public static void unplaceableSwarmWalksAlongTheRingInsteadOfJumping(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        TdTable.Json json = new TdTable.Json();
        json.materializeRadius = 400.0D;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        double hold = TdTestAccess.holdRing();
        double speed = 1.0D;
        TdTestAccess.addVirtualSwarm(s.server, new Vec3(tx + hold, target.getY(), tz), ModEntities.ARBORA.get(), 3, speed);
        TdTestAccess.startSidestep(s.server, 0, net.minecraft.util.RandomSource.create(4L));
        double goal = TdTestAccess.sidestepAngle(s.server, 0);
        double deg = Math.PI / 180.0D;
        if (Double.isNaN(goal) || Math.abs(goal) < 10.0D * deg - 1.0E-9D || Math.abs(goal) > 20.0D * deg + 1.0E-9D) {
            s.fail("横歩きの行き先が " + Math.toDegrees(goal) + " 度(±10〜20 度のはず)");
            return;
        }
        List<com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm> views = TdTestAccess.hordeViews(s.server);
        if (views.size() != 1) {
            s.fail("送る群れが " + views.size() + " つ(1 のはず)");
            return;
        }
        com.yayuy.overgrownvegetation.network.TdHordePayload.Swarm view = views.get(0);
        float expectZ = goal > 0.0D ? 1.0F : -1.0F;
        if (Math.abs(view.dirX()) > 1.0E-4F || Math.abs(view.dirZ() - expectZ) > 1.0E-4F || Math.abs(view.speed() - speed) > 1.0E-5F
                || Math.abs(view.travel() - Math.abs(goal) * hold) > 1.0E-3D) {
            s.fail("横歩きの送る形: 向き (" + view.dirX() + ", " + view.dirZ() + ")・速さ " + view.speed() + "・残り " + view.travel()
                    + "((0, " + expectZ + ")・" + speed + "・" + Math.abs(goal) * hold + " のはず)");
            return;
        }
        double[] last = {tx + hold, tz};
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            List<TdData.VirtualSwarm> virtual = e.virtualSwarms();
            if (virtual.isEmpty()) {
                s.fail("横歩きの群れが消えた: " + describe(e));
                return;
            }
            TdData.VirtualSwarm v = virtual.get(0);
            double step = Math.sqrt((v.x() - last[0]) * (v.x() - last[0]) + (v.z() - last[1]) * (v.z() - last[1]));
            double r = Math.sqrt((v.x() - tx) * (v.x() - tx) + (v.z() - tz) * (v.z() - tz));
            if (step > speed * TdTestAccess.moveInterval() + 1.0E-6D || Math.abs(r - hold) > 1.0E-6D) {
                s.fail("横歩きで 1 度に " + step + " ブロック動いた・距離 " + r + "(" + speed * TdTestAccess.moveInterval()
                        + " 以下・距離 " + hold + " のはず)");
                return;
            }
            last[0] = v.x();
            last[1] = v.z();
            if (Double.isNaN(TdTestAccess.sidestepAngle(s.server, 0))) {
                double reached = Math.atan2(v.z() - tz, v.x() - tx);
                if (Math.abs(reached - goal) > 1.0E-6D) {
                    s.fail("横歩きの着いた向きが " + Math.toDegrees(reached) + " 度(" + Math.toDegrees(goal) + " 度のはず)");
                    return;
                }
                s.pass();
            } else if (helper.getTick() - begin > 240L) {
                s.fail("横歩きが終わらない(いま " + Math.toDegrees(Math.atan2(v.z() - tz, v.x() - tx)) + " 度、行き先 "
                        + Math.toDegrees(goal) + " 度)");
            }
        });
    }

    /** 印を付けずに(ふつうの湧かせ方で)置く NoAI の Mob。入れたかどうかは呼び出し側が見る。片付けの対象にする。 */
    private static <T extends Mob> T placeRaw(TdStage s, EntityType<T> type, Vec3 rel) {
        T mob = type.create(s.level);
        if (mob == null) {
            s.fail("作れない: " + EntityType.getKey(type));
            throw new IllegalStateException("unreachable");
        }
        Vec3 abs = s.helper.absoluteVec(rel);
        mob.moveTo(abs.x, abs.y, abs.z, 0.0F, 0.0F);
        mob.setNoAi(true);
        s.level.addFreshEntity(mob);
        s.spawned.add(mob);
        return mob;
    }

    /** 登るテストの調べもの: 群れのモード・先導役の経路(相対)と張り付き・一員の位置。 */
    private static String climbDebug(GameTestHelper helper, Swarm swarm) {
        StringBuilder out = new StringBuilder(swarm.mode().toString());
        out.append(" blocked=").append(swarm.consecutiveBlockedLegs());
        for (Mob m : swarm.members()) {
            out.append(" | ").append(swarm.isVanguard(m) ? "V " : "F ").append(helper.relativeVec(m.position()));
            if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity inv) {
                out.append(inv.isClinging() ? " cling" : "").append(m.onGround() ? " ground" : "")
                        .append(" {").append(inv.debugClimbState()).append('}');
                // 張り付く面・速さ・体の箱に触れている当たり判定(相対座標)。2026-10-19、金網の格子に体を付けたまま
                // y = 4.0 − 背丈で止まった回が、天井への張り付きか頭のつかえかを見分けられなかったので足した。
                out.append(" anc=").append(inv.isOnCeiling() ? "UP" : inv.isClimbing() ? String.valueOf(inv.getClimbFacing()) : "-")
                        .append(" dm=").append(m.getDeltaMovement());
                net.minecraft.world.phys.AABB probe = m.getBoundingBox().inflate(0.01D);
                Vec3 o = helper.absoluteVec(Vec3.ZERO);
                for (net.minecraft.world.phys.shapes.VoxelShape shape : m.level().getBlockCollisions(m, probe)) {
                    net.minecraft.world.phys.AABB b = shape.bounds();
                    out.append(" hit[").append(String.format("%.4f,%.4f,%.4f..%.4f,%.4f,%.4f",
                            b.minX - o.x, b.minY - o.y, b.minZ - o.z, b.maxX - o.x, b.maxY - o.y, b.maxZ - o.z)).append(']');
                }
            }
            net.minecraft.world.level.pathfinder.Path path = m.getNavigation().getPath();
            if (path != null) {
                out.append(" path[").append(path.getNextNodeIndex()).append('/').append(path.getNodeCount())
                        .append(path.canReach() ? " reach" : " partial").append("]:");
                for (int i = 0; i < path.getNodeCount() && i < 16; i++) {
                    BlockPos n = toRelative(helper, path.getNode(i).asBlockPos());
                    out.append(' ').append(n.getX()).append(',').append(n.getY()).append(',').append(n.getZ());
                }
            }
        }
        return out.toString();
    }

    /**
     * 手の届く高さ({@link Swarm#canReachBlock}): 床の上(足 y = 2)の NoAI のアルボラの隣の列で、
     * 床と面一の塊(y = 1)・1 段の台の上(y = 3)・壊す壁の 3 段目(y = 4)には届き、その上(y = 5)には届かない。
     * 「着いた」の高さの判定({@link Swarm#withinBlockReachHeight})も同じ幅。塊は置かない(箱の重なりだけを見る)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void blockReachCoversFloorAndPedestal(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob arbora = ModEntities.ARBORA.get().create(level);
        if (arbora == null) {
            helper.fail("アルボラを作れない");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(1.5D, 2.0D, 3.5D));
        arbora.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        arbora.setNoAi(true);
        level.addFreshEntity(arbora);
        try {
            int[] reachable = {1, 2, 3, 4};
            for (int y : reachable) {
                BlockPos pos = helper.absolutePos(new BlockPos(2, y, 3));
                if (!Swarm.canReachBlock(arbora, pos)) {
                    helper.fail("アルボラ(足 y = 2)の手が隣の列の y = " + y + " に届かない");
                    return;
                }
                if (!Swarm.withinBlockReachHeight(arbora, pos, 0.0D)) {
                    helper.fail("「着いた」の高さの判定が y = " + y + " を外す");
                    return;
                }
            }
            BlockPos high = helper.absolutePos(new BlockPos(2, 5, 3));
            if (Swarm.canReachBlock(arbora, high) || Swarm.withinBlockReachHeight(arbora, high, 0.0D)) {
                helper.fail("アルボラ(足 y = 2)の手が y = 5 まで届く");
                return;
            }
            BlockPos far = helper.absolutePos(new BlockPos(4, 2, 3));
            if (Swarm.canReachBlock(arbora, far)) {
                helper.fail("アルボラの手が 2 列先の塊に届く");
                return;
            }
            helper.succeed();
        } finally {
            arbora.discard();
        }
    }

    /**
     * 床の下のターゲットへ掘る列({@link TdSiege#scanCover}): テスト場の床(y = 1)の上に石の床をもう 1 段(y = 2)敷き、
     * 先導役の足は y = 3。ターゲットの座標は元の床の (3, 1, 3)(2 段下 = 足元の 1 段より深い)。
     * <ul>
     *     <li>真上の列の最初の塊 = 敷いた床の (3, 2, 3)。</li>
     *     <li>そこが岩盤(掘れない)なら、横の列のうち先導役に近い (2, *, 3) を上から = (2, 2, 3)。チェストなら掘る(ユーザー指定 2026-09-25)。</li>
     *     <li>足元の 1 段(y = 2)の塊は掘らなくても届く → null。</li>
     * </ul>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void coverScanDigsDownToSunkenTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        try {
            for (int x = 0; x <= 4; x++) {
                for (int z = 2; z <= 4; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, 2, z));
                    level.setBlock(p, stone, Block.UPDATE_CLIENTS);
                    placed.add(p);
                }
            }
            Vec3 feet = helper.absoluteVec(new Vec3(0.5D, 3.0D, 3.5D));
            BlockPos sunken = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockPos found = TdSiege.scanCover(level, feet, sunken, sunken);
            if (!helper.absolutePos(new BlockPos(3, 2, 3)).equals(found)) {
                helper.fail("2 段下のターゲットの真上の塊が " + rel(helper, found) + "(3, 2, 3 のはず)");
                return;
            }
            level.setBlock(helper.absolutePos(new BlockPos(3, 2, 3)), Blocks.CHEST.defaultBlockState(), Block.UPDATE_CLIENTS);
            found = TdSiege.scanCover(level, feet, sunken, sunken);
            if (!helper.absolutePos(new BlockPos(3, 2, 3)).equals(found)) {
                helper.fail("真上がチェストのとき、掘る塊が " + rel(helper, found) + "(チェストの 3, 2, 3 のはず)");
                return;
            }
            level.setBlock(helper.absolutePos(new BlockPos(3, 2, 3)), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            found = TdSiege.scanCover(level, feet, sunken, sunken);
            if (!helper.absolutePos(new BlockPos(2, 2, 3)).equals(found)) {
                helper.fail("真上が岩盤のとき、横の列の塊が " + rel(helper, found) + "(2, 2, 3 のはず)");
                return;
            }
            found = TdSiege.scanCover(level, feet, helper.absolutePos(new BlockPos(3, 2, 3)), null);
            if (found != null) {
                helper.fail("足元の 1 段の塊に掘る列を出した: " + rel(helper, found));
                return;
            }
            helper.succeed();
        } finally {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (BlockPos p : placed) {
                level.setBlock(p, air, Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * 忌避灯のための BREACH({@link TdSiege#lampBreachFor})。先導役は NoAI のアルボラ(相対 0.5, 2, 3.5)、ターゲットの座標は (6, 2, 3)、
     * 包絡 1 × 2。はじめは「いちばん近い灯り」で「目から見える」ときだけ選んでいたので、どちらも選ばれなかった(レビュー指摘 2026-09-25):
     * <ol>
     *     <li>ガラス板 2 段(2, 2〜3, 3)の向こうの灯り(4, 2, 3)→ 先にガラス板(2, 2, 3)を壊す。</li>
     *     <li>ターゲットブロックの真下(床と面一、6, 1, 3)の灯り → 灯りそのもの(足元の 1 段なので掘らずに届く)。</li>
     * </ol>
     * 灯りの台帳への登録はブロックエンティティの onLoad(1 tick 遅れる)なので、置いてから数 tick 待つ。灯りが近くのテストの攻め手を
     * 押し返さないよう、侵攻を始めるテストと同じく 1 本 1 バッチ。
     */
    @GameTest(template = TEMPLATE, batch = "td_lamp", timeoutTicks = 100)
    public static void lampBehindGlassAndUnderTargetIsHandled(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob arbora = ModEntities.ARBORA.get().create(level);
        if (arbora == null) {
            helper.fail("アルボラを作れない");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(0.5D, 2.0D, 3.5D));
        arbora.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        arbora.setNoAi(true);
        level.addFreshEntity(arbora);
        BlockPos target = helper.absolutePos(new BlockPos(6, 2, 3));
        BlockPos lampBehindGlass = helper.absolutePos(new BlockPos(4, 2, 3));
        BlockPos paneLow = helper.absolutePos(new BlockPos(2, 2, 3));
        BlockPos paneHigh = helper.absolutePos(new BlockPos(2, 3, 3));
        BlockPos lampUnder = helper.absolutePos(new BlockPos(6, 1, 3));
        // 2026-10-24 から忌避灯は地脈電池で点く: 点いた状態で置いて電池を入れる
        BlockState lamp = ModBlocks.REPELLENT_LAMP.get().defaultBlockState().setValue(com.yayuy.overgrownvegetation.block.RepellentLampBlock.LIT, true);
        BlockState floor = level.getBlockState(lampUnder);
        Runnable cleanUp = () -> {
            BlockState air = Blocks.AIR.defaultBlockState();
            level.setBlock(lampBehindGlass, air, Block.UPDATE_ALL);
            level.setBlock(paneLow, air, Block.UPDATE_ALL);
            level.setBlock(paneHigh, air, Block.UPDATE_ALL);
            level.setBlock(target, air, Block.UPDATE_ALL);
            level.setBlock(lampUnder, floor, Block.UPDATE_ALL);
            if (!arbora.isRemoved()) {
                arbora.discard();
            }
        };
        level.setBlock(paneLow, Blocks.GLASS_PANE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(paneHigh, Blocks.GLASS_PANE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(lampBehindGlass, lamp, Block.UPDATE_ALL);
        com.yayuy.overgrownvegetation.gametest.LeyConversionGameTests.powerLamp(level, lampBehindGlass);
        helper.runAfterDelay(3, () -> {
            BlockPos found = TdSiege.lampBreachFor(arbora, target, 1, 2);
            if (!paneLow.equals(found)) {
                cleanUp.run();
                helper.fail("ガラス板の向こうの灯りで選んだ塊が " + rel(helper, found) + "(ガラス板 2, 2, 3 のはず)");
                return;
            }
            level.setBlock(lampBehindGlass, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(paneLow, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(paneHigh, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            level.setBlock(lampUnder, lamp, Block.UPDATE_ALL);
            com.yayuy.overgrownvegetation.gametest.LeyConversionGameTests.powerLamp(level, lampUnder);
            level.setBlock(target, ModBlocks.TARGET_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            helper.runAfterDelay(3, () -> {
                BlockPos under = TdSiege.lampBreachFor(arbora, target, 1, 2);
                cleanUp.run();
                if (!lampUnder.equals(under)) {
                    helper.fail("ターゲットの下の灯りで選んだ塊が " + rel(helper, under) + "(灯り 6, 1, 3 のはず)");
                    return;
                }
                helper.succeed();
            });
        });
    }

    // ------------------------------------------------------------------ 2026-09-25 の runClient 3 回目の報告

    /**
     * <b>囲ったターゲットは屋根の上から叩ける</b>(ユーザー報告「ターゲットブロックを囲むと、攻めてくる mob が上に乗った状態で何もしなくなる。
     * 一部のアルボラがターゲットの真上のブロックは壊すが 1 マスの隙間に入れず、ターゲットを攻撃できないでスタックする」)。
     * テスト場いっぱいの石の床(y = 3)の下にターゲット(3, 2, 3)、アルボラ 2 体の群れは床の上(足 y = 4)で、降りる道は無い。
     * 群れは真上の縦穴の要の塊(3, 3, 3)を崩し、空いた穴の縁から手を伸ばしてターゲットを削る({@link Swarm#BLOCK_REACH_DOWN_OPEN})。
     * はじめは縦穴が 1 マス幅で、縁から届く手も無く、穴に入れないアルボラ(幅 1 = 経路では 2 マス)は床の上で止まり続けた。
     */
    @GameTest(template = TEMPLATE, batch = "td_roof", timeoutTicks = 1400)
    public static void encasedTargetIsStruckFromTheRoof(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                s.put(s.abs(new BlockPos(x, 3, z)), stone);
            }
        }
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 4.0D, 3.5D), id, 0, 2, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(0.5D, 4.0D, 1.5D), id, 1, 2, eventId, false);
        TdTestAccess.setAlive(s.server, 2);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        float max = be.maxHp();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (be.hp() < max - EPS) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 1300L) {
                Swarm now = SwarmManager.get(s.level, id);
                boolean keyOpen = !s.level.getBlockState(s.abs(new BlockPos(3, 3, 3))).is(Blocks.STONE);
                s.fail("床の下のターゲットを 1300 tick のうちに削らない: 真上の塊 " + (keyOpen ? "崩れた" : "残っている")
                        + " / 群れ " + (now == null ? "なし" : now.mode() + " / 壊す壁 " + rel(helper, now.breachPos()) + " / 先導役 "
                        + (now.vanguard() == null ? "なし" : helper.relativeVec(now.vanguard().position()))
                        + " / " + now.debugState() + " / " + TdTestAccess.siegeDebug(s.level, id)));
            }
        });
    }

    /**
     * <b>真上が空いた塊には 2 段上から手が届く</b>({@link Swarm#canReachBlock} の {@link Swarm#BLOCK_REACH_DOWN_OPEN})。
     * NoAI のアルボラ(足 y = 4)の隣の列の 2 段下(3, 2, 3)は、真上(3, 3, 3)が石の間は届かず、空気になると届く。
     * 「着いた」の高さの判定({@link Swarm#withinBlockReachHeight})も同じ。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void openTopLetsRimReachTarget(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        Mob arbora = ModEntities.ARBORA.get().create(level);
        if (arbora == null) {
            helper.fail("アルボラを作れない");
            return;
        }
        Vec3 at = helper.absoluteVec(new Vec3(4.5D, 4.0D, 3.5D));
        arbora.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        arbora.setNoAi(true);
        level.addFreshEntity(arbora);
        BlockPos below = helper.absolutePos(new BlockPos(3, 2, 3));
        BlockPos above = helper.absolutePos(new BlockPos(3, 3, 3));
        level.setBlock(above, Blocks.STONE.defaultBlockState(), Block.UPDATE_ALL);
        try {
            if (Swarm.canReachBlock(arbora, below) || Swarm.withinBlockReachHeight(arbora, below, 0.0D)) {
                helper.fail("真上が塞がった 2 段下の塊に手が届く");
                return;
            }
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            if (!Swarm.canReachBlock(arbora, below)) {
                helper.fail("真上が空いた 2 段下の塊に、穴の縁から手が届かない");
                return;
            }
            if (!Swarm.withinBlockReachHeight(arbora, below, 0.0D)) {
                helper.fail("真上が空いた 2 段下の塊が「着いた」の高さに入らない");
                return;
            }
            BlockPos deeper = helper.absolutePos(new BlockPos(3, 1, 3));
            if (Swarm.canReachBlock(arbora, deeper)) {
                helper.fail("3 段下の塊にまで手が届く");
                return;
            }
            helper.succeed();
        } finally {
            level.setBlock(above, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            arbora.discard();
        }
    }

    /**
     * <b>群れの縦穴は 3 × 3</b>({@code TdSiege.Column})。石の床(y = 3)の下の (3, 2, 3) へ、床の上(足 y = 4)から掘る縦穴の
     * 最初の段は、要の列(真上)の (3, 3, 3) が先頭の 9 マス。まわりの列の岩盤は飛ばす(8 マス)。要の列が岩盤なら、
     * 先導役に近い横(西)の縦穴(要の列 (2, *, 3)、x = 0〜2)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void swarmShaftIsThreeWide(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            for (int z = 0; z <= 6; z++) {
                BlockPos p = helper.absolutePos(new BlockPos(x, 3, z));
                level.setBlock(p, stone, Block.UPDATE_CLIENTS);
                placed.add(p);
            }
        }
        try {
            Vec3 feet = helper.absoluteVec(new Vec3(0.5D, 4.0D, 3.5D));
            BlockPos goal = helper.absolutePos(new BlockPos(3, 2, 3));
            List<BlockPos> cells = TdTestAccess.shaftHole(level, feet, goal, goal, 3);
            if (cells.size() != 9 || !cells.get(0).equals(helper.absolutePos(new BlockPos(3, 3, 3)))) {
                helper.fail("3 × 3 の縦穴の最初の段が " + cells.size() + " マス・先頭 "
                        + (cells.isEmpty() ? "なし" : rel(helper, cells.get(0))) + "(9 マス・先頭 3, 3, 3 のはず)");
                return;
            }
            for (BlockPos c : cells) {
                BlockPos r = toRelative(helper, c);
                if (r.getY() != 3 || r.getX() < 2 || r.getX() > 4 || r.getZ() < 2 || r.getZ() > 4) {
                    helper.fail("縦穴の最初の段の外のマス " + r.toShortString());
                    return;
                }
            }
            level.setBlock(helper.absolutePos(new BlockPos(2, 3, 2)), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells = TdTestAccess.shaftHole(level, feet, goal, goal, 3);
            if (cells.size() != 8) {
                helper.fail("まわりの列の岩盤を飛ばした縦穴が " + cells.size() + " マス(8 のはず)");
                return;
            }
            level.setBlock(helper.absolutePos(new BlockPos(2, 3, 2)), stone, Block.UPDATE_CLIENTS);
            level.setBlock(helper.absolutePos(new BlockPos(3, 3, 3)), Blocks.BEDROCK.defaultBlockState(), Block.UPDATE_CLIENTS);
            cells = TdTestAccess.shaftHole(level, feet, goal, goal, 3);
            if (cells.size() != 9 || !cells.get(0).equals(helper.absolutePos(new BlockPos(2, 3, 3)))) {
                helper.fail("要の列が岩盤のときの横の縦穴が " + cells.size() + " マス・先頭 "
                        + (cells.isEmpty() ? "なし" : rel(helper, cells.get(0))) + "(9 マス・先頭 2, 3, 3 のはず)");
                return;
            }
            for (BlockPos c : cells) {
                BlockPos r = toRelative(helper, c);
                if (r.getX() > 2 || r.getZ() < 2 || r.getZ() > 4) {
                    helper.fail("横の縦穴の外のマス " + r.toShortString() + "(x = 0〜2、z = 2〜4 のはず)");
                    return;
                }
            }
            helper.succeed();
        } finally {
            for (BlockPos p : placed) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * <b>崖には坂の橋、薄い壁には架けない</b>({@code TdBridges#plan}。ユーザー報告「崖などがあるとアルボラ以外が立ち往生する。
     * 他の mob が通れる橋をアルボラが(過繁茂したブロックで)。G5 も通れる幅 3 マスほど」)。見る範囲はテスト場に収める
     * (後ろ 1・前 5 列、崖とみなす高さの続き 2 列、頭の上 3 マス)。幅 4(td.json の既定)。
     * <ol>
     *     <li>x = 5〜6 に高さ 2 の台(立つ高さ 4)。床の上(足 (1, 2, 3))から東へ: 台の手前 x = 4 に 1 段の坂 = 足場 (4, 2, z) を
     *     z = 2〜5 の 4 マス。崩す塊は無し。</li>
     *     <li>台を x = 5 の 1 列だけにする(奥は床に戻る = 拠点の薄い壁): 橋にしない(壊して入る)。</li>
     *     <li>台を無くす: 崖も溝も無いので橋にしない。</li>
     * </ol>
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void bridgePlanRampsUpCliffButNotWall(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 5; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    level.setBlock(p, stone, Block.UPDATE_CLIENTS);
                    placed.add(p);
                }
            }
        }
        try {
            BlockPos feet = helper.absolutePos(new BlockPos(1, 2, 3));
            BlockPos goal = helper.absolutePos(new BlockPos(6, 4, 3));
            List<BlockPos> deck = TdTestAccess.bridgeSteps(level, feet, goal, 4, 1, 5, 2, 3, true, arena(helper));
            List<BlockPos> clear = TdTestAccess.bridgeSteps(level, feet, goal, 4, 1, 5, 2, 3, false, arena(helper));
            List<BlockPos> want = new ArrayList<>();
            for (int z = 2; z <= 5; z++) {
                want.add(helper.absolutePos(new BlockPos(4, 2, z)));
            }
            if (deck == null || clear == null || deck.size() != 4 || !deck.containsAll(want) || !clear.isEmpty()) {
                helper.fail("高さ 2 の崖の坂の足場が " + describeCells(helper, deck) + " / 崩す塊 " + describeCells(helper, clear)
                        + "(足場は (4, 2, 2〜5) の 4 マス、崩す塊は無しのはず)");
                return;
            }
            for (int y = 2; y <= 3; y++) {
                for (int z = 0; z <= 6; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(6, y, z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (TdTestAccess.bridgeSteps(level, feet, goal, 4, 1, 5, 2, 3, true, arena(helper)) != null) {
                helper.fail("1 列の薄い壁(拠点の壁)に橋を架けた(壁は壊して入るはず)");
                return;
            }
            for (int y = 2; y <= 3; y++) {
                for (int z = 0; z <= 6; z++) {
                    level.setBlock(helper.absolutePos(new BlockPos(5, y, z)), Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            if (TdTestAccess.bridgeSteps(level, feet, goal, 4, 1, 5, 2, 3, true, arena(helper)) != null) {
                helper.fail("平らな床に橋を架けた");
                return;
            }
            helper.succeed();
        } finally {
            for (BlockPos p : placed) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * <b>溝には平らな橋</b>({@code TdBridges#plan})。x = 0〜2 と x = 5〜6 に高さ 3 の台(立つ高さ 5)、間の x = 3〜4 は深さ 3 の溝。
     * 西の台の上(足 (1, 5, 3))から東へ: 両岸と同じ高さの平らな橋 = 足場 (3〜4, 4, z) を z = 2〜5 の 8 マス
     * (いちばん低い並びは溝へ 2 段下って 1 段ずつ上る形だが、置く数は同じなので両岸を結ぶ高さへ持ち上げる)。
     * 頭の上はテスト場の天井(y = 8 のバリア)に掛からないよう 2 マスだけ見る。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void bridgePlanSpansGapFlat(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        List<BlockPos> placed = new ArrayList<>();
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 0; x <= 6; x++) {
            if (x == 3 || x == 4) {
                continue;
            }
            for (int y = 2; y <= 4; y++) {
                for (int z = 0; z <= 6; z++) {
                    BlockPos p = helper.absolutePos(new BlockPos(x, y, z));
                    level.setBlock(p, stone, Block.UPDATE_CLIENTS);
                    placed.add(p);
                }
            }
        }
        try {
            BlockPos feet = helper.absolutePos(new BlockPos(1, 5, 3));
            BlockPos goal = helper.absolutePos(new BlockPos(6, 5, 3));
            List<BlockPos> deck = TdTestAccess.bridgeSteps(level, feet, goal, 4, 1, 5, 2, 2, true, arena(helper));
            List<BlockPos> want = new ArrayList<>();
            for (int x = 3; x <= 4; x++) {
                for (int z = 2; z <= 5; z++) {
                    want.add(helper.absolutePos(new BlockPos(x, 4, z)));
                }
            }
            if (deck == null || deck.size() != 8 || !deck.containsAll(want)) {
                helper.fail("深さ 3 の溝の橋の足場が " + describeCells(helper, deck) + "((3〜4, 4, 2〜5) の 8 マスのはず)");
                return;
            }
            helper.succeed();
        } finally {
            for (BlockPos p : placed) {
                level.setBlock(p, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
        }
    }

    /**
     * <b>群れの外のアルボラが橋を架ける</b>({@code TdBridges} の大工と {@code TdBridgeGoal})。x = 5〜6 に高さ 2 の台、
     * 侵攻の印付きの群れの外のアルボラ(床の上)。橋を頼むと、アルボラが大工になって台の手前の坂の足場 (4, 2, 2〜5) を
     * 過繁茂したブロックで置き、橋ができあがる。
     */
    @GameTest(template = TEMPLATE, batch = "td_bridge_build", timeoutTicks = 500)
    public static void loneArboraBuildsBridge(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (int x = 5; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) {
                for (int z = 0; z <= 6; z++) {
                    s.put(s.abs(new BlockPos(x, y, z)), stone);
                }
            }
        }
        BlockPos target = s.abs(new BlockPos(6, 4, 3));
        if (s.target(target) == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        s.mob(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), false, null, eventId);
        for (int z = 2; z <= 5; z++) {
            s.built.add(s.abs(new BlockPos(4, 2, z))); // 片付けで空気へ戻す
        }
        com.yayuy.overgrownvegetation.td.TdBridges.Project project = TdTestAccess.startBridge(s.level,
                s.abs(new BlockPos(1, 2, 3)), target, 4, 1, 5, 2, 3, arena(helper));
        if (project == null) {
            s.fail("台の手前に橋を計画できない");
            return;
        }
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            int placed = 0;
            for (int z = 2; z <= 5; z++) {
                if (s.level.getBlockState(s.abs(new BlockPos(4, 2, z))).is(ModBlocks.OVERGROWN_BLOCK.get())) {
                    placed++;
                }
            }
            if (project.completed() && placed == 4) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 450L) {
                s.fail("450 tick のうちに橋ができない: 置いた足場 " + placed + " / 4、大工 " + project.builderCount()
                        + "、できあがり " + project.completed());
            }
        });
    }

    /**
     * <b>登れない一員のいる群れは、崖に橋を架けてから上る</b>(ユーザー報告「崖などがあるとアルボラ以外が立ち往生する」)。
     * x = 4〜6 に高さ 2 の<b>岩盤</b>の台(壊して穴を開けても上へは出られない = 橋しか無い)、ターゲットは台の上 (6, 4, 3)。
     * スカトゥリウム(段差 1.2 まで)とアルボラ 2 体の群れが床の上から攻める。群れは区間が塞がれると橋を頼んで待ち、アルボラが
     * 台の手前 x = 3 に坂の足場を置き、スカトゥリウムが台の上へ上る(アルボラは自分で崖を登ってターゲットを叩けるので、叩いたかでは
     * 見ない)。群れが崖の下で塊に「着いた」ことになっても、先導役の手が届かないまま 200 tick で橋を頼む(TdSiege#watchSiege)。
     * 見る範囲はテスト場に収める(外の列は縁の高さ)。
     */
    @GameTest(template = TEMPLATE, batch = "td_bridge_swarm", timeoutTicks = 1700)
    public static void mixedSwarmBridgesUpCliff(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockState bedrock = Blocks.BEDROCK.defaultBlockState();
        for (int x = 4; x <= 6; x++) {
            for (int y = 2; y <= 3; y++) {
                for (int z = 0; z <= 6; z++) {
                    s.put(s.abs(new BlockPos(x, y, z)), bedrock);
                }
            }
        }
        for (int z = 0; z <= 6; z++) {
            s.built.add(s.abs(new BlockPos(3, 2, z))); // 坂の足場(片付けで空気へ戻す)
        }
        BlockPos target = s.abs(new BlockPos(6, 4, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        TdTestAccess.setBridgeShape(6, 6, 2, 3, arena(helper));
        String eventId = s.eventId();
        UUID id = UUID.randomUUID();
        Mob shielder = s.member(ModEntities.OVERGROWN_SHIELDER.get(), new Vec3(1.5D, 2.0D, 3.5D), id, 0, 3, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(0.5D, 2.0D, 1.5D), id, 1, 3, eventId, false);
        s.member(ModEntities.ARBORA.get(), new Vec3(0.5D, 2.0D, 5.5D), id, 2, 3, eventId, false);
        TdTestAccess.setAlive(s.server, 3);
        Swarm swarm = SwarmManager.get(s.level, id);
        if (swarm == null) {
            s.fail("TD の攻め手の群れが湧かせた時点で登録されていない");
            return;
        }
        swarm.setTestBounds(helper.getBounds());
        boolean[] bridged = {false};
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            if (!bridged[0] && TdTestAccess.bridgeOf(s.level, id) != null) {
                bridged[0] = true;
            }
            // 登れないスカトゥリウムが台の上(立つ高さ y = 4)に立てば、坂の橋を渡った(岩盤は崩せない)。
            if (shielder.isAlive() && shielder.getY() >= s.abs(new BlockPos(0, 4, 0)).getY() - 0.1D) {
                s.pass();
                return;
            }
            if (helper.getTick() >= 1600L) {
                Swarm now = SwarmManager.get(s.level, id);
                int deck = 0;
                for (int z = 0; z <= 6; z++) {
                    if (s.level.getBlockState(s.abs(new BlockPos(3, 2, z))).is(ModBlocks.OVERGROWN_BLOCK.get())) {
                        deck++;
                    }
                }
                s.fail("1600 tick のうちにスカトゥリウムが崖の上へ上らない: 橋を頼んだ " + bridged[0] + " / 坂の足場 " + deck
                        + " / スカトゥリウム " + helper.relativeVec(shielder.position()) + " / 群れ "
                        + (now == null ? "なし" : now.debugState() + " / 先導役 "
                        + (now.vanguard() == null ? "なし" : helper.relativeVec(now.vanguard().position()))));
            }
        });
    }

    /**
     * <b>攻め手のドロップはターゲットの収納へ</b>(ユーザー指定「TD 中は mob からドロップしたアイテムをターゲットブロックの
     * インベントリへ自動で入れる」、{@code TdLootCollector})。侵攻の印付きのハスクに必ず落とすダイヤモンドを持たせて倒すと、
     * ダイヤモンドはターゲットの収納に入り、テスト場には落ちない。
     */
    @GameTest(template = TEMPLATE, batch = "td_loot", timeoutTicks = 100)
    public static void attackerDropsGoIntoTargetStorage(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(5, 2, 3));
        TargetBlockEntity be = s.target(target);
        if (be == null || !s.start(target, 0.0D)) {
            return;
        }
        String eventId = s.eventId();
        Husk husk = s.husk(1.5D, 3.5D, eventId);
        husk.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.DIAMOND));
        husk.setDropChance(net.minecraft.world.entity.EquipmentSlot.MAINHAND, 2.0F);
        husk.hurt(s.level.damageSources().generic(), 1000.0F);
        helper.runAfterDelay(2, () -> {
            int stored = 0;
            for (int i = 0; i < be.storage().getContainerSize(); i++) {
                net.minecraft.world.item.ItemStack stack = be.storage().getItem(i);
                if (stack.is(net.minecraft.world.item.Items.DIAMOND)) {
                    stored += stack.getCount();
                }
            }
            List<ItemEntity> loose = s.level.getEntitiesOfClass(ItemEntity.class, helper.getBounds().inflate(2.0D),
                    e -> e.getItem().is(net.minecraft.world.item.Items.DIAMOND));
            be.storage().clearContent();
            if (stored != 1 || !loose.isEmpty()) {
                s.fail("倒した攻め手のダイヤモンドが収納に " + stored + " 個・地面に " + loose.size() + " 個(収納に 1 個・地面に 0 のはず)");
                return;
            }
            s.pass();
        });
    }

    /**
     * <b>ターゲットの収納はラージチェスト 4 つ分で、埋まったら残りは落ちる</b>({@code TargetStorage} と {@code TdLootCollector#collect})。
     * 216 マスに土 64 個ずつで満杯、そこへの丸石は入らない。1 マス空けて丸石 70 個 → 64 個入って 6 個残る(残りは落ちる側)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH)
    public static void targetStorageFillsThenOverflows(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(3, 2, 3));
        TargetBlockEntity be = new TargetBlockEntity(pos, ModBlocks.TARGET_BLOCK.get().defaultBlockState());
        com.yayuy.overgrownvegetation.td.TargetStorage storage = be.storage();
        if (storage.getContainerSize() != 216) {
            helper.fail("収納が " + storage.getContainerSize() + " マス(ラージチェスト 4 つ = 216 のはず)");
            return;
        }
        for (int i = 0; i < 216; i++) {
            net.minecraft.world.item.ItemStack rest = storage.insert(new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.DIRT, 64), 0);
            if (!rest.isEmpty()) {
                helper.fail(i + " 山目の土が入らない(残り " + rest.getCount() + ")");
                return;
            }
        }
        List<ItemEntity> drops = new ArrayList<>();
        drops.add(new ItemEntity(level, pos.getX(), pos.getY(), pos.getZ(), new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.COBBLESTONE, 5)));
        TdTestAccess.collectDrops(storage, drops.iterator());
        if (drops.size() != 1 || drops.get(0).getItem().getCount() != 5) {
            helper.fail("満杯の収納へ丸石が入った(落ちる丸石 " + (drops.isEmpty() ? 0 : drops.get(0).getItem().getCount()) + " 個)");
            return;
        }
        storage.setItem(100, net.minecraft.world.item.ItemStack.EMPTY);
        drops.clear();
        drops.add(new ItemEntity(level, pos.getX(), pos.getY(), pos.getZ(), new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.COBBLESTONE, 70)));
        TdTestAccess.collectDrops(storage, drops.iterator());
        if (drops.size() != 1 || drops.get(0).getItem().getCount() != 6
                || !storage.getItem(100).is(net.minecraft.world.item.Items.COBBLESTONE) || storage.getItem(100).getCount() != 64) {
            helper.fail("1 マス空けた収納へ丸石 70 個: 残り " + (drops.isEmpty() ? 0 : drops.get(0).getItem().getCount())
                    + " 個・空けたマス " + storage.getItem(100) + "(64 個入って 6 個残るはず)");
            return;
        }
        helper.succeed();
    }

    /**
     * <b>上限が埋まっていても控えの群れは行進する</b>(ユーザー報告「TD であるのに物量が足りなく見える。補充をもっと早く。上限 500、
     * 1 体死ねば 1 体補充くらいでもよい」、{@code virtualReserve})。上限 2・控え 20(編成を振ると 1 群れ 9 体を超えることがある)で、侵攻の印付きのハスク 2 体(読み込まれている
     * 攻め手 = 上限ちょうど)。帳簿を 2 回待つと、仮想の群れが作られている(実体にはならない)。はじめは「読み込まれている攻め手 +
     * 行進中」が上限に届くと作らなかったので、倒されてから遠い輪で次の群れが生まれていた。
     */
    @GameTest(template = TEMPLATE, batch = "td_reserve", timeoutTicks = 200)
    public static void reserveMarchesWhileCapIsFull(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        TdTable.Json json = new TdTable.Json();
        json.concurrentCap = 2;
        json.virtualReserve = 20;
        json.swarmSpawnIntervalTicks = 20;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(1, 2, 1));
        if (s.target(target) == null || !s.start(target, 30.0D)) {
            return;
        }
        TdTicker.setIgnorePlayersForTests(true);
        String eventId = s.eventId();
        s.husk(3.5D, 5.5D, eventId);
        s.husk(5.5D, 5.5D, eventId);
        TdTestAccess.setAlive(s.server, 2);
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            TdData.Event e = TdData.get(s.server).active();
            if (e == null) {
                s.fail("侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
                return;
            }
            if (helper.getTick() - begin < TWO_BOOKKEEPINGS * 2L) {
                return;
            }
            if (TdTicker.loadedAttackers(s.level) != 2) {
                s.fail("上限ちょうどなのに読み込まれている攻め手が " + TdTicker.loadedAttackers(s.level) + " 体(2 のはず)");
                return;
            }
            if (e.virtualSize() <= 0) {
                s.fail("上限が埋まっている間に控えの群れが作られない: " + describe(e));
                return;
            }
            s.pass();
        });
    }

    /**
     * テスト場の中の箱(相対 0〜6、高さ 0〜7)。橋の計画の地面探しをこの中に収める(天井のバリアの上を地面と答えないように。
     * 箱の外の列は縁の列の高さとみなす)。
     */
    private static net.minecraft.world.level.levelgen.structure.BoundingBox arena(GameTestHelper helper) {
        return net.minecraft.world.level.levelgen.structure.BoundingBox.fromCorners(
                helper.absolutePos(new BlockPos(0, 0, 0)), helper.absolutePos(new BlockPos(6, 7, 6)));
    }

    /** 失敗の文言用: 座標の並びを相対で。 */
    private static String describeCells(GameTestHelper helper, @Nullable List<BlockPos> cells) {
        if (cells == null) {
            return "計画なし";
        }
        StringBuilder out = new StringBuilder("[");
        for (BlockPos c : cells) {
            if (out.length() > 1) {
                out.append(' ');
            }
            out.append(toRelative(helper, c).toShortString());
        }
        return out.append(']').toString();
    }

    private static String rel(GameTestHelper helper, @Nullable BlockPos abs) {
        return abs == null ? "なし" : toRelative(helper, abs).toShortString();
    }

    /**
     * 絶対座標 → テスト場の相対座標({@code absolutePos} の逆)。バニラ 1.21.1 の {@code GameTestHelper#relativePos} は回転なしの
     * テストでも 180 度回してしまう(逆回転のつもりで「+180 度」を足している)ので使わない —— (3, 2, 3) が (-3, 2, -3) になり、
     * 正しい答えの検査が落ちた(2026-09-25 の初回実行)。このファイルのテストは回転させない。
     */
    private static BlockPos toRelative(GameTestHelper helper, BlockPos abs) {
        return abs.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    // ------------------------------------------------------------------ 生態系防衛

    /**
     * 進行中の侵攻のターゲットから {@code defenseSuppressRadius}(仮決め 256)以内のプレイヤーのまわりでは生態系防衛を出さない
     * (TD 設計 v2: 侵攻の攻め手と防衛の兵が同じ財布・同じ上限を二重に使わないように)。侵攻が無い・終わった後は止めない。
     * 防衛の本体はプレイヤーごとに回る(GameTest にはプレイヤーがいない)ので、防衛が見る判定を直に見る。
     */
    @GameTest(template = TEMPLATE, batch = "td_defense")
    public static void ecosystemDefenseSuppressedNearLiveInvasion(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null) {
            return;
        }
        int r = (int) Math.floor(DesignData.td().defenseSuppressRadius());
        BlockPos edge = target.offset(r, 0, 0);
        BlockPos outside = target.offset(r + 1, 0, 0);
        if (TdEvents.suppressesEcosystemDefense(s.level, target)) {
            s.fail("侵攻が無いのに生態系防衛を止めている");
            return;
        }
        if (!s.start(target, 0.0D)) {
            return;
        }
        if (!TdEvents.suppressesEcosystemDefense(s.level, target)) {
            s.fail("侵攻中のターゲットの上で生態系防衛を止めていない");
            return;
        }
        if (!TdEvents.suppressesEcosystemDefense(s.level, edge)) {
            s.fail("ターゲットからちょうど " + r + " ブロックで生態系防衛を止めていない");
            return;
        }
        if (TdEvents.suppressesEcosystemDefense(s.level, outside)) {
            s.fail("ターゲットから " + (r + 1) + " ブロック(範囲の外)でも生態系防衛を止めている");
            return;
        }
        TdTicker.forceStop(s.server);
        if (TdEvents.suppressesEcosystemDefense(s.level, target)) {
            s.fail("侵攻が終わっても生態系防衛を止めたまま");
            return;
        }
        s.pass();
    }

    // ------------------------------------------------------------------ 取りやめ

    /**
     * <b>取りやめ</b>: 世界のバイオマス 300 で侵攻を始め、仮想の群れ 4 体(遠く、速さ 0.5)を足す。GameTest のサーバーには
     * プレイヤーがいないので(プレイヤーを無視する印は立てない)、最初の帳簿で一時停止になる。
     * <ul>
     *     <li>一時停止の間、{@link TdEvents#isPaused} は true で、仮想の群れは進まない。</li>
     *     <li>止まったまま {@code abandonTicks} を過ぎたら取りやめで、予算 + 仮想の群れの値段(300 + アルボラ 4 体 × {@code tdCost})を
     *     世界へ返す。</li>
     * </ul>
     * {@code abandonTicks} の 3 日(仮決め)は待てないので、このテストの間だけ 20 tick にした表へ差し替える
     * ({@link DesignData#setTdForTests}。ほかの値は Java の既定値。終わるときに元の表へ戻す)。
     */
    @GameTest(template = TEMPLATE, batch = "td_abandon", timeoutTicks = 200)
    public static void abandonedInvasionRefundsBudgetAndVirtual(GameTestHelper helper) {
        TdStage s = new TdStage(helper);
        TdTable.Json json = new TdTable.Json();
        json.abandonTicks = 20;
        s.useTable(TdTable.fromJson(json));
        BlockPos target = s.abs(new BlockPos(3, 2, 3));
        if (s.target(target) == null) {
            return;
        }
        EcosystemData eco = EcosystemData.get(s.server);
        eco.setBiomass(300.0D);
        if (!s.start(target, null)) {
            return;
        }
        TdTestAccess.addVirtualSwarm(s.server, Vec3.atCenterOf(target).add(150.0D, 0.0D, 0.0D),
                ModEntities.ARBORA.get(), 4, 0.5D);
        double[] last = {eco.biomass()};
        double[] pausedX = {Double.NaN};
        long begin = helper.getTick();
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long t = helper.getTick() - begin;
            TdData data = TdData.get(s.server);
            TdData.Event e = data.live();
            double now = eco.biomass();
            if (e != null) {
                if (e.paused()) {
                    if (!TdEvents.isPaused(s.level)) {
                        s.fail("侵攻が一時停止しているのに TdEvents.isPaused が false");
                        return;
                    }
                    double x = e.virtualSwarms().isEmpty() ? Double.NaN : e.virtualSwarms().get(0).x();
                    if (Double.isNaN(pausedX[0])) {
                        pausedX[0] = x;
                    } else if (Double.isNaN(x) || Math.abs(x - pausedX[0]) > 1.0E-9D) {
                        s.fail("一時停止中に仮想の群れが進んだ・消えた: x " + pausedX[0] + " → " + x);
                        return;
                    }
                }
                if (t > 150L) {
                    s.fail("誰もいないまま取りやめにならない: " + describe(e));
                    return;
                }
                last[0] = now;
                return;
            }
            if (Double.isNaN(pausedX[0])) {
                s.fail("一時停止を経ずに終わった(" + data.lastOutcome() + ")");
                return;
            }
            if (data.lastOutcome() != TdData.Outcome.ABANDONED) {
                s.fail("取りやめ以外の終わり方: " + data.lastOutcome());
                return;
            }
            double refund = now - last[0];
            double expected = 300.0D + arboraCost(4);
            if (Math.abs(refund - expected) > BIOMASS_EPS) {
                s.fail("取りやめの払い戻しが " + refund + "(予算 300 + 仮想の群れ アルボラ 4 体 × " + arboraCost(1) + " = "
                        + expected + " のはず)");
                return;
            }
            s.pass();
        });
    }

    // ------------------------------------------------------------------ 道具

    /**
     * アルボラ {@code count} 体を買うバイオマス = {@link TdTestAccess#addVirtualSwarm} で足したアルボラの仮想の群れの払い戻しの額。
     * 1 体の値段は mobs.json の {@code tdCost}(2026-09-26 にユーザーが種ごとに決めた。{@link MobStats#tdCost})なので、決め打ちせずに引く。
     */
    private static double arboraCost(int count) {
        return count * MobStats.tdCost(ModEntities.ARBORA.get());
    }

    /**
     * mod の種でいちばん高い {@code tdCost}(下限 1 = バニラの種の値段)。仮想の群れの顔ぶれ(編成・護衛)はどれも mod の種なので、
     * 1 体の値段はこれを超えない。
     */
    private static double priciestTdCost() {
        double max = 1.0D;
        for (var holder : ModEntities.ENTITY_TYPES.getEntries()) {
            max = Math.max(max, MobStats.tdCost(holder.get()));
        }
        return max;
    }

    /** 前衛の 1 回の削り: 攻撃力の属性(持っていなければ支援の値。{@code TdSiege#siegeDamage} と同じ決まり)。 */
    private static float frontDamage(Mob mob, TdTable td) {
        return mob.getAttributes().hasAttribute(Attributes.ATTACK_DAMAGE)
                ? (float) mob.getAttributeValue(Attributes.ATTACK_DAMAGE)
                : td.siegeDamageSupport();
    }

    /** 1 回叩いて、量・当てたか・耐久の減りを確かめる。 */
    private static void expectStrike(TdStage s, TargetBlockEntity be, Swarm swarm, BlockPos target, Mob mob, float expected,
                                     String who) {
        float amount = TdTestAccess.siegeDamage(mob, swarm);
        if (Math.abs(amount - expected) > EPS) {
            s.fail(who + " の 1 回の削りが " + amount + "(" + expected + " のはず)");
            return;
        }
        float before = be.hp();
        boolean hit = TdTestAccess.strike(mob, swarm, target, false);
        float lost = before - be.hp();
        if (hit != expected > 0.0F) {
            s.fail(who + " が叩いた答えが " + hit + "(削る量 " + expected + ")");
            return;
        }
        if (Math.abs(lost - expected) > EPS) {
            s.fail(who + " の一撃で耐久が " + lost + " 減った(" + expected + " のはず)");
        }
    }

    private static void expectAlive(TdStage s, int alive, int killed, String what) {
        TdData.Event e = TdData.get(s.server).active();
        if (e == null) {
            s.fail(what + ": 侵攻が終わった(" + TdData.get(s.server).lastOutcome() + ")");
            return;
        }
        if (e.alive() != alive || e.killed() != killed) {
            s.fail(what + ": 生きている " + e.alive() + " / 倒した " + e.killed() + "(" + alive + " / " + killed + " のはず)");
        }
    }

    private static void expectLoaded(TdStage s, int expected, String what) {
        int n = TdTicker.loadedAttackers(s.level);
        if (n != expected) {
            s.fail(what + ": 読み込まれている攻め手の表に " + n + " 体(" + expected + " 体のはず)");
        }
    }

    /** 失敗の手がかり用の、侵攻の数えの 1 行。 */
    private static String describe(@Nullable TdData.Event e) {
        if (e == null) {
            return "侵攻なし";
        }
        return e.state() + " 予算 " + String.format(Locale.ROOT, "%.2f", e.budget()) + " / 仮想 " + e.virtualSwarms().size()
                + " 群れ " + e.virtualSize() + " 体 / 生きている " + e.alive() + "(値段 " + String.format(Locale.ROOT, "%.1f", e.aliveCost())
                + ")/ 倒した " + e.killed()
                + (e.paused() ? " / 一時停止" : "");
    }

    /**
     * 上空のターゲットと汚染の見張り(負け・撤去のテスト)。ターゲットの真下と {@value #FAR_PROBE} ブロック先(+X)に石を浮かべる。
     * 見張りの上のマスも片付けの対象にする(汚染が上に草を生やすことがある)。
     */
    private record SkyBase(TdStage s, BlockPos target, BlockPos nearProbe, BlockPos farProbe) {

        static SkyBase build(TdStage s) {
            BlockPos target = s.abs(new BlockPos(3, SKY_Y, 3));
            BlockPos near = target.below();
            BlockPos far = target.offset(FAR_PROBE, 0, 0);
            s.forceChunk(target);
            s.forceChunk(far);
            BlockState stone = Blocks.STONE.defaultBlockState();
            s.put(near, stone);
            s.put(far, stone);
            s.put(far.above(), Blocks.AIR.defaultBlockState());
            return new SkyBase(s, target, near, far);
        }

        boolean nearIsStone() {
            return this.s.level.getBlockState(this.nearProbe).is(Blocks.STONE);
        }

        boolean farIsStone() {
            return this.s.level.getBlockState(this.farProbe).is(Blocks.STONE);
        }
    }

    /** 壁の DDA の検査の置き場(置いたブロックを覚えて、ケースごと・終わりに空気へ戻す)。 */
    private static final class WallScan {
        final GameTestHelper helper;
        final ServerLevel level;
        final Vec3 from;
        final BlockPos target;
        final List<BlockPos> placed = new ArrayList<>();

        WallScan(GameTestHelper helper, Vec3 from, BlockPos target) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.from = from;
            this.target = target;
        }

        void put(int x, int y, int z, BlockState state) {
            BlockPos abs = this.helper.absolutePos(new BlockPos(x, y, z));
            this.level.setBlock(abs, state, Block.UPDATE_CLIENTS);
            this.placed.add(abs);
        }

        void clear() {
            BlockState air = Blocks.AIR.defaultBlockState();
            for (BlockPos p : this.placed) {
                this.level.setBlock(p, air, Block.UPDATE_CLIENTS);
            }
            this.placed.clear();
        }

        void expect(String what, int width, int height, @Nullable BlockPos expected) {
            BlockPos found = TdSiege.scanWall(this.level, this.helper.absoluteVec(this.from),
                    this.helper.absolutePos(this.target), width, height);
            BlockPos rel = found != null ? toRelative(this.helper, found) : null;
            if (!Objects.equals(rel, expected)) {
                this.helper.fail(what + ": 壊す壁が " + (rel == null ? "なし" : rel.toShortString())
                        + "(期待は " + (expected == null ? "なし" : expected.toShortString()) + ")");
            }
        }
    }

    /**
     * 侵攻を始める 1 本のテストの場。始めに TD の台帳を借り(空の台帳・次の侵攻までの待ちの中)、世界のバイオマスと設計値を覚える。
     * {@link #pass} / {@link #fail} は 1 回だけ効き、そのとき(成功・失敗とも)に片付ける:
     * 置いた Mob と侵攻の攻め手・落ちたアイテムを消す → 侵攻が続いていれば止める → 置いたブロックを空気へ → プレイヤーを無視する印・
     * バイオマス・設計値・台帳を元へ戻す。{@link #pass} / {@link #fail} を通らずに落ちたテスト(毎 tick の処理の例外・時間切れ)も、
     * テストの失敗の知らせで片付ける(侵攻が残ると、後のバッチで範囲の敵対 Mob の湧きが止まり、胞子の装置の登録も外れたまま。
     * レビュー指摘 2026-09-25)。
     */
    private static final class TdStage {
        final GameTestHelper helper;
        final ServerLevel level;
        final MinecraftServer server;
        final List<Entity> spawned = new ArrayList<>();
        final List<BlockPos> built = new ArrayList<>();
        private final CompoundTag ledger;
        /** テストの間だけ外す胞子の装置の登録(ほかのテスト場に残った除去装置の阻害で、負けの汚染が揺れないように)。 */
        private final long[][] devices;
        private final double biomass;
        private final TdTable table;
        boolean done;

        TdStage(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
            this.server = this.level.getServer();
            this.ledger = TdTestAccess.isolate(this.server);
            this.devices = com.yayuy.overgrownvegetation.spore.SporeDeviceData.isolateForTests(this.level);
            this.biomass = EcosystemData.get(this.server).biomass();
            this.table = DesignData.td();
            TdTicker.setIgnorePlayersForTests(false);
            // 崖・溝の橋は止めておく(ふつうの見る範囲はテスト場の外のバリア・隣のテスト場まで拾う)。橋のテストは自分で範囲を決める。
            TdTestAccess.setBridgeShape(0, 0, 1, 1, null);
            helper.testInfo.addListener(new GameTestListener() {
                @Override
                public void testStructureLoaded(GameTestInfo info) {
                }

                @Override
                public void testPassed(GameTestInfo info, GameTestRunner runner) {
                }

                @Override
                public void testFailed(GameTestInfo info, GameTestRunner runner) {
                    if (TdStage.this.done) {
                        return;
                    }
                    try {
                        TdStage.this.cleanUp();
                    } catch (RuntimeException e) {
                        OvergrownVegetation.LOGGER.error("TD のテストの片付けに失敗: {}", info.getTestName(), e);
                    }
                }

                @Override
                public void testAddedForRerun(GameTestInfo oldTest, GameTestInfo newTest, GameTestRunner runner) {
                }
            });
        }

        BlockPos abs(BlockPos rel) {
            return this.helper.absolutePos(rel);
        }

        /** 絶対座標にブロックを置く(片付けで空気へ戻す)。 */
        void put(BlockPos abs, BlockState state) {
            this.level.setBlock(abs, state, Block.UPDATE_ALL);
            this.built.add(abs.immutable());
        }

        /** そのチャンクを強制読み込みにして今すぐ読み込む(外すのはバッチの終わりのバニラ)。 */
        void forceChunk(BlockPos abs) {
            int cx = abs.getX() >> 4;
            int cz = abs.getZ() >> 4;
            this.level.setChunkForced(cx, cz, true);
            this.level.getChunk(cx, cz);
        }

        /** ターゲットブロックを置く。台帳({@link TdTargets})に載らなければ失敗にして null。 */
        @Nullable
        TargetBlockEntity target(BlockPos abs) {
            put(abs, ModBlocks.TARGET_BLOCK.get().defaultBlockState());
            TargetBlockEntity be = TdTargets.get(this.level, abs);
            if (be == null) {
                fail("置いたターゲットブロックが台帳(TdTargets)に載らない: " + this.level.getBlockState(abs));
            }
            return be;
        }

        /**
         * コマンドと同じ口で侵攻を始める({@code budget} が null なら世界のバイオマスを全部切り出す)。向きは<b>全方位</b>に決める
         * (振ると 6 割が一方向になり、向きを見るテストが揺れる。一方向は {@link #start(BlockPos, Double, TdDirection)})。
         */
        boolean start(BlockPos target, @Nullable Double budget) {
            return start(target, budget, TdDirection.AROUND);
        }

        boolean start(BlockPos target, @Nullable Double budget, TdDirection direction) {
            if (!TdTicker.forceStart(this.level, target, budget, direction)) {
                fail("侵攻を始められない(進行中のイベントが残っている?)");
                return false;
            }
            return true;
        }

        String eventId() {
            String id = TdEvents.activeEventId(this.level);
            if (id == null) {
                fail("侵攻中のイベントの id が無い");
                throw new IllegalStateException("unreachable");
            }
            return id;
        }

        /** このテストの間だけ設計値を差し替える(片付けで元へ戻す)。 */
        void useTable(TdTable table) {
            DesignData.setTdForTests(table);
        }

        /** Mob を 1 体置く({@code SwarmSpawner} と同じく、所属と侵攻の印は addFreshEntity の前に書く)。 */
        <T extends Mob> T mob(EntityType<T> type, Vec3 rel, boolean noAi, @Nullable SwarmMembership membership,
                              @Nullable String tdEventId) {
            T mob = type.create(this.level);
            if (mob == null) {
                fail("作れない: " + EntityType.getKey(type));
                throw new IllegalStateException("unreachable");
            }
            Vec3 abs = this.helper.absoluteVec(rel);
            mob.moveTo(abs.x, abs.y, abs.z, 0.0F, 0.0F);
            if (noAi) {
                mob.setNoAi(true);
            }
            if (membership != null) {
                mob.setData(ModAttachments.SWARM, membership);
            }
            if (tdEventId != null) {
                EventMobs.tag(mob, EventMobs.Kind.TD, tdEventId);
            }
            // テストが置く Mob は、侵攻の範囲の「新しい敵対 Mob を湧かせない」(TdHostiles)に止められないようにする
            // (的のハスクは敵対。プレイヤーがスポーンエッグで置いたのと同じ扱い)。
            mob.addTag(TdHostiles.EXEMPT_TAG);
            this.level.addFreshEntity(mob);
            this.spawned.add(mob);
            return mob;
        }

        /** TD の攻め手の群れの一員(出どころ TD・eventId と侵攻の印 = イベントの id。実体化と同じ形)。 */
        <T extends Mob> T member(EntityType<T> type, Vec3 rel, UUID swarmId, int slot, int peak, String eventId,
                                 boolean noAi) {
            return mob(type, rel, noAi, new SwarmMembership(swarmId, slot, SwarmOrigin.TD, eventId, peak), eventId);
        }

        /** {@link #member} の、壁・天井を登る群れ({@code Swarm#climber})の一員。 */
        <T extends Mob> T climber(EntityType<T> type, Vec3 rel, UUID swarmId, int slot, int peak, String eventId) {
            return mob(type, rel, false, new SwarmMembership(swarmId, slot, SwarmOrigin.TD, eventId, peak, true), eventId);
        }

        /** 床の上(y = 2)の NoAI のハスク(日に焼けない)。{@code tdEventId} があれば侵攻の印を付ける。 */
        Husk husk(double x, double z, @Nullable String tdEventId) {
            return mob(EntityType.HUSK, new Vec3(x, 2.0D, z), true, null, tdEventId);
        }

        /** 実体化した攻め手を探す箱(実体化の置き場は中心 ± 数ブロック、置けなければ輪の上を 8 ブロックずつずらす)。 */
        AABB attackerSweep() {
            return this.helper.getBounds().inflate(40.0D);
        }

        void pass() {
            if (this.done) {
                return;
            }
            cleanUp();
            this.helper.succeed();
        }

        /** 片付けてから失敗にする(GameTestHelper#fail は例外を投げる)。2 回目からは何もしない。 */
        void fail(String message) {
            if (this.done) {
                return;
            }
            cleanUp();
            this.helper.fail(message);
        }

        private void cleanUp() {
            this.done = true;
            for (Entity e : List.copyOf(this.spawned)) {
                if (!e.isRemoved()) {
                    e.discard();
                }
            }
            // 侵攻の攻め手(実体化したもの)は印が外れる前に消す。落ちたアイテム・経験値はテスト場のまわりだけ。
            AABB near = this.helper.getBounds().inflate(2.0D);
            for (Entity e : this.level.getEntitiesOfClass(Entity.class, attackerSweep(),
                    e -> e instanceof Mob m && EventMobs.taggedAt(m, EventMobs.Kind.TD) >= 0L
                            || (e instanceof ItemEntity || e instanceof ExperienceOrb) && near.intersects(e.getBoundingBox()))) {
                e.discard();
            }
            if (TdData.get(this.server).live() != null) {
                TdTicker.forceStop(this.server);
            }
            BlockState air = Blocks.AIR.defaultBlockState();
            for (int i = this.built.size() - 1; i >= 0; i--) {
                this.level.setBlock(this.built.get(i), air, Block.UPDATE_CLIENTS);
            }
            TdTicker.setIgnorePlayersForTests(false);
            TdTestAccess.setViewers(null);
            DesignData.setTdForTests(this.table);
            EcosystemData.get(this.server).setBiomass(this.biomass);
            TdTestAccess.restore(this.server, this.ledger);
            com.yayuy.overgrownvegetation.spore.SporeDeviceData.restoreForTests(this.level, this.devices);
        }
    }

    private TdGameTests() {
    }
}

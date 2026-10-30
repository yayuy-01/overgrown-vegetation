package com.yayuy.overgrownvegetation.gametest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.VelophyllEntity;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModEntities;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmMember;
import com.yayuy.overgrownvegetation.swarm.SwarmMembership;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmPaths;
import com.yayuy.overgrownvegetation.swarm.SwarmTable;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Husk;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * G4 の<b>群れ v2</b>(2026-09-25、{@code swarm/})の GameTest。バッチ {@value #BATCH}(ほかのクラスのテストと同時に走らない)。
 *
 * <h2>守りたいこと(設計 v2 の「Tests」)</h2>
 * <ul>
 *     <li>先導役は常に 1 体で、前衛(アルボラが先)を選ぶ。死んだら 1 回考えるうちに選び直す({@link #vanguardPrefersFrontAndReelectsOnDeath})。</li>
 *     <li>ふつうに付いていく後続は経路を引かない(立て直しの数えが 0)。先導役が 20 ブロック歩いたあと、後続は自分の陣形の位置から
 *     遅れの距離の内側にいる。先導役が死んだら、新しい先導役は前の先導役の<b>区間を引き継いで</b>歩き、引き直さない
 *     ({@link #followersKeepFormationAndInheritTheLeg})。</li>
 *     <li>殴られた後続はその場で自分だけ反撃し、プレイヤーに殴られたら群れの標的がそのプレイヤーへ替わる
 *     ({@link #hurtFollowerRetaliatesAloneAndPlayerTakesTheSwarm})。</li>
 *     <li>同じ群れどうしは押し合わず、違う群れとは押し合う({@link #sameSwarmMembersDoNotPush})。</li>
 *     <li>残党(3 体以上だった群れが 2 体以下)は、鍵の合う・空きのある群れへだけ移る。上限を超えない。出どころが違えば移らない。
 *     テストの群れは合流しない({@link #remnantJoinsOnlyACompatibleSwarmWithRoom})。</li>
 *     <li>プレイヤーが 1 人もいなければ消さない。一員は {@code removeWhenFarAway} が false({@link #naturalSwarmStaysWithoutPlayers})。</li>
 *     <li>空の群れの後続は先頭の近くを飛び続ける({@link #airFollowersStayNearTheLeader})。</li>
 *     <li>スポロフィルには標的を渡さない({@link #sporophyllIsNeverHandedATarget})。</li>
 * </ul>
 *
 * <h2>テスト場の決まり(メモリ「GameTest のテスト場の作り」)</h2>
 * <ul>
 *     <li>床は相対 y = 1、立たせるのは y = 2。外周と天井(相対 y ≒ 8)はバリア。同じバッチのテストは 12〜13 ブロック間隔に並んで同時に走る。</li>
 *     <li>群れはテストの群れ({@link SwarmOrigin#TEST}: 合流しない・消えない)にして、標的を探す範囲を
 *     {@link Swarm#setTestBounds} でそのテストの箱に絞る(隣のテストのハスク・群れを狙わない)。合流を確かめるテストだけは
 *     合流する出どころ(SUMMON / DEFENSE)を使い、イベント ID をテストごとの UUID にして、ほかのテストの群れと鍵が合わないようにする。</li>
 *     <li>湧かせ方は {@code SwarmSpawner} と同じ順番(所属を <b>addFreshEntity の前に</b>書く → Join で登録)。置き場所は決め打ち
 *     ({@code SwarmSpawner} は中心のまわりにばらまくので、7×7 の箱では天井のバリアの上に立つことがある)。</li>
 *     <li>標的・反撃は群れの仕組みだけを見たいので、動かなくてよいテストは NoAI にする({@code spawnWithNoFreeWill} では
 *     標的の Goal が残る。群れの一員は個体の索敵を止めているが、止め忘れがあれば別のテストで見える)。</li>
 *     <li>7×7 に収まらないテスト(通路を 20 ブロック歩く・空を飛ぶ)は、テスト場の<b>上空</b>に通路を作り、そのチャンクを
 *     強制読み込みにする(GameTest サーバーにはプレイヤーがいないので、強制読み込みのチャンクでしかエンティティが動かない)。
 *     強制読み込みはバッチの終わりにバニラがまとめて外す(こちらで外すと、同じチャンクを使う隣のテストのエンティティまで止まる)。</li>
 *     <li>テスト場の外に置いた Mob・ブロックは {@code succeed} でも消えないので、{@link Stage} が終わるときに片付ける。</li>
 * </ul>
 *
 * <p>確かめられないこと: 「プレイヤーから遠いと群れごと消える / 永続の一員は残る」は、GameTest サーバーに本物のプレイヤーを
 * 置けない(モックの ServerPlayer を入れると、ログイン時の処理が偽の接続へパケットを送る)ので、ここでは「プレイヤーがいなければ
 * 消さない」側だけを見る。
 */
@GameTestHolder(OvergrownVegetation.MODID)
@PrefixGameTestTemplate(false)
public final class SwarmGameTests {

    private static final String TEMPLATE = "gametest_empty7";
    private static final String BATCH = "swarm";
    /** +X(東)を向く yRot。 */
    private static final float EAST = -90.0F;

    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD)
    public static final class Registration {
        @SubscribeEvent
        public static void onRegisterGameTests(RegisterGameTestsEvent event) {
            event.register(SwarmGameTests.class);
        }

        private Registration() {
        }
    }

    // --- 先導役の選び方 ---

    /**
     * 先導役は<b>前衛(アルボラが先)</b>から選ぶ。番号の小さいスポロフィル(支援)・スカトゥリウムがいても、番号 2 のアルボラが先導役。
     * 倒すと、倒れて横たわっている間(20 tick)に 1 回考えれば選び直す(死んだ一員は「動いていない」扱い)。
     * アルボラ → アルボラ → スカトゥリウム → (前衛がいなくなったら)スポロフィルの順。どの時点でも先導役は 1 体。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void vanguardPrefersFrontAndReelectsOnDeath(GameTestHelper helper) {
        Stage s = new Stage(helper);
        UUID id = UUID.randomUUID();
        Mob sporophyll = s.arenaMember(ModEntities.SPOROPHYLL.get(), 1.5D, 1.5D, id, 0, 4, SwarmOrigin.TEST, "");
        Mob shielder = s.arenaMember(ModEntities.OVERGROWN_SHIELDER.get(), 5.0D, 1.8D, id, 1, 4, SwarmOrigin.TEST, "");
        Mob first = s.arenaMember(ModEntities.ARBORA.get(), 1.5D, 5.5D, id, 2, 4, SwarmOrigin.TEST, "");
        Mob second = s.arenaMember(ModEntities.ARBORA.get(), 4.5D, 5.5D, id, 3, 4, SwarmOrigin.TEST, "");
        if (s.swarm(id, helper.getBounds()) == null) {
            return;
        }
        List<Mob> order = List.of(first, second, shielder, sporophyll);
        int interval = DesignData.swarm().thinkIntervalTicks();
        int[] stage = {0};
        long[] changedAt = {s.level.getGameTime()};
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            long now = s.level.getGameTime();
            Swarm swarm = SwarmManager.get(s.level, id);
            if (swarm == null) {
                s.fail("群れが名簿から消えた(倒したのは " + stage[0] + " 体)");
                return;
            }
            int vanguards = 0;
            for (Mob m : order) {
                if (SwarmMember.isVanguard(m)) {
                    vanguards++;
                }
            }
            if (vanguards > 1) {
                s.fail("先導役が " + vanguards + " 体いる");
                return;
            }
            Mob vanguard = swarm.vanguard();
            Mob expected = order.get(stage[0]);
            if (vanguard == expected) {
                if (!expected.isAlive()) {
                    s.fail("死んだ一員が先導役のまま: " + name(swarm, expected));
                    return;
                }
                if (stage[0] == order.size() - 1) {
                    s.pass();
                    return;
                }
                expected.kill();
                if (expected.isAlive()) {
                    s.fail("倒せなかった: " + name(swarm, expected));
                    return;
                }
                changedAt[0] = now;
                stage[0]++;
                return;
            }
            // 最初に考えるまで(先導役なし)と、倒してから次に考えるまで(倒れた先導役のまま)は待つ。
            if (now - changedAt[0] > interval + 1) {
                s.fail((stage[0] == 0 ? "最初に考えたあと" : "先導役を倒して 1 回考えたあと") + "の先導役が "
                        + name(swarm, vanguard) + "(期待は " + name(swarm, expected) + "。前衛 → アルボラ → 番号の順)");
            }
        });
    }

    // --- 陣形・経路を引かない後続・区間の引き継ぎ ---

    /** 通路の床の相対の高さ(テスト場の天井のバリア = 相対 8 より上)。 */
    private static final int CORRIDOR_FLOOR_Y = 12;
    /** 通路の長さ(相対 x = 0 .. この値)。 */
    private static final int CORRIDOR_END_X = 76;
    /** 先導役がこれだけ歩いたら陣形を確かめる(ブロック、水平)。設計 v2 の「20 blocks」。 */
    private static final double WALK_DISTANCE = 20.0D;

    /**
     * テスト場の上空に 7 幅・77 長の通路を作り、アルボラ 5 体の群れ(先導役 + 後続 4)に通路の端のプレイヤー(モック)を狩らせる。
     * <ol>
     *     <li>先導役が 20 ブロック歩いた時点で、後続全員が自分の陣形の位置から {@code laggardDistance} 以内にいて、遅れている一員がいない
     *     (陣形の位置は足跡の上。横の並びにずれた位置も含む)。後続は歩いている(出発点から 12 ブロック以上)。</li>
     *     <li>その間、後続の立て直し(= 後続が引く経路)が 1 本も無い。群れの経路は先導役の区間だけ。</li>
     *     <li>次の区間を歩き始めた先導役を倒すと、1 回考えるうちに番号 1 のアルボラが先導役になり、前の先導役の<b>区間の写し</b>を
     *     歩き始める(経路の行き先が同じで、区間の数えが増えない = 引き直していない)。</li>
     * </ol>
     * 標的はモックのプレイヤー(レベルに入っていない)で、群れの「湧かせた側の指定」({@link Swarm#commandTarget})= 距離の上限なしの狩り。
     * 通路の端(先導役の出発点から 62 ブロック)に置くので、テストの間に囲む距離(12)へ入らない。
     * スポロフィルは入れない(足元を汚し続けるので、先導役の区間の近くのブロックが変わって引き直しが区間の数えに入る)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 700)
    public static void followersKeepFormationAndInheritTheLeg(GameTestHelper helper) {
        Stage s = new Stage(helper);
        CorridorRun run = new CorridorRun(s);
        run.chunks = s.forceChunks(-2, -3, CORRIDOR_END_X + 3, 10);
        helper.onEachTick(run::tick);
    }

    /** {@link #followersKeepFormationAndInheritTheLeg} の進み具合。 */
    private static final class CorridorRun {
        private enum Phase { LOADING, WALKING, FORMATION, WAIT_LEG, KILL_WAIT, AFTER_KILL }

        /** チャンクの読み込みを待つ上限(tick)。 */
        private static final int LOAD_LIMIT = 150;
        /** 先導役が 20 ブロック歩くまでの上限(tick)。遅れを待つ上限 300 を含む(ふつうは 60 tick ほど)。 */
        private static final int WALK_LIMIT = 330;
        /** 次の区間を歩き始めるまでの上限(tick)。 */
        private static final int NEXT_LEG_LIMIT = 150;
        /** 次の区間を歩き始めてから先導役を倒すまで(tick)。区間の頭のほうで倒す(新しい先導役に近いノードが区間の残りに入る)。 */
        private static final int KILL_DELAY = 5;

        final Stage s;
        List<ChunkPos> chunks = List.of();
        Phase phase = Phase.LOADING;
        final UUID id = UUID.randomUUID();
        final List<Mob> followers = new ArrayList<>();
        final List<Vec3> followerStarts = new ArrayList<>();
        Mob lead;
        Vec3 leadStart;
        long phaseAt;
        long legsAtCheck;
        @Nullable
        Path pathAtCheck;
        @Nullable
        BlockPos oldLegTarget;
        long legsBeforeKill;
        long electedAt = -1L;

        CorridorRun(Stage s) {
            this.s = s;
        }

        void tick() {
            if (this.s.done) {
                return;
            }
            long now = this.s.level.getGameTime();
            if (this.phase == Phase.LOADING) {
                if (this.s.chunksTicking(this.chunks, CORRIDOR_FLOOR_Y)) {
                    setUp(now);
                } else if (this.s.helper.getTick() > LOAD_LIMIT) {
                    this.s.fail("通路のチャンク(" + this.chunks.size() + " 個)が " + LOAD_LIMIT + " tick で動く状態にならない");
                }
                return;
            }
            Swarm swarm = SwarmManager.get(this.s.level, this.id);
            if (swarm == null) {
                this.s.fail("群れが名簿から消えた(段階 " + this.phase + ")");
                return;
            }
            switch (this.phase) {
                case WALKING -> walking(swarm, now);
                case FORMATION -> formation(swarm, now);
                case WAIT_LEG -> waitLeg(swarm, now);
                case KILL_WAIT -> killWait(swarm, now);
                case AFTER_KILL -> afterKill(swarm, now);
                default -> {
                }
            }
        }

        private void setUp(long now) {
            Stage st = this.s;
            int y = CORRIDOR_FLOOR_Y;
            BlockState floor = Blocks.STONE.defaultBlockState();
            BlockState rail = Blocks.GLASS.defaultBlockState();
            for (int x = -1; x <= CORRIDOR_END_X + 1; x++) {
                for (int z = -1; z <= 7; z++) {
                    boolean inside = x >= 0 && x <= CORRIDOR_END_X && z >= 0 && z <= 6;
                    st.block(new BlockPos(x, y, z), floor);
                    if (!inside) {
                        // 手すり(落ちないように。2 段)。
                        st.block(new BlockPos(x, y + 1, z), rail);
                        st.block(new BlockPos(x, y + 2, z), rail);
                    }
                }
            }
            double feet = y + 1;
            EntityType<ArboraEntity> arbora = ModEntities.ARBORA.get();
            // 陣形どおりに並べておく(1 列目: 中央・右・左、2 列目: 中央。右 = 東を向いて右 = +Z)。
            this.lead = st.member(arbora, new Vec3(12.5D, feet, 3.5D), EAST, this.id, 0, 5, SwarmOrigin.TEST, "", false);
            this.followers.add(st.member(arbora, new Vec3(10.0D, feet, 3.5D), EAST, this.id, 1, 5, SwarmOrigin.TEST, "", false));
            this.followers.add(st.member(arbora, new Vec3(10.0D, feet, 5.5D), EAST, this.id, 2, 5, SwarmOrigin.TEST, "", false));
            this.followers.add(st.member(arbora, new Vec3(10.0D, feet, 1.5D), EAST, this.id, 3, 5, SwarmOrigin.TEST, "", false));
            this.followers.add(st.member(arbora, new Vec3(7.5D, feet, 3.5D), EAST, this.id, 4, 5, SwarmOrigin.TEST, "", false));
            this.leadStart = this.lead.position();
            for (Mob f : this.followers) {
                this.followerStarts.add(f.position());
            }
            AABB bounds = st.box(-2, y - 2, -3, CORRIDOR_END_X + 3, y + 8, 10);
            st.sweep = bounds;
            Swarm swarm = st.swarm(this.id, bounds);
            if (swarm == null) {
                return;
            }
            Player quarry = st.mockPlayer(new Vec3(CORRIDOR_END_X - 1.5D, feet, 3.5D));
            swarm.commandTarget(quarry);
            this.phase = Phase.WALKING;
            this.phaseAt = now;
        }

        private void walking(Swarm swarm, long now) {
            SwarmTable table = DesignData.swarm();
            Mob vanguard = swarm.vanguard();
            if (vanguard == null) {
                if (now - this.phaseAt > table.thinkIntervalTicks() + 1) {
                    this.s.fail("最初に考えても先導役が決まらない");
                }
                return;
            }
            if (vanguard != this.lead) {
                this.s.fail("先導役が番号 0 のアルボラでない: " + name(swarm, vanguard));
                return;
            }
            double walked = horizontal(this.lead.position(), this.leadStart);
            if (walked >= WALK_DISTANCE) {
                this.phase = Phase.FORMATION;
                this.phaseAt = now;
                formation(swarm, now);
                return;
            }
            if (now - this.phaseAt > WALK_LIMIT) {
                this.s.fail(String.format(Locale.ROOT, "先導役が %d tick で %.0f ブロック歩かない(歩いたのは %.1f、モード %s、標的 %s、"
                                + "待ち %s、区間 %d 本)", WALK_LIMIT, WALK_DISTANCE, walked, swarm.mode(), swarm.target(),
                        swarm.shouldVanguardWait(), swarm.pathRequests(SwarmPaths.RequestKind.LEG)));
            }
        }

        /**
         * 陣形を確かめる。考えるのは 10 tick ごとで、陣形の位置はその時点の値なので、2 回考えるぶんの間に
         * 「全員が遅れの距離の内側・遅れの印なし」の瞬間があればよい。
         */
        private void formation(Swarm swarm, long now) {
            SwarmTable table = DesignData.swarm();
            double tolerance = table.laggardDistance();
            double worst = 0.0D;
            String worstWho = "";
            boolean ok = !swarm.anyLagging();
            for (Mob f : this.followers) {
                Vec3 point = swarm.formationPoint(f);
                if (point == null || !swarm.isFollower(f)) {
                    ok = false;
                    worstWho = name(swarm, f) + " に陣形の位置が無い";
                    worst = Double.MAX_VALUE;
                    break;
                }
                double d = f.position().distanceTo(point);
                if (d > worst) {
                    worst = d;
                    worstWho = name(swarm, f);
                }
                ok &= d <= tolerance && !swarm.isLagging(f);
            }
            long recoveries = swarm.pathRequests(SwarmPaths.RequestKind.RECOVERY);
            if (recoveries != 0L) {
                this.s.fail("ふつうに付いていく後続が経路を引いた(立て直し " + recoveries + " 本)");
                return;
            }
            if (!ok) {
                if (now - this.phaseAt > 2L * table.thinkIntervalTicks()) {
                    this.s.fail(String.format(Locale.ROOT, "先導役が %.0f ブロック歩いたあと、陣形から外れている: いちばん遠いのは %s(%.2f、許容 %.1f)、"
                            + "遅れの印 %s", WALK_DISTANCE, worstWho, worst, tolerance, swarm.anyLagging()));
                }
                return;
            }
            for (int i = 0; i < this.followers.size(); i++) {
                Mob f = this.followers.get(i);
                double moved = horizontal(f.position(), this.followerStarts.get(i));
                if (moved < WALK_DISTANCE - 8.0D) {
                    this.s.fail(String.format(Locale.ROOT, "後続 %s が付いてきていない(動いたのは %.1f ブロック)", name(swarm, f), moved));
                    return;
                }
            }
            if (swarm.pathRequests(SwarmPaths.RequestKind.LEG) < 1L) {
                this.s.fail("先導役の区間が数えに入っていない(区間を使わずに歩いた?)");
                return;
            }
            this.phase = Phase.WAIT_LEG;
            this.phaseAt = now;
            this.legsAtCheck = swarm.pathRequests(SwarmPaths.RequestKind.LEG);
            this.pathAtCheck = this.lead.getNavigation().getPath();
        }

        /** 次の区間を歩き始めるのを待つ(区間の頭で倒すため)。 */
        private void waitLeg(Swarm swarm, long now) {
            Path path = this.lead.getNavigation().getPath();
            if (swarm.pathRequests(SwarmPaths.RequestKind.LEG) > this.legsAtCheck && path != null && path != this.pathAtCheck
                    && !path.isDone() && swarm.vanguard() == this.lead) {
                this.phase = Phase.KILL_WAIT;
                this.phaseAt = now;
                return;
            }
            if (now - this.phaseAt > NEXT_LEG_LIMIT) {
                this.s.fail("先導役が次の区間を歩き始めない(区間 " + swarm.pathRequests(SwarmPaths.RequestKind.LEG) + " 本、モード "
                        + swarm.mode() + "、待ち " + swarm.shouldVanguardWait() + ")");
            }
        }

        private void killWait(Swarm swarm, long now) {
            if (now - this.phaseAt < KILL_DELAY) {
                return;
            }
            Path path = this.lead.getNavigation().getPath();
            if (path == null || path.isDone() || swarm.vanguard() != this.lead) {
                // 遅れを待って区間を捨てた・もう歩き終えた。次の区間を待ち直す。
                this.phase = Phase.WAIT_LEG;
                this.phaseAt = now;
                this.legsAtCheck = swarm.pathRequests(SwarmPaths.RequestKind.LEG);
                this.pathAtCheck = path;
                return;
            }
            this.oldLegTarget = path.getTarget();
            this.legsBeforeKill = swarm.pathRequests(SwarmPaths.RequestKind.LEG);
            this.lead.kill();
            if (this.lead.isAlive()) {
                this.s.fail("先導役を倒せなかった");
                return;
            }
            this.phase = Phase.AFTER_KILL;
            this.phaseAt = now;
        }

        private void afterKill(Swarm swarm, long now) {
            int interval = DesignData.swarm().thinkIntervalTicks();
            Mob vanguard = swarm.vanguard();
            if (vanguard == null || vanguard == this.lead) {
                if (now - this.phaseAt > interval + 1) {
                    this.s.fail("先導役を倒して 1 回考えても選び直さない(先導役 " + name(swarm, vanguard) + ")");
                }
                return;
            }
            Mob expected = this.followers.get(0);
            if (vanguard != expected) {
                this.s.fail("選び直した先導役が番号 1 のアルボラでない: " + name(swarm, vanguard));
                return;
            }
            if (this.electedAt < 0L) {
                this.electedAt = now;
                if (now - this.phaseAt > interval + 1) {
                    this.s.fail("選び直しが 1 回考えるより遅い(" + (now - this.phaseAt) + " tick)");
                    return;
                }
            }
            Path path = vanguard.getNavigation().getPath();
            if (path == null || path.isDone()) {
                if (now - this.electedAt > 6L) {
                    this.s.fail("新しい先導役が歩き始めない(引き継いだ区間が使われていない、または遅れを待っている: "
                            + swarm.shouldVanguardWait() + ")");
                }
                return;
            }
            if (!path.getTarget().equals(this.oldLegTarget)) {
                this.s.fail("新しい先導役の経路の行き先 " + path.getTarget().toShortString() + " が前の区間の行き先 "
                        + this.oldLegTarget.toShortString() + " と違う(区間を引き継がずに引き直した?)");
                return;
            }
            long legs = swarm.pathRequests(SwarmPaths.RequestKind.LEG);
            if (legs != this.legsBeforeKill) {
                this.s.fail("新しい先導役が区間を引き直した(区間の数え " + this.legsBeforeKill + " → " + legs + ")");
                return;
            }
            this.s.pass();
        }
    }

    // --- 反撃 ---

    /**
     * 殴られた後続は<b>その一員だけ</b>その相手へ反撃する(群れの標的は変えない)。プレイヤーに殴られたら、群れの標的が
     * (今の標的が反撃より下の「そのほか」なので)そのプレイヤーへ替わり、次に考えたときに殴られていない先導役にも渡る。
     * 自分だけの反撃は 100 tick 続くので、先に殴られた後続はハスクを狙ったまま。
     * 一員は NoAI(標的の出どころが群れの仕組みだけであることを見る。囲む Goal が標的を倒してしまわないように)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 100)
    public static void hurtFollowerRetaliatesAloneAndPlayerTakesTheSwarm(GameTestHelper helper) {
        Stage s = new Stage(helper);
        UUID id = UUID.randomUUID();
        EntityType<ArboraEntity> arbora = ModEntities.ARBORA.get();
        Mob lead = s.arenaMember(arbora, 1.5D, 1.5D, id, 0, 3, SwarmOrigin.TEST, "");
        Mob near = s.arenaMember(arbora, 3.5D, 1.5D, id, 1, 3, SwarmOrigin.TEST, "");
        Mob bitten = s.arenaMember(arbora, 5.5D, 1.5D, id, 2, 3, SwarmOrigin.TEST, "");
        Husk quarry = s.husk(1.5D, 5.5D);
        Husk biter = s.husk(5.5D, 5.5D);
        Player player = s.mockPlayer(new Vec3(3.5D, 2.0D, 5.5D));
        Swarm swarm = s.swarm(id, helper.getBounds());
        if (swarm == null) {
            return;
        }
        swarm.commandTarget(quarry);
        int interval = DesignData.swarm().thinkIntervalTicks();

        helper.runAfterDelay(interval + 1L, () -> {
            Swarm sw = s.swarmNow(id);
            if (sw == null || s.done) {
                return;
            }
            if (sw.target() != quarry || lead.getTarget() != quarry || near.getTarget() != quarry || bitten.getTarget() != quarry) {
                s.fail("考えたあとに群れの標的(ハスク)が全員へ渡っていない: 群れ " + sw.target() + " / 先導役 " + lead.getTarget()
                        + " / 後続 " + near.getTarget() + ", " + bitten.getTarget());
                return;
            }
            if (!sw.isFollower(bitten)) {
                s.fail("番号 2 のアルボラが後続でない(先導役 " + name(sw, sw.vanguard()) + ")");
                return;
            }
            if (!bitten.hurt(s.level.damageSources().mobAttack(biter), 1.0F)) {
                s.fail("ハスクの攻撃が後続に効かない(確かめられない)");
                return;
            }
            if (bitten.getTarget() != biter || sw.localTarget(bitten) != biter) {
                s.fail("殴られた後続がその場で殴った相手を狙わない: " + bitten.getTarget());
                return;
            }
            if (sw.target() != quarry) {
                s.fail("プレイヤーでない相手に殴られて群れの標的が替わった: " + sw.target());
            }
        });
        helper.runAfterDelay(2L * interval + 3L, () -> {
            Swarm sw = s.swarmNow(id);
            if (sw == null || s.done) {
                return;
            }
            if (bitten.getTarget() != biter) {
                s.fail("次に考えたら、殴られた後続の反撃が群れの標的で上書きされた: " + bitten.getTarget());
                return;
            }
            if (lead.getTarget() != quarry || near.getTarget() != quarry || sw.target() != quarry) {
                s.fail("殴られていない一員まで反撃した(群れで反撃した): 先導役 " + lead.getTarget() + " / 後続 " + near.getTarget()
                        + " / 群れ " + sw.target());
                return;
            }
            if (!near.hurt(s.level.damageSources().playerAttack(player), 1.0F)) {
                s.fail("プレイヤー(モック)の攻撃が後続に効かない(確かめられない)");
                return;
            }
            if (sw.target() != player || sw.targetReason() != Swarm.TargetReason.RETALIATION) {
                s.fail("プレイヤーに殴られても群れの標的が替わらない: " + sw.target() + "(理由 " + sw.targetReason() + ")");
                return;
            }
            if (near.getTarget() != player) {
                s.fail("プレイヤーに殴られた後続がそのプレイヤーを狙わない: " + near.getTarget());
            }
        });
        helper.runAfterDelay(3L * interval + 5L, () -> {
            Swarm sw = s.swarmNow(id);
            if (sw == null || s.done) {
                return;
            }
            if (sw.target() != player) {
                s.fail("次に考えたら、群れの標的がプレイヤーから外れた: " + sw.target() + "(理由 " + sw.targetReason() + ")");
                return;
            }
            if (lead.getTarget() != player) {
                s.fail("群れの標的になったプレイヤーが、殴られていない先導役へ渡らない: " + lead.getTarget());
                return;
            }
            if (bitten.getTarget() != biter) {
                s.fail("先に殴られた後続の反撃(100 tick)が途中で切れた: " + bitten.getTarget());
                return;
            }
            s.pass();
        });
    }

    // --- 押し合い ---

    /**
     * 同じ群れの一員どうしは押し合わない(縦に並んだ群れが体で陣形を崩し、押し出された後続が立て直しの経路を引かないように)。
     * 違う群れの一員とは今までどおり押し合う。{@code push(Entity)} を直に呼ぶ確かめと、重ねて置いて数 tick 待つ確かめの両方。
     *
     * <p>重ねて置く確かめは<b>位置ではなく速度</b>で見る(2026-09-25 のレビュー指摘)。一員は NoAI なので位置は動かない ——
     * {@code LivingEntity#travel} は {@code isControlledByLocalInstance()}(= {@code Mob#isEffectiveAi} = !NoAI)のときだけ動かす。
     * 押し合い({@code pushEntities})は NoAI でも毎 tick 走って速度だけを書く。はじめは 20 tick 後の距離を比べていたので、
     * 違う群れの 2 体も 0.4 のまま離れず必ず失敗し、同じ群れの側は何を確かめてもいなかった。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 60)
    public static void sameSwarmMembersDoNotPush(GameTestHelper helper) {
        Stage s = new Stage(helper);
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EntityType<ArboraEntity> arbora = ModEntities.ARBORA.get();
        Mob a1 = s.arenaMember(arbora, 1.5D, 1.5D, a, 0, 3, SwarmOrigin.TEST, "");
        Mob a2 = s.arenaMember(arbora, 1.8D, 1.5D, a, 1, 3, SwarmOrigin.TEST, "");
        Mob a3 = s.arenaMember(arbora, 4.9D, 5.5D, a, 2, 3, SwarmOrigin.TEST, "");
        Mob b1 = s.arenaMember(arbora, 5.3D, 5.5D, b, 0, 1, SwarmOrigin.TEST, "");
        if (s.swarm(a, helper.getBounds()) == null || s.swarm(b, helper.getBounds()) == null) {
            return;
        }
        List<Mob> all = List.of(a1, a2, a3, b1);
        all.forEach(m -> m.setDeltaMovement(Vec3.ZERO));
        a1.push(a2);
        if (moved(a1) || moved(a2)) {
            s.fail("同じ群れの一員どうしが押し合った(push)");
            return;
        }
        a3.push(b1);
        if (!moved(a3) || !moved(b1)) {
            s.fail("違う群れの一員が押し合わない(push)");
            return;
        }
        all.forEach(m -> m.setDeltaMovement(Vec3.ZERO));
        // 後続は押し合いの走査を 2 tick に 1 回しかしないので 3 tick 待つ(a3 は後続、b1 は 1 体の群れの先導役)。
        helper.runAfterDelay(3L, () -> {
            if (s.done) {
                return;
            }
            if (moved(a1) || moved(a2)) {
                s.fail(String.format(Locale.ROOT, "重ねて置いた同じ群れの 2 体に押し合いの速度が付いた: %s / %s",
                        a1.getDeltaMovement(), a2.getDeltaMovement()));
                return;
            }
            if (!moved(a3) || !moved(b1)) {
                s.fail(String.format(Locale.ROOT, "重ねて置いた違う群れの 2 体に押し合いの速度が付かない: %s / %s",
                        a3.getDeltaMovement(), b1.getDeltaMovement()));
                return;
            }
            s.pass();
        });
    }

    // --- 残党 ---

    /**
     * 残党の合流。3 体だった群れ R(SUMMON)の後続を 1 体倒して 2 体にすると、組み直し直後の猶予(100 tick)のあと、
     * <b>同じ種類・出どころ・イベント・狙い</b>で空きのある群れ M へ全員で移る。より近くにある
     * <ul>
     *     <li>出どころだけが違う群れ D(DEFENSE、同じイベント ID)</li>
     *     <li>鍵は合うが満杯の群れ F(上限 {@code maxSize}。上限の手前から 1 体入れて満杯にし、もう 1 体は {@code join} が断る)</li>
     * </ul>
     * へは移らない。テストの群れ T(3 → 2 体)は、すぐ隣に鍵の合うテストの群れ T2 がいても合流しない。
     * どの群れも上限を超えない。イベント ID はこのテストだけの UUID(同じバッチのほかの群れと鍵が合わないように)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 240)
    public static void remnantJoinsOnlyACompatibleSwarmWithRoom(GameTestHelper helper) {
        Stage s = new Stage(helper);
        SwarmTable table = DesignData.swarm();
        int max = table.maxSize(Swarm.Kind.GROUND);
        int interval = table.thinkIntervalTicks();
        int settle = table.settleWindowTicks();
        if (max < 3 || max + 13 > 49) {
            s.fail("maxSize(地上)= " + max + " ではテスト場(7×7)に並ばない");
            return;
        }
        if (settle + 3 * interval + 40 > 230) {
            s.fail("settleWindowTicks = " + settle + " が長すぎてこのテストの時間(240 tick)に収まらない");
            return;
        }
        String event = "swarm-gametest-" + UUID.randomUUID();
        String testEvent = "swarm-gametest-" + UUID.randomUUID();
        EntityType<ArboraEntity> arbora = ModEntities.ARBORA.get();
        boolean[][] used = new boolean[7][7];

        UUID rId = UUID.randomUUID();
        Mob r0 = s.cellMember(used, arbora, 0, 0, rId, 0, 3, SwarmOrigin.SUMMON, event);
        Mob r1 = s.cellMember(used, arbora, 1, 0, rId, 1, 3, SwarmOrigin.SUMMON, event);
        Mob r2 = s.cellMember(used, arbora, 2, 0, rId, 2, 3, SwarmOrigin.SUMMON, event);
        UUID mId = UUID.randomUUID();
        s.cellMember(used, arbora, 6, 0, mId, 0, 2, SwarmOrigin.SUMMON, event);
        s.cellMember(used, arbora, 6, 1, mId, 1, 2, SwarmOrigin.SUMMON, event);
        UUID dId = UUID.randomUUID();
        s.cellMember(used, arbora, 0, 2, dId, 0, 2, SwarmOrigin.DEFENSE, event);
        s.cellMember(used, arbora, 1, 2, dId, 1, 2, SwarmOrigin.DEFENSE, event);
        UUID tId = UUID.randomUUID();
        Mob t0 = s.cellMember(used, arbora, 3, 2, tId, 0, 3, SwarmOrigin.TEST, testEvent);
        Mob t1 = s.cellMember(used, arbora, 4, 2, tId, 1, 3, SwarmOrigin.TEST, testEvent);
        Mob t2 = s.cellMember(used, arbora, 5, 2, tId, 2, 3, SwarmOrigin.TEST, testEvent);
        UUID t2Id = UUID.randomUUID();
        s.cellMember(used, arbora, 3, 3, t2Id, 0, 2, SwarmOrigin.TEST, testEvent);
        s.cellMember(used, arbora, 4, 3, t2Id, 1, 2, SwarmOrigin.TEST, testEvent);
        // 満杯の手前の群れ F。先導役(番号 0)は R の先導役から 4 ブロック(D の 2 と M の 6 の間)。
        UUID fId = UUID.randomUUID();
        s.cellMember(used, arbora, 0, 4, fId, 0, max - 1, SwarmOrigin.SUMMON, event);
        for (int slot = 1; slot < max - 1; slot++) {
            int[] cell = freeCell(used);
            s.cellMember(used, arbora, cell[0], cell[1], fId, slot, max - 1, SwarmOrigin.SUMMON, event);
        }
        int[] c1 = freeCell(used);
        Mob extra = s.cellLoner(used, arbora, c1[0], c1[1]);
        int[] c2 = freeCell(used);
        Mob overflow = s.cellLoner(used, arbora, c2[0], c2[1]);

        AABB bounds = helper.getBounds();
        Swarm f = s.swarm(fId, bounds);
        if (s.swarm(rId, bounds) == null || s.swarm(mId, bounds) == null || s.swarm(dId, bounds) == null
                || s.swarm(tId, bounds) == null || s.swarm(t2Id, bounds) == null || f == null) {
            return;
        }
        // 上限: 上限の手前の群れに 1 体入れると満杯、もう 1 体は断られる(所属も残らない)。
        if (SwarmManager.join(extra, f) != f || f.size() != max) {
            s.fail("上限の手前の群れに 1 体入れられない(大きさ " + f.size() + " / 上限 " + max + ")");
            return;
        }
        if (SwarmManager.join(overflow, f) != null || SwarmMember.isInSwarm(overflow) || SwarmMember.hasSwarmAttachment(overflow)
                || f.size() != max) {
            s.fail("満杯の群れにさらに入った(大きさ " + f.size() + " / 上限 " + max + ")");
            return;
        }
        // 残党にする(倒れた一員が消えるまでの 20 tick は「動いていない」ので、消えてから数が合う)。
        r2.kill();
        t2.kill();
        long deadline = settle + 3L * interval + 30L;
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            for (Mob m : List.of(r0, r1)) {
                Swarm sw = SwarmManager.get(m);
                if (sw != null && (sw.id().equals(dId) || sw.id().equals(fId) || sw.id().equals(tId) || sw.id().equals(t2Id))) {
                    s.fail("残党が合流してはいけない群れへ入った: " + sw);
                    return;
                }
            }
            for (Mob m : List.of(t0, t1)) {
                Swarm sw = SwarmManager.get(m);
                if (sw == null || !sw.id().equals(tId)) {
                    s.fail("テストの群れの残りが合流・離脱した(テストの群れは合流しない): " + sw);
                    return;
                }
            }
            for (Swarm sw : SwarmManager.swarms(s.level)) {
                if (sw.size() > table.maxSize(sw.kind())) {
                    s.fail("上限を超えた群れがある: " + sw);
                    return;
                }
            }
            Swarm ofR0 = SwarmManager.get(r0);
            Swarm ofR1 = SwarmManager.get(r1);
            if (ofR0 != null && ofR1 != null && ofR0.id().equals(mId) && ofR1.id().equals(mId)) {
                if (helper.getTick() < settle) {
                    s.fail("組み直し直後の猶予(" + settle + " tick)より前に合流した(" + helper.getTick() + " tick)");
                    return;
                }
                Swarm m = ofR0;
                Swarm d = SwarmManager.get(s.level, dId);
                Swarm full = SwarmManager.get(s.level, fId);
                if (m.size() != 4 || d == null || d.size() != 2 || full == null || full.size() != max) {
                    s.fail("合流のあとの大きさが違う: M " + m.size() + "(期待 4)/ D " + (d == null ? "なし" : d.size())
                            + " / F " + (full == null ? "なし" : full.size()) + "(期待 " + max + ")");
                    return;
                }
                if (SwarmManager.get(s.level, rId) != null) {
                    s.fail("全員が移ったのに元の群れが名簿に残っている");
                    return;
                }
                s.pass();
                return;
            }
            if (helper.getTick() > deadline) {
                s.fail("残党が " + deadline + " tick までに合流しない: r0 → " + ofR0 + ", r1 → " + ofR1);
            }
        });
    }

    // --- 消え方 ---

    /**
     * ワールドにプレイヤーが 1 人もいなければ、自然の群れ(消える出どころ)でも消さない(バニラと同じ)。永続の一員も、そうでない一員も残る。
     * 一員の {@code removeWhenFarAway} は常に false(消えるのは群れごと。バニラには消させない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void naturalSwarmStaysWithoutPlayers(GameTestHelper helper) {
        Stage s = new Stage(helper);
        if (!s.level.players().isEmpty()) {
            s.fail("プレイヤーがいるワールドでは確かめられない(GameTest サーバーで走らせること)");
            return;
        }
        UUID id = UUID.randomUUID();
        EntityType<ArboraEntity> arbora = ModEntities.ARBORA.get();
        Mob plain = s.arenaMember(arbora, 1.5D, 1.5D, id, 0, 3, SwarmOrigin.NATURAL, "");
        Mob second = s.arenaMember(arbora, 3.5D, 3.5D, id, 1, 3, SwarmOrigin.NATURAL, "");
        Mob persistent = s.arenaMember(arbora, 5.5D, 5.5D, id, 2, 3, SwarmOrigin.NATURAL, "");
        persistent.setPersistenceRequired();
        if (s.swarm(id, helper.getBounds()) == null) {
            return;
        }
        List<Mob> all = List.of(plain, second, persistent);
        for (Mob m : all) {
            if (m.removeWhenFarAway(1.0E6D)) {
                s.fail("群れの一員の removeWhenFarAway が true(バニラが一員を 1 体ずつ消してしまう)");
                return;
            }
        }
        int interval = DesignData.swarm().thinkIntervalTicks();
        helper.runAfterDelay(4L * interval + 2L, () -> {
            if (s.done) {
                return;
            }
            for (Mob m : all) {
                if (m.isRemoved() || !m.isAlive()) {
                    s.fail("プレイヤーがいないのに一員が消えた: " + m + "(永続 " + m.isPersistenceRequired() + ")");
                    return;
                }
            }
            Swarm sw = SwarmManager.get(s.level, id);
            if (sw == null || sw.size() != 3) {
                s.fail("群れの一員が減った: " + sw);
                return;
            }
            s.pass();
        });
    }

    // --- 空の群れ ---

    /** 空の群れのテストで、先頭が選ばれてから後続を見始めるまで(tick)。 */
    private static final int AIR_SETTLE_TICKS = 40;
    /** 後続を見続ける長さ(tick)。 */
    private static final int AIR_OBSERVE_TICKS = 60;
    /**
     * 後続と先頭の水平の距離の許容(ブロック)= 横の間隔 × 4。V 字の位置は先頭から 約 8.5(横 6・後ろ 6)。
     * 仮決め 2026-09-25(群れの設計値に空の「遅れ」の距離は無い。先頭が待つのは 48 から)。
     */
    private static final double AIR_TOLERANCE_SPACINGS = 4.0D;
    /** 後続と先頭の高さの差の許容(ブロック)。仮決め 2026-09-25。 */
    private static final double AIR_TOLERANCE_DY = 16.0D;
    /** 空のテストで強制読み込みにする帯の東の端(相対 x)。先頭は 1 tick に 1.1 ブロックほど飛ぶ。 */
    private static final int AIR_STRIP_END_X = 176;

    /**
     * ヴェロフィル 3 体の空の群れ(先頭 + 後続 2)を、テスト場の上空で東(+X)へまっすぐ飛ばす
     * (標的は遠い東のモックのプレイヤー = 距離の上限なしの狩り。受け渡しの 64 には入らない)。
     * 先頭が選ばれて 40 tick 後から 60 tick のあいだ、後続は毎 tick 先頭から水平 {@code 4 × airLateralSpacing} 以内・
     * 高さ 16 以内を飛んでいて、地上に降りていない。先頭はその間に 40 ブロック以上進んでいる(止まった群れでの確かめにしない)。
     * 先頭は番号 0。飛ぶ帯のチャンク(東へ 176 ブロック)は強制読み込みにする。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 420)
    public static void airFollowersStayNearTheLeader(GameTestHelper helper) {
        Stage s = new Stage(helper);
        AirRun run = new AirRun(s);
        run.chunks = s.forceChunks(-16, -16, AIR_STRIP_END_X, 22);
        helper.onEachTick(run::tick);
    }

    /** {@link #airFollowersStayNearTheLeader} の進み具合。 */
    private static final class AirRun {
        private enum Phase { LOADING, WAIT_LEADER, SETTLE, OBSERVE }

        private static final int LOAD_LIMIT = 200;
        /** 飛ぶ高さ(相対 y)。テスト場の天井(相対 8)と上空の通路(相対 12〜14)より上。 */
        private static final double ALTITUDE = 30.0D;

        final Stage s;
        List<ChunkPos> chunks = List.of();
        Phase phase = Phase.LOADING;
        final UUID id = UUID.randomUUID();
        VelophyllEntity leader;
        final List<VelophyllEntity> followers = new ArrayList<>();
        long phaseAt;
        Vec3 observeStart;
        double worst;
        /** 湧かせた game tick。 */
        long spawnedAt;
        /** 見始める前(隊形が整うまで)の後続と先頭の水平の距離の最大。 */
        double settleWorst;
        /** 失敗したときに出す経過(2 tick ごと)。 */
        final List<String> trace = new ArrayList<>();

        AirRun(Stage s) {
            this.s = s;
        }

        /** 経過を残す(失敗したときの手がかり。通ったときは出さない)。 */
        private void sample(Swarm swarm, long now) {
            if ((now - this.spawnedAt) % 2L != 0L || this.trace.size() > 120) {
                return;
            }
            StringBuilder b = new StringBuilder();
            b.append(String.format(Locale.ROOT, "t=%d %s 先導=%s %s", now - this.spawnedAt, this.phase,
                    name(swarm, swarm.vanguard()), swarm.mode()));
            List<VelophyllEntity> all = new ArrayList<>();
            all.add(this.leader);
            all.addAll(this.followers);
            for (VelophyllEntity v : all) {
                if (v != this.leader && this.phase != Phase.OBSERVE) {
                    this.settleWorst = Math.max(this.settleWorst, horizontal(v.position(), this.leader.position()));
                }
                Vec3 rel = this.s.helper.relativeVec(v.position());
                b.append(String.format(Locale.ROOT, " | #%d (%.1f,%.1f,%.1f) yaw=%.0f v=%.2f st=%d 標的=%s 離れ=%.1f",
                        swarm.slot(v), rel.x, rel.y, rel.z, v.getYRot(), v.getDeltaMovement().horizontalDistance(),
                        v.getActionState(), v.hasAttackTarget(), horizontal(v.position(), this.leader.position())));
            }
            this.trace.add(b.toString());
        }

        void tick() {
            if (this.s.done) {
                return;
            }
            long now = this.s.level.getGameTime();
            if (this.phase == Phase.LOADING) {
                if (this.s.chunksTicking(this.chunks, (int) ALTITUDE)) {
                    setUp(now);
                } else if (this.s.helper.getTick() > LOAD_LIMIT) {
                    this.s.fail("空の帯のチャンク(" + this.chunks.size() + " 個)が " + LOAD_LIMIT + " tick で動く状態にならない");
                }
                return;
            }
            Swarm swarm = SwarmManager.get(this.s.level, this.id);
            if (swarm == null) {
                this.s.fail("空の群れが名簿から消えた(段階 " + this.phase + ")");
                return;
            }
            if (!this.leader.isAlive() || this.followers.stream().anyMatch(f -> !f.isAlive())) {
                this.s.fail("ヴェロフィルが死んだ(段階 " + this.phase + ")");
                return;
            }
            if (this.s.helper.relativeVec(this.leader.position()).x > AIR_STRIP_END_X - 8) {
                this.s.fail("先頭が強制読み込みの帯の端まで来た(テストの帯が短い)");
                return;
            }
            sample(swarm, now);
            switch (this.phase) {
                case WAIT_LEADER -> {
                    Mob vanguard = swarm.vanguard();
                    if (vanguard == this.leader && swarm.mode() == Swarm.Mode.HUNT) {
                        this.phase = Phase.SETTLE;
                        this.phaseAt = now;
                    } else if (vanguard != null && vanguard != this.leader) {
                        this.s.fail("空の群れの先頭が番号 0 でない: " + name(swarm, vanguard));
                    } else if (now - this.phaseAt > 2L * DesignData.swarm().thinkIntervalTicks() + 2L) {
                        this.s.fail("空の群れの先頭が決まらない・狩りにならない(先頭 " + name(swarm, vanguard) + "、モード " + swarm.mode() + ")");
                    }
                }
                case SETTLE -> {
                    if (now - this.phaseAt >= AIR_SETTLE_TICKS) {
                        this.phase = Phase.OBSERVE;
                        this.phaseAt = now;
                        this.observeStart = this.leader.position();
                    }
                }
                case OBSERVE -> observe(swarm, now);
                default -> {
                }
            }
        }

        private void setUp(long now) {
            this.spawnedAt = now;
            EntityType<VelophyllEntity> type = ModEntities.VELOPHYLL.get();
            double spacing = DesignData.swarm().airLateralSpacing();
            // V 字の位置に並べておく(東を向いて右 = +Z が番号の偶数、左 = -Z が奇数。後ろへ 1 間隔)。
            this.leader = this.s.member(type, new Vec3(6.5D, ALTITUDE, 3.5D), EAST, this.id, 0, 3, SwarmOrigin.TEST, "", false);
            this.followers.add(this.s.member(type, new Vec3(6.5D - spacing, ALTITUDE, 3.5D + spacing), EAST, this.id, 1, 3,
                    SwarmOrigin.TEST, "", false));
            this.followers.add(this.s.member(type, new Vec3(6.5D - spacing, ALTITUDE, 3.5D - spacing), EAST, this.id, 2, 3,
                    SwarmOrigin.TEST, "", false));
            // 標的を探す箱はテスト場(相対 0〜8)と上空の通路(相対 12〜14)より上だけ。下を含めると、同じ列に並んだほかのテストの
            // ハスクが候補に入る(狩りの相手のほうが優先なので替わりはしないが、確かめる範囲は自分のものだけにする)。
            AABB bounds = this.s.box(-40, 20, -80, 720, 300, 90);
            Swarm swarm = this.s.swarm(this.id, bounds);
            if (swarm == null) {
                return;
            }
            if (swarm.kind() != Swarm.Kind.AIR) {
                this.s.fail("ヴェロフィルの群れが空の群れになっていない: " + swarm);
                return;
            }
            // 狙う相手は東 640 ブロックの宙(モックのプレイヤー。レベルには入れない)。空中の相手なので噛みつきの対象として有効。
            Player quarry = this.s.mockPlayer(new Vec3(640.5D, ALTITUDE, 3.5D));
            swarm.commandTarget(quarry);
            this.phase = Phase.WAIT_LEADER;
            this.phaseAt = now;
        }

        private void observe(Swarm swarm, long now) {
            double tolerance = AIR_TOLERANCE_SPACINGS * DesignData.swarm().airLateralSpacing();
            for (VelophyllEntity f : this.followers) {
                if (f.isGrounded()) {
                    this.s.fail("空の群れの後続が地上に降りた: " + name(swarm, f));
                    return;
                }
                double h = horizontal(f.position(), this.leader.position());
                double dy = Math.abs(f.getY() - this.leader.getY());
                this.worst = Math.max(this.worst, h);
                if (h > tolerance || dy > AIR_TOLERANCE_DY) {
                    OvergrownVegetation.LOGGER.error("空の群れのテストの経過(湧かせてからの tick・段階・先導役・モード・位置・向き・速さ):\n{}",
                            String.join("\n", this.trace));
                    this.s.fail(String.format(Locale.ROOT, "空の群れの後続 %s が先頭から離れた: 水平 %.1f(許容 %.1f)、高さの差 %.1f(許容 %.1f)、"
                                    + "見始めて %d tick", name(swarm, f), h, tolerance, dy, AIR_TOLERANCE_DY, now - this.phaseAt));
                    return;
                }
            }
            if (now - this.phaseAt >= AIR_OBSERVE_TICKS) {
                double flown = horizontal(this.leader.position(), this.observeStart);
                if (flown < 40.0D) {
                    this.s.fail(String.format(Locale.ROOT, "先頭がほとんど飛んでいない(%d tick で %.1f ブロック)。止まった群れでは確かめにならない",
                            AIR_OBSERVE_TICKS, flown));
                    return;
                }
                OvergrownVegetation.LOGGER.info("空の群れ: 後続と先頭の距離は最大 {}(許容 {}。整うまでは最大 {})、先頭は {} tick で {} ブロック",
                        String.format(Locale.ROOT, "%.1f", this.worst), tolerance,
                        String.format(Locale.ROOT, "%.1f", this.settleWorst), AIR_OBSERVE_TICKS,
                        String.format(Locale.ROOT, "%.1f", flown));
                this.s.pass();
            }
        }
    }

    // --- スポロフィル ---

    /**
     * スポロフィル(支援)は標的を持たない(ユーザー指定)。群れの標的(ハスク)がすぐそばにいても、司令塔は渡さない。
     * AI は動かしたまま(個体の索敵が止まっていることも見る)。アルボラには渡る(受け渡しが動いていることの確かめ)。
     * ハスクは NoAI・無敵(アルボラが囲んで殴っても倒れず、標的が消えない)。
     */
    @GameTest(template = TEMPLATE, batch = BATCH, timeoutTicks = 80)
    public static void sporophyllIsNeverHandedATarget(GameTestHelper helper) {
        Stage s = new Stage(helper);
        UUID id = UUID.randomUUID();
        Mob arbora = s.member(ModEntities.ARBORA.get(), new Vec3(1.5D, 2.0D, 3.5D), EAST, id, 0, 2, SwarmOrigin.TEST, "", false);
        Mob sporophyll = s.member(ModEntities.SPOROPHYLL.get(), new Vec3(4.5D, 2.0D, 3.5D), EAST, id, 1, 2, SwarmOrigin.TEST, "", false);
        Husk quarry = s.husk(1.5D, 6.0D);
        quarry.setInvulnerable(true);
        Swarm swarm = s.swarm(id, helper.getBounds());
        if (swarm == null) {
            return;
        }
        swarm.commandTarget(quarry);
        int interval = DesignData.swarm().thinkIntervalTicks();
        boolean[] arboraGotIt = {false};
        helper.onEachTick(() -> {
            if (s.done) {
                return;
            }
            Swarm sw = SwarmManager.get(s.level, id);
            LivingEntity held = sporophyll.getTarget();
            if (held != null) {
                s.fail("スポロフィルが標的を持った: " + held);
                return;
            }
            if (sw != null && (sw.handedTarget(sporophyll) != null || sw.engageTarget(sporophyll) != null)) {
                s.fail("司令塔がスポロフィルへ標的を渡した: " + sw.handedTarget(sporophyll));
                return;
            }
            if (arbora.getTarget() == quarry) {
                arboraGotIt[0] = true;
            }
            if (helper.getTick() >= 3L * interval + 5L) {
                if (!arboraGotIt[0]) {
                    s.fail("アルボラにも標的が渡らない(受け渡しが動いていないので、スポロフィルの確かめにならない)");
                    return;
                }
                s.pass();
            }
        });
    }

    // --- 道具 ---

    /**
     * 1 本のテストが置いたもの(Mob・ブロック)を覚えておき、終わるとき(成功・失敗とも)に片付ける。
     * {@link #pass} / {@link #fail} は 1 回だけ効く(同じ tick に並んだほかの予定が、成功のあとに失敗を書かないように ——
     * GameTest は成功のあとでも同じ tick の残りの予定を回し、そこで失敗すると失敗として数える)。
     */
    private static final class Stage {
        final GameTestHelper helper;
        final ServerLevel level;
        final List<Entity> spawned = new ArrayList<>();
        final List<BlockPos> built = new ArrayList<>();
        /** 片付けで、中の Mob 以外のエンティティ(落ちたアイテム・経験値)も消す箱(絶対座標)。テスト場の外に置いたとき用。 */
        @Nullable
        AABB sweep;
        boolean done;

        Stage(GameTestHelper helper) {
            this.helper = helper;
            this.level = helper.getLevel();
        }

        /** Mob を 1 体置く({@code SwarmSpawner} と同じく、所属は addFreshEntity の前に書く)。 */
        <T extends Mob> T mob(EntityType<T> type, Vec3 rel, float yaw, boolean noAi, @Nullable SwarmMembership membership) {
            T mob = type.create(this.level);
            if (mob == null) {
                fail("作れない: " + EntityType.getKey(type));
                throw new IllegalStateException("unreachable");
            }
            Vec3 abs = this.helper.absoluteVec(rel);
            mob.moveTo(abs.x, abs.y, abs.z, yaw, 0.0F);
            mob.setYHeadRot(yaw);
            mob.setYBodyRot(yaw);
            if (noAi) {
                mob.setNoAi(true);
            }
            if (membership != null) {
                mob.setData(ModAttachments.SWARM, membership);
            }
            this.level.addFreshEntity(mob);
            this.spawned.add(mob);
            return mob;
        }

        <T extends Mob> T member(EntityType<T> type, Vec3 rel, float yaw, UUID id, int slot, int peak, SwarmOrigin origin,
                                 String eventId, boolean noAi) {
            return mob(type, rel, yaw, noAi, new SwarmMembership(id, slot, origin, eventId, peak));
        }

        /** テスト場の床の上(y = 2)に NoAI の一員を置く。 */
        <T extends Mob> T arenaMember(EntityType<T> type, double x, double z, UUID id, int slot, int peak, SwarmOrigin origin,
                                      String eventId) {
            return member(type, new Vec3(x, 2.0D, z), EAST, id, slot, peak, origin, eventId, true);
        }

        /** 7×7 のマス(x, z)の真ん中に NoAI の一員を置き、マスを使用済みにする。 */
        <T extends Mob> T cellMember(boolean[][] used, EntityType<T> type, int x, int z, UUID id, int slot, int peak,
                                     SwarmOrigin origin, String eventId) {
            used[x][z] = true;
            return arenaMember(type, x + 0.5D, z + 0.5D, id, slot, peak, origin, eventId);
        }

        /** 7×7 のマス(x, z)の真ん中に群れに入っていない NoAI の個体を置く。 */
        <T extends Mob> T cellLoner(boolean[][] used, EntityType<T> type, int x, int z) {
            used[x][z] = true;
            return mob(type, new Vec3(x + 0.5D, 2.0D, z + 0.5D), EAST, true, null);
        }

        /** NoAI のハスク(日に焼けない的)。 */
        Husk husk(double x, double z) {
            return mob(EntityType.HUSK, new Vec3(x, 2.0D, z), EAST, true, null);
        }

        /** レベルに入れないモックのプレイヤー(サバイバル)を相対の位置に置く。 */
        Player mockPlayer(Vec3 rel) {
            Player player = this.helper.makeMockPlayer(GameType.SURVIVAL);
            Vec3 abs = this.helper.absoluteVec(rel);
            player.moveTo(abs.x, abs.y, abs.z, 0.0F, 0.0F);
            return player;
        }

        /** 登録された群れを引き、標的を探す範囲を {@code bounds}(絶対座標)に絞る。登録されていなければ失敗。 */
        @Nullable
        Swarm swarm(UUID id, AABB bounds) {
            Swarm swarm = SwarmManager.get(this.level, id);
            if (swarm == null) {
                fail("群れ " + id + " が湧かせた時点で登録されていない(所属を addFreshEntity の前に書いたのに Join で入らない)");
                return null;
            }
            swarm.setTestBounds(bounds);
            return swarm;
        }

        /** 予定の中で群れを引く。無ければ失敗。 */
        @Nullable
        Swarm swarmNow(UUID id) {
            if (this.done) {
                return null;
            }
            Swarm swarm = SwarmManager.get(this.level, id);
            if (swarm == null) {
                fail("群れ " + id + " が名簿から消えた");
            }
            return swarm;
        }

        /** 相対の座標の箱(絶対座標にして返す)。 */
        AABB box(double x0, double y0, double z0, double x1, double y1, double z1) {
            return new AABB(this.helper.absoluteVec(new Vec3(x0, y0, z0)), this.helper.absoluteVec(new Vec3(x1, y1, z1)));
        }

        void block(BlockPos rel, BlockState state) {
            BlockPos abs = this.helper.absolutePos(rel);
            this.level.setBlock(abs, state, Block.UPDATE_ALL);
            this.built.add(abs);
        }

        /**
         * 相対の範囲(x0..x1, z0..z1)にかかるチャンクを強制読み込みにする。外すのはバッチの終わりのバニラ
         * ({@code GameTestRunner} が強制読み込みを全部外す)。ここで外すと、同じチャンクにある隣のテスト場の Mob まで止まる。
         */
        List<ChunkPos> forceChunks(int x0, int z0, int x1, int z1) {
            BlockPos a = this.helper.absolutePos(new BlockPos(x0, 0, z0));
            BlockPos b = this.helper.absolutePos(new BlockPos(x1, 0, z1));
            int cx0 = Math.min(a.getX(), b.getX()) >> 4;
            int cx1 = Math.max(a.getX(), b.getX()) >> 4;
            int cz0 = Math.min(a.getZ(), b.getZ()) >> 4;
            int cz1 = Math.max(a.getZ(), b.getZ()) >> 4;
            List<ChunkPos> out = new ArrayList<>();
            for (int cx = cx0; cx <= cx1; cx++) {
                for (int cz = cz0; cz <= cz1; cz++) {
                    this.level.setChunkForced(cx, cz, true);
                    out.add(new ChunkPos(cx, cz));
                }
            }
            return out;
        }

        /** どのチャンクもエンティティが動く状態か(相対の高さ {@code relY} で見る)。 */
        boolean chunksTicking(List<ChunkPos> chunks, int relY) {
            int y = this.helper.absolutePos(new BlockPos(0, relY, 0)).getY();
            for (ChunkPos c : chunks) {
                if (!this.level.isPositionEntityTicking(new BlockPos(c.getMiddleBlockX(), y, c.getMiddleBlockZ()))) {
                    return false;
                }
            }
            return true;
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
            if (this.sweep != null) {
                for (Entity e : this.level.getEntitiesOfClass(Entity.class, this.sweep, e -> !(e instanceof Player))) {
                    e.discard();
                }
            }
            BlockState air = Blocks.AIR.defaultBlockState();
            for (BlockPos p : this.built) {
                this.level.setBlock(p, air, Block.UPDATE_CLIENTS);
            }
        }
    }

    /** 7×7 の空いているマス(z = 4..6, 1..3, 0 の順に x = 0..6)。無ければ例外(呼ぶ前に数を確かめてある)。 */
    private static int[] freeCell(boolean[][] used) {
        int[] rows = {4, 5, 6, 1, 2, 3, 0};
        for (int z : rows) {
            for (int x = 0; x < 7; x++) {
                if (!used[x][z]) {
                    return new int[]{x, z};
                }
            }
        }
        throw new IllegalStateException("テスト場のマスが足りない");
    }

    private static boolean moved(Entity e) {
        return e.getDeltaMovement().horizontalDistanceSqr() > 1.0E-9D;
    }

    private static double horizontal(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /** 失敗の手がかり用の名前(種類 + 番号)。 */
    private static String name(Swarm swarm, @Nullable Mob mob) {
        if (mob == null) {
            return "なし";
        }
        return EntityType.getKey(mob.getType()).getPath() + "#" + swarm.slot(mob) + (mob.isAlive() ? "" : "(死亡)");
    }

    private SwarmGameTests() {
    }
}

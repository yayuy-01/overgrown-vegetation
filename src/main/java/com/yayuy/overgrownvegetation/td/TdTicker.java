package com.yayuy.overgrownvegetation.td;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.base.build.BaseWatch;
import com.yayuy.overgrownvegetation.corruption.NoisyCorruptionBurst;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.defense.SporeAlarms;
import com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData;
import com.yayuy.overgrownvegetation.ecosystem.EcosystemData;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import com.yayuy.overgrownvegetation.phase.OvergrownSpawnProfile;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.raid.EventMobs;
import com.yayuy.overgrownvegetation.registry.ModAttachments;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import com.yayuy.overgrownvegetation.swarm.Swarm;
import com.yayuy.overgrownvegetation.swarm.SwarmListener;
import com.yayuy.overgrownvegetation.swarm.SwarmManager;
import com.yayuy.overgrownvegetation.swarm.SwarmOrigin;
import com.yayuy.overgrownvegetation.swarm.SwarmRole;
import com.yayuy.overgrownvegetation.swarm.SwarmSpawner;
import com.yayuy.overgrownvegetation.swarm.SwarmTable;
import com.yayuy.overgrownvegetation.util.TickPhases;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * TD イベント(森の侵攻)の<b>進行</b>(2026-09-25、TD 設計 v2)。台帳は {@link TdData}、外から呼ぶ口は {@link TdEvents}、
 * 設計値は {@link TdTable}({@code td.json})。
 *
 * <h2>流れ</h2>
 * <ol>
 *     <li><b>始まり</b>(ユーザー指定 2026-09-24「森は自動で攻めてくる」): 進行中のイベントが無く、前のイベントから
 *     {@code cooldownTicks} 経ち、フェーズ ≥ {@code minPhase}、世界のバイオマス ≥ {@code startBiomass}、読み込まれている
 *     ターゲットブロックのどれかから {@code overgrownSearchRadius} 以内に過繁茂チャンクがある → <b>予告</b>(WARNING: 全体チャット・
 *     胞子警報器・レイドの角笛)。近くにプレイヤーがいるターゲットを優先する。攻めてくる向きはこのときに振る(下の仮想の群れ)。
 *     ターゲットの近くに同盟軍の基地があれば、その見張り台が向きと規模を知らせる({@link BaseWatch}。2026-10-08 順 2 の着手順 8)。</li>
 *     <li>{@code warningTicks} 経ち、ターゲットから {@code pauseWithoutPlayersRadius} に観戦者でないプレイヤーがいれば<b>侵攻</b>(ACTIVE)。
 *     いなければ予告のまま待つ(誰もいない拠点のためにバイオマスを切り出さない)。侵攻に入った瞬間、世界のバイオマスを
 *     <b>全部</b>予算へ移し(ユーザーが受け入れた仮の数値)、ターゲットの耐久を満タンに戻す(仮決め 2026-09-25)。</li>
 *     <li><b>仮想の群れ</b>(ユーザー指定「遠くの群れはデータだけで行進する」): {@code swarmSpawnIntervalTicks} ごとに、
 *     <b>読み込まれている攻め手 + 行進中の仮想の群れの頭数が同時の上限({@code concurrentCap})に届くまで</b>何群れでも
 *     ({@code maxVirtualSwarms} まで)、{@code swarm.json} の編成(G4 だけ。G5・G6 は v1 では出さない)を振って予算から頭数ぶん引き、
 *     ターゲットから {@code virtualSpawnMin}〜{@code virtualSpawnMax} の輪の、行進中の群れの向きのいちばん大きな隙間に置く
 *     (全方位。{@link #ringPoint})。その点が近くのプレイヤーの見える範囲(霧の終わり・描画距離。{@link TdSight})に入るなら、
 *     霧の外まで押し出す(絵が霧の中から出てくる。{@link #spawnDistance})。<b>攻めてくる向き</b>は予告のときに振った向き
 *     (全方位 4 割・一方向 6 割、一方向は過繁茂チャンクの多い 8 方位に寄る。{@link #rollDirection}。予告を飛ばすコマンドは
 *     侵攻に入るときに振るか指定の向き)、
 *     一方向の侵攻はその向きの扇の中だけに群れを作る({@link #pickAngleInFan})。{@code airSwarmShare} の割合で空の群れ(同時に出ているヴェロフィルが
 *     {@code maxAirAttackers} に届いていれば地上だけ)。5 tick ごとにターゲットへ一直線に進む(高さは読み込み済みのチャンクで木を
 *     掘り抜いた地面。チャンクを読み込まない)。流れの速さを決めるのはユーザー指定の上限(倒した分だけ次が来る。{@link #spawnVirtual})。
 *     侵攻の始めだけは、上限いっぱいの群れを実体化の輪の上に一度に作り、毎 tick 実体にする({@link #opening})。</li>
 *     <li><b>実体化</b>: ターゲットから {@code materializeRadius} 以内に来た(か {@code playerMaterializeRadius} にプレイヤーがいる)、
 *     そのチャンクが読み込まれていてエンティティが動く、<b>読み込まれている攻め手</b> + その頭数 ≤ {@code concurrentCap}
 *     (ユーザー指定の上限)のとき、{@link SwarmSpawner} で出どころ TD・eventId = イベントの id で湧かせる({@code EventMobs} の TD の印は
 *     addFreshEntity の前に付く)。帳簿 1 回に {@value #MAX_MATERIALIZE_PER_BOOKKEEPING} 群れまで。生きている数に足し、群れを攻城(SIEGE)に
 *     する。空の群れは遠景の身代わりの絵と同じ位置・高さ(空中)に湧かせる({@link #airPlacements})。上限に当たっていれば輪の上で待つ。
 *     置けなかったら輪に沿って横へ歩いて場所を変える(ターゲットへは寄せない。{@link #startSidestep})。</li>
 *     <li><b>生きている数</b>: 増えるのは実体化したときだけ。減るのは、侵攻中のイベントの印を持つ個体が<b>倒された・消された</b>
 *     ({@code getRemovalReason().shouldDestroy()})ときの {@code EntityLeaveLevelEvent} だけ(どのディメンションでも)。チャンクごと
 *     読み込み外れた・ディメンションを移ったときは減らさない(読み込まれていない攻め手もまだ生きている)。勝ちの判定とボスバーに使い、
 *     同時の上限には使わない(読み込まれていない攻め手は重さにならない。上限は「同時に出ている実体」のため)。</li>
 *     <li><b>勝ち</b>(ユーザー指定「予算が 0 で攻め手が全滅したら勝ち」): 予算がもう 1 群れも買えない({@link #cheapest} 未満)、
 *     仮想の群れなし、生きている数 0。予算の端数は世界へ返す。</li>
 *     <li><b>負け</b>(ユーザー指定「ターゲットが壊れたら負け、拠点の周りに汚染」): {@link TdEvents#onTargetDestroyed}。侵攻中の
 *     ターゲットが耐久 0 以外で無くなったとき({@link TdEvents#onTargetRemoved}・チャンクが読み込まれているのにブロックが無い)も負け。</li>
 *     <li><b>中止</b>: コマンド・ディメンションが無い・予告中にターゲットが無くなった。切り出したバイオマス(予算 + 仮想の群れ)を世界へ返す。</li>
 * </ol>
 *
 * <h2>一時停止と取りやめ</h2>
 * ターゲットから {@code pauseWithoutPlayersRadius} に観戦者でないプレイヤーが 1 人もいない間は、<b>増援だけ</b>止める:
 * 新しい仮想の群れを作らず、進めず、実体化もせず、残党を片付けず、勝ちも判定しない({@link TdEvents#isPaused})。
 * 拠点にいる攻め手の攻城(塊・壁の削り・負け)は止めない({@link TdEvents#isSiegeLive}。仮決め 2026-09-25、レビュー指摘:
 * 削りまで止めていた頃は、耐久が減ったら少し離れて自動の砲台に片付けさせるだけで負けなかった)。
 * 止まったまま {@code abandonTicks} 経ったら取りやめ(払い戻し)。
 *
 * <h2>残党</h2>
 * 予算も仮想の群れも尽きたあと、ターゲットから {@code materializeRadius} より遠くに {@code strayDiscardTicks} いた攻め手は消す
 * (消えれば Leave で数が減る)。生きている数が読み込まれている攻め手より多いまま同じ時間が経ったら、差は読み込まれていない
 * どこかにいるだけなので数から外す(その個体は読み込まれた瞬間に {@code EventMobs} が印を外し、普段の規則で消える。
 * 外したあとに読み込まれて戻ってきたら、読み込まれている数まで数え直す。仮決め 2026-09-25)。
 *
 * <h2>見せ方</h2>
 * 20 tick ごとに、プレイヤーから {@code presentationRadius} の仮想の群れに胞子の雲(遠くても見える粒子)を出し、ときどき
 * 葉擦れの低い音を群れの方向から鳴らす。ボスバー「森の侵攻 — 残り N バイオマス」(N = まだ買える予算 + 行進中の群れの値段 +
 * 生きている攻め手の値段。{@link #remainingBiomass})をターゲットから {@code bossBarRadius} のプレイヤーに出す
 * ({@code SiegeData} と同じく SavedData が持つ)。
 *
 * <p>静的に持つのは「読み込まれている攻め手」の表だけ(ワールドごと。ディメンションを閉じたとき・サーバーが止まったとき・
 * イベントが終わったときに空にする)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class TdTicker {

    /** 帳簿の間隔(tick)。td-design「bookkeeping every 20 ticks」。 */
    static final int BOOKKEEPING_INTERVAL = 20;
    /** 行進の間隔(tick)。td-design「virtual movement every 5」。 */
    static final int MOVE_INTERVAL = 5;
    /** 始まりの条件を見る間隔(tick)。ターゲットごとに周りのチャンクを数百引くので、帳簿より粗くする。仮決め 2026-09-25。 */
    static final int TRIGGER_INTERVAL = 100;

    /** 空の仮想の群れの高さ(地表から、ブロック。粒子を出す高さ)。仮決め 2026-09-25。 */
    private static final double AIR_ALTITUDE = 16.0D;
    /** 実体化の条件を満たしたのに 1 体も置けなかった回数がこれに達したら、その群れの頭数を予算へ戻して作り直させる。仮決め 2026-09-25。 */
    private static final int MAX_MATERIALIZE_FAILURES = 8;
    /** 置けなかったとき、輪に沿って横へ歩く角度の幅(ラジアン。半分〜全部)。仮決め 2026-09-25。 */
    private static final double NUDGE_ANGLE = Math.PI / 9.0D;
    /** 横へ歩く速さの下限(ブロック / tick。止まっている群れ・とても遅い群れでも場所を変えられるように)。仮決め 2026-09-25。 */
    private static final double SIDESTEP_MIN_SPEED = 0.2D;
    /** 始めの一斉投入のあと、帳簿を待たずに毎 tick 実体にする長さ(tick。群れが霧の外で生まれたときは、着くまでの時間に足す)。仮決め 2026-09-25。 */
    public static final int OPENING_BURST_TICKS = 100;
    /** 霧の外で生まれた一斉投入の群れが実体化の輪に着くまで待つ上限(tick)。とても遅い群れで毎 tick の実体化を延々続けない。仮決め 2026-09-26。 */
    static final int OPENING_MAX_ARRIVAL_TICKS = 2400;
    /** 始めの一斉投入の間、1 tick に実体にする群れの数(1 群れ約 6 体 → 1 tick に約 50 体、500 体で 1 秒ほど)。仮決め 2026-09-25。 */
    public static final int OPENING_PER_TICK = 8;
    /**
     * 帳簿 1 回(20 tick)に実体にする群れの数の上限。仮想の群れを上限いっぱいまで作るようになったので、何十群れが同じ帳簿で
     * 輪に着くと 1 tick に 200 体近くを湧かせてしまう。仮決め 2026-09-25: 4 群れ(1 秒に 20〜30 体)→ 同日のユーザー報告
     * 「補充をもっと早く」で上限 500 に合わせて 8 群れ(1 秒に 40〜60 体、500 体まで 10 秒ほど)。
     */
    static final int MAX_MATERIALIZE_PER_BOOKKEEPING = 8;
    /** 負けの汚染を小さな球に分けるときの 1 個の半径(ブロック)。1 tick に作る数は {@code NoisyCorruptionBurst} の上限に従う。仮決め 2026-09-25。 */
    private static final int DEFEAT_SUB_RADIUS = 8;
    /** 行進の音: 1 回の帳簿でプレイヤーごとに鳴らす確率。仮決め 2026-09-25。 */
    private static final float MARCH_SOUND_CHANCE = 0.3F;

    /** 読み込まれている攻め手(侵攻中のイベントの印を持つ個体)。ワールドごと。サーバースレッドだけが触る。 */
    private static final Map<ResourceKey<Level>, LevelMobs> LOADED = new HashMap<>();

    /** テスト用: プレイヤーがいなくても一時停止しない(GameTest のサーバーにはプレイヤーがいない)。サーバー停止で戻す。 */
    private static boolean ignorePlayersForTests;

    private static final class LevelMobs {
        final ServerLevel level;
        final Map<UUID, Mob> mobs = new HashMap<>();
        /** 残党の判定: ターゲットから遠くにいるのに気づいた時刻。 */
        final Map<UUID, Long> farSince = new HashMap<>();

        LevelMobs(ServerLevel level) {
            this.level = level;
        }
    }

    /** 組み直した群れを攻城に戻す口(群れは実行時だけのデータで、モードもターゲットも保存しない)。 */
    private static final SwarmListener LISTENER = new SwarmListener() {
        @Override
        public void onSwarmRebuilt(Swarm swarm) {
            applySiege(swarm, null);
        }

        @Override
        public void onMemberRegistered(Swarm swarm, Mob member) {
            applySiege(swarm, member);
        }
    };

    // ------------------------------------------------------------------ 拍

    /**
     * 群れの組み直しの口を登録する。ワールドの読み込み(出現地点のチャンクの攻め手の登録)より前に済ませたいので、
     * 起動の直前({@code ServerAboutToStartEvent})で。同じものを 2 度登録しても 1 回と数える。
     */
    @SubscribeEvent
    public static void onServerAboutToStart(ServerAboutToStartEvent event) {
        SwarmManager.addListener(LISTENER);
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel overworld) || overworld != overworld.getServer().overworld()) {
            return;
        }
        // 時間が止まっている(/tick freeze)間は進めない(群れの拍と同じ)。
        if (!overworld.tickRateManager().runsNormally()) {
            return;
        }
        long now = overworld.getGameTime();
        boolean bookkeeping = TickPhases.isDue(now, TickPhases.TD_EVENT, BOOKKEEPING_INTERVAL);
        boolean move = TickPhases.isDue(now, TickPhases.TD_EVENT, MOVE_INTERVAL);
        MinecraftServer server = overworld.getServer();
        TdData data = TdData.get(server);
        TdData.Event live = data.live();
        // ピースフル(difficulty/Peaceful、2026-09-26): 予告中・侵攻中のイベントは中止する(バニラのレイドと同じ。攻め手は敵なので
        // Peaceful が消す)。帳簿より先に見る —— 攻め手が消えたあとに帳簿が回ると、仮想の群れを実体にしては消されるのを繰り返し、
        // 予算の尽きた終盤なら「全滅 = 勝ち」と数えてしまう。
        // 次の侵攻までの待ちも止め(進めたままだと、ピースフルを抜けた途端に始まる)、新しい侵攻は始めない(下の tryTrigger)。
        boolean peaceful = com.yayuy.overgrownvegetation.difficulty.Peaceful.is(overworld);
        if (peaceful) {
            if (live != null) {
                cancel(server, data, live, TdData.Outcome.CANCELLED, now);
                live = null;
            }
            data.holdCooldown(now, DesignData.td().cooldownTicks());
        }
        // 始めの一斉投入の直後は、帳簿を待たずに毎 tick 実体にする(opening)。
        boolean burst = live != null && now < live.burstUntil && live.state() == TdData.State.ACTIVE && !live.paused();
        if (!bookkeeping && !move && !burst) {
            return;
        }
        if (live == null) {
            if (bookkeeping) {
                TdHordeSync.clearAll(server); // 終わった侵攻の群れの身代わりを消す
            }
            if (!peaceful && TickPhases.isDue(now, TickPhases.TD_EVENT, TRIGGER_INTERVAL)) {
                tryTrigger(overworld, data, now);
            }
            return;
        }
        ServerLevel level = server.getLevel(live.dimension());
        if (level == null) {
            OvergrownVegetation.LOGGER.warn("TD: イベントのディメンション {} が無いので中止する", live.dimension().location());
            cancel(server, data, live, TdData.Outcome.CANCELLED, now);
            return;
        }
        TdTable table = DesignData.td();
        if (move && live.state() == TdData.State.ACTIVE && !live.paused()) {
            moveVirtual(level, data, live, table, MOVE_INTERVAL);
        }
        if (bookkeeping) {
            bookkeeping(level, data, live, table, now);
        } else if (burst && materialize(level, data, live, table, OPENING_PER_TICK) > 0) {
            // 実体になった群れの身代わりをすぐ消す(次の帳簿まで待つと、本物と絵が 1 秒ほど重なる)。
            TdHordeSync.send(level, live, table);
        }
    }

    // ------------------------------------------------------------------ 始まり

    private static void tryTrigger(ServerLevel overworld, TdData data, long now) {
        TdTable table = DesignData.td();
        if (!data.cooldownOver(now, table.cooldownTicks())) {
            return;
        }
        PhaseData phase = PhaseData.get(overworld);
        // どのフェーズで侵攻が起きるかは phases.json の tdEnabled(2026-09-29 に td.json の minPhase から移した。
        // レイド・ティタニカ戦と同じく、フェーズタブで 1 つずつ入り切りできる)。
        if (!DesignData.phases().tdEnabled(phase.getPhase())) {
            return;
        }
        if (EcosystemData.get(overworld.getServer()).biomass() < table.startBiomass()) {
            return;
        }
        BlockPos target = pickTarget(overworld, phase, table);
        if (target != null) {
            startWarning(overworld, data, target, now);
        }
    }

    /**
     * 狙うターゲット: 読み込まれていて、{@code overgrownSearchRadius} 以内に過繁茂チャンクがあるもの。
     * 近くにプレイヤーがいるものを優先する(いない拠点を選ぶと予告のまま止まり、ほかの拠点が狙われなくなる)。
     * 過繁茂の台帳はオーバーワールドのものなので、オーバーワールドのターゲットだけを見る(仮決め 2026-09-25)。
     */
    @Nullable
    static BlockPos pickTarget(ServerLevel overworld, PhaseData phase, TdTable table) {
        BlockPos best = null;
        boolean bestHasPlayer = false;
        for (BlockPos pos : TdTargets.loaded(overworld)) {
            if (!hasOvergrownNear(phase, pos, table.overgrownSearchRadius())) {
                continue;
            }
            boolean hasPlayer = hasPlayerWithin(overworld, Vec3.atCenterOf(pos), table.pauseWithoutPlayersRadius());
            if (best == null || (hasPlayer && !bestHasPlayer)) {
                best = pos;
                bestHasPlayer = hasPlayer;
            }
            if (bestHasPlayer) {
                break;
            }
        }
        return best;
    }

    /** {@code pos} から {@code radius} 以内(チャンクの中心で見る)に過繁茂チャンクがあるか。台帳を引くだけでチャンクは読み込まない。 */
    static boolean hasOvergrownNear(PhaseData phase, BlockPos pos, double radius) {
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        int r = Mth.ceil(radius / 16.0D);
        double reach = radius + 8.0D;
        double reachSq = reach * reach;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double ox = ((cx + dx) << 4) + 8.0D - (pos.getX() + 0.5D);
                double oz = ((cz + dz) << 4) + 8.0D - (pos.getZ() + 0.5D);
                if (ox * ox + oz * oz > reachSq) {
                    continue;
                }
                if (phase.isOvergrownChunk(new ChunkPos(cx + dx, cz + dz))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void startWarning(ServerLevel level, TdData data, BlockPos target, long now) {
        TdData.Event event = data.begin(UUID.randomUUID(), level.dimension(), target, TdData.State.WARNING, now);
        event.phase = PhaseData.get(level).getPhase(); // 攻め手の顔ぶれはこのフェーズで湧く種だけ(ユーザー指定 2026-09-25)
        // 攻めてくる向きは予告のときに振り、侵攻もこの向きで来る(見張り台が予告で知らせるため。2026-10-08 順 2 の着手順 8、仮決め)。
        event.direction = rollDirection(level, target, DesignData.td(), level.random);
        event.directionChosen = true;
        data.setDirty();
        TdHostiles.clear(level, event);
        TargetBlockEntity.syncLock(level, target); // 予告の間から掘れない(クライアントにも知らせる)
        int seconds = Math.max(1, DesignData.td().warningTicks() / 20);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.overgrown_vegetation.td.warning", target.getX(), target.getY(), target.getZ(), seconds), false);
        // 近くに同盟軍の基地があれば、その見張り台が群れの向きと規模(侵攻に入る瞬間に予算になる世界のバイオマスの見込み)を知らせる。
        BaseWatch.report(level, target, event.direction, EcosystemData.get(level.getServer()).biomass());
        SporeAlarms.warn(level, Component.translatable("message.overgrown_vegetation.alarm.td"), Vec3.atCenterOf(target));
        playHorn(level, target);
        OvergrownVegetation.LOGGER.info("TD: 予告 — ターゲット {}、向き {}(イベント {})", target, event.direction.japanese(),
                event.eventId());
    }

    /**
     * コマンドから: {@code target} で<b>予告</b>から始める(過繁茂の近さ・フェーズ・バイオマス・次までの待ちの条件は見ない)。
     * あとは自動の侵攻と同じ(予告の知らせ・見張り台・{@code warningTicks} 後に侵攻、近くに誰もいなければ待つ)。
     *
     * @return 始めたら true(進行中のイベントがある・難易度がピースフルなら false)
     */
    public static boolean forceWarning(ServerLevel level, BlockPos target) {
        TdData data = TdData.get(level.getServer());
        if (data.live() != null || com.yayuy.overgrownvegetation.difficulty.Peaceful.is(level)) {
            return false;
        }
        startWarning(level, data, target, level.getGameTime());
        return true;
    }

    /**
     * 侵攻へ進める。{@code forcedBudget} が null なら世界のバイオマスを全部切り出す(払い戻せる)。
     * 数を決めて始めた({@code /ov td start <予算>})ときは世界のバイオマスに触らない(払い戻さない)。
     * 攻めてくる向きは {@code forcedDirection}(コマンドの指定)、null なら予告のときに振った向き、それも無ければ振る({@link #rollDirection})。
     */
    private static void activate(ServerLevel level, TdData data, TdData.Event event, long now, @Nullable Double forcedBudget,
                                 @Nullable TdDirection forcedDirection) {
        event.direction = forcedDirection != null ? forcedDirection
                : event.directionChosen ? event.direction
                : rollDirection(level, event.target(), DesignData.td(), level.random);
        event.directionChosen = true;
        EcosystemData eco = EcosystemData.get(level.getServer());
        if (forcedBudget == null) {
            event.budget = Math.max(0.0D, eco.biomass());
            eco.setBiomass(0.0D);
            event.refundable = true;
        } else {
            event.budget = Math.max(0.0D, forcedBudget);
            event.refundable = false;
        }
        event.initialTotal = Math.max(1.0D, Math.floor(event.budget));
        event.state = TdData.State.ACTIVE;
        event.stateSince = now;
        event.nextSwarmTick = now;
        event.opened = false; // 最初の仮想の群れの番で、上限いっぱいを一度に出す(opening)
        event.burstUntil = Long.MIN_VALUE;
        event.alive = 0;
        event.aliveCost = 0.0D;
        event.killed = 0;
        event.virtual.clear();
        data.setShownRemaining(-1);
        data.setDirty();
        TargetBlockEntity target = TdTargets.get(level, event.target());
        if (target != null) {
            // 侵攻のたびに満タンから(前の侵攻で削られたまま始めない)。仮決め 2026-09-25。
            target.resetHp();
        }
        // 向きも告げる(全方位 /「北から」)。地平線の嵐もその向きにだけ立つ。
        level.getServer().getPlayerList().broadcastSystemMessage(event.direction.around()
                ? Component.translatable("message.overgrown_vegetation.td.started", biomassText(event.budget))
                : Component.translatable("message.overgrown_vegetation.td.started_from", event.direction.label(),
                biomassText(event.budget)), false);
        playHorn(level, event.target());
        OvergrownVegetation.LOGGER.info("TD: 侵攻開始 — ターゲット {}、予算 {}(払い戻し {})、向き {}", event.target(),
                String.format("%.1f", event.budget), event.refundable, event.direction.japanese());
    }

    /**
     * コマンドから: {@code target} で今すぐ侵攻を始める(予告を飛ばす。過繁茂の近さ・フェーズ・バイオマスの条件も見ない)。
     *
     * @param budget    null なら世界のバイオマスを全部切り出す
     * @param direction 攻めてくる向き(全方位も選べる)。null なら自動の侵攻と同じく振る
     * @return 始めたら true(進行中のイベントがある・難易度がピースフルなら false)
     */
    public static boolean forceStart(ServerLevel level, BlockPos target, @Nullable Double budget,
                                     @Nullable TdDirection direction) {
        TdData data = TdData.get(level.getServer());
        if (data.live() != null || com.yayuy.overgrownvegetation.difficulty.Peaceful.is(level)) {
            return false;
        }
        long now = level.getGameTime();
        TdData.Event event = data.begin(UUID.randomUUID(), level.dimension(), target, TdData.State.WARNING, now);
        event.phase = PhaseData.get(level).getPhase();
        SporeAlarms.warn(level, Component.translatable("message.overgrown_vegetation.alarm.td"), Vec3.atCenterOf(target));
        activate(level, data, event, now, budget, direction);
        TargetBlockEntity.syncLock(level, target);
        TdHostiles.clear(level, event);
        return true;
    }

    /** コマンドから: 進行中のイベントを中止する(払い戻す)。止めたら true。 */
    public static boolean forceStop(MinecraftServer server) {
        TdData data = TdData.get(server);
        TdData.Event event = data.live();
        if (event == null) {
            return false;
        }
        cancel(server, data, event, TdData.Outcome.CANCELLED, server.overworld().getGameTime());
        return true;
    }

    /** コマンドから: 侵攻中のイベントの予算を決める。決めたら true。 */
    public static boolean setBudget(MinecraftServer server, double budget) {
        TdData data = TdData.get(server);
        TdData.Event event = data.active();
        if (event == null) {
            return false;
        }
        event.budget = Math.max(0.0D, budget);
        ServerLevel level = server.getLevel(event.dimension());
        event.initialTotal = Math.max(event.initialTotal,
                Math.floor(remainingBiomass(level != null ? level : server.overworld(), event)));
        data.setShownRemaining(-1);
        data.setDirty();
        return true;
    }

    // ------------------------------------------------------------------ 帳簿

    private static void bookkeeping(ServerLevel level, TdData data, TdData.Event event, TdTable table, long now) {
        if (!targetPresent(level, event.target())) {
            // onRemove を通らずに消えた(外部の編集など)。onRemove から来たときと同じ扱い(侵攻中なら負け、予告中なら取りやめ)。
            OvergrownVegetation.LOGGER.info("TD: ターゲット {} が無くなっていた", event.target());
            if (event.state() == TdData.State.ACTIVE) {
                defeat(level, data, event);
            } else {
                cancel(level.getServer(), data, event, TdData.Outcome.CANCELLED, now);
            }
            return;
        }

        boolean anyone = ignorePlayersForTests
                || hasPlayerWithin(level, Vec3.atCenterOf(event.target()), table.pauseWithoutPlayersRadius());
        if (!anyone && event.pausedSince < 0L) {
            event.pausedSince = now;
            data.setDirty();
        } else if (anyone && event.pausedSince >= 0L) {
            event.pausedSince = -1L;
            data.setDirty();
        }
        if (event.paused() && now - event.pausedSince > table.abandonTicks()) {
            cancel(level.getServer(), data, event, TdData.Outcome.ABANDONED, now);
            return;
        }

        if (event.state() == TdData.State.WARNING) {
            if (!event.paused() && now - event.stateSince() >= table.warningTicks()) {
                activate(level, data, event, now, null, null);
            }
            return;
        }

        // 増援(新しい群れ・実体化・残党・勝ち)だけ止める。拠点の攻城は止めない(クラスの説明「一時停止と取りやめ」)。
        if (!event.paused()) {
            spawnVirtual(level, data, event, table, now);
            materialize(level, data, event, table, now < event.burstUntil ? OPENING_PER_TICK : MAX_MATERIALIZE_PER_BOOKKEEPING);
            stragglers(level, data, event, table, now);
            if (event.budget < cheapest(level, event) && event.virtual.isEmpty() && event.alive <= 0
                    && loadedAttackers(level) == 0) {
                victory(level, data, event, now);
                return;
            }
        }
        present(level, event, table);
        updateBossBar(level, data, event, table);
        // 行進中の群れを近くのプレイヤーへ(遠景の身代わり)。実体化のあとなので、実体になった群れはもう一覧に無い。
        TdHordeSync.send(level, event, table);
    }

    /** ターゲットのチャンクが読み込まれていればブロックがあるか。読み込まれていなければ分からないので「ある」。 */
    private static boolean targetPresent(ServerLevel level, BlockPos pos) {
        if (level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4) == null) {
            return true;
        }
        return level.getBlockState(pos).getBlock() instanceof TargetBlock;
    }

    // ------------------------------------------------------------------ 仮想の群れ

    /**
     * 仮想の群れを作る。{@code swarmSpawnIntervalTicks} ごとに、<b>行進中の仮想の群れの頭数</b>が「同時の上限の空き
     * (上限 − 読み込まれている攻め手)」と「控え({@code virtualReserve})」の大きい方に届くまで、{@code maxVirtualSwarms} まで
     * 何群れでも作る。1 群れの頭数が残りより多ければ作らずに次を待つ(小さな群れをばらばらに出さない)。ただし何も出ていない
     * (読み込まれている攻め手も仮想の群れも 0)ときは、残りが 1 群れより小さくても作る(詰まらないように)。
     *
     * <p>仮決め 2026-09-25(レビュー指摘): はじめは 1 回に 1 群れだったので、上限に関係なく 1 分に約 72 体(1 群れ約 6 体)しか出ず、
     * 最小の侵攻(バイオマス 2000)でも作り終えるだけで 28 分、1 万を超えると何時間もかかった(その間ずっと拠点のまわりの生態系防衛も
     * 止まる)。いまは上限が流れを決める —— 最初に上限ぶんが押し寄せ、倒した分だけ次が来る。侵攻の長さ ≒ 予算 ÷ 倒す速さ。
     *
     * <p>控え(2026-09-25 のユーザー報告「補充をもっと早く。1 体死ねば 1 体補充でもよい」): それまでは「読み込まれている攻め手 +
     * 行進中」が上限に届くと作らなかったので、上限が埋まっている間は待つ輪に誰もおらず、倒された分は遠い輪から 5〜28 秒歩いて来た。
     * いまは上限が埋まっていても控えの頭数だけ待つ輪で待たせ、空いた次の帳簿で実体にする({@link #materialize})。
     */
    private static void spawnVirtual(ServerLevel level, TdData data, TdData.Event event, TdTable table, long now) {
        if (now < event.nextSwarmTick) {
            return;
        }
        event.nextSwarmTick = now + table.swarmSpawnIntervalTicks();
        data.setDirty();
        if (!event.opened) {
            opening(level, data, event, table, now);
        }
        int loaded = loadedAttackers(level);
        int want = Math.max(table.virtualReserve(), concurrentCap(level, table) - loaded);
        double cheapest = cheapest(level, event);
        while (event.budget >= cheapest && event.virtual.size() < table.maxVirtualSwarms()) {
            int marching = event.virtualSize();
            if (!spawnOneVirtual(level, data, event, table, want - marching, loaded + marching == 0,
                    table.virtualSpawnMin(), table.virtualSpawnMax())) {
                return;
            }
        }
    }

    /**
     * 同時に出ている攻め手の上限。ワールドの難易度「TD イベントの最大 mob 数」(2026-09-25)があればそれ、無ければ td.json の
     * {@code concurrentCap}(ユーザー指定 500)。
     */
    private static int concurrentCap(ServerLevel level, TdTable table) {
        return WorldDifficultyData.tdConcurrentCap(level.getServer(), table);
    }

    /**
     * 侵攻の始めの<b>一斉投入</b>(2026-09-25 の runClient 4 回目のユーザー指定「TD が始まってすぐは一気に上限までの mob を湧かせて
     * 攻めてくるように」): 同時の上限の空き(上限 − 読み込まれている攻め手 − 行進中)が埋まるまで、群れを<b>実体化の輪の上</b>
     * ({@link #holdRing}〜{@code materializeRadius})に全方位から作り({@code maxVirtualSwarms} は見ない)、そこから
     * {@value #OPENING_BURST_TICKS} tick の間は帳簿を待たずに毎 tick {@value #OPENING_PER_TICK} 群れまで実体にする
     * (上限 500 でも 1 秒ほどで揃う。1 tick に全部湧かせると重い)。
     *
     * <p>それまでは最初から遠い輪(128〜192)で生まれて歩いて来たうえ、同時に行進する群れの数({@code maxVirtualSwarms} 40)で頭打ちに
     * なっていたので、上限いっぱいになるまで数分かかっていた。侵攻に入った最初の仮想の群れの番({@link #spawnVirtual})で 1 回だけ
     * (一時停止中は来ない。{@code /ov td start} で予告を飛ばしても同じ)。
     *
     * <p>実体化の輪が近くのプレイヤーの見える範囲に入っていれば、ほかの群れと同じく霧の外へ押し出して生ませる({@link #spawnDistance}。
     * 2026-09-26 のユーザー指定「外周の霧の中から出てくる感じに」: 見えている 92〜95 ブロックに 500 体が一度に現れていた)。
     * 押し出された群れは歩いて輪に着くので、毎 tick 実体にする期限は<b>いちばん遅く着く群れが着くまで</b>
     * + {@value #OPENING_BURST_TICKS} tick に延ばす({@value #OPENING_MAX_ARRIVAL_TICKS} tick まで)。
     */
    private static void opening(ServerLevel level, TdData data, TdData.Event event, TdTable table, long now) {
        event.opened = true;
        data.setDirty();
        int loaded = loadedAttackers(level);
        double cheapest = cheapest(level, event);
        double near = holdRing(table);
        double far = Math.max(near, table.materializeRadius() - 1.0D);
        int swarms = 0;
        int heads = 0;
        long arrival = 0L;
        while (event.budget >= cheapest) {
            int before = event.virtualSize();
            int room = concurrentCap(level, table) - loaded - before;
            if (!spawnOneVirtual(level, data, event, table, room, false, near, far)) {
                break;
            }
            swarms++;
            heads += event.virtualSize() - before;
            arrival = Math.max(arrival, ticksToMaterialize(event.virtual.get(event.virtual.size() - 1), event, table));
        }
        if (swarms > 0) {
            event.burstUntil = now + arrival + OPENING_BURST_TICKS;
            OvergrownVegetation.LOGGER.info("TD: 始めの一斉投入 — {} 群れ・{} 体(読み込まれている攻め手 {}、上限 {})、実体化の輪まで最長 {} 秒",
                    swarms, heads, loaded, concurrentCap(level, table), arrival / 20L);
        }
    }

    /**
     * 群れが実体化の輪({@code materializeRadius})に着くまでの tick(もう内側なら 0、{@value #OPENING_MAX_ARRIVAL_TICKS} まで)。
     * 行進は {@value #MOVE_INTERVAL} tick ごとに速さ × その tick ぶん進むので、1 回ぶん足しておく。
     */
    static long ticksToMaterialize(TdData.VirtualSwarm v, TdData.Event event, TdTable table) {
        double dx = v.x - (event.target().getX() + 0.5D);
        double dz = v.z - (event.target().getZ() + 0.5D);
        double over = Math.sqrt(dx * dx + dz * dz) - table.materializeRadius();
        if (over <= 0.0D) {
            return 0L;
        }
        double ticks = over / Math.max(v.speed, 1.0E-3D) + MOVE_INTERVAL;
        return (long) Math.min(OPENING_MAX_ARRIVAL_TICKS, Math.ceil(ticks));
    }

    /**
     * 予算の<b>打ち止め</b>の境目: 予算がこれを下回ったら、もう 1 群れも買えない(勝ちの条件・残党・ボスバーの残りの境目)。
     * 1 バイオマス = 1 体だった頃の {@code budget < 1.0} の置き換え。残った端数は勝ったときに世界のバイオマスへ戻る({@link #victory})。
     *
     * <p>値は<b>群れの先頭になれる種</b>のうちいちばん安い値段({@link #cheapestLead})。群れは編成の先頭から買えるところまでを買う
     * ({@link #spawnOneVirtual})ので、先頭の 1 体が買えなければ 1 体も出ない。2026-09-26 までは「そのフェーズに過繁茂バイオームで湧く
     * 全部の種」のいちばん安い値段を見ていたので、侵攻に出ないペディキュラ(値段を決めていない = 1)で境目が 1 になり、アルボラ
     * (10)を買えない端数(1〜9)が残ると、攻め手を全滅させても勝ちにならず侵攻が終わらなかった。
     */
    static double cheapest(ServerLevel level, TdData.Event event) {
        int phase = event.phase >= 0 ? event.phase : PhaseData.get(level).getPhase();
        return cheapestLead(phase, DesignData.td(), DesignData.swarm());
    }

    /**
     * フェーズ {@code phase} の侵攻で、群れの<b>先頭になれる種</b>のうちいちばん安い値段(下限 1。先頭になれる種が無ければ 1)。
     * 見る編成は {@link #rollRoster} が振りうるもの: 地上(空の割合が 1 未満のとき)・登る群れ({@code climberShare} &gt; 0)・
     * 空({@code airSwarmShare} &gt; 0 で、空の群れ 1 つが {@code maxAirAttackers} に収まるとき)。護衛は並びの最後なので先頭にならない。
     */
    static double cheapestLead(int phase, TdTable table, SwarmTable swarms) {
        List<EntityType<?>> pool = MobStats.overgrownSpawnPool(phase);
        boolean airPossible = table.airSwarmShare() > 0.0D && swarms.maxSize(Swarm.Kind.AIR) <= table.maxAirAttackers();
        double min = Double.MAX_VALUE;
        if (!airPossible || table.airSwarmShare() < 1.0D) {
            if (table.climberShare() < 1.0D) {
                min = Math.min(min, leadCost(templateFor(swarms, phase, Swarm.Kind.GROUND, false), pool));
            }
            if (table.climberShare() > 0.0D) {
                min = Math.min(min, leadCost(templateFor(swarms, phase, Swarm.Kind.GROUND, true), pool));
            }
        }
        if (airPossible) {
            min = Math.min(min, leadCost(templateFor(swarms, phase, Swarm.Kind.AIR, false), pool));
        }
        return min == Double.MAX_VALUE ? 1.0D : Math.max(1.0D, min);
    }

    /**
     * 編成の先頭になりうる種のうちいちばん安い値段(無ければ {@link Double#MAX_VALUE})。振った編成からは {@link #rollTemplate} と
     * 同じくそのフェーズに湧かない種が外れる(全部外れる編成はそのまま)ので、湧く種が 1 つでもあれば湧く種だけを見る。
     * 行を上から見て、必ず 1 体は入る行(min ≥ 1)で止める —— その下の行の種は先頭に来ない。抽選でしか入らない行は、
     * 入る見込みがあるとき(確率 &gt; 0)だけ数える。
     */
    private static double leadCost(@Nullable SwarmTable.Template template, List<EntityType<?>> pool) {
        if (template == null) {
            return Double.MAX_VALUE;
        }
        boolean anyInPool = false;
        for (SwarmTable.MemberSpec spec : template.members()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(spec.id()).orElse(null);
            if (type != null && spec.max() >= 1 && pool.contains(type)) {
                anyInPool = true;
                break;
            }
        }
        double min = Double.MAX_VALUE;
        for (SwarmTable.MemberSpec spec : template.members()) {
            EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.getOptional(spec.id()).orElse(null);
            if (type == null || spec.max() < 1 || (anyInPool && !pool.contains(type))) {
                continue;
            }
            double chance = spec.chance() != null ? spec.chance() : OvergrownSpawnProfile.spawnChance(type);
            if (spec.min() >= 1 || chance > 0.0D) {
                min = Math.min(min, MobStats.tdCost(type));
            }
            if (spec.min() >= 1) {
                break;
            }
        }
        return min;
    }

    /**
     * 残りのバイオマス(ボスバーの N・{@code /ov td}・{@code /ov biomass}。ユーザー指定 2026-09-26「残り何体ではなく、あと何バイオマス
     * 分出てくるか」): まだ買える予算 + 行進中の群れの値段 + 生きている攻め手の値段。群れを買っても実体にしても変わらず、倒した
     * 攻め手の値段だけ減る。もう 1 群れも買えない予算の端数({@link #cheapest} 未満)は出てこない(勝てば世界へ返る)ので数えない
     * —— 最後の攻め手を倒したとき 0 になる。
     */
    public static double remainingBiomass(ServerLevel level, TdData.Event event) {
        return comingBiomass(level, event) + event.aliveCost();
    }

    /** {@link #remainingBiomass} のうち、まだ出てきていない分(まだ買える予算 + 行進中の群れの値段)。 */
    public static double comingBiomass(ServerLevel level, TdData.Event event) {
        double budget = event.budget >= cheapest(level, event) ? event.budget : 0.0D;
        return budget + virtualCost(event);
    }

    /** 表示用のバイオマス(切り捨て・3 桁区切り)。浮動小数の誤差で 1 下がって見えないよう、ごくわずかに足してから切り捨てる。 */
    public static String biomassText(double biomass) {
        return String.format(Locale.ROOT, "%,d", (long) Math.floor(Math.max(0.0D, biomass) + 1.0E-6D));
    }

    /** 仮想の群れ 1 つを買うのに使ったバイオマス(置けずに諦めたときに予算へ戻す額)。 */
    private static double virtualCost(TdData.VirtualSwarm v) {
        double total = 0.0D;
        for (Object2IntMap.Entry<String> e : v.counts.object2IntEntrySet()) {
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) : null;
            // 消えた種は 1 体 1 として戻す(値段が引けないので、少なくとも元の頭数は返す)。
            total += Math.max(0, e.getIntValue()) * (type != null ? MobStats.tdCost(type) : 1.0D);
        }
        return total;
    }

    /** 行進中の群れ全部を買うのに使ったバイオマス(取りやめ・負けのときの払い戻し)。 */
    private static double virtualCost(TdData.Event event) {
        double total = 0.0D;
        for (TdData.VirtualSwarm v : event.virtualSwarms()) {
            total += virtualCost(v);
        }
        return total;
    }

    /**
     * 仮想の群れを 1 つ作る。
     *
     * @param room  まだ行進させてよい頭数({@link #spawnVirtual})。群れがこれより大きければ作らない
     * @param force 残りを見ずに作る(行進中の群れが無いとき)
     * @param minR  生まれる距離(ターゲットから、ブロック)の下限
     * @param maxR  同じく上限
     * @return 作ったら true
     */
    static boolean spawnOneVirtual(ServerLevel level, TdData data, TdData.Event event, TdTable table, int room,
                                           boolean force, double minR, double maxR) {
        if (!force && room <= 0) {
            return false;
        }
        RandomSource random = level.random;
        int phase = event.phase >= 0 ? event.phase : PhaseData.get(level).getPhase();
        // 空の群れは、同時に出ているヴェロフィル(実体 + 行進中)が上限の残りに収まる間だけ(仮に最大の編成でも超えないように見る)。
        boolean allowAir = airAttackers(level, event) + DesignData.swarm().maxSize(Swarm.Kind.AIR) <= table.maxAirAttackers();
        Roster roster = rollRoster(phase, table, DesignData.swarm(), random, allowAir);
        if (roster == null) {
            return false;
        }
        List<EntityType<?>> types = roster.types();
        // 予算の残りが編成より少なければ、買えるところまでの小さな群れにする(予算を使い切る。ユーザー指定「予算が 0 で勝ち」)。
        // 護衛は並びの最後なので、足りないときは護衛から削れる。
        // 1 体ぶんの値段は mobs.json の tdCost(未設定なら 1 = 2026-09-24 までと同じ)。強い種ほど高くすると頭数が減る。
        double cost = 0.0D;
        int n = 0;
        while (n < types.size() && cost + MobStats.tdCost(types.get(n)) <= event.budget) {
            cost += MobStats.tdCost(types.get(n));
            n++;
        }
        if (n <= 0 || (!force && n > room)) {
            return false;
        }
        types = types.subList(0, n);
        double[] xz = ringPoint(level, event, table, random, minR, maxR);
        boolean air = roster.kind() == Swarm.Kind.AIR;
        double ground = groundY(level, xz[0], xz[1], event.target().getY() + 1.0D);
        TdData.VirtualSwarm swarm = new TdData.VirtualSwarm(UUID.randomUUID(), air, xz[0],
                ground + (air ? AIR_ALTITUDE : 0.0D), xz[1], speedOf(types, air, table));
        swarm.climber = roster.climber();
        for (EntityType<?> type : types) {
            swarm.counts.addTo(EntityType.getKey(type).toString(), 1);
        }
        event.budget -= cost;
        event.virtual.add(swarm);
        data.setDirty();
        return true;
    }

    /** フェーズの編成。コマンドでフェーズ 5 より前に始めたとき(編成が無い)は、いちばん後ろの編成を使う。 */
    @Nullable
    private static SwarmTable.Template templateFor(SwarmTable swarms, int phase, Swarm.Kind kind, boolean climber) {
        if (climber) {
            SwarmTable.Template template = swarms.climbTemplate(phase);
            return template != null ? template : swarms.climbTemplate(Integer.MAX_VALUE);
        }
        SwarmTable.Template template = swarms.template(phase, kind);
        return template != null ? template : swarms.template(Integer.MAX_VALUE, kind);
    }

    /**
     * 仮想の群れ 1 つの顔ぶれ。
     *
     * @param kind    地上 / 空
     * @param climber 壁・天井を登る群れ(地上だけ)
     * @param types   湧かせる種(群れの一員が先、護衛が最後)
     */
    record Roster(Swarm.Kind kind, boolean climber, List<EntityType<?>> types) {
    }

    /**
     * 仮想の群れ 1 つの顔ぶれを振る(2026-09-25)。
     * <ol>
     *     <li>{@code airSwarmShare} で空の群れ、残りは地上。地上のうち {@code climberShare} は<b>登る群れ</b>(swarm.json の templates.climb、
     *     アルボラだけ。ユーザー指定「壁を壊して入るグループと、壁を登って壁の上のプレイヤーを襲うグループ」)。</li>
     *     <li>編成から、侵攻が起きたフェーズ({@code phase})に過繁茂バイオームで湧かない種を外す(ユーザー指定「TD が起きたフェーズで
     *     湧く mob のみ」。mobs.json の spawns と naturalSpawn)。1 体も残らない(コマンドでフェーズ 5 より前に始めた)ときだけ編成のまま。</li>
     *     <li>地上の群れには、グループ 5〜9 ごとに {@code escortChance} で<b>護衛</b>を 1 体足す(そのフェーズに湧く、群れに入らない種から。
     *     ユーザー指定「TD で G4〜9 も」)。</li>
     * </ol>
     * 登る群れの編成が空なら地上、地上が空なら空、それも空なら null。
     */
    @Nullable
    static Roster rollRoster(int phase, TdTable table, SwarmTable swarms, RandomSource random) {
        return rollRoster(phase, table, swarms, random, true);
    }

    /**
     * {@link #rollRoster(int, TdTable, SwarmTable, RandomSource)} の、空の群れを出してよいかを選べる形。{@code allowAir} が false なら
     * 地上だけ(同時に出ているヴェロフィルが {@code maxAirAttackers} に届いているとき。地上の編成が空なら null)。
     */
    @Nullable
    static Roster rollRoster(int phase, TdTable table, SwarmTable swarms, RandomSource random, boolean allowAir) {
        List<EntityType<?>> pool = MobStats.overgrownSpawnPool(phase);
        Swarm.Kind kind = allowAir && random.nextDouble() < table.airSwarmShare() ? Swarm.Kind.AIR : Swarm.Kind.GROUND;
        boolean climber = kind == Swarm.Kind.GROUND && random.nextDouble() < table.climberShare();
        List<EntityType<?>> types = rollTemplate(swarms, phase, kind, climber, pool, random);
        if (types.isEmpty() && climber) {
            climber = false;
            types = rollTemplate(swarms, phase, kind, false, pool, random);
        }
        if (types.isEmpty() && (allowAir || kind == Swarm.Kind.AIR)) {
            kind = kind == Swarm.Kind.AIR ? Swarm.Kind.GROUND : Swarm.Kind.AIR;
            types = rollTemplate(swarms, phase, kind, false, pool, random);
        }
        if (types.isEmpty()) {
            return null;
        }
        if (kind == Swarm.Kind.GROUND) {
            addEscorts(types, table, pool, random);
        }
        return new Roster(kind, climber, types);
    }

    /** 編成を振り、そのフェーズに湧かない種を外す(外すと 1 体も残らないときは編成のまま)。 */
    private static List<EntityType<?>> rollTemplate(SwarmTable swarms, int phase, Swarm.Kind kind, boolean climber,
                                                    List<EntityType<?>> pool, RandomSource random) {
        SwarmTable.Template template = templateFor(swarms, phase, kind, climber);
        if (template == null) {
            return new ArrayList<>();
        }
        List<EntityType<?>> rolled = new ArrayList<>(template.roll(random, null, swarms.maxSize(kind)));
        List<EntityType<?>> inPhase = new ArrayList<>(rolled);
        inPhase.removeIf(type -> !pool.contains(type));
        return inPhase.isEmpty() ? rolled : inPhase;
    }

    /**
     * 護衛を足す: グループ 5〜9 ごとに {@code escortChance} で 1 体。候補は、そのフェーズに過繁茂バイオームで湧き、群れに入らず
     * (群れの種は G4 の編成で出る)、{@code escortExcluded} に無い種。候補が無ければ足さない(いま G6〜G9 は空)。
     */
    private static void addEscorts(List<EntityType<?>> types, TdTable table, List<EntityType<?>> pool, RandomSource random) {
        for (int g = TdTable.ESCORT_MIN_GROUP; g <= TdTable.ESCORT_MAX_GROUP; g++) {
            double chance = table.escortChance(g);
            if (!(chance > 0.0D) || random.nextDouble() >= chance) {
                continue;
            }
            MobGroup group = MobGroup.byNumber(g);
            if (group == null) {
                continue;
            }
            List<EntityType<?>> candidates = new ArrayList<>();
            for (EntityType<?> type : group.members()) {
                if (pool.contains(type) && SwarmRole.of(type) == null && !table.escortExcluded(EntityType.getKey(type))) {
                    candidates.add(type);
                }
            }
            if (!candidates.isEmpty()) {
                types.add(candidates.get(random.nextInt(candidates.size())));
            }
        }
    }

    /**
     * 行進の速さ(ブロック / tick)。地上は編成の中でいちばん遅い種の {@code movementSpeed} × {@code groundSpeedFactor}
     * (群れはいちばん遅い一員に歩調を合わせるので)。空は {@code airSpeed}。仮決め 2026-09-25。
     */
    private static double speedOf(List<EntityType<?>> types, boolean air, TdTable table) {
        if (air) {
            return table.airSpeed();
        }
        double slowest = Double.MAX_VALUE;
        for (EntityType<?> type : types) {
            MobStat stat = DesignData.mobs().forId(EntityType.getKey(type).getPath());
            double speed = stat.movementSpeed != null && stat.movementSpeed > 0.0D ? stat.movementSpeed : 0.25D;
            slowest = Math.min(slowest, speed);
        }
        return (slowest == Double.MAX_VALUE ? 0.25D : slowest) * table.groundSpeedFactor();
    }

    /**
     * 仮想の群れが生まれる点(x, z)。向きは、いま行進している・待っている仮想の群れの向き(ターゲットから見た角度)のあいだの
     * <b>いちばん大きな隙間</b>の真ん中あたり({@link #pickAngle})、ターゲットからの距離は {@code minR}〜{@code maxR} の乱数。
     *
     * <p>2026-09-25 ユーザー報告「mob の攻めてくる方向がほとんど 1 つの方向からだけ」: はじめは輪の全周から過繁茂チャンクに
     * 当たるまで引いていたので、過繁茂が片側にしかない拠点では群れが全部その側から来ていた → 8 つの扇のうち群れの少ない扇
     * (扇の中では過繁茂チャンクを選ぶ)にした。同日の runClient 4 回目「8 方向だけでなく本当に全方位から攻めてくるように」
     * (ユーザー指定): 扇の中でも過繁茂の塊に寄って 8 本の筋に見えていたので、扇と過繁茂の好みをやめて角度を連続にした。
     * 隙間の真ん中の半分から選ぶのは仮決め(群れが 6 つあれば、どの 2 つの向きの間も 90 度より開かない)。
     *
     * <p>距離は {@link #spawnDistance}: その輪が近くのプレイヤーの見える範囲に入っていれば、霧の外まで押し出す
     * (2026-09-26 のユーザー指定「外周の霧の中から出てくる感じに」)。
     */
    private static double[] ringPoint(ServerLevel level, TdData.Event event, TdTable table, RandomSource random,
                                      double minR, double maxR) {
        BlockPos target = event.target();
        double tx = target.getX() + 0.5D;
        double tz = target.getZ() + 0.5D;
        List<Double> taken = new ArrayList<>(event.virtual.size());
        for (TdData.VirtualSwarm v : event.virtual) {
            taken.add(Math.atan2(v.z - tz, v.x - tx));
        }
        // 一方向の侵攻は、その向きの扇の中だけ(2026-09-26 のユーザー指定「北や西など一方向から攻めてくる場合も」)。
        double angle = event.direction.around() ? pickAngle(taken, random)
                : pickAngleInFan(taken, event.direction.bearing(), Math.toRadians(table.directionalSpread()), random);
        double r = spawnDistance(tx, tz, angle, minR, maxR, TdSight.around(level, target), table.virtualSpawnBeyondSight(),
                table.virtualSpawnFarthest(), random);
        return new double[]{tx + Math.cos(angle) * r, tz + Math.sin(angle) * r};
    }

    /** {@link #ringPoint} をテストから(群れは作らない)。 */
    static double[] ringPointForTests(ServerLevel level, TdData.Event event, TdTable table, RandomSource random) {
        return ringPoint(level, event, table, random, table.virtualSpawnMin(), table.virtualSpawnMax());
    }

    /**
     * 仮想の群れが生まれる、ターゲットからの距離(向き {@code angle} の上。ブロック)。
     * <ol>
     *     <li>まず {@code minR}〜{@code maxR} の乱数(前と同じ輪)。</li>
     *     <li>その点が誰かの見える範囲(見える距離 + {@code margin}〜2 × {@code margin}。{@link TdSight})に入っていれば、同じ向きの
     *     まま全員の範囲の外まで押し出す(外へ出た先で別の人の範囲に入れば、そこも越える)。霧の終わりちょうどでは絵が霧の色に
     *     溶け切っているので、少し外(仮決め 16〜32)から歩いて霧を抜けてくる。</li>
     *     <li>押し出しても {@code farthest} より遠くへはやらない(描画距離がとても長いと行進が長すぎる。その先は見えている所に現れる)。</li>
     * </ol>
     * {@code margin} が負・見ている人がいない(GameTest のサーバー)ときは 1 だけ(乱数の引き方も前と同じ)。
     */
    static double spawnDistance(double tx, double tz, double angle, double minR, double maxR, List<TdSight.Viewer> viewers,
                                double margin, double farthest, RandomSource random) {
        double r = minR + random.nextDouble() * Math.max(0.0D, maxR - minR);
        if (margin < 0.0D || viewers.isEmpty()) {
            return r;
        }
        double extra = margin * (1.0D + random.nextDouble());
        double out = outOfSight(tx, tz, Math.cos(angle), Math.sin(angle), r, viewers, extra);
        return out > r ? Math.min(out, Math.max(farthest, r)) : r;
    }

    /**
     * ターゲットから向き ({@code ux}, {@code uz}) へ {@code from} 以上進んだ所で、どの人の「見える距離 + {@code extra}」の円にも
     * 入っていない最初の距離。円は向きの線と区間 (a − s, a + s) で交わる(a = 人の位置を線へ落とした距離)ので、今の距離が区間の中なら
     * 奥の端へ進める。距離は増えるだけなので、人の数 + 1 回で落ち着く。
     */
    static double outOfSight(double tx, double tz, double ux, double uz, double from, List<TdSight.Viewer> viewers,
                             double extra) {
        double d = from;
        for (int pass = 0; pass <= viewers.size(); pass++) {
            boolean moved = false;
            for (TdSight.Viewer v : viewers) {
                double wx = v.x() - tx;
                double wz = v.z() - tz;
                double a = wx * ux + wz * uz;
                double radius = v.sight() + extra;
                double disc = a * a - (wx * wx + wz * wz) + radius * radius;
                if (disc <= 0.0D) {
                    continue; // この向きの線は円に入らない
                }
                double s = Math.sqrt(disc);
                if (d > a - s && d < a + s) {
                    d = a + s;
                    moved = true;
                }
            }
            if (!moved) {
                break;
            }
        }
        return d;
    }

    /**
     * 空いている向き(ラジアン): {@code taken} の角度を小さい順に並べ、隣どうしの隙間(最後と最初の間も)のいちばん大きいものの、
     * 真ん中の半分から乱数で選ぶ。同じ大きさの隙間は乱数で。何も無ければ全周から乱数。
     */
    static double pickAngle(List<Double> taken, RandomSource random) {
        double turn = Math.PI * 2.0D;
        if (taken.isEmpty()) {
            return random.nextDouble() * turn;
        }
        double[] a = new double[taken.size()];
        for (int i = 0; i < a.length; i++) {
            double v = taken.get(i) % turn;
            a[i] = v < 0.0D ? v + turn : v;
        }
        java.util.Arrays.sort(a);
        double bestStart = a[0];
        double bestGap = -1.0D;
        int ties = 0;
        for (int i = 0; i < a.length; i++) {
            double end = i + 1 < a.length ? a[i + 1] : a[0] + turn;
            double gap = end - a[i];
            if (gap > bestGap + 1.0E-9D) {
                bestGap = gap;
                bestStart = a[i];
                ties = 1;
            } else if (Math.abs(gap - bestGap) <= 1.0E-9D && random.nextInt(++ties) == 0) {
                bestStart = a[i];
            }
        }
        return bestStart + bestGap * (0.25D + 0.5D * random.nextDouble());
    }

    /**
     * 一方向の侵攻の群れの向き(ラジアン): 向き {@code center} ± {@code half} の扇の中で、扇に入っている {@code taken} の向きと
     * 扇の両端のあいだのいちばん大きな隙間の、真ん中の半分から乱数({@link #pickAngle} の扇版。扇の外の群れは見ない)。
     * 最初の群れは扇の真ん中あたり、あとは隙間を埋めて扇いっぱいの前線になる。
     */
    static double pickAngleInFan(List<Double> taken, double center, double half, RandomSource random) {
        List<Double> inside = new ArrayList<>(taken.size());
        for (double a : taken) {
            double d = angleBetween(center, a);
            if (Math.abs(d) <= half) {
                inside.add(d);
            }
        }
        inside.sort(null);
        double prev = -half;
        double bestStart = -half;
        double bestGap = -1.0D;
        int ties = 0;
        for (int i = 0; i <= inside.size(); i++) {
            double next = i < inside.size() ? inside.get(i) : half;
            double gap = next - prev;
            if (gap > bestGap + 1.0E-9D) {
                bestGap = gap;
                bestStart = prev;
                ties = 1;
            } else if (Math.abs(gap - bestGap) <= 1.0E-9D && random.nextInt(++ties) == 0) {
                bestStart = prev;
            }
            prev = next;
        }
        return center + bestStart + bestGap * (0.25D + 0.5D * random.nextDouble());
    }

    /**
     * 侵攻が攻めてくる向きを振る(侵攻に入るとき。2026-09-26 のユーザー指定「大体全周囲 4 割、特定の方向からが 6 割くらい。
     * 過繁茂したバイオームがある方向からになりやすいように」)。{@code directionalShare} の割合で一方向、その向きは 8 方位のうち
     * ターゲットから {@code overgrownSearchRadius} の中の過繁茂チャンクが多い向きほど選ばれやすい({@link #pickSector})。
     */
    static TdDirection rollDirection(ServerLevel level, BlockPos target, TdTable table, RandomSource random) {
        PhaseData phase = PhaseData.get(level);
        return rollDirection(() -> overgrownSectors(target.getX() + 0.5D, target.getZ() + 0.5D, table.overgrownSearchRadius(),
                phase::isOvergrownChunk), table.directionalShare(), table.directionalOvergrownBias(), random);
    }

    /** {@link #rollDirection(ServerLevel, BlockPos, TdTable, RandomSource)} の中身(過繁茂の数えは一方向になったときだけ)。 */
    static TdDirection rollDirection(java.util.function.Supplier<double[]> sectors, double share, double bias,
                                     RandomSource random) {
        if (!(random.nextDouble() < share)) {
            return TdDirection.AROUND;
        }
        return pickSector(sectors.get(), bias, random);
    }

    /**
     * 8 方位({@link TdDirection#COMPASS} の順)から 1 つ: それぞれの確率 = {@code bias} × その向きの過繁茂チャンクの割合 +
     * (1 − {@code bias}) ÷ 8。過繁茂チャンクが 1 つも無ければ等しく。
     */
    static TdDirection pickSector(double[] counts, double bias, RandomSource random) {
        double total = 0.0D;
        for (double c : counts) {
            total += Math.max(0.0D, c);
        }
        double r = random.nextDouble();
        for (int i = 0; i < TdDirection.COMPASS.length; i++) {
            double p = total > 0.0D ? bias * Math.max(0.0D, counts[i]) / total + (1.0D - bias) / 8.0D : 1.0D / 8.0D;
            r -= p;
            if (r < 0.0D) {
                return TdDirection.COMPASS[i];
            }
        }
        return TdDirection.COMPASS[TdDirection.COMPASS.length - 1];
    }

    /**
     * ターゲットから {@code radius} の中の過繁茂チャンクを、8 方位({@link TdDirection#COMPASS} の順、45 度ずつ)に数える。
     * チャンクの中心で測る。ターゲットのすぐそば(中心が 8 ブロックより近い)のチャンクは向きが決まらないので数えない。
     */
    static double[] overgrownSectors(double tx, double tz, double radius, java.util.function.Predicate<ChunkPos> overgrown) {
        double[] counts = new double[TdDirection.COMPASS.length];
        int cx = Mth.floor(tx) >> 4;
        int cz = Mth.floor(tz) >> 4;
        int r = Mth.ceil(radius / 16.0D);
        double reachSq = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double ox = ((cx + dx) << 4) + 8.0D - tx;
                double oz = ((cz + dz) << 4) + 8.0D - tz;
                double sq = ox * ox + oz * oz;
                if (sq > reachSq || sq < 64.0D || !overgrown.test(new ChunkPos(cx + dx, cz + dz))) {
                    continue;
                }
                counts[TdDirection.sectorOf(Math.atan2(oz, ox))] += 1.0D;
            }
        }
        return counts;
    }

    /** 角度 {@code from} から {@code to} へ回る量(ラジアン、−π〜π。正 = 角度が増える向き)。 */
    static double angleBetween(double from, double to) {
        double d = (to - from) % (Math.PI * 2.0D);
        if (d > Math.PI) {
            d -= Math.PI * 2.0D;
        } else if (d <= -Math.PI) {
            d += Math.PI * 2.0D;
        }
        return d;
    }

    /** 同時に出ているヴェロフィル = 読み込まれている空の役割の攻め手 + 行進中の空の群れの頭数。 */
    static int airAttackers(ServerLevel level, TdData.Event event) {
        int n = 0;
        LevelMobs loaded = existing(level);
        if (loaded != null) {
            for (Mob mob : loaded.mobs.values()) {
                if (SwarmRole.of(mob) == SwarmRole.AIR) {
                    n++;
                }
            }
        }
        for (TdData.VirtualSwarm v : event.virtual) {
            if (v.air) {
                n += v.size();
            }
        }
        return n;
    }

    /**
     * 仮想の群れを {@code ticks} ぶん進める。実体化の距離の少し内側で止まって待つ。置けなかった群れは、その間ターゲットへは進まず
     * 輪に沿って横へ歩く({@link #sidestep})。
     */
    private static void moveVirtual(ServerLevel level, TdData data, TdData.Event event, TdTable table, int ticks) {
        if (event.virtual.isEmpty()) {
            return;
        }
        double tx = event.target().getX() + 0.5D;
        double tz = event.target().getZ() + 0.5D;
        double stop = holdRing(table);
        for (TdData.VirtualSwarm v : event.virtual) {
            if (!Double.isNaN(v.sidestepAngle)) {
                sidestep(level, v, tx, tz, stop, ticks);
                continue;
            }
            double dx = tx - v.x;
            double dz = tz - v.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist <= stop) {
                continue;
            }
            double step = Math.min(v.speed * ticks, dist - stop);
            v.x += dx / dist * step;
            v.z += dz / dist * step;
            double ground = groundY(level, v.x, v.z, Double.NaN);
            if (!Double.isNaN(ground)) {
                v.y = ground + (v.air ? AIR_ALTITUDE : 0.0D);
            }
        }
        data.setDirty();
    }

    /**
     * 仮想の群れを実体にする(条件はクラスの説明)。1 体でも置けたら、その群れは実体になったものとして仮想の群れから外し、
     * 置けなかった残り(体の大きい種が入らなかった)は予算へ戻す(次の編成で作り直す。1 つの群れを小さな群れに分けて出さない)。
     * 1 体も置けなければ、輪の上で回して({@link #nudge})次の帳簿でやり直す。{@value #MAX_MATERIALIZE_FAILURES} 回置けなければ
     * 頭数を予算へ戻して、別の場所に作り直させる(水の上など)。
     *
     * <p>同時の上限は<b>読み込まれている攻め手</b>で見る(レビュー指摘 2026-09-25: 保存している生きている数で見ていたので、
     * 読み込み外れた・遠くで実体になって置き去りにされた攻め手が数に残り続けると、仮想の群れが輪の上で永久に待ち、
     * 勝ちも負けも来なくなった)。
     *
     * <p>空の群れは地面を探さず、遠景の身代わりの絵と同じ位置・高さ・向きに湧かせる({@link #airPlacements}。2026-09-25 の runClient
     * 4 回目のユーザー報告「ヴェロフィルが mob に変わるとき、絵は空中にいるのに mob になった瞬間地面に湧く」「絵の状態で突然横に
     * 高速移動する」: 地上の群れと同じく地面に置いていたので、翼幅 6 ブロックを超えるヴェロフィルは森ではまず体が入らず、置けない
     * たびに群れを輪の上で 16〜32 ブロック瞬間移動させていた = 絵が 0.5 秒で横へ滑る)。
     *
     * @param limit この回に実体にする群れの数の上限(帳簿 {@value #MAX_MATERIALIZE_PER_BOOKKEEPING}、始めの一斉投入の直後は毎 tick
     *              {@value #OPENING_PER_TICK})
     * @return 実体にした群れの数
     */
    private static int materialize(ServerLevel level, TdData data, TdData.Event event, TdTable table, int limit) {
        if (event.virtual.isEmpty()) {
            return 0;
        }
        double tx = event.target().getX() + 0.5D;
        double tz = event.target().getZ() + 0.5D;
        double materializeSq = table.materializeRadius() * table.materializeRadius();
        double playerSq = table.playerMaterializeRadius() * table.playerMaterializeRadius();
        double holdRing = holdRing(table);
        int made = 0;
        Iterator<TdData.VirtualSwarm> it = event.virtual.iterator();
        while (it.hasNext()) {
            if (made >= limit) {
                return made; // 残りは次の回で
            }
            TdData.VirtualSwarm v = it.next();
            int size = v.size();
            if (size <= 0) {
                it.remove();
                data.setDirty();
                continue;
            }
            if (!Double.isNaN(v.sidestepAngle)) {
                continue; // 置けなかったので輪に沿って歩いている。着いてからやり直す
            }
            double dx = v.x - tx;
            double dz = v.z - tz;
            boolean near = dx * dx + dz * dz <= materializeSq || playerNear(level, v.x, v.y, v.z, playerSq);
            if (!near) {
                continue;
            }
            int loaded = loadedAttackers(level);
            if (loaded > 0 && loaded + size > concurrentCap(level, table)) {
                continue; // 上限(ユーザー指定)。輪の上で待つ
            }
            int bx = Mth.floor(v.x);
            int bz = Mth.floor(v.z);
            if (level.getChunkSource().getChunkNow(bx >> 4, bz >> 4) == null) {
                continue;
            }
            // 地上の群れ(と護衛)は中心の地面のまわりに置く。空の群れは空中の決まった位置なので、地面は護衛のためだけに引く。
            BlockPos ground = TreeCanopyGroundFinder.findSpawnSurface(level, bx, bz);
            if (ground == null) {
                ground = BlockPos.containing(v.x, groundY(level, v.x, v.z, v.y), v.z);
            }
            if (!level.isPositionEntityTicking(ground)) {
                continue;
            }
            List<EntityType<?>> types = typesOf(v, event);
            if (types.isEmpty()) {
                it.remove();
                data.setDirty();
                continue;
            }
            // 群れの一員と護衛(群れに入らない G5〜G9)に分ける。
            List<EntityType<?>> members = new ArrayList<>(types.size());
            List<EntityType<?>> escorts = new ArrayList<>(2);
            for (EntityType<?> type : types) {
                (SwarmRole.of(type) != null ? members : escorts).add(type);
            }
            int spawned = 0;
            // 置けたぶんの値段。置けなかったぶん(買った額 − これ)を予算へ戻すので、種ごとに数える
            // (tdCost が種で違うので「頭数 × 1」では戻しすぎ・戻さなすぎになる)。
            double spawnedCost = 0.0D;
            Swarm swarm = null;
            if (!members.isEmpty()) {
                SwarmSpawner.Result result = v.air
                        ? SwarmSpawner.spawnPlaced(level, airPlacements(level, v, tx, tz), SwarmOrigin.TD, event.eventId(), null)
                        : SwarmSpawner.spawn(level, ground, members, SwarmOrigin.TD, event.eventId(), null,
                                v.climber() ? TdTicker::markClimber : null);
                spawned += result.members().size();
                for (Mob mob : result.members()) {
                    spawnedCost += MobStats.tdCost(mob.getType());
                }
                swarm = result.swarm(level);
            }
            for (EntityType<?> type : escorts) {
                if (SwarmSpawner.spawnLoose(level, type, ground, SwarmOrigin.TD, event.eventId()) != null) {
                    spawned++;
                    spawnedCost += MobStats.tdCost(type);
                }
            }
            if (spawned > 0) {
                made++;
                event.alive += spawned;
                event.aliveCost += spawnedCost;
                if (swarm != null && !event.target().equals(swarm.targetBlock())) {
                    swarm.setSiege(event.target());
                }
                double left = virtualCost(v) - spawnedCost;
                if (left > 0.0D) {
                    // 置けなかった残りは予算へ(biomass は失わない)。仮決め 2026-09-25(レビュー指摘: 残して次の帳簿でやり直すと、
                    // 1 つの群れが小さな群れに分かれて出ていた)。
                    event.budget += left;
                }
                it.remove();
                data.setDirty();
                continue;
            }
            // 1 体も置けなかった(水・溶岩・急な斜面・体が入らない)。数えるのはこのときだけ。
            v.failures++;
            if (v.failures >= MAX_MATERIALIZE_FAILURES) {
                event.budget += virtualCost(v);
                it.remove();
                OvergrownVegetation.LOGGER.debug("TD: 仮想の群れを置けないので予算へ戻した({} 体)", v.size());
            } else {
                startSidestep(v, event, table, tx, tz, level.random);
            }
            data.setDirty();
        }
        return made;
    }

    /**
     * 空の群れの一員を湧かせる位置 = 遠景の身代わりの絵の位置({@link HordeFormation}: 群れの番号と頭数から作る隊列を、ターゲットを
     * 向けて置く。高さは群れの高さと「梢・地形の上 {@value HordeFormation#AIR_CLEARANCE}」の高い方 + 一員ごとのずれ)。向きも絵と同じ
     * (ターゲットへ + ばらし)。群れに入らない種の席は飛ばす(空の群れには護衛が付かない)。
     */
    static List<SwarmSpawner.Placement> airPlacements(ServerLevel level, TdData.VirtualSwarm v, double tx, double tz) {
        List<HordeFormation.Slot> slots = HordeFormation.slots(TdHordeSync.viewId(v), TdHordeSync.counts(v));
        double dx = tx - v.x;
        double dz = tz - v.z;
        double dist = Math.sqrt(dx * dx + dz * dz);
        float fx = dist > 1.0E-6D ? (float) (dx / dist) : 0.0F;
        float fz = dist > 1.0E-6D ? (float) (dz / dist) : 1.0F;
        float yaw = HordeFormation.yaw(fx, fz);
        List<SwarmSpawner.Placement> out = new ArrayList<>(slots.size());
        for (HordeFormation.Slot slot : slots) {
            if (SwarmRole.of(slot.type()) == null) {
                continue;
            }
            double x = HordeFormation.x(v.x, fx, fz, slot);
            double z = HordeFormation.z(v.z, fx, fz, slot);
            double top = canopyTop(level, x, z, v.y - HordeFormation.AIR_CLEARANCE);
            out.add(new SwarmSpawner.Placement(slot.type(), x, HordeFormation.airY(v.y, top, slot), z, yaw + slot.yawJitter()));
        }
        return out;
    }

    /**
     * 梢・地形の一番上(動きを止めるブロック —— 葉を含む —— の上の最初の空き)の高さ。クライアントの絵の高さ
     * ({@code ClientHorde} の {@code Level#getHeight(MOTION_BLOCKING)})と同じ値。読み込まれていなければ {@code fallback}。
     */
    private static double canopyTop(ServerLevel level, double x, double z, double fallback) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        return chunk == null ? fallback : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, bx & 15, bz & 15) + 1;
    }

    /**
     * 登る群れの一員の所属に climber を立てる({@link SwarmSpawner#spawn} の「足す前」。所属は書かれたあと・addFreshEntity の前。
     * 群れは最初に登録された一員の所属から登る群れになる)。
     */
    private static void markClimber(Mob mob) {
        mob.getExistingData(ModAttachments.SWARM).ifPresent(m -> mob.setData(ModAttachments.SWARM, m.withClimber(true)));
    }

    /** 仮想の群れが行進をやめて待つ輪(ターゲットから、ブロック)= 実体化の距離 − 4。 */
    static double holdRing(TdTable table) {
        return Math.max(4.0D, table.materializeRadius() - 4.0D);
    }

    /** 仮想の群れの種ごとの頭数を、湧かせる種の並びにする。種が引けなければ(消えた種)その頭数を予算へ戻す。 */
    private static List<EntityType<?>> typesOf(TdData.VirtualSwarm v, TdData.Event event) {
        List<EntityType<?>> out = new ArrayList<>();
        Iterator<Object2IntMap.Entry<String>> it = v.counts.object2IntEntrySet().iterator();
        while (it.hasNext()) {
            Object2IntMap.Entry<String> e = it.next();
            ResourceLocation id = ResourceLocation.tryParse(e.getKey());
            EntityType<?> type = id != null ? BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null) : null;
            if (type == null) {
                event.budget += Math.max(0, e.getIntValue());
                it.remove();
                continue;
            }
            for (int i = 0; i < e.getIntValue(); i++) {
                out.add(type);
            }
        }
        return out;
    }

    /**
     * 1 体も置けなかった群れに、ターゲットからの<b>距離を変えずに</b>輪に沿って横へ歩く行き先を決める(水の上・崖などから外す。
     * 半分〜全部の {@code NUDGE_ANGLE} ぶん。向きは最初に乱数で決め、2 回目からは同じ向きへ探し続ける)。歩くのは {@link #sidestep}、
     * 着くまで実体化はやり直さない。待つ輪({@code holdRing})より内へは決して入れない。
     *
     * <p>レビュー指摘 2026-09-25: はじめは回すたびに 8 ブロックずつターゲットへ寄せていたので、堀・湖・起伏で置けない拠点では
     * 7 回(約 7 秒)で拠点の 8 ブロック先 —— 囲った壁の内側や屋根の上 —— に湧いていた。ユーザー指定「囲った拠点は壁を壊して入る」
     * に反し、堀がプレイヤーに不利に働いていた。
     *
     * <p>同日の runClient 4 回目のユーザー報告「ヴェロフィルが絵の状態のとき、突然横に高速移動し始める」: その次は輪の上で
     * 16〜32 ブロックを瞬間移動させていたので、遠景の身代わりの絵が 0.5 秒で横へ滑っていた。いまは群れの速さで歩く
     * (遅い群れ・止まっている群れも {@value #SIDESTEP_MIN_SPEED} ブロック / tick)。
     */
    private static void startSidestep(TdData.VirtualSwarm v, TdData.Event event, TdTable table, double tx, double tz,
                                      RandomSource random) {
        if (v.sidestepSign == 0) {
            v.sidestepSign = random.nextBoolean() ? 1 : -1;
        }
        double angle = Math.atan2(v.z - tz, v.x - tx);
        double step = (0.5D + 0.5D * random.nextDouble()) * NUDGE_ANGLE;
        double next = angle + v.sidestepSign * step;
        if (!event.direction.around()) {
            // 一方向の侵攻は扇の外へ出ない(2026-09-26): 出るなら向きを返し(次からもその向き)、返しても出る狭い扇なら端で止める。
            double center = event.direction.bearing();
            double half = Math.toRadians(table.directionalSpread());
            if (Math.abs(angleBetween(center, next)) > half) {
                v.sidestepSign = -v.sidestepSign;
                next = angle + v.sidestepSign * step;
                double off = angleBetween(center, next);
                if (Math.abs(off) > half) {
                    next = center + Math.copySign(half, off);
                }
            }
        }
        v.sidestepAngle = next;
    }

    /** {@link #startSidestep} をテストから。 */
    static void startSidestepForTests(TdData.VirtualSwarm v, TdData.Event event, TdTable table, double tx, double tz,
                                      RandomSource random) {
        startSidestep(v, event, table, tx, tz, random);
    }

    /** 横へ歩く速さ(ブロック / tick)= 群れの行進の速さ(下限 {@value #SIDESTEP_MIN_SPEED})。 */
    static double sidestepSpeed(TdData.VirtualSwarm v) {
        return Math.max(SIDESTEP_MIN_SPEED, v.speed);
    }

    /** 横へ歩く群れを {@code ticks} ぶん輪に沿って進める(半径 = いまの距離と待つ輪の大きい方)。着いたら行き先を消す。 */
    private static void sidestep(ServerLevel level, TdData.VirtualSwarm v, double tx, double tz, double minRadius, int ticks) {
        double dx = v.x - tx;
        double dz = v.z - tz;
        double r = Math.max(Math.sqrt(dx * dx + dz * dz), minRadius);
        double angle = Math.atan2(dz, dx);
        double left = angleBetween(angle, v.sidestepAngle);
        double step = sidestepSpeed(v) * ticks / r;
        if (Math.abs(left) <= step) {
            angle = v.sidestepAngle;
            v.sidestepAngle = Double.NaN;
        } else {
            angle += Math.signum(left) * step;
        }
        v.x = tx + Math.cos(angle) * r;
        v.z = tz + Math.sin(angle) * r;
        double ground = groundY(level, v.x, v.z, Double.NaN);
        if (!Double.isNaN(ground)) {
            v.y = ground + (v.air ? AIR_ALTITUDE : 0.0D);
        }
    }

    /**
     * 残党(クラスの説明)。予算も仮想の群れも尽きたあとだけ。
     * <ul>
     *     <li>生きている数 &lt; 読み込まれている攻め手 → 読み込まれている数まで戻す(数から外した攻め手が読み込まれて戻ってきた・
     *     別のディメンションで倒された個体の引きすぎ。いるものを「全滅」と数えて勝ちにしない)。</li>
     *     <li>生きている数 &gt; 読み込まれている攻め手のまま {@code strayDiscardTicks} → 差を数から外す(読み込まれていない所にいるだけ)。
     *     はじめは「読み込まれている攻め手が 1 体もいない」ときだけだったので、近くに 1 体でも残っていると、遠くで置き去りにされた
     *     攻め手の数がいつまでも残った(レビュー指摘 2026-09-25)。</li>
     *     <li>ターゲットから {@code materializeRadius} より遠くに {@code strayDiscardTicks} いた、読み込まれている攻め手は消す。</li>
     * </ul>
     */
    private static void stragglers(ServerLevel level, TdData data, TdData.Event event, TdTable table, long now) {
        LevelMobs loaded = existing(level);
        boolean endPhase = event.budget < cheapest(level, event) && event.virtual.isEmpty();
        if (!endPhase) {
            if (loaded != null) {
                loaded.farSince.clear();
            }
            event.noLoadedSince = -1L;
            return;
        }
        int loadedCount = loaded != null ? loaded.mobs.size() : 0;
        if (event.alive < loadedCount) {
            event.alive = loadedCount;
            event.aliveCost = loadedCost(loaded);
            data.setDirty();
        }
        if (event.alive > loadedCount) {
            if (event.noLoadedSince < 0L) {
                event.noLoadedSince = now;
            } else if (now - event.noLoadedSince >= table.strayDiscardTicks()) {
                OvergrownVegetation.LOGGER.info("TD: 読み込まれていない攻め手 {} 体を数から外した", event.alive - loadedCount);
                event.alive = loadedCount;
                event.aliveCost = loadedCost(loaded);
                event.noLoadedSince = -1L;
                data.setDirty();
            }
        } else {
            event.noLoadedSince = -1L;
        }
        if (loaded == null || loaded.mobs.isEmpty()) {
            return;
        }
        double tx = event.target().getX() + 0.5D;
        double tz = event.target().getZ() + 0.5D;
        double limitSq = table.materializeRadius() * table.materializeRadius();
        for (Mob mob : List.copyOf(loaded.mobs.values())) {
            if (mob.isRemoved() || !mob.isAlive()) {
                continue;
            }
            double dx = mob.getX() - tx;
            double dz = mob.getZ() - tz;
            UUID id = mob.getUUID();
            if (dx * dx + dz * dz > limitSq) {
                long since = loaded.farSince.computeIfAbsent(id, k -> now);
                if (now - since >= table.strayDiscardTicks()) {
                    // discard は Leave を同期で投げる(数はそこで減る)。写しを回しているので壊れない。
                    mob.discard();
                }
            } else {
                loaded.farSince.remove(id);
            }
        }
    }

    // ------------------------------------------------------------------ 終わり方

    private static void victory(ServerLevel level, TdData data, TdData.Event event, long now) {
        double leftover = event.refundable ? Math.max(0.0D, event.budget) : 0.0D;
        int killed = event.killed;
        data.end(event, TdData.Outcome.VICTORY, now);
        if (leftover > 0.0D) {
            EcosystemData.get(level.getServer()).addBiomass(leftover);
        }
        afterEnd(level.getServer(), event);
        level.getServer().getPlayerList().broadcastSystemMessage(
                Component.translatable("message.overgrown_vegetation.td.victory", killed), false);
        OvergrownVegetation.LOGGER.info("TD: 勝ち — ターゲット {}、倒した攻め手 {}", event.target(), killed);
    }

    /**
     * 負け({@link TdEvents#onTargetDestroyed} から)。状態を先に終わりにしてからターゲットを消し(落とさない)、
     * 拠点のまわりに汚染を噴き出させ、残りの予算と仮想の群れを世界へ返す。
     */
    static void defeat(ServerLevel level, TdData data, TdData.Event event) {
        long now = level.getGameTime();
        TdTable table = DesignData.td();
        BlockPos pos = event.target();
        double refund = event.refundable ? Math.max(0.0D, event.budget) + virtualCost(event) : 0.0D;
        data.end(event, TdData.Outcome.DEFEAT, now);
        data.setDefeating(level.dimension(), pos);
        try {
            if (level.getBlockState(pos).getBlock() instanceof TargetBlock) {
                level.removeBlock(pos, false);
            }
        } finally {
            data.setDefeating(null, null);
        }
        defeatBurst(level, pos, table.defeatCorruptionRadius(), table.defeatCorruptionNoise());
        if (refund > 0.0D) {
            EcosystemData.get(level.getServer()).addBiomass(refund);
        }
        afterEnd(level.getServer(), event);
        level.getServer().getPlayerList().broadcastSystemMessage(Component.translatable(
                "message.overgrown_vegetation.td.defeat", pos.getX(), pos.getY(), pos.getZ()), false);
        level.playSound(null, pos, SoundEvents.WITHER_DEATH, SoundSource.HOSTILE, 2.0F, 0.6F);
        OvergrownVegetation.LOGGER.info("TD: 負け — ターゲット {} が壊された(世界へ返したバイオマス {})", pos,
                String.format("%.1f", refund));
    }

    /**
     * 中止・取りやめ。侵攻中に世界から切り出したバイオマス(予算 + 仮想の群れ)を返す(汚染は噴き出さない)。
     * 生きている攻め手は返さない(もう湧いているので、印を外して普段の規則に任せる)。
     */
    static void cancel(MinecraftServer server, TdData data, TdData.Event event, TdData.Outcome outcome, long now) {
        boolean wasActive = event.state() == TdData.State.ACTIVE;
        double refund = wasActive && event.refundable ? Math.max(0.0D, event.budget) + virtualCost(event) : 0.0D;
        data.end(event, outcome, now);
        if (refund > 0.0D) {
            EcosystemData.get(server).addBiomass(refund);
        }
        afterEnd(server, event);
        // 予告中に終わった(まだ何も切り出していない)ときは返した量を言わない。
        String key = !wasActive
                ? "message.overgrown_vegetation.td.called_off"
                : outcome == TdData.Outcome.ABANDONED
                ? "message.overgrown_vegetation.td.abandoned"
                : "message.overgrown_vegetation.td.cancelled";
        server.getPlayerList().broadcastSystemMessage(Component.translatable(key, biomassText(refund)), false);
        OvergrownVegetation.LOGGER.info("TD: {} — ターゲット {}(世界へ返したバイオマス {})", outcome.key(), event.target(),
                String.format("%.1f", refund));
    }

    /**
     * 終わったあとの後始末: 攻め手の印を外す(永続を戻す)・群れの攻城をやめさせる・読み込まれている攻め手の表を空にする・
     * ターゲットの「掘れない」をクライアントへ解く。
     */
    private static void afterEnd(MinecraftServer server, TdData.Event event) {
        EventMobs.releaseOrigin(server, EventMobs.Kind.TD, event.eventId());
        ServerLevel level = server.getLevel(event.dimension());
        if (level != null) {
            TargetBlockEntity.syncLock(level, event.target());
            for (Swarm swarm : SwarmManager.swarms(level)) {
                boolean ours = swarm.origin() == SwarmOrigin.TD && event.eventId().equals(swarm.eventId());
                if (ours || event.target().equals(swarm.targetBlock())) {
                    swarm.clearSiege();
                }
            }
        }
        LOADED.clear();
    }

    /**
     * 負けの汚染(ユーザー指定「拠点の周りに汚染が噴き出す」)。半径 {@code radius} の球を、半径 {@value #DEFEAT_SUB_RADIUS} の
     * 小さな球の格子に分けて {@link NoisyCorruptionBurst#deathSphere} に積む(1 tick に作る数に上限がある積み方。半径 24 を
     * 一度に作ると数万マスの書き換えが 1 tick に重なるため。仮決め 2026-09-25)。内側から作る。胞子除去装置の圏内は
     * {@code CorruptionRegistry#canCorrupt} が弾く。
     */
    static void defeatBurst(ServerLevel level, BlockPos center, int radius, double noise) {
        if (radius <= 0) {
            return;
        }
        int sub = Math.min(DEFEAT_SUB_RADIUS, radius);
        int spacing = Math.max(1, sub);
        int limit = Math.max(0, radius - sub + spacing / 2);
        int steps = limit / spacing;
        List<int[]> offsets = new ArrayList<>();
        for (int i = -steps; i <= steps; i++) {
            for (int j = -steps; j <= steps; j++) {
                for (int k = -steps; k <= steps; k++) {
                    int dx = i * spacing;
                    int dy = j * spacing;
                    int dz = k * spacing;
                    if (dx * dx + dy * dy + dz * dz <= limit * limit) {
                        offsets.add(new int[]{dx, dy, dz});
                    }
                }
            }
        }
        offsets.sort(Comparator.comparingInt(o -> o[0] * o[0] + o[1] * o[1] + o[2] * o[2]));
        Vec3 c = Vec3.atCenterOf(center);
        for (int[] o : offsets) {
            NoisyCorruptionBurst.deathSphere(level, c.add(o[0], o[1], o[2]), sub, noise);
        }
    }

    // ------------------------------------------------------------------ 見せ方

    /** 仮想の群れの胞子の雲と、行進の音(クラスの説明)。 */
    private static void present(ServerLevel level, TdData.Event event, TdTable table) {
        if (event.virtual.isEmpty() || table.presentationRadius() <= 0.0D) {
            return;
        }
        double radiusSq = table.presentationRadius() * table.presentationRadius();
        RandomSource random = level.random;
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            TdData.VirtualSwarm nearest = null;
            double nearestSq = Double.MAX_VALUE;
            for (TdData.VirtualSwarm v : event.virtual) {
                double sq = player.distanceToSqr(v.x, v.y, v.z);
                if (sq > radiusSq) {
                    continue;
                }
                int count = Mth.clamp(v.size(), 4, 16);
                level.sendParticles(player, ModParticles.OVERGROWN_SPORE.get(), true, v.x, v.y + 1.5D, v.z, count,
                        2.5D, 1.0D, 2.5D, 0.01D);
                if (sq < nearestSq) {
                    nearestSq = sq;
                    nearest = v;
                }
            }
            if (nearest != null && random.nextFloat() < MARCH_SOUND_CHANCE) {
                // 群れの方向から聞こえるように、プレイヤーから群れへ向けて最大 12 ブロックの点で鳴らす(遠くの群れの位置で
                // 鳴らすと 16 ブロックで減衰して聞こえない)。低い葉擦れ。仮決め 2026-09-25。
                Vec3 eye = player.getEyePosition();
                Vec3 dir = new Vec3(nearest.x - eye.x, nearest.y - eye.y, nearest.z - eye.z);
                double len = dir.length();
                Vec3 at = len > 1.0E-3D ? eye.add(dir.scale(Math.min(12.0D, len) / len)) : eye;
                player.connection.send(new ClientboundSoundPacket(
                        BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.AZALEA_LEAVES_BREAK), SoundSource.HOSTILE,
                        at.x, at.y, at.z, 0.8F, 0.5F, random.nextLong()));
            }
        }
    }

    /** レイドの角笛をターゲットの方角から(バニラの {@code Raid#playSound} と同じ置き方)。 */
    private static void playHorn(ServerLevel level, BlockPos target) {
        Vec3 center = Vec3.atCenterOf(target);
        double range = DesignData.td().bossBarRadius();
        long seed = level.random.nextLong();
        for (ServerPlayer player : level.players()) {
            Vec3 p = player.position();
            double dx = center.x - p.x;
            double dz = center.z - p.z;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d > range) {
                continue;
            }
            double x = d > 1.0E-3D ? p.x + 13.0D / d * dx : p.x;
            double z = d > 1.0E-3D ? p.z + 13.0D / d * dz : p.z;
            player.connection.send(new ClientboundSoundPacket(SoundEvents.RAID_HORN, SoundSource.NEUTRAL,
                    x, player.getY(), z, 64.0F, 1.0F, seed));
        }
    }

    /**
     * ボスバー「森の侵攻 — 残り N バイオマス」({@link #remainingBiomass}。ユーザー指定 2026-09-26: それまでは「残り N 体」で、
     * N は予算(バイオマス)と頭数を足した単位の混ざった数だった)。長さは開始時の予算に対する割合。
     * ターゲットから {@code bossBarRadius} のプレイヤーに出す。
     */
    private static void updateBossBar(ServerLevel level, TdData data, TdData.Event event, TdTable table) {
        ServerBossEvent bar = data.bossEvent();
        double remaining = remainingBiomass(level, event);
        long shown = (long) Math.floor(remaining + 1.0E-6D);
        if (shown != data.shownRemaining()) {
            bar.setName(Component.translatable("event.overgrown_vegetation.td.boss", biomassText(remaining)));
            data.setShownRemaining(shown);
        }
        bar.setProgress(Mth.clamp((float) (remaining / Math.max(1.0D, event.initialTotal)), 0.0F, 1.0F));
        bar.setVisible(true);
        Vec3 center = Vec3.atCenterOf(event.target());
        double radiusSq = table.bossBarRadius() * table.bossBarRadius();
        List<ServerPlayer> inRange = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.distanceToSqr(center) <= radiusSq) {
                inRange.add(player);
                if (!bar.getPlayers().contains(player)) {
                    bar.addPlayer(player);
                }
            }
        }
        for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
            if (!inRange.contains(player)) {
                bar.removePlayer(player);
            }
        }
    }

    // ------------------------------------------------------------------ 攻め手の数

    /**
     * 読み込まれた攻め手を表に載せる。{@code EventMobs#onJoin}(ふつうの優先度。終わったイベントの印を外す)より後に動く。
     * 湧いた瞬間(印は addFreshEntity の前に付いている)もここを通る。
     */
    @SubscribeEvent(priority = EventPriority.LOW)
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)
                || !mob.isPersistenceRequired() || EventMobs.taggedAt(mob, EventMobs.Kind.TD) < 0L) {
            return;
        }
        TdData.Event active = TdData.get(level.getServer()).active();
        if (active == null || !active.dimension().equals(level.dimension())
                || !EventMobs.has(mob, EventMobs.Kind.TD, active.eventId())) {
            return;
        }
        forLevel(level).mobs.put(mob.getUUID(), mob);
        // 標的がいない間ターゲットへ向かう Goal を差し込む(Goal は保存されないので読み込みのたび)。護衛(群れに入らない G5〜G9)と、
        // 地上の群れの一員(群れにいる間は動かない。付いて来られずに群れから外れたら自分でターゲットへ向かう。2026-09-25)。
        SwarmRole role = SwarmRole.of(mob);
        if ((role == null || role.isGround()) && mob instanceof PathfinderMob pathfinder) {
            TdAssaultGoal.install(pathfinder);
        }
    }

    /**
     * 攻め手が離れた。倒された・消された(KILLED / DISCARDED)ときだけ生きている数を減らす。
     * 読み込み外れ(理由 null / UNLOADED_TO_CHUNK)・ディメンションの移動では減らさない(td-design)。
     * <b>どのディメンションで</b>倒されても減らす(レビュー指摘 2026-09-25: イベントのディメンションだけを見ていたので、
     * 別のディメンションへ移った攻め手が向こうで倒されても数が減らなかった。ポータルは {@code SwarmGoals#blocksPortal} で
     * 塞いであるが、{@code /tp}・ほかの MOD の移動は通る)。
     */
    @SubscribeEvent
    public static void onLeave(EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof Mob mob)) {
            return;
        }
        LevelMobs loaded = existing(level);
        boolean tracked = false;
        if (loaded != null) {
            tracked = loaded.mobs.remove(mob.getUUID(), mob);
            loaded.farSince.remove(mob.getUUID());
        }
        Entity.RemovalReason reason = mob.getRemovalReason();
        if (reason == null || !reason.shouldDestroy()) {
            return;
        }
        if (!tracked && (!mob.isPersistenceRequired() || EventMobs.taggedAt(mob, EventMobs.Kind.TD) < 0L)) {
            return;
        }
        TdData data = TdData.get(level.getServer());
        TdData.Event active = data.active();
        if (active == null) {
            return;
        }
        if (!tracked && !EventMobs.has(mob, EventMobs.Kind.TD, active.eventId())) {
            return;
        }
        active.alive = Math.max(0, active.alive - 1);
        // 値段も同じ個体の分だけ減らす(0 体になったら端数の誤差ごと 0 に)。
        active.aliveCost = active.alive > 0 ? Math.max(0.0D, active.aliveCost - MobStats.tdCost(mob.getType())) : 0.0D;
        if (reason == Entity.RemovalReason.KILLED) {
            active.killed++;
        }
        data.setDirty();
    }

    // ------------------------------------------------------------------ 群れの攻城を戻す

    /**
     * 組み直した・一員が登録された群れが侵攻中のイベントのもの(出どころ TD・eventId が同じ、または一員が印を持つ)なら、
     * 攻城(SIEGE、ターゲット)に戻す。もう同じターゲットを攻めていれば触らない(壁を壊している BREACH を崩さない)。
     */
    static void applySiege(Swarm swarm, @Nullable Mob member) {
        ServerLevel level = swarm.level();
        if (level == null) {
            return;
        }
        TdData.Event active = TdData.get(level.getServer()).active();
        if (active == null || !active.dimension().equals(level.dimension())
                || active.target().equals(swarm.targetBlock())) {
            return;
        }
        String id = active.eventId();
        boolean ours = swarm.origin() == SwarmOrigin.TD && id.equals(swarm.eventId());
        if (!ours) {
            if (member != null) {
                ours = EventMobs.has(member, EventMobs.Kind.TD, id);
            } else {
                for (Mob m : swarm.members()) {
                    if (EventMobs.has(m, EventMobs.Kind.TD, id)) {
                        ours = true;
                        break;
                    }
                }
            }
        }
        if (ours) {
            swarm.setSiege(active.target());
        }
    }

    // ------------------------------------------------------------------ 道具

    /** {@code center} から {@code radius} 以内に観戦者でないプレイヤーがいるか。 */
    static boolean hasPlayerWithin(ServerLevel level, Vec3 center, double radius) {
        double radiusSq = radius * radius;
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(center) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    private static boolean playerNear(ServerLevel level, double x, double y, double z, double radiusSq) {
        if (radiusSq <= 0.0D) {
            return false;
        }
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.distanceToSqr(x, y, z) <= radiusSq) {
                return true;
            }
        }
        return false;
    }

    /**
     * 読み込み済みのチャンクで立てる高さ = 木(過繁茂の葉・巨大樹の幹を含む)を掘り抜いた地面({@link TreeCanopyGroundFinder})。
     * 掘り抜けなければ高さ地図(葉を除く)。読み込まれていなければ {@code fallback}(チャンクを読み込まない)。
     *
     * <p>2026-09-25 ユーザー報告「森の木の葉の上に絵が出て、葉の上から mob が迫ってくるように見える」: はじめは高さ地図(葉を除く)
     * だけだった。過繁茂の葉はバニラの葉タグに入っていないので、過繁茂の森では梢の上が「地面」になり、遠景の身代わりが梢の上を歩いていた。
     */
    private static double groundY(ServerLevel level, double x, double z, double fallback) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        LevelChunk chunk = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        if (chunk == null) {
            return fallback;
        }
        BlockPos ground = TreeCanopyGroundFinder.findSpawnSurface(level, bx, bz);
        return ground != null ? ground.getY() : chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    @Nullable
    private static LevelMobs existing(ServerLevel level) {
        LevelMobs mobs = LOADED.get(level.dimension());
        return mobs != null && mobs.level == level ? mobs : null;
    }

    private static LevelMobs forLevel(ServerLevel level) {
        LevelMobs mobs = LOADED.get(level.dimension());
        if (mobs == null || mobs.level != level) {
            mobs = new LevelMobs(level);
            LOADED.put(level.dimension(), mobs);
        }
        return mobs;
    }

    /** 読み込まれている攻め手の数(表示・テスト用)。 */
    public static int loadedAttackers(ServerLevel level) {
        LevelMobs mobs = existing(level);
        return mobs != null ? mobs.mobs.size() : 0;
    }

    /** 読み込まれている攻め手の値段の合計(残党で生きている数を数え直すとき、値段もこれに合わせる)。 */
    private static double loadedCost(@Nullable LevelMobs loaded) {
        double total = 0.0D;
        if (loaded != null) {
            for (Mob mob : loaded.mobs.values()) {
                total += MobStats.tdCost(mob.getType());
            }
        }
        return total;
    }

    /** テスト用: プレイヤーがいなくても一時停止しない。サーバーが止まると戻る。 */
    public static void setIgnorePlayersForTests(boolean ignore) {
        ignorePlayersForTests = ignore;
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) {
            LevelMobs mobs = LOADED.get(level.dimension());
            if (mobs != null && mobs.level == level) {
                LOADED.remove(level.dimension());
            }
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        LOADED.clear();
        ignorePlayersForTests = false;
    }

    private TdTicker() {
    }
}

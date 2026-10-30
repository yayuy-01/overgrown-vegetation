package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.PhaseTable;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * 基地の胞子除去装置とフェーズ(ユーザー指定 2026-10-02):
 * <ul>
 *     <li>フェーズが 2 になるまでは、基地に胞子除去装置を置かない(除去塔・見張り台・壁の除去装置の場所は空けて建てる)。</li>
 *     <li>フェーズ 2 以上で新しく台帳に載る基地は、最初から除去装置を置く({@link #allowed})。</li>
 *     <li>フェーズ 2 より前に建った、除去装置の無い基地には、フェーズ 2 に入ってからゲーム内 1 日たったら、計画の場所へ
 *     除去装置だけを後から置く(最初の燃料・フィルター入り。工事中の基地は工事の後)。2026-10-04 から<b>読み込まれたチャンクだけ</b>に置き、
 *     読み込まれていないチャンクは読み込まれたときに置く({@link BaseChunkJobs}。ユーザー指定 10-02「後付けは読み込んだチャンクから」)。</li>
 *     <li>世界生成の基地は生成では除去装置を置かず、台帳に載ったときにフェーズ 2 以上なら、すぐ後付けを始める({@link #begin})。</li>
 * </ul>
 * 1 日は汚染の暦({@link PhaseData#clockNow}、寝た夜も 1 日に数える)で測る。フェーズが 2 より下に戻っても、置いた除去装置は外さない。
 * 除去装置を外すのは置き方だけで、配置計画は変えない(計画が変わると、広げるときの差分がずれる)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BasePurifiers {

    /** 除去装置を置き始めるフェーズ(ユーザー指定)。 */
    public static final int PHASE = 2;
    /** フェーズ 2 から後付けまでの時間(ゲーム内 1 日、ユーザー指定)。 */
    public static final long DELAY_TICKS = 24000L;

    /** 今、新しく台帳に載せる基地に除去装置を最初から置くか(フェーズ 2 以上)。 */
    public static boolean allowed(ServerLevel level) {
        return PhaseData.get(level).getPhase() >= PHASE;
    }

    /**
     * フェーズ 2 以上に入った暦の時刻(フェーズごとに最初に入った時刻のうち、2 以上で記録のあるいちばん早いもの —— フェーズを飛ばして
     * 上げると、飛ばしたフェーズの時刻は残らない)。無ければ −1。
     */
    public static long since(long[] reachedClock) {
        long best = -1L;
        for (int n = PHASE; n < reachedClock.length; n++) {
            long c = reachedClock[n];
            if (c >= 0L && (best < 0L || c < best)) {
                best = c;
            }
        }
        return best;
    }

    /** 後付けの時刻が来たか。 */
    public static boolean due(int phase, long since, long now) {
        return phase >= PHASE && since >= 0L && now - since >= DELAY_TICKS;
    }

    /** フェーズ 2 以上に入った暦の時刻(フェーズの台帳に無ければ、基地の台帳が最初に見た時刻)。フェーズ 2 より前なら −1。 */
    static long since(ServerLevel overworld, BaseSites data) {
        PhaseData pd = PhaseData.get(overworld);
        if (pd.getPhase() < PHASE) {
            return -1L;
        }
        long[] reached = new long[PhaseTable.MAX_PHASE + 1];
        for (int n = 0; n < reached.length; n++) {
            reached[n] = pd.phaseReachedClock(n);
        }
        long s = since(reached);
        return s >= 0L ? s : data.phase2Seen;
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        BaseSites data = BaseSites.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        if (server.getTickCount() % 20 == 0) {
            check(server, data);
        }
    }

    /** フェーズを見て、時刻が来たら除去装置の無い基地を後付けに回す(読み込まれたチャンクから。{@link BaseChunkJobs})。 */
    private static void check(MinecraftServer server, BaseSites data) {
        ServerLevel overworld = server.overworld();
        int phase = PhaseData.get(overworld).getPhase();
        if (phase < PHASE) {
            if (data.phase2Seen >= 0L) {
                data.phase2Seen = -1L;
                data.changed();
            }
            return;
        }
        long now = PhaseData.clockNow(overworld);
        if (since(overworld, data) < 0L) {
            data.phase2Seen = now;
            data.changed();
        }
        if (!due(phase, since(overworld, data), now)) {
            return;
        }
        for (BaseSite s : data.all()) {
            // 前の版の基地は今の計画と場所が合わないので置かない(建て直してもらう)。陥落した基地にも置かない
            if (!s.purifiers && !s.outdated() && !s.fallen) {
                begin(server, data, s);
            }
        }
    }

    /**
     * 除去装置の後付けを始める(読み込まれたチャンクから。読み込まれていないチャンクは読み込まれたときに)。世界生成の基地がフェーズ 2 以上で台帳に
     * 載ったときもここ(ユーザー指定「フェーズ 2 以上で新しく生まれる基地は最初から」—— 生成では置かず、載ったらすぐ置く)。
     */
    public static void begin(MinecraftServer server, BaseSites data, BaseSite site) {
        site.purifiers = true;
        BaseChunkJobs.start(server.getLevel(site.dimension), data, site, BaseChunkJobs.Job.PURIFY);
    }

    /** 除去装置の後付けをその場で全部済ませる(コマンドの {@code now} と GameTest。読み込まれていないチャンクも読み込んで回る)。 */
    public static void installNow(ServerLevel level, BaseSites data, BaseSite site) {
        site.purifiers = true;
        for (ChunkPos c : BaseConstruction.chunks(site, site.level())) {
            level.getChunk(c.x, c.z);
            BasePlacer.placePurifiers(level, site, site.level(), c);
        }
        BaseChunkJobs.cancel(data, site, BaseChunkJobs.Job.PURIFY);
        data.changed();
        announce(level, site);
    }

    /** 後付けが全部のチャンクで終わった({@link BaseChunkJobs})。世界生成の基地は知らせない(載ったばかりの、誰も頼んでいない基地)。 */
    static void finished(ServerLevel level, BaseSite site) {
        if (!site.generated) {
            announce(level, site);
        }
    }

    private static void announce(ServerLevel level, BaseSite site) {
        for (ServerPlayer p : level.players()) {
            if (p.blockPosition().distSqr(site.center) < 256 * 256) {
                p.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.purifiers_installed", site.id));
            }
        }
    }

    /** 状態の説明(開発用コマンド)。 */
    static String describe(ServerLevel overworld, BaseSites data, BaseSite site) {
        int phase = PhaseData.get(overworld).getPhase();
        long since = since(overworld, data);
        long now = PhaseData.clockNow(overworld);
        String state = site.installingPurifiers() ? "後付けの途中(残り " + site.purifyPendingChunks() + " / " + BaseConstruction.chunks(site, site.level()).size()
                + " チャンク。読み込まれたチャンクから置く・列に " + BaseChunkJobs.readyCount(site) + ")"
                : site.purifiers ? "あり" : "なし";
        String timer = phase < PHASE ? "フェーズ " + phase + "(フェーズ " + PHASE + " になるまで置かない)"
                : since < 0L ? "フェーズ " + phase
                : "フェーズ " + phase + "・フェーズ " + PHASE + " 以上になって " + (now - since) + " tick"
                + (site.purifiers ? "" : "(後付けまで残り " + Math.max(0L, DELAY_TICKS - (now - since)) + " tick)");
        return "基地 #" + site.id + ": 胞子除去装置 " + state + "・" + timer;
    }

    private BasePurifiers() {
    }
}

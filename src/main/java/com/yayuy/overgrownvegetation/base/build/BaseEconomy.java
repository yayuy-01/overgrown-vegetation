package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.ally.SoldierEntity;
import com.yayuy.overgrownvegetation.base.road.BaseRoads;
import com.yayuy.overgrownvegetation.block.GunTurretBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceBlockEntity;
import com.yayuy.overgrownvegetation.block.SporeDeviceKind;
import com.yayuy.overgrownvegetation.data.DefenseTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.spore.SporeDeviceData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.function.LongPredicate;
import java.util.function.Predicate;

/**
 * 同盟軍の基地の貯蓄と補給値・留守中の計算・仮の陥落(順 2 の着手順 1、2026-10-04)。仕様は {@code docs/ORDER2_SPEC.md}、数値は {@code defense.json} の {@code alliedBase}。
 *
 * <p><b>ユーザー指定(10-01)</b>: 貯蓄は兵士の補充などに使うポイントで上限は大体 1 万(レベルで変える)。補給値 = 1 日に貯蓄が回復する量で 1 日 1000〜3000、
 * 時間とともに増え上限は無い。道でつながった基地 1 つで +15%(足し算)、補給基地とのつながりは代わりに +60%。プレイヤーが離れている間の回復は
 * 戻ったときに日数からまとめて計算する。読み込まれていない基地は 1 日が終わるごとに周りの汚染で貯蓄が減り(周りに汚染が無ければ 0、完全に囲まれていれば
 * 2800〜4000、汚染の割合で変わる)、減る量が回復量を上回る日が 3 日続くと陥落。陥落 = 貯蓄の枯渇 + 壁の中の守備兵の全滅。
 * <b>ユーザー指定(10-04)</b>: 陥落は順 2 で仮の姿まで作る —— 守備兵が消えて設備が止まる廃墟。順 5 で G7 の前哨基地に置き換え、それまでは取り戻せない。
 *
 * <p>Claude 仮決め:
 * <ul>
 *     <li>精算は汚染の暦({@link PhaseData#clockNow}、眠った夜も 1 日)が 24000 進むごと。基地は少ないので、読み込まれているかに関係なく毎日全部を精算する
 *     (戻ったときにまとめて計算するのと同じ結果で、壊れにくい)。眠り・時刻の飛びで何日も進んだら、同じ「いる / いない」と割合でまとめて。</li>
 *     <li>「読み込まれている」= 日が変わったときに、同じ次元のプレイヤーが中心から(計画の外接の半分 + {@code presenceMargin})以内にいる。</li>
 *     <li>周りの汚染の割合は、汚染の台帳(極相化・縁からの深さ d と同じ物差し)で数える。ブロックを読まないので読み込まれていない基地でも数えられる。
 *     <b>形と幅はユーザー指定(10-04、測りの表を見て)</b>: 外周から 8 チャンクの輪で、胞子除去装置が守るチャンクを分母から外す
 *     (守られたチャンクは台帳が塗らないので、数えると完全に囲まれても 100% にならない)。</li>
 *     <li>つながりの割り増しは道の台帳({@link BaseRoads}、着手順 7、2026-10-08)が数える。直接つながった基地だけ(ユーザー指定 10-08)。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseEconomy {

    /** 暦の 1 日(tick)。 */
    public static final long DAY = 24000L;
    /** 日が変わったかの見回りの間隔(tick)。 */
    private static final int CHECK_INTERVAL = 20;
    /** 読み込まれていて貯蓄が 0 の基地の守備兵を数える間隔(tick)。 */
    private static final int GARRISON_CHECK_INTERVAL = 100;

    /** GameTest 用の差し替え: その基地にプレイヤーがいるか(null = 本物。GameTest の偽のプレイヤーはプレイヤーの一覧に入らない)。 */
    @Nullable
    public static Predicate<BaseSite> presenceForTests;
    /** GameTest 用の差し替え: 汚染の台帳(チャンクの {@link ChunkPos#toLong} → 過繁茂以上か。null = 本物。本物の台帳は書き換えない)。 */
    @Nullable
    public static LongPredicate ledgerForTests;
    /** GameTest 用の差し替え: 胞子除去装置が台帳から守っているチャンク(null = 本物)。 */
    @Nullable
    public static LongPredicate guardedForTests;

    /** 陥落した基地の兵士は、読み込まれたら次の tick に消す(読み込みの途中では消さない)。 */
    private static final List<SoldierEntity> PENDING_REMOVAL = new ArrayList<>();

    /** 陥落の理由(知らせに出す)。 */
    public enum Reason {
        /** 留守中に「減る量 > 回復量」の日が続いた。 */
        SURROUNDED("surrounded"),
        /** 貯蓄が 0 で、壁の中の守備兵が全滅した。 */
        GARRISON_LOST("garrison_lost"),
        /** 開発用のコマンド。 */
        COMMAND("command"),
        /**
         * 世界生成の基地が、台帳に載ったときにはもう汚染に飲まれていた(順 2 の着手順 2。ユーザー指定 10-04「耐えられなければ廃墟」:
         * 周りの汚染が、留守中の計算で落ちる量を超えていれば最初から陥落した廃墟)。
         */
        OVERRUN("overrun");

        final String key;

        Reason(String key) {
            this.key = key;
        }
    }

    // ---------- 数式(GameTest が直に確かめる) ----------

    /**
     * その日の回復量 = 始まりの補給値 ×(1 + 増え方 × 始めてからの日数)×(1 + つながりの割り増し)。
     * 増え方は「1 日ごとに始まりの値の 1% ずつ」(30 日で 1.3 倍・100 日で 2 倍、上限なし。Claude 仮決め)。
     */
    public static int recovery(int level, long daysSinceFounded, double connectionBonus) {
        DefenseTable t = DesignData.defense();
        double base = t.baseSupplyStart(level) * (1.0D + t.baseSupplyGrowthPerDay() * Math.max(0L, daysSinceFounded));
        return (int) Math.round(base * (1.0D + Math.max(0.0D, connectionBonus)));
    }

    /** その日の減る量 = 下限〜上限の乱数 × 周りの汚染の割合。プレイヤーがいれば 0。乱数は基地と日で決まる(同じ日を何度数えても同じ)。 */
    public static int decline(BaseSite site, long day, double ratio, boolean present) {
        if (present || ratio <= 0.0D) {
            return 0;
        }
        DefenseTable t = DesignData.defense();
        Random random = new Random(site.seed ^ (site.id * 0x9E3779B97F4A7C15L) ^ (day * 0xC2B2AE3D27D4EB4FL));
        int amount = t.baseDeclineMin() + random.nextInt(t.baseDeclineMax() - t.baseDeclineMin() + 1);
        return (int) Math.round(amount * Math.min(1.0D, ratio));
    }

    /** 回復と減りを貯蓄に当てる。差し引きで増えるなら上限まで(上限を超えている貯蓄はそのまま)、減るなら 0 まで。 */
    public static int apply(int stock, int cap, int recovery, int decline) {
        int net = recovery - decline;
        if (net >= 0) {
            return stock >= cap ? stock : Math.min(cap, stock + net);
        }
        return Math.max(0, stock + net);
    }

    /** つながりの割り増し(道でつながった基地 1 つで +15%、補給基地は +60%。足し算)。道の台帳({@link BaseRoads}、着手順 7)が数える。 */
    static double connectionBonus(MinecraftServer server, BaseSite site) {
        return BaseRoads.bonus(server, site);
    }

    /** 今日の回復量(管理端末の表示)。 */
    public static int todaysRecovery(MinecraftServer server, BaseSite site) {
        return recovery(site.level(), today(server) - site.foundedDay, connectionBonus(server, site));
    }

    // ---------- 周りの汚染の割合 ----------

    /** 周りの汚染の割合(0〜1)。設計値の形と幅で数える。台帳はオーバーワールドだけなので、ほかの次元の基地は 0。 */
    public static double surroundRatio(MinecraftServer server, BaseSite site) {
        DefenseTable t = DesignData.defense();
        return surroundRatio(server, site, t.baseSurroundShape(), t.baseSurroundChunks(), t.baseSurroundSkipGuarded());
    }

    /** 形と幅を決めて数える(コマンドの見比べ)。 */
    public static double surroundRatio(MinecraftServer server, BaseSite site, DefenseTable.SurroundShape shape, int chunks, boolean skipGuarded) {
        if (!site.dimension.equals(Level.OVERWORLD)) {
            return 0.0D;
        }
        ServerLevel overworld = server.overworld();
        LongPredicate overgrown = ledgerForTests != null ? ledgerForTests : PhaseData.get(overworld)::isOvergrownKey;
        LongPredicate guarded;
        if (!skipGuarded) {
            guarded = k -> false;
        } else if (guardedForTests != null) {
            guarded = guardedForTests;
        } else {
            long[] guards = SporeDeviceData.ledgerGuards(overworld);
            guarded = k -> SporeDeviceData.guards(guards, ChunkPos.getX(k), ChunkPos.getZ(k));
        }
        return surroundRatio(site, shape, chunks, overgrown, guarded);
    }

    /**
     * 周りの汚染の割合。{@code RING} = 基地の外接(計画の手を入れる範囲を覆うチャンク)からの距離が 1〜{@code chunks} のチャンク(角は丸い)、
     * {@code CIRCLE} = 中心のチャンクから半径 {@code chunks} の円のチャンク(基地のチャンクも入る)。{@code guarded} のチャンクは数えない。
     */
    public static double surroundRatio(BaseSite site, DefenseTable.SurroundShape shape, int chunks, LongPredicate overgrown, LongPredicate guarded) {
        int[] fp = footprintChunks(site);
        int total = 0;
        int hit = 0;
        long r2 = (long) chunks * chunks;
        if (shape == DefenseTable.SurroundShape.CIRCLE) {
            int ccx = site.center.getX() >> 4;
            int ccz = site.center.getZ() >> 4;
            for (int dx = -chunks; dx <= chunks; dx++) {
                for (int dz = -chunks; dz <= chunks; dz++) {
                    if ((long) dx * dx + (long) dz * dz > r2) {
                        continue;
                    }
                    long key = ChunkPos.asLong(ccx + dx, ccz + dz);
                    if (guarded.test(key)) {
                        continue;
                    }
                    total++;
                    if (overgrown.test(key)) {
                        hit++;
                    }
                }
            }
        } else {
            for (int cx = fp[0] - chunks; cx <= fp[2] + chunks; cx++) {
                for (int cz = fp[1] - chunks; cz <= fp[3] + chunks; cz++) {
                    long dx = cx < fp[0] ? fp[0] - cx : cx > fp[2] ? cx - fp[2] : 0;
                    long dz = cz < fp[1] ? fp[1] - cz : cz > fp[3] ? cz - fp[3] : 0;
                    long d2 = dx * dx + dz * dz;
                    if (d2 == 0 || d2 > r2) {
                        continue;
                    }
                    long key = ChunkPos.asLong(cx, cz);
                    if (guarded.test(key)) {
                        continue;
                    }
                    total++;
                    if (overgrown.test(key)) {
                        hit++;
                    }
                }
            }
        }
        return total == 0 ? 0.0D : (double) hit / total;
    }

    /** 基地の外接のチャンクの範囲 {cx0, cz0, cx1, cz1}(工事で置くチャンクと同じ)。 */
    public static int[] footprintChunks(BaseSite site) {
        List<ChunkPos> chunks = BaseConstruction.chunks(site, site.level());
        int[] fp = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (ChunkPos c : chunks) {
            fp[0] = Math.min(fp[0], c.x);
            fp[1] = Math.min(fp[1], c.z);
            fp[2] = Math.max(fp[2], c.x);
            fp[3] = Math.max(fp[3], c.z);
        }
        return fp;
    }

    // ---------- プレイヤーがいるか ----------

    /** 同じ次元のプレイヤー(観戦者を除く)が、中心から(計画の外接の半分 + {@code presenceMargin})以内にいるか。 */
    public static boolean present(MinecraftServer server, BaseSite site) {
        if (presenceForTests != null) {
            return presenceForTests.test(site);
        }
        ServerLevel level = server.getLevel(site.dimension);
        if (level == null) {
            return false;
        }
        double r = presenceRadius(site);
        double cx = site.center.getX() + 0.5D;
        double cz = site.center.getZ() + 0.5D;
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) {
                continue;
            }
            double dx = p.getX() - cx;
            double dz = p.getZ() - cz;
            if (dx * dx + dz * dz <= r * r) {
                return true;
            }
        }
        return false;
    }

    /** 「読み込まれている」の半径(ブロック)。 */
    public static double presenceRadius(BaseSite site) {
        int[] b = site.plan(site.level()).bounds();
        return Math.max(b[2] - b[0], b[3] - b[1]) / 2.0D + DesignData.defense().basePresenceMargin();
    }

    // ---------- 精算 ----------

    /** 今日(汚染の暦の日)。 */
    public static long today(MinecraftServer server) {
        return Math.floorDiv(PhaseData.clockNow(server.overworld()), DAY);
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (!PENDING_REMOVAL.isEmpty()) {
            for (SoldierEntity s : List.copyOf(PENDING_REMOVAL)) {
                if (!s.isRemoved()) {
                    s.discard();
                }
            }
            PENDING_REMOVAL.clear();
        }
        BaseSites data = BaseSites.get(server);
        if (data.all().isEmpty()) {
            return;
        }
        int tick = server.getTickCount();
        if (tick % CHECK_INTERVAL == 0) {
            long today = today(server);
            for (BaseSite site : List.copyOf(data.all())) {
                settleTo(server, data, site, today);
            }
        }
        if (tick % GARRISON_CHECK_INTERVAL == 0) {
            for (BaseSite site : List.copyOf(data.all())) {
                if (!site.fallen && site.stock == 0 && !site.building() && present(server, site)) {
                    checkGarrison(server, data, site);
                }
            }
        }
    }

    /** 貯蓄を始める(上限 × 最初の割合)。新しい基地と、この仕組みより前の台帳の基地。 */
    public static void start(BaseSites data, BaseSite site, long today) {
        DefenseTable t = DesignData.defense();
        site.stock = (int) Math.round(t.baseStockCap(site.level()) * t.baseStartStockFraction());
        site.foundedDay = today;
        site.lastDay = today;
        site.streak = 0;
        data.changed();
    }

    /** 貯蓄から払う(道の工事など、順 2 の着手順 7)。{@code floor} より下へは払わない。払った額を返す。 */
    public static int spend(BaseSites data, BaseSite site, int amount, int floor) {
        int paid = Math.max(0, Math.min(amount, site.stock - Math.max(0, floor)));
        if (paid > 0) {
            site.stock -= paid;
            data.changed();
        }
        return paid;
    }

    /** 暦の日 {@code today} まで精算する(飛んだ日はまとめて)。陥落した基地は日付だけ進める(回復しない)。 */
    public static void settleTo(MinecraftServer server, BaseSites data, BaseSite site, long today) {
        if (site.stock < 0) {
            start(data, site, today);
            return;
        }
        if (today <= site.lastDay) {
            return;
        }
        if (site.fallen) {
            site.lastDay = today;
            data.changed();
            return;
        }
        boolean present = present(server, site);
        double ratio = present ? 0.0D : surroundRatio(server, site);
        settleDays(server, data, site, today, present, ratio);
    }

    /** {@code site.lastDay + 1} 〜 {@code today} の日を、同じ「いる / いない」と周りの汚染の割合で精算する。 */
    public static void settleDays(MinecraftServer server, BaseSites data, BaseSite site, long today, boolean present, double ratio) {
        DefenseTable t = DesignData.defense();
        int cap = t.baseStockCap(site.level());
        double bonus = connectionBonus(server, site);
        for (long day = site.lastDay + 1; day <= today && !site.fallen; day++) {
            int rec = recovery(site.level(), day - site.foundedDay, bonus);
            int dec = decline(site, day, ratio, present);
            site.stock = apply(site.stock, cap, rec, dec);
            site.streak = !present && dec > rec ? site.streak + 1 : 0;
            site.lastRecovery = rec;
            site.lastDecline = dec;
            site.lastRatio = (float) ratio;
            site.lastPresent = present;
            if (site.streak >= t.baseFallStreakDays()) {
                fall(server, data, site, Reason.SURROUNDED);
            } else if (!present) {
                // 留守中の補充(順 2 の着手順 3、ユーザー指定 10-04「戻るまでに済ませておく」): その日の回復と減りの後に、減っていた分を補充しておく
                BaseGarrison.replenishAway(server, data, site);
            }
        }
        site.lastDay = Math.max(site.lastDay, today);
        data.changed();
    }

    /** 開発用: {@code days} 日ぶん、プレイヤーがいない(または {@code present})ものとして、今の周りの汚染で精算する。 */
    public static void settleAhead(MinecraftServer server, BaseSites data, BaseSite site, int days, boolean present) {
        if (site.stock < 0) {
            start(data, site, today(server));
        }
        long today = today(server);
        site.lastDay = today - days;
        site.foundedDay = Math.min(site.foundedDay, site.lastDay);
        settleDays(server, data, site, today, present, present ? 0.0D : surroundRatio(server, site));
    }

    /** 開発用: 貯蓄を {@code value} にする。 */
    public static void setStock(BaseSites data, BaseSite site, int value) {
        site.stock = Math.max(0, value);
        data.changed();
    }

    // ---------- 仮の陥落 ----------

    /**
     * 読み込まれていて貯蓄が 0 の基地: 守備兵が全滅していたら陥落(ユーザー指定 10-01「貯蓄の枯渇 + 守備兵の全滅」)。全滅は名簿で数える
     * ({@link BaseGarrison#wiped}。名簿の無い前の基地は、基地が全部読み込まれているときに壁の中の兵士を数える)。
     * 着手順 3 から補充が貯蓄を使うので、ふつうに遊んでいても起こりうる(補充で貯蓄が尽き、残りの兵士も倒れたとき)。
     */
    public static boolean checkGarrison(MinecraftServer server, BaseSites data, BaseSite site) {
        ServerLevel level = server.getLevel(site.dimension);
        if (level == null || site.fallen || !BaseGarrison.wiped(level, site)) {
            return false;
        }
        fall(server, data, site, Reason.GARRISON_LOST);
        return true;
    }

    /**
     * 仮の陥落(ユーザー指定 2026-10-04「守備兵が消えて設備が止まる廃墟」): 印を付け、工事・除去装置の後付けを止め、兵士を消し(読み込まれていない兵士は
     * 読み込まれた次の tick に)、機関砲台の弾と除去装置の燃料・フィルターを空にする後片付けを始める({@link BaseChunkJobs}。読み込まれたチャンクから。
     * 読み込まれていないチャンクは、読み込まれたときに)。ブロックは変えない(作り替えの見た目は順 5)。陥落はすべてのプレイヤーに知らせる。
     */
    public static void fall(MinecraftServer server, BaseSites data, BaseSite site, Reason reason) {
        fall(server, data, site, reason, true);
    }

    private static void fall(MinecraftServer server, BaseSites data, BaseSite site, Reason reason, boolean announce) {
        if (site.fallen) {
            return;
        }
        site.fallen = true;
        site.streak = 0;
        site.buildFrom = 0;
        site.buildTo = 0;
        site.cursor = 0;
        site.roster.clear(); // 兵士は消える(読み込まれていない兵士も、読み込まれたら消える)
        BaseChunkJobs.cancel(data, site, BaseChunkJobs.Job.PURIFY);
        ServerLevel level = server.getLevel(site.dimension);
        BaseChunkJobs.start(level, data, site, BaseChunkJobs.Job.EMPTY);
        if (level != null) {
            removeSoldiers(level, site);
        }
        if (announce) {
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                p.sendSystemMessage(Component.translatable("message.overgrown_vegetation.base.fallen", site.id,
                        Component.translatable("message.overgrown_vegetation.base.fallen_reason." + reason.key)));
            }
        }
    }

    /**
     * 世界生成の基地が、台帳に載ったときにはもう汚染に飲まれていたか(ユーザー指定 2026-10-04「耐えられなければ廃墟」)。
     * 留守中の計算と同じ線: 周りの汚染の割合 {@code ratio} で減る量の平均((下限 + 上限)/ 2 × 割合)が、生まれた日の回復量を超える
     * (新しい基地で L1 29% … L5 88%)。「最初からあった基地が、汚染に囲まれて落ちていた」の読み。
     */
    public static boolean overrun(BaseSite site, double ratio) {
        DefenseTable t = DesignData.defense();
        double meanDecline = (t.baseDeclineMin() + t.baseDeclineMax()) / 2.0D * ratio;
        return meanDecline > recovery(site.level(), 0L, 0.0D);
    }

    /**
     * 最初から陥落した廃墟として台帳に載せる(世界生成の基地、{@link Reason#OVERRUN})。陥落と同じ後片付け(設備を空にする)をするが、
     * 誰も知らない基地なので知らせは出さない。
     */
    public static void fallAtBirth(MinecraftServer server, BaseSites data, BaseSite site) {
        fall(server, data, site, Reason.OVERRUN, false);
    }

    /** 陥落と後片付けをその場で全部済ませる(コマンドと GameTest。後片付けは読み込まれていないチャンクも読み込んで回る)。 */
    public static void fallNow(MinecraftServer server, BaseSites data, BaseSite site, Reason reason) {
        fall(server, data, site, reason);
        ServerLevel level = server.getLevel(site.dimension);
        if (level == null) {
            return;
        }
        for (ChunkPos c : BaseConstruction.chunks(site, site.level())) {
            emptyDevices(level, site, level.getChunk(c.x, c.z)); // 読み込む(無ければ作る)
        }
        BaseChunkJobs.cancel(data, site, BaseChunkJobs.Job.EMPTY);
    }

    /** 開発用: 陥落を戻す(順 5 の奪還ができるまでのデバッグ用。空にした設備・消えた兵士は戻らない)。 */
    public static void unfall(BaseSites data, BaseSite site) {
        site.fallen = false;
        BaseChunkJobs.cancel(data, site, BaseChunkJobs.Job.EMPTY);
        site.streak = 0;
        data.changed();
    }

    /** チャンク {@code chunk} の、基地の中の機関砲台と胞子除去装置を空にする(= 止まる)。空にした数。 */
    static int emptyDevices(ServerLevel level, BaseSite site, LevelChunk chunk) {
        int n = 0;
        for (BlockEntity be : List.copyOf(chunk.getBlockEntities().values())) {
            BlockPos p = be.getBlockPos();
            if (!site.contains(p.getX(), p.getZ(), site.level())) {
                continue;
            }
            if (be instanceof GunTurretBlockEntity turret) {
                turret.clearContent();
                turret.setChanged();
                n++;
            } else if (be instanceof SporeDeviceBlockEntity device && device.kind() == SporeDeviceKind.PURIFIER) {
                device.clearContent();
                device.setChanged();
                n++;
            }
        }
        return n;
    }

    /** 読み込まれている、その基地の兵士を消す。消した数。 */
    static int removeSoldiers(ServerLevel level, BaseSite site) {
        List<SoldierEntity> found = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e instanceof SoldierEntity s && s.siteId() == site.id) {
                found.add(s);
            }
        }
        found.forEach(Entity::discard);
        return found.size();
    }

    /** 陥落した基地の兵士が読み込まれたら、次の tick に消す。 */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level) || !(event.getEntity() instanceof SoldierEntity s) || s.siteId() <= 0) {
            return;
        }
        BaseSite site = BaseSites.get(level.getServer()).byId(s.siteId());
        if (site != null && site.fallen) {
            PENDING_REMOVAL.add(s);
        }
    }

    // ---------- 表示 ----------

    /** 管理端末とコマンドの 1 行(貯蓄・今日の回復・前の日の内訳・補給が追いつかなかった日)。 */
    public static Component describe(MinecraftServer server, BaseSite site) {
        DefenseTable t = DesignData.defense();
        if (site.fallen) {
            return Component.translatable("message.overgrown_vegetation.base.is_fallen", site.id);
        }
        if (site.stock < 0) {
            return Component.translatable("message.overgrown_vegetation.base.economy_starting", site.id);
        }
        return Component.translatable("message.overgrown_vegetation.base.economy", site.stock, t.baseStockCap(site.level()),
                todaysRecovery(server, site), site.lastRecovery, site.lastDecline, Math.round(site.lastRatio * 100.0F),
                site.streak, t.baseFallStreakDays());
    }

    private BaseEconomy() {
    }
}

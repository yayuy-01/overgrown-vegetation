package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.WatcherTable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 司令塔の側の監視塔(フィロセンス)の口(2026-11-14。正本は Vault「Mob の能力/フィロセンス」の末尾とフェーズのノートの司令塔の節)。
 * 監視塔の本体(Mob・モデル・アニメ)は別のセッションが組み込む。司令塔はここに口だけを用意する。
 *
 * <h2>監視塔の側が使う順</h2>
 * <ol>
 *   <li>{@link #registerPlanter}: 「この場所に監視塔の芽を置く」を登録する(登録が 1 つでもあれば、司令塔が植え始め、認識の確定に監視塔の印が要るようになる)。</li>
 *   <li>{@link #onGrown}: 芽が育ち切ったら呼ぶ(約半日)。</li>
 *   <li>{@link #markSeen}: 監視塔がプレイヤーに視線の印を付けたら呼ぶ。そばの覚えた拠点の認識が確定でき、同時に半径 64 の植物側の Mob を
 *   1 分呼ぶ({@link PlantSideCall#towards})。</li>
 *   <li>{@link #onCut}: 刈られたら呼ぶ(育つ前・後どちらも)。認識が遅れる・1 段戻る。</li>
 * </ol>
 * 繁茂の森にまばらに自然に立つ監視塔は、司令塔の外(植えない・払わない)。それでも {@link #markSeen} は呼んでよい(そばに覚えた拠点があれば効く)。
 */
public final class Watchers {

    private Watchers() {
    }

    /** 監視塔の芽を置く口。置けたら true(司令塔が予算を払い、場所を拠点の記憶に持つ)。 */
    public interface Planter {
        boolean plant(ServerLevel level, BlockPos pos, BaseMemory.Base base);
    }

    private static final List<Planter> PLANTERS = new ArrayList<>();
    @Nullable
    private static Planter testPlanter;
    @Nullable
    private static Supplier<BlockPos> spotOverride;

    public static void registerPlanter(Planter p) {
        PLANTERS.add(p);
    }

    /** テスト・確かめ用: 植える口を差し替える(null で戻す)。 */
    public static void setPlanterForTests(@Nullable Planter p) {
        testPlanter = p;
    }

    /** テスト用: 植える所を決め打ちにする。 */
    public static void setSpotForTests(@Nullable Supplier<BlockPos> s) {
        spotOverride = s;
    }

    /** 監視塔が組み込まれているか(植える口が 1 つでもある)。このときだけ、認識の確定に監視塔の印が要る。 */
    public static boolean active() {
        return testPlanter != null || !PLANTERS.isEmpty();
    }

    // ---------------------------------------------------------------- 植える(見張り 20 秒ごと)

    static void tick(ServerLevel level, BaseMemory memory, double day) {
        if (!active() || !DirectorGates.open(level, DirectorGates.Gate.WATCHER)) return;
        // 植えるのも植物側の「湧き」: 大きな襲撃の前触れの間は止める。貯めている間は予算を使わないので止める
        if (DirectorGates.plantSpawnsSuppressed(level) || ForestDirector.hoarding()) return;
        for (BaseMemory.Base b : List.copyOf(memory.bases())) tryPlant(level, memory, b, day, level.getRandom());
    }

    /**
     * 1 つの拠点に 1 本植えてみる(本数・間・段の強さの確率・予算を見る)。
     *
     * @return 植えた場所(植えなければ null)
     */
    @Nullable
    public static BlockPos tryPlant(ServerLevel level, BaseMemory memory, BaseMemory.Base b, double day, RandomSource random) {
        WatcherTable t = DesignData.director().watcher;
        if (!active() || b.watchers.size() >= t.maxPerBase || day < b.nextPlantDay) return null;
        double strength = DirectorGates.strength(level, DirectorGates.Gate.WATCHER);
        if (!DirectorGates.roll(strength, random)) {
            b.nextPlantDay = day + t.plantIntervalDays * 0.25D;
            return null;
        }
        double price = ForestDirector.stagePrice(t.cost, strength);
        ForestDirector d = ForestDirector.get(level);
        if (d.available(level) + 1.0E-9D < price) return null;
        BlockPos at = spot(level, b, random);
        if (at == null) {
            b.nextPlantDay = day + 0.05D;
            return null;
        }
        boolean placed = false;
        if (testPlanter != null) {
            placed = testPlanter.plant(level, at, b);
        } else {
            for (Planter p : PLANTERS) {
                if (p.plant(level, at, b)) {
                    placed = true;
                    break;
                }
            }
        }
        if (!placed) return null;
        d.tryPay(level, price);
        b.watchers.add(new WatcherSite(at, day));
        b.nextPlantDay = day + t.plantIntervalDays;
        memory.setDirty();
        d.note(String.format(Locale.ROOT, "監視塔を植えた: 拠点 #%d の森の側 (%d, %d, %d)(%d 本目。予算 %.0f)", b.id(), at.getX(), at.getY(), at.getZ(),
                b.watchers.size(), price));
        return at;
    }

    /** 植える所: 拠点の真ん中から森の方角(森が見えなければ弱い方角・どこでも)へ 48〜96、プレイヤーから見通せない地面。 */
    @Nullable
    private static BlockPos spot(ServerLevel level, BaseMemory.Base b, RandomSource random) {
        if (spotOverride != null) return spotOverride.get();
        WatcherTable t = DesignData.director().watcher;
        Vec3 away = ForestEdge.of(level).away(new Vec3(b.centerX(), 64.0D, b.centerZ()));
        double angle = away != null ? Math.atan2(-away.z, -away.x)
                : b.weakSector() >= 0 ? BaseDefense.angleOf(b.weakSector(), DesignData.director().base.weakSectors)
                : random.nextDouble() * Math.PI * 2.0D;
        return RaidSpawns.findHiddenSpot(level, b.centerX(), b.centerZ(), angle, t.spreadDeg, t.minBlocks, t.maxBlocks, t.playerMinBlocks, t.tries, random);
    }

    // ---------------------------------------------------------------- 監視塔の側から

    /** 植えた監視塔(その場所にある)と、その拠点。無ければ null。 */
    @Nullable
    private static Object[] find(BaseMemory memory, BlockPos pos) {
        for (BaseMemory.Base b : memory.bases()) {
            for (WatcherSite w : b.watchers) {
                if (w.pos().distSqr(pos) <= 4.0D) return new Object[]{b, w};
            }
        }
        return null;
    }

    /** 芽が育ち切った。 */
    public static void onGrown(ServerLevel level, BlockPos pos) {
        BaseMemory memory = BaseMemory.get(level.getServer());
        Object[] f = find(memory, pos);
        if (f == null) return;
        ((WatcherSite) f[1]).setGrown();
        memory.setDirty();
    }

    /**
     * 刈られた(育つ前でも後でも)。育つ前なら認識を {@code cutYoungDelayDays} 遅らせ、育った後なら {@code cutGrownDelayDays} 遅らせ、
     * 認識済みなら 1 段戻す({@code cutGrownSetsBack})。拠点は忘れない(植え直しは {@code replantDays} 後から)。
     *
     * @return 司令塔が植えた監視塔だったか
     */
    public static boolean onCut(ServerLevel level, BlockPos pos) {
        BaseMemory memory = BaseMemory.get(level.getServer());
        Object[] f = find(memory, pos);
        if (f == null) return false;
        BaseMemory.Base b = (BaseMemory.Base) f[0];
        WatcherSite w = (WatcherSite) f[1];
        WatcherTable t = DesignData.director().watcher;
        double day = BaseMemory.today(level.getServer());
        b.watchers.remove(w);
        b.nextPlantDay = Math.max(b.nextPlantDay, day + t.replantDays);
        if (w.grown()) {
            b.watchersCutGrown++;
            if (t.cutGrownSetsBack && b.stage() == BaseMemory.Stage.RECOGNIZED) memory.setBack(b.id(), day, false);
            memory.delayRecognition(b.id(), t.cutGrownDelayDays);
        } else {
            b.watchersCutYoung++;
            memory.delayRecognition(b.id(), t.cutYoungDelayDays);
        }
        memory.setDirty();
        ForestDirector.get(level).note(String.format(Locale.ROOT, "監視塔が刈られた: 拠点 #%d(%s)", b.id(), w.grown() ? "育った後" : "育つ前"));
        return true;
    }

    /**
     * 監視塔がプレイヤーに視線の印を付けた。そばの覚えた拠点(植えた拠点、無ければ真ん中まで maxBlocks × 1.5 の中のいちばん近い拠点)の
     * 「監視塔に見られた日」を今日にし(認識の確定の条件)、同時に、半径 {@code callRadius} の植物側の Mob を {@code callTicks} の間、相手へ向かわせる。
     *
     * @return 呼び寄せた数
     */
    public static int markSeen(ServerLevel level, BlockPos watcherPos, LivingEntity target) {
        BaseMemory memory = BaseMemory.get(level.getServer());
        WatcherTable t = DesignData.director().watcher;
        Object[] f = find(memory, watcherPos);
        BaseMemory.Base b = f != null ? (BaseMemory.Base) f[0]
                : memory.nearest(watcherPos.getX() + 0.5D, watcherPos.getZ() + 0.5D, t.maxBlocks * 1.5D);
        // 11-18 夜: 監視塔は F4 から植わる(フィロセンスの出るフェーズ F4)が、認識は F5 から(gates.recognize)。
        // F4 の印を覚えておくと F5 に上がった瞬間に認識してしまうので、認識が開くまでは印を数えない(呼び寄せは F4 から効く)
        if (b != null && DirectorGates.open(level, DirectorGates.Gate.RECOGNIZE)) {
            b.watcherSeenDay = BaseMemory.today(level.getServer());
            memory.setDirty();
            // 偵察済みなら、その場で認識する(W1 = 2)。「覚えた」だけの拠点は、印を覚えておくが認識しない(偵察は感染体、確定は監視塔 = 偵察が済んでいることが要る)。
            // 印の後に偵察が済んでも、印は偵察済みになった日より前なので効かない(もう一度見られる必要がある)
            memory.update(BaseMemory.today(level.getServer()), DirectorGates.open(level, DirectorGates.Gate.RECOGNIZE));
        }
        return PlantSideCall.towards(level, Vec3.atCenterOf(watcherPos), target, t.callRadius, t.callTicks);
    }

    /** 監視塔に見られたか: 偵察済みになってから監視塔の印が付いた(組み込まれていなければ常に true)。11-14 ユーザー W1 = 2: これだけで認識する。 */
    static boolean confirms(BaseMemory.Base b) {
        return !active() || b.watcherSeenDay >= b.stageSince();
    }
}

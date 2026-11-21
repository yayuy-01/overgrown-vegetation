package com.yayuy.overgrownvegetation.horde;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PerfTable;
import com.yayuy.overgrownvegetation.entity.crawler.CrawlerPack;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.spawn.DirectorHooks;
import com.yayuy.overgrownvegetation.spawn.EnemySpawns;
import com.yayuy.overgrownvegetation.spawn.PlantMarch;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>仮想の群れ</b>(2026-11-18 大群の軽量化 A。ユーザー決定: 遠くから来る群れは仮想で進め、読み込み範囲に入ったら本物に・0.2.0 に入れる。
 * 正本は共有ノート virtual-swarm-draft.md と Vault「TPS をできる限り軽くする仕組み」)。
 *
 * <p>群れ 1 つ = 位置・向き・速さ・行き先・種ごとの頭数(1 体ずつは持たない)。エンティティではないので、ぶつかり・行動・送り・描画の重さが無い。
 * parked の TD の仮想の群れ({@code td/TdData.VirtualSwarm}・{@code TdTicker})の群れの部分を土台にした。
 *
 * <h2>本物にする・仮想へ戻す(11-18 全体管理経由でユーザー了承の合わせ方)</h2>
 * <ul>
 *     <li>読み込み範囲の外(群れの真ん中のチャンクが読み込み終わっていない・エンティティが動かない所)は必ず仮想。</li>
 *     <li>内側では、どれかのプレイヤー(観戦を除く)から水平 {@code materializeBlocks}(64)で本物に。1 回に {@code spawnPerRun} 体まで・
 *     本物の総数(印 {@link #TAG} の読み込まれた個体)は {@code realCap}(300)まで。出し切れない分は同じ群れに仮想のまま残り、待つ(進まない)。</li>
 *     <li>本物の群れの一員は、全プレイヤーから水平 {@code dematerializeBlocks}(128)より遠く、行進中・無傷・標的なし・叩かれていない・名札/乗り物なし・
 *     行き先から 48 ブロックより外なら、仮想へ戻す(死なせずに {@code discard}。kill だと死の破裂で汚染を撒く)。</li>
 * </ul>
 * 頭数は作る・倒される・寿命で消える以外で変わらない(本物化・戻しで数が合う。{@link #total})。
 *
 * <h2>動き</h2>
 * {@code moveIntervalTicks} ごとに行き先へまっすぐ進み、読み込み済みの所では高さ地図で高さを合わせる(チャンクは読み込まない)。
 * 本物にしようとして 1 体も置けなければ、行き先から見て横へ 8 ブロックずらしてやり直す(TD の横歩きの簡単な形)。
 * 行き先に着いて誰も来ないまま {@code lifeAfterArriveTicks} 経てば消える【仮】。
 * 流れの地図を下らせるのは後(ユーザー決定: まっすぐ + 横へ避けるから始める)。
 *
 * <p>入口は今のところ確かめの命令 {@code /ov perf virtual} と {@link #spawn} だけ。司令塔の襲撃へのつなぎは全体管理と相談してから(既定では呼ばれない)。
 * 空の種・G1 は入れない(地上の行進できる G2・G3 = {@link DirectorHooks#marchable} だけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class VirtualHordes extends SavedData {

    public static final String ID = OvergrownVegetation.MODID + "_virtual_hordes";
    /** 仮想の群れから本物になった個体の印(戻すとき・本物の総数を数えるとき)。 */
    public static final String TAG = "ov_virtual_horde";
    /** 本物にしたとき付けた印(戻すとき群れへ持ち帰る)を覚える個体の保存データの鍵。 */
    static final String EXTRA_KEY = "ov_virtual_horde_tag";
    /** 大規模レイドの群れから本物になった個体が、どの本番のものか(本番の始まった日)を覚える鍵(11-18 見直し ③1)。 */
    static final String RAID_KEY = "ov_virtual_horde_raid";
    /** テスト用: 今の大規模レイドの本番の世代(NaN = 本番でない。null = 司令塔から)。 */
    @Nullable
    public static Double raidForTests;

    /** 今の大規模レイドの本番の世代(始まった日)。本番でない・的の拠点が無いなら NaN。 */
    static double liveRaid(ServerLevel level) {
        if (raidForTests != null) return raidForTests;
        com.yayuy.overgrownvegetation.director.ForestDirector d = com.yayuy.overgrownvegetation.director.ForestDirector.get(level);
        if (d.mood() != com.yayuy.overgrownvegetation.director.ForestDirector.Mood.LARGE
                || com.yayuy.overgrownvegetation.director.LargeRaids.target(level) == null) return Double.NaN;
        return d.moodSince();
    }

    /** テスト用: 仮想の群れから本物になった大規模レイドの攻め手の印を付け、行進させる(本番の世代 {@code raid})。 */
    public static void markRaidAttackerForTests(Mob m, double raid, int baseId, double tx, double tz) {
        String tag = com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG;
        m.addTag(TAG);
        m.addTag(tag);
        m.getPersistentData().putString(EXTRA_KEY, tag);
        m.getPersistentData().putDouble(RAID_KEY, raid);
        beginMarch(m, baseId, tx, tz);
    }

    /** 大規模レイドの群れの本物の個体が、今の本番のものか(印の無い古い個体は今の本番があれば今のものと見る)。 */
    static boolean ofLiveRaid(Mob m, double live) {
        if (Double.isNaN(live)) return false;
        return !m.getPersistentData().contains(RAID_KEY) || m.getPersistentData().getDouble(RAID_KEY) == live;
    }
    /** 行き先から この距離の中では戻さない(拠点の壁を壊す範囲 = BreachGoal.BASE_RANGE と同じ)。 */
    static final double KEEP_NEAR_TARGET = 48.0D;
    /** 戻すとき、この距離の中にある同じ行き先の群れへ足す。 */
    static final double MERGE_BLOCKS = 24.0D;
    /** 着いたとみなす行き先からの距離。 */
    static final double ARRIVE_BLOCKS = 8.0D;
    /** 置けなかったとき横へずらす距離。 */
    static final double NUDGE_BLOCKS = 8.0D;
    /** 群れ 1 つの頭数の上限(壊れたデータ・命令で巨大にしない)。 */
    public static final int MAX_SWARM = 512;

    public static final SavedData.Factory<VirtualHordes> FACTORY = new SavedData.Factory<>(VirtualHordes::new, (tag, registries) -> load(tag), null);

    /** 確かめ用の数(保存しない)。 */
    /** テスト用: プレイヤーの代わりの位置 {x, z}(GameTest には本物のプレイヤーがいない。null で本物)。 */
    public static java.util.List<double[]> playersForTests;
    /** テスト用: 本物の総数の上限の上書き(null で設計値)。 */
    public static Integer realCapForTests;
    /** テストで世界の tick から動かす(ふだんの GameTest のサーバーでは動かさない: テストの Mob を勝手に戻さない)。 */
    public static boolean tickInGameTests;

    public static long materialized, dematerialized, placeFailures, expired, retreated, moveNanos, moveRuns, materializeNanos, maxMaterializeNanos, runs;

    /** 群れ 1 つ。 */
    public static final class Swarm {
        final long id;
        double x, y, z;
        /** 向き(行き先への単位ベクトル。送り・隊列用)。 */
        float dirX = 1.0F, dirZ;
        double speed;
        /** 行き先(拠点の id。無ければ -1)と座標。司令塔が {@link #redirect} で差し替えられる。 */
        int baseId;
        double tx, tz;
        /** 出発点(引き返すとき戻る所)。 */
        double originX, originZ;
        /** 引き返している(襲撃が終わった)。本物にせず、出発点に着いたら消える。 */
        boolean retreating;
        /** 本物にしたとき付ける印(襲撃の印など。空なら無し)。 */
        final String extraTag;
        /** 種(エンティティの id)ごとの頭数。 */
        final LinkedHashMap<ResourceLocation, Integer> counts = new LinkedHashMap<>();
        long arrivedAt = -1L;
        /** 本物にしようとして置けなかった回数(保存しない)。 */
        int failures;
        /** 上限で待っているか(この回は進まない。保存しない)。 */
        boolean waiting;
        /** 次に本物にする一員の並びの番号(本物にした分だけ進む。詰めないので、残りの身代わりの位置が動かない)。 */
        int firstIndex;
        // ---- 11-21 前線と部隊: 群れ = 部隊。前線の無い古い群れは front −1・目的 MARCH
        /** 属する前線の id(−1 = 前線なし)。 */
        int front = -1;
        /** 目的。 */
        UnitPurpose purpose = UnitPurpose.MARCH;
        /** 払った額の合計(作ったとき・補充したとき足す)。 */
        double paid;
        /** 狩りの的のプレイヤー(狩る部隊だけ。無ければ null)。 */
        @Nullable
        java.util.UUID hunt;
        /** 今の目的になったゲーム時刻。 */
        long purposeSince;

        Swarm(long id, double x, double y, double z, double speed, int baseId, double tx, double tz, String extraTag) {
            this.id = id;
            this.x = x;
            this.y = y;
            this.z = z;
            this.speed = speed;
            this.baseId = baseId;
            this.tx = tx;
            this.tz = tz;
            this.extraTag = extraTag;
            this.originX = x;
            this.originZ = z;
            faceTarget();
        }

        public int size() {
            int n = 0;
            for (int v : counts.values()) n += v;
            return n;
        }

        void add(ResourceLocation type, int n) {
            counts.merge(type, n, Integer::sum);
        }

        void faceTarget() {
            double dx = tx - x, dz = tz - z, d = Math.sqrt(dx * dx + dz * dz);
            if (d > 1.0E-3D) {
                dirX = (float) (dx / d);
                dirZ = (float) (dz / d);
            }
        }

        public long id() {
            return id;
        }

        public double x() {
            return x;
        }

        public double y() {
            return y;
        }

        public double z() {
            return z;
        }

        public float dirX() {
            return dirX;
        }

        public float dirZ() {
            return dirZ;
        }

        public int firstIndex() {
            return firstIndex;
        }

        public double speed() {
            return waiting || arrivedAt >= 0L ? 0.0D : speed;
        }

        public Map<ResourceLocation, Integer> counts() {
            return counts;
        }

        public int front() {
            return front;
        }

        public UnitPurpose purpose() {
            return purpose;
        }

        public double paid() {
            return paid;
        }

        @Nullable
        public java.util.UUID hunt() {
            return hunt;
        }

        public long purposeSince() {
            return purposeSince;
        }

        public double tx() {
            return tx;
        }

        public double tz() {
            return tz;
        }

        public int baseId() {
            return baseId;
        }

        public boolean arrived() {
            return arrivedAt >= 0L;
        }

        public boolean retreating() {
            return retreating;
        }

        /** 進めずに待っている(囲まれた・本物の上限)。島の拠点へ流れ木もどきで運ぶきっかけ。 */
        public boolean blocked() {
            return waiting;
        }

        /** 前線の部隊か。 */
        public boolean unit() {
            return front >= 0;
        }

        /** 頭数を足す(払った額も)。上限 {@link #MAX_SWARM} を超える分は足さない。足した数を返す。 */
        public int addHeads(ResourceLocation type, int n, double paidEach) {
            int k = Math.max(0, Math.min(n, MAX_SWARM - size()));
            if (k <= 0) return 0;
            add(type, k);
            paid += paidEach * k;
            return k;
        }

        /** 種ごとに頭数を抜く(分かれる・使う)。抜いた分の払った額も比で移す。抜いた中身を返す。 */
        public LinkedHashMap<ResourceLocation, Integer> takeHeads(int n) {
            LinkedHashMap<ResourceLocation, Integer> out = new LinkedHashMap<>();
            int total = size();
            if (n <= 0 || total <= 0) return out;
            int want = Math.min(n, total);
            int left = want;
            Iterator<Map.Entry<ResourceLocation, Integer>> it = counts.entrySet().iterator();
            while (it.hasNext() && left > 0) {
                Map.Entry<ResourceLocation, Integer> e = it.next();
                int k = Math.min(left, e.getValue());
                e.setValue(e.getValue() - k);
                out.merge(e.getKey(), k, Integer::sum);
                left -= k;
                if (e.getValue() <= 0) it.remove();
            }
            paid -= paid * (want - left) / (double) total;
            return out;
        }

        /** 種 type を n 体抜く(進化で入れ替えるとき)。払った額も比で抜く。抜いた数を返す。 */
        public int takeHeadsOf(ResourceLocation type, int n) {
            int have = counts.getOrDefault(type, 0);
            int k = Math.min(have, Math.max(0, n));
            if (k <= 0) return 0;
            int total = size();
            if (have - k <= 0) counts.remove(type);
            else counts.put(type, have - k);
            paid -= paid * k / (double) total;
            return k;
        }

        /** 目的と行き先を変える(着いた印・引き返しを外す)。 */
        public void assign(UnitPurpose p, double tx, double tz, long now) {
            if (p != purpose) purposeSince = now;
            purpose = p;
            this.tx = tx;
            this.tz = tz;
            arrivedAt = -1L;
            retreating = false;
            faceTarget();
        }

        /** 払った額を足す(合わさったとき)。 */
        public void paidAdd(double v) {
            paid = Math.max(0.0D, paid + v);
        }

        public void setFront(int front) {
            this.front = front;
        }

        public void setHunt(@Nullable java.util.UUID hunt) {
            this.hunt = hunt;
        }

        public void setBase(int baseId) {
            this.baseId = baseId;
        }
    }

    private final List<Swarm> swarms = new ArrayList<>();
    private long nextId = 1L;

    public static VirtualHordes get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, ID);
    }

    public List<Swarm> swarms() {
        return swarms;
    }

    public static boolean enabled() {
        // MOD の設定「遠くの群れを使うか」(11-19。既定は COMMON の virtualHordeEnabled)
        return com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.onCurrent(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.VIRTUAL_HORDES);
    }

    /** 仮想にできる種か: 地上の行進できる G2・G3({@link DirectorHooks#marchable})か、G1 の段階 0(11-18 ユーザー: 後で司令塔が G1 の群れで前線を広げるため)。 */
    public static boolean allowed(EntityType<?> type) {
        if (DirectorHooks.marchable(type)) return true;
        for (com.yayuy.overgrownvegetation.corpse.G1Species s : com.yayuy.overgrownvegetation.corpse.G1Species.values()) {
            if (s.entityType() == type) return true;
        }
        return false;
    }

    /** 仮想の頭数をあと何体足せるか(仮想の上限 {@code virtualCap})。 */
    public int room() {
        return Math.max(0, (int) Math.round(com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.ofCurrent(com.yayuy.overgrownvegetation.difficulty.DifficultySetting.VIRTUAL_CAP)) - virtualHeads()); // 11-19 MOD の設定(既定は perf.json の virtualCap)
    }

    /**
     * 仮想の群れを作る(呼ぶ側が予算を払う)。{@link #allowed} の種だけを、仮想の上限まで数える(ほかは捨てる。作った頭数は返した群れの size)。
     *
     * @return 作った群れ(頭数 0 なら null)
     */
    @Nullable
    public Swarm spawn(double x, double y, double z, int baseId, double tx, double tz, String extraTag, Map<EntityType<?>, Integer> heads) {
        PerfTable t = DesignData.perf();
        Swarm s = new Swarm(nextId++, x, y, z, t.virtualSpeed, baseId, tx, tz, extraTag == null ? "" : extraTag);
        int n = 0, cap = Math.min(MAX_SWARM, room());
        for (Map.Entry<EntityType<?>, Integer> e : heads.entrySet()) {
            if (!allowed(e.getKey()) || e.getValue() <= 0) continue;
            int k = Math.min(e.getValue(), cap - n);
            if (k <= 0) break;
            s.add(BuiltInRegistries.ENTITY_TYPE.getKey(e.getKey()), k);
            n += k;
        }
        if (n == 0) return null;
        swarms.add(s);
        setDirty();
        return s;
    }

    /** 部隊から実物になった個体が、どの部隊(群れの id)のものかを覚える鍵(11-21 前線と部隊)。 */
    public static final String UNIT_KEY = "ov_unit";
    /** 部隊から実物になった個体の前線の id。 */
    public static final String FRONT_KEY = "ov_unit_front";
    /** 部隊から実物になった個体の目的。 */
    static final String PURPOSE_KEY = "ov_unit_purpose";

    /**
     * 前線の部隊を作る(11-21)。頭数 0 から始め、呼ぶ側が {@link Swarm#addHeads} で足す(0 のまま次に進めると消える)。
     * 仮想の上限 {@code virtualCap} は見ない: 部隊の数は維持費の 8 割の関所で決まる。
     */
    public Swarm newUnit(double x, double y, double z, int front, UnitPurpose purpose, long now) {
        Swarm s = new Swarm(nextId++, x, y, z, DesignData.front().unitSpeed, -1, x, z, "");
        s.front = front;
        s.purpose = purpose;
        s.purposeSince = now;
        s.arrivedAt = now; // 作った所 = 持ち場
        swarms.add(s);
        setDirty();
        return s;
    }

    /**
     * 部隊の 1 体を、足元 feet に実物として出す(流れ木もどきに乗せるときなど)。頭数を 1 減らし、部隊の印を付ける。行進はさせない(呼ぶ側が決める)。
     * 置けなければ null(頭数は減らさない)。
     */
    @Nullable
    public Mob materializeOne(ServerLevel level, Swarm s, BlockPos feet, RandomSource random) {
        for (Map.Entry<ResourceLocation, Integer> e : s.counts.entrySet()) {
            if (e.getValue() <= 0) continue;
            Mob m = EnemySpawns.create(level, BuiltInRegistries.ENTITY_TYPE.get(e.getKey()), feet, MobSpawnType.EVENT, random);
            if (m == null) return null;
            m.addTag(TAG);
            m.getPersistentData().putString(EXTRA_KEY, s.extraTag);
            m.getPersistentData().putLong(UNIT_KEY, s.id);
            m.getPersistentData().putInt(FRONT_KEY, s.front);
            m.getPersistentData().putString(PURPOSE_KEY, s.purpose.name());
            int before = s.size();
            if (before > 0) com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(m, s.paid / before);
            s.paid -= before > 0 ? s.paid / before : 0.0D;
            e.setValue(e.getValue() - 1);
            s.counts.values().removeIf(v -> v <= 0);
            setDirty();
            return m;
        }
        return null;
    }

    /** 部隊に入っていない実物を部隊 s へ頭数として吸い込む(死なせずに消す)。吸い込めたら true。 */
    public boolean absorb(ServerLevel level, Swarm s, Mob m) {
        if (s.size() >= MAX_SWARM) return false;
        s.add(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()), 1);
        s.paid += com.yayuy.overgrownvegetation.director.ForestEconomy.paid(m);
        m.getPersistentData().remove(com.yayuy.overgrownvegetation.director.ForestEconomy.PAID_KEY); // 消えたときの払い戻しを二重にしない
        if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
        m.discard();
        setDirty();
        return true;
    }

    /** 部隊を消す(使い切った・合わさった)。 */
    public void removeUnit(Swarm s) {
        if (swarms.remove(s)) setDirty();
    }

    /** 変えたことを保存に知らせる(前線の側から)。 */
    public void touch() {
        setDirty();
    }

    /** id の群れ(無ければ null)。 */
    @Nullable
    public Swarm byId(long id) {
        for (Swarm s : swarms) if (s.id == id) return s;
        return null;
    }

    /**
     * 群れの行き先を差し替える(司令塔が「この群れをここへ」と命じる口。11-18 ユーザー: 後で仮想の G1 の群れを動かして前線を広げる)。
     * 着いた・待っている印は外し、引き返しもやめる。群れが無ければ false。
     */
    public boolean redirect(long id, int baseId, double tx, double tz) {
        Swarm s = byId(id);
        if (s == null) return false;
        s.baseId = baseId;
        s.tx = tx;
        s.tz = tz;
        s.arrivedAt = -1L;
        s.retreating = false;
        s.faceTarget();
        setDirty();
        return true;
    }

    /**
     * 印 {@code extraTag} の群れを出発点へ引き返させる(襲撃が終わった。11-18 ユーザー決定: 森の方へ引き返して消える)。
     * 引き返している群れは本物にならず、出発点に着いたら消える。引き返させた群れの数を返す。
     */
    public static int retreat(ServerLevel level, String extraTag) {
        return get(level.getServer()).retreatTag(extraTag);
    }

    /** {@link #retreat} の中身(テストからも)。 */
    public int retreatTag(String extraTag) {
        VirtualHordes h = this;
        int n = 0;
        for (Swarm s : h.swarms) {
            if (!s.extraTag.equals(extraTag) || s.retreating) continue;
            s.retreating = true;
            s.tx = s.originX;
            s.tz = s.originZ;
            s.arrivedAt = -1L;
            s.faceTarget();
            n++;
        }
        if (n > 0) h.setDirty();
        return n;
    }

    /** 本物にしたとき行進させる(G2・G3 は拠点への行進 {@link PlantMarch}、G1 は G1 の行進 {@code G1March#beginTo})。 */
    static void beginMarch(Mob m, int baseId, double tx, double tz) {
        if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster g1) g1.march().beginTo(tx, tz, baseId);
        else PlantMarch.begin(m, baseId, tx, tz);
    }

    /** 前線の側から: 実物を (tx, tz) へ行進させ直す(狩りの的が動いたとき・襲撃の集まる所から拠点へ)。 */
    public static void marchTo(Mob m, int baseId, double tx, double tz) {
        beginMarch(m, baseId, tx, tz);
    }

    /** 行進しているか(戻してよい条件の 1 つ)。 */
    static boolean marching(Mob m) {
        if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster g1) return g1.march().active();
        return PlantMarch.active(m);
    }

    /** 行進の行き先(無ければ null)。 */
    @Nullable
    static net.minecraft.world.phys.Vec3 marchTarget(Mob m) {
        if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster g1) return g1.march().active() ? g1.march().target(m.getY()) : null;
        return PlantMarch.target(m);
    }

    static int marchBase(Mob m) {
        if (m instanceof com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster g1) return g1.march().baseId();
        return PlantMarch.baseId(m);
    }

    /** 仮想の頭数の合計。 */
    public int virtualHeads() {
        int n = 0;
        for (Swarm s : swarms) n += s.size();
        return n;
    }

    /** 本物になっている個体(印つき・読み込まれて生きている)の数。 */
    public static int realHeads(ServerLevel level) {
        int n = 0;
        for (Entity e : level.getAllEntities()) {
            if (e instanceof Mob m && m.isAlive() && m.getTags().contains(TAG)) n++;
        }
        return n;
    }

    /** 仮想 + 本物の頭数(往復で変わらないこと)。 */
    public int total(ServerLevel level) {
        return virtualHeads() + realHeads(level);
    }

    // ---------------------------------------------------------------- tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!enabled()) return;
        MinecraftServer server = event.getServer();
        if (!tickInGameTests && server instanceof net.minecraft.gametest.framework.GameTestServer) return;
        ServerLevel level = server.overworld();
        VirtualHordes h = get(server);
        PerfTable t = DesignData.perf();
        int tick = server.getTickCount();
        if (!h.swarms.isEmpty() && tick % t.virtualMoveIntervalTicks == 0) {
            // 大規模レイドが終わった・的の拠点を忘れたのに引き返していない群れ(終わる前の保存から読んだ・取りこぼし)は引き返させる
            // (11-18 見直し ③1。拠点が無いと本物にする枠が 0 で、64 ブロックの外でずっと待っていた)
            if (Double.isNaN(liveRaid(level))) h.retreatTag(com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG);
            long t0 = System.nanoTime();
            h.move(level, t.virtualMoveIntervalTicks, t);
            moveNanos += System.nanoTime() - t0;
            moveRuns++;
        }
        if (!h.swarms.isEmpty() && tick % t.virtualMaterializeIntervalTicks == 0) {
            long t0 = System.nanoTime();
            h.materialize(level, t);
            long dt = System.nanoTime() - t0;
            materializeNanos += dt;
            maxMaterializeNanos = Math.max(maxMaterializeNanos, dt);
            runs++;
        }
        if (tick % t.virtualDematerializeIntervalTicks == 0) {
            h.dematerialize(level, t);
        }
    }

    /** 進める(テストからも)。 */
    public void move(ServerLevel level, int ticks, PerfTable t) {
        long now = level.getGameTime();
        Iterator<Swarm> it = swarms.iterator();
        while (it.hasNext()) {
            Swarm s = it.next();
            if (s.size() <= 0) {
                if (!s.unit()) it.remove(); // 部隊の空は前線の側が片付ける(実物が戻る先)
                continue;
            }
            if (s.arrivedAt >= 0L) {
                // 前線の部隊は着いた所で待つ(守る = 持ち場・広げる = 升・襲う = 拠点の前。戦いは読み込まれてから。11-21)
                if (s.unit()) continue;
                if (now - s.arrivedAt > t.virtualLifeAfterArriveTicks) {
                    expired += s.size();
                    it.remove();
                }
                continue;
            }
            if (s.waiting) continue;
            double dx = s.tx - s.x, dz = s.tz - s.z, d = Math.sqrt(dx * dx + dz * dz);
            if (d <= ARRIVE_BLOCKS) {
                if (s.retreating) {
                    retreated += s.size(); // 引き返して森に着いた: 消える
                    it.remove();
                    continue;
                }
                s.arrivedAt = now;
                continue;
            }
            double step = Math.min(s.speed * ticks, d - ARRIVE_BLOCKS * 0.5D);
            if (s.unit()) {
                // 11-21 地図の上の動き: 山の升は半分の速さ・水の升は避けて回り道(深い海は流れ木もどき = 区切り 3)
                double[] dir = com.yayuy.overgrownvegetation.front.UnitTerrain.stepDir(level, s.x, s.z, dx / d, dz / d, step);
                if (dir == null) {
                    s.waiting = true; // 囲まれて進めない: この回は待つ
                    continue;
                }
                step *= dir[2];
                s.x += dir[0] * step;
                s.z += dir[1] * step;
            } else {
                s.x += dx / d * step;
                s.z += dz / d * step;
            }
            s.faceTarget();
            double g = surfaceY(level, s.x, s.z);
            if (!Double.isNaN(g)) s.y = g;
        }
        setDirty();
    }

    /** 読み込み終わった所の地表の高さ(葉を除く高さ地図。読み込み途中なら NaN。チャンクを読み込まない)。 */
    static double surfaceY(ServerLevel level, double x, double z) {
        int bx = Mth.floor(x), bz = Mth.floor(z);
        LevelChunk ch = level.getChunkSource().getChunkNow(bx >> 4, bz >> 4);
        return ch == null ? Double.NaN : ch.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, bx & 15, bz & 15) + 1;
    }

    /** 水平の距離の 2 乗でいちばん近いプレイヤー(観戦を除く)までの 2 乗。 */
    static double nearestPlayerSq(ServerLevel level, double x, double z) {
        double best = Double.MAX_VALUE;
        if (playersForTests != null) {
            for (double[] p : playersForTests) best = Math.min(best, (p[0] - x) * (p[0] - x) + (p[1] - z) * (p[1] - z));
            return best;
        }
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator()) continue;
            double dx = p.getX() - x, dz = p.getZ() - z;
            best = Math.min(best, dx * dx + dz * dz);
        }
        return best;
    }

    /** 本物にする(テストからも)。 */
    public void materialize(ServerLevel level, PerfTable t) {
        double near = t.virtualMaterializeBlocks * t.virtualMaterializeBlocks;
        int room = (realCapForTests != null ? realCapForTests : (int) Math.round(com.yayuy.overgrownvegetation.difficulty.WorldDifficultyData.of(level.getServer(), com.yayuy.overgrownvegetation.difficulty.DifficultySetting.REAL_CAP))) - realHeads(level); // 11-19 MOD の設定(既定は perf.json の realCap)
        int budget = Math.min(room, t.virtualSpawnPerRun);
        RandomSource random = level.getRandom();
        double raid = liveRaid(level);
        // 襲撃の上限の数え(拠点ごとの攻め手)はこの回に 1 度だけ数え、出した分を足していく(11-18 見直し ③6。前は群れごとに全エンティティを数え直した)
        it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap raidCount = new it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap();
        // 11-21 戦っている部隊を先に出す: 行進する部隊(狩る・襲う・古い行進)→ ほか(守る・広げる・建てる)の順。同じ順の中は前の並びのまま
        List<Swarm> order = new ArrayList<>(swarms);
        order.sort(java.util.Comparator.comparingInt(s -> s.purpose.marches ? 0 : 1));
        for (Swarm s : order) {
            s.waiting = false;
            if (s.retreating || nearestPlayerSq(level, s.x, s.z) > near) continue;
            BlockPos center = BlockPos.containing(s.x, s.y, s.z);
            if (level.getChunkSource().getChunkNow(center.getX() >> 4, center.getZ() >> 4) == null || !level.isPositionEntityTicking(center)) {
                continue; // 読み込み範囲の外・読み込み途中: 仮想のまま(進み続ける)
            }
            // 襲撃の群れは、その襲撃の同時の上限(大規模レイドの maxAlive。重さの安全弁)も守る。値段は仮想を出した時に払い済み
            int own = VirtualRaids.room(level, s, raidCount);
            if (budget <= 0 || own <= 0) {
                s.waiting = true; // 本物の上限・この回の数の上限・襲撃の上限: その場で待つ
                holdOutside(level, s, t);
                continue;
            }
            int before = budget;
            budget = Math.min(budget, own);
            int rest = before - budget;
            int placed = 0;
            boolean blocked = false;
            for (Map.Entry<ResourceLocation, Integer> e : s.counts.entrySet()) {
                if (blocked) break;
                EntityType<?> type = BuiltInRegistries.ENTITY_TYPE.get(e.getKey());
                int left = e.getValue();
                while (left > 0 && budget > 0) {
                    // ひまわりの並び(遠景の身代わりと同じ式: VirtualHordeLayout)。番号は前から順に使い、詰めない(firstIndex。身代わりの並びが動かないように)
                    double[] o = VirtualHordeLayout.offset(s.id, s.firstIndex, type);
                    int bx = Mth.floor(s.x + o[0]), bz = Mth.floor(s.z + o[1]);
                    BlockPos feet = TreeCanopyGroundFinder.findSpawnSurface(level, bx, bz); // 樹冠の上でなく地面(読み込み途中なら null)
                    Mob m = feet == null || !level.isPositionEntityTicking(feet) || !level.getFluidState(feet).isEmpty() ? null
                            : EnemySpawns.create(level, type, feet, MobSpawnType.EVENT, random);
                    // 11-21 森が密だと並びの位置に体が入らない(幹・根・茂み): 前線の部隊はまわり 3 ブロックを数か所試す
                    for (int tr = 0; m == null && s.unit() && tr < 6; tr++) {
                        int ax = bx + random.nextInt(7) - 3, az = bz + random.nextInt(7) - 3;
                        BlockPos f2 = TreeCanopyGroundFinder.findSpawnSurface(level, ax, az);
                        if (f2 != null && level.isPositionEntityTicking(f2) && level.getFluidState(f2).isEmpty()) m = EnemySpawns.create(level, type, f2, MobSpawnType.EVENT, random);
                    }
                    if (m == null) {
                        blocked = true; // この一員が置けない: 順番を崩さないよう、この群れは次の回に(置けない回が続けば横へずれる)
                        break;
                    }
                    s.firstIndex++;
                    m.addTag(TAG);
                    if (!s.extraTag.isEmpty()) m.addTag(s.extraTag);
                    m.setYRot((float) Math.toDegrees(Math.atan2(-s.dirX, s.dirZ)));
                    m.getPersistentData().putString(EXTRA_KEY, s.extraTag);
                    if (s.unit()) {
                        m.getPersistentData().putLong(UNIT_KEY, s.id);
                        m.getPersistentData().putInt(FRONT_KEY, s.front);
                        m.getPersistentData().putString(PURPOSE_KEY, s.purpose.name());
                        if (s.size() > 0) com.yayuy.overgrownvegetation.director.ForestEconomy.markPaid(m, s.paid / s.size());
                    }
                    if (com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG.equals(s.extraTag)) m.getPersistentData().putDouble(RAID_KEY, raid);
                    if (!level.addFreshEntity(m)) {
                        // 断られた(ほかの MOD の湧きの取り消しなど): 頭数を減らさず、この群れは次の回に(11-18 見直し ③5。前は 1 体消えていた)
                        s.firstIndex--;
                        blocked = true;
                        break;
                    }
                    if (s.purpose.marches) beginMarch(m, s.baseId, s.tx, s.tz);
                    left--;
                    budget--;
                    placed++;
                }
                e.setValue(left);
            }
            int before2 = s.size() + placed;
            s.counts.values().removeIf(v -> v <= 0);
            if (placed > 0 && before2 > 0) s.paid -= s.paid * placed / (double) before2;
            budget += rest;
            if (placed > 0 && raidCount.containsKey(s.baseId) && com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG.equals(s.extraTag)) {
                raidCount.addTo(s.baseId, placed);
            }
            materialized += placed;
            if (s.size() <= 0) {
                if (!s.unit()) swarms.remove(s); // 部隊は空になっても残す(実物が戻る先・補充の先)
            } else if (placed == 0) {
                s.failures++;
                placeFailures++;
                // 1 体も置けない(水・崖・体が入らない): 行き先から見て横へずらしてやり直す
                double side = (s.failures % 2 == 0 ? 1.0D : -1.0D) * NUDGE_BLOCKS * ((s.failures + 1) / 2);
                s.x += -s.dirZ * side;
                s.z += s.dirX * side;
            }
            setDirty();
        }
    }

    /**
     * 待っている群れを、並び全体が本物にする距離の外に収まる所まで、いちばん近いプレイヤーから遠ざける(11-18 C の指摘: 64 の内側に仮想のまま
     * 残る一員は、叩けない身代わりが近くに見える)。
     */
    static void holdOutside(ServerLevel level, Swarm s, PerfTable t) {
        double[] p = nearestPlayer(level, s.x, s.z);
        if (p == null) return;
        double maxW = 1.0D;
        for (ResourceLocation id : s.counts.keySet()) maxW = Math.max(maxW, BuiltInRegistries.ENTITY_TYPE.get(id).getWidth());
        double keep = t.virtualMaterializeBlocks + 1.4D * maxW * Math.sqrt(s.firstIndex + s.size() + 0.5D) + 2.0D;
        double dx = s.x - p[0], dz = s.z - p[1], d = Math.sqrt(dx * dx + dz * dz);
        if (d >= keep || d < 1.0E-3D) return;
        s.x = p[0] + dx / d * keep;
        s.z = p[1] + dz / d * keep;
    }

    /** いちばん近いプレイヤー(観戦を除く)の {x, z}。いなければ null。 */
    @Nullable
    static double[] nearestPlayer(ServerLevel level, double x, double z) {
        double best = Double.MAX_VALUE;
        double[] out = null;
        if (playersForTests != null) {
            for (double[] q : playersForTests) {
                double d = (q[0] - x) * (q[0] - x) + (q[1] - z) * (q[1] - z);
                if (d < best) {
                    best = d;
                    out = q;
                }
            }
            return out;
        }
        for (ServerPlayer q : level.players()) {
            if (q.isSpectator()) continue;
            double d = (q.getX() - x) * (q.getX() - x) + (q.getZ() - z) * (q.getZ() - z);
            if (d < best) {
                best = d;
                out = new double[]{q.getX(), q.getZ()};
            }
        }
        return out;
    }

    /** 遠い本物を仮想へ戻す(テストからも)。戻した数を返す。 */
    public int dematerialize(ServerLevel level, PerfTable t) {
        double far = t.virtualDematerializeBlocks * t.virtualDematerializeBlocks;
        double keep = KEEP_NEAR_TARGET * KEEP_NEAR_TARGET, merge = MERGE_BLOCKS * MERGE_BLOCKS;
        List<Mob> back = new ArrayList<>();
        List<Mob> residents = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (!(e instanceof Mob m) || !m.isAlive() || !m.getTags().contains(TAG)) continue;
            if (m.hasCustomName() || m.isPassenger() || m.isVehicle()) continue;
            // 11-21 戻すのは戦っていない Mob だけ。傷は戻るときに治る(頭数だけ戻す。前は無傷のときだけ戻した)
            if (m.getTarget() instanceof Player) continue;
            if (m.getLastHurtByMob() != null && m.tickCount - m.getLastHurtByMobTimestamp() < 200) continue;
            // 前線の部隊の住人(守る・広げる・建てる)は行進していない。プレイヤーから遠ければ部隊へ戻す
            if (m.getPersistentData().contains(UNIT_KEY) && !UnitPurpose.byName(m.getPersistentData().getString(PURPOSE_KEY)).marches) {
                if (nearestPlayerSq(level, m.getX(), m.getZ()) > far) residents.add(m);
                continue;
            }
            if (!marching(m)) continue;
            net.minecraft.world.phys.Vec3 tg = marchTarget(m);
            if (tg == null) continue;
            double dx = tg.x - m.getX(), dz = tg.z - m.getZ();
            if (dx * dx + dz * dz < keep) continue;
            if (nearestPlayerSq(level, m.getX(), m.getZ()) <= far) continue;
            back.add(m);
        }
        for (Mob m : residents) returnToUnit(level, m);
        double raid = back.isEmpty() ? Double.NaN : liveRaid(level);
        for (Mob m : back) {
            net.minecraft.world.phys.Vec3 tg = marchTarget(m);
            int baseId = marchBase(m);
            String extra = m.getPersistentData().getString(EXTRA_KEY);
            if (com.yayuy.overgrownvegetation.director.LargeRaids.ATTACKER_TAG.equals(extra) && !ofLiveRaid(m, raid)) {
                // 終わった大規模レイドの攻め手(11-18 見直し ③1): 前は引き返さない新しい群れに戻され、また拠点へ歩いて本物になっていた。
                // プレイヤーから遠いので、群れへ戻さず森へ引き返して消えた扱いにする(死なせない)
                if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
                m.discard();
                retreated++;
                continue;
            }
            if (m.getPersistentData().contains(UNIT_KEY)) {
                returnToUnit(level, m); // 前線の部隊(狩る・襲う)は元の部隊へ
                continue;
            }
            Swarm into = null;
            for (Swarm s : swarms) {
                double ax = s.x - m.getX(), az = s.z - m.getZ();
                if (s.baseId == baseId && Math.abs(s.tx - tg.x) < 1.0D && Math.abs(s.tz - tg.z) < 1.0D && ax * ax + az * az <= merge
                        && s.extraTag.equals(extra) && s.size() < MAX_SWARM) {
                    into = s;
                    break;
                }
            }
            if (into == null) {
                into = new Swarm(nextId++, m.getX(), m.getY(), m.getZ(), DesignData.perf().virtualSpeed, baseId, tg.x, tg.z, extra);
                swarms.add(into);
            }
            // 本物にした分だけ進む番号は戻らないので、往復を繰り返すと際限なく増え、群れの真ん中から遠くに本物が出た・クライアントの鍵
            // (群れの id × 4096 + 番号)が隣の群れとぶつかった(11-18 見直し ③9)。戻すとき(プレイヤーから遠い)に大きければ 0 からに戻す
            if (into.firstIndex > MAX_SWARM) into.firstIndex = 0;
            into.add(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()), 1);
            if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
            m.discard(); // 死なせない(死の破裂で汚染を撒かない)
            dematerialized++;
        }
        if (!back.isEmpty() || !residents.isEmpty()) setDirty();
        return back.size() + residents.size();
    }

    /**
     * 部隊から出た実物を、その部隊(群れの id)へ頭数として戻す(11-21)。部隊がもう無ければ、同じ前線・目的の部隊をその場に作り直す。
     * 傷は数えない(戻ると治る)。払った額は部隊へ戻す。死なせずに消す(死の破裂で汚染を撒かない)。
     */
    void returnToUnit(ServerLevel level, Mob m) {
        CompoundTag pd = m.getPersistentData();
        Swarm s = byId(pd.getLong(UNIT_KEY));
        if (s == null) {
            s = new Swarm(nextId++, m.getX(), m.getY(), m.getZ(), DesignData.front().unitSpeed, -1, m.getX(), m.getZ(), "");
            s.front = pd.getInt(FRONT_KEY);
            s.purpose = UnitPurpose.byName(pd.getString(PURPOSE_KEY));
            s.purposeSince = level.getGameTime();
            s.arrivedAt = level.getGameTime();
            swarms.add(s);
        }
        if (s.firstIndex > MAX_SWARM) s.firstIndex = 0;
        if (s.size() < MAX_SWARM) {
            s.add(BuiltInRegistries.ENTITY_TYPE.getKey(m.getType()), 1);
            s.paid += com.yayuy.overgrownvegetation.director.ForestEconomy.paid(m);
        }
        if (CrawlerPack.externalOf(m) != null) CrawlerPack.leaveExternal(level, m);
        m.discard();
        dematerialized++;
    }

    /** 全部消す(確かめ用)。 */
    public int clear() {
        int n = virtualHeads();
        swarms.clear();
        setDirty();
        return n;
    }

    /** 確かめ用の 1 行。 */
    public String describe(ServerLevel level) {
        int real = realHeads(level);
        return String.format(Locale.ROOT, "仮想の群れ: %s・群れ %d・仮想 %d 体・本物 %d 体(合計 %d)・本物にした %d・戻した %d・置けなかった回 %d・寿命で消えた %d・引き返して消えた %d・"
                        + "進める 平均 %.3f ms・本物にする 平均 %.2f ms 最大 %.2f ms(%d 回)",
                enabled() ? "入" : "切", swarms.size(), virtualHeads(), real, virtualHeads() + real, materialized, dematerialized, placeFailures, expired, retreated,
                moveRuns == 0 ? 0.0D : moveNanos / 1e6 / moveRuns,
                runs == 0 ? 0.0D : materializeNanos / 1e6 / runs, maxMaterializeNanos / 1e6, runs);
    }

    // ---------------------------------------------------------------- 保存

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Swarm s : swarms) {
            CompoundTag c = new CompoundTag();
            c.putLong("Id", s.id);
            c.putDouble("X", s.x);
            c.putDouble("Y", s.y);
            c.putDouble("Z", s.z);
            c.putDouble("Speed", s.speed);
            c.putInt("Base", s.baseId);
            c.putDouble("TX", s.tx);
            c.putDouble("TZ", s.tz);
            c.putString("Tag", s.extraTag);
            c.putLong("Arrived", s.arrivedAt);
            c.putDouble("OX", s.originX);
            c.putDouble("OZ", s.originZ);
            c.putBoolean("Retreat", s.retreating);
            c.putInt("First", s.firstIndex);
            if (s.unit()) {
                c.putInt("Front", s.front);
                c.putString("Purpose", s.purpose.name());
                c.putDouble("Paid", s.paid);
                c.putLong("Since", s.purposeSince);
                if (s.hunt != null) c.putUUID("Hunt", s.hunt);
            }
            ListTag counts = new ListTag();
            for (Map.Entry<ResourceLocation, Integer> e : s.counts.entrySet()) {
                CompoundTag ct = new CompoundTag();
                ct.putString("Type", e.getKey().toString());
                ct.putInt("N", e.getValue());
                counts.add(ct);
            }
            c.put("Counts", counts);
            list.add(c);
        }
        tag.put("Swarms", list);
        tag.putLong("NextId", nextId);
        return tag;
    }

    /** 保存した形から読む(テストからも)。 */
    public static VirtualHordes load(CompoundTag tag) {
        VirtualHordes h = new VirtualHordes();
        h.nextId = Math.max(1L, tag.getLong("NextId"));
        for (Tag t : tag.getList("Swarms", Tag.TAG_COMPOUND)) {
            CompoundTag c = (CompoundTag) t;
            Swarm s = new Swarm(c.getLong("Id"), c.getDouble("X"), c.getDouble("Y"), c.getDouble("Z"), c.getDouble("Speed"), c.getInt("Base"),
                    c.getDouble("TX"), c.getDouble("TZ"), c.getString("Tag"));
            s.arrivedAt = c.getLong("Arrived");
            if (c.contains("OX")) {
                s.originX = c.getDouble("OX");
                s.originZ = c.getDouble("OZ");
            }
            s.retreating = c.getBoolean("Retreat");
            s.firstIndex = Math.max(0, c.getInt("First"));
            if (c.contains("Front")) {
                s.front = c.getInt("Front");
                s.purpose = UnitPurpose.byName(c.getString("Purpose"));
                s.paid = Math.max(0.0D, c.getDouble("Paid"));
                s.purposeSince = c.getLong("Since");
                if (c.hasUUID("Hunt")) s.hunt = c.getUUID("Hunt");
            }
            for (Tag ct : c.getList("Counts", Tag.TAG_COMPOUND)) {
                CompoundTag cc = (CompoundTag) ct;
                ResourceLocation id = ResourceLocation.tryParse(cc.getString("Type"));
                if (id == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(id)) continue; // 消えた種(MOD を外した)は捨てる
                int n = Math.max(0, Math.min(MAX_SWARM, cc.getInt("N")));
                if (n > 0) s.add(id, n);
            }
            if (s.size() > 0 || s.unit()) h.swarms.add(s); // 部隊は空でも残す(実物が戻る先)
            h.nextId = Math.max(h.nextId, s.id + 1);
        }
        return h;
    }
}

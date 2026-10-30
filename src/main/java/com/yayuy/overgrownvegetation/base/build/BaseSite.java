package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ally.SoldierKind;
import com.yayuy.overgrownvegetation.base.plan.BaseEmitter;
import com.yayuy.overgrownvegetation.base.plan.BasePlan;
import com.yayuy.overgrownvegetation.base.plan.BasePlanner;
import com.yayuy.overgrownvegetation.base.plan.BaseType;
import com.yayuy.overgrownvegetation.base.plan.BaseTerrain;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 台帳({@link BaseSites})に載る基地 1 つ(2026-10-01)。中心・向き・配置の種・レベルと、地形の標本(8 マスおきの地面と水面の高さ)を持つ。
 * 計画と地形は種と標本から作り直せるので、ブロックの中身は保存しない。
 *
 * <p>局所座標(基地の中心が原点、正門が南)と世界の座標の変換も持つ。{@link #rotation} は時計回りの 1/4 回転の回数。
 *
 * <p>{@link #version}: 配置の作り方の版。2 = 区画を継ぎ足す形(2026-10-01 の作り直し)、3 = 区画どうしのあいだの埋め草・見張り台と壁の砲台(10-02)、
 * 4 = 浄化圏をつなぐ形(10-02 の作り直し)、5 = 兵舎を増やした(居住区の浄化圏を大きめに・建物の列を浄化圏の中に収める、10-02)、
 * 6 = 起伏のきつい地形でも継ぎ足す・壁の機関砲台を胸壁の列へ(10-02)、7 = 設備までの経路(塔の階段と屋根の口・壁へ上がる階段・扉の前の高さ)・
 * 内側の柵・弾薬区などの浄化圏を大きめに(10-02)、8 = 観測・研究 = 出城、野戦病院 = 大通り沿い、特別な施設(10-02 夜。この 3 種類だけ計画が変わった)、
 * 9 = 研究の出城は 1 つで真ん中に大きな研究所(10-02 夜。研究だけ計画が変わった。野戦病院の医療テントの見た目も変えたが計画は同じ)、
 * 10 = 研究の出城がレベル 3〜5 で横へ広がり、研究所も増築の棟と連絡通路で広がる(10-02 夜。研究だけ)。
 * それより前の版で建てた基地は、広げるときの差分(古い計画と新しい計画の違い)が今のコードで作り直した古い計画になって合わないので、
 * 広げない(台帳から外して建て直してもらう)。どの版から前かは種類ごと({@link #planVersion})。
 */
public final class BaseSite {

    /** 今の配置の作り方の版(10 = 研究所が出城の広がりと一緒に横へ広がる、2026-10-02 夜)。新しく建てる基地はこの版。 */
    public static final int VERSION = 10;

    /**
     * 種類 {@code type} の計画が最後に変わった版。これより前の版の基地は広げられない。版 8 で計画が変わったのは観測・研究・野戦病院だけ
     * (浄化・大型防衛・補給・工廠は試験の地形 240 計画と台帳の地形 6 基地で、計画も書き出しも 1 マスも変わっていない)ので、その 4 種類の版 7 の基地はそのまま広げられる。
     * 版 9・10 で計画が変わったのは研究だけ(野戦病院は医療テントの設計図だけで、建っているテントは建て直すまで前の見た目のまま)。
     */
    static int planVersion(BaseType type) {
        return switch (type) {
            case RESEARCH -> 10;
            case OBSERVATION, HOSPITAL -> 8;
            default -> 7;
        };
    }

    public final int id;
    public final ResourceKey<Level> dimension;
    public final BlockPos center;
    public final int rotation;
    public final long seed;
    public final int version;
    /** 基地の種類(広げ方が変わる。ユーザー指定 2026-10-02)。この欄より前の台帳の基地は浄化(浄化圏がつながる形)。 */
    public final BaseType type;
    private int level;
    private final short[] samples;
    private final short[] surface;

    /** 建てている途中なら、建て始めたときのレベル(0 = 新しく建てる)と目標のレベル(0 = 建てていない)、進んだチャンクの数。 */
    int buildFrom;
    int buildTo;
    int cursor;
    /**
     * 胞子除去装置を置くか(置いたか)。偽の間は、計画の除去装置の場所を空気にして建てる(ユーザー指定 2026-10-02「フェーズが 2 になるまでは
     * 基地に胞子除去装置を置かない」)。フェーズ 2 に入ってからゲーム内 1 日で真にして、建っている所へ後から置く({@link BasePurifiers})。
     * この印より前の台帳(印が無い)の基地は、もう除去装置が建っているので真。
     */
    boolean purifiers = true;
    /**
     * 世界生成で生まれた基地か(順 2 の着手順 2、2026-10-04)。ブロックは地形と一緒に置かれ、プレイヤーが初めて中心のチャンクを読み込んだときに
     * 台帳に載る({@code base/gen/BaseWorldgen})。
     */
    boolean generated;
    /**
     * 読み込まれたら手を入れるチャンク({@link ChunkPos#toLong})。胞子除去装置の後付けと、陥落の後片付け(設備を空にする)の 2 つ
     * ({@link BaseChunkJobs})。2026-10-04 から、どちらも読み込まれていないチャンクを作ってまで回らない(ユーザー指定 10-02「後付けは読み込んだチャンクから」)。
     */
    final LongOpenHashSet purifyPending = new LongOpenHashSet();
    final LongOpenHashSet emptyPending = new LongOpenHashSet();
    /** 前の保存の「途中までの工事」(数えた位置で持っていた)を、次の tick に控えへ直す印。 */
    transient boolean restartPurify;
    transient boolean restartEmpty;

    // ---------- 貯蓄と補給値・仮の陥落(順 2 の着手順 1、2026-10-04、{@link BaseEconomy}) ----------

    /** 貯蓄(−1 = まだ始めていない。最初の精算で上限 × 最初の割合から始まる。この欄より前の台帳の基地も同じ)。 */
    int stock = -1;
    /** 貯蓄を始めた暦の日(補給値の増え方の起点)。 */
    long foundedDay;
    /** 最後に精算した暦の日。 */
    long lastDay;
    /** 留守中に「減る量 > 回復量」だった日が続いた数(ユーザー指定: 3 日続くと陥落)。 */
    int streak;
    /** 陥落したか(仮の陥落。順 5 で G7 の前哨基地に置き換える。それまでは取り戻せない —— ユーザー指定 2026-10-04)。 */
    boolean fallen;
    /** 直近の精算の内訳(管理端末とコマンドの表示用): 回復量・減った量・周りの汚染の割合・その日プレイヤーがいたか。 */
    int lastRecovery;
    int lastDecline;
    float lastRatio;
    boolean lastPresent = true;

    // ---------- 守備兵の名簿と補充(順 2 の着手順 3、2026-10-04、{@link BaseGarrison}) ----------

    /**
     * 守備兵の名簿(生きている兵士の UUID → 兵種)。出した兵士を載せ、倒れたら消す。減った数 = 定員 − 名簿
     * (読み込まれていないチャンクの兵士は数えられないので、エンティティを数えずに名簿で持つ)。
     */
    final Map<UUID, SoldierKind> roster = new LinkedHashMap<>();
    /**
     * 守備兵を置いたことがあるか(建て終えた・台帳に載ったときの {@link BaseGarrison#reinforce} で真)。偽の基地には「減った分」が無いので補充しない
     * (工事中の新しい基地・守備兵を切った GameTest の基地)。この欄より前の台帳の基地は守備兵を置いてあるので真。
     */
    boolean garrisoned;
    /**
     * 名簿が実際の兵士と合っているか。新しい基地は真(まだ誰もいない)。この欄より前の台帳の基地は偽で、基地が全部読み込まれているときに
     * 実際の兵士を数えて名簿を作るまで補充しない({@link BaseGarrison#census})。
     */
    boolean rosterKnown = true;
    /** 補充の待ち(tick。減っている間だけ進む)。 */
    transient int replenishTimer;
    /** 次に兵士を出す兵舎(順番に回す)。 */
    transient int nextBarracks;
    /** {@link #refugeBox} の写しと、それを作ったレベル。 */
    private transient int[] refugeBox;
    private transient int refugeBoxLevel;
    /** 整備の控え(計画の設備と倉庫・仕事。順 2 の着手順 4、{@link BaseUpkeep}。保存しない)。 */
    transient BaseUpkeep.State upkeep;

    private transient BaseTerrain terrain;
    /** レベルごとの書き出し器(広げるときは古いレベルと新しいレベルの 2 つを使う)。 */
    private final transient Map<Integer, BaseEmitter> emitters = new LinkedHashMap<>();

    BaseSite(int id, ResourceKey<Level> dimension, BlockPos center, int rotation, long seed, int level,
             short[] samples, short[] surface, int version, BaseType type) {
        this.id = id;
        this.dimension = dimension;
        this.center = center.immutable();
        this.rotation = Math.floorMod(rotation, 4);
        this.seed = seed;
        this.level = level;
        this.samples = samples;
        this.surface = surface;
        this.version = version;
        this.type = type;
    }

    public int level() {
        return level;
    }

    void setLevel(int level) {
        this.level = level;
    }

    public boolean building() {
        return buildTo > 0;
    }

    /** 胞子除去装置を置く(置いた)基地か。偽なら建てるときに除去装置の場所を空ける。 */
    public boolean purifiers() {
        return purifiers;
    }

    /** 胞子除去装置を後から置いている途中か(読み込まれていないチャンクが残っている間も真)。 */
    public boolean installingPurifiers() {
        return !purifyPending.isEmpty() || restartPurify;
    }

    /** 胞子除去装置の後付けで、まだ置いていないチャンクの数。 */
    public int purifyPendingChunks() {
        return purifyPending.size();
    }

    /** 陥落の後片付けで、まだ空にしていないチャンクの数。 */
    public int emptyPendingChunks() {
        return emptyPending.size();
    }

    /** 世界生成で生まれた基地か。 */
    public boolean generated() {
        return generated;
    }

    public int buildTarget() {
        return buildTo;
    }

    /** 貯蓄(−1 = まだ始めていない)。 */
    public int stock() {
        return stock;
    }

    /** 留守中に「減る量 > 回復量」だった日が続いた数。 */
    public int streak() {
        return streak;
    }

    /** 陥落したか(仮の陥落、ユーザー指定 2026-10-04)。 */
    public boolean fallen() {
        return fallen;
    }

    /** 直近の精算で回復した量。 */
    public int lastRecovery() {
        return lastRecovery;
    }

    /** 直近の精算で減った量。 */
    public int lastDecline() {
        return lastDecline;
    }

    /** 直近の精算で数えた周りの汚染の割合(0〜1)。 */
    public float lastRatio() {
        return lastRatio;
    }

    /** 直近の精算のときにプレイヤーがいたか(いれば減らない)。 */
    public boolean lastPresent() {
        return lastPresent;
    }

    /** 名簿に載っている守備兵の数。 */
    public int rosterSize() {
        return roster.size();
    }

    /** 名簿に載っている兵種 {@code kind} の守備兵の数。 */
    public int rosterCount(SoldierKind kind) {
        int n = 0;
        for (SoldierKind k : roster.values()) {
            if (k == kind) {
                n++;
            }
        }
        return n;
    }

    /** 兵士 {@code uuid} が名簿に載っているか。 */
    public boolean inRoster(UUID uuid) {
        return roster.containsKey(uuid);
    }

    /** 名簿が実際の兵士と合っているか(偽の間は補充しない)。 */
    public boolean rosterKnown() {
        return rosterKnown;
    }

    /** 守備兵を置いたことがあるか(偽の基地は補充しない)。 */
    public boolean garrisoned() {
        return garrisoned;
    }

    /** 最後に精算した暦の日。 */
    public long lastDay() {
        return lastDay;
    }

    /** 貯蓄を始めた暦の日。 */
    public long foundedDay() {
        return foundedDay;
    }

    /** 前の版の作り方で建てた基地か(広げられない)。種類ごとに、計画が最後に変わった版より前か。 */
    public boolean outdated() {
        return version < planVersion(type);
    }

    public BaseTerrain terrain() {
        if (terrain == null) {
            terrain = BaseTerrain.fromSamples(samples, surface);
        }
        return terrain;
    }

    /** レベル {@code lv} の計画の書き出し器(直近の 2 レベルぶんを使い回す)。 */
    public synchronized BaseEmitter emitter(int lv) {
        BaseEmitter em = emitters.get(lv);
        if (em == null) {
            em = new BaseEmitter(BasePlanner.plan(seed, lv, terrain(), type), terrain());
            emitters.put(lv, em);
            while (emitters.size() > 2) {
                emitters.remove(emitters.keySet().iterator().next());
            }
        }
        return em;
    }

    /**
     * 同じ基地の書き出し用の写し {@code from}(世界生成の部品が持つ)が作った計画を使い回す(台帳に載せるときに、生成の裏で作った計画を
     * 本体の tick で作り直さない)。種・向き・種類・レベルが違えば何もしない。
     */
    public void adoptPlans(@javax.annotation.Nullable BaseSite from) {
        if (from == null || from == this || from.seed != seed || from.rotation != rotation || from.type != type) {
            return;
        }
        Map<Integer, BaseEmitter> copy;
        synchronized (from) {
            copy = new LinkedHashMap<>(from.emitters);
        }
        synchronized (this) {
            for (Map.Entry<Integer, BaseEmitter> e : copy.entrySet()) {
                emitters.putIfAbsent(e.getKey(), e.getValue());
            }
            if (terrain == null && !copy.isEmpty()) {
                terrain = copy.values().iterator().next().terrain();
            }
        }
    }

    /**
     * レベル {@code from} → {@code to} で浄化圏が増えるか。増えない = 水と急な斜面に囲まれて継ぎ足せる場所が無い
     * (外周は広がらず、壁・守備兵などだけがレベルどおりに上がる)。広げ始めるときに知らせる(コマンドと管理端末)。
     */
    public boolean grows(int from, int to) {
        return from <= 0 || emitter(to).plan().lobes.size() > emitter(from).plan().lobes.size();
    }

    public BasePlan plan(int lv) {
        return emitter(lv).plan();
    }

    // ---------- 座標 ----------

    /** 局所座標 → 世界の座標(時計回りに {@link #rotation} 回)。 */
    public BlockPos toWorld(int lx, int y, int lz) {
        int x = lx;
        int z = lz;
        for (int i = 0; i < rotation; i++) {
            int t = x;
            x = -z;
            z = t;
        }
        return new BlockPos(center.getX() + x, y, center.getZ() + z);
    }

    /** 世界の x, z → 局所座標 {x, z}。 */
    public int[] toLocal(int wx, int wz) {
        int x = wx - center.getX();
        int z = wz - center.getZ();
        for (int i = 0; i < rotation; i++) {
            int t = x;
            x = z;
            z = -t;
        }
        return new int[]{x, z};
    }

    /**
     * 今のレベルの壁の中(浄化圏の和)の外接の、世界の座標 {x0, z0, x1, z1}(端を含む)。避難場所の判定({@link BaseRefuge})が
     * {@link #contains} の前に見る下ごしらえ。レベルが変わったら作り直す。本体のスレッドだけで使う。
     */
    public int[] refugeBox() {
        if (refugeBox == null || refugeBoxLevel != level) {
            int[] b = plan(level).bounds();
            BlockPos c0 = toWorld(b[0], 0, b[1]);
            BlockPos c1 = toWorld(b[2], 0, b[3]);
            refugeBox = new int[]{Math.min(c0.getX(), c1.getX()), Math.min(c0.getZ(), c1.getZ()),
                    Math.max(c0.getX(), c1.getX()), Math.max(c0.getZ(), c1.getZ())};
            refugeBoxLevel = level;
        }
        return refugeBox;
    }

    /** 世界の (x, z) がレベル {@code lv} の基地の中(外周の帯を含む)か。 */
    public boolean contains(int wx, int wz, int lv) {
        int[] l = toLocal(wx, wz);
        return plan(Math.max(1, Math.min(BasePlan.MAX_LEVEL, lv))).inside(l[0], l[1]);
    }

    // ---------- 保存 ----------

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("id", id);
        tag.putString("dim", dimension.location().toString());
        tag.putInt("x", center.getX());
        tag.putInt("y", center.getY());
        tag.putInt("z", center.getZ());
        tag.putInt("rot", rotation);
        tag.putLong("seed", seed);
        tag.putInt("level", level);
        tag.putInt("ver", version);
        tag.putString("type", type.id);
        tag.putIntArray("samples", widen(samples));
        tag.putIntArray("surface", widen(surface));
        tag.putInt("buildFrom", buildFrom);
        tag.putInt("buildTo", buildTo);
        tag.putInt("cursor", cursor);
        tag.putBoolean("purifiers", purifiers);
        tag.putBoolean("generated", generated);
        tag.putLongArray("purifyPending", purifyPending.toLongArray());
        tag.putLongArray("emptyPending", emptyPending.toLongArray());
        tag.putBoolean("restartPurify", restartPurify);
        tag.putBoolean("restartEmpty", restartEmpty);
        tag.putInt("stock", stock);
        tag.putLong("foundedDay", foundedDay);
        tag.putLong("lastDay", lastDay);
        tag.putInt("streak", streak);
        tag.putBoolean("fallen", fallen);
        tag.putInt("lastRecovery", lastRecovery);
        tag.putInt("lastDecline", lastDecline);
        tag.putFloat("lastRatio", lastRatio);
        tag.putBoolean("lastPresent", lastPresent);
        ListTag list = new ListTag();
        for (Map.Entry<UUID, SoldierKind> e : roster.entrySet()) {
            CompoundTag s = new CompoundTag();
            s.putUUID("u", e.getKey());
            s.putString("k", e.getValue().id);
            list.add(s);
        }
        tag.put("roster", list);
        tag.putBoolean("rosterKnown", rosterKnown);
        tag.putBoolean("garrisoned", garrisoned);
        return tag;
    }

    /** 保存して読み直した写し(GameTest で、貯蓄と陥落の欄が保存を通るかを見る)。 */
    public static BaseSite reloaded(BaseSite site) {
        return load(site.save());
    }

    /**
     * 台帳に載せない、書き出し用の基地(番号 0、胞子除去装置なし)。世界生成の部品がチャンクごとに書き出すときに、計画と座標の変換だけ借りる
     * (順 2 の着手順 2、{@code base/gen/AlliedBasePiece})。
     */
    public static BaseSite forEmission(ResourceKey<Level> dim, BlockPos center, int rotation, long seed, int level,
                                       short[] samples, short[] surface, int version, BaseType type) {
        BaseSite site = new BaseSite(0, dim, center, rotation, seed, level, samples, surface, version, type);
        site.purifiers = false;
        site.generated = true;
        return site;
    }

    static BaseSite load(CompoundTag tag) {
        short[] samples = narrow(tag.getIntArray("samples"));
        short[] surface = tag.contains("surface") ? narrow(tag.getIntArray("surface")) : samples.clone();
        ResourceKey<Level> dim = ResourceKey.create(Registries.DIMENSION, ResourceLocation.parse(tag.getString("dim")));
        BaseSite site = new BaseSite(tag.getInt("id"), dim, new BlockPos(tag.getInt("x"), tag.getInt("y"), tag.getInt("z")),
                tag.getInt("rot"), tag.getLong("seed"), Math.max(1, tag.getInt("level")), samples, surface, tag.getInt("ver"),
                BaseType.byId(tag.getString("type")));
        site.buildFrom = tag.getInt("buildFrom");
        site.buildTo = tag.getInt("buildTo");
        site.cursor = tag.getInt("cursor");
        site.purifiers = !tag.contains("purifiers") || tag.getBoolean("purifiers");
        site.generated = tag.getBoolean("generated");
        for (long c : tag.getLongArray("purifyPending")) {
            site.purifyPending.add(c);
        }
        for (long c : tag.getLongArray("emptyPending")) {
            site.emptyPending.add(c);
        }
        // 前の保存(2026-10-04 の着手順 2 より前)は進んだチャンクの数で持っていた → 次の tick に控えを作り直す(読み込まれたチャンクから)
        site.restartPurify = tag.getBoolean("restartPurify") || tag.contains("installCursor") && tag.getInt("installCursor") >= 0;
        site.restartEmpty = tag.getBoolean("restartEmpty") || tag.contains("fallCursor") && tag.getInt("fallCursor") >= 0;
        site.stock = tag.contains("stock") ? tag.getInt("stock") : -1; // この欄より前の台帳の基地は、次の精算で始める
        site.foundedDay = tag.getLong("foundedDay");
        site.lastDay = tag.getLong("lastDay");
        site.streak = tag.getInt("streak");
        site.fallen = tag.getBoolean("fallen");
        site.lastRecovery = tag.getInt("lastRecovery");
        site.lastDecline = tag.getInt("lastDecline");
        site.lastRatio = tag.getFloat("lastRatio");
        site.lastPresent = !tag.contains("lastPresent") || tag.getBoolean("lastPresent");
        for (Tag t : tag.getList("roster", Tag.TAG_COMPOUND)) {
            CompoundTag s = (CompoundTag) t;
            SoldierKind kind = SoldierKind.byId(s.getString("k"));
            if (s.hasUUID("u") && kind != null) {
                site.roster.put(s.getUUID("u"), kind);
            }
        }
        // この欄より前の台帳の基地は守備兵を置いてあり、名簿が無い → 基地が全部読み込まれたときに数えて名簿を作る
        site.garrisoned = !tag.contains("garrisoned") || tag.getBoolean("garrisoned");
        site.rosterKnown = tag.getBoolean("rosterKnown");
        if (site.outdated()) { // 前の版の工事の続きは今の計画では置けない
            site.buildFrom = 0;
            site.buildTo = 0;
            site.cursor = 0;
            site.purifyPending.clear();
            site.restartPurify = false;
        }
        return site;
    }

    private static int[] widen(short[] a) {
        int[] r = new int[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = a[i];
        }
        return r;
    }

    private static short[] narrow(int[] a) {
        short[] r = new short[a.length];
        for (int i = 0; i < a.length; i++) {
            r[i] = (short) a[i];
        }
        return r;
    }
}

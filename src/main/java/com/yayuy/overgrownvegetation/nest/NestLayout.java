package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.data.NestTable;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * アルボラの巣の形を計算する(2026-09-19)。<b>地形は一切見ない純粋な計算</b>で、
 * 「どのマスを掘るか」「どのマスを壁にするか」「側室がどこにあるか」だけを出す。
 * 実際に掘ってよいか(プレイヤーの建築物ではないか、水ではないか)の判断は
 * {@link NestGrowthQueue} が設置するときに行う。
 *
 * <h2>形: 1 本の幹と、そこから出る枝</h2>
 * <p>入口の坑道(幹)が地表の塚から<b>斜めに下りていき</b>、その途中から枝が
 * <b>1 本ずつ順番に</b>分かれる。枝はさらに孫枝を出し、道の脇と行き止まりに部屋ができる。
 *
 * <p><b>放射状に一斉に生やしてはいけない</b> —— 1 か所から何本も同時に出すと、その周りが
 * まとめて掘り抜かれて<b>大きな空洞</b>になる(2026-09-19 にゲーム内で確認)。枝は幹に沿って
 * {@link #branchEvery()} 歩ごとに 1 本ずつ、左右交互に出す。
 *
 * <p>枝は水平とはかぎらない。生えるときに「下る / 水平 / 上る」のどれかを選び、途中でも
 * {@link NestTable#descendChance()} で下り坂に変わる。ただし傾きは
 * {@link NestTable#maxTunnelSlope()} までに抑えるので<b>縦穴にはならず、歩いて下れる坂</b>になる
 * (ユーザー指定 2026-09-19)。
 *
 * <p>掘削点は<b>全部まとめて 1 歩ずつ</b>回す。おかげで予算(
 * {@link NestTable#blockBudget()})で打ち切ってもどの枝も同じくらいの長さになり、形が片寄らない。
 */
public final class NestLayout {

    /** 側室 1 つ。{@code pos} は部屋の中心。 */
    public record Chamber(NestChamber type, BlockPos pos, int radius) {
    }

    /**
     * 坑道の<b>行き止まり</b>と、その先に置いた部屋(2026-09-20)。
     * {@code room} が null なら置けなかった —— 「坑道の最奥には必ず部屋がある」が破れている印。
     */
    public record TunnelEnd(BlockPos pos, @Nullable NestChamber room) {
    }

    /** 掘る計画。座標はすべてワールド絶対座標を {@link BlockPos#asLong} で詰めたもの。 */
    public static final class Plan {
        /** 巣の中心(地表)。塚はここに盛る。 */
        public final BlockPos origin;
        /** 地表に盛る塚のマス。掘る前に置く(入口の坑道はこの塚を貫いて下りていく)。 */
        public final LongArrayList mound = new LongArrayList();
        /** 空洞にするマス。掘った順に並んでいるので、この順に設置すれば「掘り進む」ように見える。 */
        public final LongArrayList hollow = new LongArrayList();
        /** 空洞に面したマス = 壁。水や溶岩を塞ぐのもここ。 */
        public final LongArrayList lining = new LongArrayList();
        /** 壁のうち<b>真上が空洞</b>のマス = 床。草を生やすのと、土や草ブロックを混ぜるのに使う。 */
        public final LongArrayList liningFloor = new LongArrayList();
        /** 側室(女王室・核を含む)。 */
        public final List<Chamber> chambers = new ArrayList<>();
        /**
         * 噴出孔の<b>柱</b>(2026-09-20 にユーザー指定で「中が空洞の煙突」から作り直し)。
         * ここは<b>掘るのではなく詰める</b> —— 空洞にはしないので、{@link #hollow} とは別に持つ。
         */
        public final LongArrayList ventColumn = new LongArrayList();
        /** 噴出孔の柱のてっぺん(最大 3)。噴気室が無ければ空。ここの真上に噴出孔ブロックが載る。 */
        public final List<BlockPos> surfaceVents = new ArrayList<>();
        /**
         * 巣じゅうの<b>道しるべ</b>(およそ 3 マスおき)。Mob が巣を出入りするときに辿る
         * ({@code nest/NestRoute})。<b>添字 0 が坑口</b>。
         *
         * <p>これが要るのは、アルボラの経路探索が壁のぼり用({@code WallClimberNavigation})で、
         * 目的地までの経路が見つからないと<b>そのまま直進して壁に張り付く</b>ため
         * (2026-09-20 にゲーム内で確認)。長い坑道を一気に指すのではなく、
         * 隣の道しるべまでの短い距離を順に歩かせれば普通の経路探索で通れる。
         *
         * <p><b>幹だけでなく枝・側室への通路・部屋の中心にも落とす</b>(2026-09-20 に作り直し)。
         * 幹の 1 本線だけだったときは、枝や側室に居る個体が幹まで遠く、そこへ置く中継点が
         * <b>岩の中に落ちて</b>経路が出ず、結局直進して天井に張り付いていた(ユーザー報告)。
         */
        public final List<BlockPos> route = new ArrayList<>();
        /**
         * 道しるべ {@code i} の<b>親</b> —— 坑口へ 1 歩戻る先。根(坑口)は -1。
         * どこに居ても親をたぐるだけで坑口に出られ、逆にたどれば任意の部屋へ入れる。
         */
        public final IntArrayList routeParents = new IntArrayList();
        /**
         * 外に開いたままにする場所 —— 坑口と噴出口。{@link NestGrowthQueue} はここの近くだけ
         * <b>外殻を張らない</b>。これが無いと殻が入口ごと塞いでしまい、入れない巣になる。
         */
        public final List<BlockPos> openings = new ArrayList<>();
        /**
         * 坑道の<b>行き止まり</b>(2026-09-20)。ここには必ず孵化室か貯蔵室が付く。
         * テストが「どの坑道も最後まで行けば部屋がある」ことを確かめるのに使う。
         */
        public final List<TunnelEnd> tunnelEnds = new ArrayList<>();
        /**
         * {@link #hollow} のうち、ここから後ろは<b>噴出孔</b>(噴気室から地表へ抜ける煙突)。
         * 煙突は人が通る坑道ではないので垂直でよく、坑道の傾きの決まりから外して扱う。
         */
        public int ventShaftFrom;
        /** 坑口(塚の横腹に開く穴)。側室の「入口から離れているか」はここから測る。 */
        public BlockPos mouth;
        /**
         * 巣の本体の中心(幹が主坑の深さの半分まで下りた場所)。枝の広がり({@code spread})はここから測る。
         * 入口は坑口として離れた場所にあるので、入口を中心にすると巣が片側へ寄る。
         */
        public BlockPos centre;
        /** 核(女王室の奥)。女王室が作れなければ null。 */
        @Nullable
        public BlockPos corePos;

        Plan(BlockPos origin) {
            this.origin = origin;
            this.centre = origin;
            this.mouth = origin;
            this.ventShaftFrom = Integer.MAX_VALUE;
        }

        public boolean isEmpty() {
            return hollow.isEmpty();
        }

        /** {@code pos} が坑口や噴出口のそば({@code radius} マス以内)か。外殻を張らない範囲。 */
        public boolean nearOpening(BlockPos pos, int radius) {
            double rr = (double) radius * radius;
            for (BlockPos o : openings) {
                double dx = pos.getX() - o.getX();
                double dy = pos.getY() - o.getY();
                double dz = pos.getZ() - o.getZ();
                if (dx * dx + dy * dy + dz * dz <= rr) {
                    return true;
                }
            }
            return false;
        }
    }

    /** 掘削点。 */
    private static final class Digger {
        double x;
        double y;
        double z;
        double dx;
        double dy;
        double dz;
        int budget;
        int gen;
        final Kind kind;
        /** この坑道が今狙っている 1 歩あたりの上下(負 = 下る)。 */
        double drift;
        /** {@link #drift} を保つ残り歩数。0 になったら水平に戻る。 */
        int driftLeft;
        /** 部屋の岩に当たって向きを変えた回数。使い切ったらそこで行き止まりにする。 */
        int guardTurns;
        /** 直前に落とした道しるべの添字(親になる)。まだ無ければ -1。 */
        int way = -1;
        /** 幹のみ: 次に枝を出すまでの歩数。 */
        int untilBranch;
        /** 幹のみ: あと何本まで枝を出してよいか。 */
        int branchesLeft;
        /** 次の枝を出す側(左右交互)。 */
        boolean flip;
        /**
         * この掘削点が通った道(局所座標)。行き止まりの部屋を置く場所が終点で見つからないとき、
         * <b>少しずつ手前へ戻って</b>探すのに使う(2026-09-20)。{@link #recent} と違って
         * 分岐しても親から引き継がない —— 自分が掘った道だけを戻りたいため。
         */
        final LongArrayList path = new LongArrayList();
        /** 直前に通った点(自分の足跡を「別の坑道」と誤認しないため)。 */
        final long[] recent = new long[10];
        int recentIndex;
        /**
         * 分岐した直後は、親の坑道が真横にあって当たり前なので間隔の決まりを見ない残り歩数。
         * これが無いと枝が生えた瞬間に「別の坑道が近い」と判定されて、その場でぐるぐる回る。
         */
        int grace;

        Digger(double x, double y, double z, double dx, double dy, double dz, int budget, Kind kind) {
            // 空き枠は「点なし」と分かる値で埋める。0 のままだと局所座標 (0,0,0) と区別がつかない。
            java.util.Arrays.fill(recent, Long.MIN_VALUE);
            this.x = x;
            this.y = y;
            this.z = z;
            this.dx = dx;
            this.dy = dy;
            this.dz = dz;
            this.budget = budget;
            this.kind = kind;
        }
    }

    private enum Kind { ADIT, BRANCH }

    /**
     * <b>Mob が歩く穴の最小の半径</b>(2026-09-20 のユーザー報告
     * 「アルボラの当たり判定的に細い通路が通れない」)。
     *
     * <p>球 1 つで掘ると、半径が √3 ≒ 1.74 を超えて初めて<b>角のマスまで届く</b> ——
     * つまり 3×3×3 がまるごと空く。これより細いと断面が<b>十字</b>になり、床にあたるマスが
     * 1×1 の飛び石になる。Minecraft の経路探索は角が塞がった斜め移動を通さないので、
     * そこで<b>1 歩も進めなくなる</b>。
     *
     * <p>側室への通路を坑道の 0.8 倍(半径 1.28)で掘っていたのが直接の原因だった。
     * 部屋を離したぶん通路が 11〜20 マスと長くなっていたので、症状がはっきり出た。
     */
    public static final double WALKABLE_RADIUS = 1.8D;
    /** 道しるべを落とす間隔(の 2 乗)。1 区間が短いほど経路探索が失敗しにくい。 */
    private static final double WAYPOINT_SPACING_SQ = 9.0D;
    /** 掘削点が暴走しないための保険(1 歩ずつの周回数の上限)。 */
    private static final int MAX_ROUNDS = 4000;
    /** 坑道ぶんの予算の割合。残りは行き止まりの部屋・女王室・核・噴出孔のために取っておく。 */
    private static final double TUNNEL_BUDGET_SHARE = 0.74D;
    /** 行き止まりの部屋を掘ってよい上限。ここから先は女王室と噴出孔のために空けておく。 */
    private static final double DEAD_END_BUDGET_SHARE = 0.92D;
    /**
     * 行き止まりの部屋を置く向き(本道の向きから何度振るか)。まっすぐ先から順に試す。
     * 行き止まりには<b>必ず</b>部屋を置きたいので、1 方向で諦めない(2026-09-20 のユーザー指定)。
     */
    /**
     * 「坑道」とみなす最短の歩数。これより短い掘削点は掘った跡がほとんど無いので、
     * 行き止まりの部屋を置く対象から外す(壁の窪みであって通路ではない)。
     */
    private static final int MIN_TUNNEL_STEPS = 6;
    /** 行き止まりの部屋を探すとき、道を何地点まで手前へ戻るか(2 歩おき)。 */
    private static final int ANCHOR_BACK = 24;
    /** 部屋の岩に当たったとき、直角に曲がって回り込んでよい回数。 */
    private static final int GUARD_TURNS = 4;
    /** 行き止まりの部屋は距離を伸ばして粘る(必ず置きたいので、途中の部屋より多く試す)。 */
    private static final int DEAD_END_TRIES = 6;
    /**
     * 行き止まりの部屋を置く向き(本道の向きから何度振るか)。まっすぐ先から順に試す。
     * 行き止まりには<b>必ず</b>部屋を置きたいので、1 方向で諦めない(2026-09-20 のユーザー指定)。
     */
    private static final double[] DEAD_END_TURNS_UNUSED_DOC = {};
    private static final double[] DEAD_END_TURNS = {
            0.0D, 0.45D, -0.45D, 0.9D, -0.9D, 1.6D, -1.6D, 2.4D, -2.4D,
    };
    /** 柱を入口より上へ伸ばしてよい高さの上限(崖の下に巣ができたときの保険)。 */
    private static final int VENT_MAX_RISE = 48;
    /** 噴出孔の柱を<b>地上からさらに何マス伸ばすか</b>(ユーザー指定 2026-09-20)。 */
    public static final int VENT_ABOVE_GROUND = 5;
    /** 坑道 1 歩ごとに脇の部屋が出る確率(実際に置けるかは {@link #chamberFits} が決める)。 */
    private static final double SIDE_CHAMBER_CHANCE = 0.08D;
    /** 部屋を置ける場所が見つからないとき、本道から離す距離を伸ばして試す回数。 */
    private static final int CHAMBER_TRIES = 4;
    /** 距離を伸ばすときの 1 回ぶん(ブロック)。 */
    private static final int CHAMBER_TRY_STEP = 3;

    private final NestTable t;
    private final RandomSource random;
    private final BlockPos origin;
    private final Plan plan;
    private final LongOpenHashSet carved = new LongOpenHashSet();
    /**
     * 坑道の芯の点を升目ごとにまとめた索引。別の坑道と<b>実際の距離</b>を測るのに使う。
     *
     * <p>2026-09-19 の版は「同じ升目に居るか」だけを見ていた。升目が 1 辺 7 マスなので、
     * <b>隣の升目に居れば 1 マス差でも見逃す</b>。そのせいで坑道が束になり、半径 4 マスの
     * 空洞ができていた(2026-09-20 に GameTest が検出)。いまは近くの升目を集めて点との距離を測る。
     */
    private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<LongArrayList> trailIndex =
            new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
    /**
     * <b>部屋のまわりに確保した岩</b>(2026-09-20)。部屋を 1 室置くたびに、その球の外側
     * {@link NestTable#chamberClearance()} マスぶんのうち<b>まだ掘られていないマス</b>を押さえる。
     * 坑道はここを掘れない。
     *
     * <p>これが要るのは、「進む先に部屋があったら向きを変える」だけでは<b>曲がりきれずに
     * 突き抜ける</b>ことがあるため —— 坑道は 1 歩 1 マスしか曲がれないのに、部屋は半径 4 以上ある。
     * 実際、枝分かれした坑道が部屋と合体して大きな空間になっていた(2026-09-20 のユーザー報告)。
     * 向きを変えるのは<b>見た目のため</b>(行き止まりだらけにしないため)で、
     * <b>貫通させない保証はこの岩の方</b>が持つ。
     */
    private final LongOpenHashSet roomGuard = new LongOpenHashSet();
    /** 坑口(ローカル座標)。側室が入口に寄らないようにするための基準。 */
    private double mouthX;
    private double mouthZ;
    /** 巣の本体の中心(ローカル座標)。枝の広がりはここから測る。 */
    private double centreX;
    private double centreZ;
    private boolean centreFound;
    /** 掘ってよい深さの下限・上限(ローカル y)。 */
    private double floorY;
    private double ceilingY;
    private final List<NestChamber> pending = new ArrayList<>();
    /** 地形の高さを教えてくれる窓口。既定は「入口と同じ高さ」。 */
    private Terrain terrain = (x, z) -> 0;
    /** 予算が尽きて止まった掘削点。掘り終わってから終点に部屋を掘る。 */
    private final List<Digger> deadEnds = new ArrayList<>();
    /**
     * これまでに作った掘削点の数。{@link NestTable#deadEndChambers()} で頭打ちにする ——
     * 掘削点は 1 つにつき行き止まりが 1 つできるので、この数を抑えることが
     * <b>「行き止まりの数 = 行き止まりの部屋の数」</b>を成り立たせる鍵になる(2026-09-20)。
     */
    private int diggersMade;
    /** 幹があと何本の枝を出す予定か。孫枝がその枠を食いつぶさないように見張る。 */
    private int trunkBranchesLeft;

    private NestLayout(BlockPos origin, RandomSource random, NestTable t) {
        this.origin = origin;
        this.random = random;
        this.t = t;
        this.plan = new Plan(origin);
    }

    /**
     * <b>地形の高さを教えてもらう窓口</b>(2026-09-20)。形の計算はこれ以外に世界を見ない ——
     * GameTest とプレビューが世界無しで同じ形を出せるようにするため。
     *
     * <p>使い道は噴出孔だけ。煙突は<b>実際の地面の高さまで</b>伸ばさないと地中で止まる
     * (2026-09-20 のユーザー報告)。
     */
    public interface Terrain {
        /** 地表の高さ(<b>水も地表として数える</b>)。噴出孔の柱をここまで伸ばす。 */
        int surfaceAt(int x, int z);

        /**
         * <b>水底</b>の高さ(水を数えない地表)。{@link #surfaceAt} より低ければ、その柱には
         * その差ぶんの水がある(2026-09-21 のユーザー報告「水中に巣を拡張してしまう」)。
         *
         * <p>既定は「水は無い」。GameTest とプレビューは水の無い世界として扱う。
         */
        default int seabedAt(int x, int z) {
            return surfaceAt(x, z);
        }
    }

    /**
     * {@code origin}(地表の入口)から巣を 1 つ設計する。地形は入口と同じ高さとみなす。
     *
     * @param seed 同じ種なら同じ形になる
     */
    public static Plan build(BlockPos origin, long seed, NestTable table) {
        return build(origin, seed, table, (x, z) -> origin.getY());
    }

    /** 地形の高さを見ながら設計する(噴出孔を実際の地表まで届かせるため)。 */
    public static Plan build(BlockPos origin, long seed, NestTable table, Terrain terrain) {
        NestLayout layout = new NestLayout(origin, RandomSource.create(seed), table);
        layout.terrain = terrain;
        layout.run();
        return layout.plan;
    }

    private void run() {
        fillPendingChambers();
        buildMound();
        digTunnels();
        digDeadEndChambers();
        ensureVentChamber();
        fillRemainingChambers();
        digQueenChamber();
        digVentShafts();
        buildLining();
    }

    // --- 側室の枠 ---

    /** 掘りたい側室の種類を、総数ぶん並べておく。 */
    private void fillPendingChambers() {
        int totalWeight = 0;
        for (NestChamber c : NestChamber.values()) {
            if (c.unique()) {
                if (c != NestChamber.QUEEN) {
                    pending.add(c);      // 噴気室は必ず 1 室
                }
            } else {
                totalWeight += c.weight();
            }
        }
        int left = Math.max(0, t.chamberTotal() - pending.size());
        if (totalWeight <= 0) {
            return;
        }
        for (NestChamber c : NestChamber.values()) {
            if (c.unique() || c.weight() <= 0) {
                continue;
            }
            int n = Math.max(1, Math.round((float) left * c.weight() / totalWeight));
            for (int i = 0; i < n; i++) {
                pending.add(c);
            }
        }
    }

    // --- 掘る ---

    private boolean tunnelBudgetSpent() {
        return carved.size() >= t.blockBudget() * TUNNEL_BUDGET_SHARE;
    }

    /** 幹が枝を 1 本出すまでの歩数。{@link NestTable#trunkBranches()} 本が幹全体に散るようにする。 */
    private int branchEvery() {
        double slope = Math.max(0.1D, (t.entranceSlope() + t.deepSlope()) * 0.5D);
        double steps = t.shaftDepth() / slope;
        return Math.max(5, (int) Math.round(steps / Math.max(1, t.trunkBranches())));
    }

    /** 幹の歩数。最下段まで下りて、さらに少し奥へ進むぶん。 */
    private int trunkBudget() {
        double slope = Math.max(0.1D, (t.entranceSlope() + t.deepSlope()) * 0.5D);
        return (int) Math.round(t.shaftDepth() / slope * 1.4D) + branchEvery() * 2;
    }

    private void digTunnels() {
        floorY = -(t.shaftDepth() + t.depthBand() / 2.0D);
        ceilingY = -5.0D;

        // 入口は縦穴ではなく、斜めに下る坑道。<b>塚の外側から始めて塚を貫く</b>ので、
        // 山の横腹に坑口が開く(塚の上に穴が空くだけ、にならないように。2026-09-19 の指摘)。
        double a0 = random.nextDouble() * Math.PI * 2.0D;
        double startOut = t.moundRadius() + 3.0D;
        mouthX = -Math.cos(a0) * startOut;
        mouthZ = -Math.sin(a0) * startOut;
        plan.mouth = local(mouthX, 0.0D, mouthZ);
        Digger adit = new Digger(mouthX, 1.0D, mouthZ,
                Math.cos(a0), -t.entranceSlope(), Math.sin(a0),
                trunkBudget(), Kind.ADIT);
        adit.untilBranch = branchEvery();
        adit.branchesLeft = Math.min(t.trunkBranches(), Math.max(0, t.deadEndChambers() - 1));
        diggersMade = 1;                          // 幹も 1 本の坑道(その最奥にも部屋が要る)
        trunkBranchesLeft = adit.branchesLeft;

        List<Digger> active = new ArrayList<>();
        active.add(adit);

        int rounds = 0;
        while (!active.isEmpty() && rounds++ < MAX_ROUNDS) {
            if (tunnelBudgetSpent()) {
                deadEnds.addAll(active);          // 打ち切られた枝も終点として扱う
                return;
            }
            List<Digger> next = new ArrayList<>();
            for (Digger d : active) {
                d.budget--;
                if (d.kind == Kind.ADIT) {
                    stepAdit(d, next);
                } else {
                    stepBranch(d, next);
                }
            }
            active = next;
        }
        deadEnds.addAll(active);
    }

    /**
     * 入口の坑道(幹)。地表の塚から<b>斜めに下っていく</b>。
     *
     * <p>傾きは浅いうちは {@link NestTable#entranceSlope()}(緩い坂)で、深くなるにつれて
     * {@link NestTable#deepSlope()} まで急になる。最下段に着いたらほぼ水平になり、そのまま奥へ伸びる。
     *
     * <p>下りながら {@link #branchEvery()} 歩ごとに枝を<b>1 本ずつ左右交互に</b>出す。
     * 一度に何本も出すと、その周りがまとめて掘り抜かれて大きな空洞になる(2026-09-19 の指摘)。
     */
    private void stepAdit(Digger d, List<Digger> next) {
        double progress = Mth.clamp((-d.y) / Math.max(1, t.shaftDepth()), 0.0D, 1.0D);
        double slope = Mth.lerp(progress, t.entranceSlope(), t.deepSlope());
        if (d.y <= -t.shaftDepth()) {
            slope = 0.08D;                                // 最下段に着いた。ここから先はほぼ水平
        }
        d.dx += (random.nextDouble() - 0.5D) * t.wander() * 0.7D;
        d.dz += (random.nextDouble() - 0.5D) * t.wander() * 0.7D;
        double flat = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
        if (flat < 1.0E-4D) {
            d.dx = 1.0D;
            flat = 1.0D;
        }
        d.dx /= flat;
        d.dz /= flat;
        d.dy = -slope;
        normalize(d);
        // 既にある部屋をよける。ただし<b>幹は止めない</b> —— 巣の背骨で、ここが途切れると
        // 奥がまるごと行けなくなる。よけきれなかったときだけ部屋のまわりの岩を押しのけて進む
        // (第 6 引数の force)。部屋を本道から離して置くようになったので、まず起きない。
        avoidChambers(d, 0.5D);
        advance(d);
        // 幹が水に当たったら<b>掘らずに 1 歩戻して向きを変える</b>。幹は止められないが、
        // 水へ突っ込むわけにもいかない。沈めて逃がすのは駄目 —— 縦に飛ぶので坑道が切れる。
        for (int away = 0; away < 4 && inWaterBall(d.x, d.y, d.z, walkRadius() + 0.5D); away++) {
            d.x -= d.dx;
            d.z -= d.dz;
            double dry = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
            double a = (random.nextBoolean() ? 1 : -1) * (Math.PI / 2.0D);
            double ca = Math.cos(a);
            double sa = Math.sin(a);
            double nx = d.dx * ca - d.dz * sa;
            double nz = d.dx * sa + d.dz * ca;
            double nl = Math.sqrt(nx * nx + nz * nz);
            d.dx = nx / nl * dry;
            d.dz = nz / nl * dry;
            d.x += d.dx;
            d.z += d.dz;
        }
        // 坑口のあいだは塚の中(地表より上)も掘って、通り抜けられる口を開ける
        boolean atMouth = d.y > -5.0D;
        carve(d.x, d.y, d.z, walkRadius() + 0.5D, atMouth ? t.moundHeight() + 2 : 0, true);
        markTrail(d);
        if (atMouth) {
            plan.openings.add(local(d.x, d.y, d.z));      // ここは外殻で塞がない
        }
        dropWaypoint(d);                                  // 3 マスおきに道しるべを落とす

        if (!centreFound && d.y <= -t.shaftDepth() * 0.5D) {
            centreFound = true;                           // ここから先が巣の本体
            centreX = d.x;
            centreZ = d.z;
            plan.centre = local(d.x, d.y, d.z);
        }
        if (d.y < ceilingY - 1.0D && d.branchesLeft > 0 && --d.untilBranch <= 0) {
            d.untilBranch = branchEvery();
            d.branchesLeft--;
            trunkBranchesLeft--;
            spawnBranch(next, d, t.galleryBudget());
        }
        if (d.y < ceilingY - 3.0D && random.nextDouble() < SIDE_CHAMBER_CHANCE && chamberAllowed()) {
            makeChamber(d);
        }
        if (d.budget > 0) {
            next.add(d);
        } else {
            deadEnds.add(d);
        }
    }

    /**
     * 枝。水平とはかぎらず、生えるときに選んだ傾きでゆるやかに上り下りする。
     * 傾きは {@link NestTable#maxTunnelSlope()} までなので、縦穴にはならず歩いて下れる。
     */
    private void stepBranch(Digger d, List<Digger> next) {
        double w = t.wander();
        d.dx += (random.nextDouble() - 0.5D) * w;
        d.dy += (random.nextDouble() - 0.5D) * w * 0.5D;
        d.dz += (random.nextDouble() - 0.5D) * w;

        if (d.driftLeft > 0) {
            d.driftLeft--;
            d.dy = d.dy * 0.5D + d.drift * 0.5D;             // 狙いの坂へ寄せる
        } else if (random.nextDouble() < t.descendChance()) {
            d.drift = -t.maxTunnelSlope() * 0.8D;            // ここから斜めに下り始める
            d.driftLeft = 6 + random.nextInt(10);
        } else {
            d.dy *= 0.7D;                                    // 坂が終わったら水平に戻る
        }

        if (d.y > ceilingY) {
            d.dy -= 0.8D;                                    // 地表を突き抜けない
        }
        if (d.y < floorY) {
            d.dy += 0.6D;                                    // 決めた深さより下は掘らない
        }
        // 水平の広がりを巣の中心から spread 以内に収める
        double ox = d.x - centreX;
        double oz = d.z - centreZ;
        double hd = Math.sqrt(ox * ox + oz * oz);
        if (hd > t.spread()) {
            d.dx -= ox / hd * 0.55D;
            d.dz -= oz / hd * 0.55D;
        }
        normalize(d);
        // 坑道が垂直にならないよう傾きを抑える(ユーザー指定 2026-09-19)
        clampSlope(d, t.maxTunnelSlope());
        // 別の坑道に近づきすぎるなら、掘る前に向きを変える(坑道どうしが密集しないように)
        avoidOtherTunnels(d);
        // 既にある部屋を貫かない(貫くと部屋と坑道がひと続きの大空間になる。2026-09-20 の指摘)
        avoidChambers(d, 1.0D);
        advance(d);
        // <b>水の中には掘らない</b>(2026-09-21)。湖や海を掘り抜くと、外殻を張っても
        // 水底にぽっかり空いた巣になり、水を避けるアルボラが切羽に近づけなくなる。
        if (inWaterBall(d.x, d.y, d.z, walkRadius() + 0.5D)) {
            deadEnds.add(d);
            return;
        }
        // よけきれずに<b>部屋のまわりの岩</b>へ突き当たった。掘らずに止めるので、
        // 部屋を貫いた先に切れ端の空洞が残ることはない(2026-09-20)。
        if (hitsRoomGuard(d.x, d.y, d.z, walkRadius() + 0.5D)) {
            // <b>数回までは直角に曲がって回り込む</b>(2026-09-20 の追加)。即座に終わらせると、
            // 生えたばかりの枝が 1 歩も掘らずに消えて<b>行き止まりの部屋が 1 室減る</b> ——
            // 巣を小さくして坑道が密になったぶん、これが目に見えて効いていた。
            if (d.guardTurns++ < GUARD_TURNS) {
                d.x -= d.dx;                          // 踏み出した 1 歩を戻す
                d.y -= d.dy;
                d.z -= d.dz;
                double a = (random.nextBoolean() ? 1 : -1) * (Math.PI / 2.0D);
                double ca = Math.cos(a);
                double sa = Math.sin(a);
                double nx = d.dx * ca - d.dz * sa;
                double nz = d.dx * sa + d.dz * ca;
                d.dx = nx;
                d.dz = nz;
                if (d.budget > 0) {
                    next.add(d);
                } else {
                    deadEnds.add(d);
                }
                return;
            }
            deadEnds.add(d);
            return;
        }
        carve(d.x, d.y, d.z, walkRadius() + random.nextDouble() * 0.45D);
        markTrail(d);
        dropWaypoint(d);                                  // 枝にも道しるべを落とす(2026-09-20)

        // 孫枝。<b>幹が出す予定の枝のぶんは空けておく</b> —— 孫枝が先に枠を使うと、
        // 幹の枝が生えずに巣が片側へ寄る。
        if (d.gen < 3 && random.nextDouble() < t.branchChance()
                && diggersMade + Math.max(0, trunkBranchesLeft) < t.deadEndChambers()) {
            spawnBranch(next, d, Math.max(10, (int) (d.budget * 0.6D)));
        }
        // 脇の部屋
        if (random.nextDouble() < SIDE_CHAMBER_CHANCE && chamberAllowed()) {
            makeChamber(d);
        }

        if (d.budget > 0) {
            next.add(d);
        } else {
            deadEnds.add(d);
        }
    }

    /** 本道から 60〜120 度ほど振った枝を 1 本出す。上る枝・水平な枝・下る枝が混じる。 */
    private void spawnBranch(List<Digger> next, Digger from, int budget) {
        if (diggersMade >= t.deadEndChambers()) {
            return;                               // 行き止まりの部屋を置ける数までしか枝を出さない
        }
        diggersMade++;
        double base = Math.atan2(from.dz, from.dx);
        double turn = (Math.PI / 3.0D) + random.nextDouble() * (Math.PI / 3.0D);
        double a = base + (from.flip ? turn : -turn);
        from.flip = !from.flip;

        double roll = random.nextDouble();
        double drift;
        if (roll < 0.45D) {
            drift = -t.maxTunnelSlope() * 0.8D;              // 斜めに下る
        } else if (roll < 0.80D) {
            drift = 0.0D;                                    // 水平
        } else {
            drift = t.maxTunnelSlope() * 0.6D;               // 斜めに上る
        }
        Digger b = new Digger(from.x, from.y, from.z,
                Math.cos(a), drift, Math.sin(a), budget, Kind.BRANCH);
        b.gen = from.gen + 1;
        b.drift = drift;
        b.driftLeft = 6 + random.nextInt(10);
        b.way = from.way;                             // 道しるべは分かれ道の親から伸ばす
        b.grace = t.tunnelSpacing() + 2;              // 親から離れるまで間隔の決まりを見ない
        System.arraycopy(from.recent, 0, b.recent, 0, from.recent.length);
        b.recentIndex = from.recentIndex;
        normalize(b);
        clampSlope(b, t.maxTunnelSlope());
        next.add(b);
    }

    // --- 側室 ---

    /**
     * 側室の枠がまだ残っていて、掘り進み具合から見て今出してよいか(場所の判定は
     * {@link #digChamberAt} が最終位置に対して行う)。この段階的な開放が無いと幹の浅いところで
     * 枠を使い切り、部屋が入口寄りに偏る(2026-09-19 にプレビューで判明)。
     */
    private boolean chamberAllowed() {
        if (pending.isEmpty()) {
            return false;
        }
        double progress = carved.size() / (t.blockBudget() * TUNNEL_BUDGET_SHARE);
        return plan.chambers.size() <= t.chamberTotal() * progress + 1;
    }

    /**
     * 2 つの部屋の隔たり。<b>縦は 1.6 倍で数える</b> —— 上下に離れた部屋は床と天井を挟むので、
     * 水平に同じ距離だけ離れているより「別の部屋」に見えるため。
     * 判定と検証が食い違わないよう、テストもこの値を使う。
     */
    public static double chamberSeparation(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = (a.getY() - b.getY()) * 1.6D;
        double dz = a.getZ() - b.getZ();
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * 本道の横へそれて、行き止まりの部屋を掘る(道の<b>脇</b>の部屋)。ここで決めるのは
     * <b>どちらへそれるか</b>だけで、どれだけ離すかは {@link #digChamberAt} が部屋の大きさから決める。
     */
    private void makeChamber(Digger d) {
        // 進行方向の成分を抜いて「真横」へ向ける
        double a = random.nextDouble() * Math.PI * 2.0D;
        double sx = Math.cos(a);
        double sz = Math.sin(a);
        double dot = sx * d.dx + sz * d.dz;
        sx -= d.dx * dot;
        sz -= d.dz * dot;
        double len = Math.sqrt(sx * sx + sz * sz);
        if (len < 1.0E-4D) {
            sx = 1.0D;
            sz = 0.0D;
            len = 1.0D;
        }
        sx /= len;
        sz /= len;
        // 坑口から外向きの側を選ぶ(内側へそれて入口に近づくのを防ぐ)
        if (sx * (d.x - mouthX) + sz * (d.z - mouthZ) < 0.0D) {
            sx = -sx;
            sz = -sz;
        }
        digChamberAt(d, sx, 0.0D, sz);
    }

    /**
     * 掘り終わった坑道の<b>終点</b>に部屋を作る。アリの巣の袋小路にあたる部分で、
     * 「道の終わりには必ず何かある」形にするためのもの(ユーザー指定 2026-09-19)。
     *
     * <p>2026-09-20 に<b>必ず置く</b>ようにした(ユーザー指定「坑道の最奥に必ず卵室か貯蔵室を」)。
     * 変えたのは 3 点:
     * <ul>
     *   <li>種類は<b>孵化室か貯蔵室に限る</b>。奥から順に {@link NestTable#deadEndHatcheries()} 室を
     *   孵化室、残りを貯蔵室にする。坑道の途中の部屋は今までどおり全種類から選ぶ。</li>
     *   <li>枠を {@code chamberTotal}(坑道の途中のぶん)と<b>分けた</b>。途中の部屋を掘り尽くして
     *   終点に何も置けない、ということが起きなくなる。</li>
     *   <li>まっすぐ先で置けなければ<b>向きを振って 9 方向まで</b>試す。1 方向で諦めると、
     *   隣の部屋の岩に阻まれた終点が空のまま残る。</li>
     * </ul>
     *
     * <p>そもそも<b>行き止まりの数そのものを {@link NestTable#deadEndChambers()} で抑えて</b>あるので
     * ({@link #diggersMade})、置く先が足りなくなることがない。
     */
    private void digDeadEndChambers() {
        List<Digger> ends = new ArrayList<>(deadEnds);
        // 奥から順に。孵化室を巣の深いところへ寄せるためと、予算が尽きたときに
        // 切り捨てられるのが入口寄りの終点になるようにするため。
        ends.sort((a, b) -> Double.compare(remoteness(b.x, b.y, b.z), remoteness(a.x, a.y, a.z)));
        int placed = 0;
        for (Digger d : ends) {
            if (placed >= t.deadEndChambers()
                    || carved.size() >= t.blockBudget() * DEAD_END_BUDGET_SHARE) {
                return;
            }
            // <b>掘っていない掘削点は坑道ではない</b>。枝は生えた直後に部屋のまわりの岩へ
            // 当たって 1 歩も掘れずに終わることがあり、そこには通路が無いので部屋も要らない
            // (2026-09-20 に GameTest が検出)。数歩ぶんの窪みも同じ扱いにする。
            if (d.path.size() < MIN_TUNNEL_STEPS) {
                continue;
            }
            NestChamber kind = placed < t.deadEndHatcheries() ? NestChamber.HATCHERY : NestChamber.STORAGE;
            double flat = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
            if (flat < 1.0E-4D) {
                d.dx = 1.0D;
                d.dz = 0.0D;
                flat = 1.0D;
            }
            NestChamber made = null;
            // 3 段構えで探す。行き止まりには<b>必ず</b>部屋が要るので、1 方向で諦めない。
            //   1. 終点から、少し戻った地点も含めて(anchorsFor)、9 方向 × 6 距離
            //   2. それでも駄目なら間隔を chamberClearance + 1 まで詰めてもう 1 周
            // 詰めても clearOfHollow は効いたままなので、部屋どうしが融けることはない。
            int[] gaps = {t.chamberGap(), t.chamberClearance() + 1};
            List<double[]> anchors = anchorsFor(d);
            found:
            for (int gap : gaps) {
                for (double[] anchor : anchors) {
                    for (double turn : DEAD_END_TURNS) {
                        double ca = Math.cos(turn);
                        double sa = Math.sin(turn);
                        // そらす向きは<b>水平にする</b>。坑道の坂をそのまま延ばすと、上る枝の終点から
                        // 地表へ突き抜ける向きになって 1 か所も置けない(2026-09-20 に GameTest が検出)。
                        double sx = (d.dx * ca - d.dz * sa) / flat;
                        double sz = (d.dx * sa + d.dz * ca) / flat;
                        if (digChamberAt(anchor[0], anchor[1], anchor[2], sx, 0.0D, sz, kind,
                                gap, DEAD_END_TRIES)) {
                            made = kind;
                            placed++;
                            break found;
                        }
                    }
                }
            }
            // 置けなかった行き止まりも記録する。取りこぼしはテストで見つけたいので隠さない。
            plan.tunnelEnds.add(new TunnelEnd(local(d.x, d.y, d.z), made));
        }
        // 行き止まりが足りなかったぶん(枝が短くて坑道にならなかった等)は貯蔵室として
        // 坑道の脇に振り替える。<b>部屋の数を坑道の運に左右させない</b>ため(2026-09-20)。
        for (int i = placed; i < t.deadEndChambers(); i++) {
            pending.add(NestChamber.STORAGE);
        }
    }

    /**
     * <b>置けずに余った部屋を坑道の脇へ補充する</b>(2026-09-20 のユーザー指定
     * 「巣を小さくしたいが部屋の数は変えなくてよい」)。
     *
     * <p>坑道の途中の部屋は「1 歩ごとに 8% で抽選」なので、坑道が短くなると<b>抽選の回数ごと減る</b>。
     * 行き止まりの部屋も、枝が短くて坑道にならなければ置けない。どちらも<b>坑道の運</b>で数がぶれる。
     * ここで最後にまとめて置き直すことで、巣の大きさと部屋の数を切り離す。
     *
     * <p>置き方は噴気室の後付け({@link #ensureVentChamber})と同じ —— 掘った空洞を
     * 飛ばし飛ばしに見て、その<b>脇</b>に部屋と短い通路を掘る。
     */
    private void fillRemainingChambers() {
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 64) {
            if (carved.size() >= t.blockBudget() * DEAD_END_BUDGET_SHARE) {
                return;
            }
            NestChamber kind = pending.remove(0);
            placeBesideAnyTunnel(kind);               // 置けなければその 1 室は諦める
        }
    }

    /** 掘った空洞のどこかの脇に {@code kind} を 1 室置く。置けたら true。 */
    private boolean placeBesideAnyTunnel(NestChamber kind) {
        double r = kind.radius();
        double corridor = walkRadius();
        for (int i = 0; i < plan.hollow.size(); i += 7) {
            long key = plan.hollow.getLong(i);
            BlockPos anchor = new BlockPos(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            if (anchor.getY() > origin.getY() + ceilingY) {
                continue;                             // 坑口のあたりには置かない
            }
            BlockPos centre = besideTunnel(anchor, r);
            if (centre == null) {
                continue;
            }
            double ax = anchor.getX() - origin.getX();
            double ay = anchor.getY() - origin.getY();
            double az = anchor.getZ() - origin.getZ();
            double tx = centre.getX() - origin.getX();
            double tz = centre.getZ() - origin.getZ();
            int steps = Math.max(1, Mth.ceil(Math.sqrt((tx - ax) * (tx - ax) + (tz - az) * (tz - az))));
            for (int step = 1; step <= steps; step++) {
                double f = (double) step / steps;
                carve(ax + (tx - ax) * f, ay, az + (tz - az) * f, corridor);
            }
            double rr = r + random.nextDouble() * 0.6D;
            carve(tx, ay, tz, rr);
            plan.chambers.add(new Chamber(kind, centre, Mth.floor(rr)));
            linkChamber(ax, ay, az, (tx - ax) / steps, 0.0D, (tz - az) / steps, steps, centre);
            reserveRoom(centre, rr);
            return true;
        }
        return false;
    }

    /**
     * 行き止まりの部屋を探す足がかり —— 終点そのものと、そこから<b>少しずつ戻った地点</b>
     * (掘削点が覚えている直前の足跡)。新しい順に並べる。
     */
    private List<double[]> anchorsFor(Digger d) {
        List<double[]> out = new ArrayList<>();
        double reach = walkRadius() + 1.0D;
        // 押さえた岩の中にある地点は足がかりにならない —— そこから掘り出す通路は
        // 最初の 1 歩で岩に当たって途切れ、部屋が孤立する(2026-09-20 に GameTest が検出)。
        if (!hitsRoomGuard(d.x, d.y, d.z, reach)) {
            out.add(new double[] {d.x, d.y, d.z});
        }
        for (int i = d.path.size() - 1, back = 0; i >= 0 && back < ANCHOR_BACK; i -= 2, back++) {
            long p = d.path.getLong(i);
            double x = BlockPos.getX(p);
            double y = BlockPos.getY(p);
            double z = BlockPos.getZ(p);
            if (!hitsRoomGuard(x, y, z, reach)) {
                out.add(new double[] {x, y, z});
            }
        }
        return out;
    }

    /**
     * {@code from}(掘削点)から向き {@code (sx,sy,sz)} へそれて部屋を 1 室置き、そこまでの通路も掘る。
     * 置けなければ枠を消費せずに戻る。
     *
     * <p><b>部屋の球がまるごと岩の中に収まり、まわりに {@link NestTable#chamberClearance()} マスの
     * 岩が残る</b>ところにしか置かない(2026-09-20)。そのぶん本道から遠くなるので、
     * 離す距離は部屋の半径から計算し、届かなければ {@link #CHAMBER_TRY_STEP} マスずつ伸ばして
     * {@link #CHAMBER_TRIES} 回まで試す。
     *
     * <p>以前は本道のすぐ脇(3〜5 マス)へ置き、判定も「芯の 55% が掘られていないか」だけだった。
     * <b>縁が坑道に重なるのは通ってしまう</b>ので、そこで部屋と坑道がひと続きの大きな空間になっていた
     * (2026-09-20 のユーザー報告)。
     */
    private void digChamberAt(Digger from, double sx, double sy, double sz) {
        // 種類は先に決める —— 離す距離が部屋の半径で決まるので、半径が分からないと場所を探せない。
        // 奥まり具合は「だいたいこのあたり」で見ておく(本道からそれる距離ぶんの差は誤差)。
        NestChamber kind = takePending(remoteness(from.x + sx * 10.0D, from.y + sy * 10.0D,
                from.z + sz * 10.0D));
        if (kind == null) {
            return;
        }
        if (!digChamberAt(from, sx, sy, sz, kind)) {
            pending.add(kind);                        // 置けなかった。枠は戻して次の機会に回す
        }
    }

    /**
     * 種類を指定して部屋を 1 室置く。置けたら true。枠({@link #pending})には触らない ——
     * 行き止まりの部屋は枠とは別勘定なので、呼ぶ側が後始末を決める。
     */
    private boolean digChamberAt(Digger from, double sx, double sy, double sz, NestChamber kind) {
        return digChamberAt(from.x, from.y, from.z, sx, sy, sz, kind);
    }

    /** 掘削点ではなく座標から掘る版(行き止まりの部屋を少し戻った地点から探すのに使う)。 */
    private boolean digChamberAt(double fx, double fy, double fz,
                                 double sx, double sy, double sz, NestChamber kind) {
        return digChamberAt(fx, fy, fz, sx, sy, sz, kind, t.chamberGap(), CHAMBER_TRIES);
    }

    /** 間隔と試す回数を指定する版(行き止まりの部屋を粘って置くのに使う)。 */
    private boolean digChamberAt(double fx, double fy, double fz,
                                 double sx, double sy, double sz, NestChamber kind,
                                 int gap, int tryCount) {
        double radius = kind.radius();
        int off = Mth.ceil(radius + t.chamberClearance() + walkRadius()) + 2;
        double corridor = walkRadius();
        for (int tries = 0; tries < tryCount; tries++, off += CHAMBER_TRY_STEP) {
            double x = fx + sx * off;
            double y = fy + sy * off;
            double z = fz + sz * off;
            if (y > ceilingY || y < floorY - 6.0D) {
                continue;                             // 地表を突き抜ける / 決めた深さより下
            }
            BlockPos centre = local(x, y, z);
            if (!chamberFits(centre, radius, gap)) {
                continue;
            }
            if (!corridorClear(fx, fy, fz, sx, sy, sz, off, corridor)) {
                continue;                             // 通路が別の部屋の岩を突っ切ってしまう
            }
            if (!corridorDry(fx, fy, fz, sx, sy, sz, off, corridor)) {
                continue;                             // 通路が湖を突っ切ってしまう
            }
            double cx = fx;
            double cy = fy;
            double cz = fz;
            for (int step = 0; step < off; step++) {
                cx += sx;
                cy += sy;
                cz += sz;
                carve(cx, cy, cz, corridor);
            }
            double r = radius + random.nextDouble() * 0.6D;
            carve(x, y, z, r);
            plan.chambers.add(new Chamber(kind, centre, Mth.floor(r)));
            linkChamber(fx, fy, fz, sx, sy, sz, off, centre);
            // 岩を押さえるのは<b>掘ったあと</b>。先に押さえると自分の通路が掘れなくなる。
            reserveRoom(centre, r);
            return true;
        }
        return false;
    }

    /**
     * 噴気室は 1 つの巣に必ず 1 室要る。掘る途中で「いちばん奥まったところ」に届かないまま
     * 予算が尽きると置かれずに終わるので({@link NestChamber#VENT} の狙いの奥まり具合は 0.95)、
     * 残っていたら掘り終わった空洞の中から<b>いちばん奥のマス</b>を選んで置く
     * (2026-09-19 に GameTest が検出)。
     */
    private void ensureVentChamber() {
        if (!pending.remove(NestChamber.VENT)) {
            return;                                   // 既に置けている
        }
        double r = NestChamber.VENT.radius();
        double bestScore = -Double.MAX_VALUE;
        BlockPos bestAnchor = null;
        BlockPos bestCentre = null;
        // いちばん奥まった空洞の<b>脇</b>へ置く。空洞そのものを中心にすると坑道を丸ごと飲み込んで
        // ひと続きの大空間になる(2026-09-20 の指摘。chamberFits の芯の判定にも引っかかる)。
        for (int i = 0; i < plan.hollow.size(); i += 3) {
            long key = plan.hollow.getLong(i);
            double lx = BlockPos.getX(key) - origin.getX();
            double ly = BlockPos.getY(key) - origin.getY();
            double lz = BlockPos.getZ(key) - origin.getZ();
            double score = remoteness(lx, ly, lz);
            if (score <= bestScore) {
                continue;
            }
            BlockPos anchor = new BlockPos(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            BlockPos centre = besideTunnel(anchor, r);
            if (centre == null) {
                continue;
            }
            bestScore = score;
            bestAnchor = anchor;
            bestCentre = centre;
        }
        if (bestCentre == null) {
            return;                                   // 置ける隙間が無い(極端に小さい巣)
        }
        // 本道から部屋までの通路
        double ax = bestAnchor.getX() - origin.getX();
        double ay = bestAnchor.getY() - origin.getY();
        double az = bestAnchor.getZ() - origin.getZ();
        double tx = bestCentre.getX() - origin.getX();
        double tz = bestCentre.getZ() - origin.getZ();
        int steps = Math.max(1, Mth.ceil(Math.sqrt((tx - ax) * (tx - ax) + (tz - az) * (tz - az))));
        for (int s = 1; s <= steps; s++) {
            double f = (double) s / steps;
            carve(ax + (tx - ax) * f, ay, az + (tz - az) * f, walkRadius());
        }
        carve(tx, ay, tz, r);
        plan.chambers.add(new Chamber(NestChamber.VENT, bestCentre, Mth.floor(r)));
        linkChamber(ax, ay, az, (tx - ax) / steps, 0.0D, (tz - az) / steps, steps, bestCentre);
        reserveRoom(bestCentre, r);
    }

    /**
     * その位置に部屋を置けるか。見るのは 3 つ —— 坑口から離れているか、他の部屋と離れているか、
     * そして<b>まわりが岩か</b>。
     *
     * <p>2026-09-20 に 2 つめと 3 つめを作り直した(「まだ坑道と部屋が合体する」というユーザー報告)。
     * <ul>
     *   <li>部屋どうしの間隔は<b>両方の半径を足して</b>測る。中心どうしの距離だけで見ていたので、
     *   大きい部屋(女王室は半径 6.5)が並ぶと壁が 1 マスも残らず 1 つの空間になっていた。</li>
     *   <li>まわりの岩は<b>部屋の球そのもの + {@link NestTable#chamberClearance()} マス</b>に
     *   掘った跡が 1 つも無いことを求める。以前は芯の 55% しか見ておらず、
     *   <b>縁が坑道に重なるのは通ってしまう</b>ので、そこが大きな空間になっていた。
     *   部屋へ入る道は {@link #digChamberAt} が別に 1 本掘るので、重ねる必要はない。</li>
     * </ul>
     */
    private boolean chamberFits(BlockPos centre, double radius) {
        return chamberFits(centre, radius, t.chamberGap());
    }

    /**
     * 間隔を指定する版。行き止まりの部屋は<b>必ず</b>置きたいので、狙いの間隔で置けなかったときだけ
     * {@code chamberClearance + 1} まで詰めて置き直す(2026-09-20)。
     * 詰めても {@link #clearOfHollow} は効いたままなので、部屋どうしが融けることはない。
     */
    private boolean chamberFits(BlockPos centre, double radius, int gap) {
        double lx = (centre.getX() - origin.getX()) - mouthX;
        double lz = (centre.getZ() - origin.getZ()) - mouthZ;
        if (Math.sqrt(lx * lx + lz * lz) < gap + radius) {
            return false;
        }
        for (Chamber c : plan.chambers) {
            if (chamberSeparation(c.pos(), centre) < gap + c.type().radius() + radius) {
                return false;
            }
        }
        if (inWaterAround(centre, radius + 1.0D)) {
            return false;                             // 水の中(湖や海の底)には置かない
        }
        return clearOfHollow(centre, radius + t.chamberClearance());
    }

    /** 部屋の球が水に掛かっていないか。 */
    private boolean inWaterAround(BlockPos centre, double radius) {
        return inWaterBall(centre.getX() - origin.getX(), centre.getY() - origin.getY(),
                centre.getZ() - origin.getZ(), Mth.ceil(radius));
    }

    /**
     * {@code (x,y,z)} から向き {@code (sx,sy,sz)} へ {@code steps} 歩ぶんの通路が水に掛からないか。
     * 部屋そのものが岸に収まっていても、そこへ引く通路が湖を突っ切ることがある。
     */
    private boolean corridorDry(double x, double y, double z,
                                double sx, double sy, double sz, int steps, double r) {
        for (int step = 1; step <= steps; step++) {
            if (inWaterBall(x + sx * step, y + sy * step, z + sz * step, r + 0.5D)) {
                return false;
            }
        }
        return true;
    }

    /**
     * {@code anchor}(坑道のマス)の<b>脇</b>に半径 {@code r} の部屋を置ける場所を探す。
     * 8 方向 × 距離を伸ばしながら試して、最初に通ったところを返す。無ければ null。
     */
    @Nullable
    private BlockPos besideTunnel(BlockPos anchor, double r) {
        int off = Mth.ceil(r + t.chamberClearance() + walkRadius()) + 2;
        double corridor = walkRadius();
        for (int tries = 0; tries < CHAMBER_TRIES; tries++, off += CHAMBER_TRY_STEP) {
            for (int i = 0; i < 8; i++) {
                double a = i * Math.PI / 4.0D;
                BlockPos centre = anchor.offset((int) Math.round(Math.cos(a) * off), 0,
                        (int) Math.round(Math.sin(a) * off));
                if (!chamberFits(centre, r)) {
                    continue;
                }
                if (!corridorClear(anchor.getX() - origin.getX(), anchor.getY() - origin.getY(),
                        anchor.getZ() - origin.getZ(), Math.cos(a), 0.0D, Math.sin(a), off, corridor)) {
                    continue;
                }
                if (!corridorDry(anchor.getX() - origin.getX(), anchor.getY() - origin.getY(),
                        anchor.getZ() - origin.getZ(), Math.cos(a), 0.0D, Math.sin(a), off, corridor)) {
                    continue;
                }
                return centre;
            }
        }
        return null;
    }

    /**
     * {@code centre} を中心に半径 {@code radius} の球の中に、既に掘ったマスが 1 つも無いか。
     *
     * <p>2026-09-20 に {@code coreIsSolid}(芯の 55% だけを見て 10% までの掘り残しを許す)から
     * 差し替えたもの。芯だけを見ていたので部屋の縁が坑道に重なるのは通ってしまい、
     * そこで部屋と坑道がひと続きの大きな空間になっていた。
     */
    private boolean clearOfHollow(BlockPos centre, double radius) {
        int r = Mth.ceil(radius);
        double rr = radius * radius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    if (carved.contains(BlockPos.asLong(centre.getX() + dx, centre.getY() + dy,
                            centre.getZ() + dz))) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /**
     * 部屋のまわりの岩を押さえる({@link #roomGuard})。既に掘ってあるマス —— つまりそこへ来た通路 ——
     * は押さえないので、入口だけは開いたまま残る。
     */
    private void reserveRoom(BlockPos centre, double radius) {
        double keep = radius + t.chamberClearance();
        int r = Mth.ceil(keep);
        double rr = keep * keep;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    long key = BlockPos.asLong(centre.getX() + dx, centre.getY() + dy,
                            centre.getZ() + dz);
                    if (!carved.contains(key)) {
                        roomGuard.add(key);
                    }
                }
            }
        }
    }

    /** 巣ローカル座標 {@code (x,y,z)} を中心にした半径 {@code r} の球が、押さえた岩に掛かるか。 */
    private boolean hitsRoomGuard(double x, double y, double z, double r) {
        if (roomGuard.isEmpty()) {
            return false;
        }
        int ri = Mth.ceil(r);
        int cx = origin.getX() + Mth.floor(x + 0.5D);
        int cy = origin.getY() + Mth.floor(y + 0.5D);
        int cz = origin.getZ() + Mth.floor(z + 0.5D);
        double rr = r * r;
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    if (roomGuard.contains(BlockPos.asLong(cx + dx, cy + dy, cz + dz))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * {@code (x,y,z)} から向き {@code (sx,sy,sz)} へ {@code steps} 歩ぶんの通路が、
     * 別の部屋の岩を突っ切らないか。突っ切る通路を掘ると、その部屋に<b>2 つめの穴</b>が開いて
     * 坑道とひと続きになる。
     */
    private boolean corridorClear(double x, double y, double z,
                                  double sx, double sy, double sz, int steps, double r) {
        for (int s = 1; s <= steps; s++) {
            if (hitsRoomGuard(x + sx * s, y + sy * s, z + sz * s, r)) {
                return false;
            }
        }
        return true;
    }

    /** 奥まり具合にいちばん合う種類を枠から取り出す。 */
    @Nullable
    private NestChamber takePending(double remote) {
        if (pending.isEmpty()) {
            return null;
        }
        int bestIndex = 0;
        double best = Double.MAX_VALUE;
        for (int i = 0; i < pending.size(); i++) {
            double diff = Math.abs(pending.get(i).remoteness() - remote);
            if (diff < best) {
                best = diff;
                bestIndex = i;
            }
        }
        return pending.remove(bestIndex);
    }

    /** 坑口からの奥まり具合(0 = 入口寄り、1 = 最奥)。深さと水平距離の両方を見る。 */
    private double remoteness(double x, double y, double z) {
        double depth = (-y) / Math.max(1, t.shaftDepth() + t.depthBand());
        double ox = x - mouthX;
        double oz = z - mouthZ;
        double far = Math.sqrt(ox * ox + oz * oz) / Math.max(1, t.spread() * 2);
        return Mth.clamp(Math.max(depth * 0.9D, far * 0.85D), 0.0D, 1.0D);
    }

    /**
     * 地表に盛る塚。過繫茂しているブロックの小さな山で、この横腹から入口の坑道が下りていく。
     * 巣を見つける手がかりになるので、必ず地表に出す(ユーザー指定 2026-09-19)。
     */
    private void buildMound() {
        int r = t.moundRadius();
        int h = t.moundHeight();
        if (r <= 0 || h <= 0) {
            return;
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt((double) dx * dx + (double) dz * dz);
                if (dist > r) {
                    continue;
                }
                // 縁がなだらかに落ちるドーム
                int top = (int) Math.round(h * Math.cos(dist / r * Math.PI * 0.5D));
                for (int y = 0; y <= top; y++) {
                    plan.mound.add(BlockPos.asLong(origin.getX() + dx, origin.getY() + y,
                            origin.getZ() + dz));
                }
            }
        }
    }

    /** 女王室と核: いちばん奥まった側室の、さらに先へ掘る。 */
    private void digQueenChamber() {
        Chamber anchor = null;
        double bestScore = -Double.MAX_VALUE;
        for (Chamber c : plan.chambers) {
            double lx = (c.pos().getX() - origin.getX()) - mouthX;
            double ly = c.pos().getY() - origin.getY();
            double lz = (c.pos().getZ() - origin.getZ()) - mouthZ;
            double score = Math.sqrt(lx * lx + lz * lz) / Math.max(1, t.spread() * 2)
                    + (-ly) / Math.max(1, t.shaftDepth() + t.depthBand());
            if (score > bestScore) {
                bestScore = score;
                anchor = c;
            }
        }
        if (anchor == null) {
            return;
        }
        double ax = anchor.pos().getX() - origin.getX();
        double ay = anchor.pos().getY() - origin.getY();
        double az = anchor.pos().getZ() - origin.getZ();
        double a = Math.atan2(az - mouthZ, ax - mouthX);
        double r = NestChamber.QUEEN.radius();
        double corridor = walkRadius();
        // 女王室は巣でいちばん大きい(半径 6.5)。支えにした部屋から<b>両方の半径ぶん</b>離す ——
        // 以前は 9 歩しか離していなかったので、隣の部屋と重なってひと続きの大空間になっていた
        // (2026-09-20 のユーザー報告)。まっすぐ奥で置けなければ、角度と距離を変えて探す。
        int base = Mth.ceil(r + anchor.radius() + t.chamberClearance()) + 3;
        double angle = a;
        int steps = base;
        boolean found = false;
        for (int extra = 0; extra <= 12 && !found; extra += CHAMBER_TRY_STEP) {
            for (int k = 0; k < 5 && !found; k++) {
                double turn = ((k + 1) / 2) * (Math.PI / 6.0D) * (k % 2 == 1 ? 1.0D : -1.0D);
                double ang = a + turn;
                int len = base + extra;
                double px = ax + Math.cos(ang) * len;
                double py = ay - 0.35D * len;
                double pz = az + Math.sin(ang) * len;
                if (py < floorY - 8.0D) {
                    continue;
                }
                if (!chamberFits(local(px, py, pz), r)) {
                    continue;
                }
                if (!corridorClear(ax, ay, az, Math.cos(ang), -0.35D, Math.sin(ang), len, corridor)) {
                    continue;
                }
                if (!corridorDry(ax, ay, az, Math.cos(ang), -0.35D, Math.sin(ang), len, corridor)) {
                    continue;
                }
                angle = ang;
                steps = len;
                found = true;
            }
        }
        // 見つからなくても<b>女王室は必ず置く</b>(巣の中身がここに乗っているため)。
        // その場合だけ岩を押しのける。base まで離してあるので隣と重なることはまず無い。
        double x = ax;
        double y = ay;
        double z = az;
        for (int s = 0; s < steps; s++) {
            x += Math.cos(angle);
            z += Math.sin(angle);
            y -= 0.35D;
            carve(x, y, z, corridor, 0, !found);
        }
        carve(x, y, z, r, 0, !found);
        BlockPos queen = local(x, y, z);
        plan.chambers.add(new Chamber(NestChamber.QUEEN, queen, Mth.floor(r)));
        linkChamber(ax, ay, az, Math.cos(angle), -0.35D, Math.sin(angle), steps, queen);

        double nx = x + Math.cos(angle) * (r - 1.0D);
        double nz = z + Math.sin(angle) * (r - 1.0D);
        carve(nx, y, nz, 1.6D, 0, true);
        plan.corePos = local(nx, y, nz);
        reserveRoom(queen, r);
    }

    /**
     * 噴出孔: 噴気室から地上へ最大 3 本の<b>柱</b>を立てる。
     *
     * <p>2026-09-20 にユーザー指定で<b>中が空洞の煙突から中身の詰まった柱へ</b>作り直した。
     * 掘るのではなく詰めるので {@link Plan#ventColumn} に別に積む —— 空洞ではないので
     * 壁({@code lining})も外殻({@code openings})も要らない。
     *
     * <p>高さは<b>地面よりさらに {@link #VENT_ABOVE_GROUND} マス上</b>まで。
     * 地面の高さは{@link Terrain 地形の窓口}に<b>1 歩ごとにその (x,z) で</b>訊く ——
     * 柱は登りながら横へ 10 マスほど流れるので、根元の高さだけで決めると
     * 斜面では埋まったり浮いたりする。
     */
    private void digVentShafts() {
        Chamber vent = null;
        for (Chamber c : plan.chambers) {
            if (c.type() == NestChamber.VENT) {
                vent = c;
                break;
            }
        }
        if (vent == null) {
            return;
        }
        plan.ventShaftFrom = plan.hollow.size();      // 柱は空洞ではないので、ここから先に空洞は無い
        double vx = vent.pos().getX() - origin.getX();
        double vy = vent.pos().getY() - origin.getY();
        double vz = vent.pos().getZ() - origin.getZ();
        LongOpenHashSet filled = new LongOpenHashSet();

        for (int i = 0; i < 3; i++) {
            double a = random.nextDouble() * Math.PI * 2.0D;
            double x = vx;
            double y = vy;
            double z = vz;
            double dx = Math.cos(a) * 0.22D;
            double dz = Math.sin(a) * 0.22D;
            BlockPos top = local(x, y, z);
            int guard = 0;
            while (guard++ < VENT_MAX_RISE + 64) {
                dx += (random.nextDouble() - 0.5D) * 0.1D;
                dz += (random.nextDouble() - 0.5D) * 0.1D;
                dx = Mth.clamp(dx, -0.45D, 0.45D);
                dz = Mth.clamp(dz, -0.45D, 0.45D);
                x += dx;
                y += 1.0D;
                z += dz;
                BlockPos here = local(x, y, z);
                // その (x,z) の地面より VENT_ABOVE_GROUND マス上まで
                int ceiling = Mth.clamp(terrain.surfaceAt(here.getX(), here.getZ()) - origin.getY(),
                        0, VENT_MAX_RISE) + VENT_ABOVE_GROUND;
                if (y > ceiling) {
                    break;
                }
                fillColumn(x, y, z, filled);
                top = here;
            }
            plan.surfaceVents.add(top);
        }
    }

    /** {@code (x,y,z)} を中心に十字の断面で柱を 1 段ぶん詰める(空洞にはしない)。 */
    private void fillColumn(double x, double y, double z, LongOpenHashSet filled) {
        int cx = Mth.floor(x + 0.5D);
        int cy = Mth.floor(y + 0.5D);
        int cz = Mth.floor(z + 0.5D);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 && dz != 0) {
                    continue;                         // 十字だけ。柱は細くてよい
                }
                long key = BlockPos.asLong(origin.getX() + cx + dx, origin.getY() + cy,
                        origin.getZ() + cz + dz);
                if (carved.contains(key)) {
                    continue;                         // 掘った坑道を柱で埋めない
                }
                if (filled.add(key)) {
                    plan.ventColumn.add(key);
                }
            }
        }
    }

    // --- 掘る・壁 ---

    /** 進む向きの傾きを {@code maxSlope}(1 マス進むごとの上下)までに抑える。正規化済みの向きに使う。 */
    /**
     * その場所が<b>水の中</b>か(2026-09-21 のユーザー報告「水中に巣を拡張してしまう」)。
     *
     * <p>柱ごとに「水底」と「水も数えた地表」を訊き、<b>水底より上で地表より下</b>なら水の中。
     * 湖や海を掘り抜くと、外殻を張っても<b>水の底にぽっかり空いた巣</b>になってしまい、
     * 水を避けるアルボラが切羽に近づけない —— 建設が止まる直接の原因になる。
     *
     * <p>柱の高さだけで見るので、洞窟の中の水たまりまでは拾えない。そこは掘るときに
     * {@code NestGrowthQueue} が壁で塞ぐ({@code shouldLine})。ここで止めたいのは
     * <b>湖や海のような大きな水</b>。
     */
    private boolean inWater(double x, double y, double z) {
        BlockPos at = local(x, y, z);
        int seabed = terrain.seabedAt(at.getX(), at.getZ());
        if (seabed >= terrain.surfaceAt(at.getX(), at.getZ())) {
            return false;                             // その柱に水は無い
        }
        return at.getY() > seabed - WATER_MARGIN;
    }

    /** 水底からこのマスぶんは掘らない(薄い天井を残すと崩れて水が入る)。 */
    private static final int WATER_MARGIN = 3;

    /**
     * 半径 {@code r} の球ごと水に掛からないか。<b>中心だけ見ても足りない</b> ——
     * 掘るのは球なので、中心が岸でも縁が湖に入る(2026-09-21 に GameTest が検出)。
     */
    private boolean inWaterBall(double x, double y, double z, double r) {
        return inWater(x, y, z)
                || inWater(x + r, y, z) || inWater(x - r, y, z)
                || inWater(x, y, z + r) || inWater(x, y, z - r)
                || inWater(x, y + r, z);
    }

    /** Mob が通る穴を掘る半径。設計値が細すぎても {@link #WALKABLE_RADIUS} は下回らせない。 */
    private double walkRadius() {
        return Math.max(t.tunnelRadius(), WALKABLE_RADIUS);
    }

    private static void clampSlope(Digger d, double maxSlope) {
        double flat = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
        if (flat < 1.0E-4D) {
            d.dx = 1.0D;
            d.dz = 0.0D;
            flat = 1.0D;
        }
        double limit = flat * maxSlope;
        if (d.dy > limit) {
            d.dy = limit;
        } else if (d.dy < -limit) {
            d.dy = -limit;
        }
        normalize(d);
    }

    /**
     * 坑道の間隔を測る升目。{@link NestTable#tunnelSpacing()} を 1 辺にする。
     * <b>y も升目に含める</b>ので、上を通った坑道の真下へ別の坑道が入り込むのも防げる ——
     * これが無いと坑道どうしが縦につながり、結果として縦穴のような空洞ができる(2026-09-19)。
     */
    private long cellKey(double x, double y, double z) {
        int size = Math.max(2, t.tunnelSpacing());
        return BlockPos.asLong(Mth.floor(x / size), Mth.floor(y / size), Mth.floor(z / size));
    }

    private void markTrail(Digger d) {
        long point = BlockPos.asLong(Mth.floor(d.x + 0.5D), Mth.floor(d.y + 0.5D), Mth.floor(d.z + 0.5D));
        long cell = cellKey(d.x, d.y, d.z);
        LongArrayList bucket = trailIndex.get(cell);
        if (bucket == null) {
            bucket = new LongArrayList();
            trailIndex.put(cell, bucket);
        }
        bucket.add(point);
        d.path.add(point);
        d.recent[d.recentIndex] = point;
        d.recentIndex = (d.recentIndex + 1) % d.recent.length;
    }

    private static boolean ownTrail(Digger d, long point) {
        for (long p : d.recent) {
            if (p == point) {
                return true;
            }
        }
        return false;
    }

    /**
     * 進む先に<b>別の坑道</b>があるなら、そこから外向きへ振って逃げる
     * (2026-09-19 の「密集しすぎ」への対処を 2026-09-20 に作り直したもの)。
     *
     * <p>自分がさっき通った点は数えないので、まっすぐ進むぶんには曲がらない。分岐した直後は
     * 親の坑道が真横にあって当たり前なので {@link Digger#grace} 歩ぶん見ない。
     */
    private void avoidOtherTunnels(Digger d) {
        int sep = t.tunnelSpacing();
        if (sep <= 0) {
            return;
        }
        if (d.grace > 0) {
            d.grace--;
            return;
        }
        for (int attempt = 0; attempt < 4; attempt++) {
            double ax = d.x + d.dx * 2.0D;
            double ay = d.y + d.dy * 2.0D;
            double az = d.z + d.dz * 2.0D;
            long other = nearestOtherTrail(d, ax, ay, az, sep);
            if (other == Long.MIN_VALUE) {
                return;
            }
            double ox = ax - BlockPos.getX(other);
            double oz = az - BlockPos.getZ(other);
            double len = Math.sqrt(ox * ox + oz * oz);
            if (len < 1.0E-4D) {
                double a = random.nextDouble() * Math.PI * 2.0D;
                ox = Math.cos(a);
                oz = Math.sin(a);
                len = 1.0D;
            }
            // 水平の長さを保ったまま回す(ここで坂をいじると坑道が急になる)
            double flat = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
            double nx = d.dx * 0.25D + ox / len * flat;
            double nz = d.dz * 0.25D + oz / len * flat;
            double nl = Math.sqrt(nx * nx + nz * nz);
            if (nl < 1.0E-6D) {
                return;
            }
            d.dx = nx / nl * flat;
            d.dz = nz / nl * flat;
        }
    }

    /**
     * {@code (x,y,z)} から {@code sep} マス以内にある<b>自分以外の坑道の芯</b>のうち、いちばん近い点。
     * 無ければ {@link Long#MIN_VALUE}。近くの升目だけを見るので、坑道が何千歩あっても速さは変わらない。
     */
    private long nearestOtherTrail(Digger d, double x, double y, double z, double sep) {
        int size = Math.max(2, t.tunnelSpacing());
        int cx = Mth.floor(x / size);
        int cy = Mth.floor(y / size);
        int cz = Mth.floor(z / size);
        double best = sep * sep;
        long bestPoint = Long.MIN_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    LongArrayList bucket = trailIndex.get(BlockPos.asLong(cx + dx, cy + dy, cz + dz));
                    if (bucket == null) {
                        continue;
                    }
                    for (int i = 0; i < bucket.size(); i++) {
                        long p = bucket.getLong(i);
                        if (ownTrail(d, p)) {
                            continue;
                        }
                        double ex = x - BlockPos.getX(p);
                        double ey = y - BlockPos.getY(p);
                        double ez = z - BlockPos.getZ(p);
                        double dd = ex * ex + ey * ey + ez * ez;
                        if (dd < best) {
                            best = dd;
                            bestPoint = p;
                        }
                    }
                }
            }
        }
        return bestPoint;
    }

    /**
     * 進む先に<b>既にある部屋</b>があるなら、水平に向きを振ってよける(2026-09-20 に作り直し)。
     *
     * <p>坑道が部屋を貫くと、部屋の壁が坑道ぶんだけ開いて<b>両方がひと続きの大きな空間</b>になる。
     * 部屋へ入る道は {@link #digChamberAt} が別に 1 本掘るので、通りがかりの坑道は避けてよい。
     *
     * <p>作り直しで変えたのは 3 点:
     * <ul>
     *   <li>見る距離を 2 マス先から 3 マス先へ。よけるのに曲がれる歩数を稼ぐため。</li>
     *   <li>よける範囲に {@link NestTable#chamberClearance()} を足した。壁の厚みぶん手前で曲がる。</li>
     *   <li><b>上下の向きは変えない</b>。以前は振ったあとに正規化していたので坂が変わり、
     *   よけるたびに坑道が急になっていた。水平の長さを保ったまま回すようにした。</li>
     * </ul>
     *
     * <p>これは<b>見た目のため</b>の仕掛けで、貫通させない保証は {@link #roomGuard} が持つ ——
     * 坑道は 1 歩 1 マスしか曲がれないので、間に合わないことがある。
     *
     * @param strength よける強さ。幹は 0.5(曲げすぎると坑口の方へ戻って縦穴のような形になる)、
     *                 枝は 1.0。
     */
    private void avoidChambers(Digger d, double strength) {
        if (plan.chambers.isEmpty()) {
            return;
        }
        // 幹は strength を下げて呼ぶ。全力でよけさせると曲がりすぎて坑口の方へ戻ってしまい、
        // 「入口が縦穴に近い」形になる(2026-09-20 に GameTest が検出)。
        double keep = (t.chamberClearance() + walkRadius() + 1.0D) * strength;
        double look = 1.0D + 2.0D * strength;
        int attempts = strength >= 1.0D ? 6 : 2;
        for (int attempt = 0; attempt < attempts; attempt++) {
            double ax = d.x + d.dx * look;
            double ay = d.y + d.dy * look;
            double az = d.z + d.dz * look;
            Chamber hit = null;
            for (Chamber c : plan.chambers) {
                double cx = (c.pos().getX() - origin.getX()) - ax;
                double cy = (c.pos().getY() - origin.getY()) - ay;
                double cz = (c.pos().getZ() - origin.getZ()) - az;
                double reach = c.radius() + keep;
                if (cx * cx + cy * cy + cz * cz <= reach * reach) {
                    hit = c;
                    break;
                }
            }
            if (hit == null) {
                return;
            }
            // 部屋の中心から見て外向きへ振る
            double ox = ax - ((hit.pos().getX() - origin.getX()));
            double oz = az - ((hit.pos().getZ() - origin.getZ()));
            double len = Math.sqrt(ox * ox + oz * oz);
            if (len < 1.0E-4D) {
                double a = random.nextDouble() * Math.PI * 2.0D;
                ox = Math.cos(a);
                oz = Math.sin(a);
                len = 1.0D;
            }
            double flat = Math.sqrt(d.dx * d.dx + d.dz * d.dz);
            double nx = d.dx * (1.0D - 0.75D * strength) + ox / len * flat * strength;
            double nz = d.dz * (1.0D - 0.75D * strength) + oz / len * flat * strength;
            double nl = Math.sqrt(nx * nx + nz * nz);
            if (nl < 1.0E-6D) {
                return;
            }
            d.dx = nx / nl * flat;                    // 水平の長さは変えない → 坂はそのまま
            d.dz = nz / nl * flat;
        }
    }

    /**
     * 掘削点が 3 マス進むごとに道しるべを 1 つ落とす。親は<b>その掘削点が直前に落とした道しるべ</b>
     * なので、枝は分かれ道から素直に伸びた木になる。
     */
    private void dropWaypoint(Digger d) {
        BlockPos here = local(d.x, d.y, d.z);
        if (d.way < 0 || plan.route.get(d.way).distSqr(here) >= WAYPOINT_SPACING_SQ) {
            d.way = addWaypoint(here, d.way);
        }
    }

    /** 道しるべを 1 つ足して添字を返す。 */
    private int addWaypoint(BlockPos pos, int parent) {
        plan.route.add(pos);
        plan.routeParents.add(parent);
        return plan.route.size() - 1;
    }

    /** 局所座標 {@code (x,y,z)} にいちばん近い道しるべ。無ければ -1。 */
    private int nearestWaypoint(double x, double y, double z) {
        BlockPos here = local(x, y, z);
        int best = -1;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < plan.route.size(); i++) {
            double sq = plan.route.get(i).distSqr(here);
            if (sq < bestSq) {
                bestSq = sq;
                best = i;
            }
        }
        return best;
    }

    /**
     * 側室へ入る通路にも道しるべを落とし、最後に<b>部屋の中心</b>を足す。
     * これが無いと、部屋に居る個体は幹の道しるべまでが遠くて出られない(2026-09-20)。
     */
    private void linkChamber(double fx, double fy, double fz,
                             double sx, double sy, double sz, int steps, BlockPos centre) {
        int parent = nearestWaypoint(fx, fy, fz);
        double x = fx;
        double y = fy;
        double z = fz;
        for (int step = 1; step <= steps; step++) {
            x += sx;
            y += sy;
            z += sz;
            if (step % 3 == 0) {
                parent = addWaypoint(local(x, y, z), parent);
            }
        }
        addWaypoint(centre, parent);
    }

    private void advance(Digger d) {
        d.x += d.dx;
        d.y += d.dy;
        d.z += d.dz;
    }

    private static void normalize(Digger d) {
        double l = Math.sqrt(d.dx * d.dx + d.dy * d.dy + d.dz * d.dz);
        if (l < 1.0E-6D) {
            d.dy = -1.0D;
            return;
        }
        d.dx /= l;
        d.dy /= l;
        d.dz /= l;
    }

    /** 巣ローカル座標 → ワールド絶対座標。 */
    private BlockPos local(double x, double y, double z) {
        return new BlockPos(origin.getX() + Mth.floor(x + 0.5D),
                origin.getY() + Mth.floor(y + 0.5D),
                origin.getZ() + Mth.floor(z + 0.5D));
    }

    /** {@code (x,y,z)} を中心に半径 {@code r} の球を空洞にする。地表(ローカル y = 0)より上は掘らない。 */
    private void carve(double x, double y, double z, double r) {
        carve(x, y, z, r, 0, false);
    }

    /**
     * {@code (x,y,z)} を中心に半径 {@code r} の球を空洞にする。
     * {@code maxLocalY} は地表(ローカル y = 0)より上をどこまで掘ってよいか —— 塚に坑口を開けるときだけ正の値。
     */
    private void carve(double x, double y, double z, double r, int maxLocalY) {
        carve(x, y, z, r, maxLocalY, false);
    }

    /**
     * {@code force} は<b>部屋のまわりに押さえた岩({@link #roomGuard})を押しのけて掘る</b>か。
     * 使うのは幹と、女王室をどうしても置けなかったときだけ —— どちらも途切れると巣が成り立たない。
     * ふつうの坑道は {@code false} で掘るので、部屋の壁を破れない。
     */
    private void carve(double x, double y, double z, double r, int maxLocalY, boolean force) {
        int ri = Mth.ceil(r);
        int cx = Mth.floor(x + 0.5D);
        int cy = Mth.floor(y + 0.5D);
        int cz = Mth.floor(z + 0.5D);
        double rr = r * r;
        for (int dx = -ri; dx <= ri; dx++) {
            for (int dy = -ri; dy <= ri; dy++) {
                for (int dz = -ri; dz <= ri; dz++) {
                    if (dx * dx + dy * dy + dz * dz > rr) {
                        continue;
                    }
                    int ly = cy + dy;
                    if (ly > maxLocalY) {
                        continue;                       // 地表(と塚)より上は掘らない
                    }
                    if (carved.size() >= t.blockBudget()) {
                        return;                     // 総量の硬い上限。ここを外すと固まる
                    }
                    long key = BlockPos.asLong(origin.getX() + cx + dx, origin.getY() + ly,
                            origin.getZ() + cz + dz);
                    if (!force && roomGuard.contains(key)) {
                        continue;                       // 部屋のまわりの岩は坑道で掘らない
                    }
                    // <b>水の中は誰が掘ろうと掘らない</b>(2026-09-21)。掘削点の側でも止めているが、
                    // 球で掘るので縁がはみ出すし、女王室の最後の手段は force で押し通る。
                    // ここが最後の砦(force でも効かせる)。
                    if (inWater(cx + dx, ly, cz + dz)) {
                        continue;
                    }
                    if (carved.add(key)) {
                        plan.hollow.add(key);
                    }
                }
            }
        }
    }

    /**
     * 空洞に face で接しているマスを壁として集める。水や溶岩を塞ぐのもこの壁の役目
     * ({@link NestGrowthQueue} が流体をここで置き換える)。
     */
    private void buildLining() {
        LongOpenHashSet seen = new LongOpenHashSet();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int i = 0; i < plan.hollow.size(); i++) {
            long key = plan.hollow.getLong(i);
            cursor.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
            for (Direction dir : Direction.values()) {
                long side = BlockPos.asLong(cursor.getX() + dir.getStepX(),
                        cursor.getY() + dir.getStepY(), cursor.getZ() + dir.getStepZ());
                if (carved.contains(side) || !seen.add(side)) {
                    continue;
                }
                // 真上が空洞なら床。草を生やす面であり、土や草ブロックを混ぜる面でもある。
                long above = BlockPos.asLong(BlockPos.getX(side), BlockPos.getY(side) + 1, BlockPos.getZ(side));
                if (carved.contains(above)) {
                    plan.liningFloor.add(side);
                } else {
                    plan.lining.add(side);
                }
            }
        }
    }

}

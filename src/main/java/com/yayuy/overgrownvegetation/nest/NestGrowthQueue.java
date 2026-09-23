package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.corruption.CaveGrowth;
import com.yayuy.overgrownvegetation.corruption.CorruptionGrowth;
import com.yayuy.overgrownvegetation.corruption.OvergrowthRegionMap;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.AbstractOvergrownInvaderEntity;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * 巣を少しずつ掘る(2026-09-19)。{@link NestLayout} が出した計画を、1 tick あたり
 * {@code nest.json} の {@code blocksPerTick} マスずつ実際のワールドへ反映する。
 *
 * <p><b>一度に掘ってはいけない</b> —— 1 万マスを超える空洞化は光の再計算が大量に走り、
 * サーバースレッドが止まる(巨大樹で実証済み。{@code GiantTreeGrowthQueue} と同じ理由)。
 *
 * <h2>順番</h2>
 * <ol>
 *     <li><b>塚</b> — 地表に過繫茂しているブロックの小さな山を盛る。入口の坑道はこの中から下りていくので、
 *     掘るより先に置く。</li>
 *     <li><b>壁</b> — 空洞になる場所の周りを先に固める。後にすると掘った瞬間に水が流れ込む。</li>
 *     <li><b>空洞</b> — 掘る。</li>
 *     <li><b>下草</b> — 床に草を生やし、壁と天井に洞窟植物を付ける。掘り終わってからでないと
 *     床が確定しない。</li>
 * </ol>
 *
 * <h2>掘ってよいかの判断はここ</h2>
 * <p>{@link NestLayout} は地形を見ない純粋な計算なので、世界の都合はすべてここで見る。
 * <ul>
 *     <li><b>自然の地形しか掘らない</b>({@code #overgrown_vegetation:nest_diggable})。
 *     丸石・板材・チェストなどに当たったらそのマスは飛ばす。誰が置いたかを記録しなくても、
 *     これだけでプレイヤーの建築物が守られる。</li>
 *     <li><b>水と溶岩は壁で塞ぐ</b>。壁のマスが流体なら過繫茂しているブロックに置き換えるので、
 *     掘った坑道へ流れ込まない。</li>
 *     <li><b>鉱石は壁に残す</b>。坑道は鉱石も掘り抜くが(塞がると通れないため)、
 *     壁のマスが鉱石ならそのままにするので、鉱脈を切った断面が見える。</li>
 *     <li><b>草・蔦・葉は一緒に消す</b>({@code #overgrown_vegetation:nest_clearable})。
 *     地形ではないので掘ってよいブロックには入らないが、残すと支えを失って空中に浮く
 *     —— 繫茂した洞窟を掘り抜いたときに実際に起きた(2026-09-19 の指摘)。</li>
 *     <li><b>空いている空間は外殻で塞ぐ</b>。洞窟でも崖の外でも、坑道が途切れないように殻を張る。
 *     開けたままにするのは坑口と噴出口({@code Plan.openings})のそばだけ。</li>
 * </ul>
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class NestGrowthQueue {

    /** 光の再計算を抑えるため、隣への形状更新は行わない(巨大樹と同じ設定)。 */
    private static final int UPDATE_FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    private static final BlockState AIR = Blocks.AIR.defaultBlockState();

    /**
     * チャンクを掴んでおく長さ(tick)。掘り終わって中身を置くまでの余裕を見て長めに取る
     * —— 15,000 マスを 240/tick で置いて 60 tick ほど、壁と下草を入れても 300 tick には収まる。
     */
    private static final int TICKET_LIFESPAN = 1_200;

    private static final List<Job> JOBS = new ArrayList<>();

    /**
     * 地域マップのぼかし用ソルト。{@link CaveGrowth} と<b>同じ値</b>を使うので、巣の中の植物は
     * 周りの洞窟と食い違わない。
     */
    private static final int REGION_SALT = 6;

    private static final class Job {
        final ServerLevel level;
        final NestLayout.Plan plan;
        /** この形を組み立てた種。読み直しのときに同じ形を作り直すのに要る({@link NestDigData})。 */
        final long seed;
        /**
         * この巣が湿地系(湿地 / 菌湿地帯)の地域にあるか。発光菌糸・苔の塊・胞子嚢は
         * <b>湿地系にしか出さない</b> —— 密林の地域に生えていた(2026-09-20 の指摘)。
         */
        final boolean swamp;
        int moundCursor;
        /** 次に掘るマス({@code plan.hollow} の添字)。これが<b>切羽</b>。 */
        int cursor;
        int decorCursor;
        /** 計画で掘るマスの集合。壁をその場で作るのに使う(隣が計画外なら壁)。 */
        final LongOpenHashSet planned = new LongOpenHashSet();
        /**
         * <b>迂回で足したマス</b>。岩盤やプレイヤーの入れ物に当たって掘れなかったとき、
         * その脇を膨らませて道を通す(2026-09-20 の折衷案。掘り手がその場で判断する部分)。
         */
        final LongArrayList detours = new LongArrayList();
        final LongOpenHashSet detourCells = new LongOpenHashSet();
        int detourCursor;
        /** 掘り手(建設グループのアルボラ)。空なら自動で掘る。 */
        final List<java.util.UUID> crew = new ArrayList<>();
        /** 掘り手が切羽に着かないまま経った tick。長引いたら自動掘りへ切り替える。 */
        int stall;
        /** 1 マスに満たない掘り進みの端数。{@code digTicksPerBlock} で刻むために持つ。 */
        double progress;
        /** 何本目の噴出孔を地表まで貫いているか。 */
        int ventCursor;
        /** その噴出孔を今どの高さまで貫いたか(ワールド y)。{@link Integer#MIN_VALUE} = これから。 */
        int ventY = Integer.MIN_VALUE;

        Job(ServerLevel level, NestLayout.Plan plan, long seed) {
            this.level = level;
            this.plan = plan;
            this.seed = seed;
            this.swamp = OvergrowthRegionMap
                    .blendedVariantAt(level, plan.origin.getX(), plan.origin.getZ(), REGION_SALT)
                    .isSwamp();
            for (int i = 0; i < plan.hollow.size(); i++) {
                planned.add(plan.hollow.getLong(i));
            }
        }

        /** 今の切羽(次に掘るマス)。掘り終わっていれば null。 */
        @javax.annotation.Nullable
        BlockPos face() {
            if (cursor < plan.hollow.size()) {
                return BlockPos.of(plan.hollow.getLong(cursor));
            }
            if (detourCursor < detours.size()) {
                return BlockPos.of(detours.getLong(detourCursor));
            }
            return null;
        }

        boolean done() {
            return moundCursor >= plan.mound.size()
                    && cursor >= plan.hollow.size()
                    && detourCursor >= detours.size()
                    && ventCursor >= plan.ventColumn.size()
                    && decorCursor >= plan.hollow.size();
        }

        boolean dug() {
            return cursor >= plan.hollow.size() && detourCursor >= detours.size();
        }
    }

    /**
     * 掘っているあいだ巣のチャンクを読み込んだままにするための切符。
     * <b>寿命つきなので勝手に外れる</b> —— {@code /forceload} と違ってワールドに残らないので、
     * 途中でサーバーが落ちてもチャンクが固定されたままにならない。
     */
    private static final TicketType<ChunkPos> NEST_TICKET =
            TicketType.create("overgrown_vegetation_nest", Comparator.comparingLong(ChunkPos::toLong),
                    TICKET_LIFESPAN);

    public static void enqueue(ServerLevel level, NestLayout.Plan plan, long seed) {
        if (level == null || plan == null || plan.isEmpty()) {
            return;
        }
        holdChunks(level, plan);
        Job job = new Job(level, plan, seed);
        JOBS.add(job);
        remember(job);
    }

    /**
     * <b>掘りかけの巣をワールドに書き留める</b>(2026-09-20 のユーザー報告
     * 「再ログインのたびにグループがなくなると巣が一生完成しないのでは」)。
     *
     * <p>消えていたのは建設グループではなく<b>ここ</b>だった —— {@link #JOBS} はただの静的リストで、
     * サーバーを落とすと丸ごと消える。アルボラ側の記憶(グループ・建設地・役割)はもともと
     * エンティティの NBT に残るので、掘るものだけが無くなって<b>同じ場所に別の設計で掘り直し</b>が
     * 始まっていた。
     */
    private static void remember(Job job) {
        List<java.util.UUID> crew = new ArrayList<>(job.crew);
        NestDigData.get(job.level).store(new NestDigData.Snapshot(job.plan.origin, job.seed,
                job.moundCursor, job.cursor, job.decorCursor, job.ventCursor, crew));
    }

    /**
     * ワールドを読み直したあと、掘りかけの巣を組み立て直して続きから掘る。
     * 形は入口と種から作り直すので、保存してあるのは「どこまで掘ったか」だけ。
     */
    private static void restore(ServerLevel level) {
        NestDigData data = NestDigData.get(level);
        NestDigData.Snapshot snap = data.snapshot();
        if (snap == null) {
            return;
        }
        if (NestData.get(level).byOrigin(snap.origin()) != null) {
            data.clear();                             // もう完成している
            return;
        }
        NestLayout.Plan plan = NestFormation.buildPlan(level, snap.origin(), snap.seed());
        if (plan.isEmpty()) {
            data.clear();
            return;
        }
        Job job = new Job(level, plan, snap.seed());
        // 設計値が変わっていると数が合わないことがあるので、必ず収まるところまで詰める
        job.moundCursor = Math.min(snap.moundCursor(), plan.mound.size());
        job.cursor = Math.min(snap.cursor(), plan.hollow.size());
        job.decorCursor = Math.min(snap.decorCursor(), plan.hollow.size());
        job.ventCursor = Math.min(snap.ventCursor(), plan.ventColumn.size());
        job.crew.addAll(snap.crew());
        JOBS.add(job);
        holdChunks(level, plan);
        if (!job.crew.isEmpty()) {
            NestCrew.setDig(plan.route, plan.routeParents.toIntArray());
        }
    }

    /** サーバーを落とすときの後始末。次に読み込んだら {@link #restore} が拾い直す。 */
    public static void clearAll() {
        JOBS.clear();
        restored = false;
    }

    /** 掘りかけの巣を読み直したか(サーバー起動ごとに 1 回)。 */
    private static boolean restored;

    /**
     * 巣がまたがるチャンクに切符を置いて、掘り終わって中身を置くまで読み込んだままにする
     * (2026-09-20)。
     *
     * <p>これが無いと<b>奥の部屋だけ中身が置かれない</b>。掘るのも中身を置くのも
     * 「読み込まれているマス」しか触らないので、プレイヤーが入口の近くに立っていると、
     * いちばん遠い噴気室や女王室のチャンクが落ちていて<b>胞子ノードも女王も置かれずに終わる</b>
     * —— 掘った床(過繫茂した草ブロック)だけが残るので、「node が草に置き換えられた」ように見える。
     */
    private static void holdChunks(ServerLevel level, NestLayout.Plan plan) {
        LongOpenHashSet chunks = new LongOpenHashSet();
        for (int i = 0; i < plan.hollow.size(); i++) {
            long key = plan.hollow.getLong(i);
            chunks.add(ChunkPos.asLong(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4));
        }
        for (int i = 0; i < plan.ventColumn.size(); i++) {
            long key = plan.ventColumn.getLong(i);
            chunks.add(ChunkPos.asLong(BlockPos.getX(key) >> 4, BlockPos.getZ(key) >> 4));
        }
        for (long key : chunks) {
            ChunkPos pos = new ChunkPos(key);
            // 半径 1 = 読み込みだけ(mob は動かない)。掘って物を置くにはこれで足りる。
            level.getChunkSource().addRegionTicket(NEST_TICKET, pos, 1, pos);
        }
    }

    /** 掘っている最中の巣の数。 */
    public static int activeJobs() {
        return JOBS.size();
    }

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (JOBS.isEmpty() || !(event.getLevel() instanceof ServerLevel serverLevel)) {
            return;
        }
        if (!restored) {
            restored = true;
            restore(serverLevel);                     // 掘りかけの巣を拾い直す(起動後 1 回)
        }
        int budget = DesignData.nest().blocksPerTick();
        Iterator<Job> it = JOBS.iterator();
        while (it.hasNext() && budget > 0) {
            Job job = it.next();
            if (job.level != serverLevel) {
                continue;
            }
            // 掘り終わるまでチャンクを掴み続ける(ユーザー指定 2026-09-20)。切符は寿命つきなので
            // <b>置き直さないと外れる</b> —— 1 マスずつ掘る作りにしたぶん、掘削は何分も続く。
            if (serverLevel.getGameTime() % HOLD_REFRESH == 0L) {
                holdChunks(serverLevel, job.plan);
            }
            budget = work(job, Math.min(budget, share(job)));
            // どこまで掘ったかを書き留める。毎 tick 書くと重いので間隔を空ける。
            if (serverLevel.getGameTime() % REMEMBER_INTERVAL == 0L) {
                remember(job);
            }
            if (job.done()) {
                for (java.util.UUID id : job.crew) {
                    NestCrew.clear(id);        // 掘り手を解放(解散は BuilderGroups がやる)
                }
                NestCrew.clearDig();
                NestDigData.get(serverLevel).clear();
                NestData.get(serverLevel).onNestDug(serverLevel, job.plan);
                // 掘り終わったら中身(卵・貯蔵胞・衛兵・胞子ノード・女王・核)を置く
                NestContents.populate(serverLevel, job.plan);
                it.remove();
            }
        }
    }

    /**
     * この tick にこの巣が使ってよいマス数。<b>切羽にアルボラが何人居るか</b>で決まる
     * (2026-09-20 の折衷案)。
     *
     * <p>1 人も居なければ {@link #STALL_LIMIT} tick は待ち、それでも来なければ
     * {@link #AUTO_SHARE} の速さで<b>自動で掘る</b>。待ち続けないのは、プレイヤーが離れると
     * アルボラが消えて<b>掘りかけの巣が永久に残る</b>ため —— 「見ていないあいだは机上で進める」。
     */
    private static int share(Job job) {
        NestTable t = DesignData.nest();
        int full = t.blocksPerTick();
        if (job.moundCursor < job.plan.mound.size()) {
            return full;                          // 塚は先に盛る(建設地の目印になる)
        }
        BlockPos face = job.face();
        if (face == null) {
            return full;                          // 掘り終わり。柱と下草は待たせない
        }
        int diggers = countDiggers(job, face);
        if (diggers > 0) {
            job.stall = 0;
        } else {
            job.stall++;
            if (job.stall <= STALL_LIMIT) {
                return 0;
            }
            diggers = AUTO_DIGGERS;               // 誰も来ない。机上で進める
        }
        // <b>1 マスずつ</b>(ユーザー指定 2026-09-20)。速さは「切羽の人数 ÷ digTicksPerBlock」。
        // 端数を持ち越すので、人数が少なくても止まらずに少しずつ進む。
        job.progress += diggers / (double) Math.max(1, t.digTicksPerBlock());
        int allowed = (int) job.progress;
        job.progress -= allowed;
        return Math.min(allowed, full);
    }

    /**
     * 切羽に着いている掘り手の数。ついでに<b>全員へ切羽を配り直す</b>ので、
     * 掘り進むたびにアルボラも付いてくる({@code entity/NestDigGoal})。
     */
    private static int countDiggers(Job job, BlockPos face) {
        if (job.crew.isEmpty()) {
            return 0;
        }
        int here = 0;
        int slot = 0;
        Iterator<java.util.UUID> it = job.crew.iterator();
        while (it.hasNext()) {
            java.util.UUID id = it.next();
            net.minecraft.world.entity.Entity entity = job.level.getEntity(id);
            if (entity == null) {
                continue;                         // まだ読み込まれていないだけ。名簿には残す
            }
            if (!(entity instanceof AbstractOvergrownInvaderEntity mob) || !mob.isAlive()) {
                NestCrew.clear(id);
                it.remove();                      // 死んだと分かった掘り手だけ名簿から外す
                continue;
            }
            NestCrew.setFace(id, face, slot++);
            // <b>切羽と同じ高さ帯に居ること</b>も求める。水平距離だけで数えると、浅い切羽の
            // 真上の地面に立っているだけで掘り手として数えられ、地上から巣ができてしまう
            // (2026-09-20 のユーザー報告)。
            double dx = mob.getX() - (face.getX() + 0.5D);
            double dz = mob.getZ() - (face.getZ() + 0.5D);
            if (dx * dx + dz * dz <= NestCrew.REACH * NestCrew.REACH
                    && Math.abs(mob.getBlockY() - face.getY()) <= NestCrew.REACH_Y) {
                here++;
            }
        }
        return here;
    }

    /** 建設グループを掘り手として登録する({@code nest/BuilderGroups} から)。 */
    public static void assignCrew(ServerLevel level, BlockPos origin, List<java.util.UUID> crew) {
        for (Job job : JOBS) {
            if (job.level == level && job.plan.origin.equals(origin)) {
                job.crew.addAll(crew);
                remember(job);
                // 掘り手が切羽まで<b>坑道を通って</b>潜れるように、道しるべを渡す(2026-09-20)。
                NestCrew.setDig(job.plan.route, job.plan.routeParents.toIntArray());
                return;
            }
        }
    }

    /** 掘り手が切羽に来ないまま待つ上限(tick)。 */
    private static final int STALL_LIMIT = 100;
    /**
     * 掘り手が来ないときに<b>机上で掘り進める人数</b>。プレイヤーが離れるとアルボラは消えるので、
     * これが無いと掘りかけの巣が永久に残る。実際の掘り手より遅くしてあるので、
     * <b>見ているほうが早く建つ</b>。
     */
    private static final int AUTO_DIGGERS = 4;
    /** チャンクの切符を置き直す間隔(tick)。切符の寿命より短くすること。 */
    private static final int HOLD_REFRESH = 400;
    /** 掘りかけの巣を書き留める間隔(tick)。 */
    private static final int REMEMBER_INTERVAL = 100;

    private static int work(Job job, int budget) {
        ServerLevel level = job.level;
        NestTable t = DesignData.nest();
        RandomSource random = level.random;
        int openRadius = t.openingRadius();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 1) 塚
        while (job.moundCursor < job.plan.mound.size() && budget > 0) {
            long key = job.plan.mound.getLong(job.moundCursor++);
            budget--;
            if (!load(level, cursor, key)) {
                continue;
            }
            BlockState state = level.getBlockState(cursor);
            // 地面の上に盛るので、空気か草・下草のような置き換えてよいものにだけ置く
            if (!state.isAir() && !state.canBeReplaced()) {
                continue;
            }
            // 塚の上面だけ草ブロック、その下は土。上に何か載るなら土のまま。
            boolean covered = !level.getBlockState(cursor.above()).isAir()
                    || job.plan.mound.contains(BlockPos.asLong(cursor.getX(), cursor.getY() + 1, cursor.getZ()));
            level.setBlock(cursor.immutable(),
                    covered ? ModBlocks.OVERGROWN_DIRT.get().defaultBlockState()
                            : ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState(),
                    UPDATE_FLAGS);
        }

        // 2) 掘る。<b>1 マスずつ「まず周りを壁にしてから空洞にする」</b>(2026-09-20 に組み替え)。
        //    壁を全部先に置いてから掘る作りだと、掘り始める前に巣の輪郭が丸ごと現れてしまい、
        //    掘り手が掘っているように見えない。1 マスずつなら切羽が前へ進んでいくように見えるし、
        //    「穴より先に壁」という水の流れ込み対策も local には保たれる。
        while (job.cursor < job.plan.hollow.size() && budget > 0) {
            budget = digOne(job, level, job.plan.hollow.getLong(job.cursor++), budget, openRadius);
        }
        // 迂回で足したマス(岩盤などをよけた道)は最後に掘る
        while (job.detourCursor < job.detours.size() && budget > 0) {
            budget = digOne(job, level, job.detours.getLong(job.detourCursor++), budget, openRadius);
        }

        // 3) 噴出孔の柱を立てる。<b>掘るのではなく詰める</b>(2026-09-20 にユーザー指定で
        //    「中が空洞の煙突」から作り直し)。地上からさらに 5 マス伸びる。
        while (job.dug() && job.ventCursor < job.plan.ventColumn.size() && budget > 0) {
            budget = raiseVentColumn(job, level, budget);
        }

        // 4) 下草(掘り終わってから。床が確定していないと生やせない)
        if (job.dug() && job.ventCursor >= job.plan.ventColumn.size()) {
            while (job.decorCursor < job.plan.hollow.size() && budget > 0) {
                long key = job.plan.hollow.getLong(job.decorCursor++);
                budget--;
                decorate(level, cursor, key, t, random, job.swamp);
            }
        }
        return budget;
    }

    /**
     * 噴出孔の柱を 1 マスぶん積む。<b>掘るのではなく詰める</b>
     * (2026-09-20 にユーザー指定で「中が空洞の煙突」から作り直し)。
     *
     * <p>置くのは<b>空いているところだけ</b>。地中のぶんは元から岩なので何もせず、
     * 地面から上だけが柱として見える。プレイヤーの建築物は
     * {@code #overgrown_vegetation:nest_diggable} で守る —— 掘るときと同じ規則。
     */
    private static int raiseVentColumn(Job job, ServerLevel level, int budget) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        while (job.ventCursor < job.plan.ventColumn.size() && budget > 0) {
            long key = job.plan.ventColumn.getLong(job.ventCursor++);
            budget--;
            if (!load(level, cursor, key)) {
                continue;
            }
            BlockState here = level.getBlockState(cursor);
            boolean free = here.isAir() || isClearable(here) || !here.getFluidState().isEmpty();
            if (!free && !isDiggable(here)) {
                continue;                           // プレイヤーの建築物などは触らない
            }
            if (!free && cursor.getY() < level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                    cursor.getX(), cursor.getZ()) - 1) {
                continue;                           // 地中の岩はそのまま(柱は地上だけ見せる)
            }
            level.setBlock(cursor.immutable(), ventColumnBlock(cursor), UPDATE_FLAGS);
        }
        return budget;
    }

    /** 柱のブロック。壁と同じ斑にして、巣と同じ素材に見えるようにする。 */
    private static BlockState ventColumnBlock(BlockPos pos) {
        int roll = patchHash(pos.getX(), pos.getY(), pos.getZ()) % 100;
        if (roll < 18) {
            return ModBlocks.GLOWING_MYCELIUM.get().defaultBlockState();
        }
        if (roll < 55) {
            return ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        }
        return ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
    }

    /**
     * 1 マス掘る。先に<b>そのマスの周りのうち計画外のところ</b>を壁にしてから空洞にする。
     *
     * <p>掘れなかったとき(岩盤・チェストなど、掘ってよいブロックでないもの)は、
     * {@link #detour} が<b>その脇を膨らませて道を通す</b> —— 骨組みは同じでも、
     * 実際の地形に当たったところだけ形が変わる(2026-09-20 の折衷案)。
     */
    private static int digOne(Job job, ServerLevel level, long key, int budget, int openRadius) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        if (!load(level, cursor, key)) {
            return budget - 1;
        }
        BlockPos pos = cursor.immutable();
        // まず周りを壁にする(計画で掘るマスは飛ばす)
        for (Direction dir : Direction.values()) {
            long side = BlockPos.asLong(pos.getX() + dir.getStepX(), pos.getY() + dir.getStepY(),
                    pos.getZ() + dir.getStepZ());
            if (job.planned.contains(side) || job.detourCells.contains(side)) {
                continue;
            }
            boolean floor = job.planned.contains(BlockPos.asLong(BlockPos.getX(side),
                    BlockPos.getY(side) + 1, BlockPos.getZ(side)));
            placeLining(level, cursor, side, floor, job, openRadius);
            budget--;
        }
        cursor.set(pos);
        budget--;
        BlockState here = level.getBlockState(pos);
        if (here.isAir()) {
            return budget;                     // 既に空洞。置き直す意味がない
        }
        // 流体も、草・蔦・葉のような飾りも必ず消す。どちらも残すと
        // 掘った坑道の中に水や苔が浮いたまま残る(2026-09-19 の指摘)。
        if (isDiggable(here) || isClearable(here) || !here.getFluidState().isEmpty()) {
            level.setBlock(pos, AIR, UPDATE_FLAGS);
            return budget;
        }
        detour(job, pos);                      // 掘れなかった。脇をふくらませてよける
        return budget;
    }

    /**
     * 掘れなかったマスの<b>脇へ道を膨らませる</b>(2026-09-20)。
     *
     * <p>骨組みどおりに掘れない場所 —— 岩盤、プレイヤーのチェストや樽 —— に当たったとき、
     * そこを塞いだままにすると坑道が行き止まりになる。横と上の隣を掘る予定に足して、
     * <b>障害物を避けて通れる幅</b>を作る。足しすぎて巣が膨れないよう {@link #DETOUR_LIMIT} で止める。
     */
    private static void detour(Job job, BlockPos blocked) {
        if (job.detours.size() >= DETOUR_LIMIT) {
            return;
        }
        for (Direction dir : DETOUR_DIRECTIONS) {
            BlockPos side = blocked.relative(dir);
            long key = side.asLong();
            if (job.planned.contains(key) || !job.detourCells.add(key)) {
                continue;
            }
            job.detours.add(key);
            if (job.detours.size() >= DETOUR_LIMIT) {
                return;
            }
        }
    }

    /** 迂回で掘ってよいマス数の上限。骨組みが崩れない程度に留める。 */
    private static final int DETOUR_LIMIT = 400;
    /** 迂回でふくらませる向き(横 4 方向と上)。下は掘っても落ちるだけなので見ない。 */
    private static final Direction[] DETOUR_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP
    };

    private static boolean load(ServerLevel level, BlockPos.MutableBlockPos cursor, long key) {
        cursor.set(BlockPos.getX(key), BlockPos.getY(key), BlockPos.getZ(key));
        return level.isLoaded(cursor);
    }

    private static void placeLining(ServerLevel level, BlockPos.MutableBlockPos cursor, long key,
                                    boolean floor, Job job, int openingRadius) {
        if (!load(level, cursor, key)) {
            return;
        }
        BlockState state = level.getBlockState(cursor);
        if (!shouldLine(state) && !shouldShell(state, cursor, job.plan, openingRadius)) {
            return;
        }
        level.setBlock(cursor.immutable(), liningFor(cursor, floor, job.swamp), UPDATE_FLAGS);
    }

    /**
     * 元から空いていた空間を塞いで<b>外殻</b>にするか(ユーザー指定 2026-09-19)。
     *
     * <p>これが無いと、坑道や部屋が洞窟に掘り当たった時点で壁が作られず、そこで途切れて
     * 広大な空洞へ流れ出してしまう。殻を張ることで、洞窟を横切っても坑道は坑道のまま通る。
     *
     * <p><b>屋外も同じく殻で覆う</b>(2026-09-19 にユーザー指定で「地表より深いところだけ」から変更)。
     * 崖の横腹や水辺に掘り抜けると、殻が無ければそこが口を開けたままになる。開けておくのは
     * {@link NestLayout.Plan#openings}(坑口と噴出口)のそばだけ。
     */
    private static boolean shouldShell(BlockState state, BlockPos pos, NestLayout.Plan plan, int openingRadius) {
        if (!state.isAir()) {
            return false;
        }
        return !plan.nearOpening(pos, openingRadius);
    }

    /**
     * 掘ってよいマスか。自然の地形と、この MOD が汚染した地形だけ。
     * ベッドロックとプレイヤーが置いたブロックはここで弾かれる。
     */
    public static boolean isDiggable(BlockState state) {
        return state.is(ModTags.NEST_DIGGABLE);
    }

    /**
     * 巣を掘るときに<b>一緒に消す飾り</b>か。草・蔦・葉・苔の絨毯など、地形ではないので
     * {@link #isDiggable} には入らないが、残すと支えを失って空中に浮く(2026-09-19 の指摘)。
     */
    public static boolean isClearable(BlockState state) {
        return state.is(ModTags.NEST_CLEARABLE);
    }

    /** 壁として置き換えてよいマスか。流体は必ず塞ぐ。鉱石は断面を見せたいので残す。 */
    private static boolean shouldLine(BlockState state) {
        if (!state.getFluidState().isEmpty()) {
            return true;                       // 水・溶岩は必ず塞ぐ
        }
        // <b>空気はここで判断しない</b>。{@code #minecraft:replaceable} には空気が入っているので、
        // 飾りと一緒くたにすると「空洞に面した空気は全部壁」になり、坑口と噴出口まで塞がって
        // 入れない巣になる(2026-09-20 にゲーム内で発生)。空気の扱いは
        // {@link #shouldShell} が開口部を見て決める。
        if (state.isAir()) {
            return false;
        }
        if (state.is(ModTags.NEST_LINING_KEEP)) {
            return false;                      // 鉱石などはそのまま壁に残す
        }
        // 葉や草を壁にしても壁にならない。飾りは地形と同じく置き換える。
        return isDiggable(state) || isClearable(state);
    }

    /**
     * 壁に使うブロック。<b>1 種類で塗りつぶさず、まだらに混ぜる</b>(ユーザー指定 2026-09-19)。
     * 座標を 3 で割った値から決めるので、1 マスずつのごま塩ではなく<b>3 マスほどの斑</b>になる。
     *
     * @param floor 真上が空洞のマス(= 床)。草ブロックと土を多めにし、天井・側面には草を出さない
     * @param swamp 湿地系の地域か。発光菌糸は<b>湿地系だけ</b>(密林の巣に生えていた。2026-09-20 の指摘)
     */
    private static BlockState liningFor(BlockPos pos, boolean floor, boolean swamp) {
        int patch = patchHash(pos.getX(), pos.getY(), pos.getZ());
        boolean deep = pos.getY() < 0;
        int roll = patch % 100;
        if (floor) {
            // 床: 草ブロックと土が主役。奥の岩肌も少し覗く。
            if (roll < 34) {
                return ModBlocks.OVERGROWN_GRASS_BLOCK.get().defaultBlockState();
            }
            if (roll < 62) {
                return ModBlocks.OVERGROWN_DIRT.get().defaultBlockState();
            }
            if (roll < 72) {
                return ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
            }
            if (roll < 78) {
                // 発光菌糸は菌の環境のもの。密林の地域では岩肌のまま(明かりは天井の光るツタが受け持つ)。
                if (swamp) {
                    return ModBlocks.GLOWING_MYCELIUM.get().defaultBlockState();
                }
                return deep ? ModBlocks.OVERGROWN_DEEPSLATE.get().defaultBlockState()
                        : ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
            }
            return deep ? ModBlocks.OVERGROWN_DEEPSLATE.get().defaultBlockState()
                    : ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
        }
        // 天井・側面: 岩肌が主役。土と過繫茂しているブロックを斑に混ぜる。
        if (roll < 18) {
            return ModBlocks.OVERGROWN_DIRT.get().defaultBlockState();
        }
        if (roll < 34) {
            return ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
        }
        if (roll < 39) {
            if (swamp) {
                return ModBlocks.GLOWING_MYCELIUM.get().defaultBlockState();
            }
            return deep ? ModBlocks.OVERGROWN_DEEPSLATE.get().defaultBlockState()
                    : ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
        }
        if (roll < 52 && deep) {
            return ModBlocks.OVERGROWN_STONE.get().defaultBlockState();        // 深層岩の中に石の層
        }
        return deep ? ModBlocks.OVERGROWN_DEEPSLATE.get().defaultBlockState()
                : ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
    }

    /** 座標を 3 マス単位にまとめた疑似乱数。同じ斑の中では同じ値になる。 */
    private static int patchHash(int x, int y, int z) {
        int h = Mth.floor(x / 3.0F) * 73_856_093
                ^ Mth.floor(y / 3.0F) * 19_349_663
                ^ Mth.floor(z / 3.0F) * 83_492_791;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return h & 0x7FFF_FFFF;
    }

    /**
     * 掘った 1 マスに植物を生やす。床には下草、天井と側面には洞窟植物(胞子嚢・苔の塊)。
     *
     * <p>立体モデルのシダは面数が多く FPS の主因なので、{@code geoFernShare} で割合を絞る。
     */
    private static void decorate(ServerLevel level, BlockPos.MutableBlockPos cursor, long key,
                                 NestTable t, RandomSource random, boolean swamp) {
        if (!load(level, cursor, key) || !level.getBlockState(cursor).isAir()) {
            return;
        }
        BlockPos pos = cursor.immutable();

        // 床の下草
        if (random.nextDouble() < t.floorPlantChance()) {
            BlockState below = level.getBlockState(pos.below());
            if (below.isFaceSturdy(level, pos.below(), Direction.UP) && !below.isAir()) {
                BlockState plant = floorPlant(random, t);
                if (plant.canSurvive(level, pos)) {
                    level.setBlock(pos, plant, UPDATE_FLAGS);
                    return;
                }
            }
        }
        // 天井・側面の植物。<b>地域に合わせる</b>(2026-09-20 の指摘) ——
        // 湿地系なら洞窟専用の植物(微光の胞子嚢・苔の塊)、密林なら普段の垂れ蔦・壁蔦。
        if (random.nextDouble() >= t.wallPlantChance()) {
            return;
        }
        Direction exposed = WALL_FACES[random.nextInt(WALL_FACES.length)];
        BlockPos support = pos.relative(exposed.getOpposite());
        if (!level.isLoaded(support) || level.getBlockState(support).isAir()) {
            return;
        }
        if (swamp) {
            CaveGrowth.placePlant(level, pos, exposed, random);
            return;
        }
        placeJunglePlant(level, pos, exposed, random);
    }

    /**
     * 密林の地域の巣に生やす植物。天井は垂れ蔦で、明かりの代わりに光るツタをまれに混ぜる
     * (発光菌糸は菌の環境のものなので、こちらには出さない)。側面は壁の蔦。
     */
    private static void placeJunglePlant(ServerLevel level, BlockPos pos, Direction exposed, RandomSource random) {
        BlockState plant;
        if (exposed == Direction.DOWN) {
            plant = random.nextDouble() < GLOW_VINE_SHARE
                    ? ModBlocks.OVERGROWN_GLOW_VINES.get().defaultBlockState()
                    : CorruptionGrowth.randomHanging(random);
        } else {
            plant = CorruptionGrowth.randomSide(random, exposed);
        }
        if (plant.canSurvive(level, pos)) {
            level.setBlock(pos, plant, UPDATE_FLAGS);
        }
    }

    /** 密林の巣の天井で、垂れ蔦の代わりに光るツタになる割合。真っ暗にしないための明かり。 */
    private static final double GLOW_VINE_SHARE = 0.22D;

    /** 洞窟植物を付ける面(天井から下がる / 壁から横に出る)。 */
    private static final Direction[] WALL_FACES = {
            Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST
    };

    private static BlockState floorPlant(RandomSource random, NestTable t) {
        if (random.nextDouble() < t.geoFernShare()) {
            return ModBlocks.OVERGROWN_CURLED_FERN.get().defaultBlockState();
        }
        double r = random.nextDouble();
        if (r < 0.62D) {
            return ModBlocks.OVERGROWN_GROWTH.get().defaultBlockState();
        }
        if (r < 0.82D) {
            return ModBlocks.OVERGROWN_BUSH.get().defaultBlockState();
        }
        return ModBlocks.OVERGROWN_TALL_FERN.get().defaultBlockState();
    }

    @SubscribeEvent
    public static void onLevelUnload(LevelEvent.Unload event) {
        JOBS.removeIf(job -> job.level == event.getLevel());
    }

    private NestGrowthQueue() {
    }
}

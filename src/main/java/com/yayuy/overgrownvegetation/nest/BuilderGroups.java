package com.yayuy.overgrownvegetation.nest;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.NestTable;
import com.yayuy.overgrownvegetation.entity.ArboraEntity;
import com.yayuy.overgrownvegetation.entity.NestRole;
import com.yayuy.overgrownvegetation.phase.TreeCanopyGroundFinder;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * <b>建設グループ</b>(ユーザー指定 2026-09-20)。
 *
 * <p>それまでは「アルボラが 1 か所に 15 体集まったらその場に巣を掘る」だった。
 * これだと巣は必ず過繫茂バイオームのど真ん中にでき、汚染の広がりと結びつかない。
 * ここからは:
 *
 * <ol>
 *     <li>いくつもの群れから<b>アルボラだけ</b>を {@code builderRequired}(15)体引き抜いて
 *     1 つの建設グループを作る。作るかどうかは<b>確率</b>({@code builderFormChance})。</li>
 *     <li>グループは<b>過繫茂バイオームから離れた</b>地点({@code builderMinDistance}〜
 *     {@code builderMaxDistance})を選んで歩いていく。周りが過繫茂バイオームしかなければ
 *     その場に作る。</li>
 *     <li>道中は<b>自分が攻撃されるまで何も攻撃しない</b>({@link NestRole#BUILDER})。
 *     群れの編成も作らない(アルボラ以外は入れない)。</li>
 *     <li>着いたら<b>自分たちで掘る</b>。掘削キューは切羽にグループの面々が居るあいだしか進まない
 *     ({@link NestCrew})。掘り終えたらグループを解散し、以後はふつうの個体として
 *     巣の周りで狩りをし、群れを作る。</li>
 * </ol>
 *
 * <p>グループの名簿はワールドに持たない —— 各個体が {@code builderGroup}(UUID)と
 * {@code builderSite} を憶えている。{@link com.yayuy.overgrownvegetation.entity.InvaderPackBond}
 * と同じ考え方で、死んだ個体の後始末が要らなくなる。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BuilderGroups {

    /** アルボラを数えるとき、各プレイヤーの周りを見る半径。 */
    private static final double SCAN_RADIUS = 128.0D;
    /** 建設地を探す試行回数。 */
    private static final int SITE_ATTEMPTS = 40;
    /** 巣を掘り始めてよい「着いた」距離。 */
    private static final double ARRIVE = 16.0D;
    /**
     * <b>いま世界で唯一の建設グループ</b>(ユーザー指定 2026-09-20:
     * 「建設行為や建設グループはワールドに 1 つしか生まれないようにしてほしい」)。
     *
     * <p>掘削は 1 マスずつ・何分も続き、そのあいだチャンクを掴み続けるので、
     * <b>同時に 2 つ走ると重い</b>。走査はプレイヤーの周りしか見ないので、
     * 「他にグループが居ないか」を見て回ることはできない —— ここに 1 つだけ憶えておく。
     */
    @Nullable
    private static UUID activeGroup;
    /**
     * その枠をいつまで押さえておくか。面々が<b>全滅すると解散の合図が来ない</b>ので、
     * これが無いと枠が永久に埋まって二度と巣ができなくなる。
     */
    private static long activeUntil;
    /**
     * 建設を諦めるまでの猶予(tick)。海や崖に阻まれて着けないグループが、
     * <b>永続個体のまま無害に溜まり続ける</b>のを防ぐ。既定はゲーム内 2 日ぶん。
     */
    private static final long BUILD_DEADLINE = 48_000L;

    @SubscribeEvent
    public static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        NestTable t = DesignData.nest();
        if (level.getGameTime() % t.builderCheckIntervalTicks() != 0L) {
            return;
        }
        List<ArboraEntity> all = scan(level);
        if (all.isEmpty()) {
            return;
        }
        checkArrivals(level, t, all);
        tryForm(level, t, all);
    }

    // --- 着いたグループが掘り始める ---

    private static void checkArrivals(ServerLevel level, NestTable t, List<ArboraEntity> all) {
        NestData data = NestData.get(level);
        List<UUID> seen = new ArrayList<>();
        for (ArboraEntity arbora : all) {
            UUID group = arbora.builderGroup();
            BlockPos site = arbora.builderSite();
            if (group == null || site == null || seen.contains(group)) {
                continue;
            }
            seen.add(group);
            // ワールドを読み直すと枠(静的)は消えるが、アルボラ側の記憶は NBT に残っている。
            // 居るグループを枠として拾い直さないと、<b>2 つめのグループが組めてしまう</b>(2026-09-20)。
            if (activeGroup == null) {
                activeGroup = group;
                activeUntil = Math.max(arbora.builderUntil(), level.getGameTime() + BUILD_DEADLINE);
            }
            List<ArboraEntity> members = membersOf(all, group);

            // 掘り終わっていれば解散。ここを活動中の巣の判定より先に置く ——
            // 掘っているのが自分たちの巣なので、後ろに置くと永久に解散できない(2026-09-20)。
            if (data.byOrigin(site) != null) {
                disband(members, site);
                continue;
            }
            if (arbora.builderUntil() > 0L && level.getGameTime() > arbora.builderUntil()) {
                disband(members, null);               // 着けなかった。解散してふつうに戻る
                continue;
            }
            if (NestGrowthQueue.activeJobs() > 0) {
                continue;                             // 同時に 2 つ掘らない(負荷のため)
            }
            if (data.count() >= t.maxNests()) {
                continue;
            }
            int arrived = 0;
            for (ArboraEntity member : members) {
                if (member.blockPosition().distSqr(site) <= ARRIVE * ARRIVE) {
                    arrived++;
                }
            }
            // 8 体着けば掘り始める(ユーザー指定 2026-09-20)。道中で欠けるので全員は待たない。
            int need = Math.min(t.builderStartCount(), members.size());
            if (arrived < Math.max(1, need)) {
                continue;
            }
            if (data.hasNestWithin(site, t.minDistanceBetweenNests())) {
                disband(members, null);               // 先を越された。解散してふつうに戻る
                continue;
            }
            if (NestFormation.start(level, site)) {
                // <b>解散せずに掘り手として働かせる</b>(2026-09-20 の折衷案)。
                // 掘削キューは切羽にこの面々が居るあいだだけ進む。解散は掘り終わってから。
                List<UUID> ids = new ArrayList<>();
                for (ArboraEntity member : members) {
                    ids.add(member.getUUID());
                }
                NestGrowthQueue.assignCrew(level, site, ids);
                return;                               // 1 tick に 1 つだけ
            }
        }
    }

    /** グループを解散する。{@code home} を渡すと、そこを「自分の巣」として憶える。 */
    private static void disband(List<ArboraEntity> members, @Nullable BlockPos home) {
        for (ArboraEntity member : members) {
            if (activeGroup != null && activeGroup.equals(member.builderGroup())) {
                activeGroup = null;               // 枠を空ける(建設グループは世界に 1 つだけ)
            }
            member.leaveBuilderGroup(home);
        }
    }

    /** ワールドの読み直しで古い枠が残らないように(サーバー停止時)。 */
    public static void clearAll() {
        activeGroup = null;
        activeUntil = 0L;
    }

    // --- 新しいグループを組む ---

    private static void tryForm(ServerLevel level, NestTable t, List<ArboraEntity> all) {
        if (level.random.nextDouble() >= t.builderFormChance()) {
            return;                                   // 群れと違って確率で組む(ユーザー指定)
        }
        form(level, t, all);
    }

    /**
     * 建設グループを 1 つ組む。確率の抽選は<b>呼ぶ側</b>の仕事なので、ここは条件だけを見る
     * ({@code /ov nest builders} が抽選を飛ばして直接呼ぶ)。
     *
     * @return 何が起きたか(または、どの条件で止まったか)。翻訳キーではなく素の文字列 ——
     *         確認用コマンドの出力にしか使わない。
     */
    static String form(ServerLevel level, NestTable t, List<ArboraEntity> all) {
        RandomSource random = level.random;
        NestData data = NestData.get(level);
        if (data.count() >= t.maxNests()) {
            return "巣の数が上限 (" + data.count() + "/" + t.maxNests() + ")";
        }
        // 建設は<b>世界に 1 つだけ</b>(ユーザー指定 2026-09-20)。1 マスずつ掘るあいだ
        // チャンクを掴み続けるので、同時に 2 つ走らせると重い。
        if (NestGrowthQueue.activeJobs() > 0) {
            return "既に別の巣を掘っている(建設は世界に 1 つだけ)";
        }
        if (activeGroup != null && level.getGameTime() > activeUntil) {
            activeGroup = null;                       // 全滅などで解散の合図が来なかった。枠を返す
        }
        if (activeGroup != null) {
            return "既に建設グループが居る(建設グループは世界に 1 つだけ)";
        }
        List<ArboraEntity> free = new ArrayList<>();
        for (ArboraEntity arbora : all) {
            if (arbora.nestRole() == NestRole.NONE) {
                free.add(arbora);                     // 巣を持っていても、別の巣を作りに行ってよい
            }
        }
        if (free.size() < t.builderRequired()) {
            return "手空きのアルボラが足りない (" + free.size() + "/" + t.builderRequired() + ")";
        }
        List<ArboraEntity> crew = largestCluster(free, t);
        if (crew.size() < t.builderRequired()) {
            return "いちばん大きい集まりでも " + crew.size() + " 体 (要 " + t.builderRequired()
                    + "、半径 " + t.builderGatherRadius() + " 以内)";
        }
        Vec3 centre = centroid(crew);
        BlockPos site = chooseSite(level, t, centre, random);
        if (site == null) {
            return "建設地が見つからない (中心 " + Math.round(centre.x) + "," + Math.round(centre.z)
                    + " から " + t.builderMinDistance() + "〜" + t.builderMaxDistance()
                    + " の輪。読み込み済みで水でなく、他の巣とプレイヤーから離れた地表が要る)";
        }
        UUID group = UUID.randomUUID();
        long until = level.getGameTime() + BUILD_DEADLINE;
        activeGroup = group;
        activeUntil = until;
        for (ArboraEntity member : crew) {
            member.joinBuilderGroup(group, site, until);
        }
        return "アルボラ " + crew.size() + " 体で建設グループを組んだ → " + site.getX() + ","
                + site.getY() + "," + site.getZ()
                + (ModBiomes.isOvergrownLike(level.getBiome(site)) ? " (過繫茂バイオームの中)" : "");
    }

    /** {@code builderGatherRadius} 以内でいちばん多く集まっている面々。 */
    private static List<ArboraEntity> largestCluster(List<ArboraEntity> free, NestTable t) {
        double gatherSq = (double) t.builderGatherRadius() * t.builderGatherRadius();
        List<ArboraEntity> best = new ArrayList<>();
        for (ArboraEntity seed : free) {
            List<ArboraEntity> crew = new ArrayList<>();
            for (ArboraEntity other : free) {
                if (other.distanceToSqr(seed) <= gatherSq) {
                    crew.add(other);
                }
                if (crew.size() >= t.builderRequired()) {
                    break;
                }
            }
            if (crew.size() > best.size()) {
                best = crew;
            }
            if (best.size() >= t.builderRequired()) {
                break;
            }
        }
        return best;
    }

    /**
     * <b>自然発生の下見</b>({@code /ov nest simulate}、2026-09-20)。
     * 実際に組まずに、条件を上から順に見て「いまどこで止まっているか」を並べて返す。
     *
     * <p>営巣は「アルボラが集まる → 確率で建設グループ → 歩いて行く → 掘る」と段が多く、
     * どこで止まっているかは<b>ゲーム内で見ていても分からない</b>。ここで 1 段ずつ出す。
     */
    public static List<String> simulate(ServerLevel level) {
        NestTable t = DesignData.nest();
        List<String> out = new ArrayList<>();
        NestData data = NestData.get(level);
        List<ArboraEntity> all = scan(level);
        int free = 0;
        int builders = 0;
        for (ArboraEntity arbora : all) {
            if (arbora.nestRole() == NestRole.NONE) {
                free++;
            }
            if (arbora.builderGroup() != null) {
                builders++;
            }
        }
        out.add("巣: " + data.count() + "/" + t.maxNests() + "  掘削中: " + NestGrowthQueue.activeJobs()
                + (activeGroup != null ? "  建設グループ: 稼働中" : "  建設グループ: なし"));
        if (NestGrowthQueue.activeJobs() > 0 || activeGroup != null) {
            out.add("→ 建設は世界に 1 つだけ。いまのが終わるまで次は組まれない");
        }
        out.add("アルボラ: プレイヤーの周り " + (int) SCAN_RADIUS + " マスに " + all.size()
                + " 体、うち手空き " + free + " 体、建設グループ中 " + builders + " 体");
        if (all.isEmpty()) {
            out.add("→ 1 体も居ない。営巣はプレイヤーの近くでしか判定しないので、まず湧く場所へ行くこと");
            return out;
        }
        List<ArboraEntity> freeList = new ArrayList<>();
        for (ArboraEntity arbora : all) {
            if (arbora.nestRole() == NestRole.NONE) {
                freeList.add(arbora);
            }
        }
        List<ArboraEntity> crew = largestCluster(freeList, t);
        out.add("いちばん大きい集まり: " + crew.size() + "/" + t.builderRequired()
                + " 体 (半径 " + t.builderGatherRadius() + " 以内)");
        if (crew.size() >= t.builderRequired()) {
            Vec3 centre = centroid(crew);
            BlockPos site = chooseSite(level, t, centre, level.random);
            out.add(site == null
                    ? "建設地: 見つからない (" + t.builderMinDistance() + "〜" + t.builderMaxDistance()
                      + " の輪が読み込まれていない可能性が高い)"
                    : "建設地: " + site.getX() + "," + site.getY() + "," + site.getZ()
                      + (ModBiomes.isOvergrownLike(level.getBiome(site)) ? " (過繫茂バイオームの中)" : " (外)"));
            long mean = Math.round(t.builderCheckIntervalTicks() / Math.max(1.0E-6D, t.builderFormChance()));
            out.add("→ 条件は揃っている。判定は " + t.builderCheckIntervalTicks() + " tick ごとに "
                    + Math.round(t.builderFormChance() * 100) + "%、平均 " + mean + " tick ("
                    + (mean / 20) + " 秒) で組まれる");
        } else {
            out.add("→ ここで止まっている。アルボラが " + t.builderRequired() + " 体、半径 "
                    + t.builderGatherRadius() + " 以内に集まる必要がある");
        }
        return out;
    }

    /**
     * 面々を直接渡して組ませる(GameTest 用)。{@link #scan} はプレイヤーの周りしか見ないので、
     * プレイヤーの居ないテストサーバーでは自然発生の道筋をここからしか辿れない。
     */
    public static String formFrom(ServerLevel level, List<ArboraEntity> crew) {
        return form(level, DesignData.nest(), crew);
    }

    /** {@code /ov nest builders} —— 確率の抽選を飛ばして、いますぐ 1 グループ組ませる。 */
    public static String forceForm(ServerLevel level) {
        List<ArboraEntity> all = scan(level);
        if (all.isEmpty()) {
            return "プレイヤーの周りにアルボラが居ない";
        }
        return form(level, DesignData.nest(), all);
    }

    private static Vec3 centroid(List<ArboraEntity> crew) {
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        for (ArboraEntity arbora : crew) {
            x += arbora.getX();
            y += arbora.getY();
            z += arbora.getZ();
        }
        return new Vec3(x / crew.size(), y / crew.size(), z / crew.size());
    }

    /**
     * 建設地を選ぶ。まず<b>過繫茂バイオームの外</b>を {@code builderMinDistance}〜
     * {@code builderMaxDistance} の輪の中で探し、見つからなければ<b>その場の近く</b>にする
     * (周りが過繫茂バイオームしかない場合。ユーザー指定 2026-09-20)。
     */
    @Nullable
    private static BlockPos chooseSite(ServerLevel level, NestTable t, Vec3 centre, RandomSource random) {
        int min = t.builderMinDistance();
        int max = Math.max(min + 1, t.builderMaxDistance());
        for (int i = 0; i < SITE_ATTEMPTS; i++) {
            double angle = random.nextDouble() * Math.PI * 2.0D;
            int distance = min + random.nextInt(max - min + 1);
            int x = (int) Math.round(centre.x + Math.cos(angle) * distance);
            int z = (int) Math.round(centre.z + Math.sin(angle) * distance);
            BlockPos site = validSite(level, t, x, z);
            if (site != null && !ModBiomes.isOvergrownLike(level.getBiome(site))) {
                return site;
            }
        }
        // <b>距離を詰めて探し直す</b>(2026-09-20)。狙いの輪(96〜256)は<b>たいてい読み込まれていない</b> ——
        // 既定の描画距離では 160 マスあたりまでしか存在せず、`validSite` の最初の関門で落ちる。
        // ここを飛ばすといきなり「その場に作る」へ落ち、<b>過繫茂バイオームのど真ん中にばかり</b>できてしまう。
        for (int near = min; near >= 32; near -= 16) {
            for (int i = 0; i < SITE_ATTEMPTS; i++) {
                double angle = random.nextDouble() * Math.PI * 2.0D;
                int x = (int) Math.round(centre.x + Math.cos(angle) * near);
                int z = (int) Math.round(centre.z + Math.sin(angle) * near);
                BlockPos site = validSite(level, t, x, z);
                if (site != null && !ModBiomes.isOvergrownLike(level.getBiome(site))) {
                    return site;
                }
            }
        }
        // 周りが過繫茂バイオームだらけ。その場に作る。
        for (int i = 0; i < SITE_ATTEMPTS; i++) {
            int x = (int) Math.round(centre.x) + random.nextInt(33) - 16;
            int z = (int) Math.round(centre.z) + random.nextInt(33) - 16;
            BlockPos site = validSite(level, t, x, z);
            if (site != null) {
                return site;
            }
        }
        return null;
    }

    /** その柱に巣を作れるか(地表があり、水でなく、他の巣とプレイヤーから離れている)。 */
    @Nullable
    private static BlockPos validSite(ServerLevel level, NestTable t, int x, int z) {
        BlockPos probe = new BlockPos(x, level.getSeaLevel(), z);
        if (!level.hasChunkAt(probe)) {
            return null;                              // 未ロード。地形を触らない
        }
        BlockPos surface = TreeCanopyGroundFinder.findSpawnSurface(level, x, z);
        if (surface == null || !level.getFluidState(surface).isEmpty()) {
            return null;
        }
        if (tooMuchWaterAround(level, surface)) {
            return null;                              // 湖や海の縁。掘ると水中に広がる
        }
        NestData data = NestData.get(level);
        if (data.hasNestWithin(surface, t.minDistanceBetweenNests())) {
            return null;
        }
        double keepAway = t.minDistanceFromPlayer();
        if (keepAway > 0.0D) {
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator()) {
                    continue;
                }
                if (player.distanceToSqr(surface.getX() + 0.5D, surface.getY() + 0.5D,
                        surface.getZ() + 0.5D) <= keepAway * keepAway) {
                    return null;                      // 拠点の真下を掘らせない
                }
            }
        }
        return surface;
    }

    /**
     * 巣が広がる範囲に<b>水が多すぎないか</b>(2026-09-21 のユーザー報告
     * 「水中に巣を拡張してしまう」)。
     *
     * <p>入口が乾いていても、巣は横に {@code spread} マス広がる。湖や海の縁を選ぶと
     * 坑道が水の下へ潜り込み、<b>水底にぽっかり空いた巣</b>になる。そうなると水を避ける
     * アルボラが切羽に近づけず、建設が止まる。
     *
     * <p>掘る側でも水には入らないようにしてあるが({@code NestLayout.inWater})、
     * <b>そもそも水辺を選ばない</b>ほうが巣の形が素直になる。
     */
    private static boolean tooMuchWaterAround(ServerLevel level, BlockPos site) {
        int spread = DesignData.nest().spread();
        int wet = 0;
        int seen = 0;
        for (int i = 0; i < WATER_SAMPLES; i++) {
            double angle = i * (Math.PI * 2.0D / WATER_SAMPLES);
            for (double f = 0.45D; f <= 1.0D; f += 0.55D) {
                int x = site.getX() + (int) Math.round(Math.cos(angle) * spread * f);
                int z = site.getZ() + (int) Math.round(Math.sin(angle) * spread * f);
                if (!level.hasChunkAt(new BlockPos(x, site.getY(), z))) {
                    continue;                         // 未ロード。数に入れない
                }
                seen++;
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                int floor = level.getHeight(Heightmap.Types.OCEAN_FLOOR, x, z);
                if (top - floor >= WATER_DEEP) {
                    wet++;
                }
            }
        }
        return seen > 0 && (double) wet / seen > WATER_SHARE;
    }

    /** 水を調べる方角の数。 */
    private static final int WATER_SAMPLES = 12;
    /** これより深い水があれば「水」とみなす(1 マスの水たまりは数えない)。 */
    private static final int WATER_DEEP = 2;
    /** 調べた柱のうち水がこの割合を超えたら、そこには作らない。 */
    private static final double WATER_SHARE = 0.25D;

    // --- 走査 ---

    private static List<ArboraEntity> scan(ServerLevel level) {
        List<ArboraEntity> all = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isSpectator()) {
                continue;
            }
            AABB box = player.getBoundingBox().inflate(SCAN_RADIUS);
            for (ArboraEntity arbora : level.getEntitiesOfClass(ArboraEntity.class, box, ArboraEntity::isAlive)) {
                if (!all.contains(arbora)) {
                    all.add(arbora);
                }
            }
        }
        return all;
    }

    private static List<ArboraEntity> membersOf(List<ArboraEntity> all, UUID group) {
        List<ArboraEntity> out = new ArrayList<>();
        for (ArboraEntity arbora : all) {
            if (group.equals(arbora.builderGroup())) {
                out.add(arbora);
            }
        }
        return out;
    }

    private BuilderGroups() {
    }
}

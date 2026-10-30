package com.yayuy.overgrownvegetation.corruption;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.PortalTable;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.BlockUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EndPortalFrameBlock;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.end.EndDragonFight;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

/**
 * <b>ポータルからネザー・エンドへの侵食</b>(2026-10-02、ストーリーライン {@code story.portal_spread})。
 *
 * <h2>ネザーポータル</h2>
 * <ol>
 *     <li>汚染が<b>火の付いたネザーポータルの枠</b>(本体のポータルブロックに隣接する黒曜石)に届くと、
 *     {@link CorruptionRegistry#corrupt(ServerLevel, BlockPos, BlockState, net.minecraft.util.RandomSource)} が
 *     {@link #interceptNetherFrame} を呼ぶ。枠は<b>必ず覆うだけ</b>にする —— 対応表に無い立方体は 3 割が
 *     汎用の汚染ブロックへ置き換わるが、枠が黒曜石でなくなるとポータルが消えて、それ以上どこへも伝わらない。</li>
 *     <li>そのポータルを待ち行列へ積む(汚染の処理の途中で別の次元のチャンクを作らないよう、tick の終わりに 1 つずつ)。</li>
 *     <li>向こう側の出口を<b>バニラと同じ探し方</b>で求める({@code NetherPortalBlock#getPortalDestination} の写し:
 *     座標を次元の倍率で割り、{@code PortalForcer#findClosestPortalPosition}、無ければ {@code createPortal})。
 *     エンティティが通ったときと同じ出口になる。</li>
 *     <li>両端のポータルを<b>同時に</b>侵食済みとして覚え({@link PortalCorruptionData})、出口のまわりに
 *     {@link NoisyCorruptionBurst#sphere} で汚染の球を置く(G4 の死亡時の球と同じ。バイオームの書き換えも
 *     いつもの閾値任せ)。両端を先に覚えるので、球が出口の枠に触れても戻ってこない。</li>
 * </ol>
 * ネザー側で広がった侵食が<b>別の</b>ポータルに届いたときの逆流は {@link PortalTable#backflow()}(返事待ち・既定 true)。
 *
 * <h2>エンドポータル</h2>
 * 枠({@code end_portal_frame})と本体は汚染されない(免疫)ので、<b>隣のマスが汚染されたとき</b>に
 * {@link #noticeEndFrame} で気付く。要塞のポータルの部屋では、枠の下は石レンガ、内側は溶岩(源泉は玄武岩へ固まる)。
 * 汚染の 1 マスごとに隣 6 マスを読むと深部の一括置換が重くなるので、<b>石レンガ・階段・溶岩・エンドストーン</b>だけで見る。
 * 目のはまっていない枠も数えるかは {@link PortalTable#endFrameNeedsEye()}(返事待ち・既定 false = 数える)。
 * 「森がエンドに着いた」ことと、そのときエンドラが既に倒されていたかを覚え(一度だけ)、エンドの本島をまるごと
 * 過繫茂したエンドにする({@link EndOvergrowth}。出口の足場に球を置くのは 10-02 にやめた —— 足場が無いと奈落に浮く)。
 *
 * <h2>まだ無いもの</h2>
 * 向こう側で広がり続けるのは今ある仕組みだけ(ブロック単位の広がり・過繁茂チャンクの這い出し・運び手の Mob)。
 * どれも<b>読み込まれているチャンクでしか動かない</b>ので、誰もいないネザーでは止まっている
 * (オーバーワールドの留守中の広がり = 台帳の広がりはオーバーワールド専用)。エンドは本島だけを一度に塗る。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class PortalCorruption {

    /** ポータル本体の矩形を探す範囲(バニラの {@code NetherPortalBlock} と同じ 21)。 */
    private static final int PORTAL_SCAN = 21;

    /** 待ち行列の種類。 */
    private enum Kind {
        /** ネザーポータルの向こう側へ伝える。 */
        NETHER,
        /** 要塞のエンドポータルの輪を腫瘍の枠へ置き換えて開く(輪ごと)。 */
        RING,
        /** 森がエンドに着く(ワールドで一度だけ)。 */
        END
    }

    /** 待ち行列の 1 件。{@code anchor} はネザーポータルなら矩形の最小の角、エンドポータルなら気付いた枠の位置。 */
    private record Pending(ResourceKey<Level> dimension, BlockPos portal, Kind kind, GlobalPos anchor) {
        boolean end() {
            return kind == Kind.END;
        }
    }

    /**
     * 1 件ぶんの結果({@code /ov portal}・GameTest 用)。
     *
     * @param from    こちら側(ネザーポータルなら矩形の最小の角)
     * @param to      向こう側(ネザーポータルなら出口の矩形の最小の角、エンドなら本島の中心 (0, 0, 0))
     * @param created 出口のポータルを新しく作ったか(エンドでは常に false)
     * @param placed  汚染の球で置き換えたブロック数(覆っただけのマスは数えない)。エンドは塗る順番待ちに積んだ本島のチャンク数
     */
    public record Result(GlobalPos from, GlobalPos to, boolean created, int placed) {
    }

    private static final ArrayDeque<Pending> QUEUE = new ArrayDeque<>();
    /** 積んだまま処理していないネザーポータル・エンドポータルの輪(同じ枠に何度も届くので、積むのは 1 回だけ)。 */
    private static final Set<GlobalPos> QUEUED = new HashSet<>();
    /** エンド行きを積んだまま処理していないか。エンドへ着くのはワールドで一度だけなので 1 本で足りる。 */
    private static boolean endQueued;

    // ------------------------------------------------------------------------------------------
    // 汚染の処理から呼ばれる入口
    // ------------------------------------------------------------------------------------------

    /**
     * 汚染しようとしているマスが<b>火の付いたネザーポータルの枠</b>なら、ポータルを待ち行列へ積んで {@code true}。
     * 呼ぶ側({@link CorruptionRegistry})はそのとき置き換えずに覆うだけにする。
     * 対応表に無いブロック(黒曜石はそう)の分かれ道からだけ呼ぶので、ふだんの汚染の重さは変わらない。
     */
    static boolean interceptNetherFrame(ServerLevel level, BlockPos pos, BlockState state) {
        if (!state.isPortalFrame(level, pos)) {
            return false;
        }
        BlockPos portal = adjacent(level, pos, s -> s.is(Blocks.NETHER_PORTAL));
        if (portal == null) {
            return false; // 火の付いていない枠は普通の黒曜石と同じ
        }
        PortalTable table = DesignData.portal();
        if (!table.enabled() || (level.dimension() == Level.NETHER && !table.backflow())) {
            return true; // 伝わらなくても、枠は壊さない
        }
        GlobalPos anchor = GlobalPos.of(level.dimension(), portalRect(level, portal).minCorner.immutable());
        if (!QUEUED.contains(anchor) && !PortalCorruptionData.get(level.getServer()).isInfected(anchor)) {
            QUEUED.add(anchor);
            QUEUE.add(new Pending(level.dimension(), portal.immutable(), Kind.NETHER, anchor));
        }
        return true;
    }

    /**
     * 汚染しようとしているマスが<b>エンドポータルの枠(か本体)に隣接</b>していれば、エンド行きを待ち行列へ積む。
     * 置き換え自体には口を出さない(枠も本体も免疫なので、隣が汚染されるだけ)。
     */
    static void noticeEndFrame(ServerLevel level, BlockPos pos, BlockState state) {
        if (level.dimension() == Level.END || !mayTouchEndFrame(state)) {
            return;
        }
        PortalTable table = DesignData.portal();
        if (!table.enabled()) {
            return;
        }
        boolean needsEye = table.endFrameNeedsEye();
        BlockPos frame = adjacent(level, pos, s -> s.is(Blocks.END_PORTAL)
                || s.is(ModBlocks.OVERGROWN_END_PORTAL_FRAME.get())
                || (s.is(Blocks.END_PORTAL_FRAME) && (!needsEye || s.getValue(EndPortalFrameBlock.HAS_EYE))));
        if (frame == null) {
            return;
        }
        GlobalPos anchor = GlobalPos.of(level.dimension(), frame.immutable());
        // 輪を開くのは要塞ごと(まだ元の枠なら)。気付いた枠の位置で積み、処理するときに輪全体を探す。
        if (level.getBlockState(frame).is(Blocks.END_PORTAL_FRAME) && QUEUED.add(anchor)) {
            QUEUE.add(new Pending(level.dimension(), frame.immutable(), Kind.RING, anchor));
        }
        // エンドへ着くのはワールドで一度だけ。
        if (!endQueued && !PortalCorruptionData.get(level.getServer()).endReached()) {
            endQueued = true;
            QUEUE.add(new Pending(level.dimension(), frame.immutable(), Kind.END, anchor));
        }
    }

    /** エンドポータルの枠(元の枠か、腫瘍の枠)か。 */
    private static boolean isEndFrame(BlockState state) {
        return state.is(Blocks.END_PORTAL_FRAME) || state.is(ModBlocks.OVERGROWN_END_PORTAL_FRAME.get());
    }

    /**
     * 要塞のポータルの部屋で枠に隣接しうるブロックか(枠の下の石レンガ・手前の階段・内側の溶岩)。
     * エンドストーンは、枠をエンドストーンの上に置き直した場合の保険。
     */
    private static boolean mayTouchEndFrame(BlockState state) {
        return state.is(BlockTags.STONE_BRICKS) || state.is(BlockTags.STAIRS)
                || state.is(Blocks.LAVA) || state.is(Blocks.END_STONE);
    }

    @Nullable
    private static BlockPos adjacent(Level level, BlockPos pos, Predicate<BlockState> test) {
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (Direction dir : Direction.values()) {
            cursor.setWithOffset(pos, dir);
            if (level.isLoaded(cursor) && test.test(level.getBlockState(cursor))) {
                return cursor.immutable();
            }
        }
        return null;
    }

    /** ネザーポータル本体の矩形(バニラと同じ求め方。どのポータルブロックから求めても同じ角になる)。 */
    private static BlockUtil.FoundRectangle portalRect(Level level, BlockPos portalBlock) {
        BlockState state = level.getBlockState(portalBlock);
        Direction.Axis axis = state.getOptionalValue(NetherPortalBlock.AXIS).orElse(Direction.Axis.X);
        return BlockUtil.getLargestRectangleAround(portalBlock, axis, PORTAL_SCAN, Direction.Axis.Y, PORTAL_SCAN,
                p -> level.getBlockState(p) == state);
    }

    /** {@code pos} のまわり(±2)のネザーポータル本体の矩形の角。無ければ null。 */
    @Nullable
    static BlockPos portalAnchorNear(Level level, BlockPos pos) {
        for (BlockPos p : BlockPos.betweenClosed(pos.offset(-2, -2, -2), pos.offset(2, 3, 2))) {
            if (level.getBlockState(p).is(Blocks.NETHER_PORTAL)) {
                return portalRect(level, p).minCorner.immutable();
            }
        }
        return null;
    }

    // ------------------------------------------------------------------------------------------
    // 通ったときにポータルの対を覚える(2026-10-23 胞子の場 F、相談セッションの答え)
    // ------------------------------------------------------------------------------------------

    /** ネザーポータルで次元を移る途中の人・Mob: UUID → {元の次元がオーバーワールドか, 元のポータルの角}。 */
    private static final java.util.Map<java.util.UUID, Object[]> TRAVELING = new java.util.HashMap<>();

    @SubscribeEvent
    public static void onTravel(net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent event) {
        if (!(event.getEntity().level() instanceof ServerLevel from)) return;
        ResourceKey<Level> to = event.getDimension();
        boolean owToNether = from.dimension() == Level.OVERWORLD && to == Level.NETHER;
        boolean netherToOw = from.dimension() == Level.NETHER && to == Level.OVERWORLD;
        if (!owToNether && !netherToOw) return;
        BlockPos anchor = portalAnchorNear(from, event.getEntity().blockPosition());
        if (anchor == null) return;
        if (TRAVELING.size() > 256) TRAVELING.clear();
        TRAVELING.put(event.getEntity().getUUID(), new Object[]{owToNether, anchor});
    }

    @SubscribeEvent
    public static void onArrive(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (TRAVELING.isEmpty() || !(event.getLevel() instanceof ServerLevel to)) return;
        Object[] t = TRAVELING.remove(event.getEntity().getUUID());
        if (t == null) return;
        boolean owToNether = (Boolean) t[0];
        if (to.dimension() != (owToNether ? Level.NETHER : Level.OVERWORLD)) return;
        BlockPos fromAnchor = (BlockPos) t[1];
        // 着いた所のポータルは、この tick ではまだ置かれていないことがある(作られた出口)ので、次の tick に探す
        BlockPos arrived = event.getEntity().blockPosition();
        to.getServer().execute(() -> {
            BlockPos toAnchor = portalAnchorNear(to, arrived);
            if (toAnchor == null) return;
            PortalCorruptionData data = PortalCorruptionData.get(to.getServer());
            if (owToNether) data.addPair(fromAnchor, toAnchor);
            else data.addPair(toAnchor, fromAnchor);
        });
    }

    // ------------------------------------------------------------------------------------------
    // 待ち行列
    // ------------------------------------------------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (CorruptionSwitch.isOff()) {
            return; // /ov corruption false(2026-10-10 ユーザー指定): 止める(順番待ちは残り、オンで続きから)
        }
        if (!QUEUE.isEmpty()) {
            processOne(event.getServer());
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        QUEUE.clear();
        QUEUED.clear();
        endQueued = false;
    }

    /** 積んである件数({@code /ov portal status}・GameTest 用)。 */
    public static int queuedCount() {
        return QUEUE.size();
    }

    /** 積んであるものを今すぐ全部処理する({@code /ov portal step}・GameTest 用)。 */
    public static List<Result> processAllNow(MinecraftServer server) {
        List<Result> out = new ArrayList<>();
        while (!QUEUE.isEmpty()) {
            Result r = processOne(server);
            if (r != null) {
                out.add(r);
            }
        }
        return out;
    }

    @Nullable
    private static Result processOne(MinecraftServer server) {
        Pending p = QUEUE.poll();
        if (p == null) {
            return null;
        }
        try {
            return switch (p.kind()) {
                case NETHER -> crossNether(server, p);
                case RING -> openRing(server, p);
                case END -> reachEnd(server, p);
            };
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("ポータルを越えた侵食の処理に失敗しました: {}", p, e);
            return null;
        } finally {
            if (p.end()) {
                endQueued = false;
            } else {
                QUEUED.remove(p.anchor());
            }
        }
    }

    /**
     * {@code /ov portal infect} 用: その位置のネザーポータル(本体のブロック)を、汚染が枠に届いたのと同じ扱いで
     * 今すぐ処理する。ポータルでなければ null。
     */
    @Nullable
    public static Result infectNow(ServerLevel level, BlockPos portalBlock) {
        if (!level.getBlockState(portalBlock).is(Blocks.NETHER_PORTAL)) {
            return null;
        }
        GlobalPos anchor = GlobalPos.of(level.dimension(), portalRect(level, portalBlock).minCorner.immutable());
        return crossNether(level.getServer(), new Pending(level.dimension(), portalBlock.immutable(), Kind.NETHER, anchor));
    }

    // ------------------------------------------------------------------------------------------
    // 向こう側
    // ------------------------------------------------------------------------------------------

    @Nullable
    private static Result crossNether(MinecraftServer server, Pending p) {
        ServerLevel from = server.getLevel(p.dimension());
        if (from == null) {
            return null;
        }
        BlockState portalState = from.getBlockState(p.portal());
        if (!portalState.is(Blocks.NETHER_PORTAL)) {
            return null; // 積んでから処理するまでに消えた
        }
        BlockUtil.FoundRectangle fromRect = portalRect(from, p.portal());
        GlobalPos fromAnchor = GlobalPos.of(from.dimension(), fromRect.minCorner.immutable());
        PortalCorruptionData data = PortalCorruptionData.get(server);
        if (data.isInfected(fromAnchor)) {
            return null;
        }

        // ここから NetherPortalBlock#getPortalDestination / getExitPortal と同じ求め方。
        ResourceKey<Level> toKey = from.dimension() == Level.NETHER ? Level.OVERWORLD : Level.NETHER;
        ServerLevel to = server.getLevel(toKey);
        if (to == null) {
            data.markInfected(fromAnchor);
            return null;
        }
        WorldBorder border = to.getWorldBorder();
        double scale = DimensionType.getTeleportationScale(from.dimensionType(), to.dimensionType());
        BlockPos exitPos = border.clampToBounds(fromRect.minCorner.getX() * scale, fromRect.minCorner.getY(),
                fromRect.minCorner.getZ() * scale);
        Direction.Axis axis = portalState.getValue(NetherPortalBlock.AXIS);

        Optional<BlockPos> existing = to.getPortalForcer().findClosestPortalPosition(exitPos, toKey == Level.NETHER, border);
        BlockUtil.FoundRectangle toRect;
        boolean created;
        if (existing.isPresent()) {
            toRect = portalRect(to, existing.get());
            created = false;
        } else {
            Optional<BlockUtil.FoundRectangle> made = to.getPortalForcer().createPortal(exitPos, axis);
            if (made.isEmpty()) {
                OvergrownVegetation.LOGGER.warn("ポータルを越えた侵食: {} の出口を作れませんでした(ワールドの境界の外?)", fromAnchor);
                data.markInfected(fromAnchor);
                return null;
            }
            toRect = made.get();
            created = true;
        }
        GlobalPos toAnchor = GlobalPos.of(toKey, toRect.minCorner.immutable());
        data.markInfected(fromAnchor);
        data.markInfected(toAnchor);
        // 2026-10-23 胞子の場 F: 出口に汚染の球を置き・出口のチャンクを過繁茂にするのはやめた。両端の対を覚え、
        // 以後は両側のポータルのチャンクの胞子が毎回少しずつ染み出す(spore.field.PortalSeepage。向こうで発芽するのは場まかせ)
        BlockPos owAnchor = toKey == Level.NETHER ? fromAnchor.pos() : toAnchor.pos();
        BlockPos netherAnchor = toKey == Level.NETHER ? toAnchor.pos() : fromAnchor.pos();
        data.addPair(owAnchor, netherAnchor);
        OvergrownVegetation.LOGGER.info("ポータルを越えた侵食: {} ↔ {} をつないだ(出口を{})",
                fromAnchor, toAnchor, created ? "作った" : "見つけた");
        return new Result(fromAnchor, toAnchor, created, 0);
    }

    /**
     * 要塞のエンドポータルの輪を、光る腫瘍のはまった枠({@link ModBlocks#OVERGROWN_END_PORTAL_FRAME})へ置き換え、
     * 12 個そろっていれば内側 3 × 3 にエンドポータルを置いて<b>無理やり開く</b>(2026-10-02 ユーザー指定)。
     *
     * <p>輪は気付いた枠と同じ高さの、まわり ±4 マスの枠から探す(バニラの輪は 5 × 5 の角を除いた 12 個)。
     * 枠が欠けていても、ある分は腫瘍の枠にする(開かない)。
     *
     * @return {@code placed} は置き換えた枠の数。{@code created} は開いたか
     */
    @Nullable
    private static Result openRing(MinecraftServer server, Pending p) {
        ServerLevel level = server.getLevel(p.dimension());
        if (level == null) {
            return null;
        }
        BlockPos origin = p.portal();
        List<BlockPos> frames = new ArrayList<>();
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(origin.offset(-4, 0, -4), origin.offset(4, 0, 4))) {
            if (isEndFrame(level.getBlockState(pos))) {
                frames.add(pos.immutable());
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
        }
        if (frames.isEmpty()) {
            return null;
        }
        int replaced = 0;
        for (BlockPos pos : frames) {
            BlockState old = level.getBlockState(pos);
            if (!old.is(Blocks.END_PORTAL_FRAME)) {
                continue;
            }
            BlockState tumor = ModBlocks.OVERGROWN_END_PORTAL_FRAME.get().defaultBlockState()
                    .setValue(com.yayuy.overgrownvegetation.block.OvergrownEndPortalFrameBlock.FACING,
                            old.getValue(EndPortalFrameBlock.FACING));
            level.setBlock(pos, tumor, Block.UPDATE_CLIENTS);
            replaced++;
        }
        boolean opened = frames.size() >= 12 && maxX - minX == 4 && maxZ - minZ == 4;
        if (opened) {
            BlockPos.MutableBlockPos inner = new BlockPos.MutableBlockPos();
            for (int x = minX + 1; x <= maxX - 1; x++) {
                for (int z = minZ + 1; z <= maxZ - 1; z++) {
                    inner.set(x, origin.getY(), z);
                    level.setBlock(inner, Blocks.END_PORTAL.defaultBlockState(), Block.UPDATE_CLIENTS);
                }
            }
            // バニラで目を 12 個はめ終えたときと同じ音(全員に聞こえる)。
            level.globalLevelEvent(1038, new BlockPos(minX + 2, origin.getY(), minZ + 2), 0);
        }
        GlobalPos center = GlobalPos.of(level.dimension(), new BlockPos((minX + maxX) / 2, origin.getY(), (minZ + maxZ) / 2));
        OvergrownVegetation.LOGGER.info("ポータルを越えた侵食: エンドポータルの輪 {} の枠 {} 個を腫瘍に置き換えた({})",
                center, replaced, opened ? "開いた" : "枠が足りないので開かない");
        return new Result(center, center, opened, replaced);
    }

    /** {@code /ov portal end} 用: エンドポータルの枠に届いたのと同じ扱いで、今すぐ森をエンドへ着かせる。もう着いていれば null。 */
    @Nullable
    public static Result reachEndNow(MinecraftServer server, GlobalPos from) {
        return reachEnd(server, new Pending(from.dimension(), from.pos(), Kind.END, from));
    }

    @Nullable
    private static Result reachEnd(MinecraftServer server, Pending p) {
        PortalCorruptionData data = PortalCorruptionData.get(server);
        ServerLevel end = server.getLevel(Level.END);
        if (end == null || data.endReached()) {
            return null;
        }
        EndDragonFight fight = end.getDragonFight();
        boolean dragonKilled = fight != null && fight.hasPreviouslyKilledDragon();
        // 出口の足場(100, 49, 0)に球を置くのはやめた(2026-10-02): まだ誰も入っていないエンドでは足場が無く、
        // 汚染の塊が奈落の上に浮いていた。代わりに本島をまるごと過繫茂したエンドにする(EndOvergrowth)。
        // 足場はバニラがプレイヤーを送るときに作る。
        data.markEndReached(server.overworld().getGameTime(), dragonKilled, p.anchor());
        int queued = EndOvergrowth.begin(server);
        GlobalPos toAnchor = GlobalPos.of(Level.END, BlockPos.ZERO);
        OvergrownVegetation.LOGGER.info("ポータルを越えた侵食: 森がエンドに着いた({} から。エンドラは{}、本島の {} チャンクを塗る)",
                p.anchor(), dragonKilled ? "既に倒されていた" : "まだ倒されていない", queued);
        return new Result(p.anchor(), toAnchor, false, queued);
    }

    /** 出口のまわりのチャンクを読み込んで(無ければ作って)から、汚染の球を置く。 */
    private static int seed(ServerLevel level, BlockPos center, PortalTable table) {
        int reach = table.seedRadius() + (int) Math.ceil(table.seedNoise()) + 1;
        loadAround(level, center, reach);
        return NoisyCorruptionBurst.sphere(level, Vec3.atBottomCenterOf(center), table.seedRadius(), table.seedNoise());
    }

    private static void loadAround(ServerLevel level, BlockPos center, int reach) {
        int minX = (center.getX() - reach) >> 4;
        int maxX = (center.getX() + reach) >> 4;
        int minZ = (center.getZ() - reach) >> 4;
        int maxZ = (center.getZ() + reach) >> 4;
        for (int cx = minX; cx <= maxX; cx++) {
            for (int cz = minZ; cz <= maxZ; cz++) {
                level.getChunk(cx, cz);
            }
        }
    }

    /** {@code /ov portal reset} 用。記録と待ち行列を消す(置いた汚染はそのまま)。 */
    public static void reset(MinecraftServer server) {
        QUEUE.clear();
        QUEUED.clear();
        endQueued = false;
        EndOvergrowth.reset();
        PortalCorruptionData.get(server).clear();
    }

    private PortalCorruption() {
    }
}

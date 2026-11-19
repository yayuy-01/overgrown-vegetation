package com.yayuy.overgrownvegetation.organ;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.perf.PerfJob;
import com.yayuy.overgrownvegetation.perf.TickBudget;
import com.yayuy.overgrownvegetation.phase.PhaseData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.block.AmethystClusterBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.MultifaceBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import javax.annotation.Nullable;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 森の器官を世界で動かす所(2026-11-17)。毎 tick:
 * <ol>
 *   <li>育ち具合を時計で見る(育ち切ったら役割が働く。読み込まれていなくても)。</li>
 *   <li>読み込まれている器官は、育ち具合のぶんまでブロックを追いつきで置く({@link PerfJob#ORGAN_BUILD} の予算の中で)。
 *       芽吹いたばかりの器官には芽(仮の芯 + 嚢)を置く —— 育つ前に見つけて潰せるように。</li>
 *   <li>枯れた器官は、嚢・卵・光る筋・芯を枯れの状態に替えていく。</li>
 *   <li>100 tick ごとに役割(フェーズのポイント・胞子・苗床・根晶)と守りの Mob。</li>
 * </ol>
 * 設計図は背景のスレッドで作り、メモリにだけ持つ。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class OrganWorld {
    private OrganWorld() {
    }

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "OV organ blueprints");
        t.setDaemon(true);
        return t;
    });
    private static final Int2ObjectOpenHashMap<CompletableFuture<OrganBlueprint>> BLUEPRINTS = new Int2ObjectOpenHashMap<>();
    private static boolean placing;

    // 役割の写し(司令塔の上限から毎回読むので覚えておく)
    private static double sacHives = -1.0D;
    /** 根の割り当てを数え直して掛け直す(次の tick に)。 */
    private static boolean buffsDirty = true;

    /** 器官の手順がブロックを置き換えている間(芯の onRemove を数えない)。 */
    public static boolean placing() {
        return placing;
    }

    static void invalidateCaches() {
        sacHives = -1.0D;
        buffsDirty = true;
        LeyBuffs.reset();
    }

    static void forget(int id) {
        BLUEPRINTS.remove(id);
        OrganClearing.forget(id);
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        BLUEPRINTS.clear();
        OrganClearing.clearAll();
        LeyBuffs.forgetApplied();
        invalidateCaches();
    }

    /** 設計図(まだなら背景で作り始めて null)。 */
    @Nullable
    static OrganBlueprint blueprint(ForestOrgans.Organ o) {
        CompletableFuture<OrganBlueprint> f = BLUEPRINTS.get(o.id);
        if (f == null && o.fusion != null) {
            // 融合の器官(11-19): 保存した列から(世界を見て作った形なので作り直さない)
            f = CompletableFuture.completedFuture(OrganBlueprint.fromEntries(o.fusion, o.seed));
            BLUEPRINTS.put(o.id, f);
        }
        if (f == null) {
            f = CompletableFuture.supplyAsync(() -> OrganBlueprint.create(o.kind, o.small, o.variant, o.seed, o.rot), EXEC);
            BLUEPRINTS.put(o.id, f);
        }
        if (!f.isDone()) return null;
        try {
            return fit(o, f.join());
        } catch (RuntimeException e) {
            OvergrownVegetation.LOGGER.error("森の器官 #{} の設計図を作れなかった", o.id, e);
            BLUEPRINTS.remove(o.id);
            return null;
        }
    }

    /** すぐに設計図が要るとき(コマンド・テスト)。 */
    static OrganBlueprint blueprintNow(ForestOrgans.Organ o) {
        CompletableFuture<OrganBlueprint> f = BLUEPRINTS.computeIfAbsent(o.id,
                k -> CompletableFuture.completedFuture(o.fusion != null ? OrganBlueprint.fromEntries(o.fusion, o.seed)
                        : OrganBlueprint.create(o.kind, o.small, o.variant, o.seed, o.rot)));
        return fit(o, f.join());
    }

    /**
     * 保存された数を設計図に合わせる(11-18 見直し ②1)。形が変わると、保存した {@code placed}・{@code withered} が新しい設計図より長く、
     * 配列の外を読んでサーバーが落ちていた。役割の処理(OrganRoles)も設計図を読むので、設計図を渡す所で必ず
     * {@code 0 <= withered <= placed <= entries.length} にする。形の指紋が違えば(形を作り替えた更新)置き直す:
     * 生きている器官は最初から置き直し、枯れた器官は全部を枯らし直す(枯れの状態に替えるのは器官のブロックだけ)。
     * 古いブロックのうち新しい形に無い所はそのまま残る。
     */
    static OrganBlueprint fit(ForestOrgans.Organ o, OrganBlueprint bp) {
        int n = bp.entries.length;
        boolean reshaped = o.shapeHash != 0 ? o.shapeHash != bp.shapeHash : (o.total != 0 && o.total != n);
        int placed = o.placed, withered = o.withered;
        if (reshaped) {
            if (o.state == ForestOrgans.State.WITHERED) {
                placed = n;
            } else {
                placed = 0;
            }
            withered = 0;
        }
        placed = Math.max(0, Math.min(placed, n));
        withered = Math.max(0, Math.min(withered, placed));
        if (placed != o.placed || withered != o.withered || o.shapeHash != bp.shapeHash || o.total != n) {
            o.placed = placed;
            o.withered = withered;
            o.shapeHash = bp.shapeHash;
            o.total = n;
            o.refit = true;
        }
        return bp;
    }

    // ---------------------------------------------------------------- 役割の口(ほかの仕組みから読む)

    /**
     * 蓄えの嚢がハイヴノード何個分か(司令塔の上限に足す。ForestDirector#cap から)。巨大樹 1 本 = ハイヴノード 1 つなので、
     * ユーザーの「大きいものが巨大樹 3 本分、小さなものが巨大樹 0.2 本分」= 大 3・小 0.2【organs.json】。
     */
    public static double sacHiveEquivalent(ServerLevel level) {
        if (!DesignData.organs().enabled) return 0.0D;
        if (sacHives < 0.0D) {
            double s = 0.0D;
            for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
                if (o.kind == OrganKind.SAC && o.active()) s += DesignData.organs().sacHiveNodes(o.small);
            }
            sacHives = s;
        }
        return sacHives;
    }

    // ---------------------------------------------------------------- 毎 tick

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (!DesignData.organs().enabled) return;
        long t0 = System.nanoTime();
        try {
            tickInner(event);
        } finally {
            OrganMeter.record(System.nanoTime() - t0);
        }
    }

    private static void tickInner(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        ServerLevel level = server.overworld();
        ForestOrgans data = ForestOrgans.get(server);
        if (buffsDirty) {
            buffsDirty = false;
            LeyBuffs.changed(level); // 地脈を吸う根が増えた・枯れた → 植物側の Mob へ掛け直す
        }
        if (data.organs().isEmpty()) return;
        long now = PhaseData.clockNow(level);
        boolean roles = server.getTickCount() % 100 == 0;
        for (ForestOrgans.Organ o : data.organs()) {
            if (o.state == ForestOrgans.State.GROWING && o.growth(now) >= 1.0D) {
                o.state = ForestOrgans.State.ALIVE;
                o.roleAt = now;
                invalidateCaches();
                data.changed();
                com.yayuy.overgrownvegetation.director.ForestDirector.get(level).note(
                        String.format(java.util.Locale.ROOT, "器官 #%d %s が育ち切った", o.id, o.kind.label));
            }
            if (roles && o.active()) OrganRoles.tick(level, data, o, now);
            if (roles && o.state == ForestOrgans.State.WITHERED && o.regrown >= 0) {
                // 枯れた後、払った所に苗木を植え直す(11-19)
                OrganBlueprint bp = blueprint(o);
                if (bp != null && OrganClearing.regrow(level, o, bp, now)) data.changed();
            }
        }
        // 置く・枯らす(予算の中で。器官ごとに順に)
        if (!TickBudget.mayStart(PerfJob.ORGAN_BUILD)) return;
        long start = TickBudget.begin();
        int done = 0;
        try {
            int budget = DesignData.organs().blocksPerTick;
            for (ForestOrgans.Organ o : data.organs()) {
                if (budget <= 0) break;
                if (!OrganWorld.ready(level, o.x >> 4, o.z >> 4)) continue;
                int before = budget;
                long s0 = System.nanoTime();
                // CPU 時間・GC の合計は、大群の測り(HordeMeter)を回している間だけ取る(11-18 見直し ②13。前は器官ごとに毎 tick 取っていた)
                boolean detail = com.yayuy.overgrownvegetation.perf.HordeMeter.on;
                long c0 = detail ? OrganMeter.cpuNanos() : 0L, g0 = detail ? OrganMeter.gcMillis() : 0L;
                boolean wasResolved = o.resolved();
                int placedBefore = o.placed;
                budget = step(level, data, o, now, budget);
                long took = System.nanoTime() - s0;
                if (took > 5_000_000L) OrganMeter.slow(String.format(java.util.Locale.ROOT, "#%d %s%s %.1f ms(CPU %.1f・GC %d)置いた %d 芽 %s", o.id, o.kind.id,
                        o.small ? "(小)" : "", took / 1e6, detail ? (OrganMeter.cpuNanos() - c0) / 1e6 : Double.NaN, detail ? OrganMeter.gcMillis() - g0 : -1L,
                        o.placed - placedBefore, wasResolved ? "-" : "置いた"));
                done += before - budget;
                OrganMeter.blocks(before - budget);
            }
        } finally {
            TickBudget.end(PerfJob.ORGAN_BUILD, start, done);
            for (int i = 0; i < CANCELLED.size(); i++) data.remove(CANCELLED.getInt(i)); // 回している間は消せないので後で
            CANCELLED.clear();
        }
    }

    /** 構造物にかかるので建てない器官(11-19 融合 段 1)。払った値段を返す。 */
    private static final it.unimi.dsi.fastutil.ints.IntArrayList CANCELLED = new it.unimi.dsi.fastutil.ints.IntArrayList();

    static void cancel(ServerLevel level, ForestOrgans data, ForestOrgans.Organ o) {
        if (CANCELLED.contains(o.id)) return;
        CANCELLED.add(o.id);
        com.yayuy.overgrownvegetation.director.ForestDirector dir = com.yayuy.overgrownvegetation.director.ForestDirector.get(level);
        if (o.onPiece == null && o.takeover == null) dir.refund(level, OrganPlanner.price(level, o.small)); // 構造物に植えた器官(段 2c・2d)・乗っ取りの器官(段 3)は値段を払っていないので返さない
        dir.note(String.format(java.util.Locale.ROOT, "器官 #%d %s%s は構造物にかかるので建てない(値段を返した)", o.id, o.kind.label, o.small ? "(小)" : ""));
    }

    /** 器官 1 つを進める(置く・枯らす)。残りの予算を返す。 */
    static int step(ServerLevel level, ForestOrgans data, ForestOrgans.Organ o, long now, int budget) {
        if (!o.resolved()) {
            // 地面を引く所(±5)と芽(±1)のチャンクが全部読み込まれるまで待つ。読み込まれていない升に触るとその場で読み込んで止まる
            // (11-18 測り: 移動中に 1 回 20〜90 ms)
            for (int dx = -6; dx <= 6; dx += 12) {
                for (int dz = -6; dz <= 6; dz += 12) {
                    if (!OrganWorld.ready(level, (o.x + dx) >> 4, (o.z + dz) >> 4)) return budget;
                }
            }
            o.y = surface(level, o.x, o.z);
            data.changed();
            budget -= 16;
        }
        if (!o.fusionChecked) {
            // 構造物と森の融合(11-19 段 1): 構造物のそばの小さい版は融合の形に、構造物にかかる大きい版は建てない
            OrganFusion.Result fr = o.state == ForestOrgans.State.WITHERED ? OrganFusion.Result.NORMAL : OrganFusion.decide(level, o);
            if (fr == OrganFusion.Result.WAIT) return budget;
            o.fusionChecked = true;
            data.changed();
            if (fr == OrganFusion.Result.CANCEL) {
                cancel(level, data, o);
                return budget - 16;
            }
            if (fr == OrganFusion.Result.FUSED) {
                BLUEPRINTS.remove(o.id);
                o.budCleared = true; // 融合の器官は芽を置かない(芯は形の中にすぐ出る)
                o.total = 0;
                o.shapeHash = 0;
                com.yayuy.overgrownvegetation.director.ForestDirector.get(level).note(String.format(java.util.Locale.ROOT,
                        "器官 #%d %s(小)は構造物のそばなので融合の形で建てる", o.id, o.kind.label));
            } else if (o.state != ForestOrgans.State.WITHERED) {
                placeBud(level, o);
            }
            budget -= 16;
        }
        OrganBlueprint bp = blueprint(o);
        if (bp == null) return budget;
        if (o.refit) {
            o.refit = false;
            data.changed();
        }
        if (!o.cleared) {
            // 置き始める前に、建つ所の木を払う(11-19 ユーザー「んじゃ払うか」)
            budget = OrganClearing.step(level, o, bp, budget);
            if (!o.cleared) return budget;
            data.changed();
        }
        BlockPos anchor = o.anchor();
        int target = o.state == ForestOrgans.State.GROWING ? (int) Math.floor(o.growth(now) * o.total) : o.total;
        if (o.state == ForestOrgans.State.WITHERED) target = o.placed; // 枯れたら育たない
        boolean changed = false;
        placing = true;
        try {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            while (budget > 0 && o.placed < target) {
                long e = bp.entries[o.placed];
                p.set(anchor.getX() + OrganBlueprint.dx(e), anchor.getY() + OrganBlueprint.dy(e), anchor.getZ() + OrganBlueprint.dz(e));
                if (!OrganWorld.ready(level, p.getX() >> 4, p.getZ() >> 4)) break; // 読み込まれたら続きから
                place(level, o, p, e, false);
                if (OrganBlueprint.material(e) == OrganBlueprint.M_CORE && !o.budCleared) {
                    clearBud(level, o);
                }
                o.placed++;
                budget = TickBudget.spend(budget);
                changed = true;
            }
            if (o.state != ForestOrgans.State.WITHERED && o.placed >= o.total && !o.budCleared) clearBud(level, o);
            if (!o.tentacles && o.state != ForestOrgans.State.WITHERED && o.placed >= o.total * 0.7D) {
                spawnTentacles(level, o, bp);
                changed = true;
            }
            if (o.state == ForestOrgans.State.WITHERED) {
                if (!o.budCleared) clearBud(level, o);
                while (budget > 0 && o.withered < o.placed) {
                    long e = bp.entries[o.withered];
                    p.set(anchor.getX() + OrganBlueprint.dx(e), anchor.getY() + OrganBlueprint.dy(e), anchor.getZ() + OrganBlueprint.dz(e));
                    if (!OrganWorld.ready(level, p.getX() >> 4, p.getZ() >> 4)) break;
                    witherAt(level, p);
                    o.withered++;
                    budget = TickBudget.spend(budget);
                    changed = true;
                }
            }
            if (o.state == ForestOrgans.State.WITHERED && o.fusion != null && !o.fusionCleaned && o.withered >= o.placed
                    && now - o.witheredAt >= (long) (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.table().vineFadeSeconds * 20.0D)) {
                cleanFusion(level, o, bp);
                changed = true;
            }
        } finally {
            placing = false;
        }
        if (changed) data.changed();
        return budget;
    }

    /**
     * 枯れた融合の器官の蔦の触手と脈を消し、この器官が覆ったマスを剥がす(11-19 段 1「器官を潰すと触手と覆いは枯れて消える」)。
     * 器官の本体(柄・袋・芯)は今までの器官と同じく枯れた形が残る(空気のマスにしか無いので、構造物のブロックは元のまま)。
     * 読み込まれていない所が残れば、次の tick にまた。
     */
    static boolean cleanFusion(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp) {
        BlockPos anchor = o.anchor();
        boolean all = true;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (long e : bp.entries) {
            int m = OrganBlueprint.material(e);
            // 建物に重ねた器官(段 2d: 構造物に植えた器官・建物の上の大きい版)は、枯れたら本体も剥がす(建物を包んだまま残さない)
            boolean bigBody = (!o.small || o.onPiece != null || o.takeover != null) && m != OrganBlueprint.M_COVER && m != OrganBlueprint.M_CARVE; // 段 3: 乗っ取りの器官も伸びた物ごと剥がす
            if (m != OrganBlueprint.M_VINE && m != OrganBlueprint.M_VEIN && !bigBody) continue;
            p.set(anchor.getX() + OrganBlueprint.dx(e), anchor.getY() + OrganBlueprint.dy(e), anchor.getZ() + OrganBlueprint.dz(e));
            if (!ready(level, p.getX() >> 4, p.getZ() >> 4)) {
                all = false;
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.is(OrganBlocks.ORGAN_VINE.get()) || s.is(OrganBlocks.ORGAN_VEIN.get())
                    || bigBody && !s.isAir() && com.yayuy.overgrownvegetation.OvergrownVegetation.MODID.equals(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(s.getBlock()).getNamespace())) level.setBlock(p, Blocks.AIR.defaultBlockState(), FLAGS);
        }
        it.unimi.dsi.fastutil.longs.LongArrayList left = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (int i = 0; i < o.covered.size(); i++) {
            long k = o.covered.getLong(i);
            if (!ready(level, BlockPos.getX(k) >> 4, BlockPos.getZ(k) >> 4)) {
                left.add(k);
                continue;
            }
            com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.uncover(level, BlockPos.of(k));
        }
        o.covered.clear();
        o.covered.addAll(left);
        if (all && left.isEmpty()) o.fusionCleaned = true;
        return o.fusionCleaned;
    }

    private static final int FLAGS = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;

    /** 設計図の 1 つを置く。 */
    static void place(ServerLevel level, BlockPos p, long e, boolean force) {
        place(level, null, p, e, force);
    }

    /**
     * 設計図の 1 つを置く。構造物の部品の箱(地表に出ている物。まだ分からない所も)では、空気と置き換えられる草花のマスにしか置かず、
     * 掘らない(2026-11-19 構造物と森の融合 段 1「壊さずに取り込む」)。覆いの印({@link OrganBlueprint#M_COVER})は、新しく覆えたマスを器官が覚える。
     */
    static void place(ServerLevel level, @Nullable ForestOrgans.Organ o, BlockPos p, long e, boolean force) {
        if (p.getY() <= level.getMinBuildHeight() || p.getY() >= level.getMaxBuildHeight() - 1) return;
        BlockState cur = level.getBlockState(p);
        int mat = OrganBlueprint.material(e);
        if (mat == OrganBlueprint.M_COVER) {
            if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.canCover(cur)
                    && com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.cover(level, p) && o != null) o.covered.add(p.asLong());
            return;
        }
        if (cur.hasBlockEntity() || cur.getDestroySpeed(level, p) < 0.0F) return; // 箱・機械・岩盤は残す
        if (o != null && o.takeover != null) {
            // 乗っ取りの器官(段 3): 突き破る穴は人工のブロックだけ、ほかは空気と草花のマスだけ(部屋の中・入口も塞いでよい)。押し返す道具の効く所には置かない
            if (com.yayuy.overgrownvegetation.corruption.pushback.RingWards.guarded(level, p)) return;
            if (mat == OrganBlueprint.M_CARVE) {
                if (!Takeover.isArtificial(level, p, cur)) return;
            } else if (!(cur.isAir() || cur.canBeReplaced()) || !cur.getFluidState().isEmpty()) {
                return;
            }
            BlockState ts = stateOf(mat, OrganBlueprint.faces(e));
            if (ts != null) level.setBlock(p, ts, FLAGS);
            return;
        }
        if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)
                && (mat == OrganBlueprint.M_CARVE || !(cur.isAir() || cur.canBeReplaced()) || !cur.getFluidState().isEmpty())) return;
        BlockState s = stateOf(mat, OrganBlueprint.faces(e));
        if (s == null) return;
        if (mat == OrganBlueprint.M_CARVE) {
            if (cur.isAir()) return;
            if (!cur.getFluidState().isEmpty() && cur.getFluidState().isSource()) return; // 水の中は掘らない
        }
        level.setBlock(p, s, FLAGS);
    }

    @Nullable
    static BlockState stateOf(int mat, int faces) {
        return switch (mat) {
            case OrganBlueprint.M_CARVE -> Blocks.AIR.defaultBlockState();
            case OrganBlueprint.M_MOSS -> ModBlocks.OVERGROWN_BLOCK.get().defaultBlockState();
            case OrganBlueprint.M_LOG -> ModBlocks.OVERGROWN_LOG.get().defaultBlockState();
            case OrganBlueprint.M_GIANT_LOG -> ModBlocks.GIANT_OVERGROWN_LOG.get().defaultBlockState();
            case OrganBlueprint.M_SAC -> OrganBlocks.ORGAN_SAC.get().defaultBlockState();
            case OrganBlueprint.M_SAC_WITHERED -> OrganBlocks.ORGAN_SAC.get().defaultBlockState().setValue(OrganTissueBlock.WITHERED, true);
            case OrganBlueprint.M_VEIN -> vein(faces);
            case OrganBlueprint.M_CORE -> OrganBlocks.ORGAN_CORE.get().defaultBlockState();
            case OrganBlueprint.M_CRYSTAL -> OrganBlocks.ROOT_CRYSTAL_BLOCK.get().defaultBlockState();
            case OrganBlueprint.M_PITH -> ModBlocks.GIANT_OVERGROWN_CAMBIUM.get().defaultBlockState();
            case OrganBlueprint.M_CAP -> ModBlocks.PROTOTAXITES_CAP.get().defaultBlockState();
            case OrganBlueprint.M_ROOT -> ModBlocks.STIGMARIA.get().defaultBlockState();
            case OrganBlueprint.M_EGG -> OrganBlocks.ORGAN_EGG_MASS.get().defaultBlockState();
            case OrganBlueprint.M_STONE -> ModBlocks.OVERGROWN_STONE.get().defaultBlockState();
            case OrganBlueprint.M_VINE -> OrganBlocks.ORGAN_VINE.get().defaultBlockState();
            default -> null;
        };
    }

    private static BlockState vein(int faces) {
        BlockState s = OrganBlocks.ORGAN_VEIN.get().defaultBlockState();
        for (Direction d : Direction.values()) {
            s = s.setValue(MultifaceBlock.getFaceProperty(d), (faces & (1 << d.get3DDataValue())) != 0);
        }
        return s;
    }

    /** 枯れの状態へ(器官のブロックだけ)。 */
    static void witherAt(ServerLevel level, BlockPos p) {
        BlockState cur = level.getBlockState(p);
        if (cur.hasProperty(OrganTissueBlock.WITHERED) && !cur.getValue(OrganTissueBlock.WITHERED)
                && (cur.is(OrganBlocks.ORGAN_SAC.get()) || cur.is(OrganBlocks.ORGAN_EGG_MASS.get()) || cur.is(OrganBlocks.ORGAN_CORE.get())
                || cur.is(OrganBlocks.ORGAN_VEIN.get()) || cur.is(OrganBlocks.ORGAN_VINE.get()))) {
            level.setBlock(p, cur.setValue(OrganTissueBlock.WITHERED, true), FLAGS);
        }
    }

    /** 器官の真ん中の地面のいちばん上(木を除いた地面。まわり 9 本の低い方から 3 番目)。 */
    /**
     * そのチャンクが読み込み済みで生成し終わっているか。{@code level.hasChunk} は「読み込む札が付いている」だけで、生成中でも true を返し、
     * そこへ setBlock・getHeight すると生成し終わるまで主スレッドが待つ(11-18 測り: 移動中に芽を置く 1 回が 10〜165 ms)。
     * getChunkNow は生成し終わった LevelChunk だけを返す(主スレッドから呼ぶ)。
     */
    static boolean ready(ServerLevel level, int cx, int cz) {
        if (level.getChunkSource().getChunkNow(cx, cz) != null) return true;
        if (level.hasChunk(cx, cz)) OrganMeter.notReady();
        return false;
    }

    static int surface(ServerLevel level, int x, int z) {
        int[] hs = new int[9];
        int n = 0;
        for (int i = -1; i <= 1; i++) {
            for (int j = -1; j <= 1; j++) {
                int sx = x + i * 5, sz = z + j * 5;
                if (!OrganWorld.ready(level, sx >> 4, sz >> 4)) continue; // 読み込まれていない升を引くとその場で読み込んで止まる(11-18 測り: 最大 71 ms)
                // 木(幹・葉)・下草を除いた地面(11-19: 前は幹を地面と数え、森では樹冠の上 y=236・262 に建っていた)
                int gy = OrganClearing.groundY(level, sx, sz);
                if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, new BlockPos(sx, gy, sz))) continue; // 構造物の屋根を地面と数えない(11-19 融合)
                hs[n++] = gy;
            }
        }
        if (n == 0) return 64;
        // 低い方から 3 番目(9 本のとき)。いちばん低い 1 本だと、谷・地割れに 1 本落ちただけで器官が沈む(11-19 の撮影で y=25 が 2 つ)
        java.util.Arrays.sort(hs, 0, n);
        return hs[Math.min(n - 1, n / 4)];
    }

    /** 芽: 地面に仮の芯 1 つと、まわりの嚢。育つ前に見つけて潰せる。 */
    static void placeBud(ServerLevel level, ForestOrgans.Organ o) {
        BlockPos c = o.anchor().above();
        placing = true;
        try {
            budSet(level, c, OrganBlocks.ORGAN_CORE.get().defaultBlockState());
            for (Direction d : Direction.Plane.HORIZONTAL) {
                budSet(level, c.relative(d), OrganBlocks.ORGAN_SAC.get().defaultBlockState());
            }
            budSet(level, c.above(), OrganBlocks.ORGAN_SAC.get().defaultBlockState());
        } finally {
            placing = false;
        }
    }

    /** 芽の 1 つ。構造物の部品の箱では空気・草花のマスだけ(11-19 融合 段 1)。 */
    private static void budSet(ServerLevel level, BlockPos p, BlockState s) {
        if (com.yayuy.overgrownvegetation.worldgen.fusion.StructureFusion.blocks(level, p)) {
            BlockState cur = level.getBlockState(p);
            if (!(cur.isAir() || cur.canBeReplaced()) || !cur.getFluidState().isEmpty()) return;
        }
        level.setBlock(p, s, FLAGS);
    }

    /** 芽を片付ける(本物の芯が出たら。枯れたら芽の芯も枯れの状態へ)。 */
    static void clearBud(ServerLevel level, ForestOrgans.Organ o) {
        o.budCleared = true;
        if (!o.resolved()) return;
        BlockPos c = o.anchor().above();
        if (!OrganWorld.ready(level, c.getX() >> 4, c.getZ() >> 4)) return;
        boolean prev = placing;
        placing = true;
        try {
            BlockState s = level.getBlockState(c);
            if (s.is(OrganBlocks.ORGAN_CORE.get())) {
                if (o.state == ForestOrgans.State.WITHERED) {
                    witherAt(level, c);
                } else {
                    level.setBlock(c, OrganBlocks.ORGAN_SAC.get().defaultBlockState(), FLAGS);
                }
            }
        } finally {
            placing = prev;
        }
    }

    // ---------------------------------------------------------------- コマンド・テスト向け

    /** 育ち具合を無視して、設計図を全部その場で置く(読み込まれている所だけ)。 */
    static int buildNow(ServerLevel level, ForestOrgans data, ForestOrgans.Organ o) {
        if (!o.resolved()) {
            o.y = surface(level, o.x, o.z);
        }
        if (!o.fusionChecked) {
            // 構造物と森の融合(11-19): コマンド・テストでも同じ判定(読み込み待ちはふつうの器官として。置く所で守る)
            OrganFusion.Result fr = OrganFusion.decide(level, o);
            o.fusionChecked = true;
            if (fr == OrganFusion.Result.CANCEL) {
                data.remove(o.id);
                return -1;
            }
            if (fr == OrganFusion.Result.FUSED) {
                BLUEPRINTS.remove(o.id);
                o.total = 0;
                o.shapeHash = 0;
            }
        }
        OrganBlueprint bp = blueprintNow(o);
        OrganClearing.clearNow(level, o, bp);
        o.total = bp.entries.length;
        BlockPos anchor = o.anchor();
        int n = 0;
        placing = true;
        try {
            BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
            for (; o.placed < o.total; o.placed++) {
                long e = bp.entries[o.placed];
                p.set(anchor.getX() + OrganBlueprint.dx(e), anchor.getY() + OrganBlueprint.dy(e), anchor.getZ() + OrganBlueprint.dz(e));
                place(level, o, p, e, true);
                n++;
            }
            o.budCleared = true;
        } finally {
            placing = false;
        }
        if (!o.tentacles && o.state != ForestOrgans.State.WITHERED) spawnTentacles(level, o, bp);
        data.changed();
        return n;
    }

    /** 動く蔦の触手を出す(育ちが 7 割を超えたら 1 回。大きい版は min〜max 本・小さい版は smallCount 本)。 */
    static void spawnTentacles(ServerLevel level, ForestOrgans.Organ o, OrganBlueprint bp) {
        o.tentacles = true;
        OrganTable t = DesignData.organs();
        java.util.Random r = new java.util.Random(o.seed * 31L + 7L);
        int n = o.small ? t.tentacleSmall : t.tentacleMin + r.nextInt(t.tentacleMax - t.tentacleMin + 1);
        BlockPos anchor = o.anchor();
        for (int i = 0; i < Math.min(n, bp.tentacleBases.size()); i++) {
            BlockPos b = bp.tentacleBases.get(i);
            OrganTentacle e = OrganEntities.TENTACLE.get().create(level);
            if (e == null) return;
            double len = Math.sqrt((double) b.getX() * b.getX() + (double) b.getZ() * b.getZ());
            double ox = len < 1.0E-3D ? 1.0D : b.getX() / len, oz = len < 1.0E-3D ? 0.0D : b.getZ() / len;
            e.setup(o.id, o.small ? 4 + r.nextInt(3) : 8 + r.nextInt(7), OrganTentacle.headingFrom(ox, oz));
            e.moveTo(anchor.getX() + b.getX() + 0.5D + ox, anchor.getY() + b.getY() + 0.5D, anchor.getZ() + b.getZ() + 0.5D + oz, 0.0F, 0.0F);
            level.addFreshEntity(e);
        }
    }

    /** 守りの Mob・苗床の手先を湧かせる(入口か芯の近く)。 */
    @Nullable
    static Mob spawnNear(ServerLevel level, BlockPos at, int group) {
        EntityType<?> type = com.yayuy.overgrownvegetation.spawn.DirectorHooks.pickPlant(level, level.getRandom(), group);
        if (type == null) return null;
        BlockPos feet = findFeet(level, at);
        if (feet == null) return null;
        Mob m = com.yayuy.overgrownvegetation.director.RaidSpawns.spawnPlantAttacker(level, feet, type, level.getRandom());
        if (m != null) {
            m.addTag(GUARD_TAG);
            m.setPersistenceRequired();
        }
        return m;
    }

    public static final String GUARD_TAG = "ov_organ_guard";

    /** 守りが死んだ・消えたら器官の数えから外す(11-18 見直し ②4)。チャンクと一緒に外れただけなら数えたまま。 */
    @SubscribeEvent
    public static void onGuardLeave(net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        net.minecraft.world.entity.Entity e = event.getEntity();
        if (!e.getTags().contains(GUARD_TAG)) return;
        net.minecraft.world.entity.Entity.RemovalReason why = e.getRemovalReason();
        if (why == null || !why.shouldDestroy()) return;
        ForestOrgans data = ForestOrgans.get(level);
        boolean any = false;
        for (ForestOrgans.Organ o : data.organs()) any |= o.guards.remove(e.getUUID());
        if (any) data.changed();
    }

    @Nullable
    private static BlockPos findFeet(ServerLevel level, BlockPos at) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 12; i++) {
            int dx = level.getRandom().nextInt(7) - 3, dz = level.getRandom().nextInt(7) - 3;
            if (!OrganWorld.ready(level, (at.getX() + dx) >> 4, (at.getZ() + dz) >> 4)) continue; // 読み込まれていない升に触らない
            for (int dy = 3; dy >= -4; dy--) {
                p.set(at.getX() + dx, at.getY() + dy, at.getZ() + dz);
                if (level.getBlockState(p).isAir() && level.getBlockState(p.above()).isAir()
                        && level.getBlockState(p.below()).isFaceSturdy(level, p.below(), Direction.UP)) {
                    return p.immutable();
                }
            }
        }
        return null;
    }

    /** 近くにサバイバルのプレイヤーがいるか。 */
    static boolean playerNear(ServerLevel level, BlockPos at, double blocks) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || p.isCreative()) continue;
            if (p.distanceToSqr(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D) <= blocks * blocks) return true;
        }
        return false;
    }

    /** 根晶を 1 つ、採られた所に生え直す(地脈を吸う根)。 */
    static boolean regrowCrystal(ServerLevel level, ForestOrgans.Organ o) {
        OrganBlueprint bp = blueprint(o);
        if (bp == null || bp.crystals.isEmpty() || !o.resolved()) return false;
        BlockPos anchor = o.anchor();
        int start = level.getRandom().nextInt(bp.crystals.size());
        for (int i = 0; i < bp.crystals.size(); i++) {
            BlockPos p = anchor.offset(bp.crystals.get((start + i) % bp.crystals.size()));
            if (!OrganWorld.ready(level, p.getX() >> 4, p.getZ() >> 4)) continue;
            if (!level.getBlockState(p).isAir()) continue;
            // 塊が採られた所: 隣の壁から芽を伸ばす。壁が無ければ塊で埋める
            for (Direction d : Direction.values()) {
                BlockPos wall = p.relative(d.getOpposite());
                if (level.getBlockState(wall).isFaceSturdy(level, wall, d)) {
                    level.setBlock(p, OrganBlocks.ROOT_CRYSTAL_CLUSTER.get().defaultBlockState().setValue(AmethystClusterBlock.FACING, d), Block.UPDATE_ALL);
                    return true;
                }
            }
            level.setBlock(p, OrganBlocks.ROOT_CRYSTAL_BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            return true;
        }
        return false;
    }
}

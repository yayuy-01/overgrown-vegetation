package com.yayuy.overgrownvegetation.director;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.HedgeTable;
import com.yayuy.overgrownvegetation.organ.ForestOrgans;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 棘の生垣を置く・置き直す・伸ばす(2026-11-20 ユーザー決定。森の防衛設備はいったんこれだけ)。共有の ModBlocks を太らせないよう、
 * 登録の口もここに持つ({@code OvergrownVegetation} から {@link #register} を 1 行呼ぶ。器官のブロックと同じ形)。
 * <ul>
 *   <li><b>置く・置き直す</b>({@link #tend}): 育ち切った器官ごとに、プレイヤーが {@code playerBlocks} 以内にいる間、{@code intervalSeconds} ごとに見直す。
 *   器官の真ん中から半径 {@code radius} の輪に、切れ目のある短い列({@code segments} 本 × {@code segmentLength})を決まった場所に置く
 *   (入口の向きは空ける)。欠けている所は 1 マス {@code cost} を払って置き直す(1 回に {@code placePerCheck} まで)。払えなければ置かない。</li>
 *   <li><b>伸びる</b>({@link #grow}): 予算なしで、ランダム tick で隣へ少しずつ。まわりに多ければ伸びない(密集させない)・器官の輪の帯の外へは伸びない。</li>
 *   <li>置く所・伸びる所は、空いていて足元が固く、押し返す道具(輪の守り・杭・焼けた荒地)の効かない所だけ。読み込まれていないチャンクには触らない。</li>
 * </ul>
 */
public final class ThornHedges {

    private ThornHedges() {
    }

    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(OvergrownVegetation.MODID);

    /** 棘の生垣。絵は仮(甘い実の茂みの若い姿)→ Fable が描き替える。 */
    public static final DeferredBlock<Block> THORN_HEDGE = BLOCKS.registerBlock("thorn_hedge", ThornHedgeBlock::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_BROWN).noCollission().noOcclusion().strength(0.8F)
                    .sound(SoundType.SWEET_BERRY_BUSH).pushReaction(PushReaction.DESTROY).randomTicks());

    public static void register(IEventBus modBus) {
        BLOCKS.register(modBus);
    }

    public static HedgeTable table() {
        return DesignData.director().hedge;
    }

    /** 器官ごとの次に見直す時刻(保存しない)。 */
    private static final Int2LongOpenHashMap NEXT = new Int2LongOpenHashMap();
    /** これまでに払って置いた数(保存しない。/ov director で見る)。 */
    static long placedPaid;

    // ---------------------------------------------------------------- 置く所

    /** 器官の輪の半径。 */
    public static double radius(ForestOrgans.Organ o) {
        return o.small ? table().radiusSmall : table().radiusBig;
    }

    /**
     * 輪に置く柱(x, z)の並び(純粋な計算)。列 i の向き = 2π(i + 揺らぎ) / 列の数(揺らぎは種から)、入口の向き ± entranceGapDeg の列は置かない。
     * 列は輪の接線の向きに {@code length} マス。
     */
    public static List<int[]> plan(double cx, double cz, double radius, int segments, int length, long seed, double entranceAngle, double gapDeg) {
        List<int[]> out = new ArrayList<>();
        RandomSource r = RandomSource.create(seed);
        double gap = Math.toRadians(gapDeg);
        for (int i = 0; i < segments; i++) {
            double a = (i + 0.3D + 0.4D * r.nextDouble()) * Math.PI * 2.0D / segments;
            if (!Double.isNaN(entranceAngle) && Math.abs(Mth.wrapDegrees(Math.toDegrees(a - entranceAngle))) < Math.toDegrees(gap)) continue;
            double ox = cx + Math.cos(a) * radius, oz = cz + Math.sin(a) * radius;
            double tx = -Math.sin(a), tz = Math.cos(a);
            for (int k = 0; k < length; k++) {
                double off = k - (length - 1) / 2.0D;
                out.add(new int[]{Mth.floor(ox + tx * off), Mth.floor(oz + tz * off)});
            }
        }
        return out;
    }

    /** 生垣を置いてよい所か(空いている・水が無い・足元が固い・押し返す道具が効いていない)。 */
    public static boolean canPlace(ServerLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (!s.isAir() && !(s.canBeReplaced() && !(s.getBlock() instanceof ThornHedgeBlock))) return false;
        if (!level.getFluidState(pos).isEmpty()) return false;
        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP)) return false;
        return !pushedBack(level, pos);
    }

    /** 押し返す道具が効いている所か(輪の守りの帯・杭の範囲・焼けた荒地)。 */
    public static boolean pushedBack(ServerLevel level, BlockPos pos) {
        return com.yayuy.overgrownvegetation.corruption.pushback.RingWards.guarded(level, pos)
                || com.yayuy.overgrownvegetation.corruption.pushback.BurntLand.isBurnt(level, pos);
    }

    public static boolean isHedge(BlockState s) {
        return s.is(THORN_HEDGE.get());
    }

    // ---------------------------------------------------------------- 司令塔が置く

    /** 器官の見張り(OrganRoles#tick、100 tick ごと)から。 */
    public static void tend(ServerLevel level, ForestOrgans.Organ o, long now) {
        HedgeTable t = table();
        if (!t.enabled || !o.active() || DirectorGates.peaceful(level)) return;
        long next = NEXT.getOrDefault(o.id, Long.MIN_VALUE);
        if (now < next && next - now <= t.intervalSeconds * 20L) return; // 間より先の記録(別のワールド・時計の巻き戻り)は捨てる
        NEXT.put(o.id, now + t.intervalSeconds * 20L);
        BlockPos at = o.anchor();
        if (!playerNear(level, at, t.playerBlocks)) return;
        double entrance = com.yayuy.overgrownvegetation.organ.OrganRoles.entranceAngle(o);
        int placed = placeRing(level, at.getX() + 0.5D, at.getZ() + 0.5D, radius(o), o.small ? t.segmentsSmall : t.segmentsBig,
                o.seed * 31L + o.id, entrance, t.placePerCheck);
        if (placed > 0) ForestDirector.get(level).note(String.format(Locale.ROOT, "器官 #%d のまわりに棘の生垣を %d マス置いた(予算 %.0f)", o.id, placed, placed * t.cost));
    }

    /**
     * 輪の欠けている所を置く(置き直す)。1 マスずつ値段を払い、払えなくなったら止める。置いた数を返す。
     * 柱の地面は高さ地図(葉を除く)で見る。読み込まれていないチャンクは飛ばす。
     */
    public static int placeRing(ServerLevel level, double cx, double cz, double radius, int segments, long seed, double entrance, int max) {
        LAST_RING.clear();
        HedgeTable t = table();
        int placed = 0;
        ForestDirector dir = ForestDirector.get(level);
        for (int[] c : plan(cx, cz, radius, segments, t.segmentLength, seed, entrance, t.entranceGapDeg)) {
            if (placed >= max) break;
            var chunk = level.getChunkSource().getChunkNow(c[0] >> 4, c[1] >> 4);
            if (chunk == null) continue;
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c[0] & 15, c[1] & 15) + 1;
            BlockPos pos = new BlockPos(c[0], y, c[1]);
            if (isHedge(level.getBlockState(pos))) continue;
            if (!canPlace(level, pos)) continue;
            if (!dir.tryPay(level, t.cost)) break;
            level.setBlock(pos, THORN_HEDGE.get().defaultBlockState(), Block.UPDATE_ALL);
            placed++;
            LAST_RING.add(pos);
            placedPaid++;
        }
        return placed;
    }

    private static boolean playerNear(ServerLevel level, BlockPos at, double r) {
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && p.distanceToSqr(at.getX() + 0.5D, p.getY(), at.getZ() + 0.5D) <= r * r) return true;
        }
        return false;
    }

    // ---------------------------------------------------------------- 自分で伸びる

    /** 隣へ 1 マス伸びる(予算なし)。伸びたら true。 */
    public static boolean grow(ServerLevel level, BlockPos from, RandomSource random) {
        HedgeTable t = table();
        Direction d = Direction.Plane.HORIZONTAL.getRandomDirection(random);
        BlockPos side = from.relative(d);
        BlockPos to = null;
        for (int dy : new int[]{0, 1, -1}) {
            BlockPos p = side.above(dy);
            if (!level.isLoaded(p)) return false;
            if (canPlace(level, p)) {
                to = p;
                break;
            }
        }
        if (to == null || crowded(level, to, t) || !nearOrganRing(level, to, t)) return false;
        level.setBlock(to, THORN_HEDGE.get().defaultBlockState(), Block.UPDATE_ALL);
        return true;
    }

    /** まわり growNearRadius(上下 1)に生垣が growMaxNear 本以上あるか(密集させない)。 */
    public static boolean crowded(ServerLevel level, BlockPos at, HedgeTable t) {
        int n = 0, r = t.growNearRadius;
        for (BlockPos p : BlockPos.betweenClosed(at.offset(-r, -1, -r), at.offset(r, 1, r))) {
            if (isHedge(level.getBlockState(p)) && ++n >= t.growMaxNear) return true;
        }
        return false;
    }

    /** 育ち切った器官の輪の帯(半径 ± growOrganPad)の中か。器官の無い所へは伸びない。 */
    public static boolean nearOrganRing(ServerLevel level, BlockPos at, HedgeTable t) {
        if (!DesignData.organs().enabled) return false;
        for (ForestOrgans.Organ o : ForestOrgans.get(level).organs()) {
            if (!o.active()) continue;
            double d = Math.hypot(o.x + 0.5D - (at.getX() + 0.5D), o.z + 0.5D - (at.getZ() + 0.5D));
            if (Math.abs(d - radius(o)) <= t.growOrganPad) return true;
        }
        return false;
    }

    public static String describe() {
        return String.format(Locale.ROOT, "棘の生垣: これまで払って置いた %d マス", placedPaid);
    }

    /** 直前の placeRing で置いた所(テスト・確かめ用)。 */
    public static final List<BlockPos> LAST_RING = new ArrayList<>();

    /** テスト用: 見直しの間を忘れる。 */
    public static void resetForTests() {
        NEXT.clear();
    }
}

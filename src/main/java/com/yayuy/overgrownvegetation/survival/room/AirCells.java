package com.yayuy.overgrownvegetation.survival.room;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import it.unimi.dsi.fastutil.objects.Reference2ByteOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod.EventBusSubscriber;
import net.minecraftforge.event.TagsUpdatedEvent;

/**
 * 1 マスが空気を通すか(2026-11-13 段 2a)。ブロックの状態ごとに 1 バイトに縮めて覚える。
 *
 * <p>考え方: 当たり判定の形を、流れる向き(東西・上下・南北)に沿って面へ<b>投影</b>し、面が全部ふさがればその向きには通さない。
 * あるマスから隣へ空気が流れるのは、自分も隣もその向きをふさいでいないとき。
 * <ul>
 *     <li>空気・当たり判定の無いブロック(草・松明・看板・感圧板・水など)は通す。完全なブロック(ガラスも)はどの向きも通さない。</li>
 *     <li>板ガラス・閉じたドア・閉じたトラップドア・閉じたフェンスゲート: 板を横切る向きを通さない(ドアの決定 17。開けば板の向きが変わって通す)。</li>
 *     <li>【仮】ハーフ: 上下は通さない(屋根・床になる)・横はすき間(半分あいている)。階段: 段の向きに合わせて同じ投影で決まる(屋根は通さない)。</li>
 *     <li>【仮】葉・フェンス・鉄格子・鎖は通す(すき間だらけ)。塀(石の塀)は形どおり横切る向きを通さない。</li>
 *     <li>【仮】バリアは「外」(テスト場・地図の外枠の向こう)。</li>
 * </ul>
 * 自然の壁: タグ {@code overgrown_vegetation:room_natural_wall}(石・深層岩・鉱石・砂利・ネザーラックなど)に入るブロック。
 * 境界がこれだけの空間は部屋にしない(自然の洞窟の袋。{@link com.yayuy.overgrownvegetation.data.RoomSpec#requireBuiltWall})。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.FORGE)
public final class AirCells {

    /** 東西(X)に沿った流れをふさぐ。 */
    public static final byte BLOCK_X = 1;
    /** 上下(Y)に沿った流れをふさぐ。 */
    public static final byte BLOCK_Y = 2;
    /** 南北(Z)に沿った流れをふさぐ。 */
    public static final byte BLOCK_Z = 4;
    /** どの向きもふさぐ(入れないマス)。 */
    public static final byte SOLID = BLOCK_X | BLOCK_Y | BLOCK_Z;
    /** 外(バリア)。 */
    public static final byte OUTSIDE = 8;
    /** 自然の壁(タグ)。 */
    public static final byte NATURAL = 16;
    /** 当たり判定がある(境界として数える)。 */
    public static final byte COLLIDES = 32;
    /** 読めない(チャンクが読み込まれていない)。 */
    public static final byte UNLOADED = -1;

    public static final TagKey<Block> NATURAL_WALL =
            TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath(OvergrownVegetation.MODID, "room_natural_wall"));

    private static final Reference2ByteOpenHashMap<BlockState> MEMO = new Reference2ByteOpenHashMap<>();

    static {
        MEMO.defaultReturnValue((byte) -128);
    }

    private AirCells() {
    }

    /** タグが読み直されたら覚えを捨てる(自然の壁の印が変わる)。 */
    @SubscribeEvent
    public static void onTags(TagsUpdatedEvent event) {
        synchronized (MEMO) {
            MEMO.clear();
        }
    }

    /** {@code state} の 1 バイトの印(上の定数の組み合わせ)。 */
    public static byte of(BlockState state) {
        synchronized (MEMO) {
            byte b = MEMO.getByte(state);
            if (b != (byte) -128) return b;
            b = classify(state);
            MEMO.put(state, b);
            return b;
        }
    }

    /** 軸 0 = X・1 = Y・2 = Z の流れをふさぐか。 */
    public static boolean blocks(byte cls, int axis) {
        return (cls & (1 << axis)) != 0;
    }

    private static byte classify(BlockState state) {
        if (state.is(Blocks.BARRIER)) return OUTSIDE;
        if (state.isAir()) return 0;
        if (state.is(BlockTags.LEAVES)) return 0; // 空気と同じ(繁った洞窟のツツジの葉を「人の手」に数えない)
        if (state.is(BlockTags.FENCES)|| state.is(Blocks.IRON_BARS) || state.is(Blocks.CHAIN)) return COLLIDES;
        byte natural = state.is(NATURAL_WALL) ? NATURAL : 0;
        VoxelShape shape;
        try {
            shape = state.getCollisionShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO);
        } catch (RuntimeException e) {
            // 世界を見ないと形が決まらないブロック: 光をさえぎる物は壁、それ以外は通す
            return (byte) (state.canOcclude() ? SOLID | COLLIDES | natural : COLLIDES | natural);
        }
        if (shape.isEmpty()) return 0;
        if (shape == Shapes.block()) return (byte) (SOLID | COLLIDES | natural);
        // 見た目は満ちたブロック(ソウルサンド・泥: 当たり判定は 14/16)は壁【仮】
        try {
            if (Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO))) return (byte) (SOLID | COLLIDES | natural);
        } catch (RuntimeException ignored) {
            // 形が世界に依るブロックは当たり判定の投影だけで見る
        }
        byte out = (byte) (COLLIDES | natural);
        for (int axis = 0; axis < 3; axis++) {
            if (projectionFull(shape, axis)) out |= (byte) (1 << axis);
        }
        return out;
    }

    /** 形を軸 {@code axis} に沿って 1×1 の面へ投影し、面がすき間なく埋まるか(1/16 の格子で見る)。 */
    static boolean projectionFull(VoxelShape shape, int axis) {
        boolean[] cover = new boolean[256];
        for (AABB b : shape.toAabbs()) {
            double a0 = axis == 0 ? b.minX : axis == 1 ? b.minY : b.minZ;
            double a1 = axis == 0 ? b.maxX : axis == 1 ? b.maxY : b.maxZ;
            if (a1 <= 1.0E-6 || a0 >= 1.0D - 1.0E-6) continue; // マスの外にはみ出した部分(フェンスの上の 0.5 など)
            double u0 = axis == 0 ? b.minY : b.minX, u1 = axis == 0 ? b.maxY : b.maxX;
            double v0 = axis == 2 ? b.minY : b.minZ, v1 = axis == 2 ? b.maxY : b.maxZ;
            int iu0 = cell(u0, false), iu1 = cell(u1, true), iv0 = cell(v0, false), iv1 = cell(v1, true);
            for (int u = iu0; u < iu1; u++) for (int v = iv0; v < iv1; v++) cover[u * 16 + v] = true;
        }
        // 1/16 の 1 列ぶん(16 格)までの欠けは埋まっているとみなす【仮】(耕地・土の道の 15/16 の高さを壁に数える)
        int open = 0;
        for (boolean c : cover) if (!c) open++;
        return open <= 16;
    }

    private static int cell(double d, boolean upper) {
        double s = Math.max(0.0D, Math.min(1.0D, d)) * 16.0D;
        return Math.max(0, Math.min(16, upper ? (int) Math.ceil(s - 1.0E-4) : (int) Math.floor(s + 1.0E-4)));
    }
}

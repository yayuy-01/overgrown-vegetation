package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.FloatingLeafParticle;
import com.yayuy.overgrownvegetation.client.UnderwaterMoteParticle;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import com.yayuy.overgrownvegetation.registry.ModParticles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.Vec3;

/**
 * <b>水面に浮く落ち葉を出す係</b>(2026-11-13、密林の水辺 ②。ユーザー選択「水面に浮く落ち葉」)。粒は {@link FloatingLeafParticle}。
 * 水面の上 {@link #LEAF_ABOVE} ブロック以内に木の葉が張り出している列に多く、汚染の水({@link UnderwaterMoteParticle#overgrownWater})なら
 * どこにも少し。数値はすべて仮決め。
 * <p>
 * 水面を流れる流木も同じ 11-13 に入れたが、同じ日にユーザー「流木あんまりいらなかった」で消した(水底の倒木は別物で残っている)。
 */
public final class FloatingLeaves {

    /** 1 tick あたり試す回数(張り出した葉の下)と、どこでもの割合。 */
    static final float LEAF_TRIES = 0.35F;
    static final float LEAF_ANYWHERE = 0.04F;
    static final int LEAF_ABOVE = 10;

    private static final RandomSource RANDOM = RandomSource.create();
    private static int leaves;

    private FloatingLeaves() {
    }

    static String describe() {
        return String.format("浮く葉 %d", leaves);
    }

    static void tick(ClientLevel level) {
        if (!VisualQuality.waterLife()) {
            return;
        }
        Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        var biome = level.getBiome(BlockPos.containing(cam));
        if (!(biome.is(ModBiomes.OVERGROWN) || biome.is(ModBiomes.OVERGROWN_OCEAN) || biome.is(ModBiomes.OVERGROWN_RIVER)
                || UnderwaterMoteParticle.overgrownWater(biome))) {
            return;
        }
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        if (RANDOM.nextFloat() < LEAF_TRIES) {
            leaf(level, cam, pos, true);
        }
        if (RANDOM.nextFloat() < LEAF_ANYWHERE) {
            leaf(level, cam, pos, false);
        }
    }

    /** 水面の高さ(ワールドの y。水源のブロックの上面 = ブロック + 8/9)。(x, z) の列でカメラの上 16〜下 24 に水面が無ければ NaN。 */
    private static double surfaceAt(ClientLevel level, int x, int z, int fromY, BlockPos.MutableBlockPos pos) {
        for (int y = fromY + 16; y >= fromY - 24; y--) {
            pos.set(x, y, z);
            if (level.getFluidState(pos).is(FluidTags.WATER)) {
                if (!level.getFluidState(pos).isSource()) {
                    return Double.NaN;
                }
                pos.move(0, 1, 0);
                // 水面の膜の上にも出る(11-13 膜のかき分け)
                var above = level.getBlockState(pos);
                return above.isAir() || above.getBlock() instanceof com.yayuy.overgrownvegetation.block.FloatingFilmBlock ? y + 8.0 / 9.0 : Double.NaN;
            }
            var state = level.getBlockState(pos);
            if (!state.isAir() && !(state.getBlock() instanceof com.yayuy.overgrownvegetation.block.FloatingFilmBlock)) {
                return Double.NaN;
            }
        }
        return Double.NaN;
    }

    private static void leaf(ClientLevel level, Vec3 cam, BlockPos.MutableBlockPos pos, boolean underCanopy) {
        int x = Mth.floor(cam.x) + RANDOM.nextInt(41) - 20;
        int z = Mth.floor(cam.z) + RANDOM.nextInt(41) - 20;
        double surface = surfaceAt(level, x, z, Mth.floor(cam.y), pos);
        if (Double.isNaN(surface)) {
            return;
        }
        int sy = Mth.floor(surface);
        if (!UnderwaterMoteParticle.overgrownWater(level.getBiome(pos.set(x, sy, z)))) {
            return;
        }
        if (underCanopy) {
            boolean canopy = false;
            for (int i = 1; i <= LEAF_ABOVE && !canopy; i++) {
                var s = level.getBlockState(pos.set(x, sy + i, z));
                canopy = s.is(BlockTags.LEAVES) || s.is(ModBlocks.OVERGROWN_LEAVES.get());
            }
            if (!canopy) {
                return;
            }
        }
        double kind = RANDOM.nextFloat() < 0.45F ? 1.0 : 0.0;
        level.addParticle(ModParticles.FLOATING_LEAF.get(), x + RANDOM.nextDouble(), surface, z + RANDOM.nextDouble(), kind, surface, 0.0);
        leaves++;
    }
}

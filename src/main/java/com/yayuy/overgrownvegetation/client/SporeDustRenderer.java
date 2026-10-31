package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.joml.Matrix4f;

/**
 * <b>胞子の粉を地面に描く</b>(2026-10-31 合間の小物 3。元は parked の {@code ClientSporeDust} の描画。見えないと「どこをシャベルで払えるか」が分からなかった)。
 * 降り積もり %({@link ClientSporeDust})で、各列のいちばん上の面(地面・屋根・葉・水面)の上に半透明の点々の板を 1 枚重ねる。ブロックは書き換えない。
 *
 * <h2>半透明の層に戻した(2026-10-31 夜、ユーザー指定「前の方が見やすかった」)</h2>
 * 10-31 昼は並べ替えを減らすため cutout の層に疎な不透明の点を描いていたが、ユーザーが前(parked)の見た目を選んだので、描き方ごと前へ戻した:
 * {@link AddSectionGeometryEvent}(Sodium 0.8 も呼ぶ)で {@link RenderType#translucent()} へ、不透明度を 1/16 刻みで焼いた点々の板
 * {@code spore_dust_a01〜a16} を、頂点の色で白緑〜黄緑に付けて描く(Sodium は頂点の透明度を使わないので、濃さは絵の段で出す。10-24 の不具合)。
 * 半透明の並べ替えは粉のある区画の分だけ増える(見やすさを優先)。
 * <p>濃さ(0〜1)= {@code dust.maxAlpha} × (降り積もり % ÷ 100) の {@code dust.gamma} 乗 × 列ごとのむら、葉の上は {@code leafFactor}・水面は {@code waterFactor} 倍。
 * 色は降り積もり % で {@code thinColor} → {@code thickColor}。絵の向きはブロックの座標から 4 向き × 裏返し(縞に見せない)。
 * 載せるのは固い上面・葉・氷・水面だけ。払った升(境は雑音で 1 ブロックぼかす)・汚染済み・覆われた面・過繁茂のバイオームには載せない。
 * 粉の無い区画には追加の描画を登録しない(バニラの軽い組み立てのまま)。数値は {@code atmosphere.json} の節 {@code dust}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SporeDustRenderer {

    /** 不透明度を 1/16 ずつ焼いた粉の絵(spore_dust_a01〜a16。元の絵 spore_dust の透明度 × k/16。元の絵は parked の U9)。 */
    private static final int ALPHA_STEPS = 16;
    private static final net.minecraft.resources.ResourceLocation[] LEVEL_SPRITES = new net.minecraft.resources.ResourceLocation[ALPHA_STEPS + 1];

    static {
        for (int k = 1; k <= ALPHA_STEPS; k++) {
            LEVEL_SPRITES[k] = OvergrownVegetation.id(String.format("block/spore_dust_a%02d", k));
        }
    }

    private SporeDustRenderer() {
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        Level level = event.getLevel();
        if (!ClientSporeDust.current(level)) {
            return;
        }
        BlockPos origin = event.getSectionOrigin();
        int cx = SectionPos.blockToSectionCoord(origin.getX());
        int cz = SectionPos.blockToSectionCoord(origin.getZ());
        if (!ClientSporeDust.nearDust(cx, cz)) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return;
        }
        AtmosphereTable.DustParams p = DesignData.atmosphere().dust();
        boolean nether = level.dimension() == Level.NETHER;
        int ceiling = nether ? com.yayuy.overgrownvegetation.corruption.NetherSurface.topY(level) : Integer.MAX_VALUE;
        // 粉を載せる面(本スレッドで写す): (面のブロックの y << 8) | 列、載せる高さ、不透明度 0〜1、色。
        IntArrayList spots = new IntArrayList();
        FloatArrayList lift = new FloatArrayList();
        FloatArrayList alphas = new FloatArrayList();
        IntArrayList colors = new IntArrayList();
        float[] thin = p.thinColor();
        float[] thick = p.thickColor();
        int y0 = origin.getY();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos up = new BlockPos.MutableBlockPos();
        for (int i = 0; i < 256; i++) {
            int lx = i & 15;
            int lz = i >> 4;
            int wx = origin.getX() + lx;
            int wz = origin.getZ() + lz;
            float pct = ClientSporeDust.columnLevel(wx, wz);
            if (pct < p.minPercent() || ClientSporeDust.swept(wx, wz, true)) {
                continue;
            }
            // 不透明度は胞子計の % にそろえる(10-24 ユーザーの不具合: 1% でも白く濃かった)。1% でうっすら・20〜30% で膜・100% で上限
            float frac = Math.min(1.0F, pct / 100.0F);
            float a = p.maxAlpha() * (float) Math.pow(frac, p.gamma());
            a *= 0.82F + 0.36F * ((ClientSporeDust.hash(wx, wz) >>> 24) / 255.0F); // 列ごとに少しむら
            int color = Math.round(255 * Mth.lerp(frac, thin[0], thick[0])) << 16 | Math.round(255 * Mth.lerp(frac, thin[1], thick[1])) << 8
                    | Math.round(255 * Mth.lerp(frac, thin[2], thick[2]));
            if (nether) {
                // ネザー(2026-10-23 F): このセクションの中の空洞の床ぜんぶ(高さ地図は天井の岩盤を指す)。
                for (int y = y0; y < y0 + 16 && y < ceiling; y++) {
                    pos.set(wx, y, wz);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isFaceSturdy(level, pos, Direction.UP) || !ClientSporeDust.dustSurface(level, chunk, pos, state)
                            || !com.yayuy.overgrownvegetation.corruption.NetherSurface.isOpen(level.getBlockState(up.set(wx, y + 1, wz)))) {
                        continue;
                    }
                    spots.add((y << 8) | i);
                    lift.add(1.005F);
                    alphas.add(Math.min(1.0F, a));
                    colors.add(color);
                }
                continue;
            }
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz); // いちばん上のブロックの高さ(-1 済み)
            if (top < y0 || top >= y0 + 16) {
                continue;
            }
            pos.set(wx, top, wz);
            BlockState state = chunk.getBlockState(pos);
            if (!ClientSporeDust.dustSurface(level, chunk, pos, state)) {
                continue;
            }
            FluidState fluid = state.getFluidState();
            float y;
            if (!fluid.isEmpty() && fluid.is(FluidTags.WATER) && !state.isFaceSturdy(level, pos, Direction.UP)) {
                y = fluid.getHeight(level, pos) + 0.005F; // 水面に浮かべる
                a *= p.waterFactor();
            } else if (state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES) || state.isFaceSturdy(level, pos, Direction.UP)) {
                y = 1.005F;
                if (state.is(BlockTags.LEAVES)) {
                    a *= p.leafFactor();
                }
            } else {
                continue; // 柵・ハーフ・板などには載せない
            }
            spots.add((top << 8) | i);
            lift.add(y);
            alphas.add(Math.min(1.0F, a));
            colors.add(color);
        }
        if (spots.isEmpty()) {
            return;
        }
        BlockPos fixed = origin.immutable();
        int[] s = spots.toIntArray();
        float[] l = lift.toFloatArray();
        float[] al = alphas.toFloatArray();
        int[] c = colors.toIntArray();
        event.addRenderer(context -> render(context, fixed, s, l, al, c));
    }

    /** セクション 1 つぶんの粉を描く(組み立てのスレッド)。spots = (面のブロックの y << 8) | 列(z * 16 + x)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, int[] spots, float[] lift,
                               float[] alphas, int[] colors) {
        BlockAndTintGetter region = context.getRegion();
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite[] levels = new TextureAtlasSprite[ALPHA_STEPS + 1];
        for (int k = 1; k <= ALPHA_STEPS; k++) {
            levels[k] = atlas.getSprite(LEVEL_SPRITES[k]);
        }
        VertexConsumer vc = context.getOrCreateChunkBuffer(RenderType.translucent());
        Matrix4f m = context.getPoseStack().last().pose();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        for (int n = 0; n < spots.length; n++) {
            // 濃さは絵に焼いた 16 段から選ぶ(頂点の透明度は 255。Sodium は頂点の透明度を使わない)。
            int step = Mth.clamp(Math.round(alphas[n] * ALPHA_STEPS), 1, ALPHA_STEPS); // minPercent を超えた列はいちばん薄い段でも描く(Codex の指摘、10-31)
            int top = spots[n] >> 8;
            int i = spots[n] & 255;
            int lx = i & 15;
            int lz = i >> 4;
            int wx = origin.getX() + lx;
            int wz = origin.getZ() + lz;
            int light = LevelRenderer.getLightColor(region, above.set(wx, top + 1, wz));
            TextureAtlasSprite sprite = levels[step];
            float[] us = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
            float[] vs = {sprite.getV0(), sprite.getV0(), sprite.getV1(), sprite.getV1()};
            int cr = colors[n] >> 16 & 255;
            int cg = colors[n] >> 8 & 255;
            int cb = colors[n] & 255;
            // 同じ絵がブロックごとに同じ向きで並ぶと縞に見えるので、座標の雑音で 4 向き × 裏返しを選ぶ。
            int h = ClientSporeDust.hash(wx, wz);
            int rot = h & 3;
            boolean flip = (h & 4) != 0;
            float x0 = lx;
            float z0 = lz;
            float y = top - origin.getY() + lift[n];
            float[][] corners = {{x0, z0}, {x0 + 1, z0}, {x0 + 1, z0 + 1}, {x0, z0 + 1}};
            // 上から見て反時計回り(上向きの面)。
            int[] order = {0, 3, 2, 1};
            for (int k = 0; k < 4; k++) {
                int c = order[k];
                int uvi = flip ? (3 - ((c + rot) & 3)) : ((c + rot) & 3);
                vc.addVertex(m, corners[c][0], y, corners[c][1]).setColor(cr, cg, cb, 255)
                        .setUv(us[uvi], vs[uvi]).setLight(light).setNormal(0, 1, 0);
            }
        }
    }
}

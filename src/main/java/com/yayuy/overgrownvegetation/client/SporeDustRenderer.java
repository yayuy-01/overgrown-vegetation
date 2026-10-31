package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import it.unimi.dsi.fastutil.floats.FloatArrayList;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
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
import net.neoforged.neoforge.client.model.data.ModelData;
import org.joml.Matrix4f;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * <b>胞子の粉を地面に描く</b>(2026-10-31 合間の小物 3。元は parked の {@code ClientSporeDust} の描画。見えないと「どこをシャベルで払えるか」が分からなかった)。
 * 降り積もり %({@link ClientSporeDust})で、各列のいちばん上の面(地面・屋根・葉・水面)の上に点々の板を 1 枚重ねる。ブロックは書き換えない。
 *
 * <h2>固い層に入れる(10-31 相談セッションの答え (a))</h2>
 * 前は半透明の層で描いていた(並べ替えが粉のある区画の分だけ増える)。今は覆い({@link CoverRenderer})と同じく {@link AddSectionGeometryEvent}
 * (Sodium 0.8 も呼ぶ)で<b>ミップの無い cutout の層</b>へ足す。半透明が使えないので:
 * <ul>
 *     <li>点は 1 画素ずつ離れた不透明な白い点(絵 {@code spore_dust_<模様>_<段>}、段 1〜8 で点の数が増える。生成器は {@code tools/spore-dust})。
 *     濃さは点の数で出す。</li>
 *     <li>点の色 = 下の面の上面の平均色(草・葉・水の色付け込み)と粉の色(薄い所は白緑・濃い所は黄緑)の混ぜ。濃い段ほど粉の色に寄せる
 *     —— 薄い所は地面の色に近い点がうっすら、濃い所ははっきりした白緑〜黄緑の点の膜(半透明に重ねたのと近い見え方)。</li>
 *     <li>同じ模様が並んで格子・縞に見えないよう、ブロックの座標から模様(2 種)・向き(4)・裏返しを選ぶ。</li>
 * </ul>
 * 濃さ(0〜1)= (降り積もり % ÷ 100) の {@code dust.gamma} 乗 × 列ごとのむら、葉の上は {@code leafFactor}・水面は {@code waterFactor} 倍。
 * 載せるのは固い上面・葉・氷・水面だけ。払った升(境は雑音で 1 ブロックぼかす)・汚染済み・覆われた面・過繁茂のバイオームには載せない。
 * 粉の無い区画には追加の描画を登録しない(バニラの軽い組み立てのまま)。数値は {@code atmosphere.json} の節 {@code dust}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, value = Dist.CLIENT)
public final class SporeDustRenderer {

    private static final RenderType LAYER = RenderType.cutout();
    private static final int STEPS = 8;
    private static final net.minecraft.resources.ResourceLocation[][] SPRITES = new net.minecraft.resources.ResourceLocation[2][STEPS + 1];
    /** 絵ごとの上面の平均色(透けていない画素の平均。組み立てのスレッドからも読む)。 */
    private static final Map<TextureAtlasSprite, Integer> AVERAGE = new ConcurrentHashMap<>();

    static {
        for (int v = 0; v < 2; v++) {
            for (int k = 1; k <= STEPS; k++) {
                SPRITES[v][k] = OvergrownVegetation.id(String.format("block/spore_dust_%d_%d", v, k));
            }
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
        // 粉を載せる面(本スレッドで写す): (面のブロックの y << 8) | 列、載せる高さ、濃さ 0〜1。
        IntArrayList spots = new IntArrayList();
        FloatArrayList lift = new FloatArrayList();
        FloatArrayList strength = new FloatArrayList();
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
            float a = (float) Math.pow(Math.min(1.0F, pct / 100.0F), p.gamma());
            a *= 0.82F + 0.36F * ((ClientSporeDust.hash(wx, wz) >>> 24) / 255.0F);
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
                    lift.add(1.002F);
                    strength.add(Math.min(1.0F, a));
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
                y = fluid.getHeight(level, pos) + 0.002F;
                a *= p.waterFactor();
            } else if (state.is(BlockTags.ICE) || state.is(BlockTags.LEAVES) || state.isFaceSturdy(level, pos, Direction.UP)) {
                y = 1.002F;
                if (state.is(BlockTags.LEAVES)) {
                    a *= p.leafFactor();
                }
            } else {
                continue; // 柵・ハーフ・板などには載せない
            }
            spots.add((top << 8) | i);
            lift.add(y);
            strength.add(Math.min(1.0F, a));
        }
        if (spots.isEmpty()) {
            return;
        }
        BlockPos fixed = origin.immutable();
        int[] s = spots.toIntArray();
        float[] l = lift.toFloatArray();
        float[] st = strength.toFloatArray();
        event.addRenderer(context -> render(context, fixed, s, l, st, p));
    }

    /** セクション 1 つぶんの粉を描く(組み立てのスレッド)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, int[] spots, float[] lift,
                               float[] strength, AtmosphereTable.DustParams p) {
        BlockAndTintGetter region = context.getRegion();
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        VertexConsumer vc = context.getOrCreateChunkBuffer(LAYER);
        Matrix4f m = context.getPoseStack().last().pose();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        RandomSource random = RandomSource.create();
        float[] thin = p.thinColor();
        float[] thick = p.thickColor();
        for (int n = 0; n < spots.length; n++) {
            float a = strength[n];
            int step = Mth.clamp(Mth.ceil(a * STEPS), 1, STEPS);
            int top = spots[n] >> 8;
            int i = spots[n] & 255;
            int lx = i & 15;
            int lz = i >> 4;
            int wx = origin.getX() + lx;
            int wz = origin.getZ() + lz;
            pos.set(wx, top, wz);
            above.set(wx, top + 1, wz);
            int ground = groundColor(region, pos, random);
            // 濃い段ほど粉の色に寄せる(薄い所は地面に近い色の点がうっすら)。
            float w = 0.25F + 0.6F * a;
            float dr = Mth.lerp(a, thin[0], thick[0]);
            float dg = Mth.lerp(a, thin[1], thick[1]);
            float db = Mth.lerp(a, thin[2], thick[2]);
            int cr = Math.round(255 * Mth.lerp(w, ((ground >> 16) & 255) / 255.0F, dr));
            int cg = Math.round(255 * Mth.lerp(w, ((ground >> 8) & 255) / 255.0F, dg));
            int cb = Math.round(255 * Mth.lerp(w, (ground & 255) / 255.0F, db));
            int light = LevelRenderer.getLightColor(region, above);
            // 模様・向き・裏返しをブロックの座標から(並べたときの格子・縞を崩す)。
            int h = ClientSporeDust.hash(wx, wz);
            TextureAtlasSprite sprite = atlas.getSprite(SPRITES[(h >>> 3) & 1][step]);
            float[] us = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
            float[] vs = {sprite.getV0(), sprite.getV0(), sprite.getV1(), sprite.getV1()};
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

    /** 下の面の上面の平均色(色付け込み、0xRRGGBB)。水は水の色、模型の上の面が無ければ粒の絵。 */
    private static int groundColor(BlockAndTintGetter region, BlockPos pos, RandomSource random) {
        BlockState state = region.getBlockState(pos);
        if (!state.getFluidState().isEmpty() && state.getFluidState().is(FluidTags.WATER) && !state.isFaceSturdy(region, pos, Direction.UP)) {
            return scale(BiomeColors.getAverageWaterColor(region, pos), 0.75F);
        }
        Minecraft mc = Minecraft.getInstance();
        BakedModel model = mc.getBlockRenderer().getBlockModel(state);
        random.setSeed(state.getSeed(pos));
        List<BakedQuad> quads = model.getQuads(state, Direction.UP, random, ModelData.EMPTY, null);
        BakedQuad quad = quads.isEmpty() ? null : quads.get(0);
        if (quad == null) {
            random.setSeed(state.getSeed(pos));
            for (BakedQuad q : model.getQuads(state, null, random, ModelData.EMPTY, null)) {
                if (q.getDirection() == Direction.UP) {
                    quad = q;
                    break;
                }
            }
        }
        TextureAtlasSprite sprite = quad != null ? quad.getSprite() : model.getParticleIcon(ModelData.EMPTY);
        int avg = AVERAGE.computeIfAbsent(sprite, SporeDustRenderer::average);
        if (quad != null && quad.isTinted()) {
            int tint = mc.getBlockColors().getColor(state, region, pos, quad.getTintIndex());
            if (tint != -1) {
                avg = multiply(avg, tint);
            }
        }
        return avg;
    }

    private static int average(TextureAtlasSprite sprite) {
        try {
            NativeImage image = sprite.contents().getOriginalImage();
            long r = 0;
            long g = 0;
            long b = 0;
            long n = 0;
            for (int y = 0; y < sprite.contents().height(); y++) {
                for (int x = 0; x < sprite.contents().width(); x++) {
                    int abgr = image.getPixelRGBA(x, y);
                    if ((abgr >>> 24) < 128) {
                        continue;
                    }
                    r += abgr & 0xFF;
                    g += (abgr >> 8) & 0xFF;
                    b += (abgr >> 16) & 0xFF;
                    n++;
                }
            }
            if (n > 0) {
                return (int) (r / n) << 16 | (int) (g / n) << 8 | (int) (b / n);
            }
        } catch (RuntimeException ignored) {
            // 絵が読めない(資源の読み直しの途中など)は灰色の土に。
        }
        return 0x6B5A44;
    }

    private static int multiply(int a, int b) {
        int r = ((a >> 16) & 255) * ((b >> 16) & 255) / 255;
        int g = ((a >> 8) & 255) * ((b >> 8) & 255) / 255;
        int bl = (a & 255) * (b & 255) / 255;
        return r << 16 | g << 8 | bl;
    }

    private static int scale(int c, float k) {
        return Math.round(((c >> 16) & 255) * k) << 16 | Math.round(((c >> 8) & 255) * k) << 8 | Math.round((c & 255) * k);
    }
}

package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.network.SporeDustPayload;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import org.joml.Matrix4f;

/**
 * 胞子の粉の見た目(2026-10-23 胞子の場と風 第 2 段 E の (1))。サーバーが送るチャンクごとの降り積もりの濃さ(0〜7)で、
 * 各列のいちばん上のブロック(地面・屋根・葉)の上面に半透明の点々の板を 1 枚重ねる(絵は白い点、色は頂点の色で白緑〜黄緑)。ブロックは書き換えない(書き込みゼロ)。
 * 嵐の直後には風下一帯が一斉に色づく。濃さが変わったチャンクは描き直す。列ごとの濃さはまわり 4 チャンクの真ん中から
 * 距離で混ぜ(四角く見せない)、絵の向きは世界座標の雑音で変える(縞に見せない)。固い面・水面・氷・葉の上だけ(10-23)。
 *
 * <p>受け取りはワールドに入る途中にも来るので、受け取る所でワールドの替わりを見る(memory client-payload-before-first-tick)。
 * 粉の無いセクションには追加の描画を登録しない(バニラの軽い組み立てのまま)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClientSporeDust {

    private static final Long2ByteOpenHashMap LEVELS = new Long2ByteOpenHashMap();
    /** シャベルで払った升の印(チャンクごとの 16 ビット、spore/field/SporeSweeping)。 */
    private static final it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap MASKS = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
    private static ClientLevel lastLevel;
    private static final net.minecraft.resources.ResourceLocation SPRITE = OvergrownVegetation.id("block/spore_dust");
    /** 不透明度を 1/16 ずつ焼いた粉の絵(spore_dust_a01〜a16。生成は scratchpad の DustLevels と同じ手順: 元の絵の透明度 × k/16)。 */
    private static final int ALPHA_STEPS = 16;
    private static final net.minecraft.resources.ResourceLocation[] LEVEL_SPRITES = new net.minecraft.resources.ResourceLocation[ALPHA_STEPS + 1];

    static {
        for (int k = 1; k <= ALPHA_STEPS; k++) LEVEL_SPRITES[k] = OvergrownVegetation.id(String.format("block/spore_dust_a%02d", k));
    }

    private ClientSporeDust() {
    }

    public static void accept(SporeDustPayload p) {
        ClientSporeAir.accept(p); // 浮遊・プレイヤーの所の濃度・巨大樹の梢(緑の霧とプルーム、2026-10-23 G)
        ClientLevel level = Minecraft.getInstance().level;
        if (level != lastLevel) {
            LEVELS.clear();
            MASKS.clear();
            lastLevel = level;
        }
        LevelRenderer renderer = Minecraft.getInstance().levelRenderer;
        it.unimi.dsi.fastutil.longs.LongOpenHashSet dirty = new it.unimi.dsi.fastutil.longs.LongOpenHashSet();
        for (int dz = 0; dz < p.size(); dz++)
            for (int dx = 0; dx < p.size(); dx++) {
                int cx = p.minCx() + dx, cz = p.minCz() + dz;
                long key = ChunkPos.asLong(cx, cz);
                byte v = p.levels()[dz * p.size() + dx];
                byte old = LEVELS.get(key);
                int mask = p.masks()[dz * p.size() + dx], oldMask = MASKS.get(key);
                if (mask != oldMask) {
                    if (mask == 0) MASKS.remove(key);
                    else MASKS.put(key, mask);
                    for (int ox = -1; ox <= 1; ox++)
                        for (int oz = -1; oz <= 1; oz++) dirty.add(ChunkPos.asLong(cx + ox, cz + oz));
                }
                if (v == old) continue;
                if (v == 0) LEVELS.remove(key);
                else LEVELS.put(key, v);
                // 隣のチャンクの列も混ぜて描くので、まわり 1 チャンクも描き直す
                for (int ox = -1; ox <= 1; ox++)
                    for (int oz = -1; oz <= 1; oz++) dirty.add(ChunkPos.asLong(cx + ox, cz + oz));
            }
        if (level != null) {
            int minS = level.getMinSection(), maxS = level.getMaxSection();
            for (long k : dirty) {
                int cx = ChunkPos.getX(k), cz = ChunkPos.getZ(k);
                for (int sy = minS; sy < maxS; sy++) renderer.setSectionDirty(cx, sy, cz);
            }
        }
        if (LEVELS.size() > 20000) {
            LEVELS.clear();
            MASKS.clear();
        }
    }

    /** ワールドを出たら捨てる(次に入ったワールドで場が回らないと送りが来ない)。 */
    @SubscribeEvent
    public static void onLoggingOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        LEVELS.clear();
        MASKS.clear();
        lastLevel = null;
    }

    /**
     * 列ごとの降り積もり %(胞子計と同じ物差し、0〜100 の連続値。2026-10-24 から。前は 0〜7 の段)。濃度の読みと同じく、まわり 4 つのチャンクの真ん中の値を距離で混ぜる
     * (チャンクの境が四角く見えないように。10-23 テストクライアントで指摘)。
     */
    private static float columnLevel(int x, int z) {
        float fx = (x + 0.5F) / 16.0F - 0.5F, fz = (z + 0.5F) / 16.0F - 0.5F;
        int i0 = net.minecraft.util.Mth.floor(fx), j0 = net.minecraft.util.Mth.floor(fz);
        float tx = fx - i0, tz = fz - j0;
        float a = LEVELS.get(ChunkPos.asLong(i0, j0)), b = LEVELS.get(ChunkPos.asLong(i0 + 1, j0));
        float c = LEVELS.get(ChunkPos.asLong(i0, j0 + 1)), d = LEVELS.get(ChunkPos.asLong(i0 + 1, j0 + 1));
        return (a * (1 - tx) + b * tx) * (1 - tz) + (c * (1 - tx) + d * tx) * tz;
    }

    /**
     * その列が払った升に入るか。jitter なら升の境を世界座標の雑音で ±1 ブロックずらして見る(4×4 の四角をぼかす)。
     */
    private static boolean swept(int x, int z, boolean jitter) {
        if (MASKS.isEmpty()) return false;
        if (jitter) {
            int h = hash(x * 31 + 7, z * 17 + 3);
            x += Math.floorMod(h, 3) - 1;
            z += Math.floorMod(h >>> 8, 3) - 1;
        }
        int mask = MASKS.get(ChunkPos.asLong(x >> 4, z >> 4));
        return mask != 0 && (mask & (1 << (((z & 15) >> 2) * 4 + ((x & 15) >> 2)))) != 0;
    }

    /**
     * その上面に粉を載せるか: 過繁茂・極相のバイオーム、汚染済み(#corrupted)か覆われた上面には載せない
     * (2026-10-24 ユーザー指定: 粉はまだ汚染されていない所だけ。降り積もりの値は場に残る)。
     */
    private static boolean dustSurface(Level level, LevelChunk chunk, BlockPos pos, BlockState state) {
        if (state.is(com.yayuy.overgrownvegetation.ModTags.CORRUPTED)) return false;
        if (com.yayuy.overgrownvegetation.corruption.OvergrownLayerData.isCovered(chunk, pos, state)) return false;
        return !com.yayuy.overgrownvegetation.registry.ModBiomes.isOvergrownLike(level.getBiome(pos.above()));
    }

    /** その上面に粉が見えているか(シャベルで払えるか。画面側の予測用)。 */
    public static boolean hasDustAt(BlockPos pos) {
        if (Minecraft.getInstance().level != lastLevel) return false;
        if (lastLevel != null && !dustSurface(lastLevel, lastLevel.getChunkAt(pos), pos, lastLevel.getBlockState(pos))) return false;
        int cx = pos.getX() >> 4, cz = pos.getZ() >> 4;
        if (LEVELS.get(ChunkPos.asLong(cx, cz)) <= 0) return false;
        return columnLevel(pos.getX(), pos.getZ()) >= (float) com.yayuy.overgrownvegetation.data.DesignData.sporeField().dustMinPercent()
                && !swept(pos.getX(), pos.getZ(), false);
    }

    /** 世界座標の雑音(0〜1)。 */
    private static int hash(int x, int z) {
        int h = x * 0x27D4EB2D ^ z * 0x165667B1;
        h ^= h >>> 15;
        h *= 0x2C1B3C6D;
        h ^= h >>> 12;
        return h;
    }

    @SubscribeEvent
    public static void onAddSectionGeometry(AddSectionGeometryEvent event) {
        // 前のワールド・別の次元の値で描かない(新しいワールドで場が回らないと送りが来ず、前の粉が残っていた。10-24 ユーザーの不具合)
        if (LEVELS.isEmpty() || event.getLevel() != lastLevel) return;
        Level level = event.getLevel();
        BlockPos origin = event.getSectionOrigin();
        int cx = SectionPos.blockToSectionCoord(origin.getX()), cz = SectionPos.blockToSectionCoord(origin.getZ());
        boolean near = false;
        for (int ox = -1; ox <= 1 && !near; ox++)
            for (int oz = -1; oz <= 1; oz++)
                if (LEVELS.get(ChunkPos.asLong(cx + ox, cz + oz)) > 0) {
                    near = true;
                    break;
                }
        if (!near) return;
        LevelChunk chunk = level.getChunk(cx, cz);
        boolean nether = level.dimension() == Level.NETHER;
        // 粉を載せる面(メインスレッドで高さ・載せる高さ・濃さを写す)。オーバーワールドは列のいちばん上、
        // ネザー(2026-10-23 F)はこのセクションの中の空洞の床ぜんぶ(高さ地図は天井の岩盤を指すので使わない)
        it.unimi.dsi.fastutil.ints.IntArrayList spots = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.floats.FloatArrayList lift = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        it.unimi.dsi.fastutil.floats.FloatArrayList alphas = new it.unimi.dsi.fastutil.floats.FloatArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList colors = new it.unimi.dsi.fastutil.ints.IntArrayList();
        int y0 = origin.getY();
        int ceiling = nether ? com.yayuy.overgrownvegetation.corruption.NetherSurface.topY(level) : Integer.MAX_VALUE;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos up = new BlockPos.MutableBlockPos();
        com.yayuy.overgrownvegetation.data.SporeFieldTable look = com.yayuy.overgrownvegetation.data.DesignData.sporeField();
        float minPct = (float) look.dustMinPercent(), maxA = (float) look.dustMaxAlpha(), gamma = (float) look.dustGamma();
        float leafF = (float) look.dustLeafFactor(), waterF = (float) look.dustWaterFactor();
        for (int i = 0; i < 256; i++) {
            int lx = i & 15, lz = i >> 4;
            int wx = origin.getX() + lx, wz = origin.getZ() + lz;
            float lv = columnLevel(wx, wz); // 降り積もり %
            if (lv < minPct) continue;
            if (swept(wx, wz, true)) continue; // シャベルで払った升(境は雑音で 1 ブロックぼかす)
            // 不透明度は胞子計の % にそろえる(2026-10-24 ユーザーの不具合: 1% でも白く濃かった)。1% でうっすら・20〜30% で膜・100% で上限
            float frac = Math.min(1.0F, lv / 100.0F);
            float alpha = maxA * (float) Math.pow(frac, gamma);
            int h = hash(wx, wz);
            alpha *= 0.82F + 0.36F * ((h >>> 24) / 255.0F); // 列ごとに少しむらを付ける
            float t = frac;
            int color = ((int) net.minecraft.util.Mth.lerp(t, 214, 178) << 16) | ((int) net.minecraft.util.Mth.lerp(t, 236, 216) << 8)
                    | (int) net.minecraft.util.Mth.lerp(t, 200, 160);
            if (nether) {
                for (int y = y0; y < y0 + 16 && y < ceiling; y++) {
                    pos.set(wx, y, wz);
                    BlockState state = chunk.getBlockState(pos);
                    if (!state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)) continue;
                    if (!dustSurface(level, chunk, pos, state)) continue;
                    BlockState above = level.getBlockState(up.set(wx, y + 1, wz));
                    if (!com.yayuy.overgrownvegetation.corruption.NetherSurface.isOpen(above)) continue;
                    spots.add((y << 8) | i);
                    lift.add(1.005F);
                    alphas.add(Math.min(1.0F, alpha));
                    colors.add(color);
                }
                continue;
            }
            int top = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, lx, lz); // getHeight はもういちばん上のブロックの高さ(-1 済み)
            if (top < y0 || top >= y0 + 16) continue;
            pos.set(wx, top, wz);
            BlockState state = chunk.getBlockState(pos);
            if (!dustSurface(level, chunk, pos, state)) continue; // 汚染済みの地面・過繁茂のバイオームには載せない(2026-10-24)
            net.minecraft.world.level.material.FluidState fluid = state.getFluidState();
            float y;
            if (!fluid.isEmpty() && fluid.is(net.minecraft.tags.FluidTags.WATER) && !state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)) {
                y = fluid.getHeight(level, pos) + 0.005F; // 水面に浮かべる
                alpha *= waterF; // 水面は地面より強く見えるので薄く(2026-10-24)
            } else if (state.is(net.minecraft.tags.BlockTags.ICE) || state.is(net.minecraft.tags.BlockTags.LEAVES)
                    || state.isFaceSturdy(level, pos, net.minecraft.core.Direction.UP)) {
                y = 1.005F;
                if (state.is(net.minecraft.tags.BlockTags.LEAVES)) alpha *= leafF; // 葉の上は地面より薄く
            } else {
                continue; // 水面・氷・葉以外の非固体(柵・ハーフ・板など)には載せない
            }
            // 色は白緑〜黄緑(霧の緑がかった色と揃える。10-23 ユーザー指定)。濃い所でも地面の色が透ける程度の不透明度(最大 0.42 × 絵の点)
            spots.add((top << 8) | i);
            lift.add(y);
            alphas.add(Math.min(1.0F, alpha));
            colors.add(color);
        }
        if (spots.isEmpty()) return;
        BlockPos fixed = origin.immutable();
        int[] s = spots.toIntArray();
        float[] l = lift.toFloatArray(), a = alphas.toFloatArray();
        int[] c = colors.toIntArray();
        event.addRenderer(context -> render(context, fixed, s, l, a, c));
    }

    /** spots = (面のブロックの y << 8) | 列(z * 16 + x)。 */
    private static void render(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, int[] spots,
                               float[] lift, float[] alphas, int[] colors) {
        BlockAndTintGetter region = context.getRegion();
        // 濃さは絵に焼いた 16 段から選ぶ(Sodium は頂点の透明度を使わず、粉が不透明に描かれていた。2026-10-24 ユーザーの不具合)。頂点の透明度は 255
        TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(TextureAtlas.LOCATION_BLOCKS);
        TextureAtlasSprite[] levels = new TextureAtlasSprite[ALPHA_STEPS + 1];
        for (int k = 1; k <= ALPHA_STEPS; k++) levels[k] = atlas.getSprite(LEVEL_SPRITES[k]);
        VertexConsumer vc = context.getOrCreateChunkBuffer(RenderType.translucent());
        PoseStack pose = context.getPoseStack();
        Matrix4f m = pose.last().pose();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int n = 0; n < spots.length; n++) {
            int top = spots[n] >> 8;
            int i = spots[n] & 255;
            int lx = i & 15, lz = i >> 4;
            int wx = origin.getX() + lx, wz = origin.getZ() + lz;
            pos.set(wx, top + 1, wz);
            int light = LevelRenderer.getLightColor(region, pos);
            float x0 = lx, z0 = lz, y = top - origin.getY() + lift[n];
            int step = Math.min(ALPHA_STEPS, Math.round(alphas[n] * ALPHA_STEPS));
            if (step <= 0) continue;
            TextureAtlasSprite sprite = levels[step];
            float[] us = {sprite.getU0(), sprite.getU1(), sprite.getU1(), sprite.getU0()};
            float[] vs = {sprite.getV0(), sprite.getV0(), sprite.getV1(), sprite.getV1()};
            int a = 255;
            int cr = colors[n] >> 16 & 255, cg = colors[n] >> 8 & 255, cb = colors[n] & 255;
            // 同じ絵がブロックごとに同じ向きで並ぶと縞に見えるので、世界座標の雑音で 4 向き × 裏返しを選ぶ
            int h = hash(wx, wz);
            int rot = h & 3;
            boolean flip = (h & 4) != 0;
            float[][] corners = {{x0, z0}, {x0 + 1, z0}, {x0 + 1, z0 + 1}, {x0, z0 + 1}};
            // 上から見て反時計回り(上向きの面)に x0z0 → x0z1 → x1z1 → x1z0
            int[] order = {0, 3, 2, 1};
            for (int k = 0; k < 4; k++) {
                int c = order[k];
                int uvi = flip ? (3 - ((c + rot) & 3)) : ((c + rot) & 3);
                vc.addVertex(m, corners[c][0], y, corners[c][1]).setColor(cr, cg, cb, a)
                        .setUv(us[uvi], vs[uvi]).setLight(light).setNormal(0, 1, 0);
            }
        }
    }
}

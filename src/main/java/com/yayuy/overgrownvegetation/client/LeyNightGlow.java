package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * <b>夜にぼんやり光る地脈とエネルギー結晶</b>(2026-10-16。ユーザー「夜になると地脈やエネルギー結晶がぼんやり光る」(10-15)→
 * 選択「光のにじみを足す」: 夜だけ、地脈ブロックと結晶のまわりに柔らかい光のにじみと、ゆっくり立ちのぼる小さな光の粒を描く。
 * 周りの明るさ(ブロックの光)は変えない —— 極相の暗さはそのまま)。
 *
 * <ul>
 *     <li>どこにあるか: プレイヤーの周り(水平 ±3 チャンク、上下 ±2 区画)の区画を毎 tick {@link #SECTIONS_PER_TICK} ずつ見直す
 *     (区画のパレットに地脈も結晶も無ければブロックを読まない)。地脈は上が開いている(不透明なブロックが乗っていない)ものだけ。</li>
 *     <li>地脈のにじみ: 上面に 2×2 ブロックの、中心が明るく縁が 0 の水平の板。隣どうしが重なって、線より少し広い柔らかい帯になる。</li>
 *     <li>結晶のにじみ: 結晶は何ブロックもの塊なので、2×2×2 の升ごとにまとめ、升の中心にカメラを向くぼやけた丸
 *     (塊が大きいほど大きく、明るさは頭打ち。重ねて足すと白く飛ぶので)。</li>
 *     <li>粒: 地脈・結晶の上から、毎秒 0.3 ブロックほどで 3〜5 秒立ちのぼって消える。</li>
 *     <li>夜の度合い = 1 − 太陽の高さで決まる昼の強さ(雨は数えない。雨の昼は光らない)。昼は描かない。</li>
 *     <li><b>一つずつ灯る・消える</b>(2026-10-16 ユーザー指定「夕方と夜明けに発光が一つずつ灯る・消える」): 光ごとに場所のハッシュで灯る順番を持ち、
 *     夕方は夜の度合いがそれを越えたものから 4 秒ほどで灯り、夜明けは遅く灯ったものから消える。実の付いた光るツタのまわりにも黄緑のにじみを足した
 *     (ツタの明かりそのものはブロックの光で、昼も夜も変わらない —— 湧き・明るさを変えないため)。</li>
 * </ul>
 * 色は地脈・結晶の青緑。設定 {@code leyNightGlow}。数値はすべて仮決め。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class LeyNightGlow {

    private static final int CHUNK_RADIUS = 3;
    private static final int SECTION_RADIUS = 2;
    private static final int SECTIONS_PER_TICK = 6;
    private static final double RANGE = 40.0;
    private static final int MAX_MOTES = 140;
    private static final float R = 0.35F;
    private static final float G = 1.0F;
    private static final float B = 0.82F;
    /** 地脈の上の板・結晶の丸の芯の濃さ(加算)。仮決め。 */
    private static final float VEIN_ALPHA = 0.16F;
    private static final float CRYSTAL_ALPHA = 0.22F;
    /** 光るツタの実のにじみ(黄緑。実の色 170,253,0 から)の大きさと濃さ。仮決め。 */
    private static final float VINE_SIZE = 1.8F;
    private static final float VINE_ALPHA = 0.32F;
    private static final float VINE_R = 0.72F;
    private static final float VINE_G = 1.0F;
    private static final float VINE_B = 0.35F;
    /** 洞窟の胞子嚢のにじみの大きさと濃さ(色は地脈・結晶の青緑)。仮決め。 */
    private static final float SAC_SIZE = 0.9F;
    private static final float SAC_ALPHA = 0.14F;
    /**
     * 一つずつ灯る: 光ごとの灯る順番(夜の度合いのどこで灯るか)の幅と、1 つが灯りきるまでの夜の度合いの幅(約 4 秒)。
     * 夕方は順番の早いものから一つずつ灯り、夜明けは遅いものから一つずつ消える(2026-10-16 ユーザー指定「夕方と夜明けに発光が
     * 一つずつ灯る・消える」。順番は場所のハッシュで、仮決め)。
     */
    private static final float LIT_FROM = 0.08F;
    private static final float LIT_TO = 0.92F;
    private static final float LIT_SPAN = 0.04F;

    /** 区画ごとの、見つけた地脈(上が開いたもの)・結晶・実の付いた光るツタの位置。 */
    private static final class Found {
        final long[] veins;
        final long[] crystals;
        final long[] vines;
        /** 洞窟の胞子嚢(地下の光る植物)。 */
        final long[] sacs;

        Found(long[] veins, long[] crystals, long[] vines, long[] sacs) {
            this.veins = veins;
            this.crystals = crystals;
            this.vines = vines;
            this.sacs = sacs;
        }
    }

    private static final class Mote {
        final double x;
        double y;
        final double z;
        final int life;
        int age;
        final float size;

        Mote(double x, double y, double z, int life, float size) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.life = life;
            this.size = size;
        }
    }

    private static final Long2ObjectOpenHashMap<Found> SECTIONS = new Long2ObjectOpenHashMap<>();
    private static final List<Mote> MOTES = new ArrayList<>();
    private static final RandomSource RANDOM = RandomSource.create();
    private static ClientLevel glowLevel;
    private static int cursor;
    private static float night;
    private static boolean broken;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != glowLevel) {
            SECTIONS.clear();
            MOTES.clear();
            glowLevel = level;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        if (!OvergrownVegetationConfig.COMMON.leyNightGlow.get() || !level.dimensionType().hasSkyLight()) {
            night = 0.0F;
            MOTES.clear();
            return;
        }
        // 夜の度合いは太陽の高さだけで決める(雨の昼は夜ではない。CanopyLight#daylight は雨で弱まるので使わない)。
        float sunY = (float) CanopyLight.sunDirection(level, 1.0F).y;
        night = 1.0F - CanopyLight.smooth(Mth.clamp((sunY - 0.06F) / 0.3F, 0.0F, 1.0F));
        // 極相の密林では昼も地脈・結晶が光る(明るさは地脈や結晶が吐き返す光 = ユーザー決定の橋渡し案)。
        night = Math.max(night, CLIMAX_DAY_GLOW * ClimaxJungle.strength());
        // 区画を少しずつ見直す(遠くなった区画は捨てる)。
        int pcx = player.chunkPosition().x;
        int pcz = player.chunkPosition().z;
        int psy = SectionPos.blockToSectionCoord(Mth.floor(player.getY()));
        int side = CHUNK_RADIUS * 2 + 1;
        int layers = SECTION_RADIUS * 2 + 1;
        int total = side * side * layers;
        for (int n = 0; n < SECTIONS_PER_TICK; n++) {
            cursor = (cursor + 1) % total;
            int dx = cursor % side - CHUNK_RADIUS;
            int dz = (cursor / side) % side - CHUNK_RADIUS;
            int dy = cursor / (side * side) - SECTION_RADIUS;
            scan(level, pcx + dx, psy + dy, pcz + dz);
        }
        if (cursor < SECTIONS_PER_TICK) {
            SECTIONS.long2ObjectEntrySet().removeIf(e -> Math.abs(SectionPos.x(e.getLongKey()) - pcx) > CHUNK_RADIUS + 1
                    || Math.abs(SectionPos.z(e.getLongKey()) - pcz) > CHUNK_RADIUS + 1
                    || Math.abs(SectionPos.y(e.getLongKey()) - psy) > SECTION_RADIUS + 1);
        }
        tickMotes();
        tickResonance(level, player);
    }

    /** 区画 1 つを見て、地脈(上が開いたもの)と結晶の位置を覚える。どちらも無ければ忘れる。 */
    private static void scan(ClientLevel level, int sx, int sy, int sz) {
        long key = SectionPos.asLong(sx, sy, sz);
        int index = level.getSectionIndexFromSectionY(sy);
        if (index < 0 || index >= level.getSectionsCount() || !level.hasChunk(sx, sz)) {
            SECTIONS.remove(key);
            return;
        }
        LevelChunk chunk = level.getChunk(sx, sz);
        LevelChunkSection section = chunk.getSection(index);
        Block vein = ModBlocks.LEY_VEIN.get();
        Block crystal = ModBlocks.ENERGY_CRYSTAL.get();
        Block sac = ModBlocks.CAVE_SPORE_SAC.get();
        if (section.hasOnlyAir() || !section.maybeHas(state -> state.is(vein) || state.is(crystal) || isLitVine(state) || state.is(sac))) {
            SECTIONS.remove(key);
            return;
        }
        LongArrayList veins = new LongArrayList();
        LongArrayList crystals = new LongArrayList();
        LongArrayList vines = new LongArrayList();
        LongArrayList sacs = new LongArrayList();
        BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();
        int baseX = SectionPos.sectionToBlockCoord(sx);
        int baseY = SectionPos.sectionToBlockCoord(sy);
        int baseZ = SectionPos.sectionToBlockCoord(sz);
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState state = section.getBlockState(x, y, z);
                    if (state.is(vein)) {
                        BlockState over = level.getBlockState(above.set(baseX + x, baseY + y + 1, baseZ + z));
                        if (!over.canOcclude() && !over.is(vein)) {
                            veins.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                        }
                    } else if (state.is(crystal)) {
                        crystals.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    } else if (isLitVine(state)) {
                        vines.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    } else if (state.is(sac)) {
                        sacs.add(BlockPos.asLong(baseX + x, baseY + y, baseZ + z));
                    }
                }
            }
        }
        if (veins.isEmpty() && crystals.isEmpty() && vines.isEmpty() && sacs.isEmpty()) {
            SECTIONS.remove(key);
        } else {
            SECTIONS.put(key, new Found(veins.toLongArray(), crystals.toLongArray(), vines.toLongArray(), sacs.toLongArray()));
        }
    }

    private static void tickMotes() {
        for (Iterator<Mote> it = MOTES.iterator(); it.hasNext(); ) {
            Mote m = it.next();
            if (++m.age >= m.life) {
                it.remove();
                continue;
            }
            m.y += 0.015;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (SECTIONS.isEmpty()) {
            return;
        }
        // 夜(地下では昼も)、灯っている地脈・結晶・ツタ・胞子嚢の上から粒を少しずつ立ちのぼらせる。
        Found[] all = SECTIONS.values().toArray(new Found[0]);
        for (int tries = 0; tries < 3 && MOTES.size() < MAX_MOTES; tries++) {
            Found f = all[RANDOM.nextInt(all.length)];
            int kind = RANDOM.nextInt(4);
            long[] from = kind == 0 ? f.crystals : (kind == 1 ? f.veins : (kind == 2 ? f.vines : f.sacs));
            if (from.length == 0) {
                continue;
            }
            long p = from[RANDOM.nextInt(from.length)];
            // 灯っている光からだけ(灯った所から順に粒が立ちのぼり始める)。
            double mdx = BlockPos.getX(p) + 0.5 - player.getX();
            double mdz = BlockPos.getZ(p) + 0.5 - player.getZ();
            if (RANDOM.nextFloat() > 0.5F * lit(p) * omen(Math.sqrt(mdx * mdx + mdz * mdz), p)) {
                continue;
            }
            MOTES.add(new Mote(BlockPos.getX(p) + RANDOM.nextDouble(), BlockPos.getY(p) + 1.0 + RANDOM.nextDouble() * 0.3,
                    BlockPos.getZ(p) + RANDOM.nextDouble(), 60 + RANDOM.nextInt(40), 0.035F + RANDOM.nextFloat() * 0.04F));
        }
    }

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken || SECTIONS.isEmpty()) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("夜の地脈・結晶のにじみの描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Minecraft mc = Minecraft.getInstance();
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double seconds = (mc.level == null ? 0L : mc.level.getGameTime()) / 20.0 + partialTick / 20.0;
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        Long2IntOpenHashMap clusters = new Long2IntOpenHashMap();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        for (Found f : SECTIONS.values()) {
            // 実の付いた光るツタ: 実のまわりにぼやけた黄緑の丸(ツタの明かりそのものはブロックの光で、昼も夜も変わらない)。
            for (long p : f.vines) {
                double hx = BlockPos.getX(p) + 0.5 - eye.x;
                double hy = BlockPos.getY(p) + 0.45 - eye.y;
                double hz = BlockPos.getZ(p) + 0.5 - eye.z;
                double dist = Math.sqrt(hx * hx + hz * hz);
                if (dist > RANGE) {
                    continue;
                }
                float twinkle = (float) (0.85 + 0.15 * Math.sin(seconds * 1.3 + (p & 63L)));
                disk(c, (float) hx, (float) hy, (float) hz, VINE_SIZE, left, up, VINE_ALPHA * lit(p) * twinkle * fade(dist) * omen(dist, p), VINE_R, VINE_G, VINE_B);
            }
            // 洞窟の胞子嚢: 小さな青緑の丸(床・壁・天井に張り付いた袋のまわり)。
            for (long p : f.sacs) {
                double hx = BlockPos.getX(p) + 0.5 - eye.x;
                double hy = BlockPos.getY(p) + 0.5 - eye.y;
                double hz = BlockPos.getZ(p) + 0.5 - eye.z;
                double dist = Math.sqrt(hx * hx + hz * hz);
                if (dist > RANGE) {
                    continue;
                }
                float breathe = (float) (0.8 + 0.2 * Math.sin(seconds * 0.6 + (p & 31L)));
                disk(c, (float) hx, (float) hy, (float) hz, SAC_SIZE, left, up, SAC_ALPHA * lit(p) * breathe * fade(dist) * omen(dist, p));
            }
            // 地脈: 上面に 2×2 ブロックの、中心が明るく縁が 0 の板(隣どうしが重なって柔らかい帯になる)。
            for (long p : f.veins) {
                int x = BlockPos.getX(p);
                int y = BlockPos.getY(p);
                int z = BlockPos.getZ(p);
                double hx = x + 0.5 - eye.x;
                double hz = z + 0.5 - eye.z;
                double dist = Math.sqrt(hx * hx + hz * hz);
                if (dist > RANGE) {
                    continue;
                }
                // ゆっくり息をするように明るさが揺れる(地脈ブロックの明滅と同じ 10 秒ほどの周期、場所でずらす)。
                float breathe = (float) (0.75 + 0.25 * Math.sin(seconds * Math.PI * 2.0 / 10.0 + (x * 0.37 + z * 0.29)));
                float a = VEIN_ALPHA * lit(p) * breathe * fade(dist) * omen(dist, p);
                // (6) 網の目が見える: 極相の密林では、地脈を光の脈が行き先(ハイヴノード・巨大樹・器官)の方へ流れる。
                a *= flowPulse(x, z, seconds);
                float cx = (float) hx;
                float cy = (float) (y + 1.03 - eye.y);
                float cz = (float) hz;
                for (int i = 0; i < 4; i++) {
                    float ax = (i == 0 || i == 3) ? -1.0F : 1.0F;
                    float az = (i < 2) ? -1.0F : 1.0F;
                    float bx = (i == 0 || i == 1) ? 1.0F : -1.0F;
                    float bz = (i == 0 || i == 3) ? -1.0F : 1.0F;
                    c.addVertex(cx, cy, cz).setColor(R, G, B, a);
                    c.addVertex(cx + ax, cy, cz + az).setColor(R, G, B, 0.0F);
                    c.addVertex(cx + bx, cy, cz + bz).setColor(R, G, B, 0.0F);
                    c.addVertex(cx, cy, cz).setColor(R, G, B, a);
                }
            }
            // 結晶: 2×2×2 の升にまとめる。
            for (long p : f.crystals) {
                long cell = BlockPos.asLong(BlockPos.getX(p) >> 1, BlockPos.getY(p) >> 1, BlockPos.getZ(p) >> 1);
                clusters.addTo(cell, 1);
            }
        }
        for (Long2IntOpenHashMap.Entry e : clusters.long2IntEntrySet()) {
            long cell = e.getLongKey();
            double x = BlockPos.getX(cell) * 2 + 1.0 - eye.x;
            double y = BlockPos.getY(cell) * 2 + 1.0 - eye.y;
            double z = BlockPos.getZ(cell) * 2 + 1.0 - eye.z;
            double dist = Math.sqrt(x * x + z * z);
            if (dist > RANGE) {
                continue;
            }
            int count = e.getIntValue();
            float size = 1.2F + 0.25F * Math.min(8, count);
            float a = CRYSTAL_ALPHA * lit(cell, BlockPos.asLong(BlockPos.getX(cell) * 2, BlockPos.getY(cell) * 2, BlockPos.getZ(cell) * 2)) * Math.min(1.0F, 0.4F + count / 8.0F) * fade(dist) * omen(dist, cell);
            // (5) 結晶の共鳴: 近くを通ると、結晶が近い順に光る(鳴るのは {@link #tickResonance})。
            a *= 1.0F + RESONANCE_BOOST * resonanceAt(BlockPos.getX(cell) * 2 + 1.0, BlockPos.getZ(cell) * 2 + 1.0, seconds);
            disk(c, (float) x, (float) y, (float) z, size, left, up, a);
        }
        for (Mote m : MOTES) {
            float life = Math.min(1.0F, m.age / 15.0F) * Math.min(1.0F, (m.life - m.age) / 20.0F);
            double px = m.x - eye.x;
            double py = m.y + 0.015 * partialTick - eye.y;
            double pz = m.z - eye.z;
            if (px * px + pz * pz > RANGE * RANGE) {
                continue;
            }
            disk(c, (float) px, (float) py, (float) pz, m.size, left, up, 0.85F * life);
        }
        buffers.endBatch(CanopyLight.renderType());
    }

    /** 場所 {@code pos}(BlockPos.asLong か升の番号)の光が今灯っている度合い(0〜1)。 */
    static float lit(long pos) {
        return lit(pos, pos);
    }

    /**
     * {@code key} で灯る順番を決め、{@code at}(BlockPos.asLong)の空の光で地下か見る。地下(空の光 4 未満)では昼も灯ったまま
     * (洞窟に昼夜は無い。2026-10-16 ユーザー指定「地下の光: 光る植物と結晶のにじみ」)。
     */
    static float lit(long key, long at) {
        float n = night;
        if (n < 1.0F && glowLevel != null && glowLevel.getBrightness(net.minecraft.world.level.LightLayer.SKY, BlockPos.of(at)) < 4) {
            n = 1.0F;
        }
        float order = LIT_FROM + (LIT_TO - LIT_FROM) * hash01(key);
        return CanopyLight.smooth(Mth.clamp((n - order) / LIT_SPAN + 0.5F, 0.0F, 1.0F));
    }

    /** 場所の番号から 0〜1 の決まった値。 */
    static float hash01(long key) {
        long h = key * 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        h ^= h >>> 31;
        return (h >>> 40) / (float) (1L << 24);
    }

    /** 前触れ(TD の群れが近づくと遠くの方から一つずつ消える、{@link TdOmen})で消えていない度合い。灯る順番とは別の値で決める。 */
    private static float omen(double dist, long key) {
        return 1.0F - TdOmen.darkAt(dist, hash01(key ^ 0x5DEECE66DL), RANGE);
    }

    private static boolean isLitVine(BlockState state) {
        return (state.is(ModBlocks.OVERGROWN_GLOW_VINES.get()) || state.is(ModBlocks.OVERGROWN_GLOW_VINES_PLANT.get()))
                && state.getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.BERRIES);
    }

    // ------------------------------------------------------------------ 極相の密林(2026-10-16 ユーザー指定、極相の 8 案の (5)(6))

    /** 極相の密林で昼に灯っている度合い(夜の度合いの下限)。 */
    private static final float CLIMAX_DAY_GLOW = 0.95F;
    /** 地脈を流れる光の脈: 間隔(ブロック)・速さ(ブロック / 秒)。 */
    private static final double FLOW_PERIOD = 14.0;
    private static final double FLOW_SPEED = 4.0;
    /** 結晶の共鳴: 光が広がる速さ(ブロック / 秒)・届く距離・明るさの上がり方・始まる近さ・次に鳴るまで(秒)。 */
    private static final double RESONANCE_SPEED = 12.0;
    private static final double RESONANCE_RANGE = 22.0;
    private static final float RESONANCE_BOOST = 2.5F;
    private static final double RESONANCE_TRIGGER = 4.0;
    private static final double RESONANCE_COOLDOWN = 12.0;
    private static final int MAX_CHIMES = 10;

    private static double resonanceX;
    private static double resonanceZ;
    private static double resonanceStart = -1000.0;
    private static final List<double[]> CHIMES = new ArrayList<>();

    /**
     * (6) 網の目が見える: 極相の密林では、地脈のブロックの明るさに、線に沿って行き先へ流れる光の脈を掛ける({@link LeyFlow#alongAt} の
     * 線に沿った座標。サーバーが向きを行き先へそろえて送る = ⑦ 光の流れをたどる と同じ向き)。極相の外・線を知らないマスは 1。
     */
    private static float flowPulse(int x, int z, double seconds) {
        float s = ClimaxJungle.strength();
        if (s <= 0.0F) {
            return 1.0F;
        }
        float along = LeyFlow.alongAt(x, z);
        if (Float.isNaN(along)) {
            return 1.0F;
        }
        double phase = (along - seconds * FLOW_SPEED) / FLOW_PERIOD;
        double wave = 0.5 + 0.5 * Math.cos((phase - Math.floor(phase)) * Mth.TWO_PI);
        float pulse = (float) (wave * wave * wave);
        return Mth.lerp(s, 1.0F, 0.55F + 1.6F * pulse);
    }

    /** (5) 結晶の共鳴の、場所 (x, z) の明るさの上がり具合(0〜1)。共鳴の起点から光の輪が広がり、輪が通る間だけ明るい。 */
    private static float resonanceAt(double x, double z, double seconds) {
        double t = seconds - resonanceStart;
        if (t < 0.0 || t > RESONANCE_RANGE / RESONANCE_SPEED + 2.0) {
            return 0.0F;
        }
        double d = Math.sqrt((x - resonanceX) * (x - resonanceX) + (z - resonanceZ) * (z - resonanceZ));
        if (d > RESONANCE_RANGE) {
            return 0.0F;
        }
        double since = t - d / RESONANCE_SPEED;
        if (since < 0.0) {
            return 0.0F;
        }
        return (float) (Math.exp(-since * 1.6) * Math.min(1.0, since * 8.0));
    }

    /** (5) 結晶の共鳴: 極相の密林で結晶の 4 ブロック以内に来たら起こし、近い結晶から順に鳴らす。12 秒は鳴り直さない。 */
    private static void tickResonance(ClientLevel level, LocalPlayer player) {
        double seconds = level.getGameTime() / 20.0;
        for (Iterator<double[]> it = CHIMES.iterator(); it.hasNext(); ) {
            double[] chime = it.next();
            if (seconds >= chime[0]) {
                level.playLocalSound(chime[1], chime[2], chime[3], net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_CHIME,
                        net.minecraft.sounds.SoundSource.BLOCKS, 0.7F, (float) chime[4], false);
                it.remove();
            }
        }
        if (ClimaxJungle.strength() < 0.3F || seconds - resonanceStart < RESONANCE_COOLDOWN) {
            return;
        }
        List<double[]> near = new ArrayList<>();
        boolean trigger = false;
        for (Found f : SECTIONS.values()) {
            for (long p : f.crystals) {
                double x = BlockPos.getX(p) + 0.5;
                double y = BlockPos.getY(p) + 0.5;
                double z = BlockPos.getZ(p) + 0.5;
                double d = Math.sqrt((x - player.getX()) * (x - player.getX()) + (z - player.getZ()) * (z - player.getZ()));
                if (d <= RESONANCE_TRIGGER && Math.abs(y - player.getY()) < 6.0) {
                    trigger = true;
                }
                if (d <= RESONANCE_RANGE) {
                    near.add(new double[] {d, x, y, z});
                }
            }
        }
        if (!trigger) {
            return;
        }
        resonanceX = player.getX();
        resonanceZ = player.getZ();
        resonanceStart = seconds;
        // 鳴らすのは 3 ブロックごとに 1 つ(結晶は何ブロックもの塊なので、全部鳴らすとうるさい)。近い順に MAX_CHIMES まで。
        near.sort((a, b) -> Double.compare(a[0], b[0]));
        double lastRing = -10.0;
        for (double[] c : near) {
            if (CHIMES.size() >= MAX_CHIMES) {
                break;
            }
            if (c[0] - lastRing < 3.0) {
                continue;
            }
            lastRing = c[0];
            CHIMES.add(new double[] {seconds + c[0] / RESONANCE_SPEED, c[1], c[2], c[3], 0.7 + 0.04 * CHIMES.size()});
        }
    }

    /**
     * 開発用・{@link ClimaxJungle} の浮くもの: (x, z) から水平 {@code range} 以内の地脈のブロックを 1 つ選ぶ(覚えている区画から)。
     * 無ければ {@link Long#MIN_VALUE}。
     */
    static long randomVeinNear(double x, double z, double range, RandomSource random) {
        if (SECTIONS.isEmpty()) {
            return Long.MIN_VALUE;
        }
        Found[] all = SECTIONS.values().toArray(new Found[0]);
        for (int tries = 0; tries < 6; tries++) {
            Found f = all[random.nextInt(all.length)];
            if (f.veins.length == 0) {
                continue;
            }
            long p = f.veins[random.nextInt(f.veins.length)];
            double dx = BlockPos.getX(p) + 0.5 - x;
            double dz = BlockPos.getZ(p) + 0.5 - z;
            if (dx * dx + dz * dz <= range * range) {
                return p;
            }
        }
        return Long.MIN_VALUE;
    }

    private static float fade(double dist) {
        return 1.0F - CanopyLight.smooth(Mth.clamp((float) ((dist - RANGE * 0.7) / (RANGE * 0.3)), 0.0F, 1.0F));
    }

    /** カメラを向くぼやけた丸(中心が明るく縁が 0 の 8 枚の三角)。色は地脈・結晶の青緑。 */
    private static void disk(VertexConsumer c, float x, float y, float z, float size, Vector3f left, Vector3f up, float alpha) {
        disk(c, x, y, z, size, left, up, alpha, R, G, B);
    }

    private static void disk(VertexConsumer c, float x, float y, float z, float size, Vector3f left, Vector3f up, float alpha,
                             float r, float g, float b) {
        if (alpha <= 0.003F) {
            return;
        }
        for (int i = 0; i < 8; i++) {
            float a0 = i * Mth.TWO_PI / 8.0F;
            float a1 = (i + 1) * Mth.TWO_PI / 8.0F;
            float px = (left.x() * Mth.cos(a0) + up.x() * Mth.sin(a0)) * size;
            float py = (left.y() * Mth.cos(a0) + up.y() * Mth.sin(a0)) * size;
            float pz = (left.z() * Mth.cos(a0) + up.z() * Mth.sin(a0)) * size;
            float qx = (left.x() * Mth.cos(a1) + up.x() * Mth.sin(a1)) * size;
            float qy = (left.y() * Mth.cos(a1) + up.y() * Mth.sin(a1)) * size;
            float qz = (left.z() * Mth.cos(a1) + up.z() * Mth.sin(a1)) * size;
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
            c.addVertex(x + px, y + py, z + pz).setColor(r, g, b, 0.0F);
            c.addVertex(x + qx, y + qy, z + qz).setColor(r, g, b, 0.0F);
            c.addVertex(x, y, z).setColor(r, g, b, alpha);
        }
    }

    private LeyNightGlow() {
    }
}

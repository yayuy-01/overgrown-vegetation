package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.GlStateManager;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.block.CaveLightProbeBlock;
import com.yayuy.overgrownvegetation.block.OvergrownGlowVines;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.AtmosphereCave;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.lwjgl.system.MemoryUtil;

import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * <b>地下モード</b>(2026-11-01 試作。仕様は Vault の「地下モード(洞窟のシェーダーと光)」)。過繁茂の地域の地下を、地上の森(明るい・黄緑の胞子の霧)とは
 * 別の見え方にする: 黒い霧(見通し 12〜16、ユーザー指定)の中に植物の光だけが点々と浮かぶ。
 *
 * <ul>
 *     <li><b>地上と混ぜる係数 {@link #mix()}</b>(0〜1): まわりの 17 列のうち岩の天井がある割合(真上 1 列の {@link ForestPresence#underground()} だと口の 1 歩で跳んだ)× 過繁茂の地域(カメラの所か、その列の地表の
 *     バイオーム)× 空の光の暗さ(カメラの所の空の光が {@code skyFrom} から下がり始め {@code skyTo} 以下で 1。洞窟の口から奥へ歩くと少しずつ地下になる。仮決め)。
 *     目標へは時定数 transitionCaveSeconds(11-01 夜から 4 秒、前は 1 秒の直線)で寄る。黒い霧はバニラの霧({@link MistView})で出すので Mob・粒・水にも同じに掛かる。地上の朝霧と胞子の霧はこの分だけ引く({@link MistFog})。</li>
 *     <li><b>点光源</b>: カメラのまわり {@code radius}(48)の読み込み済みの区画から「光を出すブロック」({@link #lookup})を 2 秒おきに集める
 *     (区画のパレットに光源が無ければ中を読まない。作業スレッド {@link ScanThread})。毎フレーム近い順に最大 {@code maxLights}(24)個を選び、
 *     24×2 の小さな浮動小数のテクスチャ {@code OvCaveLights}(1 行目 = カメラからの位置と半径、2 行目 = 色と強さ)で地形シェーダーへ渡す
 *     (バニラのシェーダーの json は配列の uniform を宣言できないため。Sodium へは {@code SodiumTerrain} が名前で写す)。
 *     面の色には {@code 絵の色 × 光の色 × 強さ / (1 + (d/r)²)} を足し、r〜1.5r で 0 へ落とす(霧より前。{@code ov_cave.glsl})。</li>
 *     <li><b>Mob・手に持った物・粒</b>: 同じ光源の表から {@code client/light/DynamicLights} がライトマップのブロックの光を持ち上げる。</li>
 * </ul>
 * 壁は光を遮らない(描画だけの光。試作では影を付けない)。湧き・植物の判定は本物の光量(0〜3)だけ。
 */
public final class CaveLights {

    /** シェーダーへ渡す光源の上限(テクスチャの幅。設計値の maxLights はこれ以下)。 */
    public static final int MAX = 24;
    /** テクスチャの列(光源 {@value #MAX} + 床の持ち上げ・飽和の抑えの 1 列)。 */
    private static final int COLS = MAX + 1;
    private static final int SCAN_TICKS = 40;

    /** 光源の種類: 半径・色・強さ(仕様の表。仮決め)。 */
    public record Kind(float radius, float r, float g, float b, float strength) {
        static Kind of(float radius, int rgb, float strength) {
            return new Kind(radius, (rgb >> 16 & 0xFF) / 255.0F, (rgb >> 8 & 0xFF) / 255.0F, (rgb & 0xFF) / 255.0F, strength);
        }
    }

    static final Kind TASSEL = Kind.of(22.0F, 0xACE300, 1.0F);
    static final Kind FOXFIRE = Kind.of(4.0F, 0x6FD08A, 0.5F);
    static final Kind LEY_NEAR = Kind.of(6.0F, 0xACE300, 0.6F);
    static final Kind LEY_FAR = Kind.of(3.0F, 0x5F7D00, 0.25F);
    static final Kind VINE = Kind.of(6.0F, 0x9FC23A, 0.5F);

    /** 1 つの光源(ブロックの真ん中)。 */
    public record Light(double x, double y, double z, Kind kind) {
    }

    private static float mix;
    private static float mixTarget;
    private static long scannedAt = -1_000_000L;
    @Nullable
    private static CompletableFuture<Light[]> scanning;
    /** 集めた光源(作業スレッドの結果を丸ごと差し替える)。 */
    private static volatile Light[] found = new Light[0];
    /** このフレームにシェーダーへ渡した光源(近い順)。Mob の明るさもこれを読む。 */
    private static volatile Light[] shown = new Light[0];
    /** 確かめ用: 渡す数の上限を上書き(-1 = 設計値)。 */
    static int devMaxLights = -1;
    /** 確かめ用: 地下の係数を赤で塗る(シェーダーの OvCaveFogD.w)。 */
    static boolean devTint;

    private static int texture;
    private static FloatBuffer buffer;

    private CaveLights() {
    }

    /** 地上と混ぜる係数(0 = 地上のまま、1 = 地下モード)。 */
    public static float mix() {
        return mix;
    }

    /** このフレームに渡した光源(近い順)。 */
    public static Light[] shown() {
        return shown;
    }

    static void clear() {
        mix = 0.0F;
        mixTarget = 0.0F;
        scanning = null;
        found = new Light[0];
        shown = new Light[0];
        scannedAt = -1_000_000L;
        CaveMap.clear();
    }

    /** 光を出すブロックなら、その種類(無ければ null)。作業スレッドからも呼ぶ。 */
    @Nullable
    static Kind lookup(BlockState state) {
        if (state.getBlock() instanceof CaveLightProbeBlock) {
            return switch (state.getValue(CaveLightProbeBlock.KIND)) {
                case TASSEL -> TASSEL;
                case FOXFIRE -> FOXFIRE;
                case LEY_NEAR -> LEY_NEAR;
                case LEY_FAR -> LEY_FAR;
                case VINE -> VINE;
            };
        }
        if ((state.is(ModBlocks.OVERGROWN_GLOW_VINES.get()) || state.is(ModBlocks.OVERGROWN_GLOW_VINES_PLANT.get()))
                && OvergrownGlowVines.hasGlowBerries(state)) {
            return VINE;
        }
        return null;
    }

    /** 毎 tick({@link Atmosphere} から)。 */
    static void tick(ClientLevel level, Vec3 eye, long ticks) {
        if (!VisualQuality.caveLight()) {
            clear();
            return;
        }
        AtmosphereCave.CaveParams p = DesignData.atmosphere().cave();
        if (ticks % 10 == 3) {
            mixTarget = target(level, eye, p);
        }
        // 11-01 夜(ユーザー「地上 → 洞窟の変わり目をもっとゆっくり」): 1 秒の直線 → 時定数 transitionCaveSeconds(4 秒)の指数。
        // 黒い霧・点光源の強さ(シェーダーで × mix)・Mob の明るさがどれもこの値なので、まとめて滑らかになる。
        mix = ForestPresence.approach(mix, mixTarget, 0.05F, DesignData.atmosphere().fog().transitionCaveSeconds());
        if (scanning != null && scanning.isDone()) {
            Light[] done = scanning.getNow(null);
            scanning = null;
            if (done != null) {
                found = done;
            }
        }
        // 11-01 夜(見え方の直し 4): 断片の所で地下を決める地図。カメラが外でも、まわりに過繁茂の地下があれば光源を集める(外から洞窟の中が照らされて見える)。
        CaveMap.tick(level, eye, ticks);
        if (mix <= 0.0F && mixTarget <= 0.0F && !CaveMap.nearOvergrown()) {
            found = new Light[0];
            return;
        }
        if (scanning == null && ticks - scannedAt >= SCAN_TICKS) {
            scannedAt = ticks;
            int cx = Mth.floor(eye.x);
            int cy = Mth.floor(eye.y);
            int cz = Mth.floor(eye.z);
            int radius = Math.round(p.radius());
            scanning = CompletableFuture.supplyAsync(() -> scan(level, cx, cy, cz, radius), ScanThread.EXECUTOR).exceptionally(t -> {
                OvergrownVegetation.LOGGER.warn("地下の光源を集められなかった", t);
                return null;
            });
        }
    }

    /** 天井を見る所: {半径, 重さ}(ForestPresence の覆いと同じ並び)。真ん中 1 か所・半径 4 と 9 の 8 方向ずつ。 */
    private static final float[][] RINGS = {{0.0F, 3.0F}, {4.0F, 1.5F}, {9.0F, 1.0F}};
    private static final int[][] DIRS = {{1, 0}, {1, 1}, {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}, {0, -1}, {1, -1}};

    /**
     * まわりの列のうち岩の天井({@link CaveMap#thickRoofAbove})がある割合(0〜1、真ん中ほど重く)。真上 1 列だけだと洞窟の口の 1 歩で 0 → 1 に跳んだ(11-01 撮影)ので、
     * 口から奥へ数ブロックかけて上がるようにまわりの 17 列で見る。
     */
    static float roofFraction(ClientLevel level, Vec3 eye) {
        float roofed = 0.0F;
        float total = 0.0F;
        for (float[] ring : RINGS) {
            for (int i = 0; i < (ring[0] == 0.0F ? 1 : DIRS.length); i++) {
                double len = DIRS[i][0] != 0 && DIRS[i][1] != 0 ? Math.sqrt(2.0) : 1.0;
                Vec3 at = new Vec3(eye.x + DIRS[i][0] * ring[0] / len, eye.y, eye.z + DIRS[i][1] * ring[0] / len);
                total += ring[1];
                // 11-02: 地下の地図と同じ数え方(3 段以上続く岩)。1 段の不透明なブロック(巨大樹の幹の上の土など)では天井にしない。
                if (CaveMap.thickRoofAbove(level, Mth.floor(at.x), Mth.floor(at.y) + 1, Mth.floor(at.z), 4096)) {
                    roofed += ring[1];
                }
            }
        }
        return total > 0.0F ? roofed / total : 0.0F;
    }

    /** 地下モードの目標(岩の天井の下・過繁茂の地域・空の光の暗さ)。 */
    private static float target(ClientLevel level, Vec3 eye, AtmosphereCave.CaveParams p) {
        float roof = SunLight.smooth(Mth.clamp((roofFraction(level, eye) - 0.3F) / 0.6F, 0.0F, 1.0F));
        if (roof <= 0.0F) {
            return 0.0F;
        }
        BlockPos at = BlockPos.containing(eye);
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(at));
        if (!overgrown) {
            int top = level.getHeight(Heightmap.Types.WORLD_SURFACE, at.getX(), at.getZ()) - 1;
            overgrown = top > at.getY() && ModBiomes.isOvergrownLike(level.getBiome(new BlockPos(at.getX(), top, at.getZ())));
        }
        if (!overgrown) {
            return 0.0F;
        }
        float sky = level.getBrightness(LightLayer.SKY, at);
        float dark = Mth.clamp((p.skyFrom() - sky) / (p.skyFrom() - p.skyTo()), 0.0F, 1.0F);
        return roof * SunLight.smooth(dark);
    }

    /** 作業スレッド: カメラのまわりの区画から光源を集める(パレットに光源が無い区画は中を読まない)。 */
    private static Light[] scan(ClientLevel level, int cx, int cy, int cz, int radius) {
        List<Light> out = new ArrayList<>();
        int r2 = radius * radius;
        int minSx = SectionPos.blockToSectionCoord(cx - radius);
        int maxSx = SectionPos.blockToSectionCoord(cx + radius);
        int minSz = SectionPos.blockToSectionCoord(cz - radius);
        int maxSz = SectionPos.blockToSectionCoord(cz + radius);
        int minSy = Math.max(level.getMinSection(), SectionPos.blockToSectionCoord(cy - radius));
        int maxSy = Math.min(level.getMaxSection() - 1, SectionPos.blockToSectionCoord(cy + radius));
        for (int sx = minSx; sx <= maxSx; sx++) {
            for (int sz = minSz; sz <= maxSz; sz++) {
                LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
                if (chunk == null) {
                    continue;
                }
                LevelChunkSection[] sections = chunk.getSections();
                for (int sy = minSy; sy <= maxSy; sy++) {
                    LevelChunkSection section = sections[level.getSectionIndexFromSectionY(sy)];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> lookup(s) != null)) {
                        continue;
                    }
                    int bx = SectionPos.sectionToBlockCoord(sx);
                    int by = SectionPos.sectionToBlockCoord(sy);
                    int bz = SectionPos.sectionToBlockCoord(sz);
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                Kind kind = lookup(state);
                                if (kind == null) {
                                    continue;
                                }
                                int wx = bx + x;
                                int wy = by + y;
                                int wz = bz + z;
                                int dx = wx - cx;
                                int dy = wy - cy;
                                int dz = wz - cz;
                                if (dx * dx + dy * dy + dz * dz > r2) {
                                    continue;
                                }
                                // 垂れる房は先(いちばん下)だけが光る玉(縦に並べると強さ 1 が段の数だけ重なるので)。
                                if (kind == TASSEL && level.getBlockState(new BlockPos(wx, wy - 1, wz)).getBlock() instanceof CaveLightProbeBlock
                                        && lookup(level.getBlockState(new BlockPos(wx, wy - 1, wz))) == TASSEL) {
                                    continue;
                                }
                                out.add(new Light(wx + 0.5, wy + 0.5, wz + 0.5, kind));
                            }
                        }
                    }
                }
            }
        }
        return out.toArray(new Light[0]);
    }

    /** 近い順に {@code max} 個(カメラからの距離)。 */
    static Light[] nearest(Light[] all, Vec3 cam, int max) {
        if (all.length == 0 || max <= 0) {
            return new Light[0];
        }
        Light[] sorted = all.clone();
        Arrays.sort(sorted, (a, b) -> Double.compare(dist2(a, cam), dist2(b, cam)));
        return sorted.length <= max ? sorted : Arrays.copyOf(sorted, max);
    }

    private static double dist2(Light l, Vec3 c) {
        double dx = l.x - c.x;
        double dy = l.y - c.y;
        double dz = l.z - c.z;
        return dx * dx + dy * dy + dz * dz;
    }

    /** 1 つの光源が距離 d に届ける強さ(色を掛ける前。ov_cave.glsl と同じ式)。 */
    static float reach(Kind k, double d) {
        double q = d / k.radius();
        double fade = 1.0 - SunLight.smooth((float) Mth.clamp((d - k.radius()) / (0.5 * k.radius()), 0.0, 1.0));
        return (float) (k.strength() / (1.0 + q * q) * fade);
    }

    /** {@code (x, y, z)} に届く光の強さの和(地下モードの係数込み。Mob の明るさ)。 */
    public static float intensityAt(double x, double y, double z) {
        Light[] lights = shown;
        if (lights.length == 0 || mix <= 0.0F) {
            return 0.0F;
        }
        float sum = 0.0F;
        for (Light l : lights) {
            double dx = x - l.x;
            double dy = y - l.y;
            double dz = z - l.z;
            double d2 = dx * dx + dy * dy + dz * dz;
            double reach = 1.5 * l.kind.radius();
            if (d2 >= reach * reach) {
                continue;
            }
            sum += reach(l.kind, Math.sqrt(d2)) * Math.max(l.kind.r, Math.max(l.kind.g, l.kind.b));
        }
        return sum * mix;
    }

    /** 地形シェーダーへ渡す(AFTER_SKY、{@link Atmosphere} から)。{@code on} が偽・地下でないときは 0 個。 */
    static void upload(RenderLevelStageEvent event, boolean on) {
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader()};
        AtmosphereCave.CaveParams p = DesignData.atmosphere().cave();
        Light[] pick = new Light[0];
        Vec3 cam = event.getCamera().getPosition();
        boolean mapOn = on && VisualQuality.caveLight();
        if (mapOn) {
            CaveMap.upload();
        }
        boolean useMap = mapOn && CaveMap.ready();
        if (on && VisualQuality.caveLight() && (mix > 0.0F || useMap && CaveMap.nearOvergrown())) {
            pick = nearest(found, cam, Math.min(MAX, devMaxLights >= 0 ? devMaxLights : p.maxLights()));
        }
        shown = pick;
        // 光源が 0 個でも、地下モードの間は床の持ち上げ(ambient)と飽和の抑え(clipKnee)を渡す(11-01 夜)。
        float shaderMix = on && VisualQuality.caveLight() ? mix : 0.0F;
        if (shaderMix > 0.0F || useMap) {
            write(pick, cam, p);
        }
        float mapX = useMap ? (float) (CaveMap.originX() - cam.x) : 0.0F;
        float mapZ = useMap ? (float) (CaveMap.originZ() - cam.z) : 0.0F;
        boolean inFluid = event.getCamera().getFluidInCamera() != net.minecraft.world.level.material.FogType.NONE;
        float[] surf = MistFog.sporeColor();
        float surfK = DesignData.atmosphere().fog().forestFogDensity();
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            if (texture != 0) {
                shader.setSampler("OvCaveLights", texture);
            }
            shader.safeGetUniform("OvCave").set((float) pick.length, shaderMix, p.lightGain(), p.glowThreshold());
            if (useMap) {
                shader.setSampler("OvCaveMap", CaveMap.texture());
            }
            shader.safeGetUniform("OvCaveMapA").set(mapX, mapZ, (float) -cam.y, useMap ? 1.0F : 0.0F);
            shader.safeGetUniform("OvCaveMapB").set(p.roofFrom(), p.roofTo(), p.skyFrom() / 15.0F, p.skyTo() / 15.0F);
            shader.safeGetUniform("OvCaveFog").set(p.r(), p.g(), p.b(), 0.0F);
            // z: カメラが水(など液体)の中なら 1。水中の霧が地下の霧に勝つ(11-01 ユーザー: 海から崖の洞窟を見ると中だけ澄んでいた。
            // 断片の所の地下の霧 20〜28 が、水の霧を掛けた色を差し替えていた)。
            shader.safeGetUniform("OvCaveFogD").set(p.fogStart(), p.fogEnd(), inFluid ? 1.0F : 0.0F, devTint ? 1.0F : 0.0F);
            // 洞窟の中から見た口の外: カメラの黒い霧を外した代わりに森の胞子の霞(地上の霧と同じ色・濃さ)を距離で掛ける。
            shader.safeGetUniform("OvCaveSurf").set(surf[0], surf[1], surf[2], surfK);
        }
    }

    /**
     * 25×2 の浮動小数のテクスチャへ書く(1 行目 = カメラからの位置 + 半径、2 行目 = 色 + 強さ)。25 列目の 1 行目は光源でなく
     * (床の持ち上げの色 × ambient、飽和の抑え clipKnee)、2 行目は(持ち上げの彩度の抜き、抑えた所の彩度の抜き、0、0)(11-01 夜。uniform の OvCave は 4 つとも使っているので、ここに相乗りさせた)。
     */
    private static void write(Light[] lights, Vec3 cam, AtmosphereCave.CaveParams p) {
        if (buffer == null) {
            buffer = MemoryUtil.memAllocFloat(COLS * 2 * 4);
        }
        buffer.clear();
        for (int i = 0; i < MAX; i++) {
            if (i < lights.length) {
                Light l = lights[i];
                buffer.put((float) (l.x - cam.x)).put((float) (l.y - cam.y)).put((float) (l.z - cam.z)).put(l.kind.radius());
            } else {
                buffer.put(0.0F).put(-10000.0F).put(0.0F).put(1.0F);
            }
        }
        float[] ac = p.ambientColor();
        buffer.put(ac[0] * p.ambient()).put(ac[1] * p.ambient()).put(ac[2] * p.ambient()).put(p.clipKnee());
        for (int i = 0; i < MAX; i++) {
            if (i < lights.length) {
                Kind k = lights[i].kind;
                // 11-01 夜(見え方の直し 3): 光の色を白へ寄せる(飽和した面がライム一色でなく白っぽい緑になる)。表の色(仕様)はそのまま。
                float w = p.lightWhiten();
                buffer.put(Mth.lerp(w, k.r, 1.0F)).put(Mth.lerp(w, k.g, 1.0F)).put(Mth.lerp(w, k.b, 1.0F)).put(k.strength());
            } else {
                buffer.put(0.0F).put(0.0F).put(0.0F).put(0.0F);
            }
        }
        buffer.put(p.ambientDesaturate()).put(p.clipDesaturate()).put(0.0F).put(0.0F);
        buffer.flip();
        int previous = GlStateManager._getInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (texture == 0) {
            texture = GlStateManager._genTexture();
            GlStateManager._bindTexture(texture);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_NEAREST);
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL30.GL_TEXTURE_MAX_LEVEL, 0);
        } else {
            GlStateManager._bindTexture(texture);
        }
        GlStateManager._pixelStore(GL11.GL_UNPACK_ALIGNMENT, 4);
        GlStateManager._pixelStore(GL11.GL_UNPACK_ROW_LENGTH, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_PIXELS, 0);
        GlStateManager._pixelStore(GL11.GL_UNPACK_SKIP_ROWS, 0);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL30.GL_RGBA32F, COLS, 2, 0, GL11.GL_RGBA, GL11.GL_FLOAT, buffer);
        GlStateManager._bindTexture(previous);
    }

    /** 確かめ用の一行。 */
    static String describe() {
        return String.format("地下 %.2f(目標 %.2f)光源 %d 個 / 渡した %d 個 / ", mix, mixTarget, found.length, shown.length) + CaveMap.describe();
    }
}

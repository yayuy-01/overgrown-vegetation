package com.yayuy.overgrownvegetation.client.atmosphere;

import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.AtmosphereWater;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FlowingFluid;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * <b>水底の網目の光</b>(コースティクス、2026-10-31 夜 ユーザー「水中に入った時の、網目状に光が散乱するやつを再現したい」「現実に従って上から見ても潜っても」)。
 * 波立つ水面がレンズになって日光を集め、水の下の面に明るい曲線の網を落とす。
 *
 * <h2>水面の地図</h2>
 * カメラの周り {@link #SIZE}×{@link #SIZE} 列(1 列 1 画素)について、空へ開いたいちばん上の水面の高さを裏のスレッド({@link ScanThread})で
 * {@link #REBUILD_TICKS} tick ごとに調べ、地形のシェーダー({@code shaders/include/ov_caustic.glsl})へ渡す。
 * 列を上から下へたどり、空気・葉・当たり判定の無い物(蔓・草)は抜け、水に着いたらそこが水面、固いブロックに着いたら水は無し(洞窟や張り出しの下の水は光らない)。
 * 落ちている水(滝)の列は使わない。画素: r = 水面のブロック + 1(基準の高さから)、g = そのブロックの水の高さ、b = 上に何も無い 255・葉がある 128。
 * 光る筋({@link UnderwaterShafts})も同じ地図から水面を探す。
 *
 * <h2>シェーダーの中</h2>
 * 面の列(面の向きへ 0.3 ずらし、岸の壁は池の列を読む)の水面より下なら、深さで減る網目を明るさに掛ける。網目は 2 枚の動く細胞の境目
 * (屈折した太陽の向きで水面まで戻った点で引く = 深さと太陽の角度でずれる)。深いほど線が太くぼやけ、{@code maxDepth} で消える(乳白の水なので浅め)。
 * 葉の影は林冠の日向の地図({@link CanopySunMap})があればそれ、無ければ列の上の葉で弱める。同じ地形の描画なので、上から水越しに見ても潜って見ても出る。
 * 水そのもの(半透明)には付けない。Sodium 0.8 にも {@link com.yayuy.overgrownvegetation.client.shader.SodiumTerrain} が同じ uniform を写す。
 *
 * <h2>出し入れ</h2>
 * 昼だけ(太陽の高さ 0.05〜0.35 で出る)。過繁茂の地域の外の水にも出す(10-31 夜 ユーザー「現実の現象なので地域で区切らない」)。上から見たときは {@code fromAbove} 倍。
 * 潜って見上げた水面のスネルの窓と網目は 10-31 夜に試して、ユーザーの判断でやめた(水のシェーダーには触らない)。
 * 値は節 {@code caustics}(すべて仮決め)。
 */
public final class WaterCaustics {

    static final int SIZE = 96;
    /** 地図の基準の高さ = カメラの何ブロック下か(r は 1〜255 なので、上は基準 + 254 まで)。 */
    private static final int BELOW = 40;
    /** 作り直しの間隔(tick)。 */
    private static final int REBUILD_TICKS = 40;
    /** 列のいちばん上から何ブロック下まで水面を探すか(林冠の上から池まで)。 */
    private static final int SCAN_DOWN = 64;

    private static final class Map {
        final int originX;
        final int originZ;
        final int baseY;
        final int[] pixels = new int[SIZE * SIZE];

        Map(int originX, int originZ, int baseY) {
            this.originX = originX;
            this.originZ = originZ;
            this.baseY = baseY;
        }
    }

    @Nullable
    private static CompletableFuture<Map> building;
    private static long buildStartTick = -1_000_000L;
    private static int buildFailures;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static boolean broken;

    static void clear() {
        building = null;
        buildStartTick = -1_000_000L;
        shown = null;
    }

    /** 開発ログ用(カメラの列の水面・地図の有無・最後に渡した強さ)。 */
    static String describe() {
        Vec3 eye = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        int water = 0;
        Map m = shown;
        if (m != null) {
            for (int px : m.pixels) {
                if ((px & 0xFF) != 0) {
                    water++;
                }
            }
        }
        return String.format("網目 地図 %s 水の列 %d 足元の水面 %.2f 強さ %.3f%s", m != null, water,
                surfaceAt(Mth.floor(eye.x), Mth.floor(eye.z)), lastStrength, broken ? " 停止" : "");
    }

    private static float lastStrength;

    /** 列 (x, z) の空へ開いた水面の高さ(ワールドの y)。水面が無い・地図の外・地図がまだ無いなら NaN。 */
    static double surfaceAt(int x, int z) {
        int px = pixel(x, z);
        int r = px & 0xFF;
        return r == 0 ? Double.NaN : shown.baseY + r - 1 + ((px >> 8) & 0xFF) / 255.0;
    }

    /** 列 (x, z) の水面の上に葉も何も無いか。 */
    static boolean openAt(int x, int z) {
        int px = pixel(x, z);
        return (px & 0xFF) != 0 && ((px >> 16) & 0xFF) > 192;
    }

    private static int pixel(int x, int z) {
        Map m = shown;
        if (m == null) {
            return 0;
        }
        int i = x - m.originX;
        int j = z - m.originZ;
        return i < 0 || j < 0 || i >= SIZE || j >= SIZE ? 0 : m.pixels[j * SIZE + i];
    }

    /** 毎 tick({@link Atmosphere} から)。昼か潜っている間は地図を裏のスレッドで作り直す。 */
    static void tick(ClientLevel level, long ticks) {
        if (!VisualQuality.underwater()) {
            clear();
            return;
        }
        if (broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        var t = DesignData.atmosphere();
        boolean wanted = (t.caustics().strength() > 0.0F || t.underwaterShafts().alpha() > 0.0F)
                && SunLight.sunDirection(level, 1.0F).y > 0.03;
        if (!wanted) {
            building = null;
            shown = null;
            return;
        }
        if (building != null) {
            if (!building.isDone()) {
                return;
            }
            Map done = building.getNow(null);
            building = null;
            if (done != null) {
                shown = done;
                buildFailures = 0;
                uploadPending = true;
            }
        }
        if (ticks - buildStartTick < REBUILD_TICKS) {
            return;
        }
        buildStartTick = ticks;
        Map m = new Map(Mth.floor(eye.x) - SIZE / 2, Mth.floor(eye.z) - SIZE / 2, Mth.floor(eye.y) - BELOW);
        building = CompletableFuture.supplyAsync(() -> {
            BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
            for (int index = 0; index < SIZE * SIZE; index++) {
                m.pixels[index] = trace(level, m, index % SIZE, index / SIZE, pos);
            }
            return m;
        }, ScanThread.EXECUTOR).exceptionally(e -> {
            if (++buildFailures >= 3) {
                broken = true;
                OvergrownVegetation.LOGGER.error("水面の地図を 3 回続けて作れなかったので、このセッションでは網目の光を使わない", e);
            } else {
                OvergrownVegetation.LOGGER.warn("水面の地図を作れなかった(次の回にもう一度): {}", e.toString());
            }
            return null;
        });
    }

    /** 列 (i, j) を上から下へたどって画素(ABGR)を返す。水面が無ければ 0。 */
    private static int trace(ClientLevel level, Map m, int i, int j, BlockPos.MutableBlockPos pos) {
        int x = m.originX + i;
        int z = m.originZ + j;
        if (!level.hasChunkAt(pos.set(x, m.baseY, z))) {
            return 0;
        }
        // Level の getHeight は「いちばん上のブロックの 1 つ上」。
        int top = Math.min(level.getHeight(Heightmap.Types.WORLD_SURFACE, x, z) - 1, m.baseY + 254);
        int bottom = Math.max(m.baseY, top - SCAN_DOWN);
        boolean leaves = false;
        for (int y = top; y >= bottom; y--) {
            pos.set(x, y, z);
            FluidState fluid = level.getFluidState(pos);
            if (fluid.is(FluidTags.WATER)) {
                if (fluid.getValue(FlowingFluid.FALLING)) {
                    return 0;
                }
                int r = y - m.baseY + 1;
                int g = Mth.clamp(Math.round(fluid.getHeight(level, pos) * 255.0F), 0, 255);
                int b = leaves ? 128 : 255;
                return (255 << 24) | (b << 16) | (g << 8) | r;
            }
            BlockState state = level.getBlockState(pos);
            if (state.isAir()) {
                continue;
            }
            if (state.is(BlockTags.LEAVES) || state.is(com.yayuy.overgrownvegetation.ModTags.OVERGROWN_LEAVES)) {
                leaves = true;
                continue;
            }
            if (state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            return 0;
        }
        return 0;
    }

    /**
     * 空を描いた後・地形を描く前に({@link Atmosphere} から)、地図と網目の値を地形のシェーダー(固い・草・葉の 3 つ)へ渡す。
     * 地図が無い間は強さ 0(シェーダーは地図を読まない)。
     */
    static void upload(RenderLevelStageEvent event, boolean enabled) {
        enabled = enabled && VisualQuality.underwater();
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()};
        try {
            Minecraft mc = Minecraft.getInstance();
            Map m = broken || !enabled ? null : shown;
            AtmosphereWater.CausticsParams p = DesignData.atmosphere().caustics();
            float partialTick = event.getPartialTick();
            boolean submerged = event.getCamera().getFluidInCamera() == FogType.WATER;
            float strength = 0.0F;
            Vec3 sun = null;
            if (m != null && mc.level != null) {
                sun = SunLight.sunDirection(mc.level, partialTick);
                float high = SunLight.smooth(Mth.clamp(((float) sun.y - 0.05F) / 0.3F, 0.0F, 1.0F));
                strength = sun.y <= 0.0 ? 0.0F : p.strength() * SunLight.daylight(mc.level, sun, partialTick) * high * (submerged ? 1.0F : p.fromAbove());
            }
            lastStrength = strength;
            if (sun == null || strength <= 0.001F) {
                for (ShaderInstance shader : shaders) {
                    if (shader != null) {
                        shader.safeGetUniform("OvCaustic").set(0.0F, 0.0F, 0.0F, 0.0F);
                        shader.safeGetUniform("OvCausticD").set(0.0F, 0.0F, 0.0F, 0.0F);
                    }
                }
                return;
            }
            if (texture == null) {
                texture = new DynamicTexture(SIZE, SIZE, false);
            }
            if (uploadPending) {
                NativeImage image = texture.getPixels();
                if (image != null) {
                    for (int j = 0; j < SIZE; j++) {
                        for (int i = 0; i < SIZE; i++) {
                            image.setPixelRGBA(i, j, m.pixels[j * SIZE + i]);
                        }
                    }
                    texture.upload();
                }
                uploadPending = false;
            }
            float[] k = refracted(sun.y > 0.0 ? sun : new Vec3(0.0, 1.0, 0.0));
            Vec3 cam = event.getCamera().getPosition();
            float seconds = (float) ((Atmosphere.time(partialTick) / 20.0) % 600.0);
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvWaterMap", texture.getId());
                shader.safeGetUniform("OvCaustic").set((float) wrap1024(cam.x), (float) wrap1024(cam.z), seconds, strength);
                shader.safeGetUniform("OvCausticB").set(k[0] / k[1], k[2] / k[1], p.falloff(), p.maxDepth());
                shader.safeGetUniform("OvCausticMap").set((float) (m.originX - cam.x), (float) (m.originZ - cam.z), (float) SIZE,
                        (float) (m.baseY - cam.y));
                shader.safeGetUniform("OvCausticC").set(p.r(), p.g(), p.b(), p.scale());
                shader.safeGetUniform("OvCausticD").set(p.underLeaves(), p.width(), (float) p.cycles(), 0.0F);
            }
        } catch (RuntimeException e) {
            broken = true;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvCaustic").set(0.0F, 0.0F, 0.0F, 0.0F);
                    shader.safeGetUniform("OvCausticD").set(0.0F, 0.0F, 0.0F, 0.0F);
                }
            }
            OvergrownVegetation.LOGGER.error("網目の光で例外が出たので、このセッションでは使わない", e);
        }
    }

    /** 水の中から見た太陽の向き(屈折率 1.33。水平の成分が縮んで真上へ寄る)。単位ベクトル {x, y, z}。 */
    static float[] refracted(Vec3 sun) {
        double h = Math.sqrt(sun.x * sun.x + sun.z * sun.z);
        if (h < 1.0E-4) {
            return new float[]{0.0F, 1.0F, 0.0F};
        }
        double len = Math.sqrt(h * h + sun.y * sun.y);
        double sinWater = h / len / 1.33;
        double cosWater = Math.sqrt(1.0 - sinWater * sinWater);
        return new float[]{(float) (sun.x / h * sinWater), (float) cosWater, (float) (sun.z / h * sinWater)};
    }

    private static double wrap1024(double value) {
        return value - Math.floor(value / 1024.0) * 1024.0;
    }

    private WaterCaustics() {
    }
}

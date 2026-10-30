package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

/**
 * <b>林冠の影を全部の面に</b>と<b>森の中の青緑</b>(一覧 3 + 4。2026-10-16 風の案 C、10-30 作り直し。元は parked の {@code client/CanopySunMap})。
 * 地形のシェーダー({@code shaders/include/ov_light.glsl})に<b>太陽の当たり方の小さな地図</b>を渡し、地面・草・シダ・幹・葉のどの面にも同じ影を落とす。
 *
 * <h2>太陽の向きに沿った地図</h2>
 * 太陽の方向に沿った 1 本の線の上の点は、どれも同じ (u, v) = (x − y·sun.x/sun.y, z − y·sun.z/sun.y) を持つ。地図の 1 マスは 1 本の線
 * ({@link #SIZE}×{@link #SIZE} マス = カメラの周り ±48 ブロック)で、線をカメラの {@link #BELOW} 下から {@link #ABOVE} 上まで 0.5 ブロック刻みでたどり、
 * いちばん高い固いブロックの上端・いちばん高い葉の上端・葉の層の数・過繁茂の地域か(外は影を落とさない)を 1 画素に書く。
 * 地図は裏のスレッド({@link ScanThread})で 3.2 秒ごとに作り直し、地図ごとにその時の太陽の向きを持つ。
 *
 * <h2>影の色</h2>
 * 全部影のときの明るさは 75%、逆光に慣れきると 58%({@link SunLight#backlight})。色は灰色から青緑(比 0.80 : 1.00 : 1.06、明るさはそろえる)へ
 * {@code shadeTint} の強さで寄せる(2026-10-16 ユーザー選択「影を青緑に寄せ、筋と胞子の金色を浮かせる」)。
 * <b>森の中はいつも青緑</b>(同「日光が差す場所以外でも、過繁茂の森の中にいるときは常に」): 地図が日向・影を知っている所では影だけを、
 * 知らない所(地図の外・太陽の低い朝夕・夜)では全部の面を、森の中にいる度合い × {@code forestTint} で寄せる。松明の明かりで照らされた面は寄せない。
 *
 * <h2>出し入れ</h2>
 * 影の強さは {@code surfaceShade} × 昼の強さ × 森の中で空の下にいる度合い({@link ForestPresence#openForest})、青緑は森の中にいる度合いで、
 * どちらも森の縁でなめらかに出入りする。森の外(度合い 0)では地図を作らない。Sodium 0.8 には {@link SodiumTerrain} が同じ uniform を写す。
 * Sodium が使えない・Embeddium・シェーダーパック使用中は {@link #active()} が false で、地面の上面だけに落とす({@link CanopyShade})。
 *
 * <h2>草・シダ・幹の斑点</h2>
 * 葉が 1〜3 層だけの所(固いブロックは無い)の下では、地面の上面以外の面に小さな日向の斑点が抜ける(地面の上面は {@link CanopyDapple})。
 * 模様は太陽の向きの座標のなめらかな雑音で、枝の揺れ({@link Wind})で模様ごと行き来し、1 粒ずつはシェーダーの中で明滅する。
 */
public final class CanopySunMap {

    private static final int SIZE = 96;
    private static final int BELOW = 24;
    private static final int ABOVE = 72;
    /** 作り直しの間隔(tick)。 */
    private static final int REBUILD_TICKS = 64;
    /** これより太陽が低いと影を落とさない(線が長くなりすぎる)。 */
    private static final float MIN_SUN_Y = 0.2F;
    /** 斑点の模様の細かさ(シェーダーの {@code uvRel * 1.7} と同じ値)。 */
    private static final double FLECK_SCALE = 1.7;

    private static final class Map {
        final int originU;
        final int originV;
        final int baseY;
        final int topY;
        final double sunX;
        final double sunY;
        final double sunZ;
        final int camY;
        final int[] pixels = new int[SIZE * SIZE];

        Map(int originU, int originV, int baseY, int topY, Vec3 sun, int camY) {
            this.originU = originU;
            this.originV = originV;
            this.baseY = baseY;
            this.topY = topY;
            this.sunX = sun.x;
            this.sunY = sun.y;
            this.sunZ = sun.z;
            this.camY = camY;
        }
    }

    @Nullable
    private static CompletableFuture<Map> building;
    private static long buildStartTick = -1_000_000L;
    private static volatile int buildFailures;
    @Nullable
    private static Map shown;
    private static boolean uploadPending;
    @Nullable
    private static DynamicTexture texture;
    private static boolean broken;
    private static final float[] RGB = new float[3];

    /** 地図を使って全部の面に影を落としているか(false なら {@link CanopyShade} が地面にだけ落とす)。 */
    static boolean active() {
        return !SodiumTerrain.unsupported() && !broken && shown != null;
    }

    /** 全部影のときの色(掛け算、RGB)を {@code out} へ。シェーダーの影と地面の影 {@link CanopyShade} の両方が使う。 */
    static void shadeColor(float[] out) {
        float level = shadeLevel();
        hue(out);
        out[0] *= level;
        out[1] *= level;
        out[2] *= level;
    }

    /** 全部影のときの明るさ(灰色の掛け算。逆光で 75% → 58%)。 */
    private static float shadeLevel() {
        var t = DesignData.atmosphere();
        return Mth.lerp(SunLight.backlight(), t.shade(), t.shadeBacklit());
    }

    /**
     * 影の色合い(明るさ 1 の掛け算)。<b>時刻で少し動かす</b>(提案 4、10-30 ユーザー採用): 昼は青緑(了承済みの比)、太陽が地平線に近い夕方・朝は紫寄り、
     * 夜は月光の銀青。太陽の高さ 0.05〜0.4 で夕方 → 昼、地平線の下 0〜0.2 で夕方 → 夜へ寄せる(仮決め)。強さは {@code shadeTint}。
     */
    private static void hue(float[] out) {
        var t = DesignData.atmosphere();
        float tint = t.shadeTint();
        float sunY = sunHeight();
        float[] dusk = t.duskTint();
        // 昼の影の色の比(A2、10-30 夜 ユーザー採用: 青緑から緑寄りへ。atmosphere.json の light.shadeHue、見本で決める)。
        float[] day = t.shadeHue();
        float r = day[0];
        float g = day[1];
        float b = day[2];
        if (sunY >= 0.0F) {
            float w = SunLight.smooth(Mth.clamp((sunY - 0.05F) / 0.35F, 0.0F, 1.0F));
            r = Mth.lerp(w, dusk[0], r);
            g = Mth.lerp(w, dusk[1], g);
            b = Mth.lerp(w, dusk[2], b);
        } else {
            float[] night = t.nightTint();
            float w = nightWeight();
            r = Mth.lerp(w, dusk[0], night[0]);
            g = Mth.lerp(w, dusk[1], night[1]);
            b = Mth.lerp(w, dusk[2], night[2]);
        }
        out[0] = Mth.lerp(tint, 1.0F, r);
        out[1] = Mth.lerp(tint, 1.0F, g);
        out[2] = Mth.lerp(tint, 1.0F, b);
    }

    /** 今の太陽の高さ(向きの y。ワールドの外は昼)。 */
    private static float sunHeight() {
        ClientLevel level = Minecraft.getInstance().level;
        return level == null ? 1.0F : (float) SunLight.sunDirection(level, 1.0F).y;
    }

    /** 夜の度合い(太陽が地平線の下 0〜0.2 で 0 → 1)。夜の青緑(銀青)は {@code nightTintStrength} 倍に弱める(夜も影は付く)。 */
    private static float nightWeight() {
        float sunY = sunHeight();
        return sunY >= 0.0F ? 0.0F : SunLight.smooth(Mth.clamp(-sunY / 0.2F, 0.0F, 1.0F));
    }

    static void clear() {
        building = null;
        buildStartTick = -1_000_000L;
        shown = null;
    }

    /** 毎 tick({@link Atmosphere} から)。森の中・昼なら地図を裏のスレッドで作り直す。 */
    static void tick(ClientLevel level, long ticks) {
        if (SodiumTerrain.unsupported() || broken) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        if (DesignData.atmosphere().surfaceShade() <= 0.0F || sun.y < MIN_SUN_Y || ForestPresence.openForest() <= 0.001F) {
            clear();
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
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        double camU = eye.x - eye.y * sun.x / sun.y;
        double camV = eye.z - eye.y * sun.z / sun.y;
        int camY = Mth.floor(eye.y);
        Map m = new Map(Mth.floor(camU) - SIZE / 2, Mth.floor(camV) - SIZE / 2, camY - BELOW, camY + ABOVE, sun, camY);
        building = CompletableFuture.supplyAsync(() -> {
            for (int index = 0; index < SIZE * SIZE; index++) {
                m.pixels[index] = trace(level, m, index % SIZE, index / SIZE);
            }
            return m;
        }, ScanThread.EXECUTOR).exceptionally(t -> {
            if (++buildFailures >= 3) {
                broken = true;
                OvergrownVegetation.LOGGER.error("林冠の日向の地図を 3 回続けて作れなかったので、このセッションでは使わない(地面の影だけに戻す)", t);
            } else {
                OvergrownVegetation.LOGGER.warn("林冠の日向の地図を作れなかった(次の回にもう一度): {}", t.toString());
            }
            return null;
        });
    }

    /** 地図のマス (i, j) の線をたどって、画素(ABGR)を返す。r = 固いブロックの上端、g = 葉の上端、b = 樹冠の層 × 17、a = 過繁茂なら 255・外なら 128。 */
    private static int trace(ClientLevel level, Map m, int i, int j) {
        double u = m.originU + i + 0.5;
        double v = m.originV + j + 0.5;
        double kx = m.sunX / m.sunY;
        double kz = m.sunZ / m.sunY;
        BlockPos at = BlockPos.containing(u + m.camY * kx, m.camY, v + m.camY * kz);
        boolean overgrown = ModBiomes.isOvergrownLike(level.getBiome(at));
        double y = m.baseY;
        double x = u + y * kx;
        double z = v + y * kz;
        double dx = m.sunX * 0.5;
        double dy = m.sunY * 0.5;
        double dz = m.sunZ * 0.5;
        int solidTop = 0;
        int leafTop = 0;
        int crowns = 0;
        int run = 0;
        double lastLeafY = Double.NEGATIVE_INFINITY;
        double gapY = SunTrace.CROWN_GAP * m.sunY;
        long last = Long.MIN_VALUE;
        long lastColumn = Long.MIN_VALUE;
        int columnTop = 0;
        long lastSection = Long.MIN_VALUE;
        boolean sectionAir = false;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (y < m.topY) {
            x += dx;
            y += dy;
            z += dz;
            int bx = Mth.floor(x);
            int by = Mth.floor(y);
            int bz = Mth.floor(z);
            long column = ((long) bx << 32) | (bz & 0xFFFFFFFFL);
            if (column != lastColumn) {
                lastColumn = column;
                columnTop = level.getHeight(Heightmap.Types.WORLD_SURFACE, bx, bz);
            }
            if (by >= columnTop) {
                continue;
            }
            long here = BlockPos.asLong(bx, by, bz);
            if (here == last) {
                continue;
            }
            last = here;
            long section = SectionPos.asLong(bx >> 4, by >> 4, bz >> 4);
            if (section != lastSection) {
                lastSection = section;
                sectionAir = SunTrace.sectionIsAir(level, bx, by, bz);
            }
            if (sectionAir) {
                continue;
            }
            BlockState state = level.getBlockState(pos.set(bx, by, bz));
            if (state.isAir() || state.getCollisionShape(level, pos).isEmpty()) {
                continue;
            }
            int top = Mth.clamp(by + 1 - m.baseY, 0, 255);
            if (state.canOcclude()) {
                solidTop = Math.max(solidTop, top);
            } else {
                leafTop = Math.max(leafTop, top);
                if (run > 0 && y - lastLeafY > gapY) {
                    crowns += SunTrace.crownWeight(run);
                    run = 0;
                }
                run++;
                lastLeafY = y;
            }
        }
        if (run > 0) {
            crowns += SunTrace.crownWeight(run);
        }
        int b = Math.min(crowns, 15) * 17;
        int a = overgrown ? 255 : 128;
        return (a << 24) | (b << 16) | (leafTop << 8) | solidTop;
    }

    /** 空を描いた後・地形を描く前に({@link Atmosphere} から)、地図と太陽の向きを地形のシェーダー(固い・草・葉の 3 つ)へ渡す。描かない間は強さ 0 を渡す。 */
    /**
     * 環境の胞子({@link AmbientSpores})が日向かを見るための、今のフレームの地図の受け渡し(10-30 夜 提案 3)。
     * {@code sporeTexture} は地図のテクスチャ(使えないとき −1)、{@code SPORE_SUN_A}・{@code SPORE_MAP_A} は地形のシェーダーの OvSunA・OvMapA と同じ値。
     */
    static int sporeTexture = -1;
    static final float[] SPORE_SUN_A = new float[4];
    static final float[] SPORE_MAP_A = new float[4];

    static void upload(RenderLevelStageEvent event, boolean enabled) {
        sporeTexture = -1;
        ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader()};
        try {
            Minecraft mc = Minecraft.getInstance();
            Map m = broken || !enabled ? null : shown;
            float amount = 0.0F;
            float mapDay = 0.0F;
            Vec3 sun = null;
            float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
            if (m != null && mc.level != null) {
                sun = SunLight.sunDirection(mc.level, partialTick);
                mapDay = SunLight.daylight(mc.level, sun, partialTick);
                amount = DesignData.atmosphere().surfaceShade() * mapDay * ForestPresence.openForest();
            }
            hue(RGB);
            float forest = enabled && mc.level != null ? ForestPresence.forestFactor() * DesignData.atmosphere().forestTint()
                    * Mth.lerp(nightWeight(), 1.0F, DesignData.atmosphere().nightTintStrength()) : 0.0F;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvTint").set(RGB[0], RGB[1], RGB[2], forest);
                    // 日なたの暖かい白〜金(A4、10-30 夜 ユーザー採用)。日の当たる面と木漏れ日の斑点に掛ける(森の中だけ)。
                    float[] warm = DesignData.atmosphere().sunWarm();
                    shader.safeGetUniform("OvSunTint").set(warm[0], warm[1], warm[2], DesignData.atmosphere().sunWarmStrength() * (forest > 0.0F ? 1.0F : 0.0F));
                }
            }
            if (amount <= 0.0F || sun == null) {
                for (ShaderInstance shader : shaders) {
                    if (shader != null) {
                        shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                        shader.safeGetUniform("OvFleck").set(0.0F, 0.0F, 0.0F, 0.0F);
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
            Vec3 cam = event.getCamera().getPosition();
            double kx = m.sunX / m.sunY;
            double kz = m.sunZ / m.sunY;
            double camU = cam.x - cam.y * kx;
            double camV = cam.z - cam.y * kz;
            // 斑点の模様は太陽の向きの座標で世界に固定する(カメラの (u, v) を 1.7 倍して 64 で折り返して渡す。模様は 64 ごとに繰り返す)。
            // 枝の揺れで模様ごと風の向きへ行き来し、1 粒ずつはシェーダーの中で明滅する。
            Wind wind = Wind.current();
            double windTime = Atmosphere.time(partialTick);
            double gust = Math.min(1.0, wind.strength(cam.x, cam.z, windTime));
            double branch = (0.02 + 0.22 * gust) * Wind.branch(windTime, 0.0);
            double fleckU = wrap64((camU + branch * wind.dirX()) * FLECK_SCALE);
            double fleckV = wrap64((camV + branch * wind.dirZ()) * FLECK_SCALE);
            float seconds = (float) ((windTime / 20.0) % 1000.0);
            float level = shadeLevel();
            sporeTexture = texture.getId();
            SPORE_SUN_A[0] = (float) kx;
            SPORE_SUN_A[1] = (float) kz;
            SPORE_SUN_A[2] = amount;
            SPORE_SUN_A[3] = mapDay;
            SPORE_MAP_A[0] = (float) (m.originU - camU);
            SPORE_MAP_A[1] = (float) (m.originV - camV);
            SPORE_MAP_A[2] = (float) SIZE;
            SPORE_MAP_A[3] = (float) (m.baseY - cam.y);
            for (ShaderInstance shader : shaders) {
                if (shader == null) {
                    continue;
                }
                shader.setSampler("OvSunMap", texture.getId());
                // w = 地図が日向・影を決める度合い(昼の強さ)。z = 影の強さ。
                shader.safeGetUniform("OvSunA").set((float) kx, (float) kz, amount, mapDay);
                shader.safeGetUniform("OvSunDir").set((float) sun.x, (float) sun.y, (float) sun.z, 1.0F);
                shader.safeGetUniform("OvMapA").set((float) (m.originU - camU), (float) (m.originV - camV), (float) SIZE,
                        (float) (m.baseY - cam.y));
                shader.safeGetUniform("OvShadeColor").set(level, level, level, 1.0F);
                shader.safeGetUniform("OvFleck").set((float) fleckU, (float) fleckV, seconds, CanopyDapple.FLECKS_ON ? 1.0F : 0.0F);
            }
        } catch (RuntimeException e) {
            broken = true;
            sporeTexture = -1;
            for (ShaderInstance shader : shaders) {
                if (shader != null) {
                    shader.safeGetUniform("OvSunA").set(0.0F, 0.0F, 0.0F, 0.0F);
                    shader.safeGetUniform("OvTint").set(1.0F, 1.0F, 1.0F, 0.0F);
                }
            }
            OvergrownVegetation.LOGGER.error("林冠の影の地図で例外が出たので、このセッションでは使わない(地面の影だけに戻す)", e);
        }
    }

    private static double wrap64(double value) {
        return value - Math.floor(value / 64.0) * 64.0;
    }

    private CanopySunMap() {
    }
}

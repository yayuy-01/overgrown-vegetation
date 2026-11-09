package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.platform.NativeImage;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.shader.SodiumTerrain;
import com.yayuy.overgrownvegetation.client.sky.CloudRenderers;
import com.yayuy.overgrownvegetation.client.sky.CloudThread;
import com.yayuy.overgrownvegetation.client.sky.VoxelClouds;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.sky.CloudField;
import com.yayuy.overgrownvegetation.sky.CloudSky;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * 流れる雲の影(2026-10-17。2026-11-08 雲の段階 1 で parked の {@code client/CloudShadows} から戻し、束ね役 {@link Atmosphere} につないだ)。
 *
 * <ul>
 *   <li>影の地図: {@link CloudField} の柱を 1 升 1 画素で写した {@link #SIZE} × {@link #SIZE} の絵(場の座標、8 ブロック升で 1280 ブロック四方)。
 *       画素の値 = その柱の影の濃さ(薄い雲ほど淡い)。地図の真ん中は「カメラから太陽の向きにたどって雲の高さに着く点」。場は風で流れるので、
 *       地図も描くときの平行移動で流れ、作り直すのは真ん中が {@link #REBUILD_CELLS} 升ずれたときと量が変わったときだけ(別スレッド)。
 *       雲の形は描き方(ボクセル)に依らず場から引く(描き方を替えても影は同じ)。</li>
 *   <li>地形のシェーダー({@code include/ov_cloud.glsl}。solid・cutout・cutout_mipped と Sodium の不透明の層)が、面の点から太陽の向きに雲の高さ
 *       {@link #SHADOW_Y} までたどった点で地図を引き、空の光が届く面を青みがかった灰色へ暗くする。林冠の影の中はもう暗いので重ねない、
 *       <b>洞窟の地図で地下と分かる面には落とさない</b>({@code ovCaveUnder}。天井の穴から空の光が入る所でも)。</li>
 *   <li>カメラのあたりに日が差しているか({@link #sunVisibility()}): 雲が太陽を隠すと、林冠の光の筋・木漏れ日が弱まる(読み手は
 *       {@link LightShafts}・{@link CanopyDapple})。地下にいる間は 1(雲と関係ない)。</li>
 * </ul>
 * 昼だけ(太陽の高さ 7〜18° で入れる)。雨は光の強さで弱まる。雲を描いていないとき(設定でオフ・バニラの雲の設定がオフ・シェーダーパック)は
 * 出さない(見えている雲と影が食い違うので)。数値はすべて仮決め。強さは {@code sky.json shadows.strength}。
 */
public final class CloudShadows {
    /** 地図の大きさ(画素 = 雲の柱)。 */
    public static final int SIZE = 160;
    /** 影を落とす高さ(雲の底 300 の少し上)。 */
    public static final float SHADOW_Y = CloudField.LOW_BASE + 8.0F;
    /** 地図の真ん中がこれだけ升ずれたら作り直す。 */
    static final int REBUILD_CELLS = 8;
    /** 地図の縁で影を消していく幅(ブロック)。 */
    static final float EDGE = 32.0F;
    /** カメラが雲の影に入ったとき、光の筋・木漏れ日などを何割まで弱めるか。 */
    public static final float SUN_BLOCK = 0.85F;

    /** 影の地図(薄明光線 {@link CrepuscularRays} も雲の切れ目を探すのに読む。作った後は書き換えない)。 */
    static final class Map {
        int originX;
        int originZ;
        float coverage;
        int style;
        float[] values;
        long seed = CloudField.seed();
        com.yayuy.overgrownvegetation.sky.CloudShape shape = com.yayuy.overgrownvegetation.sky.CloudShape.current();
    }

    private static Map shown;
    private static CompletableFuture<Map> pending;
    private static DynamicTexture texture;
    private static boolean uploadPending;
    private static boolean broken;
    private static ClientLevel lastLevel;
    private static float visibility = 1.0F;
    private static float lastBase;
    /** 前触れの帯を地図に書いた tick と、書いてあるか。 */
    private static long omenTick = Long.MIN_VALUE;
    private static boolean omenShown;

    static Map current() {
        return shown;
    }

    /** 太陽の高さと昼の強さ(設定を掛ける前)。地図があるのはこれが 0 より大きい間だけ。 */
    static float base() {
        return lastBase;
    }

    /** カメラのあたりに日が差しているか(1 = 雲に隠れていない 〜 1 − {@link #SUN_BLOCK} = 厚い雲の影)。光の筋・木漏れ日が掛ける。 */
    public static float sunVisibility() {
        return visibility;
    }

    static void clear() {
        shown = null;
        pending = null;
        visibility = 1.0F;
        lastBase = 0.0F;
    }

    /**
     * 雲の影の地図を作る光の向き。昼は太陽、夜は G5 光る胞子の夜({@link GlowNight})の間だけ月(太陽の反対。11-08)。
     */
    static Vec3 lightDirection(ClientLevel level, float partialTick) {
        Vec3 sun = SunLight.sunDirection(level, partialTick);
        return sun.y < 0.0 && GlowNight.amount() > 0.001F ? sun.scale(-1.0) : sun;
    }

    /** dir = {@link #lightDirection}。昼は太陽の高さ × 昼の強さ、夜(月)は月の高さだけ(光る胞子の夜の度合いは読み手が掛ける)。 */
    private static float base(ClientLevel level, Vec3 dir, float partialTick) {
        if (!CloudRenderers.current().active()) {
            return 0.0F;
        }
        float low = SunLight.smooth(Mth.clamp(((float) dir.y - 0.12F) / 0.19F, 0.0F, 1.0F));
        if (SunLight.sunDirection(level, partialTick).y < 0.0) {
            return GlowNight.amount() > 0.001F ? low : 0.0F;
        }
        return low * SunLight.daylight(level, dir, partialTick);
    }

    /** 地形に落とす影の濃さの設定。夜(月の影)は G5 の moonShadow × 光る胞子の夜の度合い。 */
    private static float shadowSetting(ClientLevel level, float partialTick) {
        if (SunLight.sunDirection(level, partialTick).y < 0.0) {
            return DesignData.sky().shadows * DesignData.atmosphere().glowNight().moonShadow() * GlowNight.amount();
        }
        return DesignData.sky().shadows;
    }

    /** 毎 tick(呼び手は {@link Atmosphere})。カメラの所の日の差し方をならす。地下にいる間は 1 へ。 */
    static void tick(ClientLevel level, Vec3 cam) {
        if (broken) {
            return;
        }
        float target = 1.0F;
        Map m = shown;
        Vec3 sun = SunLight.sunDirection(level, 1.0F);
        float s = base(level, sun, 1.0F) * shadowSetting(level, 1.0F) * (1.0F - ForestPresence.underground());
        if (m != null && s > 0.0F && sun.y > 0.0) {
            target = 1.0F - SUN_BLOCK * Math.min(1.0F, s * 1.5F) * sample(m, level, cam.x, cam.y, cam.z, sun, 1.0F);
        }
        // 雲の縁はもともと 8 ブロックかけてぼけるので、ここは tick ごとの段だけをならす。
        visibility += (target - visibility) * 0.15F;
    }

    /** 地図を CPU で引く(シェーダーと同じ式・同じ補間)。 */
    private static float sample(Map m, ClientLevel level, double x, double y, double z, Vec3 sun, float partialTick) {
        double kx = sun.x / sun.y;
        double kz = sun.z / sun.y;
        long gameTime = level.getGameTime();
        double qx = CloudField.fieldX(x + kx * (SHADOW_Y - y), gameTime, partialTick) / CloudField.CELL_XZ - m.originX - 0.5;
        double qz = CloudField.fieldZ(z + kz * (SHADOW_Y - y), gameTime, partialTick) / CloudField.CELL_XZ - m.originZ - 0.5;
        int i = Mth.floor(qx);
        int j = Mth.floor(qz);
        if (i < 0 || j < 0 || i >= SIZE - 1 || j >= SIZE - 1) {
            return 0.0F;
        }
        float fx = (float) (qx - i);
        float fz = (float) (qz - j);
        float a = m.values[j * SIZE + i];
        float b = m.values[j * SIZE + i + 1];
        float c = m.values[(j + 1) * SIZE + i];
        float d = m.values[(j + 1) * SIZE + i + 1];
        float cloud = Mth.lerp(fz, Mth.lerp(fx, a, b), Mth.lerp(fx, c, d));
        // G4 胞子の風の前触れの影(地図に足した分と同じ。日差しも陰る)。
        return Math.max(cloud, SporeGale.omenAt(x, z, Atmosphere.time(partialTick)));
    }

    private static ShaderInstance[] shaders() {
        // 11-08 改善 ⑨: 水面・ガラス(translucent)と登る蔓(liana_canopy_lit)にも。
        return new ShaderInstance[]{GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                GameRenderer.getRendertypeCutoutMippedShader(), GameRenderer.getRendertypeTranslucentShader(),
                com.yayuy.overgrownvegetation.client.shader.ModShaders.lianaCanopyLit()};
    }

    /** 空を描いた後・地形の前(呼び手は {@link Atmosphere})。地形のシェーダーへ影の地図と値を渡す。on = 雰囲気の演出を描くか。 */
    static void upload(RenderLevelStageEvent event, boolean on) {
        ShaderInstance[] shaders = shaders();
        if (broken || !on || SodiumTerrain.unsupported()) {
            off(shaders);
            lastBase = 0.0F;
            visibility = 1.0F;
            return;
        }
        try {
            if (!apply(event, shaders)) {
                off(shaders);
            }
        } catch (RuntimeException e) {
            broken = true;
            visibility = 1.0F;
            off(shaders);
            OvergrownVegetation.LOGGER.error("雲の影で例外が出たので、このセッションでは使わない", e);
        }
    }

    private static void off(ShaderInstance[] shaders) {
        for (ShaderInstance shader : shaders) {
            if (shader != null) {
                shader.safeGetUniform("OvCloudA").set(0.0F, 0.0F, 0.0F, 0.0F);
            }
        }
    }

    private static boolean apply(RenderLevelStageEvent event, ShaderInstance[] shaders) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        if (level != lastLevel) {
            lastLevel = level;
            clear();
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 sun = lightDirection(level, partialTick);
        float base = base(level, sun, partialTick);
        lastBase = 0.0F;
        float strength = base * shadowSetting(level, partialTick);
        float coverage = VoxelClouds.coverage(level, partialTick);
        if (base <= 0.001F || coverage <= 0.0F || strength <= 0.0F && DesignData.sky().rays <= 0.0F) {
            return false;
        }
        Vec3 cam = event.getCamera().getPosition();
        long gameTime = level.getGameTime();
        double kx = sun.x / sun.y;
        double kz = sun.z / sun.y;
        double px = CloudField.fieldX(cam.x + kx * (SHADOW_Y - cam.y), gameTime, partialTick);
        double pz = CloudField.fieldZ(cam.z + kz * (SHADOW_Y - cam.y), gameTime, partialTick);
        int ox = Mth.floor(px / CloudField.CELL_XZ) - SIZE / 2;
        int oz = Mth.floor(pz / CloudField.CELL_XZ) - SIZE / 2;
        int style = CloudSky.style();

        if (pending != null && pending.isDone()) {
            shown = pending.join();
            pending = null;
            uploadPending = true;
        }
        boolean moved = shown == null || Math.abs(ox - shown.originX) > REBUILD_CELLS || Math.abs(oz - shown.originZ) > REBUILD_CELLS
                || Math.abs(coverage - shown.coverage) > VoxelClouds.REBUILD_COVERAGE || shown.style != style || shown.seed != CloudField.seed()
                || !shown.shape.equals(com.yayuy.overgrownvegetation.sky.CloudShape.current());
        if (pending == null && moved) {
            final float c = coverage;
            pending = CompletableFuture.supplyAsync(() -> build(ox, oz, c, style), CloudThread.EXECUTOR);
        }
        Map m = shown;
        if (m == null) {
            return false;
        }
        lastBase = base;
        if (texture == null) {
            texture = new DynamicTexture(SIZE, SIZE, false);
            uploadPending = true;
        }
        // G4 胞子の風の前触れ(11-08): 走る影の帯を地図に足す。帯が出ている間は tick ごとに書き直す(帯は地図の升より速く動くので)。
        boolean omen = SporeGale.omenActive();
        if (omen && gameTime != omenTick || !omen && omenShown) {
            uploadPending = true;
        }
        if (uploadPending) {
            NativeImage image = texture.getPixels();
            if (image != null) {
                double windX0 = CloudField.offsetX((double) gameTime + partialTick);
                double windZ0 = CloudField.offsetZ((double) gameTime + partialTick);
                double t = Atmosphere.time(partialTick);
                double lift = SHADOW_Y - cam.y;
                for (int j = 0; j < SIZE; j++) {
                    for (int i = 0; i < SIZE; i++) {
                        float value = m.values[j * SIZE + i];
                        if (omen) {
                            // 升の真ん中(雲の高さ・世界)から光の向きに地面(カメラの高さ)へ戻した点の帯の濃さ。
                            double gx = (m.originX + i + 0.5) * CloudField.CELL_XZ + windX0 - kx * lift;
                            double gz = (m.originZ + j + 0.5) * CloudField.CELL_XZ + windZ0 - kz * lift;
                            value = Math.max(value, SporeGale.omenAt(gx, gz, t));
                        }
                        int v = Math.round(value * 255.0F);
                        image.setPixelRGBA(i, j, 0xFF000000 | v);
                    }
                }
                texture.bind();
                // なめらかな補間(影の縁をぼかす)・縁の外は繰り返さない。
                image.upload(0, 0, 0, 0, 0, SIZE, SIZE, true, true, false, false);
            }
            uploadPending = false;
            omenTick = gameTime;
            omenShown = omen;
        }
        // 地図の原点(場の座標)を世界へ戻し(風で流れた分を足す)、カメラからの位置にする。
        double windX = CloudField.offsetX((double) gameTime + partialTick);
        double windZ = CloudField.offsetZ((double) gameTime + partialTick);
        float originX = (float) (m.originX * (double) CloudField.CELL_XZ + windX - cam.x);
        float originZ = (float) (m.originZ * (double) CloudField.CELL_XZ + windZ - cam.z);
        float span = SIZE * CloudField.CELL_XZ;
        for (ShaderInstance shader : shaders) {
            if (shader == null) {
                continue;
            }
            shader.setSampler("OvCloudMap", texture.getId());
            shader.safeGetUniform("OvCloudA").set((float) kx, (float) kz, (float) (SHADOW_Y - cam.y), strength);
            shader.safeGetUniform("OvCloudB").set(originX, originZ, span, EDGE);
            shader.safeGetUniform("OvCloudC").set((float) sun.x, (float) sun.y, (float) sun.z, 0.0F);
        }
        return true;
    }

    /** 場から影の地図を作る(別スレッド)。値 = 柱の影の濃さ(厚さ 4 で 0.55 〜 厚さ 24 以上で 1)。段階 1 は下の層だけ(積乱雲の影は段階 2)。 */
    private static Map build(int originX, int originZ, float coverage, int style) {
        Map m = new Map();
        m.originX = originX;
        m.originZ = originZ;
        m.coverage = coverage;
        m.style = style;
        m.values = new float[SIZE * SIZE];
        float threshold = CloudField.threshold(coverage);
        for (int j = 0; j < SIZE; j++) {
            for (int i = 0; i < SIZE; i++) {
                CloudField.Column c = CloudField.column(originX + i, originZ + j, threshold, coverage, List.of(), style);
                if (c.present()) {
                    m.values[j * SIZE + i] = Mth.clamp(0.45F + (c.top() - c.bottom()) / 40.0F, 0.0F, 1.0F);
                }
            }
        }
        return m;
    }

    public static String describe() {
        return String.format("雲の影 地図 %s 昼 %.2f 日差し %.2f 薄明光線 %d 本", shown != null, lastBase, visibility, CrepuscularRays.count());
    }

    private CloudShadows() {
    }
}

package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * 極相の密林の演出(2026-10-16 ユーザー指定「極相は別のセッションで提案してもらった 8 案を全て実装」「とりあえず密林のみ」「結晶地帯も入れる」。
 * 見せ方はユーザー決定の橋渡し案「光は差すが、差した光が吸われていくのが見える(空は明るい・林床は薄明・明るさは地脈や結晶が吐き返す光)」、
 * 密林 = 光を食う大聖堂(藍と青緑))。極相の密林(結晶地帯・川・海を含む)に居る度合いは {@link CorruptionAtmosphere#jungleClimax}。
 *
 * <ul>
 *     <li>(2) <b>下からの光</b>: 地形のシェーダーが、下を向いた面(林冠の裏・枝の下)を青緑に、横を向いた面を少しだけ照らす({@code ovClimaxUplight})。</li>
 *     <li>(3) <b>時間の狂い</b>: 真昼でも林冠の穴から星が見える(星の明るさの下限を上げる)。太陽のまわりに大きな霞の暈をかぶせて、霞んだ円に見せる。</li>
 *     <li>(4) <b>宙に浮くもの</b>: 地脈のまわりで、光る胞子・葉・小石がゆっくり浮き上がって漂い、また沈む(あふれたエネルギー)。
 *     葉・小石は転がる小さな立方体(10-16 ユーザー指摘で板から変えた。板はチリ紙に見えた)。</li>
 *     <li>(7) ガラスのような葉は作ったが、ユーザー判断でやめた(2026-10-16「葉を光らせるのはやめましょう。雰囲気が微妙になってしまいました」)。</li>
 *     <li>(8) <b>雨が林床に届かない</b>: 雨の間、林冠の下では雨の代わりに光る滴がまばらに落ちる(雨そのものはバニラのとおり林冠で止まる)。</li>
 * </ul>
 * (1) 光の吸い込みは {@link CanopyLight}、(5) 結晶の共鳴と (6) 網の目(地脈を流れる光)は {@link LeyNightGlow}。昼の暗さ・空と霧の色は
 * {@code climax.json} の daylightBrightness とバイオームの json。数値は全部 Claude の仮決め。設定 {@code climaxJungleEffects}。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME, value = Dist.CLIENT)
public final class ClimaxJungle {

    /** 下からの光の色(面の色に掛けて足す)。 */
    static final float UP_R = 0.05F;
    static final float UP_G = 0.16F;
    static final float UP_B = 0.15F;
    /** 真昼の星の明るさ(0〜1)。 */
    static final float DAY_STARS = 0.85F;
    /** 浮くものの数の上限・範囲(ブロック)。 */
    static final int MAX_FLOATERS = 70;
    static final double FLOAT_RANGE = 24.0;
    /** 光る滴の数の上限。 */
    static final int MAX_DROPS = 90;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Floater> FLOATERS = new ArrayList<>();
    private static final List<Drop> DROPS = new ArrayList<>();
    private static ClientLevel trackedLevel;
    private static long ticks;
    private static boolean broken;
    private static RenderType solidType;

    /** 浮くもの 1 つ(0 = 光る胞子・1 = 草・葉のかけら・2 = 小石)。1・2 は小さな立方体で、{@code axis} のまわりに転がる。 */
    private static final class Floater {
        final double x;
        final double baseY;
        final double z;
        final int kind;
        final int life;
        final float rise;
        /** 光る胞子は光の大きさ、立方体は半分の辺の長さ(ブロック)。 */
        final float size;
        final float phase;
        final Vector3f axis;
        /** 色の揺らぎ(0〜1。2 つの色の間のどこか)。 */
        final float tint;
        int age;

        Floater(double x, double baseY, double z, int kind, int life, float rise, float size, float phase, Vector3f axis, float tint) {
            this.x = x;
            this.baseY = baseY;
            this.z = z;
            this.kind = kind;
            this.life = life;
            this.rise = rise;
            this.size = size;
            this.phase = phase;
            this.axis = axis;
            this.tint = tint;
        }

        double y(double t) {
            double up = Mth.clamp(t / 60.0, 0.0, 1.0);
            double down = Mth.clamp((this.life - t) / 60.0, 0.0, 1.0);
            double h = this.rise * CanopyLight.smooth((float) Math.min(up, down));
            return this.baseY + h + 0.12 * Math.sin(t * 0.04 + this.phase);
        }
    }

    /** 林冠の下に落ちる光る滴。 */
    private static final class Drop {
        double x;
        double y;
        double z;
        final double floor;
        int age;

        Drop(double x, double y, double z, double floor) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.floor = floor;
        }
    }

    /** 極相の密林の演出の強さ(0〜1)。設定で切れる。 */
    static float strength() {
        if (!OvergrownVegetationConfig.COMMON.climaxJungleEffects.get()) {
            return 0.0F;
        }
        return CorruptionAtmosphere.jungleClimax();
    }

    /**
     * (8) 雨が林床に届かない: 極相の密林で林冠の下(カメラの上に葉・木がある)にいる間は、バニラの雨の筋を描かない(光る滴だけが落ちる。
     * 雨音・林冠の上のしぶきはバニラのまま)。{@code LevelRendererClimaxRainMixin} が読む。
     */
    public static boolean hideRain() {
        Minecraft mc = Minecraft.getInstance();
        if (strength() < 0.5F || mc.level == null) {
            return false;
        }
        Vec3 eye = mc.gameRenderer.getMainCamera().getPosition();
        int top = mc.level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(eye.x), Mth.floor(eye.z));
        return top > eye.y + 2.0;
    }

    /** 真昼の星(星の明るさの下限)。{@code ClientLevelDayStarsMixin} が読む。 */
    public static float dayStars() {
        return DAY_STARS * strength();
    }

    // ------------------------------------------------------------------ tick

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level != trackedLevel) {
            FLOATERS.clear();
            DROPS.clear();
            trackedLevel = level;
        }
        if (level == null || player == null || mc.isPaused()) {
            return;
        }
        ticks++;
        float s = strength();
        // (4) 宙に浮くもの: 地脈のまわりから少しずつ浮かせる。
        for (Iterator<Floater> it = FLOATERS.iterator(); it.hasNext(); ) {
            Floater f = it.next();
            if (++f.age >= f.life || player.distanceToSqr(f.x, f.baseY, f.z) > (FLOAT_RANGE + 8) * (FLOAT_RANGE + 8)) {
                it.remove();
            }
        }
        for (int n = 0; n < 2 && s > 0.3F && FLOATERS.size() < MAX_FLOATERS && RANDOM.nextFloat() < 0.6F * s; n++) {
            long vein = LeyNightGlow.randomVeinNear(player.getX(), player.getZ(), FLOAT_RANGE, RANDOM);
            if (vein != Long.MIN_VALUE) {
                double x = BlockPos.getX(vein) + 0.5 + (RANDOM.nextDouble() - 0.5) * 3.0;
                double z = BlockPos.getZ(vein) + 0.5 + (RANDOM.nextDouble() - 0.5) * 3.0;
                double y = BlockPos.getY(vein) + 1.05;
                float roll = RANDOM.nextFloat();
                int kind = roll < 0.6F ? 0 : (roll < 0.85F ? 1 : 2);
                float size = kind == 0 ? 0.05F + RANDOM.nextFloat() * 0.05F
                        : kind == 1 ? 0.035F + RANDOM.nextFloat() * 0.03F : 0.045F + RANDOM.nextFloat() * 0.04F;
                Vector3f axis = new Vector3f(RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F, RANDOM.nextFloat() - 0.5F);
                if (axis.lengthSquared() < 1.0E-4F) {
                    axis.set(0.0F, 1.0F, 0.0F);
                }
                FLOATERS.add(new Floater(x, y, z, kind, 240 + RANDOM.nextInt(260), 0.6F + RANDOM.nextFloat() * 1.8F,
                        size, RANDOM.nextFloat() * Mth.TWO_PI, axis.normalize(), RANDOM.nextFloat()));
            }
        }
        // (8) 雨が林床に届かない: 林冠の下に光る滴。
        for (Iterator<Drop> it = DROPS.iterator(); it.hasNext(); ) {
            Drop d = it.next();
            d.age++;
            d.y -= 0.22;
            if (d.y <= d.floor || d.age > 200) {
                it.remove();
            }
        }
        float rain = level.getRainLevel(1.0F);
        if (s > 0.3F && rain > 0.2F && DROPS.size() < MAX_DROPS) {
            for (int i = 0; i < 3; i++) {
                double x = player.getX() + (RANDOM.nextDouble() - 0.5) * 24.0;
                double z = player.getZ() + (RANDOM.nextDouble() - 0.5) * 24.0;
                int bx = Mth.floor(x);
                int bz = Mth.floor(z);
                int top = level.getHeight(Heightmap.Types.MOTION_BLOCKING, bx, bz);
                float ground = CanopyLight.groundAt(level, bx, bz, Mth.floor(player.getY()));
                // 林冠(地面より上の葉)の下だけ。雨そのものが届く開けた所には足さない。
                if (Float.isNaN(ground) || top - ground < 3.0F || RANDOM.nextFloat() > rain * s) {
                    continue;
                }
                double start = Math.min(top - 1.0, player.getY() + 10.0);
                DROPS.add(new Drop(x, start, z, ground));
            }
        }
    }

    // ------------------------------------------------------------------ シェーダーの値と空

    /** 空を描いた後・地形を描く前: 下からの光の値を地形のシェーダーへ。 */
    @SubscribeEvent
    public static void onRenderSky(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_SKY || broken) {
            return;
        }
        try {
            Minecraft mc = Minecraft.getInstance();
            ClientLevel level = mc.level;
            float s = level == null ? 0.0F : strength();
            if (!SodiumTerrain.unsupported()) {
                ShaderInstance[] shaders = {GameRenderer.getRendertypeSolidShader(), GameRenderer.getRendertypeCutoutShader(),
                        GameRenderer.getRendertypeCutoutMippedShader()};
                for (ShaderInstance shader : shaders) {
                    if (shader == null) {
                        continue;
                    }
                    // 下からの光は夜も(地脈・結晶の光なので)。
                    shader.safeGetUniform("OvClimax").set(UP_R, UP_G, UP_B, s);
                }
            }
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("極相の密林の演出で例外が出たので、このセッションでは使わない", e);
        }
    }

    /** (3) 太陽を霞んだ円に: 太陽の向きに大きなぼやけた光の暈を重ねる(加算)。 */
    private static void sunHaze(RenderLevelStageEvent event, ClientLevel level, float s, float partialTick) {
        Vec3 sun = CanopyLight.sunDirection(level, partialTick);
        if (sun.y < -0.05) {
            return;
        }
        Camera camera = event.getCamera();
        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer c = buffers.getBuffer(CanopyLight.renderType());
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        float d = 100.0F;
        float x = (float) sun.x * d;
        float y = (float) sun.y * d;
        float z = (float) sun.z * d;
        float a = 0.35F * s * Mth.clamp((float) (sun.y + 0.05) / 0.2F, 0.0F, 1.0F);
        CanopyLight.mote(c, x, y, z, 34.0F, left, up, a, 0.95F, 0.97F, 1.0F);
        CanopyLight.mote(c, x, y, z, 14.0F, left, up, a * 0.8F, 1.0F, 1.0F, 0.96F);
        buffers.endBatch(CanopyLight.renderType());
    }

    // ------------------------------------------------------------------ 浮くもの・滴

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || broken) {
            return;
        }
        boolean main = CanopyLight.drawToMainTarget();  // ファビュラスでは加算の光を粒の層でなく主画面へ(CanopyLight#drawToMainTarget)
        try {
            ClientLevel level = Minecraft.getInstance().level;
            float s = level == null ? 0.0F : strength();
            if (s > 0.02F && event.getCamera().getFluidInCamera() == FogType.NONE) {
                // 地形の後に描く(太陽の向き 100 ブロック先の暈は、近い地形には隠れ、霧に沈んだ遠くの上には重なる = 光る霧)。
                sunHaze(event, level, s, event.getPartialTick().getGameTimeDeltaPartialTick(false));
            }
            if (FLOATERS.isEmpty() && DROPS.isEmpty()) {
                return;
            }
            render(event);
        } catch (RuntimeException e) {
            broken = true;
            OvergrownVegetation.LOGGER.error("極相の密林の浮くもの・滴の描画で例外が出たので、このセッションでは描かない", e);
        } finally {
            CanopyLight.restoreTarget(main);
        }
    }

    private static void render(RenderLevelStageEvent event) {
        Camera camera = event.getCamera();
        if (camera.getFluidInCamera() != FogType.NONE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        Vec3 eye = camera.getPosition();
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float s = strength();
        Vector3f left = camera.getLeftVector();
        Vector3f up = camera.getUpVector();
        MultiBufferSource.BufferSource buffers = CanopyLight.immediateBuffers();  // Iris のまとめ描きを避ける(CanopyLight#immediateBuffers、2026-10-22)
        // 光る胞子と滴(加算)。
        VertexConsumer glow = buffers.getBuffer(CanopyLight.renderType());
        for (Floater f : FLOATERS) {
            if (f.kind != 0) {
                continue;
            }
            double t = f.age + partialTick;
            float fade = (float) Math.min(1.0, Math.min(t / 40.0, (f.life - t) / 40.0));
            float twinkle = 0.75F + 0.25F * (float) Math.sin(t * 0.2 + f.phase);
            CanopyLight.mote(glow, (float) (f.x - eye.x), (float) (f.y(t) - eye.y), (float) (f.z - eye.z), f.size, left, up,
                    0.8F * fade * twinkle * s, 0.45F, 1.0F, 0.85F);
        }
        for (Drop d : DROPS) {
            float fade = Math.min(1.0F, d.age / 10.0F);
            double y = d.y - 0.22 * partialTick;
            CanopyLight.mote(glow, (float) (d.x - eye.x), (float) (y - eye.y), (float) (d.z - eye.z), 0.035F, left, up,
                    0.7F * fade * s, 0.5F, 0.95F, 1.0F);
            CanopyLight.mote(glow, (float) (d.x - eye.x), (float) (y + 0.18 - eye.y), (float) (d.z - eye.z), 0.02F, left, up,
                    0.25F * fade * s, 0.5F, 0.95F, 1.0F);
        }
        buffers.endBatch(CanopyLight.renderType());
        // 草・葉のかけらと小石: 転がりながら浮く小さな立方体(2026-10-16 ユーザー指摘「板なせいで石や葉ではなくチリ紙が浮いているように見える。
        // 小さなキューブが浮いている感じに」)。前は明かりも霧も掛からない単色の板で、暗い林床の中で白っぽく光って紙に見えた。
        // 面ごとにブロックと同じ向きの明暗を付け、その場所の明かり(ライトマップ)・霧・林冠の影の色を掛ける。
        VertexConsumer solid = buffers.getBuffer(solidType());
        float[] shade = {1.0F, 1.0F, 1.0F};
        if (CanopySunMap.active()) {
            CanopySunMap.shadeColor(shade);
        }
        for (Floater f : FLOATERS) {
            if (f.kind == 0) {
                continue;
            }
            double t = f.age + partialTick;
            double y = f.y(t);
            float fade = (float) Math.min(1.0, Math.min(t / 40.0, (f.life - t) / 40.0)) * s;
            float[] from = f.kind == 1 ? GRASS_A : STONE_A;
            float[] to = f.kind == 1 ? GRASS_B : STONE_B;
            int light = LevelRenderer.getLightColor(mc.level, BlockPos.containing(f.x, y, f.z));
            ROTATION.identity().rotateAxis((float) (t * 0.03 + f.phase), f.axis);
            cube(solid, (float) (f.x - eye.x), (float) (y - eye.y), (float) (f.z - eye.z), f.size, ROTATION,
                    Mth.lerp(f.tint, from[0], to[0]) * shade[0], Mth.lerp(f.tint, from[1], to[1]) * shade[1],
                    Mth.lerp(f.tint, from[2], to[2]) * shade[2], fade, light);
        }
        buffers.endBatch(solidType());
    }

    /** 草・葉のかけらの色(2 つの間で揺らす)と、小石の色(灰色と苔むした灰緑)。仮決め。 */
    private static final float[] GRASS_A = {0.17F, 0.30F, 0.12F};
    private static final float[] GRASS_B = {0.27F, 0.38F, 0.15F};
    private static final float[] STONE_A = {0.38F, 0.39F, 0.38F};
    private static final float[] STONE_B = {0.29F, 0.34F, 0.26F};
    /** 描画用の使い回し(描画のスレッドだけ)。 */
    private static final Quaternionf ROTATION = new Quaternionf();
    private static final Vector3f[] CORNERS = {new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f(),
            new Vector3f(), new Vector3f(), new Vector3f(), new Vector3f()};
    private static final Vector3f NORMAL = new Vector3f();
    /** 立方体の 6 面(角の番号は bit0 = x・bit1 = y・bit2 = z が +)。外から見て反時計回りにそろえる(裏は描かない)。 */
    private static final int[][] FACES = cubeFaces();

    private static int[][] cubeFaces() {
        int[][] faces = {{0, 2, 6, 4}, {1, 3, 7, 5}, {0, 1, 5, 4}, {2, 3, 7, 6}, {0, 1, 3, 2}, {4, 5, 7, 6}};
        for (int[] face : faces) {
            Vector3f a = corner(face[0]);
            Vector3f u = corner(face[1]).sub(a);
            Vector3f v = corner(face[2]).sub(a);
            Vector3f center = corner(face[0]).add(corner(face[1])).add(corner(face[2])).add(corner(face[3]));
            if (u.cross(v).dot(center) < 0.0F) {
                int swap = face[1];
                face[1] = face[3];
                face[3] = swap;
            }
        }
        return faces;
    }

    private static Vector3f corner(int i) {
        return new Vector3f((i & 1) != 0 ? 1.0F : -1.0F, (i & 2) != 0 ? 1.0F : -1.0F, (i & 4) != 0 ? 1.0F : -1.0F);
    }

    /** 中心 (x, y, z)・半分の辺 half の立方体を rot で回して描く。面の明るさはブロックに近い(上 1・横 0.7〜0.8・下 0.55)。 */
    private static void cube(VertexConsumer c, float x, float y, float z, float half, Quaternionf rot,
                             float r, float g, float b, float a, int light) {
        for (int i = 0; i < 8; i++) {
            rot.transform(CORNERS[i].set((i & 1) != 0 ? half : -half, (i & 2) != 0 ? half : -half, (i & 4) != 0 ? half : -half));
        }
        for (int[] face : FACES) {
            NORMAL.set(CORNERS[face[0]]).add(CORNERS[face[1]]).add(CORNERS[face[2]]).add(CORNERS[face[3]]).normalize();
            float lit = 0.55F + 0.45F * (0.5F + 0.5F * NORMAL.y) - 0.08F * Math.abs(NORMAL.x);
            for (int k : face) {
                Vector3f p = CORNERS[k];
                c.addVertex(x + p.x, y + p.y, z + p.z).setColor(r * lit, g * lit, b * lit, a).setLight(light);
            }
        }
    }

    /** 浮く立方体の描き方: ライトマップの明かりと霧が掛かるリードのシェーダー(頂点の色 × 明かり)、半透明(現れる・消えるときだけ)、裏は描かない。 */
    static RenderType solidType() {
        if (solidType == null) {
            solidType = RenderType.create(
                    OvergrownVegetation.MODID + "_climax_floaters",
                    DefaultVertexFormat.POSITION_COLOR_LIGHTMAP,
                    VertexFormat.Mode.QUADS,
                    4096,
                    false,
                    true,
                    RenderType.CompositeState.builder()
                            .setShaderState(RenderStateShard.RENDERTYPE_LEASH_SHADER)
                            .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                            .setLightmapState(RenderStateShard.LIGHTMAP)
                            .setCullState(RenderStateShard.CULL)
                            .createCompositeState(false));
        }
        return solidType;
    }

    private ClimaxJungle() {
    }
}

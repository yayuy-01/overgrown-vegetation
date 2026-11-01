package com.yayuy.overgrownvegetation.client.atmosphere;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.client.VisualQuality;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>幹を伝う水</b>(一覧 41。設定: 熱帯の雨は葉から幹へ集まって流れ落ちる = 樹幹流)。2026-11-01 に作り直した(側面・底面の段、ユーザー「もう一度」)。
 * <p>前の作り(10-31。11-01 に U12 で parked へ退避)は、テクスチャの無い色だけの細い帯を CPU で蛇行させ、明るい粒を流し、道を世代で入れ替えていた。
 * 一晩で 4 回直したのち削除になった(理由は記録に無い)。今度は<b>絵を描いて流すだけ</b>にした:
 * <ul>
 *     <li>下絵の水の筋({@code textures/entity/trunk_water_streak.png}、6×32・半透明)を、幹の面に貼る細い板に縦に繰り返し、V をずらして下へ流す
 *     (1 画素 = 1/16 ブロックで地形と同じ細かさ。蛇行と滴は絵の中にある)。</li>
 *     <li>雨の間と雨上がり(濡れの時計 {@link MistFog#wetAfter}。照り {@link WetSheen} と同じ節 {@code wet} の分。止んで 3 分で消える)。</li>
 *     <li>幹 1 本に {@link #MIN_PER_TREE}〜{@link #MAX_PER_TREE} 筋、空気に面した側面に、面から {@link #LIFT} 浮かせる。</li>
 * </ul>
 * 値はこのクラスの定数(仮決め。design json には戻していない)。設定画面の「水面の演出」({@code waterLife})で一緒に切れる。
 */
public final class TrunkRivulets {

    private static final ResourceLocation TEXTURE = OvergrownVegetation.id("textures/entity/trunk_water_streak.png");
    /** 幹を探す半径(ブロック)。 */
    private static final int RADIUS = 16;
    /** 筋を付ける幹の数の上限。 */
    private static final int MAX_TREES = 40;
    private static final int MIN_PER_TREE = 1;
    private static final int MAX_PER_TREE = 3;
    /** 筋の板の幅(絵の 6 画素 = 6/16 ブロック)と、絵 1 枚の高さ(32 画素 = 2 ブロック)。 */
    private static final float WIDTH = 6.0F / 16.0F;
    private static final float TILE = 2.0F;
    /** 面から浮かせる量(1/32 ブロック)。 */
    private static final float LIFT = 1.0F / 32.0F;
    /** 流れる速さ(ブロック/秒)。10-31 ユーザー「速すぎる」の後の 0.45 の前後。 */
    private static final float SPEED_MIN = 0.35F;
    private static final float SPEED_MAX = 0.5F;
    /** 上下の端で薄れる長さ(ブロック)。 */
    private static final float FADE = 0.5F;
    /** 筋の長さの上限(根元から何段まで。目に入る幹の下の方。11-01 の確かめで「幹の上から 10 段」だと目線より上にしか出なかったので根元から)。 */
    private static final int MAX_LENGTH = 12;
    /** 1 tick に幹を探す列の数(3 では雨の 10 秒で 0〜5 筋しか見つからなかった)。 */
    private static final int TRIES_PER_TICK = 12;

    private static final RandomSource RANDOM = RandomSource.create();
    private static final List<Streak> STREAKS = new ArrayList<>();
    private static final Direction[] SIDES = {Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST};
    private static float wet;

    /** 1 筋。原木の列 (x, z)・面の向き・面の中の位置(-0.5〜0.5)・上と下の端・速さ・絵の位相。 */
    private record Streak(int x, int z, Direction side, float along, double top, double bottom, float speed, float phase) {
    }

    static void clear() {
        STREAKS.clear();
        wet = 0.0F;
    }

    static String describe() {
        return String.format("幹の水 %.2f 筋 %d", wet, STREAKS.size());
    }

    static void tick(ClientLevel level, LocalPlayer player) {
        if (!VisualQuality.waterLife()) {
            clear();
            return;
        }
        wet = MistFog.wetAfter(DesignData.atmosphere().wet().minutes(), level.getRainLevel(1.0F));
        double r2 = (double) RADIUS * RADIUS;
        STREAKS.removeIf(s -> {
            double dx = s.x + 0.5 - player.getX();
            double dz = s.z + 0.5 - player.getZ();
            return dx * dx + dz * dz > r2 * 1.5;
        });
        if (wet <= 0.01F) {
            STREAKS.clear();
            return;
        }
        if (countTrees() >= MAX_TREES) {
            return;
        }
        // 1 tick に TRIES_PER_TICK 列ずつ幹を探す。
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int tries = 0; tries < TRIES_PER_TICK; tries++) {
            int x = Mth.floor(player.getX() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            int z = Mth.floor(player.getZ() + (RANDOM.nextDouble() * 2.0 - 1.0) * RADIUS);
            if (!level.hasChunkAt(pos.set(x, Mth.floor(player.getY()), z)) || hasTree(x, z)) {
                continue;
            }
            int y0 = Mth.floor(player.getY()) + 12;
            int top = Integer.MIN_VALUE;
            int bottom = Integer.MIN_VALUE;
            for (int y = y0; y > y0 - 24; y--) {
                boolean log = level.getBlockState(pos.set(x, y, z)).is(BlockTags.LOGS);
                if (log && top == Integer.MIN_VALUE) {
                    top = y;
                }
                if (!log && top != Integer.MIN_VALUE) {
                    bottom = y + 1;
                    break;
                }
            }
            if (top == Integer.MIN_VALUE || bottom == Integer.MIN_VALUE || top - bottom < 2
                    || !ModBiomes.isOvergrownLike(level.getBiome(pos.set(x, bottom, z)))) {
                continue;
            }
            double upper = Math.min(top + 1.0, bottom + MAX_LENGTH);
            int n = MIN_PER_TREE + RANDOM.nextInt(MAX_PER_TREE - MIN_PER_TREE + 1);
            for (int k = 0; k < n; k++) {
                Direction side = SIDES[RANDOM.nextInt(4)];
                // 空気に面した側だけ(隣も幹・下草の板でも塞がっていれば見えない)。根元の段で見る。
                if (level.getBlockState(pos.set(x + side.getStepX(), bottom, z + side.getStepZ())).blocksMotion()) {
                    continue;
                }
                float along = (RANDOM.nextFloat() - 0.5F) * (1.0F - WIDTH - 0.1F);
                STREAKS.add(new Streak(x, z, side, along, upper - RANDOM.nextFloat() * 0.5, bottom,
                        Mth.lerp(RANDOM.nextFloat(), SPEED_MIN, SPEED_MAX), RANDOM.nextFloat()));
            }
        }
    }

    private static int countTrees() {
        int n = 0;
        int lastX = Integer.MIN_VALUE;
        int lastZ = Integer.MIN_VALUE;
        for (Streak s : STREAKS) {
            if (s.x != lastX || s.z != lastZ) {
                n++;
                lastX = s.x;
                lastZ = s.z;
            }
        }
        return n;
    }

    private static boolean hasTree(int x, int z) {
        for (Streak s : STREAKS) {
            if (s.x == x && s.z == z) {
                return true;
            }
        }
        return false;
    }

    static void render(RenderLevelStageEvent event) {
        if (wet <= 0.01F || STREAKS.isEmpty() || !VisualQuality.waterLife()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        double seconds = Atmosphere.time(partialTick) / 20.0;
        Vec3 cam = event.getCamera().getPosition();
        float skyDarken = level.getSkyDarken(partialTick);
        BufferBuilder builder = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX_COLOR);
        BlockPos.MutableBlockPos lightPos = new BlockPos.MutableBlockPos();
        for (Streak s : STREAKS) {
            float nx = s.side.getStepX();
            float nz = s.side.getStepZ();
            // 面の上の点: 原木の中心から面まで 0.5 + 浮かせ。面に沿う向きは法線を 90° 回したもの。
            double fx = s.x + 0.5 + nx * (0.5 + LIFT);
            double fz = s.z + 0.5 + nz * (0.5 + LIFT);
            float tx = -nz;
            float tz = nx;
            double cx = fx + tx * s.along;
            double cz = fz + tz * s.along;
            lightPos.set(s.x + s.side.getStepX(), Mth.floor((s.top + s.bottom) * 0.5), s.z + s.side.getStepZ());
            float sky = level.getBrightness(LightLayer.SKY, lightPos) / 15.0F * skyDarken;
            float block = level.getBrightness(LightLayer.BLOCK, lightPos) / 15.0F;
            float shade = Math.max(0.12F, Math.max(sky, block));
            // V は -高さ / TILE(絵の上が上)。時間で V を引くと絵が下へ流れる。
            float scroll = (float) ((seconds * s.speed / TILE + s.phase) % 1.0);
            double fadeTop = s.top - FADE;
            double fadeBottom = s.bottom + FADE;
            // 上の端の薄れ・真ん中・下の端の薄れの 3 枚
            quad(builder, cam, cx, cz, tx, tz, s.top, fadeTop, 0.0F, 1.0F, scroll, shade);
            quad(builder, cam, cx, cz, tx, tz, fadeTop, fadeBottom, 1.0F, 1.0F, scroll, shade);
            quad(builder, cam, cx, cz, tx, tz, fadeBottom, s.bottom, 1.0F, 0.0F, scroll, shade);
        }
        MeshData mesh = builder.build();
        if (mesh == null) {
            return;
        }
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShaderTexture(0, TEXTURE);
        ShaderInstance shader = GameRenderer.getPositionTexColorShader();
        if (shader == null) {
            mesh.close();
        } else {
            shader.setDefaultUniforms(VertexFormat.Mode.QUADS, new Matrix4f(event.getModelViewMatrix()), new Matrix4f(event.getProjectionMatrix()),
                    mc.getWindow());
            shader.apply();
            BufferUploader.draw(mesh);
            shader.clear();
        }
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.disableBlend();
    }

    /** 高さ hi → lo の板 1 枚。透明度は上 aHi・下 aLo に濡れと霞を掛ける。 */
    private static void quad(BufferBuilder b, Vec3 cam, double cx, double cz, float tx, float tz, double hi, double lo, float aHi, float aLo,
                             float scroll, float shade) {
        if (hi <= lo) {
            return;
        }
        float half = WIDTH / 2.0F;
        float vHi = (float) (-hi / TILE) - scroll;
        float vLo = (float) (-lo / TILE) - scroll;
        vertex(b, cam, cx - tx * half, hi, cz - tz * half, 0.0F, vHi, shade, aHi);
        vertex(b, cam, cx + tx * half, hi, cz + tz * half, 1.0F, vHi, shade, aHi);
        vertex(b, cam, cx + tx * half, lo, cz + tz * half, 1.0F, vLo, shade, aLo);
        vertex(b, cam, cx - tx * half, lo, cz - tz * half, 0.0F, vLo, shade, aLo);
    }

    private static void vertex(BufferBuilder b, Vec3 cam, double x, double y, double z, float u, float v, float shade, float a) {
        float dx = (float) (x - cam.x);
        float dy = (float) (y - cam.y);
        float dz = (float) (z - cam.z);
        // この描き方は霧を受けないので、森の霞の分だけ薄める(LifeSprites と同じ)。
        float alpha = a * wet * (1.0F - MistView.fogAmount(Mth.sqrt(dx * dx + dy * dy + dz * dz)));
        b.addVertex(dx, dy, dz).setUv(u, v).setColor(shade, shade, shade, alpha);
    }

    private TrunkRivulets() {
    }
}

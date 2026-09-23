package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.HiveVineEntity;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

/**
 * 「巣の蔦」({@link HiveVineEntity})の描画。エンティティの位置ではなく、同期されてきた
 * <b>ノード側の端と殻側の端</b>の 2 点を結ぶ垂れ下がった曲線をリボンとして直接描く。
 *
 * <p>描き方は {@link CorruptedLianaRenderer}(木と木を結ぶ蔦)と同じ手
 * ——{@code POSITION_COLOR_TEX_LIGHTMAP} + テキスト用シェーダーの独自 {@link RenderType} に、
 * 曲線を {@value #SEGMENTS} 分割し、1 区間ごとに四角い角柱(4 面)を積む。太さは
 * {@code hiveNodeVineThickness} 1 でほぼ 1 ブロック分({@value #BASE_WIDTH})なので、
 * 見た目はブロックで組んだ蔦に近い。エンティティ自身の当たり判定は
 * 曲線の中点にしか無いので、{@link HiveVineEntity#getBoundingBoxForCulling()} が曲線全体を覆う箱を
 * 返して視錐台カリングで消えないようにしている。
 *
 * <p>曲線は「始点・終点・(エンティティ位置 = 曲線の中点)」から二次ベジエを復元したもの。
 * たわみの量を別途同期しなくても、中点さえ分かれば同じ曲線が描ける。
 */
public class HiveVineRenderer extends EntityRenderer<HiveVineEntity> {

    /** 曲線の分割数。 */
    private static final int SEGMENTS = 20;
    /** テクスチャがワールド内で何ブロックごとに縦へ 1 回繰り返すか。 */
    private static final float TILE_LENGTH = 1.0F;
    /** {@code hiveNodeVineThickness} 1 あたりの太さ(ブロック)。1 でほぼ 1 ブロック分。 */
    private static final float BASE_WIDTH = 0.9F;

    private static final ResourceLocation TEXTURE =
            OvergrownVegetation.id("textures/entity/overgrown_liana_vine.png");

    private static final RenderType VINE_TYPE = RenderType.create(
            "overgrown_vegetation_hive_vine",
            DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP,
            VertexFormat.Mode.QUADS,
            256,
            false,
            false,
            RenderType.CompositeState.builder()
                    .setShaderState(RenderStateShard.RENDERTYPE_TEXT_SHADER)
                    .setTextureState(new RenderStateShard.TextureStateShard(TEXTURE, false, false))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setLightmapState(RenderStateShard.LIGHTMAP)
                    .setCullState(RenderStateShard.NO_CULL)
                    .createCompositeState(false)
    );

    public HiveVineRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.shadowRadius = 0.0F;
    }

    @Override
    public ResourceLocation getTextureLocation(HiveVineEntity entity) {
        return TEXTURE;
    }

    @Override
    public void render(HiveVineEntity entity, float yaw, float partialTick, PoseStack pose,
                       MultiBufferSource buffers, int packedLight) {
        Vec3 start = entity.getOrigin();
        Vec3 end = entity.getAnchor();
        if (start.lengthSqr() == 0.0D || end.lengthSqr() == 0.0D) {
            return;
        }
        // エンティティ自身の座標(曲線の中点)を通るように二次ベジエの制御点を復元する。
        Vec3 through = new Vec3(entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ());
        Vec3 control = through.scale(2.0D).subtract(start.add(end).scale(0.5D));

        // 描画はエンティティの位置を原点とした相対座標で行う。
        Vec3 origin = new Vec3(entity.getX(), entity.getY(), entity.getZ());
        // 太さ。1 でほぼ 1 ブロック分(四角い角柱として描くので、見た目もブロックの蔦に近い)。
        float width = BASE_WIDTH * Math.max(1, OvergrownVegetationConfig.COMMON.hiveNodeVineThickness.get());
        float half = width * 0.5F;

        VertexConsumer buffer = buffers.getBuffer(VINE_TYPE);
        Matrix4f matrix = pose.last().pose();

        float length = (float) start.distanceTo(end);
        // 断面の 4 隅(前の分割点ぶん)。x/y/z を順に詰めた 12 要素。
        float[] prev = new float[12];
        int prevLight = packedLight;
        float prevV = 0.0F;

        for (int i = 0; i <= SEGMENTS; i++) {
            float t = (float) i / SEGMENTS;
            float it = 1.0F - t;
            double px = it * it * start.x + 2 * it * t * control.x + t * t * end.x;
            double py = it * it * start.y + 2 * it * t * control.y + t * t * end.y;
            double pz = it * it * start.z + 2 * it * t * control.z + t * t * end.z;

            float x = (float) (px - origin.x);
            float y = (float) (py - origin.y);
            float z = (float) (pz - origin.z);
            int light = lightAt(entity, px, py, pz, packedLight);
            float v = t * length / TILE_LENGTH;

            // 断面の基底: 進行方向に直交する水平ベクトルと、鉛直方向。
            float dx, dz;
            if (i < SEGMENTS) {
                float t2 = (float) (i + 1) / SEGMENTS;
                float it2 = 1.0F - t2;
                dx = (float) ((it2 * it2 * start.x + 2 * it2 * t2 * control.x + t2 * t2 * end.x) - px);
                dz = (float) ((it2 * it2 * start.z + 2 * it2 * t2 * control.z + t2 * t2 * end.z) - pz);
            } else {
                dx = (float) (end.x - start.x);
                dz = (float) (end.z - start.z);
            }
            float len = Mth.sqrt(dx * dx + dz * dz);
            float hx = len > 1.0e-4F ? -dz / len * half : half;
            float hz = len > 1.0e-4F ? dx / len * half : 0.0F;

            // 角柱の 4 隅(水平方向 ±half、鉛直方向 ±half)。
            float[] ring = {
                x + hx, y + half, z + hz,
                x + hx, y - half, z + hz,
                x - hx, y - half, z - hz,
                x - hx, y + half, z - hz
            };

            if (i > 0) {
                for (int f = 0; f < 4; f++) {
                    int a = f * 3;
                    int b = ((f + 1) % 4) * 3;
                    addQuad(buffer, matrix,
                            prev[a], prev[a + 1], prev[a + 2], 0.0F, prevV, prevLight,
                            prev[b], prev[b + 1], prev[b + 2], 1.0F, prevV, prevLight,
                            ring[b], ring[b + 1], ring[b + 2], 1.0F, v, light,
                            ring[a], ring[a + 1], ring[a + 2], 0.0F, v, light);
                }
            }
            System.arraycopy(ring, 0, prev, 0, 12);
            prevLight = light;
            prevV = v;
        }

        super.render(entity, yaw, partialTick, pose, buffers, packedLight);
    }

    /** 曲線上の 1 点の明るさ。広間の中は真っ暗なので、点ごとに拾っても差はほぼ出ないが正確に。 */
    private static int lightAt(HiveVineEntity entity, double x, double y, double z, int fallback) {
        if (entity.level() == null) {
            return fallback;
        }
        BlockPos pos = BlockPos.containing(x, y, z);
        if (!entity.level().hasChunkAt(pos)) {
            return fallback;
        }
        int block = entity.level().getBrightness(LightLayer.BLOCK, pos);
        int sky = entity.level().getBrightness(LightLayer.SKY, pos);
        return net.minecraft.client.renderer.LightTexture.pack(block, sky);
    }

    private static void addQuad(VertexConsumer buffer, Matrix4f pose,
            float x0, float y0, float z0, float u0, float v0, int light0,
            float x1, float y1, float z1, float u1, float v1, int light1,
            float x2, float y2, float z2, float u2, float v2, int light2,
            float x3, float y3, float z3, float u3, float v3, int light3) {
        buffer.addVertex(pose, x0, y0, z0).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u0, v0).setLight(light0);
        buffer.addVertex(pose, x1, y1, z1).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u1, v1).setLight(light1);
        buffer.addVertex(pose, x2, y2, z2).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u2, v2).setLight(light2);
        buffer.addVertex(pose, x3, y3, z3).setColor(1.0F, 1.0F, 1.0F, 1.0F).setUv(u3, v3).setLight(light3);
    }
}

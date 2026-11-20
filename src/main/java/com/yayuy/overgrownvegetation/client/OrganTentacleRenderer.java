package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.organ.OrganBlocks;
import com.yayuy.overgrownvegetation.organ.OrganEntities;
import com.yayuy.overgrownvegetation.organ.OrganTentacle;
import com.yayuy.overgrownvegetation.organ.OrganTissueBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import org.joml.Quaternionf;

/**
 * 器官の蔦の触手の描き方(2026-11-20 作り直し。ユーザー「太い蔦の鞭にしたい」)。{@link OrganTentacle#point} の節に沿って、
 * 器官のブロックの箱を節の向きへ回して並べる(根元が太く先へ細る)。形の式(当たりも同じ)は触手の側のまま、ここは見た目だけ。
 *
 * <p>見た目の案({@link #style}。0 が既定):
 * <ul>
 *   <li>0 太い蔦の鞭: 根の肋の胴・根元に硬い殻の襟・先に髄の瘤(叩く所)。</li>
 *   <li>1 棘の鞭: 0 の胴に硬い殻の棘。先は尖る。</li>
 *   <li>2 蔓の束: 絡む根の細い蔓 3 本をねじり、先で 3 本にほどける。</li>
 *   <li>3 ぜんまいの鞭: 0 の胴で、先がぜんまいのように巻く(見た目だけ。叩くときにほどく動きは未実装)。</li>
 * </ul>
 * 開発用のクライアント({@code -Dov.devbridge=true})では {@code devbridge/tentacle_style.txt}(0〜3 か mix)で切り替えて見比べる。
 */
public class OrganTentacleRenderer extends EntityRenderer<OrganTentacle> {

    /** 既定の見た目。 */
    private static final int DEFAULT_STYLE = 0;
    private static final boolean DEV = "true".equals(System.getProperty("ov.devbridge"));
    private static int devStyle = DEFAULT_STYLE;
    private static long devReadAt;

    public OrganTentacleRenderer(EntityRendererProvider.Context ctx) {
        super(ctx);
    }

    private static int style(OrganTentacle e) {
        if (!DEV) return DEFAULT_STYLE;
        long now = System.currentTimeMillis();
        if (now - devReadAt > 1500L) {
            devReadAt = now;
            try {
                java.nio.file.Path p = java.nio.file.Path.of("devbridge", "tentacle_style.txt");
                String s = java.nio.file.Files.exists(p) ? java.nio.file.Files.readString(p).trim() : "";
                devStyle = s.equals("mix") ? -1 : s.isEmpty() ? DEFAULT_STYLE : Integer.parseInt(s);
            } catch (Exception ex) {
                devStyle = DEFAULT_STYLE;
            }
        }
        return devStyle < 0 ? Math.floorMod(e.getId(), 4) : devStyle;
    }

    private static BlockState state(DeferredBlock<Block> b, boolean limp) {
        return b.get().defaultBlockState().setValue(OrganTissueBlock.WITHERED, limp);
    }

    @Override
    public void render(OrganTentacle e, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        BlockRenderDispatcher br = Minecraft.getInstance().getBlockRenderer();
        boolean limp = e.limp();
        int st = style(e);
        float t = e.swayTime() + partialTick; // サーバーと同じゲーム時刻で揺らす(11-18 見直し ②7)
        int n = e.segments();
        Vec3[] pts = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) pts[i] = e.point(i, t);
        if (st == 3 && !limp) coil(pts, n);
        BlockState body = state(st == 2 ? OrganBlocks.TANGLED_ROOT : OrganBlocks.ORGAN_RIB, limp);
        BlockState shell = state(OrganBlocks.ORGAN_SHELL, limp), pith = state(OrganBlocks.ORGAN_PITH, limp), stalk = state(OrganBlocks.ORGAN_STALK, limp);
        BlockPos.MutableBlockPos lp = new BlockPos.MutableBlockPos();
        double thick = Math.min(2.2D, 1.0D + e.length() * 0.07D); // 根元の太さ(長い触手ほど太い)
        for (int i = 0; i <= n; i++) {
            Vec3 p = pts[i];
            Vec3 a = pts[Math.max(0, i - 1)], b = pts[Math.min(n, i + 1)];
            Vec3 d = b.subtract(a);
            double dl = d.length();
            if (dl < 1.0E-5D) continue;
            d = d.scale(1.0D / dl);
            double s = i / (double) Math.max(1, n);
            float w = (float) (thick * (1.0D - 0.6D * Math.pow(s, 0.9D)));
            float len = (float) (dl * 0.5D * 1.5D);
            lp.set(e.getX() + p.x, e.getY() + p.y, e.getZ() + p.z);
            int segLight = Math.max(LevelRenderer.getLightColor(e.level(), lp), i == 0 ? light : 0);
            // 節の向きに垂直な 2 本(棘・蔓の束の位置に使う)
            Vec3 ref = Math.abs(d.y) > 0.9D ? new Vec3(1, 0, 0) : new Vec3(0, 1, 0);
            Vec3 u = d.cross(ref).normalize(), v = d.cross(u);
            if (st == 2) {
                // 蔓の束: 3 本をねじる。先の 2 割でほどける
                double spread = w * 0.34D + Math.max(0.0D, s - 0.8D) * 4.0D;
                for (int k = 0; k < 3; k++) {
                    double ang = i * 0.42D + k * (Math.PI * 2.0D / 3.0D);
                    Vec3 q = p.add(u.scale(Math.cos(ang) * spread)).add(v.scale(Math.sin(ang) * spread));
                    box(br, pose, buffers, body, q, d, Math.max(0.16F, w * 0.52F), len, segLight);
                }
                continue;
            }
            BlockState seg = body;
            float ww = w;
            if (s < 0.07D) { // 根元の襟
                seg = shell;
                ww = w * 1.22F;
            } else if (st == 3 && s > 0.72D) {
                seg = stalk;
            } else if ((st == 0 || st == 3) && i >= n - 2 && !(st == 3)) {
                seg = pith; // 先の瘤(叩く所)
                ww = Math.max(w * 1.9F, 0.55F);
            }
            box(br, pose, buffers, seg, p, d, ww, len, segLight);
            if (st == 3 && i == n) box(br, pose, buffers, pith, p, d, 0.5F, 0.5F, segLight); // ぜんまいの芯
            if (st == 1 && i % 3 == 1 && s > 0.1D) {
                // 棘: 胴の外へ、互い違いに
                double ang = i * 1.3D;
                Vec3 dir = u.scale(Math.cos(ang)).add(v.scale(Math.sin(ang)));
                float tw = Math.max(0.16F, w * 0.34F);
                box(br, pose, buffers, shell, p.add(dir.scale(w * 0.5D + tw * 0.9D)), dir, tw, tw * 2.6F, segLight);
            }
        }
        super.render(e, yaw, partialTick, pose, buffers, light);
    }

    /** 先の 3 割を、ぜんまいのように内へ巻く(見た目だけ)。 */
    private static void coil(Vec3[] pts, int n) {
        int c0 = (int) (n * 0.7D);
        if (c0 < 1 || c0 >= n) return;
        Vec3 d0 = pts[c0].subtract(pts[c0 - 1]).normalize();
        Vec3 up = new Vec3(0, 1, 0);
        Vec3 w = up.subtract(d0.scale(up.dot(d0)));
        if (w.lengthSqr() < 1.0E-4D) w = new Vec3(1, 0, 0);
        w = w.normalize().scale(-1.0D); // 下へ巻き込む
        double total = pts[n].subtract(pts[c0]).length();
        double r0 = Math.max(0.8D, total / 5.0D);
        Vec3 c = pts[c0].add(w.scale(r0));
        for (int i = c0; i <= n; i++) {
            double f = (i - c0) / (double) (n - c0), th = f * Math.PI * 2.0D * 2.25D, r = r0 * (1.0D - 0.78D * f);
            pts[i] = c.add(w.scale(-Math.cos(th) * r)).add(d0.scale(Math.sin(th) * r));
        }
    }

    /** 中心 p・向き d の箱(太さ w・長さ len)を、ブロックの絵で描く。 */
    private static void box(BlockRenderDispatcher br, PoseStack pose, MultiBufferSource buffers, BlockState s, Vec3 p, Vec3 d, float w, float len, int light) {
        pose.pushPose();
        pose.translate(p.x, p.y, p.z);
        pose.mulPose(new Quaternionf().rotationTo(0.0F, 1.0F, 0.0F, (float) d.x, (float) d.y, (float) d.z));
        pose.scale(w, Math.max(len, w * 0.5F), w);
        pose.translate(-0.5F, -0.5F, -0.5F);
        br.renderSingleBlock(s, pose, buffers, light, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    @Override
    public ResourceLocation getTextureLocation(OrganTentacle e) {
        return TextureAtlas.LOCATION_BLOCKS;
    }

    /** 登録(共有の OvergrownVegetationClient を触らないよう、ここで)。 */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Registration {
        private Registration() {
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            event.registerEntityRenderer(OrganEntities.TENTACLE.get(), OrganTentacleRenderer::new);
        }
    }
}

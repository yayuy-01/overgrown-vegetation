package com.yayuy.overgrownvegetation.mixin.client;

import com.yayuy.overgrownvegetation.client.CorruptionAtmosphere;
import com.yayuy.overgrownvegetation.client.FogCullBacklog;
import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 過繁茂バイオームの濃い霧の中にいる間、地形霧の終端より奥にあるチャンクセクションを描画対象から外す。
 *
 * <p>バニラでは霧はGPUの最終段でピクセルを霧色へ潰すだけで、その手前のジオメトリ(草・葉など)は
 * 描画距離いっぱいまで普通に頂点処理・ドローコールされ続ける。過繁茂バイオームでは
 * {@link CorruptionAtmosphere} が地形霧の終端を最大6チャンク(96ブロック)まで詰めているので、
 * それより奥のチャンクは「描いても霧で完全に見えない」。ここでその範囲のセクションを
 * {@link LevelRenderer} の可視セクション一覧({@code visibleSections})から取り除き、
 * 地形レイヤー・ブロックエンティティの描画対象から外す。
 *
 * <p>{@code applyFrustum} はカメラの移動・回転や視錐台更新のたびに {@code visibleSections} を
 * オクルージョングラフから作り直す。その直後(TAIL)に毎回ここでフィルタするので、
 * バイオームを出れば({@link CorruptionAtmosphere#terrainFogCullFarPlane()} が負を返すようになり)
 * 次の作り直しで自然に元へ戻る。グラフ自体もチャンクのロード状態も一切触らないため、
 * 描画距離オプションを下げる方式と違ってチャンク再構築のカクつきは起きない。
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererFogCullMixin {

    @Shadow
    @Final
    private ObjectArrayList<SectionRenderDispatcher.RenderSection> visibleSections;

    @Shadow
    private double prevCamRotX;

    /** 直近の applyFrustum で切った基準の霧の距離(負 = 切らなかった)。 */
    @Unique
    private float overgrownvegetation$cullFar = -1.0F;

    /**
     * 霧の距離が変わったら、視点が止まっていても切り直す(2026-10-18)。バニラは視点が動いたとき(2° ごと)しか見える区画を
     * 作り直さないので、テレポートした後に霧が 10 秒かけて濃くなっても、止まっている間は霧の奥の区画を全部描き続けていた
     * (巨大樹の傘の中で 2394 区画・26 FPS。動けば 431 区画・161 FPS)。向きの記録を壊して、次の setupRender で作り直させる。
     */
    @Inject(method = "setupRender", at = @At("HEAD"))
    private void overgrownvegetation$recullWhenFogMoves(Camera camera, Frustum frustum, boolean hasCapturedFrustum, boolean isSpectator,
                                                       CallbackInfo ci) {
        float far = CorruptionAtmosphere.terrainFogCullFarPlane();
        float last = this.overgrownvegetation$cullFar;
        if ((far < 0.0F) != (last < 0.0F) || far >= 0.0F && Math.abs(far - last) > 8.0F) {
            this.prevCamRotX = Double.NaN;
        }
    }

    @Inject(method = "applyFrustum", at = @At("TAIL"))
    private void overgrownvegetation$cullFoggedSections(Frustum frustum, CallbackInfo ci) {
        float far = CorruptionAtmosphere.terrainFogCullFarPlane();
        this.overgrownvegetation$cullFar = far;
        FogCullBacklog.clear();
        if (far < 0.0F) {
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();

        double limit = far + OvergrownVegetationConfig.COMMON.fogCullExtraMargin.get();
        // 基準距離が描画距離の外まで来ていたら、そもそも切れるセクションが無い
        // (霧が薄い/フェード中で終端距離がバニラ側へ寄っているとき)。数千件の removeIf を回さず抜ける。
        // 対角の分だけ甘く見る必要があるので、辺の長さではなく √3 倍で比べる。
        double renderDistance = mc.options.getEffectiveRenderDistance() * 16.0;
        if (limit >= renderDistance * 1.733) {
            return;
        }
        double limitSq = limit * limit;

        this.visibleSections.removeIf(section -> {
            AABB bb = section.getBoundingBox();
            double dx = Math.max(0.0, Math.max(bb.minX - cam.x, cam.x - bb.maxX));
            double dy = Math.max(0.0, Math.max(bb.minY - cam.y, cam.y - bb.maxY));
            double dz = Math.max(0.0, Math.max(bb.minZ - cam.z, cam.z - bb.maxZ));
            if (dx * dx + dy * dy + dz * dz > limitSq) {
                FogCullBacklog.add(section); // 描かないが、変わったら裏で組み立てておく(2026-10-20)
                return true;
            }
            return false;
        });
    }
}

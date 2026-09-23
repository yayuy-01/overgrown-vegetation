package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import software.bernie.geckolib.animatable.GeoItem;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.model.GeoModel;
import software.bernie.geckolib.renderer.GeoItemRenderer;
import software.bernie.geckolib.util.RenderUtil;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * GeckoLib の防具モデルを、そのままアイテムアイコン(インベントリ/手持ち/落下アイテム)として描く共通処理。
 *
 * <h2>焼き上がったモデルは着用側と共有されている</h2>
 * <p>{@code GeoArmorRenderer} と同じ {@link BakedGeoModel} インスタンスを触るので、
 * <b>いじった表示可否は必ず元へ戻す</b>({@link #doPostRenderCleanup})。
 * 触るのは {@link #ARMOR_BONES} の 8 本だけ — {@code GeoArmorRenderer} が毎フレーム
 * 設定し直すのもこの 8 本だけで、その子や親({@code biped*})をこちらで隠すと
 * <b>誰も戻さないまま着用時の防具が消える</b>。
 * {@link GeoBone#setHidden} は子の表示可否も一緒に落とすので、8 本を切り替えれば中身も付いてくる。
 *
 * <h2>枠への収め方</h2>
 * <p>防具の geo は人型の座標にそのまま置かれていて、部位ごとに位置も大きさもばらばら。
 * アイテムモデル JSON の {@code display} を部位ごとに手で合わせるのは当てずっぽうになるので、
 * <b>描画のたびに表示中のキューブの実寸を測って</b>中央へ寄せ、{@link #fitSize} に収まるよう拡縮する。
 *
 * <p>実寸は {@link #accumulate} が<b>描画と同じ変換を空の {@link PoseStack} で再現して</b>求める。
 * ボーンにもキューブにも回転が入るモデルがあるので、ピボットの足し算だけでは合わない。
 */
public abstract class GeoArmorIconRenderer<T extends ArmorItem & GeoItem> extends GeoItemRenderer<T> {

    /** {@code GeoArmorRenderer} が面倒を見るのと同じ 8 本。こちらもここしか触らない。 */
    private static final String[] ARMOR_BONES = {
            "armorHead", "armorBody", "armorRightArm", "armorLeftArm",
            "armorRightLeg", "armorLeftLeg", "armorRightBoot", "armorLeftBoot"
    };

    /** 描画前の表示可否。{@link #doPostRenderCleanup} でそのまま書き戻す。 */
    private final Map<GeoBone, Boolean> savedVisibility = new HashMap<>();

    protected GeoArmorIconRenderer(GeoModel<T> model) {
        super(model);
        useAlternateGuiLighting();
    }

    /**
     * アイコン枠に収める一辺の長さ(ブロック単位)。1.0 だと 3/4 に振った角が枠から出るので、
     * 既定は対角ぶんの余白を見て少し小さくしてある。大きさを変えたい素材はここを上書きする。
     */
    protected float fitSize() {
        return 0.8F;
    }

    @Override
    public void preRender(PoseStack poseStack, T animatable, BakedGeoModel model,
                          @Nullable MultiBufferSource bufferSource, @Nullable VertexConsumer buffer,
                          boolean isReRender, float partialTick, int packedLight, int packedOverlay, int colour) {
        applyBoneVisibility(model, shownBones(animatable.getType(), getCurrentItemStack()));
        super.preRender(poseStack, animatable, model, bufferSource, buffer, isReRender, partialTick,
                packedLight, packedOverlay, colour);

        if (isReRender) {
            // 2 週目以降(発光レイヤーなど)は 1 週目と同じ姿勢で呼ばれるので、二重に掛けない。
            return;
        }
        float[] box = visibleBounds(model);
        if (box == null) {
            return;
        }
        float longest = Math.max(box[3] - box[0], Math.max(box[4] - box[1], box[5] - box[2]));
        if (longest <= 0.0F) {
            return;
        }
        float scale = fitSize() / longest;
        poseStack.scale(scale, scale, scale);
        poseStack.translate(-(box[0] + box[3]) * 0.5F, -(box[1] + box[4]) * 0.5F, -(box[2] + box[5]) * 0.5F);
    }

    /** 共有しているモデルを元の状態へ戻す。描画が途中で打ち切られても必ず呼ばれる。 */
    @Override
    public void doPostRenderCleanup() {
        super.doPostRenderCleanup();
        this.savedVisibility.forEach(GeoBone::setHidden);
        this.savedVisibility.clear();
    }

    /** その部位で見せるボーン。キューブは更に子のボーン側に入っていることが多い。段階で変わる装備はここを上書きする。 */
    protected Set<String> shownBones(ArmorItem.Type type, ItemStack stack) {
        return bonesFor(type);
    }

    private static Set<String> bonesFor(ArmorItem.Type type) {
        return switch (type) {
            case HELMET -> Set.of("armorHead");
            case CHESTPLATE -> Set.of("armorBody", "armorRightArm", "armorLeftArm");
            case LEGGINGS -> Set.of("armorRightLeg", "armorLeftLeg");
            case BOOTS -> Set.of("armorRightBoot", "armorLeftBoot");
            default -> Set.of();
        };
    }

    private void applyBoneVisibility(BakedGeoModel model, Set<String> shown) {
        for (String name : ARMOR_BONES) {
            model.getBone(name).ifPresent(bone -> {
                this.savedVisibility.putIfAbsent(bone, bone.isHidden());
                bone.setHidden(!shown.contains(name));
            });
        }
    }

    /**
     * いま表示されているキューブ全体の境界箱(ブロック単位){minX, minY, minZ, maxX, maxY, maxZ}。
     * 何も表示するものが無ければ {@code null}。
     */
    @Nullable
    private static float[] visibleBounds(BakedGeoModel model) {
        float[] box = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE,
                -Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        PoseStack scratch = new PoseStack();
        boolean any = false;
        for (GeoBone bone : model.topLevelBones()) {
            any |= accumulate(scratch, bone, box);
        }
        return any ? box : null;
    }

    /**
     * {@code GeoRenderer#renderRecursively} と同じ順番で行列を積み、頂点を実際の位置へ移して測る
     * (ボーン: {@code prepMatrixForBone} / キューブ: ピボットへ移動 → 回転 → 戻す)。
     * 隠れているかの判定も描画側と同じで、キューブは {@code isHidden}、子は {@code isHidingChildren} を見る。
     */
    private static boolean accumulate(PoseStack poseStack, GeoBone bone, float[] box) {
        poseStack.pushPose();
        RenderUtil.prepMatrixForBone(poseStack, bone);
        boolean any = false;

        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                poseStack.pushPose();
                RenderUtil.translateToPivotPoint(poseStack, cube);
                RenderUtil.rotateMatrixAroundCube(poseStack, cube);
                RenderUtil.translateAwayFromPivotPoint(poseStack, cube);
                Matrix4f pose = poseStack.last().pose();
                for (GeoQuad quad : cube.quads()) {
                    if (quad == null) {
                        continue;
                    }
                    for (GeoVertex vertex : quad.vertices()) {
                        Vector3f p = vertex.position();
                        Vector4f moved = pose.transform(new Vector4f(p.x(), p.y(), p.z(), 1.0F));
                        box[0] = Math.min(box[0], moved.x());
                        box[1] = Math.min(box[1], moved.y());
                        box[2] = Math.min(box[2], moved.z());
                        box[3] = Math.max(box[3], moved.x());
                        box[4] = Math.max(box[4], moved.y());
                        box[5] = Math.max(box[5], moved.z());
                        any = true;
                    }
                }
                poseStack.popPose();
            }
        }

        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones()) {
                any |= accumulate(poseStack, child, box);
            }
        }
        poseStack.popPose();
        return any;
    }
}

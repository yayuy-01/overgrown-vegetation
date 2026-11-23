package com.yayuy.overgrownvegetation.client.crowd.sprite;

// 2026-11-18: parked/client/horde から戻した(大群の軽量化 C。遠くの群れの絵の板。ユーザー決定「組み合わせを既定に、絵だけ・モデルだけにも切り替えられるように」)

import com.mojang.blaze3d.vertex.PoseStack;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import software.bernie.geckolib.cache.object.BakedGeoModel;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.cache.object.GeoCube;
import software.bernie.geckolib.cache.object.GeoQuad;
import software.bernie.geckolib.cache.object.GeoVertex;
import software.bernie.geckolib.util.RenderUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * GeckoLib のモデルを<b>レストポーズ</b>のまま、スプライトを描く面の一覧({@link SpriteRasterizer.Quad})にする
 * (2026-09-25、遠景の身代わり)。ボーンと直方体の行列は GeckoLib のレンダラー({@code GeoRenderer#renderRecursively} /
 * {@code renderCube})と同じ順で {@link RenderUtil} を使って積むので、位置・UV・法線は Mob を描くときと同じ
 * ({@code client/GeoStaticGeometry} と同じ作り)。
 *
 * <p>座標はモデル空間(ブロック単位、原点 = エンティティの足元、前 = -Z)。エンティティの向き・体格
 * ({@code GeoEntityRenderer} の {@code 180 − yaw} の回転と {@code getScale()})は掛けない —— 描く側が掛ける。
 *
 * <p>モデルは GeckoLib のキャッシュのものを使わないこと: キャッシュのボーンはアニメーションで書き換わっている
 * (呼び出し側が geo.json から読み直したものを渡す)。
 */
public final class SpriteQuads {

    /** レストポーズの面を全部集める(隠れたボーンは除く)。 */
    public static List<SpriteRasterizer.Quad> of(BakedGeoModel model) {
        List<SpriteRasterizer.Quad> out = new ArrayList<>();
        PoseStack pose = new PoseStack();
        for (GeoBone bone : model.topLevelBones()) {
            addBone(pose, bone, out);
        }
        return out;
    }

    /**
     * 面を前後(モデルの Z)へ {@code shiftZ} ブロックずらした写し(レンダラーが原点に合わせてモデルをずらして描く種 ——
     * スクティムネスの {@code ScutimnesRenderer.MODEL_SHIFT}。0 ならそのまま返す)。
     */
    public static List<SpriteRasterizer.Quad> shifted(List<SpriteRasterizer.Quad> quads, float shiftZ) {
        if (shiftZ == 0.0F) {
            return quads;
        }
        List<SpriteRasterizer.Quad> out = new ArrayList<>(quads.size());
        for (SpriteRasterizer.Quad q : quads) {
            float[] xyz = q.xyz().clone();
            for (int i = 2; i < 12; i += 3) {
                xyz[i] += shiftZ;
            }
            out.add(new SpriteRasterizer.Quad(xyz, q.uv(), q.nx(), q.ny(), q.nz()));
        }
        return out;
    }

    private static void addBone(PoseStack pose, GeoBone bone, List<SpriteRasterizer.Quad> out) {
        pose.pushPose();
        RenderUtils.prepMatrixForBone(pose, bone);
        if (!bone.isHidden()) {
            for (GeoCube cube : bone.getCubes()) {
                addCube(pose, cube, out);
            }
        }
        if (!bone.isHidingChildren()) {
            for (GeoBone child : bone.getChildBones()) {
                addBone(pose, child, out);
            }
        }
        pose.popPose();
    }

    private static void addCube(PoseStack pose, GeoCube cube, List<SpriteRasterizer.Quad> out) {
        pose.pushPose();
        RenderUtils.translateToPivotPoint(pose, cube);
        RenderUtils.rotateMatrixAroundCube(pose, cube);
        RenderUtils.translateAwayFromPivotPoint(pose, cube);
        Matrix4f matrix = pose.last().pose();
        Matrix3f normalMatrix = pose.last().normal();
        for (GeoQuad quad : cube.quads()) {
            if (quad == null) {
                continue;
            }
            GeoVertex[] vertices = quad.vertices();
            float[] xyz = new float[12];
            float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                Vector3f position = matrix.transformPosition(new Vector3f(vertices[i].position()));
                xyz[i * 3] = position.x();
                xyz[i * 3 + 1] = position.y();
                xyz[i * 3 + 2] = position.z();
                uv[i * 2] = vertices[i].texU();
                uv[i * 2 + 1] = vertices[i].texV();
            }
            // GeckoLib が陰影に使う法線(厚み 0 の直方体の裏返りの補正込み)。
            Vector3f normal = normalMatrix.transform(new Vector3f(quad.normal()));
            RenderUtils.fixInvertedFlatCube(cube, normal);
            out.add(new SpriteRasterizer.Quad(xyz, uv, normal.x(), normal.y(), normal.z()));
        }
        pose.popPose();
    }

    private SpriteQuads() {
    }
}

package com.yayuy.overgrownvegetation.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.entity.PediculaEntity;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import org.joml.Quaternionf;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ペディキュラ」の描画。<b>回転はここで付ける</b>(ユーザー指定 2026-09-23:
 * 「x,y,z の回転すべてがランダムに働き、風に流されていく巨大な胞子の塊のような感じに」)。
 *
 * <h2>回し方</h2>
 * 3 軸それぞれに、個体ごとに違う<b>速さ・向き・位相</b>を持たせ({@link PediculaEntity#individual} —
 * UUID から作るのでどのクライアントでも同じ個体は同じ回り方をする)、そこへ<b>ゆっくりした揺らぎ</b>
 * (突風で回り方が変わる感じ)を足す。3 軸の角度がばらばらに進むので、決まった軸で回っている感じが出ない。
 * 速さの平均は mobs.json の {@code drift.spinSpeed}(度/tick)。
 *
 * <h2>支点</h2>
 * モデルは全部の骨が根に並んでいて、全体をまとめる骨が無い。骨ごとに回すとばらばらになるので、
 * <b>姿勢の行列ごと</b>、main の大きな立方体の中心(Blockbench 座標 0.146, 28.85, 0.5。GeckoLib は x を反転する)
 * を支点に回す。当たり判定(高さ 3.6)の中心とも一致させてある。
 * 体の向き(yaw)は使わない —— 転がっている塊に「正面」は無い。
 */
public class PediculaRenderer extends GeoEntityRenderer<PediculaEntity> {

    /** 回転の支点(ブロック。モデル座標、x は GeckoLib の反転後)。 */
    private static final float PIVOT_X = -0.14645F / 16.0F;
    private static final float PIVOT_Y = 28.85355F / 16.0F;
    private static final float PIVOT_Z = 0.5F / 16.0F;

    /** 回り方の揺らぎの幅(度)。 */
    private static final double GUST_DEGREES = 25.0D;
    /** 揺らぎの速さ(ラジアン/tick)の下限と幅。0.01〜0.02 で 1 周期 15〜30 秒。 */
    private static final double GUST_RATE_MIN = 0.01D;
    private static final double GUST_RATE_SPAN = 0.01D;

    public PediculaRenderer(EntityRendererProvider.Context context) {
        super(context, new PediculaModel());
        // 上空に浮いているので影は地面に届かない。スポーンエッグを床に置いたときに丸い影が出るのも変なので無し。
        this.shadowRadius = 0.0F;
    }

    /** バニラの「体の向き」「死ぬと横倒し」の代わりに、転がる回転だけを付ける。 */
    @Override
    protected void applyRotations(PediculaEntity animatable, PoseStack poseStack, float ageInTicks,
                                  float rotationYaw, float partialTick, float nativeScale) {
        poseStack.translate(PIVOT_X, PIVOT_Y, PIVOT_Z);
        poseStack.mulPose(tumble(animatable, ageInTicks));
        poseStack.translate(-PIVOT_X, -PIVOT_Y, -PIVOT_Z);
    }

    /** その瞬間の姿勢。 */
    private static Quaternionf tumble(PediculaEntity entity, float ageInTicks) {
        double spin = MobStats.drift("pedicula").spinSpeed();
        float[] radians = new float[3];
        for (int axis = 0; axis < 3; axis++) {
            // 速さは平均 spin(0.5〜1.5 倍)、向きは軸ごとにどちらか。
            double rate = spin * (0.5D + entity.individual(10 + axis))
                    * (entity.individual(13 + axis) < 0.5D ? -1.0D : 1.0D);
            double gustRate = GUST_RATE_MIN + GUST_RATE_SPAN * entity.individual(16 + axis);
            double degrees = entity.individual(19 + axis) * 360.0D
                    + rate * ageInTicks
                    + GUST_DEGREES * Math.sin(ageInTicks * gustRate + entity.individual(22 + axis) * Math.PI * 2.0D);
            radians[axis] = (float) Math.toRadians(degrees % 360.0D);
        }
        return new Quaternionf().rotationZYX(radians[2], radians[1], radians[0]);
    }
}

package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.BlazeRodSwing;
import com.yayuy.overgrownvegetation.entity.ParasitizedBlaze;
import net.minecraft.util.Mth;
import software.bernie.geckolib.animation.AnimationState;
import software.bernie.geckolib.animation.state.BoneSnapshot;
import software.bernie.geckolib.cache.object.GeoBone;

/**
 * 寄生されたブレイズのモデル(2026-10-09 夜)。アニメの上に、動きにつられて揺れる体と棒({@link BlazeRodSwing}、エンティティが持つ)を足す:
 * 体(root)は進む向きと逆へ傾き、棒は吊り手の骨 {@code rod<i>_hang}(アニメでは打たない、生成器 G1Blaze)で 1 本ずつなびき、
 * 芯(core)は向きを変えたぶん遅れてねじれる。
 *
 * <p>GeckoLib の中の向き: 前 = −Z、右 = +X(geo の X を反転して読む)。X 回転が + で吊られた物の下の端が前へ、Z 回転が + で右へ動く
 * ({@code Axis.XP}・{@code ZP} の右手回り)。骨はモデルごとに 1 組を使い回すので、揺れの状態はエンティティに置き、ここでは毎回書き込むだけ。
 */
public class ParasitizedBlazeModel extends G1GeoModel<ParasitizedBlaze> {

    /** 棒の吊り手の骨の名前 {@code rod<i>_hang}(2026-10-10、ブレイズ 1 体・1 コマごとに名前を組み立てないよう先に作っておく)。 */
    private static final String[] ROD_HANG_BONES = new String[BlazeRodSwing.RODS];

    static {
        for (int i = 0; i < BlazeRodSwing.RODS; i++) {
            ROD_HANG_BONES[i] = "rod" + i + "_hang";
        }
    }

    public ParasitizedBlazeModel() {
        super("overgrown_blaze", true);
    }

    @Override
    public void setCustomAnimations(ParasitizedBlaze blaze, long instanceId, AnimationState<ParasitizedBlaze> state) {
        super.setCustomAnimations(blaze, instanceId, state);
        BlazeRodSwing swing = blaze.rodSwing();
        GeoBone root = getAnimationProcessor().getBone("root");
        GeoBone stalk = getAnimationProcessor().getBone("stalk");
        GeoBone core = getAnimationProcessor().getBone("core");
        if (swing == null || root == null || stalk == null || core == null) {
            return;
        }
        float pt = state.getPartialTick();
        // 体: アニメの回転に足す(root・core はどのアニメも打つので、毎回アニメの値から始まる)
        root.setRotX(root.getRotX() + swing.rootForward(pt) * Mth.DEG_TO_RAD);
        root.setRotZ(root.getRotZ() + swing.rootRight(pt) * Mth.DEG_TO_RAD);
        core.setRotY(core.getRotY() + swing.twist(pt) * Mth.DEG_TO_RAD);
        // 棒: 体の向きの「下の端のずれ」(x = 右、z = 後ろ)を、芯の向き(root・茎・芯の Y 回転の和)へ回してから吊り手へ
        double yaw = root.getRotY() + stalk.getRotY() + core.getRotY();
        double c = Math.cos(yaw);
        double s = Math.sin(yaw);
        for (int i = 0; i < BlazeRodSwing.RODS; i++) {
            GeoBone hang = getAnimationProcessor().getBone(ROD_HANG_BONES[i]);
            if (hang == null) {
                continue;
            }
            double bx = swing.rodRight(i, pt);
            double bz = -swing.rodForward(i, pt);
            double x = bx * c - bz * s;
            double z = bx * s + bz * c;
            // 開き: 芯の軸から棒の付け根への向き
            double ox = hang.getPivotX() - core.getPivotX();
            double oz = hang.getPivotZ() - core.getPivotZ();
            double ol = Math.sqrt(ox * ox + oz * oz);
            if (ol > 1.0E-3D) {
                double f = swing.rodFlare(i, pt);
                x += f * ox / ol;
                z += f * oz / ol;
            }
            BoneSnapshot rest = hang.getInitialSnapshot();
            hang.setRotX(rest.getRotX() - (float) z * Mth.DEG_TO_RAD);
            hang.setRotZ(rest.getRotZ() + (float) x * Mth.DEG_TO_RAD);
        }
    }
}

package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.FalcimnesEntity;
import net.minecraft.util.Mth;
import software.bernie.geckolib.core.animation.AnimationState;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * 段 3 の構え(11-08 夜、森の中を動く型 ②。クライアントの描画だけ)。効きはサーバーが求めて送ったもの({@link FalcimnesEntity#squeeze})。
 * <ul>
 *   <li>狭い所の構え(決定「前を起こして細くなる」): 威嚇の前半身の起こし(根の骨 stance)の 7 割と、突き掻きの「胸に畳んで止まる」鎌。
 *       脚は {@code FootPlanter.lateral} で体へ寄せ、小股にする(FalcimnesEntity が足の置き方に渡す)。</li>
 *   <li>首を下げる構え: 頭の上に葉・枝があるとき、首の付け根と中ほどを垂らす(段 1 で決めた「頭が葉に入る」の和らげ)。</li>
 * </ul>
 * <b>値は生成器 {@code tools/falcimnes-anim/PartTrack.java} と同じ</b>(2 か所。叩ける部位のずれはそちらが表に書く)。値は全部【仮】。
 */
public final class FalcimnesShapeRig {

    /** 位置は GeckoLib の中の値(焼き込んだ px)なので、生成器の値(焼き込む前)× 1.8。 */
    static final float SQ_STANCE_RX = -16.0F * 0.7F, SQ_STANCE_PY = 3.5F * 0.7F * 1.8F, SQ_STANCE_PZ = 1.5F * 0.7F * 1.8F;
    private static final float[] ARMS = new float[64];
    static final float DUCK_NECK = 12.0F, DUCK_BONE2 = 10.0F;

    private FalcimnesShapeRig() {
    }

    public static void apply(GeoModel<FalcimnesEntity> model, FalcimnesEntity e, AnimationState<FalcimnesEntity> state) {
        float pt = state.getPartialTick();
        float q = e.squeeze(pt), d = e.duck(pt);
        if (q > 0.0F) {
            add(model, "stance", SQ_STANCE_RX * q, 0, 0);
            GeoBone st = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone("stance");
            if (st != null) {
                st.setPosY(st.getPosY() + SQ_STANCE_PY * q);
                st.setPosZ(st.getPosZ() + SQ_STANCE_PZ * q);
                st.resetStateChanges();
            }
            // 鎌は突き掻きの前触れの通り道を効きに合わせてなぞる(生成器が作った段の表。前から胸の前へ畳む、11-08 夜の直し)
            var track = com.yayuy.overgrownvegetation.entity.FalcimnesPartTrack.get();
            String[] bones = track.squeezeArmBones;
            if (bones != null && track.squeezeArms(q, ARMS)) {
                for (int i = 0; i < bones.length; i++) {
                    add(model, bones[i], ARMS[i * 3], ARMS[i * 3 + 1], ARMS[i * 3 + 2]);
                }
            }
        }
        if (d > 0.0F) {
            add(model, "neck", DUCK_NECK * d, 0, 0);
            add(model, "bone2", DUCK_BONE2 * d, 0, 0);
        }
    }

    /** ファイル座標の度を足す(GeckoLib の中では X・Y の向きが逆。FalcimnesPostureRig と同じ)。 */
    private static void add(GeoModel<FalcimnesEntity> model, String name, float x, float y, float z) {
        if (x == 0 && y == 0 && z == 0) return;
        GeoBone b = (software.bernie.geckolib.cache.object.GeoBone) model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        b.setRotX(b.getRotX() - x * Mth.DEG_TO_RAD);
        b.setRotY(b.getRotY() - y * Mth.DEG_TO_RAD);
        b.setRotZ(b.getRotZ() + z * Mth.DEG_TO_RAD);
        b.resetStateChanges();
    }
}

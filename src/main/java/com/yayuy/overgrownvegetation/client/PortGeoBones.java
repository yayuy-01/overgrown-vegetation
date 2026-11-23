package com.yayuy.overgrownvegetation.client;

import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.core.animation.AnimationProcessor;

import java.util.Collection;

/**
 * 【1.20.1 だけの物・G5 の助け手】GeckoLib 4.7.6 にあって 4.8.4 に無い骨の口。
 * <ul>
 *     <li>{@code GeoBone#updateRotation/updatePosition/updateScale}(4.7.6 は setRotX/Y/Z などを 3 つ呼ぶだけ)</li>
 *     <li>{@code AnimationProcessor#getRegisteredBones} が {@code GeoBone} の一覧を返す(4.8.4 は {@code CoreGeoBone}。中身は全部 GeoBone)</li>
 * </ul>
 */
public final class PortGeoBones {

    private PortGeoBones() {
    }

    public static void updateRotation(GeoBone bone, float x, float y, float z) {
        bone.setRotX(x);
        bone.setRotY(y);
        bone.setRotZ(z);
    }

    public static void updatePosition(GeoBone bone, float x, float y, float z) {
        bone.setPosX(x);
        bone.setPosY(y);
        bone.setPosZ(z);
    }

    public static void updateScale(GeoBone bone, float x, float y, float z) {
        bone.setScaleX(x);
        bone.setScaleY(y);
        bone.setScaleZ(z);
    }

    /** 登録済みの骨(4.7.6 の {@code getRegisteredBones()} と同じ物。写さずに型だけ変える = 毎フレーム呼んでも作り物が出ない)。 */
    @SuppressWarnings("unchecked")
    public static Collection<GeoBone> registered(AnimationProcessor<?> processor) {
        return (Collection<GeoBone>) (Collection<?>) processor.getRegisteredBones();
    }
}

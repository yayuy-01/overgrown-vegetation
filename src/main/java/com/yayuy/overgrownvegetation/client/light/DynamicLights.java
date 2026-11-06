package com.yayuy.overgrownvegetation.client.light;

import com.yayuy.overgrownvegetation.client.atmosphere.CaveLights;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * <b>動く光</b>: 光のブロックを置かずに、描画だけで Mob・手に持った物・粒を明るくする(クライアントだけ)。
 *
 * <p>2026-10-15 に琥珀の角灯(追加防具の持ち歩く灯り)のために作り、10-30 夜に画面の演出と一緒に parked へ退避(U9)。
 * 2026-11-01 の地下モードの試作で戻した。光源は<b>地下の点光源の表</b>({@link CaveLights#shown()}、地形シェーダーへ渡したのと同じ近い順の最大 24 個)。
 * 防具の灯り(追加防具 {@code ExtraArmor} は退避のまま)は戻していない。
 *
 * <h2>仕組み</h2>
 * 読むのは Mixin 2 か所: Mob などの明るさ({@code EntityRenderer#getPackedLightCoords}。一人称の手に持った物もここを通る)と粒の明るさ
 * ({@code Particle#getLightColor})。届く光の強さの和 I({@link CaveLights#intensityAt}、地形シェーダーと同じ減り方)をブロックの光
 * {@code round(15 × I × entityGain)} に直し、元の明るさより<b>暗くはしない</b>。地形の面(前の {@code LevelRenderer#getLightColor} への Mixin)は
 * 戻さない: 地形はシェーダーの点光源が照らすので二重に明るくなり、区画の組み立て直しも要る。ライトマップのブロックの光の色(暖色)のままで、緑には染まらない。
 *
 * <h2>しないこと</h2>
 * 見た目だけなので、Mob の湧き・作物の育ち・日照センサーには効かない。
 */
public final class DynamicLights {

    private DynamicLights() {
    }

    /** 動く光が 1 つでもあるか(無ければ呼ぶ側で位置を作らずに済ませる)。 */
    public static boolean any() {
        return CaveLights.mix() > 0.0F && (CaveLights.shown().length > 0 || CaveLights.ambient() > 0.0F);
    }

    /**
     * {@code pos} に足す動く光の明るさ(0〜15)。地形は「絵の色 × I × lightGain」だけ明るくなるので、Mob は明るさ b = I × lightGain × entityGain
     * になるブロックの光にする(バニラのライトマップの明るさ b = x / (4 − 3x)、x = 光 / 15 を逆に解いた x = 4b / (1 + 3b))。
     * 地下モードの床の持ち上げ(ambient × 地下の係数、11-01 夜)も地形と同じだけ足す。
     */
    public static int levelAt(Vec3 pos) {
        var p = DesignData.atmosphere().cave();
        float b = Mth.clamp(CaveLights.intensityAt(pos.x, pos.y, pos.z) * p.lightGain() * p.entityGain()
                + CaveLights.ambient() * CaveLights.mix(), 0.0F, 1.0F);
        return Mth.clamp(Math.round(15.0F * 4.0F * b / (1.0F + 3.0F * b)), 0, 15);
    }

    /** 詰めた光の値({@code sky << 20 | block << 4})の、ブロックの光だけを動く光で底上げする。 */
    public static int brighten(int packed, Vec3 pos) {
        int dyn = levelAt(pos);
        if (dyn <= 0) {
            return packed;
        }
        int block = (packed >> 4) & 0xF;
        if (dyn <= block) {
            return packed;
        }
        return (packed & ~(0xF << 4)) | (dyn << 4);
    }
}

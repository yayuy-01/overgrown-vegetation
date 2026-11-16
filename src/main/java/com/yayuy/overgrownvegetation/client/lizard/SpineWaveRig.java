package com.yayuy.overgrownvegetation.client.lizard;

import com.yayuy.overgrownvegetation.entity.lizard.LizardRunner;
import com.yayuy.overgrownvegetation.entity.lizard.SpineWave;
import net.minecraft.util.Mth;
import software.bernie.geckolib.cache.object.GeoBone;
import software.bernie.geckolib.model.GeoModel;

/**
 * トカゲ型の背骨のくねりと見回し({@link SpineWave})を骨に足す描画(2026-11-15)。脚の IK より<b>先</b>に呼ぶ(脚の付け根は胸・腰に付いているので、
 * 振った胴から脚を解く)。
 *
 * <p>骨の作り: 胸(胴の根)→ 腰(胸の子、尾側へ伸びる)→ 尾 0〜4。首 0 は胸の子。向き(GeckoLib の中の値): 頭側へ伸びる骨は rotY + で先が左へ、
 * 尾側へ伸びる骨は rotY − で先が左へ。ここでは各骨の「体に対する向き」を先に決め、親との差を足す。
 */
public final class SpineWaveRig {

    private final String chest, hip, neck0, neck1, neck2, head;
    private final String[] tails;

    /**
     * @param tails 尾の骨(付け根から先へ、{@link SpineWave#TAILS} まで)
     */
    public SpineWaveRig(String chest, String hip, String neck0, String neck1, String neck2, String head, String[] tails) {
        this.chest = chest;
        this.hip = hip;
        this.neck0 = neck0;
        this.neck1 = neck1;
        this.neck2 = neck2;
        this.head = head;
        this.tails = tails;
    }

    public void apply(GeoModel<?> model, LizardRunner e, float pt) {
        SpineWave s = e.spine();
        double r = Mth.DEG_TO_RAD;
        // 体に対する向き(GeckoLib の rotY に足す量、ラジアン)。頭側の骨は「左への角」、尾側の骨は「−左への角」
        double wChest = s.get(SpineWave.CHEST, pt) * r;
        double wHip = -s.get(SpineWave.HIP, pt) * r;
        addY(model, this.chest, wChest);
        // 腰の骨が無い体(胴が 1 本。イアンタサウルスなど、11-16)は、尾が胴に付いている: 尾の付け根は胴の向きからの差
        double before = wChest;
        if (this.hip != null) {
            addY(model, this.hip, wHip - wChest);
            before = wHip;
        }
        for (int i = 0; i < this.tails.length && i < SpineWave.TAILS; i++) {
            double w = -s.get(SpineWave.TAIL0 + i, pt) * r;
            addY(model, this.tails[i], w - before);
            before = w;
        }
        addY(model, this.neck0, s.get(SpineWave.NECK, pt) * r);
        // 見回し: 首の付け根を上げ(rotX + で頭側が上がる)、頭を左右へ(首 1・首 2・頭で分ける)
        double lift = s.get(SpineWave.LOOK_PITCH, pt) * r, scan = s.get(SpineWave.LOOK_YAW, pt) * r;
        if (lift != 0.0D || scan != 0.0D) {
            add(model, this.neck0, lift * 0.7D, 0.0D);
            add(model, this.neck1, lift * 0.3D, scan * 0.3D);
            add(model, this.neck2, -lift * 0.3D, scan * 0.3D);
            add(model, this.head, -lift * 0.4D, scan * 0.4D);
        }
    }

    private static void addY(GeoModel<?> model, String name, double rad) {
        add(model, name, 0.0D, rad);
    }

    private static void add(GeoModel<?> model, String name, double rotX, double rotY) {
        if (name == null || (rotX == 0.0D && rotY == 0.0D)) return;
        GeoBone b = model.getAnimationProcessor().getBone(name);
        if (b == null) return;
        b.setRotX(b.getRotX() + (float) rotX);
        b.setRotY(b.getRotY() + (float) rotY);
        b.resetStateChanges();   // キーの無い骨は、下ろさないと足した値が次のフレームにも残って積み上がる
    }
}

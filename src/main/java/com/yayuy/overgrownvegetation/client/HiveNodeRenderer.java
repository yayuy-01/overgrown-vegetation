package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.entity.HiveNodeEntity;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import software.bernie.geckolib.renderer.GeoEntityRenderer;

/**
 * 「ハイヴノード」の描画。
 *
 * <p>発光レイヤー({@code AutoGlowingGeoLayer} + {@code _glowmask})は 2026-09-18 に外した。GeckoLib のマスクは
 * 「色が 0 でない画素」をすべて発光側へ移すので、不透明な黒で塗ったマスクだと網({@code ami})の穴が真っ黒になる。
 * 光る点はテクスチャの移り変わり(hive_node.png の 8 コマ)で表現し、明るさは {@link HiveNodeEntity} が
 * 小部屋に置く光源ブロック(明るさ 15)に任せる。
 *
 * <p><b>遠くでは描かない</b>(2026-10-07、FPS の調査): 移り変わりは GeckoLib が描くたびに 1 コマ 512 × 512 の途中の絵を
 * CPU で作り直す(1 tick に 1 回)。体の大きさから約 190 ブロック先まで描かれていて、巨大樹の中で外からは見えないのに
 * 描画スレッドの 17%・1 コマ約 2 ms を使っていた。{@link #MAX_RENDER_DISTANCE} より遠いときは描かない。
 * 2026-10-23 からは途中の絵を作らない(mcmeta の interpolate を外した)。コマが替わるとき(25 tick ごと)に 1 回送るだけ。
 * 128 × 1024 に縮める案は網の模様と穴の縁が消えたので見送り(2026-10-24 ユーザー確定: 512 のまま補間なし)。
 */
public class HiveNodeRenderer extends GeoEntityRenderer<HiveNodeEntity> {

    /** これより遠いと描かない(ブロック)。巣の球の半径は 17 前後で、巨大樹の殻の外からは見えない。 */
    static final double MAX_RENDER_DISTANCE = 64.0D;

    public HiveNodeRenderer(EntityRendererProvider.Context context) {
        super(context, new HiveNodeModel());
        this.shadowRadius = 0.0F; // 小部屋の中で浮いているので影は落とさない
    }

    @Override
    public boolean shouldRender(HiveNodeEntity entity, Frustum frustum, double camX, double camY, double camZ) {
        return entity.distanceToSqr(camX, camY, camZ) <= MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE
                && super.shouldRender(entity, frustum, camX, camY, camZ);
    }
}

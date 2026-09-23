package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;

/**
 * 過繁茂バイオームの濃い霧の中にいる間、<b>地形霧の終端より奥にいるエンティティを描かない</b>。
 * 判定だけを持つクラスで、実際に描画を打ち切るのは
 * {@link com.yayuy.overgrownvegetation.mixin.client.EntityRendererFogCullMixin}。
 *
 * <p>{@link com.yayuy.overgrownvegetation.mixin.client.LevelRendererFogCullMixin} が地形
 * (チャンクセクション)に対して行っているのと<b>同じことをエンティティに対して行う</b>。
 * バニラは地形と同じくエンティティにも霧を最終段のピクセル処理として掛けるだけなので、
 * 霧で完全に潰れて<b>1 ピクセルも見えない</b>位置にいる Mob も、
 * 骨の行列計算・全キューブの頂点生成・ドローコールまで普通に行われ続けている。
 *
 * <h2>{@link MobRenderLod} との違い</h2>
 * <p>{@link MobRenderLod} が止めるのは<b>アニメーション</b>だけで、レストポーズの姿勢で
 * <b>ジオメトリは毎フレーム作られ続ける</b>(過繁茂シールダー 120 キューブ、
 * 過繁茂ネクサス 193 キューブ)。こちらは描画の呼び出し自体を打ち切るので、その頂点処理ごと無くなる。
 * 2 つは重なり合う対策ではなく順に効く —— {@code mobRenderLodAnimateChunkRadius}(既定 3 チャンク
 * = 約 48 ブロック)より外はアニメが止まり、霧の終端(既定 96 ブロック + {@code fogCullExtraMargin})
 * より外は描画自体が消える。
 *
 * <h2>見た目が変わらない理由</h2>
 * <p>判定に使うのは {@link CorruptionAtmosphere#terrainFogCullFarPlane()} ——
 * 「地形をここより奥は消してよい」と既に判断されている距離そのもの。
 * 地形が消えて構わないほど霧が濃い範囲なので、その奥のエンティティも同じく見えない。
 * 霧の演出がまだ効いていないとき(バイオームの外・出入りのフェード中)は負の値が返り、
 * このクラスは何もしない。距離はエンティティの当たり判定の<b>最も近い角</b>で測るので、
 * 巨大な Mob が「体の一部が霧の手前にあるのに消える」ことも起きない。
 *
 * <h2>対象外</h2>
 * <p><b>攻城戦ボス</b>({@link DorgaArchelausTitanicaEntity}): 全長 615 ブロックで常に霧の外側にいる。
 * ボスは {@link TitanicaImpostor} の遠景用インポスターで既に軽く描かれているので、
 * ここで消すと「遠くの空に見えているべき姿」が消えるだけになる。
 *
 * <p><b>{@code noCulling} のエンティティ</b>: 「当たり判定の外まで描く」と宣言しているもの
 * (攻城戦ボスの部位・ビーム等)。実際の描画がどこまで伸びているか当たり判定から測れないので触らない。
 */
public final class EntityFogCull {

    /**
     * {@code entity} は今フレーム、霧の奥にいて 1 ピクセルも見えないか。
     *
     * @param camX カメラ位置(バニラの {@code EntityRenderer#shouldRender} に渡ってくるもの)
     */
    public static boolean shouldCull(Entity entity, double camX, double camY, double camZ) {
        if (!OvergrownVegetationConfig.COMMON.fogCullEntities.get()
                || entity.noCulling
                || entity instanceof DorgaArchelausTitanicaEntity) {
            return false;
        }
        // 地形をこの距離より奥で消してよいか(= 霧が十分に濃いか)は CorruptionAtmosphere が
        // 毎フレーム決めている。負なら「今フレームは消さない」。
        float far = CorruptionAtmosphere.terrainFogCullFarPlane();
        if (far < 0.0F) {
            return false;
        }

        // 当たり判定の最も近い角までの距離で測る(地形側と同じ式)。巨大な Mob の
        // 「体の一部だけ霧の手前」を消してしまわないため、中心距離では測らない。
        AABB bb = entity.getBoundingBoxForCulling();
        double dx = Math.max(0.0, Math.max(bb.minX - camX, camX - bb.maxX));
        double dy = Math.max(0.0, Math.max(bb.minY - camY, camY - bb.maxY));
        double dz = Math.max(0.0, Math.max(bb.minZ - camZ, camZ - bb.maxZ));
        double limit = far + OvergrownVegetationConfig.COMMON.fogCullExtraMargin.get();
        return dx * dx + dy * dy + dz * dz > limit * limit;
    }

    private EntityFogCull() {
    }
}

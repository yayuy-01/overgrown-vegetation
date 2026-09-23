package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity;
import com.yayuy.overgrownvegetation.entity.PhyllosensEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * この MOD の GeckoLib Mob のアニメーションを、カメラから遠いときは<b>完全に止める</b>(描画 LOD)。
 * {@link LodGeoModel} から毎フレーム問い合わせられる。
 *
 * <p>GeckoLib はエンティティを描くたびに全コントローラのキーフレーム補間と全ボーンの行列計算を行う。
 * Mob が数百体いると、これがクライアント側で最も重い処理になる。
 * カメラのいるチャンクから {@code mobRenderLodAnimateChunkRadius}(既定 3)チャンクより外にいる Mob は<b>レストポーズで固まる</b> —
 * 位置・体の向きの補間は GeckoLib のレンダラー側で毎フレーム行われるので、
 * 止まるのは手足・翅などの細かい動きだけ。
 *
 * <h2>なぜ「数フレームに 1 回だけ更新する」ではいけないのか(2026-09-13 に作り直した)</h2>
 * <p>元は距離に応じて 2〜4 フレームに 1 回だけ骨を更新し、残りのフレームは「前回の姿勢のまま」
 * 描くつもりだった。ところが<b>GeckoLib の {@code GeoBone} はモデル単位で使い回される</b> —
 * 同じ種類の Mob が何体いても、骨のオブジェクトは 1 セットしかない。
 * 更新を飛ばしたフレームに残っているのは「前回の<b>自分の</b>姿勢」ではなく
 * <b>直前に描かれた別の個体の姿勢</b>で、更新するフレームとしないフレームで姿勢が飛び交い、
 * <b>痙攣しているように見えていた</b>。
 *
 * <p>そこで「間引く」のをやめて「止める」ことにした。止めるフレームでは
 * {@link LodGeoModel} が骨をレストポーズ({@code getInitialSnapshot})へ戻すので、
 * どの個体を描いても必ず同じ姿勢になり、飛び交いようがない。
 *
 * <h2>対象外にしている Mob</h2>
 * <p><b>攻城戦ボス</b>: 全長 615 ブロックあり、常にカメラから数百ブロック離れている。距離で止めると
 * <b>永遠に止まったまま</b>になるうえ、1 体しか居ないので負荷対策の意味も無い。
 * ボスのモデル({@link DorgaArchelausTitanicaModel})はそもそも {@link LodGeoModel} を継承していないが、
 * 将来つなぎ替えられても止まらないよう、ここでも明示的に外してある。
 *
 * <p><b>フィロセンス</b>(ユーザー要望 2026-09-14): <b>遠くから見て索敵中だと分かること</b>が
 * この Mob の役目そのもの —— 首を上げ花を開いた姿が「いま撒かれている」の合図なのに、
 * 48 ブロック(既定 3 チャンク)でレストポーズに戻ってしまっては伝わらない。
 * 湧く数も少ない(`OvergrownSpawnProfile` で weight 0.8 / clusterMax 1 / spawnChance 0.08 のレア枠)ので、
 * 常時動かしても負荷にならない。
 */
public final class MobRenderLod {

    /** このフレームは {@code entity} のアニメーションを止めるか(= レストポーズで描くか)。 */
    public static boolean shouldStopAnimation(Entity entity) {
        OvergrownVegetationConfig.Common cfg = OvergrownVegetationConfig.COMMON;
        if (!cfg.mobRenderLodEnabled.get()) {
            return false;
        }
        // ワールドに追加されていないもの(スポーンエッグのプレビュー等)は対象外。
        if (!entity.isAddedToLevel() || alwaysAnimated(entity)) {
            return false;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameRenderer == null || mc.level == null) {
            return false;
        }
        // 判定はチャンク単位(2026-09-13、要望): カメラのいるチャンクから半径 N チャンク(チェビシェフ距離)の
        // 正方形の中にいる Mob だけ動かす。距離(ブロック)ではなくチャンクなので、境界は常にチャンクの境目。
        int radius = cfg.mobRenderLodAnimateChunkRadius.get();
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        int camChunkX = Mth.floor(cam.x) >> 4;
        int camChunkZ = Mth.floor(cam.z) >> 4;
        int entityChunkX = entity.getBlockX() >> 4;
        int entityChunkZ = entity.getBlockZ() >> 4;
        return Math.abs(entityChunkX - camChunkX) > radius || Math.abs(entityChunkZ - camChunkZ) > radius;
    }

    /** 距離に関係なく必ず動かすもの。理由はクラスの説明を参照。 */
    private static boolean alwaysAnimated(Entity entity) {
        return entity instanceof DorgaArchelausTitanicaEntity
                || entity instanceof PhyllosensEntity;
    }

    private MobRenderLod() {
    }
}

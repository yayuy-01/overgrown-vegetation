package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.siege.SiegeData;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;

/**
 * ドルガ=アーケロウス・ティタニカの GeckoLib アニメーション制御。実エンティティとインポスタで共通。
 *
 * <p>アニメーションファイル {@code animations/dorga_archelaus_titanica.animation.json} には
 * 花({@code flower}〜{@code flower8})・増幅結晶({@code zouhukuki}〜{@code zouhukuki9})・頭の
 * 4 本だけを入れてある:
 * <ul>
 *   <li>{@code idle} — 花弁の呼吸と結晶のゆるい脈動(ループ)</li>
 *   <li>{@code charge} — 花が 8 方向へ大きく開き、結晶が膨らむ(最終フレームで保持)</li>
 *   <li>{@code fire} — 開いたまま震える(ループ)</li>
 *   <li>{@code recover} — 花が閉じ、結晶が元へ戻る(1 回)</li>
 * </ul>
 * ヒレ・触手・尾・首の動きはボーン数が多すぎてキーフレームでは書けないので、
 * {@link DorgaArchelausTitanicaModel#setCustomAnimations} が手続き的に付けている。
 *
 * <p>どのクリップを流すかは、サーバーから同期された花のビームの段階
 * ({@link ClientSiegeBoss#flowerPhase()})を毎フレーム見て決める。GeckoLib の
 * {@code triggerAnim} による同期は使わない — インポスタはエンティティではなく、
 * そもそも {@code SiegeBossStatePayload} で段階が届いているため。
 */
public final class TitanicaAnimations {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("idle");
    private static final RawAnimation CHARGE = RawAnimation.begin().thenPlayAndHold("charge");
    private static final RawAnimation FIRE = RawAnimation.begin().thenLoop("fire");
    private static final RawAnimation RECOVER = RawAnimation.begin().thenPlay("recover");

    /**
     * 段階を切り替えるときの補間(tick)。GeckoLib はこの間に今の姿勢から新しいアニメの 0 秒目の姿勢へ寄せ、
     * それが終わってからアニメを 0 秒目から流す(4.7.6 の {@code AnimationController#process})。
     * 段階の長さ({@code siegeBeam*Ticks})はアニメの秒に合わせていないので、サーバー側に足すものは無い
     * (2026-09-23 の監査)。溜め(200 tick)は開ききる 54 tick より十分長く、照射はループ。
     * 復帰(40 tick)は {@code recover} 2 秒の最後の 6 tick が切れるが、待機へ寄せる行き先(花 0°・結晶 1.0)が
     * {@code recover} の最後のコマと同じなので見た目は変わらない。
     */
    private static final int TRANSITION_TICKS = 6;

    public static <T extends TitanicaAnimatable> void register(T animatable, AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(animatable, "flower", TRANSITION_TICKS, state -> {
            RawAnimation animation = switch (ClientSiegeBoss.flowerPhase()) {
                case SiegeData.BEAM_CHARGE -> CHARGE;
                case SiegeData.BEAM_FIRE -> FIRE;
                case SiegeData.BEAM_RECOVER -> RECOVER;
                default -> IDLE;
            };
            return state.setAndContinue(animation);
        }));
    }

    private TitanicaAnimations() {
    }
}

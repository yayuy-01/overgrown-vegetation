package com.yayuy.overgrownvegetation.client;

import software.bernie.geckolib.animatable.GeoAnimatable;

/**
 * ドルガ=アーケロウス・ティタニカを描くための GeckoLib アニマタブル。
 * 近距離用の実エンティティ({@link com.yayuy.overgrownvegetation.entity.DorgaArchelausTitanicaEntity})と、
 * 遠距離用のインポスタ({@link TitanicaImpostor}、エンティティではないクライアント側だけの物体)の
 * 両方がこれを実装し、同じ {@link DorgaArchelausTitanicaModel} で描かれる。
 *
 * <p>首の向き・ビームの段階といった「ボス 1 体ぶんの状態」は、どちらから描く場合も
 * {@link ClientSiegeBoss} から読む(ボスは 1 体しか居ないため)。ここで要るのはアニメーションの
 * 時刻だけ。
 */
public interface TitanicaAnimatable extends GeoAnimatable {

    /** 手続き的な動き(ヒレ・触手・尾のうねり)に使う経過 tick(サブ tick 込み)。 */
    double titanicaAge(float partialTick);
}

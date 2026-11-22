package com.yayuy.overgrownvegetation.entity;

/**
 * スポーンエッグの絵({@code client.SpawnEggEntityItemRenderer})の描画専用の個体に、描く前の姿勢を付けさせる口。
 *
 * <p>描画専用の個体は世界に入らず tick もしない({@code onAddedToLevel} も通らない)。tick の中で描くための形を作る種
 * (体を道から解くフォディムネスなど)は、そのままだと何も描かれないので、作った直後に 1 回だけこれを呼ぶ。
 * 世界のブロック・明るさは見ないこと(個体は世界の原点に置かれている)。
 */
public interface EggPreviewPose {

    /** 描画専用の個体を作って向きを決めた直後に 1 回呼ばれる(クライアント)。 */
    void prepareEggPreview();
}

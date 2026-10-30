package com.yayuy.overgrownvegetation.config;

/**
 * 巻きシダ({@code overgrown_curled_fern})の立体モデルをチャンクメッシュへ焼き込むときの精細さ。
 *
 * <p>巻きシダは geo.json 由来の立体モデルなので、バニラのクロス型の草(4面)に比べて1本あたりの
 * 面数がずっと多い。地表の下草の半分を占めるため、非力な環境では面数を落とせるようにしている。
 * 実際の焼き込みは {@link com.yayuy.overgrownvegetation.client.GeoStaticGeometry} が行う。
 *
 * <p>面数はモデル1本あたりの目安(透明な面・面積ゼロの面を捨てたあと)。
 */
public enum CurledFernDetail {
    /**
     * geo.json のまま。約86面。
     *
     * <p>ビデオ設定「シダの葉を立体で描く」を ON にした状態。
     *
     * <p>2026-09-17 まではこれが既定だった。汚染された地表のマスのおよそ2割が巻きシダになる
     * ({@code growthChance} 0.85 ×「上面の下草の抽選で巻きシダ」weight 2/4 など)ため、
     * 1チャンク列あたり約4,600面を占め、描画距離12チャンクではバニラの地形全体の2〜3倍の
     * ポリゴンになっていた —— FPS 低下の最大の原因。既定は {@link #REDUCED} へ移した。
     */
    FULL,
    /**
     * <b>葉は 6 本のまま</b>、葉の中央の筋で折れた 2 枚の板を<b>1 枚の板</b>にした軽い形({@code overgrown_curled_fern_flat.geo.json}、
     * tools/curled-fern-flat が元の geo.json から作る)。42 面。<b>既定</b>(ビデオ設定「シダの葉を立体で描く」を OFF にした状態)。
     * 2026-10-15 夜、ユーザー「1 枚の板で表現した低キューブ版を作れば、キューブ数が半分になるので、最初から 6 枚の葉で今の fps にできる」。
     *
     * <p>それまでは下のとおり 6 本のうち 3 本だけ残していた(48 面。今もモデルに {@code reduced_geo} が無いときはこちら)。
     *
     * <p>どの3本を残すかは<b>草ごと(ブロックの位置ごと)にランダム</b>
     * (2026-09-17。それまでは fern3・fern5・fern7 を省く固定だった)。
     * モデル JSON の {@code reduced_bone_groups} が「葉の高さで組んだ3つのペア」を持っており、
     * 各ペアから1本ずつ選ぶので、<b>どの組み合わせでも低・中・高が1本ずつそろう</b>。
     * 8通り × blockstate の64通りで512通りの見え方になる。面数は変わらない
     * (詳しくは {@link com.yayuy.overgrownvegetation.client.RandomBoneGroupModel})。
     */
    REDUCED,
    /** バニラの草と同じ十字の板。4面。一番軽いが立体感は無くなる。 */
    SIMPLE
}

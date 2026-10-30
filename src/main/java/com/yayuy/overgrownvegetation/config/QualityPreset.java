package com.yayuy.overgrownvegetation.config;

/**
 * 演出の詳細設定の「品質の段階」(2026-10-23、ユーザー決定: 手動で選ぶだけ・自動調整は無し・既定は高)。
 *
 * <p>段階そのものは保存しない: 画面は下の各項目の値がどの段階と全部一致するかを見て「低・中・高」を出し、
 * どれとも違えば「カスタム」と出す(バニラの「グラフィック」とビデオ設定の関係と同じ考え。保存した段階と値が食い違うことが無い)。
 * 段階を選ぶと、ここの値が各項目へ一式書かれる。<b>高 = 各項目の既定値</b>(今の見た目)にしておくこと —— でないと新しく入れた人が「カスタム」から始まる。
 *
 * <p>値はユーザー承認(10-23)の表のとおり。目安の GPU: 高 = RTX 4060 級、中 = GTX 1650 級、低 = 内蔵 GPU。
 * 色味・ビネットは好みの設定なので段階に入れない。
 */
public enum QualityPreset {
    //    解像度 ミップ 下草 粒   光の筋 霧の板            雲の遠景               動く光 巻きシダ
    LOW(66, true, 32, 50, 30, MistSheets.FEW, FarCloudDetail.COARSE, false, CurledFernDetail.SIMPLE),
    MEDIUM(85, true, 48, 70, 60, MistSheets.MANY, FarCloudDetail.COARSE, true, CurledFernDetail.REDUCED),
    HIGH(100, true, 48, 100, 100, MistSheets.MANY, FarCloudDetail.FINE, true, CurledFernDetail.REDUCED);

    public final int renderScale;
    public final boolean plantMipmaps;
    public final int undergrowthDistance;
    public final int particleAmount;
    public final int lightShafts;
    public final MistSheets mistSheets;
    public final FarCloudDetail farClouds;
    public final boolean dynamicLights;
    public final CurledFernDetail curledFern;

    QualityPreset(int renderScale, boolean plantMipmaps, int undergrowthDistance, int particleAmount, int lightShafts,
                  MistSheets mistSheets, FarCloudDetail farClouds, boolean dynamicLights, CurledFernDetail curledFern) {
        this.renderScale = renderScale;
        this.plantMipmaps = plantMipmaps;
        this.undergrowthDistance = undergrowthDistance;
        this.particleAmount = particleAmount;
        this.lightShafts = lightShafts;
        this.mistSheets = mistSheets;
        this.farClouds = farClouds;
        this.dynamicLights = dynamicLights;
        this.curledFern = curledFern;
    }

    /** 朝霧・洞窟の霧の板の枚数。 */
    public enum MistSheets {
        /** 朝霧 4 + 林冠の雲海 2 枚・洞窟の霧 4 枚(2026-10-24 に朝霧 5 + 3 から減らした。濃さは 5 + 3 のときに合わせる)。 */
        MANY,
        /** 朝霧 3 + 2 枚・洞窟の霧 2 枚。1 枚を濃くして全体の濃さはほぼ変えない。 */
        FEW
    }

    /** 雲の上から見る遠くの雲の海の柱の大きさ。 */
    public enum FarCloudDetail {
        /** 48・96・192 ブロック(今まで)。 */
        FINE,
        /** 内の 2 段を倍の 96・192 ブロックに(面の数はおよそ 1/4)。y 950 より上の外の輪は 192 のまま(真ん中の寄せと柱の境を揃えるため)。 */
        COARSE
    }
}

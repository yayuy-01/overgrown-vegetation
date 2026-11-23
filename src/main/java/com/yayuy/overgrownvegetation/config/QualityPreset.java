package com.yayuy.overgrownvegetation.config;

import com.yayuy.overgrownvegetation.client.crowd.HordeLook;
import org.jetbrains.annotations.Nullable;

/**
 * この MOD の描画の演出の段「なし・低・中・高」(2026-11-23、ユーザー決定「クオリティの なし・低・中・高、中身は見た目だけの物を全部切る」。
 * 前は 2026-10-23 の「品質の段階」低・中・高 = 巻きシダと雰囲気の重さだけ)。ビデオ設定の「この MOD の演出」
 * ({@code client/RenderTierOption})と見た目の設定の画面の一番上の行が、どちらもここを使う。
 *
 * <p>段そのものは保存しない: 手元の値がどの段と全部一致するかを見て「低・中・高」を出し、どれとも違えば「カスタム」と出す
 * (バニラの「グラフィック」とビデオ設定の関係と同じ考え)。段を選ぶと、ここの値が各項目へ一式書かれる。<b>高 = 各項目の既定値</b>(今の見た目)
 * にしておくこと —— でないと新しく入れた人が「カスタム」から始まる。
 *
 * <p><b>なし</b> = 親の項目 {@code visualEffects} をオフにするだけ(ほかの項目の値は残す)。親がオフなら、ほかの値によらず「なし」。
 * 低・中・高を選ぶと親をオンに戻し、列の値を書く。
 *
 * <p>列の中身は 11-23 の仮決め(重い物から削る): 中 = 雰囲気の重さ 中・光のにじみを切る。低 = さらに雰囲気の重さ 低・雲(ボクセル。バニラの雲に戻る)・
 * 光の筋・木漏れ日を切り、遠くの大群は板の絵だけ。面の凹凸と簡易 AO は既定でオフなので、どの段もオフ。
 * 霧・林冠の光・葉の揺れ・粒・小さな生き物・水まわり・地下モードは森の見た目そのものなので低でも残す(粒の数は雰囲気の重さで減る)。
 * 目安の GPU: 高 = RTX 4060 級、中 = GTX 1650 級、低 = 内蔵 GPU。演出のオンオフの残り(音・見張りの目の強さなど)は好みなので段に入れない。
 */
public enum QualityPreset {
    //        巻きシダ                  雰囲気の重さ                にじみ 雲     光の筋 木漏れ日 凹凸   AO     遠くの大群
    NONE(CurledFernDetail.REDUCED, AtmosphereQuality.LOW, false, false, false, false, false, false, HordeLook.SPRITE),
    LOW(CurledFernDetail.REDUCED, AtmosphereQuality.LOW, false, false, false, false, false, false, HordeLook.SPRITE), // 11-03 まで巻きシダ SIMPLE(削除)
    MEDIUM(CurledFernDetail.REDUCED, AtmosphereQuality.MEDIUM, false, true, true, true, false, false, HordeLook.COMBINED),
    HIGH(CurledFernDetail.REDUCED, AtmosphereQuality.HIGH, true, true, true, true, false, false, HordeLook.COMBINED);

    public final CurledFernDetail curledFern;
    public final AtmosphereQuality atmosphere;
    public final boolean glowBloom;
    public final boolean clouds;
    public final boolean lightShafts;
    public final boolean canopyDapple;
    public final boolean surfaceBump;
    public final boolean simpleAo;
    public final HordeLook hordeLook;

    QualityPreset(CurledFernDetail curledFern, AtmosphereQuality atmosphere, boolean glowBloom, boolean clouds, boolean lightShafts,
                  boolean canopyDapple, boolean surfaceBump, boolean simpleAo, HordeLook hordeLook) {
        this.curledFern = curledFern;
        this.atmosphere = atmosphere;
        this.glowBloom = glowBloom;
        this.clouds = clouds;
        this.lightShafts = lightShafts;
        this.canopyDapple = canopyDapple;
        this.surfaceBump = surfaceBump;
        this.simpleAo = simpleAo;
        this.hordeLook = hordeLook;
    }

    /** 今の設定の値がどの段か(どれとも違えば null = カスタム)。親がオフなら {@link #NONE}。 */
    @Nullable
    public static QualityPreset current() {
        OvergrownClientConfig.Client c = OvergrownClientConfig.CLIENT;
        if (!c.visualEffects.get()) {
            return NONE;
        }
        for (QualityPreset p : values()) {
            if (p != NONE && p.curledFern == c.curledFernDetail.get() && p.atmosphere == c.atmosphereQuality.get()
                    && p.glowBloom == c.glowBloom.get() && p.clouds == c.clouds.get() && p.lightShafts == c.lightShafts.get()
                    && p.canopyDapple == c.canopyDapple.get() && p.surfaceBump == c.surfaceBump.get() && p.simpleAo == c.simpleAo.get()
                    && p.hordeLook == c.hordeLook.get()) {
                return p;
            }
        }
        return null;
    }

    /** この段の値を設定へ書いて保存する(なし = 親をオフにするだけ)。反映(巻きシダの焼き直し)は呼ぶ側で。 */
    public void writeToConfig() {
        OvergrownClientConfig.Client c = OvergrownClientConfig.CLIENT;
        if (this == NONE) {
            c.visualEffects.set(false);
        } else {
            c.visualEffects.set(true);
            c.curledFernDetail.set(curledFern);
            c.atmosphereQuality.set(atmosphere);
            c.glowBloom.set(glowBloom);
            c.clouds.set(clouds);
            c.lightShafts.set(lightShafts);
            c.canopyDapple.set(canopyDapple);
            c.surfaceBump.set(surfaceBump);
            c.simpleAo.set(simpleAo);
            c.hordeLook.set(hordeLook);
        }
        OvergrownClientConfig.SPEC.save();
    }
}

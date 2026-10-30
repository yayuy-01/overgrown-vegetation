package com.yayuy.overgrownvegetation.client;

import com.yayuy.overgrownvegetation.config.CurledFernDetail;
import com.yayuy.overgrownvegetation.config.OvergrownClientConfig;
import net.minecraft.client.OptionInstance;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * バニラの<b>ビデオ設定</b>画面に足す「シダの葉を立体で描く」のトグル
 * ({@link com.yayuy.overgrownvegetation.mixin.client.VideoSettingsScreenMixin} が画面へ差し込む)。
 *
 * <ul>
 *     <li><b>ON</b> — 巻きシダの葉 6 本を、中央の筋で折れた立体の形(葉 1 枚に板 2 枚)で描く({@link CurledFernDetail#FULL}、昔の見た目)。
 *     1 本あたり 86 面。</li>
 *     <li><b>OFF</b> — 葉 6 本を、折れ目の無い 1 枚の板の軽い形で描く({@link CurledFernDetail#REDUCED}。2026-10-15 夜まで「葉 3 本だけを草ごとにランダムに選んで描く」)。
 *     1 本あたり 42 面(前は 48 面)。既定。</li>
 * </ul>
 *
 * <h2>値の置き場は config({@code curledFernDetail})で、options.txt ではない</h2>
 * <p>この項目は<b>既にある設定の顔を変えただけ</b>で、新しい設定を増やしてはいない。
 * 値は {@code curledFernDetail} に書き、そのまま mod の config ファイルに保存される。
 * こうしてあるのは、設定が 2 か所にあって食い違うのを避けるため —— ビデオ設定で切り替えても
 * config を直接編集しても、行き先は同じ 1 つの値。
 *
 * <p><b>{@link CurledFernDetail#SIMPLE} を消さない</b>: この設定は 3 値で、トグルでは
 * FULL と「それ以外」しか表せない。OFF に戻したときは<b>トグルを触る前の値</b>
 * ({@link #offDetail})へ戻すので、config で SIMPLE にしている人が ON→OFF しても
 * REDUCED に落とされない。
 *
 * <h2>切り替えにリソース再読み込みが要らない理由</h2>
 * <p>{@link RandomBoneGroupModel} が「葉 6 本ぜんぶ」と「3 本の組み合わせ 8 通り」の
 * <b>両方を焼いて持っている</b>ので、切り替えはどちらを返すかの分岐だけ。
 * チャンクメッシュの焼き直し({@code levelRenderer.allChanged()})だけで反映される
 * (SIMPLE との行き来だけは焼き直しが要るので、そこは {@link GeoStaticGeometry#applyDetailChange}
 * がリソース再読み込みへ回す)。
 */
public final class CurledFernOption {

    private static final String KEY = "options.overgrown_vegetation.show_all_ferns";

    /**
     * OFF のときに戻す値。ビデオ設定を開いたときの値が FULL 以外なら、それを覚えておく。
     *
     * <p>覚えているのは<b>そのセッションの間だけ</b>。config で SIMPLE にしている状態で
     * ゲームを再起動し、一度も設定画面を開かずに ON→OFF した場合は REDUCED になる
     * (設定画面を開けば {@link #get()} が SIMPLE を読み直すので、そちらが普通の経路)。
     */
    private static CurledFernDetail offDetail = CurledFernDetail.REDUCED;

    @Nullable
    private static OptionInstance<Boolean> instance;

    /**
     * ビデオ設定に並べる項目。画面を開くたびに呼ばれるので、<b>そのとき毎回 config から読み直す</b> ——
     * config ファイルを直接編集された場合に、画面の表示が古い値のまま残らないようにするため。
     */
    public static OptionInstance<Boolean> get() {
        CurledFernDetail current = GeoStaticGeometry.detail();
        if (current != CurledFernDetail.FULL) {
            offDetail = current;
        }
        if (instance == null) {
            instance = OptionInstance.createBoolean(
                    KEY,
                    OptionInstance.cachedConstantTooltip(Component.translatable(KEY + ".tooltip")),
                    current == CurledFernDetail.FULL,
                    CurledFernOption::apply);
        } else {
            // set() は値が変わったときだけ onValueUpdate を呼ぶので、読み直しで apply が走ることはない。
            instance.set(current == CurledFernDetail.FULL);
        }
        return instance;
    }

    private static void apply(boolean showAll) {
        if (!OvergrownClientConfig.SPEC.isLoaded()) {
            return;
        }
        CurledFernDetail next = showAll ? CurledFernDetail.FULL : offDetail;
        if (GeoStaticGeometry.detail() == next) {
            return;
        }
        // set() 自体はイベントを投げずディスクにも書かないので、save() と反映を自分で呼ぶ。
        OvergrownClientConfig.CLIENT.curledFernDetail.set(next);
        OvergrownClientConfig.CLIENT.curledFernDetail.save();
        GeoStaticGeometry.applyDetailChange();
    }

    private CurledFernOption() {
    }
}

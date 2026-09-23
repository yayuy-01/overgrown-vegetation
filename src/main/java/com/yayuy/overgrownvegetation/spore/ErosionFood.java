package com.yayuy.overgrownvegetation.spore;

import com.yayuy.overgrownvegetation.data.ClimaxTable;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.world.entity.player.Player;

/**
 * 浸食度が<b>食料ゲージ</b>に及ぼす効果の計算(2026-09-22)。ユーザー指定の仕様:
 *
 * <ul>
 *     <li><b>上限が減る</b> —— 100% で半分(既定)。</li>
 *     <li><b>減った上限まで満たせば体力は回復する</b> —— バニラは「18 以上」「20 ちょうど」という
 *     固定値で回復を判定しているので、そこを<b>上限に対する割合</b>へ読み替える
 *     ({@code FoodDataErosionMixin})。上限 10 なら 9 以上で自然回復、10 で満腹回復。</li>
 *     <li><b>食べ物の回復量も同じ割合だけ減る</b> —— 上限が半分なら回復量も半分。
 *     つまり<b>ゲージを満たすのに要る食料の数は変わらない</b>。</li>
 *     <li><b>消費する量は変わらない</b> —— 空腹度({@code exhaustion})には一切触らない。
 *     上限だけが縮むので、体感としては「腹が減るのが速くなる」。</li>
 * </ul>
 *
 * <p>ここは<b>共通側</b>に置く(クライアント専用のクラスを参照しない)。値の出どころは
 * サーバーが {@link Erosion#percent}、クライアントは描画側が自分で持っている値を渡す。
 */
public final class ErosionFood {

    /** バニラの食料ゲージの上限。 */
    public static final int VANILLA_MAX_FOOD = 20;

    /** 浸食度 {@code percent} % のときの食料ゲージの上限。最低でも 1 は残す。 */
    public static int maxFood(float percent) {
        ClimaxTable table = DesignData.climax();
        double reduction = table.maxFoodReduction() * table.scale(percent);
        if (reduction <= 0.0D) {
            return VANILLA_MAX_FOOD;
        }
        return Math.max(1, (int) Math.round(VANILLA_MAX_FOOD * (1.0D - reduction)));
    }

    /** そのプレイヤーの食料ゲージの上限(<b>サーバー側</b>。クライアントでは 0% として扱われる)。 */
    public static int maxFood(Player player) {
        return maxFood(Erosion.percent(player));
    }

    /**
     * 自然回復(ゆっくり)が始まる食料ゲージの値。バニラの 18/20 を<b>上限に対する割合</b>で読み替える。
     * 上限が減っていなければバニラと同じ 18 に戻る。
     */
    public static int regenThreshold(float percent) {
        int max = maxFood(percent);
        if (max >= VANILLA_MAX_FOOD) {
            return 18;
        }
        return Math.max(1, (int) Math.round(max * DesignData.climax().regenFoodFraction()));
    }

    /** {@link #regenThreshold(float)} のサーバー側版。 */
    public static int regenThreshold(Player player) {
        return regenThreshold(Erosion.percent(player));
    }

    /** 食べ物の回復量に掛ける倍率。上限の減り方と同じにしてあるので、要る食料の数は変わらない。 */
    public static double restoreFactor(float percent) {
        ClimaxTable table = DesignData.climax();
        return 1.0D - table.foodRestoreReduction() * table.scale(percent);
    }

    /** {@link #restoreFactor(float)} のサーバー側版。 */
    public static double restoreFactor(Player player) {
        return restoreFactor(Erosion.percent(player));
    }

    /**
     * 食料ゲージと飽和度を上限まで切り詰める。{@link Erosion} の毎 tick から呼ぶ。
     *
     * <p>飽和度も一緒に切り詰めるのは、バニラが「飽和度 &le; 食料ゲージ」を前提にしているため
     * ({@code FoodData#add} がそう丸めている)。ここで放っておくと、上限を超える飽和度が残って
     * 満腹回復が止まらなくなる。
     *
     * @return 切り詰めたら true
     */
    public static boolean clamp(Player player) {
        int max = maxFood(player);
        if (max >= VANILLA_MAX_FOOD) {
            return false;
        }
        var food = player.getFoodData();
        boolean changed = false;
        if (food.getFoodLevel() > max) {
            food.setFoodLevel(max);
            changed = true;
        }
        if (food.getSaturationLevel() > max) {
            food.setSaturation(max);
            changed = true;
        }
        return changed;
    }

    private ErosionFood() {
    }
}

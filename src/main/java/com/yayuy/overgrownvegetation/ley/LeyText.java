package com.yayuy.overgrownvegetation.ley;

import java.util.Locale;

/** 地脈の部品の表示用の数の書き方(FE は 3 桁ごとに区切る・割合は整数の %)。 */
public final class LeyText {

    private LeyText() {
    }

    public static String fe(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }

    public static String percent(double ratio) {
        return String.format(Locale.ROOT, "%d", Math.round(ratio * 100.0D));
    }

    public static String one(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }
}

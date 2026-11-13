package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * ワールドごとの難易度の項目(2026-09-25、ユーザー指定)。ワールド作成画面の葉っぱのボタン・ポーズ画面の葉っぱのボタン・
 * {@code /ov difficulty} の 3 か所が、この列挙を回して同じ項目を扱う。
 *
 * <p><b>項目を足すとき</b>は、ここに 1 行と言語ファイルの 2 行(名前と説明)を足し、効かせる場所で
 * {@link WorldDifficultyData#value} を読む。画面・コマンド・保存・通信は列挙を回すだけなので触らなくてよい。
 *
 * <p>ワールドに値が書かれていない項目は {@link #defaultValue()}(config・design の<b>今の</b>値)に従う。
 * だから td.json の上限を直せば、その項目を触っていない既存のワールドにも効く。
 *
 * <p>範囲: 敵の強さの上限 256 はユーザー指定。そのほかの上下限は仮決め(汚染速度は旧 config
 * {@code corruptionSpeedMultiplier} と同じ 0.01〜100、TD は td.json の {@code concurrentCap} と同じ 1〜2000)。
 *
 * <p>難易度ではない切り替え({@link Kind#TOGGLE}、値は 0 / 1)も同じ画面・保存・コマンドに相乗りする: 実験的要素
 * ({@link #EXPERIMENTAL}、2026-10-09)・読み込まれていない範囲への侵食({@link #UNLOADED_SPREAD}、2026-10-10)。
 */
public enum DifficultySetting {
    /**
     * 汚染シダ(と汚染死体ブロック・レイドの汚染の節)の侵食速度の倍率。{@code /ov corruption speed} もこれを書き換える。
     * 既定値は config の {@code corruptionSpeedMultiplier}(全ワールド共通の既定値として残してある)。
     */
    CORRUPTION_SPEED("corruption_speed", Kind.MULTIPLIER, 0.01D, 100.0D),
    /**
     * 読み込まれていない範囲への侵食(2026-10-10、ユーザー指定「読み込まれていない範囲の汚染をありにするかどうかの設定」)。
     * オン(既定 = 今までどおり)なら台帳の広がりがプレイヤーのいない土地も過繁茂にし、本島のエンドもまとめて塗る。
     * オフなら、どちらも<b>読み込まれているチャンクだけ</b>(印を付けるのも塗るのも)。もう付いた印は消さない。
     * 効かせる所は {@link WorldDifficultyData#unloadedSpread} を読む 3 か所(台帳の広がり・ネザーの台帳・過繁茂したエンド)。
     */
    UNLOADED_SPREAD("unloaded_spread", Kind.TOGGLE, 0.0D, 1.0D),
    /**
     * 敵(グループ 1〜10 の Mob・攻城戦ボス)の攻撃力の倍率。生き物へのダメージ(間接攻撃も)と、TD で壁・鉄線・灯り・
     * ターゲットブロックを削る量に掛かる(ユーザー指定 2026-09-25)。{@link EnemyStrengthDamage}。
     */
    ENEMY_STRENGTH("enemy_strength", Kind.MULTIPLIER, 0.01D, 256.0D),
    /** フェーズ進行のポイントの<b>獲得量</b>の倍率(時間・チャンク・Mob・実績のすべて。ユーザー指定 2026-09-25)。 */
    PHASE_POINTS("phase_points", Kind.MULTIPLIER, 0.0D, 256.0D),
    /**
     * 地割れ(フェーズ1の開始)が起きる日。ワールドを作ってからこの日数が経ち、<b>かつ</b>実績の条件
     * ({@link #QUAKE_ADVANCEMENTS})がそろったとき。既定値は phases.json の
     * {@code startWorldAgeTicks} ÷ 24000(今は 3 日)。
     */
    QUAKE_DAY("quake_day", Kind.DAYS, 0.0D, 1000.0D),
    /**
     * 地割れに要る実績の種類数(2026-10-10、ユーザー指定「最初の亀裂発生の条件を n 日と実績 n 個で変えられるように」)。
     * 数えるのはフェーズ0 の間に誰かが解除した、通知の出る実績(バニラ・MOD を問わない。{@link
     * com.yayuy.overgrownvegetation.phase.AdvancementPoints})。0 なら日数だけで起きる。既定値は phases.json の
     * {@code startAdvancements}(今は 6)。上限 1000 は仮決め。
     */
    QUAKE_ADVANCEMENTS("quake_advancements", Kind.COUNT, 0.0D, 1000.0D),
    /** TD イベントで同時に出ている攻め手の上限。既定値は td.json の {@code concurrentCap}(今は 500)。 */
    TD_MAX_MOBS("td_max_mobs", Kind.COUNT, 1.0D, 2000.0D),
    /**
     * 実験的要素(2026-10-09、ユーザー指定「設定画面に『実験的要素』の項目を用意し、オンにしたときだけ出るように」)。
     * 2026-11-12 から新しいネザーの汚染(同盟軍は 10-30 に parked へ)。既定はオフ。効かせ方は {@link ExperimentalFeatures}。
     * 設定の画面(WorldDifficultyScreen)は 10-30 に parked へ行ったので、今は画面から変えられない。
     */
    EXPERIMENTAL("experimental", Kind.TOGGLE, 0.0D, 1.0D);

    /** 値の種類。入力の受け付け方と表示の仕方が変わる。 */
    public enum Kind {
        /** 倍率(小数可。表示は「1.0」「1.5」「256.0」)。 */
        MULTIPLIER,
        /** 日数(小数可。表示は「3」「2.5」)。 */
        DAYS,
        /** 個数(整数だけ)。 */
        COUNT,
        /** オン・オフ(値は 1 / 0。画面は入力欄の代わりにボタン、コマンドは true / false)。 */
        TOGGLE
    }

    /** ゲーム内 1 日。 */
    public static final int TICKS_PER_DAY = 24000;

    private static final Pattern DECIMAL = Pattern.compile("\\d{1,7}(\\.\\d{1,4})?");
    private static final Pattern INTEGER = Pattern.compile("\\d{1,7}");
    /** 既定値と同じとみなす差(画面で既定値を打ち直したときに「ワールドの値」として残さないため)。 */
    private static final double SAME_EPSILON = 1.0E-9D;

    private final String id;
    private final Kind kind;
    private final double min;
    private final double max;

    DifficultySetting(String id, Kind kind, double min, double max) {
        this.id = id;
        this.kind = kind;
        this.min = min;
        this.max = max;
    }

    /** 保存・通信・コマンドで使う名前(小文字のスネークケース)。 */
    public String id() {
        return id;
    }

    public Kind kind() {
        return kind;
    }

    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** 画面・コマンドで出す名前(単位付き)の言語キー。説明は {@link #descriptionKey()}。 */
    public String translationKey() {
        return "gui.overgrown_vegetation.world_difficulty." + id;
    }

    public String descriptionKey() {
        return translationKey() + ".desc";
    }

    /**
     * ワールドに値が無いときの値。サーバーでも、ワールド作成画面(統合サーバーと同じ JVM・同じ config と design)でも同じ式。
     */
    public double defaultValue() {
        return switch (this) {
            case CORRUPTION_SPEED -> clamp(OvergrownVegetationConfig.COMMON.corruptionSpeedMultiplier.get());
            case ENEMY_STRENGTH, PHASE_POINTS -> 1.0D;
            case UNLOADED_SPREAD -> 1.0D; // オン(今までどおり)
            case QUAKE_DAY -> DesignData.phases().startWorldAgeTicks() / (double) TICKS_PER_DAY;
            case QUAKE_ADVANCEMENTS -> DesignData.phases().startAdvancements();
            case TD_MAX_MOBS -> 0.0D; // TD は 2026-10-30 に parked/ へ(前は DesignData.td().concurrentCap())
            case EXPERIMENTAL -> 0.0D; // オフ(GameTest のサーバーだけはオン扱い —— ExperimentalFeatures#enabled)
        };
    }

    /** 範囲に収め、個数なら整数に、オン・オフなら 0 か 1 に丸める。NaN は最小値。 */
    public double clamp(double value) {
        if (Double.isNaN(value)) {
            return min;
        }
        if (kind == Kind.TOGGLE) {
            return value >= 0.5D ? 1.0D : 0.0D;
        }
        double v = Math.max(min, Math.min(max, value));
        return kind == Kind.COUNT ? Math.round(v) : v;
    }

    /** {@code a} と {@code b} を同じ値とみなすか(既定値を打ち直しただけの入力を「変更なし」にする)。 */
    public boolean same(double a, double b) {
        return Math.abs(a - b) <= SAME_EPSILON * Math.max(1.0D, Math.abs(b));
    }

    /**
     * 画面・コマンドの入力を読む。数字と小数点だけ(個数は整数だけ)で、範囲に収まっていなければ NaN。
     * 空欄も NaN(画面では赤字になり「完了」が押せない)。
     */
    public double parse(String text) {
        String s = text == null ? "" : text.trim();
        if (kind == Kind.TOGGLE) {
            return switch (s) {
                case "on", "true", "1" -> 1.0D;
                case "off", "false", "0" -> 0.0D;
                default -> Double.NaN;
            };
        }
        if (!(kind == Kind.COUNT ? INTEGER : DECIMAL).matcher(s).matches()) {
            return Double.NaN;
        }
        double v;
        try {
            v = Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
        return v >= min - SAME_EPSILON && v <= max + SAME_EPSILON ? clamp(v) : Double.NaN;
    }

    /**
     * 画面・チャットに出す値。オン・オフは言語ファイルの「オン」「オフ」、ほかは {@link #format} の数字。
     */
    public Component display(double value) {
        if (kind == Kind.TOGGLE) {
            return value >= 0.5D ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }
        return Component.literal(format(value));
    }

    /** 文字列(倍率は必ず小数点付き、日数と個数は余分な 0 を付けない、オン・オフは on / off)。人に見せるなら {@link #display}。 */
    public String format(double value) {
        if (kind == Kind.TOGGLE) {
            return value >= 0.5D ? "on" : "off";
        }
        if (kind == Kind.COUNT) {
            return Long.toString(Math.round(value));
        }
        String plain = BigDecimal.valueOf(value).setScale(4, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
        if (kind == Kind.MULTIPLIER && plain.indexOf('.') < 0) {
            return plain + ".0";
        }
        return plain;
    }

    @Nullable
    public static DifficultySetting byId(String id) {
        for (DifficultySetting setting : values()) {
            if (setting.id.equals(id)) {
                return setting;
            }
        }
        return null;
    }
}

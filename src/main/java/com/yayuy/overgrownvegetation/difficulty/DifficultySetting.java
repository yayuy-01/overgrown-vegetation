package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.config.OvergrownVegetationConfig;
import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.director.LargeRaids;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.regex.Pattern;

/**
 * ワールドごとの設定の項目(MOD の設定ページ。2026-09-25 に難易度として始まり、2026-11-19 に「設定のページを 1 つにまとめる」
 * ユーザー決定で節・MOD の難易度の段・Mob の強さを足した)。設定のページ({@code client/difficulty/WorldDifficultyScreen})・
 * 保存({@link WorldDifficultyData})・通信({@code network/WorldDifficulty*Payload})は、この列挙を回して同じ項目を扱う。
 *
 * <p><b>項目を足すとき</b>は、ここに 1 行と言語ファイルの 2 行(名前と説明)を足し、効かせる場所で
 * {@link WorldDifficultyData#value} を読む。画面・保存・通信は列挙を回すだけなので触らなくてよい。
 * <b>仕組みが無い項目は足さない</b>(口だけの項目を作らない。11-19 全体管理の決まり)。
 *
 * <p>ワールドに値が書かれていない項目は {@link #defaultValue()}(config・design の<b>今の</b>値)に従う。
 *
 * <p>{@link #tiered()} の項目は MOD の難易度の段({@link ModDifficulty})でまとめて変わる(11-19 ユーザー決定:
 * Mob の強さ 4 項目・フェーズの進み・森の予算の上限と生産量・暴露量のたまる速さ・感染の進みやすさ)。既定値はどれも 100%。
 *
 * <p>{@link #irreversible()} の項目は、途中で変えても<b>すでに起きたことは戻らない</b>(画面に一言出す。11-19)。
 *
 * <p>2026-11-19 に消した項目: {@code td_max_mobs}(TD は 2026-10-30 に parked/ へ。読む所が無い)。保存に残っていても読み捨てる。
 * {@code enemy_strength} は Mob の攻撃力({@link #MOB_ATTACK})へ移した(読み込みで写す)。
 */
public enum DifficultySetting {
    // ------------------------------------------------------------ ② 森の広がり
    /**
     * 汚染シダ(と汚染死体ブロック・レイドの汚染の節)の侵食速度の倍率。{@code /ov corruption speed} もこれを書き換える。
     * 既定値は config の {@code corruptionSpeedMultiplier}(全ワールド共通の既定値として残してある)。段では変えない。
     */
    CORRUPTION_SPEED("corruption_speed", Section.SPREAD, Kind.MULTIPLIER, 0.01D, 100.0D, false, false),
    /** フェーズ進行のポイントの<b>獲得量</b>の倍率(時間・チャンク・Mob・実績・器官のすべて。{@code PhaseData#addPoints})。段で変わる。 */
    PHASE_POINTS("phase_points", Section.SPREAD, Kind.PERCENT, 0.0D, 256.0D, true, false),
    /**
     * 読み込まれていない範囲への侵食(2026-10-10)。オンなら台帳の広がりがプレイヤーのいない土地も過繁茂にし、本島のエンドもまとめて塗る。
     * オフなら、どちらも読み込まれているチャンクだけ。もう付いた印は消さない。
     */
    UNLOADED_SPREAD("unloaded_spread", Section.SPREAD, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /**
     * 始まりの猶予(日数)= 地割れ(フェーズ1の開始)が起きる日。ワールドを作ってからこの日数が経ち、<b>かつ</b>実績の条件
     * ({@link #QUAKE_ADVANCEMENTS})がそろったとき。既定値は phases.json の {@code startWorldAgeTicks} ÷ 24000。
     * 11-19 の「始まりの猶予」はこれと同じ物(PhaseData の開始判定が読む 2 つ)なので、新しい項目は作らず名前だけ変えた。
     */
    QUAKE_DAY("quake_day", Section.SPREAD, Kind.DAYS, 0.0D, 1000.0D, false, true),
    /** 始まりの猶予(実績の数)。0 なら日数だけ。既定値は phases.json の {@code startAdvancements}。 */
    QUAKE_ADVANCEMENTS("quake_advancements", Section.SPREAD, Kind.COUNT, 0.0D, 1000.0D, false, true),
    /** フェーズの上限(11-19 第 2 便)。これより上のフェーズへは進まない({@code PhaseData#tryAdvance})。下げても今のフェーズは戻らない。 */
    PHASE_CAP("phase_cap", Section.SPREAD, Kind.COUNT, 1.0D, 10.0D, false, true),
    /** 建築ブロックも覆うか(置かれた建築へ這い上がる汚染 {@code BuildCorrosion})。既定は COMMON の {@code buildCorrosion}。 */
    BUILD_CORROSION("build_corrosion", Section.SPREAD, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /** ネザー・エンドへ渡るか(ポータルの枠からの越境 {@code PortalCorruption})。もう渡った森は残る。 */
    PORTAL_CROSSING("portal_crossing", Section.SPREAD, Kind.TOGGLE, 0.0D, 1.0D, false, true),
    /** 汚染しない場所 = オーバーワールドのスポーン地点のまわりの半径(ブロック。0 = なし)。{@code CorruptionRegistry#canCorrupt}。もう汚染されたブロックは戻らない。 */
    SPAWN_SAFE_RADIUS("spawn_safe_radius", Section.SPREAD, Kind.COUNT, 0.0D, 10000.0D, false, true),

    // ------------------------------------------------------------ ③ 森の力
    /** 森の予算(バイオマス)の生産量 = 貯まる速さの倍率({@code ForestDirector#accrue})。段で変わる。 */
    FOREST_INCOME("forest_income", Section.FOREST, Kind.PERCENT, 0.0D, 256.0D, true, false),
    /** 森の予算(バイオマス)の貯蓄量の上限の倍率({@code ForestDirector#cap})。生産量とは別に掛ける(上限は収入から作るので二重にしない)。段で変わる。 */
    FOREST_CAP("forest_cap", Section.FOREST, Kind.PERCENT, 0.01D, 256.0D, true, false),
    /** 苗床が Mob を生む速さ(問い合わせの間 {@code NurseryParasitized#hatchAskInterval} を割る)。孵るかは森の予算しだい。 */
    NURSERY_SPEED("nursery_speed", Section.FOREST, Kind.PERCENT, 0.1D, 10.0D, false, false),
    /** 森の器官が生えるか({@code OrganPlanner})。オフでも今生えている器官は残す(11-19 ユーザー決定)。 */
    ORGANS("organs", Section.FOREST, Kind.TOGGLE, 0.0D, 1.0D, false, true),
    /** 器官の数の上限の倍率(organs.json の maxOrgans・小型の上限に掛ける)。減らしても今ある器官は消さない。 */
    ORGAN_COUNT("organ_count", Section.FOREST, Kind.PERCENT, 0.0D, 10.0D, false, false),
    /** 巨大樹が生えるか(世界の生成と苗木 {@code GiantTreeFeature#place})。もう生えた巨大樹は残る。 */
    GIANT_TREES("giant_trees", Section.FOREST, Kind.TOGGLE, 0.0D, 1.0D, false, true),

    // ------------------------------------------------------------ ④ 襲撃(raid-scale の口 RaidScales.Knobs へ写す = RaidSettings)
    /** 襲撃があるか(送り込み・貯めた襲撃・大きな襲撃とも。最初の襲撃も)。 */
    RAIDS("raids", Section.RAID, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /** 襲撃の頻度(200% = 機会の間が半分)。 */
    RAID_FREQUENCY("raid_frequency", Section.RAID, Kind.PERCENT, 0.1D, 10.0D, false, false),
    /** 小(ふだんの送り込み)で来る数の倍率。 */
    RAID_COUNT_SMALL("raid_count_small", Section.RAID, Kind.PERCENT, 0.01D, 10.0D, false, false),
    /** 中(少し貯めた襲撃)で来る数の倍率。 */
    RAID_COUNT_MEDIUM("raid_count_medium", Section.RAID, Kind.PERCENT, 0.01D, 10.0D, false, false),
    /** 大(長く貯めた襲撃)で来る数の倍率。 */
    RAID_COUNT_LARGE("raid_count_large", Section.RAID, Kind.PERCENT, 0.01D, 10.0D, false, false),
    /** 中の同時の上限。既定は director.json の raidScale の中の maxAlive(40)。 */
    RAID_MAX_ALIVE_MEDIUM("raid_max_alive_medium", Section.RAID, Kind.COUNT, 1.0D, 500.0D, false, false),

    // ------------------------------------------------------------ ④ 襲撃
    /**
     * 大きな襲撃で同時に出ている攻め手の上限(2026-11-14 に COMMON の設定として作り、11-19 にワールドごとへ移した)。
     * 既定値は COMMON の {@code largeRaidMaxAlive}(−1 なら director.json の {@code largeRaid.maxAlive})。
     */
    LARGE_RAID_MAX_ALIVE("large_raid_max_alive", Section.RAID, Kind.COUNT, LargeRaids.MAX_ALIVE_MIN, LargeRaids.MAX_ALIVE_MAX, false, false),
    /** 小の 1 回の合計の上限(0 = 上限なし)。既定は director.json の assault.maxWave(40)。 */
    RAID_MAX_TOTAL_SMALL("raid_max_total_small", Section.RAID, Kind.COUNT, 0.0D, 100000.0D, false, false),
    /** 中の 1 回の合計の上限(0 = 上限なし)。既定は raidScale の中の maxTotal(80)。 */
    RAID_MAX_TOTAL_MEDIUM("raid_max_total_medium", Section.RAID, Kind.COUNT, 0.0D, 100000.0D, false, false),
    /** 大の 1 回の合計の上限(0 = 上限なし = 設計どおり)。 */
    RAID_MAX_TOTAL_LARGE("raid_max_total_large", Section.RAID, Kind.COUNT, 0.0D, 100000.0D, false, false),
    /** 襲撃で壁や地形を壊すか({@code BreachGoal}。ゲームルール mobGriefing がオフなら、こちらがオンでも壊さない)。 */
    RAID_BREACH("raid_breach", Section.RAID, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /** 大きな襲撃の前触れ(森の湧きが止まって静かになる日)があるか。オフならすぐ来る({@code RaidScales#omenDays})。 */
    RAID_OMEN("raid_omen", Section.RAID, Kind.TOGGLE, 0.0D, 1.0D, false, false),

    // ------------------------------------------------------------ ⑤ Mob(強さ 4 項目。Mob ごとの詳細は WorldDifficultyData#mobOverrides)
    /** Mob の体力(最大体力の属性に掛ける)。{@link MobStrength}。 */
    MOB_HEALTH("mob_health", Section.MOBS, Kind.PERCENT, 0.01D, 256.0D, true, false),
    /** Mob の攻撃力(生き物へのダメージに掛ける。矢・爆発などの間接攻撃も)。上限 256 倍は前の「敵の強さ」のユーザー指定。{@link MobStrength}。 */
    MOB_ATTACK("mob_attack", Section.MOBS, Kind.PERCENT, 0.01D, 256.0D, true, false),
    /** Mob の防御力(防具の属性に掛ける。もとが 0 の Mob は 0 のまま。バニラの上限 30)。{@link MobStrength}。 */
    MOB_DEFENSE("mob_defense", Section.MOBS, Kind.PERCENT, 0.01D, 256.0D, true, false),
    /** Mob の強靭(防具の強靭さの属性に掛ける。もとが 0 の Mob は 0 のまま。バニラの上限 20)。{@link MobStrength}。 */
    MOB_TOUGHNESS("mob_toughness", Section.MOBS, Kind.PERCENT, 0.01D, 256.0D, true, false),
    /** 森の縁・地下に自然に湧く量(spawns.json の caps・underCap に掛ける。{@code EnemySpawns})。 */
    MOB_SPAWN_AMOUNT("mob_spawn_amount", Section.MOBS, Kind.PERCENT, 0.0D, 10.0D, false, false),
    /** 動物や村人の感染(感染で死んだ生き物が寄生された姿・萌芽体になる。{@code G1Infection#isInfectionDeath})。プレイヤーには関係しない。 */
    MOB_INFECTION("mob_infection", Section.MOBS, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /** G5 の適応(よく使われた攻撃に強くなる。{@code Adaptation#cap})。オフでも記録は消さない。 */
    ADAPTATION("adaptation", Section.MOBS, Kind.TOGGLE, 0.0D, 1.0D, false, false),

    // ------------------------------------------------------------ ⑥ サバイバル
    /** プレイヤーの胞子の暴露量のたまる速さ(息で吸う分・部屋への染み込み・傷や技で入る分)。段で変わる。 */
    EXPOSURE_RATE("exposure_rate", Section.SURVIVAL, Kind.PERCENT, 0.0D, 256.0D, true, false),
    /** プレイヤーの感染の進みやすさ(段階 1〜10 の進み。{@code SporeInfectionEffects#advance})。段で変わる。 */
    INFECTION_RATE("infection_rate", Section.SURVIVAL, Kind.PERCENT, 0.0D, 256.0D, true, false),
    /** 疲労(オフで疲労がたまらず、効き目も無い。{@code FatigueTracker})。既定は spore_field.json の fatigue.enabled。 */
    FATIGUE("fatigue", Section.SURVIVAL, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /**
     * 死んでも感染が残る(11-19 第 3 便のユーザー決定)。オンなら、死んで復活したとき感染の段階と段階の進みを戻す
     * ({@code effect/InfectionOnDeath})。暴露量・疲労は 0 から。既定はオフ(今までどおり死ぬと治る)。
     * <b>段で変わる</b>: Nightmare だけオン、Easy・Normal・Hard はオフ({@link #tierValue})。
     */
    KEEP_INFECTION("keep_infection", Section.SURVIVAL, Kind.TOGGLE, 0.0D, 1.0D, true, false),
    /**
     * 押し返す道具の効き(11-19 第 3 便のユーザー決定「範囲は変えず使い減りを変える」)。灰と石灰の帯が止められる回数 × %、
     * 地脈の杭の FE の使う量 ÷ %、胞子除去の換気機のフィルターの詰まり ÷ %。範囲・焼け跡・枯らし薬・マスクには掛けない。段では変えない。
     */
    PUSHBACK_EFFICIENCY("pushback_efficiency", Section.SURVIVAL, Kind.PERCENT, 0.1D, 10.0D, false, false),

    // ------------------------------------------------------------ ⑦ 世界
    /** ワールドイベントの頻度(200% = 間が半分。{@code WorldEventSchedule#gapDays})。 */
    EVENT_FREQUENCY("event_frequency", Section.WORLD, Kind.PERCENT, 0.1D, 10.0D, false, false),
    /** 地割れ(フェーズ 1 の始まりの地震 {@code EarthquakeEvent#trigger})。既定は COMMON の quakeEnabled。オフでもフェーズは始まる。 */
    QUAKE("quake", Section.WORLD, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /** 地形を壊す要素(地割れが地面を裂く {@code EarthquakeFissure})。オフなら揺れるだけ。襲撃が壁を壊すかは襲撃の節。 */
    TERRAIN_DAMAGE("terrain_damage", Section.WORLD, Kind.TOGGLE, 0.0D, 1.0D, false, false),
    /**
     * 実験的要素(2026-10-09)。2026-11-12 から新しいネザーの汚染。既定はオフ。効かせ方は {@link ExperimentalFeatures}。
     */
    EXPERIMENTAL("experimental", Section.WORLD, Kind.TOGGLE, 0.0D, 1.0D, false, false),

    // ------------------------------------------------------------ ⑧ 重さ
    /** 遠くの群れを本物の Mob にするときの、本物の上限(perf.json の realCap)。 */
    REAL_CAP("real_cap", Section.PERF, Kind.COUNT, 10.0D, 2000.0D, false, false),
    /** 遠くの群れ(身代わりで描く数)の上限(perf.json の virtualCap)。 */
    VIRTUAL_CAP("virtual_cap", Section.PERF, Kind.COUNT, 0.0D, 20000.0D, false, false),
    /** 遠くの群れを使うか(オフなら群れは全部本物の Mob)。既定は COMMON の virtualHordeEnabled。 */
    VIRTUAL_HORDES("virtual_hordes", Section.PERF, Kind.TOGGLE, 0.0D, 1.0D, false, false);

    /** 設定のページの節(11-19 全体管理の一覧の番号順。今は項目のある節だけが画面に出る)。 */
    public enum Section {
        SPREAD("spread"), FOREST("forest"), RAID("raid"), MOBS("mobs"), SURVIVAL("survival"), WORLD("world"), PERF("perf");

        private final String id;

        Section(String id) {
            this.id = id;
        }

        public String translationKey() {
            return "gui.overgrown_vegetation.world_difficulty.section." + id;
        }
    }

    /** 値の種類。入力の受け付け方と表示の仕方が変わる。 */
    public enum Kind {
        /** 倍率(小数可。表示は「1.0」「1.5」「256.0」)。 */
        MULTIPLIER,
        /** 割合。<b>値は倍率で持ち</b>、画面では %(「100」「150」「70」)で読み書きする。 */
        PERCENT,
        /** 日数(小数可。表示は「3」「2.5」)。 */
        DAYS,
        /** 個数(整数だけ)。 */
        COUNT,
        /** オン・オフ(値は 1 / 0。画面は入力欄の代わりにボタン)。 */
        TOGGLE
    }

    /** ゲーム内 1 日。 */
    public static final int TICKS_PER_DAY = 24000;

    private static final Pattern DECIMAL = Pattern.compile("\\d{1,7}(\\.\\d{1,4})?");
    private static final Pattern INTEGER = Pattern.compile("\\d{1,7}");
    /** 既定値と同じとみなす差(画面で既定値を打ち直したときに「ワールドの値」として残さないため)。 */
    private static final double SAME_EPSILON = 1.0E-9D;

    private final String id;
    private final Section section;
    private final Kind kind;
    private final double min;
    private final double max;
    private final boolean tiered;
    private final boolean irreversible;

    DifficultySetting(String id, Section section, Kind kind, double min, double max, boolean tiered, boolean irreversible) {
        this.id = id;
        this.section = section;
        this.kind = kind;
        this.min = min;
        this.max = max;
        this.tiered = tiered;
        this.irreversible = irreversible;
    }

    /** 保存・通信で使う名前(小文字のスネークケース)。 */
    public String id() {
        return id;
    }

    public Section section() {
        return section;
    }

    public Kind kind() {
        return kind;
    }

    /** 値(倍率)としての下限。%の項目は画面では 100 倍して見せる。 */
    public double min() {
        return min;
    }

    public double max() {
        return max;
    }

    /** MOD の難易度の段でまとめて変わる項目か。 */
    public boolean tiered() {
        return tiered;
    }

    /**
     * 段 {@code tier} でこの項目が取る値。倍率の項目は段の倍率、「死んでも感染が残る」は Nightmare だけオン(11-19 ユーザー指示)。
     * {@code tierValue(NORMAL)} はワールドに値が無いときと同じ値(Normal は「何も書かれていない」と同じ)。カスタムは NaN。
     */
    public double tierValue(ModDifficulty tier) {
        if (tier == ModDifficulty.CUSTOM) return Double.NaN;
        if (this == KEEP_INFECTION) return tier == ModDifficulty.NIGHTMARE ? 1.0D : 0.0D;
        return tier.factor();
    }

    /** 途中で変えても、すでに起きたことは戻らない項目か(画面に一言)。 */
    public boolean irreversible() {
        return irreversible;
    }

    /** Mob の強さの 4 項目のどれか(Mob ごとの詳細を持てる)。 */
    public boolean mobStat() {
        return section == Section.MOBS;
    }

    /** Mob の強さの 4 項目(画面の列の順)。 */
    public static final DifficultySetting[] MOB_STATS = {MOB_HEALTH, MOB_ATTACK, MOB_DEFENSE, MOB_TOUGHNESS};

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
            case PHASE_POINTS, FOREST_INCOME, FOREST_CAP, MOB_HEALTH, MOB_ATTACK, MOB_DEFENSE, MOB_TOUGHNESS,
                 EXPOSURE_RATE, INFECTION_RATE -> 1.0D;
            case UNLOADED_SPREAD -> 1.0D; // オン(今までどおり)
            case QUAKE_DAY -> DesignData.phases().startWorldAgeTicks() / (double) TICKS_PER_DAY;
            case QUAKE_ADVANCEMENTS -> DesignData.phases().startAdvancements();
            case LARGE_RAID_MAX_ALIVE -> LargeRaids.configuredMaxAlive();
            case EXPERIMENTAL -> 0.0D; // オフ(GameTest のサーバーだけはオン扱い —— ExperimentalFeatures#enabled)
            case PHASE_CAP -> com.yayuy.overgrownvegetation.data.PhaseTable.MAX_PHASE;
            case BUILD_CORROSION -> OvergrownVegetationConfig.COMMON.buildCorrosion.get() ? 1.0D : 0.0D;
            case PORTAL_CROSSING, ORGANS, GIANT_TREES, RAIDS, RAID_BREACH, RAID_OMEN, MOB_INFECTION, ADAPTATION, TERRAIN_DAMAGE -> 1.0D;
            case SPAWN_SAFE_RADIUS -> 0.0D;
            case NURSERY_SPEED, ORGAN_COUNT, RAID_FREQUENCY, RAID_COUNT_SMALL, RAID_COUNT_MEDIUM, RAID_COUNT_LARGE,
                 MOB_SPAWN_AMOUNT, EVENT_FREQUENCY -> 1.0D;
            case RAID_MAX_ALIVE_MEDIUM -> RaidSettings.designMaxAlive(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.MEDIUM, 40);
            case RAID_MAX_TOTAL_SMALL -> DesignData.director().assault.maxWave;
            case RAID_MAX_TOTAL_MEDIUM -> RaidSettings.designMaxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.MEDIUM);
            case RAID_MAX_TOTAL_LARGE -> RaidSettings.designMaxTotal(com.yayuy.overgrownvegetation.director.RaidScales.RaidScale.LARGE);
            case KEEP_INFECTION -> 0.0D; // オフ(死ぬと治る。今までどおり)
            case PUSHBACK_EFFICIENCY -> 1.0D;
            case FATIGUE -> DesignData.sporeField().fatigue().enabled() ? 1.0D : 0.0D;
            case QUAKE -> OvergrownVegetationConfig.COMMON.quakeEnabled.get() ? 1.0D : 0.0D;
            case REAL_CAP -> DesignData.perf().virtualRealCap;
            case VIRTUAL_CAP -> DesignData.perf().virtualCap;
            case VIRTUAL_HORDES -> OvergrownVegetationConfig.COMMON.virtualHordeEnabled.get() ? 1.0D : 0.0D;
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
     * 画面の入力を読んで<b>値(倍率)</b>を返す。数字と小数点だけ(個数は整数だけ)で、範囲に収まっていなければ NaN。
     * % の項目は「150」を 1.5 として読む。空欄も NaN。
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
        if (kind == Kind.PERCENT) {
            v /= 100.0D;
        }
        return v >= min - SAME_EPSILON && v <= max + SAME_EPSILON ? clamp(v) : Double.NaN;
    }

    /** 画面・チャットに出す値(% の項目は「150%」)。 */
    public Component display(double value) {
        if (kind == Kind.TOGGLE) {
            return value >= 0.5D ? CommonComponents.OPTION_ON : CommonComponents.OPTION_OFF;
        }
        return Component.literal(kind == Kind.PERCENT ? format(value) + "%" : format(value));
    }

    /**
     * 入力欄に入れる文字列(倍率は必ず小数点付き、% は 100 倍して余分な 0 を付けない、日数と個数は余分な 0 を付けない、
     * オン・オフは on / off)。{@link #parse} で読み戻せる。
     */
    public String format(double value) {
        if (kind == Kind.TOGGLE) {
            return value >= 0.5D ? "on" : "off";
        }
        if (kind == Kind.COUNT) {
            return Long.toString(Math.round(value));
        }
        double shown = kind == Kind.PERCENT ? value * 100.0D : value;
        String plain = BigDecimal.valueOf(shown).setScale(kind == Kind.PERCENT ? 2 : 4, java.math.RoundingMode.HALF_UP)
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

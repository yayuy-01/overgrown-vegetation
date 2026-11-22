package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.data.DesignData;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.OptionalDouble;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * ワールドごとの設定(MOD の設定ページの値。2026-09-25 に難易度として始まり、11-19 に 1 つのページへまとめた)。
 * オーバーワールドの {@code data/} に 1 つ。
 *
 * <p><b>持っているのは「このワールドの値」だけ</b>({@link DifficultyValues})。書かれていない項目は
 * {@link DifficultySetting#defaultValue()}(config・design の今の値)に従う。
 *
 * <p>値の入り口は 3 つ:
 * <ul>
 *     <li>ワールド作成画面の葉っぱのボタン — 「ワールド作成」を押した瞬間、サーバーが起動する前の新しいワールドのフォルダへ
 *     {@link #writeInitial} が直接書く。</li>
 *     <li>遊んでいる途中の設定のページ(ポーズ画面の葉っぱのボタン・見た目の設定の「ゲーム内容…」)— {@link WorldDifficultySync}
 *     (OP か 1 人プレイのホストだけが変えられる)。</li>
 *     <li>{@code /ov corruption speed}(侵食速度だけ)。</li>
 * </ul>
 *
 * <p>効かせる場所は下の static の口から読む。
 */
public final class WorldDifficultyData extends OvSavedData {

    public static final String ID = "overgrown_vegetation_world_difficulty";

    private static final Factory<WorldDifficultyData> FACTORY =
            new Factory<>(WorldDifficultyData::new, WorldDifficultyData::load);

    private final DifficultyValues values = new DifficultyValues();

    private WorldDifficultyData() {
    }

    /** 最後に引いたサーバーとその値(汚染の判定のように 1 tick に何千回も読む所があるので、保存データの表を毎回引かない)。 */
    private record Cached(MinecraftServer server, WorldDifficultyData data) {
    }

    @javax.annotation.Nullable
    private static volatile Cached cached;

    public static WorldDifficultyData get(MinecraftServer server) {
        Cached c = cached;
        if (c != null && c.server == server) return c.data;
        WorldDifficultyData data = readFrom(server.overworld().getDataStorage());
        cached = new Cached(server, data);
        return data;
    }

    /** この項目の今の値(ワールドの値、無ければ既定値)。 */
    public static double of(MinecraftServer server, DifficultySetting setting) {
        return get(server).value(setting);
    }

    /** オン・オフの項目がオンか。 */
    public static boolean on(MinecraftServer server, DifficultySetting setting) {
        return get(server).value(setting) >= 0.5D;
    }

    /** サーバーを引数で持たない所(static の口)から読むとき。動いているサーバーが無ければ既定値。 */
    public static double ofCurrent(DifficultySetting setting) {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server != null ? of(server, setting) : setting.defaultValue();
    }

    public static boolean onCurrent(DifficultySetting setting) {
        return ofCurrent(setting) >= 0.5D;
    }

    /**
     * 世界の生成の並列スレッドから読むとき: 主スレッドでなく、まだ一度も読んでいなければ既定値(保存データの表は主スレッドの物なので、
     * 並列スレッドから開けない。起動の途中のスポーン周りの生成がこれに当たる)。
     */
    public static boolean onFromAnyThread(MinecraftServer server, DifficultySetting setting) {
        return ofFromAnyThread(server, setting) >= 0.5D;
    }

    public static double ofFromAnyThread(MinecraftServer server, DifficultySetting setting) {
        Cached c = cached;
        if (c != null && c.server == server) return c.data.value(setting);
        if (server.isSameThread()) return of(server, setting);
        return setting.defaultValue();
    }

    /** {@link #get} の中身。GameTest が作成画面の書いたファイルを別の置き場から読み直すのにも使う。 */
    public static WorldDifficultyData readFrom(DimensionDataStorage storage) {
        return storage.computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    // ------------------------------------------------------------------ 効かせる場所から読む口

    /** 汚染シダ・汚染死体ブロック・レイドの汚染の節の侵食速度の倍率。 */
    public static double corruptionSpeed(MinecraftServer server) {
        return get(server).value(DifficultySetting.CORRUPTION_SPEED);
    }

    /** フェーズ進行のポイントの獲得量の倍率(MOD の難易度の段で変わる)。 */
    public static double phasePointMultiplier(MinecraftServer server) {
        return get(server).value(DifficultySetting.PHASE_POINTS);
    }

    /** 森の予算の生産量(貯まる速さ)の倍率(段で変わる)。 */
    public static double forestIncome(MinecraftServer server) {
        return get(server).value(DifficultySetting.FOREST_INCOME);
    }

    /** 森の予算の貯蓄量の上限の倍率(段で変わる)。 */
    public static double forestCap(MinecraftServer server) {
        return get(server).value(DifficultySetting.FOREST_CAP);
    }

    /** プレイヤーの暴露量のたまる速さの倍率(段で変わる)。 */
    public static double exposureRate(MinecraftServer server) {
        return get(server).value(DifficultySetting.EXPOSURE_RATE);
    }

    /**
     * 押し返す道具の効き(倍率。11-19 第 3 便)。帯の回数 × これ、杭の FE と換気機のフィルターの詰まり ÷ これ。
     * サーバーを持たない static の口(換気機の詰まりの式)からも読むので、動いているサーバーが無ければ 1、並列スレッドからは覚えた値。
     */
    public static double pushbackEfficiency() {
        MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        return server == null ? 1.0D : ofFromAnyThread(server, DifficultySetting.PUSHBACK_EFFICIENCY);
    }

    /** プレイヤーの感染の進みやすさの倍率(段で変わる)。 */
    public static double infectionRate(MinecraftServer server) {
        return get(server).value(DifficultySetting.INFECTION_RATE);
    }

    /** 大きな襲撃で同時に出ている攻め手の上限。ワールドの値が無ければ COMMON の設定(−1 なら director.json)。 */
    public static int largeRaidMaxAlive(MinecraftServer server) {
        return (int) Math.round(get(server).value(DifficultySetting.LARGE_RAID_MAX_ALIVE));
    }

    /** この Mob(id = {@code namespace:path})のこの強さの項目の倍率 = 詳細、無ければ全体の %。 */
    public static double mobStat(MinecraftServer server, String mobId, DifficultySetting stat) {
        return get(server).values.mobValue(mobId, stat);
    }

    /**
     * フェーズ1(地割れ = 始まりの猶予の終わり)が始まれるワールドの経過時刻(tick)。ワールドの値が無ければ phases.json の
     * {@code startWorldAgeTicks} そのもの(日に換算して丸めない)。
     */
    public static long phase1StartTicks(MinecraftServer server) {
        OptionalDouble days = get(server).override(DifficultySetting.QUAKE_DAY);
        return days.isPresent()
                ? Math.round(days.getAsDouble() * DifficultySetting.TICKS_PER_DAY)
                : DesignData.phases().startWorldAgeTicks();
    }

    /** フェーズ1(地割れ)に要る実績の種類数。ワールドの値が無ければ phases.json の {@code startAdvancements}。 */
    public static int phase1Advancements(MinecraftServer server) {
        return (int) Math.round(get(server).value(DifficultySetting.QUAKE_ADVANCEMENTS));
    }

    /** 読み込まれていないチャンクへも侵食を広げてよいか(2026-10-10、既定はオン)。 */
    public static boolean unloadedSpread(MinecraftServer server) {
        return get(server).value(DifficultySetting.UNLOADED_SPREAD) >= 0.5D;
    }

    // ------------------------------------------------------------------ 値

    /** この項目の今の値(ワールドの値、無ければ既定値)。 */
    public double value(DifficultySetting setting) {
        return values.valueOr(setting, setting.defaultValue());
    }

    /** ワールドの値(無ければ空)。 */
    public OptionalDouble override(DifficultySetting setting) {
        return values.override(setting);
    }

    public boolean isOverridden(DifficultySetting setting) {
        return values.override(setting).isPresent();
    }

    /** 項目ごとのワールドの値の写し(Mob ごとの詳細は入らない。丸ごとは {@link #snapshot})。 */
    public Map<DifficultySetting, Double> overrides() {
        java.util.EnumMap<DifficultySetting, Double> out = new java.util.EnumMap<>(DifficultySetting.class);
        out.putAll(values.overrides());
        return out;
    }

    /** ワールドの値の丸ごとの写し(書き換えても元は変わらない)。 */
    public DifficultyValues snapshot() {
        return values.copy();
    }

    /** 今の MOD の難易度の段(保存はしない。値から求める)。 */
    public ModDifficulty tier() {
        return values.tier();
    }

    /** Mob の強さの項目なら全体の % として書き、詳細のその項目を消す(11-19 ユーザー決定)。 */
    public void set(DifficultySetting setting, double value) {
        if (setting.mobStat()) values.setGlobalMobStat(setting, value);
        else values.set(setting, value);
        setDirty();
    }

    public void reset(DifficultySetting setting) {
        set(setting, Double.NaN);
    }

    /** Mob ごとの詳細を書く({@code NaN} で消す)。 */
    public void setMob(String mobId, DifficultySetting stat, double value) {
        values.setMob(mobId, stat, value);
        setDirty();
    }

    /** 段を選ぶ。 */
    public void applyTier(ModDifficulty tier) {
        values.applyTier(tier);
        setDirty();
    }

    /** 項目ごとのワールドの値をまるごと置き換える(入っていない項目は既定値に戻る)。Mob ごとの詳細はそのまま。 */
    public void replaceAll(Map<DifficultySetting, Double> overrides) {
        DifficultyValues next = values.copy();
        for (DifficultySetting s : DifficultySetting.values()) next.reset(s);
        overrides.forEach(next::set);
        values.copyFrom(next);
        setDirty();
    }

    /** 詳細も含めて丸ごと置き換える。 */
    public void replaceAll(DifficultyValues next) {
        values.copyFrom(next);
        setDirty();
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag saved = values.save();
        for (String key : saved.getAllKeys()) {
            tag.put(key, saved.get(key));
        }
        return tag;
    }

    private static WorldDifficultyData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldDifficultyData data = new WorldDifficultyData();
        data.values.copyFrom(DifficultyValues.load(tag));
        return data;
    }

    /**
     * ワールド作成画面から: 「ワールド作成」を押した直後、<b>サーバーが起動する前</b>の新しいワールドのフォルダへ
     * 最初の値を書く。形式は {@link SavedData#save(java.io.File, HolderLookup.Provider)} と同じ
     * ({@code data} と {@code DataVersion} の入れ物)なので、起動したサーバーは {@link #get} でふつうに読み込む。
     *
     * @param overworldFolder オーバーワールドの保存先(= ワールドのフォルダ)。この下の {@code data/} に書く
     * @param initial         ワールドの値(空なら何も書かない)
     */
    public static void writeInitial(Path overworldFolder, DifficultyValues initial) throws IOException {
        if (initial.isEmpty()) {
            return;
        }
        WorldDifficultyData data = new WorldDifficultyData();
        data.values.copyFrom(initial);
        Path dataFolder = overworldFolder.resolve("data");
        Files.createDirectories(dataFolder);
        // SavedData#save(File, …) と同じ形を、その場で書き切る(2026-10-20。NeoForge 21.1.2xx の SavedData#save は入出力のスレッドで
        // 後から書くので、すぐ起動するサーバーが書き終わる前に読み、画面で決めた値が黙って既定値に戻りうる)。
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), RegistryAccess.EMPTY));
        NbtUtils.addCurrentDataVersion(root);
        IOUtilities.writeNbtCompressed(root, dataFolder.resolve(ID + ".dat"));
    }
}

package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.td.TdTable;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.neoforged.neoforge.common.IOUtilities;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * ワールドごとの難易度(2026-09-25、ユーザー指定)。オーバーワールドの {@code data/} に 1 つ。
 *
 * <p><b>持っているのは「このワールドの値」だけ</b>。書かれていない項目は {@link DifficultySetting#defaultValue()}
 * (config・design の今の値)に従う。画面で既定値のまま触らなかった項目・{@code reset} した項目は書かない。
 *
 * <p>値の入り口は 3 つ:
 * <ul>
 *     <li>ワールド作成画面の葉っぱのボタン — 「ワールド作成」を押した瞬間、サーバーが起動する前の新しいワールドのフォルダへ
 *     {@link #writeInitial} が直接書く(統合サーバーはふつうに {@link #get} で読み込む)。</li>
 *     <li>ポーズ画面の葉っぱのボタン — {@link WorldDifficultySync}(OP か 1 人プレイのホストだけが変えられる)。</li>
 *     <li>{@code /ov corruption speed}(侵食速度だけ。{@code /ov difficulty} は 2026-10-10 に削除、ほかの項目は葉っぱのボタンの画面)。</li>
 * </ul>
 *
 * <p>効かせる場所は下の static の口から読む(汚染速度・敵の強さ・ポイントの倍率・フェーズ1の時刻・TD の上限)。
 */
public final class WorldDifficultyData extends SavedData {

    public static final String ID = "overgrown_vegetation_world_difficulty";

    private static final Factory<WorldDifficultyData> FACTORY =
            new Factory<>(WorldDifficultyData::new, WorldDifficultyData::load);

    private final EnumMap<DifficultySetting, Double> overrides = new EnumMap<>(DifficultySetting.class);

    private WorldDifficultyData() {
    }

    public static WorldDifficultyData get(MinecraftServer server) {
        return readFrom(server.overworld().getDataStorage());
    }

    /** {@link #get} の中身。GameTest が作成画面の書いたファイルを別の置き場から読み直すのにも使う。 */
    public static WorldDifficultyData readFrom(DimensionDataStorage storage) {
        return storage.computeIfAbsent(FACTORY, ID);
    }

    // ------------------------------------------------------------------ 効かせる場所から読む口

    /** 汚染シダ・汚染死体ブロック・レイドの汚染の節の侵食速度の倍率。 */
    public static double corruptionSpeed(MinecraftServer server) {
        return get(server).value(DifficultySetting.CORRUPTION_SPEED);
    }

    /** 敵の攻撃力の倍率。 */
    public static double enemyStrength(MinecraftServer server) {
        return get(server).value(DifficultySetting.ENEMY_STRENGTH);
    }

    /** フェーズ進行のポイントの獲得量の倍率。 */
    public static double phasePointMultiplier(MinecraftServer server) {
        return get(server).value(DifficultySetting.PHASE_POINTS);
    }

    /**
     * フェーズ1(地割れ)が始まれるワールドの経過時刻(tick)。ワールドの値が無ければ phases.json の
     * {@code startWorldAgeTicks} そのもの(日に換算して丸めない)。
     */
    public static long phase1StartTicks(MinecraftServer server) {
        OptionalDouble days = get(server).override(DifficultySetting.QUAKE_DAY);
        return days.isPresent()
                ? Math.round(days.getAsDouble() * DifficultySetting.TICKS_PER_DAY)
                : DesignData.phases().startWorldAgeTicks();
    }

    /**
     * フェーズ1(地割れ)に要る実績の種類数(2026-10-10)。ワールドの値が無ければ phases.json の {@code startAdvancements}。
     */
    public static int phase1Advancements(MinecraftServer server) {
        return (int) Math.round(get(server).value(DifficultySetting.QUAKE_ADVANCEMENTS));
    }

    /**
     * 読み込まれていないチャンクへも侵食を広げてよいか(2026-10-10、既定はオン)。オフなら台帳の広がり・ネザーの台帳は
     * 読み込まれているチャンクにだけ印を付け、過繁茂したエンドは読み込まれた本島のチャンクだけを塗る。
     */
    public static boolean unloadedSpread(MinecraftServer server) {
        return get(server).value(DifficultySetting.UNLOADED_SPREAD) >= 0.5D;
    }

    /**
     * TD で同時に出ている攻め手の上限。ワールドの値が無ければ渡された表の {@code concurrentCap}
     * (GameTest が差し替えた表を使えるよう、{@code DesignData.td()} ではなく呼び出し側の表を見る)。
     */
    public static int tdConcurrentCap(MinecraftServer server, TdTable table) {
        OptionalDouble cap = get(server).override(DifficultySetting.TD_MAX_MOBS);
        return cap.isPresent() ? (int) Math.round(cap.getAsDouble()) : table.concurrentCap();
    }

    /**
     * 敵が 1 回に削る量(TD の壁・鉄線・灯り・ターゲットブロック)に敵の強さを掛ける。生き物へのダメージは
     * {@link EnemyStrengthDamage} が掛けるので、ここは通らない(二重に掛けない)。
     */
    public static float scaleEnemyStrike(Entity attacker, float amount) {
        MinecraftServer server = attacker.getServer();
        if (server == null || !(amount > 0.0F)) {
            return amount;
        }
        return (float) (amount * enemyStrength(server));
    }

    // ------------------------------------------------------------------ 値

    /** この項目の今の値(ワールドの値、無ければ既定値)。 */
    public double value(DifficultySetting setting) {
        Double v = overrides.get(setting);
        return v != null ? v : setting.defaultValue();
    }

    /** ワールドの値(無ければ空)。 */
    public OptionalDouble override(DifficultySetting setting) {
        Double v = overrides.get(setting);
        return v != null ? OptionalDouble.of(v) : OptionalDouble.empty();
    }

    public boolean isOverridden(DifficultySetting setting) {
        return overrides.containsKey(setting);
    }

    /** ワールドの値の写し(書き換えても元は変わらない)。 */
    public Map<DifficultySetting, Double> overrides() {
        return Collections.unmodifiableMap(new EnumMap<>(overrides));
    }

    public void set(DifficultySetting setting, double value) {
        overrides.put(setting, setting.clamp(value));
        setDirty();
    }

    public void reset(DifficultySetting setting) {
        if (overrides.remove(setting) != null) {
            setDirty();
        }
    }

    /** ワールドの値をまるごと置き換える(入っていない項目は既定値に戻る)。 */
    public void replaceAll(Map<DifficultySetting, Double> values) {
        overrides.clear();
        values.forEach((setting, value) -> overrides.put(setting, setting.clamp(value)));
        setDirty();
    }

    // ------------------------------------------------------------------ 保存

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag values = new CompoundTag();
        overrides.forEach((setting, value) -> values.putDouble(setting.id(), value));
        tag.put("overrides", values);
        return tag;
    }

    private static WorldDifficultyData load(CompoundTag tag, HolderLookup.Provider registries) {
        WorldDifficultyData data = new WorldDifficultyData();
        CompoundTag values = tag.getCompound("overrides");
        for (String key : values.getAllKeys()) {
            DifficultySetting setting = DifficultySetting.byId(key);
            if (setting != null) {
                data.overrides.put(setting, setting.clamp(values.getDouble(key)));
            }
        }
        return data;
    }

    /**
     * ワールド作成画面から: 「ワールド作成」を押した直後、<b>サーバーが起動する前</b>の新しいワールドのフォルダへ
     * 最初の値を書く。形式は {@link SavedData#save(java.io.File, HolderLookup.Provider)} と同じ
     * ({@code data} と {@code DataVersion} の入れ物)なので、起動したサーバーは {@link #get} でふつうに読み込む。
     *
     * @param overworldFolder オーバーワールドの保存先(= ワールドのフォルダ)。この下の {@code data/} に書く
     * @param values          ワールドの値(空なら何も書かない)
     */
    public static void writeInitial(Path overworldFolder, Map<DifficultySetting, Double> values) throws IOException {
        if (values.isEmpty()) {
            return;
        }
        WorldDifficultyData data = new WorldDifficultyData();
        data.replaceAll(values);
        Path dataFolder = overworldFolder.resolve("data");
        Files.createDirectories(dataFolder);
        // SavedData#save(File, …) と同じ形({@code data} + {@code DataVersion})を、その場で書き切る(2026-10-20)。
        // NeoForge 21.1.2xx の SavedData#save は入出力のスレッドで後から書くので、すぐ起動するサーバーが書き終わる前に読み、
        // 画面で決めた値が黙って既定値に戻りうる(GameTest createWorldFileLoadsAsSavedData が落ちて気づいた)。
        CompoundTag root = new CompoundTag();
        root.put("data", data.save(new CompoundTag(), RegistryAccess.EMPTY));
        NbtUtils.addCurrentDataVersion(root);
        IOUtilities.writeNbtCompressed(root, dataFolder.resolve(ID + ".dat"));
    }
}

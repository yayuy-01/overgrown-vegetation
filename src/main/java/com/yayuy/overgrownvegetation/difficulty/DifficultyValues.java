package com.yayuy.overgrownvegetation.difficulty;

import io.netty.handler.codec.DecoderException;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.TreeMap;

/**
 * 1 つのワールドの設定の「ワールドの値」の束(2026-11-19)。保存({@link WorldDifficultyData})・ワールド作成画面の下書き・
 * 設定のページの下書き・通信が同じ形を使う。
 *
 * <ul>
 *     <li>{@link #overrides}: 項目ごとの値。入っていない項目は既定値に従う。</li>
 *     <li>{@link #mobOverrides}: Mob の強さの詳細(Mob の id → 4 項目のうち変えた物)。入っていない物は全体の % に従う。</li>
 * </ul>
 *
 * <p>11-19 ユーザー決定の決まりはここに置く: 全体の % を動かすと全員のその項目が揃う({@link #setGlobalMobStat})、
 * 段を選ぶと段の項目が揃って詳細が消える({@link #applyTier})、1 つでも違えばカスタム({@link #tier})。
 */
public final class DifficultyValues {

    /** 一度に送る・読む数の上限(壊れたパケット・壊れた保存で巨大な表を作らないため)。 */
    public static final int MAX_ENTRIES = 64;
    public static final int MAX_MOBS = 512;

    private final EnumMap<DifficultySetting, Double> overrides = new EnumMap<>(DifficultySetting.class);
    /** 並びを決めておく(保存・通信の中身が毎回同じになるように)。 */
    private final TreeMap<String, EnumMap<DifficultySetting, Double>> mobOverrides = new TreeMap<>();

    public DifficultyValues() {
    }

    public DifficultyValues copy() {
        DifficultyValues c = new DifficultyValues();
        c.copyFrom(this);
        return c;
    }

    public void copyFrom(DifficultyValues other) {
        overrides.clear();
        overrides.putAll(other.overrides);
        mobOverrides.clear();
        other.mobOverrides.forEach((id, m) -> mobOverrides.put(id, new EnumMap<>(m)));
    }

    public boolean isEmpty() {
        return overrides.isEmpty() && mobOverrides.isEmpty();
    }

    // ------------------------------------------------------------------ 項目

    public OptionalDouble override(DifficultySetting setting) {
        Double v = overrides.get(setting);
        return v != null ? OptionalDouble.of(v) : OptionalDouble.empty();
    }

    /** ワールドの値、無ければ {@code defaultValue}。 */
    public double valueOr(DifficultySetting setting, double defaultValue) {
        Double v = overrides.get(setting);
        return v != null ? v : defaultValue;
    }

    public Map<DifficultySetting, Double> overrides() {
        return Collections.unmodifiableMap(overrides);
    }

    /** 値を書く。{@code NaN} なら消す(既定値に従う)。Mob の強さの項目でも詳細は消さない(消すなら {@link #setGlobalMobStat})。 */
    public void set(DifficultySetting setting, double value) {
        if (Double.isNaN(value)) {
            overrides.remove(setting);
        } else {
            overrides.put(setting, setting.clamp(value));
        }
    }

    public void reset(DifficultySetting setting) {
        overrides.remove(setting);
    }

    // ------------------------------------------------------------------ Mob の強さ

    /**
     * 全体の % を書き、全員のその項目を全体の % に揃える(Mob ごとの詳細のその項目を消す。11-19 ユーザー決定)。
     * {@code NaN} なら全体の % を既定(100%)に戻す。
     */
    public void setGlobalMobStat(DifficultySetting stat, double value) {
        requireMobStat(stat);
        set(stat, value);
        mobOverrides.values().forEach(m -> m.remove(stat));
        mobOverrides.values().removeIf(Map::isEmpty);
    }

    /** この Mob のこの項目の詳細(無ければ空 = 全体の % に従う)。 */
    public OptionalDouble mobOverride(String mobId, DifficultySetting stat) {
        EnumMap<DifficultySetting, Double> m = mobOverrides.get(mobId);
        Double v = m != null ? m.get(stat) : null;
        return v != null ? OptionalDouble.of(v) : OptionalDouble.empty();
    }

    /** この Mob のこの項目の値 = 詳細、無ければ全体の %(既定 100%)。 */
    public double mobValue(String mobId, DifficultySetting stat) {
        OptionalDouble own = mobOverride(mobId, stat);
        return own.isPresent() ? own.getAsDouble() : valueOr(stat, 1.0D);
    }

    /** 詳細を書く。{@code NaN} なら消す。 */
    public void setMob(String mobId, DifficultySetting stat, double value) {
        requireMobStat(stat);
        if (Double.isNaN(value)) {
            EnumMap<DifficultySetting, Double> m = mobOverrides.get(mobId);
            if (m != null) {
                m.remove(stat);
                if (m.isEmpty()) mobOverrides.remove(mobId);
            }
            return;
        }
        mobOverrides.computeIfAbsent(mobId, k -> new EnumMap<>(DifficultySetting.class)).put(stat, stat.clamp(value));
    }

    public void clearMobOverrides() {
        mobOverrides.clear();
    }

    public boolean hasMobOverrides(DifficultySetting stat) {
        for (EnumMap<DifficultySetting, Double> m : mobOverrides.values()) {
            if (m.containsKey(stat)) return true;
        }
        return false;
    }

    /** 詳細の写し(Mob の id → 項目 → 値)。 */
    public Map<String, Map<DifficultySetting, Double>> mobOverrides() {
        Map<String, Map<DifficultySetting, Double>> out = new TreeMap<>();
        mobOverrides.forEach((id, m) -> out.put(id, Collections.unmodifiableMap(new EnumMap<>(m))));
        return Collections.unmodifiableMap(out);
    }

    private static void requireMobStat(DifficultySetting stat) {
        if (!stat.mobStat()) throw new IllegalArgumentException("not a mob stat: " + stat);
    }

    // ------------------------------------------------------------------ MOD の難易度の段

    /** 今の値がどの段か。段の項目が全部その段の倍率で、Mob ごとの詳細が無ければその段、そうでなければカスタム。 */
    public ModDifficulty tier() {
        if (!mobOverrides.isEmpty()) {
            return ModDifficulty.CUSTOM;
        }
        for (ModDifficulty t : ModDifficulty.SELECTABLE) {
            boolean all = true;
            for (DifficultySetting s : DifficultySetting.values()) {
                if (s.tiered() && !s.same(valueOr(s, s.tierValue(ModDifficulty.NORMAL)), s.tierValue(t))) {
                    all = false;
                    break;
                }
            }
            if (all) return t;
        }
        return ModDifficulty.CUSTOM;
    }

    /** 段を選ぶ: 段の項目を全部その段の倍率に揃え、Mob ごとの詳細を消す。ほかの項目はそのまま。 */
    public void applyTier(ModDifficulty tier) {
        if (tier == ModDifficulty.CUSTOM) return;
        for (DifficultySetting s : DifficultySetting.values()) {
            if (!s.tiered()) continue;
            double v = s.tierValue(tier);
            if (s.same(v, s.tierValue(ModDifficulty.NORMAL))) overrides.remove(s);
            else overrides.put(s, s.clamp(v));
        }
        mobOverrides.clear();
    }

    // ------------------------------------------------------------------ 保存

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CompoundTag values = new CompoundTag();
        overrides.forEach((setting, value) -> values.putDouble(setting.id(), value));
        tag.put("overrides", values);
        CompoundTag mobs = new CompoundTag();
        mobOverrides.forEach((id, m) -> {
            CompoundTag one = new CompoundTag();
            m.forEach((stat, value) -> one.putDouble(stat.id(), value));
            mobs.put(id, one);
        });
        tag.put("mobs", mobs);
        return tag;
    }

    public static DifficultyValues load(CompoundTag tag) {
        DifficultyValues v = new DifficultyValues();
        CompoundTag values = tag.getCompound("overrides");
        for (String key : values.getAllKeys()) {
            // 11-19: 前の「敵の強さ」(攻撃力の倍率)は Mob の攻撃力の全体の % へ写す
            DifficultySetting setting = "enemy_strength".equals(key) ? DifficultySetting.MOB_ATTACK : DifficultySetting.byId(key);
            if (setting != null && !(setting == DifficultySetting.MOB_ATTACK && "enemy_strength".equals(key) && values.contains("mob_attack"))) {
                v.overrides.put(setting, setting.clamp(values.getDouble(key)));
            }
        }
        CompoundTag mobs = tag.getCompound("mobs");
        int n = 0;
        for (String id : mobs.getAllKeys()) {
            if (++n > MAX_MOBS) break;
            CompoundTag one = mobs.getCompound(id);
            for (String key : one.getAllKeys()) {
                DifficultySetting stat = DifficultySetting.byId(key);
                if (stat != null && stat.mobStat()) v.setMob(id, stat, one.getDouble(key));
            }
        }
        return v;
    }

    // ------------------------------------------------------------------ 通信

    public void write(FriendlyByteBuf buf) {
        buf.writeVarInt(overrides.size());
        overrides.forEach((setting, value) -> {
            buf.writeUtf(setting.id());
            buf.writeDouble(value);
        });
        buf.writeVarInt(mobOverrides.size());
        mobOverrides.forEach((id, m) -> {
            buf.writeUtf(id);
            buf.writeVarInt(m.size());
            m.forEach((stat, value) -> {
                buf.writeUtf(stat.id());
                buf.writeDouble(value);
            });
        });
    }

    /** 読む。知らない項目・有限でない値は読み捨て、範囲に収める(受け取った値を信じない)。 */
    public static DifficultyValues read(FriendlyByteBuf buf) {
        DifficultyValues v = new DifficultyValues();
        int size = buf.readVarInt();
        if (size < 0 || size > MAX_ENTRIES) throw new DecoderException("difficulty overrides: " + size);
        for (int i = 0; i < size; i++) {
            DifficultySetting setting = DifficultySetting.byId(buf.readUtf(64));
            double value = buf.readDouble();
            if (setting != null && Double.isFinite(value)) v.overrides.put(setting, setting.clamp(value));
        }
        int mobs = buf.readVarInt();
        if (mobs < 0 || mobs > MAX_MOBS) throw new DecoderException("difficulty mobs: " + mobs);
        for (int i = 0; i < mobs; i++) {
            String id = buf.readUtf(128);
            int stats = buf.readVarInt();
            if (stats < 0 || stats > DifficultySetting.MOB_STATS.length) throw new DecoderException("difficulty mob stats: " + stats);
            for (int j = 0; j < stats; j++) {
                DifficultySetting stat = DifficultySetting.byId(buf.readUtf(64));
                double value = buf.readDouble();
                if (stat != null && stat.mobStat() && Double.isFinite(value)) v.setMob(id, stat, value);
            }
        }
        return v;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof DifficultyValues other && overrides.equals(other.overrides) && mobOverrides.equals(other.mobOverrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode() * 31 + mobOverrides.hashCode();
    }

    @Override
    public String toString() {
        return "DifficultyValues" + overrides + " mobs=" + mobOverrides;
    }
}

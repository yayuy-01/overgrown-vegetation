package com.yayuy.overgrownvegetation.adaptation;

import it.unimi.dsi.fastutil.objects.Object2DoubleMap;
import it.unimi.dsi.fastutil.objects.Object2DoubleOpenHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import com.yayuy.overgrownvegetation.port.saved.OvSavedData;

/**
 * <b>適応の台帳</b>(2026-09-23)。<b>ワールドに 1 つ</b>(オーバーワールドに保存)、中身は<b>種ごと</b>。
 *
 * <p>種の id → (武器種のキー → 耐性)。耐性は 0〜1 の割合で、全欄の合計が上限を超えないように保つ。
 *
 * <h2>積み方(B 方式、ユーザー指定)</h2>
 * <ol>
 *     <li>倒された武器種の欄に {@code perKill}(既定 2%)を足す。</li>
 *     <li>全欄の合計が上限(既定 30%)を超えたら、全欄を {@code 上限 / 合計} 倍する。</li>
 *     <li>縮んで {@code forgetBelow} を下回った欄は捨てる(台帳が際限なく増えないように)。</li>
 * </ol>
 * <p>上限に届くまで(既定で 15 キル)は各欄 = 2% × その武器のキル数 で、比例配分と同じ結果になる。
 * 違いが出るのは上限の後で、<b>古い履歴ほど薄まる</b> —— 剣で 300 体倒したあとでも、弓に持ち替えれば
 * 弓の欄が育ち、剣の欄は縮んでいく。累計のキル数で配分すると、長く遊ぶほど耐性が動かなくなる。
 *
 * <h2>上限は読むときにも掛ける</h2>
 * <p>上限は記憶線で上がる予定(量は未定)。記憶線が壊れて上限が下がったときに台帳を書き直さずに済むよう、
 * {@link #resistance} は<b>その時点の上限</b>で縮めた値を返す。積むときも同じ上限で縮める。
 */
public final class AdaptationData extends OvSavedData {

    private static final String ID = "overgrown_vegetation_adaptation";

    public static final OvSavedData.Factory<AdaptationData> FACTORY =
            new OvSavedData.Factory<>(AdaptationData::new, (tag, registries) -> load(tag), null);

    /** 種の id → 武器種 → 耐性。 */
    private final Map<String, Object2DoubleOpenHashMap<String>> species = new HashMap<>();

    private AdaptationData() {
    }

    public static AdaptationData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY.load(), FACTORY.constructor(), ID);
    }

    private static AdaptationData load(CompoundTag tag) {
        AdaptationData data = new AdaptationData();
        for (String id : tag.getAllKeys()) {
            CompoundTag slots = tag.getCompound(id);
            Object2DoubleOpenHashMap<String> map = new Object2DoubleOpenHashMap<>();
            for (String key : slots.getAllKeys()) {
                map.put(key, slots.getDouble(key));
            }
            if (!map.isEmpty()) {
                data.species.put(id, map);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        for (Map.Entry<String, Object2DoubleOpenHashMap<String>> e : species.entrySet()) {
            CompoundTag slots = new CompoundTag();
            for (Object2DoubleMap.Entry<String> slot : e.getValue().object2DoubleEntrySet()) {
                slots.putDouble(slot.getKey(), slot.getDoubleValue());
            }
            tag.put(e.getKey(), slots);
        }
        return tag;
    }

    /** {@code speciesId} が {@code key} で倒された。 */
    public void record(String speciesId, String key, double perKill, double cap, double forgetBelow) {
        absorb(species.computeIfAbsent(speciesId, id -> new Object2DoubleOpenHashMap<>()),
                key, perKill, cap, forgetBelow);
        setDirty();
    }

    /** {@code speciesId} の {@code key} への耐性(0〜1)。その時点の上限 {@code cap} で縮めた値。 */
    public double resistance(String speciesId, String key, double cap) {
        Object2DoubleOpenHashMap<String> slots = species.get(speciesId);
        if (slots == null) {
            return 0.0D;
        }
        double value = slots.getDouble(key);
        if (value <= 0.0D) {
            return 0.0D;
        }
        double sum = sum(slots);
        return sum > cap ? value * cap / sum : value;
    }

    /** {@code speciesId} の全欄(キー順)。まだ無ければ空。縮める前の生の値。 */
    public Map<String, Double> slots(String speciesId) {
        Map<String, Double> out = new TreeMap<>();
        Object2DoubleOpenHashMap<String> slots = species.get(speciesId);
        if (slots != null) {
            for (Object2DoubleMap.Entry<String> e : slots.object2DoubleEntrySet()) {
                out.put(e.getKey(), e.getDoubleValue());
            }
        }
        return out;
    }

    /** {@code speciesId} の記録を消す(GameTest の後片付け用)。 */
    public void forget(String speciesId) {
        if (species.remove(speciesId) != null) {
            setDirty();
        }
    }

    /** 1 キルぶん積む。B 方式の計算そのもの。 */
    static void absorb(Object2DoubleOpenHashMap<String> slots, String key, double perKill, double cap,
                       double forgetBelow) {
        slots.addTo(key, perKill);
        double sum = sum(slots);
        if (sum > cap) {
            double scale = sum > 0.0D ? cap / sum : 0.0D;
            for (Object2DoubleMap.Entry<String> e : slots.object2DoubleEntrySet()) {
                e.setValue(e.getDoubleValue() * scale);
            }
            slots.object2DoubleEntrySet().removeIf(e -> e.getDoubleValue() < forgetBelow);
        }
    }

    private static double sum(Object2DoubleOpenHashMap<String> slots) {
        double sum = 0.0D;
        for (double v : slots.values()) {
            sum += v;
        }
        return sum;
    }
}

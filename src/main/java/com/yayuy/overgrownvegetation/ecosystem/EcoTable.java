package com.yayuy.overgrownvegetation.ecosystem;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 生態系の台帳の設計値({@code design/ecosystem.json}、2026-11-16)。正本は Vault「生態系の台帳(兵站・バイオマス)」。
 * 数はどれも【仮】(全体管理 11-16 了承: 升 4×4 チャンク・満員 G2 12・G3 4・5 日で戻る)。
 */
public final class EcoTable {

    // --- Gson DTO ---
    public static final class Json {
        public Boolean enabled;
        public Integer cellShift;
        public Integer fullG2, fullG3;
        public Double recoveryDays;
        public Double weightG2, weightG3, scale;
        public Double incomeFloor;
        /** 倒木の端を洞にする確率(11-18 ユーザー「あげていいよ」で 0.7)。 */
        public Double denChance;
        public List<String> countedDamageTypes;
        public InnerJson inner;
        public StubJson stub;
        /** 行動の段(11-18): 種の id(名前空間なし)ごとの値。省いた値はコードの既定(EcoBehavior)。 */
        public java.util.Map<String, com.yayuy.overgrownvegetation.ecosystem.life.EcoBehavior.Json> behavior;
    }

    public static final class InnerJson {
        public Double threshold;
        public Integer capG4, capG5, intervalTicks, triesPerPlayer, minBlocks, maxBlocks, countRadius, despawnBlocks;
    }

    public static final class StubJson {
        public List<String> g2, g3;
        public Integer capG2, capG3, intervalTicks, triesPerPlayer, minBlocks, maxBlocks;
    }

    public final boolean enabled;
    /** 升の一辺 = 2^cellShift チャンク(既定 2 = 4×4 チャンク)。 */
    public final int cellShift;
    public final int fullG2, fullG3;
    public final double recoveryDays;
    /** 多様性 = 1 − Σ(欠け × 重み) ÷ scale(全体管理 11-16 決定 A: 固定の物差し)。 */
    public final double weightG2, weightG3, scale;
    /** 予算の貯まる速さの倍率 = floor + (1 − floor) × 多様性(決定 C)。 */
    public final double incomeFloor;
    /** 倒木の端を G2・G3 の洞にする確率(1 つの端ごと・座標の乱数)【仮 0.7】。 */
    public final double denChance;
    public final List<String> countedDamageTypes;

    public final double innerThreshold;
    public final int innerCapG4, innerCapG5, innerInterval, innerTries, innerMin, innerMax, innerCountRadius, innerDespawn;

    public final List<String> stubG2, stubG3;
    public final int stubCapG2, stubCapG3, stubInterval, stubTries, stubMin, stubMax;

    public static final EcoTable DEFAULT = fromJson(null);

    private EcoTable(Json j) {
        InnerJson in = j.inner != null ? j.inner : new InnerJson();
        StubJson st = j.stub != null ? j.stub : new StubJson();
        enabled = j.enabled == null || j.enabled;
        cellShift = clamp(or(j.cellShift, 2), 0, 5);
        fullG2 = Math.max(1, or(j.fullG2, 12));
        fullG3 = Math.max(1, or(j.fullG3, 4));
        recoveryDays = Math.max(0.01D, or(j.recoveryDays, 5.0D));
        weightG2 = Math.max(0.0D, or(j.weightG2, 1.0D));
        weightG3 = Math.max(0.0D, or(j.weightG3, 2.5D));
        scale = Math.max(1.0E-6D, or(j.scale, 50.0D));
        incomeFloor = Math.max(0.0D, Math.min(1.0D, or(j.incomeFloor, 0.5D)));
        denChance = Math.max(0.0D, Math.min(1.0D, or(j.denChance, 0.7D)));
        countedDamageTypes = j.countedDamageTypes != null ? List.copyOf(j.countedDamageTypes)
                : List.of("overgrown_vegetation:autocannon", "overgrown_vegetation:barbed_wire");
        innerThreshold = Math.max(0.0D, Math.min(1.0D, or(in.threshold, 0.7D)));
        innerCapG4 = Math.max(0, or(in.capG4, 8));
        innerCapG5 = Math.max(0, or(in.capG5, 4));
        innerInterval = Math.max(1, or(in.intervalTicks, 20));
        innerTries = Math.max(0, or(in.triesPerPlayer, 2));
        innerMin = Math.max(0, or(in.minBlocks, 24));
        innerMax = Math.max(innerMin + 1, or(in.maxBlocks, 96));
        innerCountRadius = Math.max(1, or(in.countRadius, 128));
        innerDespawn = Math.max(1, or(in.despawnBlocks, 128));
        stubG2 = st.g2 != null ? List.copyOf(st.g2) : List.of("minecraft:frog");
        stubG3 = st.g3 != null ? List.copyOf(st.g3) : List.of("minecraft:ocelot");
        stubCapG2 = Math.max(0, or(st.capG2, 8));
        stubCapG3 = Math.max(0, or(st.capG3, 3));
        stubInterval = Math.max(1, or(st.intervalTicks, 40));
        stubTries = Math.max(0, or(st.triesPerPlayer, 2));
        stubMin = Math.max(0, or(st.minBlocks, 24));
        stubMax = Math.max(stubMin + 1, or(st.maxBlocks, 64));
        com.yayuy.overgrownvegetation.ecosystem.life.EcoBehavior.load(j.behavior);
    }

    public static EcoTable fromJson(@Nullable Json j) {
        return new EcoTable(j != null ? j : new Json());
    }

    /** 升の一辺のチャンク数。 */
    public int cellChunks() {
        return 1 << cellShift;
    }

    public int full(int group) {
        return group == 3 ? fullG3 : fullG2;
    }

    public double weight(int group) {
        return group == 3 ? weightG3 : weightG2;
    }

    /** 仮の種の型(登録の無い id は飛ばす)。 */
    public List<EntityType<?>> stubTypes(int group) {
        List<EntityType<?>> out = new ArrayList<>();
        for (String id : group == 3 ? stubG3 : stubG2) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) out.add(BuiltInRegistries.ENTITY_TYPE.get(rl));
        }
        return out;
    }

    private static int or(@Nullable Integer v, int d) {
        return v != null ? v : d;
    }

    private static double or(@Nullable Double v, double d) {
        return v != null && Double.isFinite(v) ? v : d;
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}

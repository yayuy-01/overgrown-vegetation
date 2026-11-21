package com.yayuy.overgrownvegetation.ecosystem.life;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 環境生物の 1 種の行動の値(2026-11-18、行動の段)。{@code design/ecosystem.json} の {@code behavior} の節(種の id ごと)。
 * 節に無い値は {@link #DEFAULTS}(コードの既定)から取る。値はどれも【仮・Claude 11-18】(全体管理の答え待ち。ecosystem-plan.md)。
 */
public final class EcoBehavior {

    /** 起きている時間: 昼・夜・湿り(雨か夜)・いつでも。 */
    public enum Active { DAY, NIGHT, WET, ALWAYS }

    /** あさる所: 倒木の上・林床・葉の上・幹の脇・あさらない。 */
    public enum Forage { LOG, GROUND, LEAF, TRUNK, NONE }

    // --- Gson DTO(どれも省略できる) ---
    public static final class Json {
        public String active, forage;
        public Double hungerDays, forageBelow, huntBelow, forageAmount, huntRange, fleeRange;
        public Integer eatTicks, restTicks, startleTicks;
        public List<String> prey;
        public Boolean defends;
        public Boolean rests;
        public Double nestRange;
    }

    public final Active active;
    public final Forage forage;
    /** 満腹度が 1 → 0 になるまで(tick)。 */
    public final long hungerTicks;
    /** これより下であさる・狩る。満腹(下の値以上)なら何もしない。 */
    public final double forageBelow, huntBelow;
    /** あさって 1 回に戻る満腹度。獲物を倒すと 1 まで戻る。 */
    public final double forageAmount;
    public final double huntRange, fleeRange;
    public final int eatTicks, restTicks, startleTicks;
    public final List<String> prey;
    /** 洞を壊した人を襲う(サソリ・ファランギオタルブス。11-18 決定)。 */
    public final boolean defends;
    /** 休む Goal を使うか(コエルロサウラウスは自前の梢の止まり木で休むので false)。 */
    public final boolean rests;
    /** 住む洞からこの距離に来たサバイバルのプレイヤーを襲う(0 = 襲わない。ファランギオタルブスだけ)。 */
    public final double nestRange;

    private EcoBehavior(Json j, Json d) {
        active = parse(Active.class, j.active != null ? j.active : d.active, Active.ALWAYS);
        forage = parse(Forage.class, j.forage != null ? j.forage : d.forage, Forage.GROUND);
        hungerTicks = Math.max(200L, Math.round(or(j.hungerDays, d.hungerDays, 1.5D) * 24000.0D));
        forageBelow = or(j.forageBelow, d.forageBelow, 0.55D);
        huntBelow = or(j.huntBelow, d.huntBelow, 0.4D);
        forageAmount = or(j.forageAmount, d.forageAmount, 0.25D);
        huntRange = or(j.huntRange, d.huntRange, 12.0D);
        fleeRange = or(j.fleeRange, d.fleeRange, 6.0D);
        eatTicks = Math.max(1, or(j.eatTicks, d.eatTicks, 60));
        restTicks = Math.max(1, or(j.restTicks, d.restTicks, 200));
        startleTicks = Math.max(0, or(j.startleTicks, d.startleTicks, 8));
        prey = j.prey != null ? List.copyOf(j.prey) : d.prey != null ? List.copyOf(d.prey) : List.of();
        defends = j.defends != null ? j.defends : d.defends != null && d.defends;
        rests = j.rests != null ? j.rests : d.rests == null || d.rests;
        nestRange = or(j.nestRange, d.nestRange, 0.0D);
    }

    /** 起きている時間か。 */
    public boolean awake(Level level) {
        long t = level.getDayTime() % 24000L;
        boolean night = t >= 13000L && t < 23000L;
        return switch (this.active) {
            case DAY -> !night && !level.isRaining();
            case NIGHT -> night;
            case WET -> night || level.isRaining();
            case ALWAYS -> true;
        };
    }

    /** 獲物の型か。 */
    public boolean isPrey(EntityType<?> type) {
        if (this.prey.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return this.prey.contains(id.toString()) || this.prey.contains(id.getPath());
    }

    // ---------------------------------------------------------------- 表

    private static final Map<String, Json> DEFAULTS = new HashMap<>();

    private static Json d(String active, String forage, double hungerDays, double fleeRange, String... prey) {
        Json j = new Json();
        j.active = active;
        j.forage = forage;
        j.hungerDays = hungerDays;
        j.fleeRange = fleeRange;
        j.prey = List.of(prey);
        return j;
    }

    static {
        // 獲物の環境生物(食べられる側)。葉を食べる 2 種・地面をあさる 2 種【仮】
        DEFAULTS.put("protophasma", d("always", "leaf", 1.5D, 7.0D));
        DEFAULTS.put("stenodictya_nymph", d("always", "leaf", 1.0D, 5.0D));
        DEFAULTS.put("giant_snail", d("wet", "ground", 2.0D, 0.0D));       // 逃げずに殻へ(段 1 のまま)
        DEFAULTS.put("arthropleura", d("night", "log", 2.0D, 5.0D));
        // 狩る側の環境生物(食べる側)。トカゲ 3 種は昼、虫は夜【仮】
        DEFAULTS.put("hylonomus", d("day", "log", 1.5D, 8.0D, "overgrown_vegetation:stenodictya_nymph", "overgrown_vegetation:protophasma"));
        DEFAULTS.put("petrolacosaurus", d("day", "log", 1.5D, 8.0D, "overgrown_vegetation:stenodictya_nymph", "overgrown_vegetation:protophasma"));
        DEFAULTS.put("ianthasaurus", d("day", "ground", 1.5D, 6.0D, "overgrown_vegetation:giant_snail", "overgrown_vegetation:protophasma", "overgrown_vegetation:stenodictya_nymph"));
        DEFAULTS.put("coelurosauravus", d("day", "none", 1.5D, 8.0D));   // 虫は梢・幹で見た目だけ食べる(eatHook)。登る輪を邪魔しない
        DEFAULTS.put("pulmonoscorpius", d("night", "ground", 2.0D, 4.0D, "overgrown_vegetation:protophasma", "overgrown_vegetation:stenodictya_nymph"));
        DEFAULTS.put("phalangiotarbus", d("night", "ground", 2.0D, 4.0D, "overgrown_vegetation:protophasma", "overgrown_vegetation:stenodictya_nymph"));
        DEFAULTS.get("pulmonoscorpius").defends = true;
        DEFAULTS.get("coelurosauravus").rests = false;
        DEFAULTS.get("phalangiotarbus").defends = true;
        DEFAULTS.get("phalangiotarbus").nestRange = 6.0D;   // 巣の近くだけ襲う(11-16 ユーザー)
    }

    private static final Map<String, EcoBehavior> CACHE = new HashMap<>();
    @Nullable
    private static Map<String, Json> loaded;

    /** design json の節を差し替える(EcoTable の読み込みから)。 */
    public static synchronized void load(@Nullable Map<String, Json> json) {
        loaded = json;
        CACHE.clear();
    }

    /** 種 id(名前空間なし)の値。 */
    public static synchronized EcoBehavior of(String id) {
        return CACHE.computeIfAbsent(id, k -> {
            Json j = loaded != null ? loaded.get(k) : null;
            Json def = DEFAULTS.getOrDefault(k, new Json());
            return new EcoBehavior(j != null ? j : new Json(), def);
        });
    }

    private static <E extends Enum<E>> E parse(Class<E> c, @Nullable String s, E fallback) {
        if (s == null) return fallback;
        try {
            return Enum.valueOf(c, s.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double or(@Nullable Double a, @Nullable Double b, double c) {
        return a != null && Double.isFinite(a) ? a : b != null && Double.isFinite(b) ? b : c;
    }

    private static int or(@Nullable Integer a, @Nullable Integer b, int c) {
        return a != null ? a : b != null ? b : c;
    }
}

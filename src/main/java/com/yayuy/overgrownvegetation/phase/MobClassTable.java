package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Mob の分類({@code design/mob_class.json}、2026-11-18 夜 ユーザー決定)。系統・型・危険度・出るフェーズ・自然に湧くか。
 * 書き方は「○○型-危険度 N-○○種」。正本の決定は Vault「構想と進捗/バランス調整の下ごしらえ(Mob の危険度と型)(11-18)」の【決定】の節。
 *
 * <ul>
 *   <li>出るフェーズは種ごと(危険度の上限で自動に開く決まりは使わない)。寄生種は「その姿になれるフェーズ」(段階 0 F1・苗床/根付き F2・1 段目 F3)。</li>
 *   <li>危険度と型は、森が Mob を出すときの値段の目安: {@link #price(EntityType)} = base × step^(危険度 − 1) × 型の倍率(複合型は高い方)。</li>
 *   <li>環境生物は型も危険度も無い(値段 0 = 森は買わない)。</li>
 * </ul>
 * 引く口は {@code DesignData.mobClass()}。Gson で直に読む(鍵が無ければ既定値)。表に無い id は {@code null}。
 */
public final class MobClassTable {

    public static final MobClassTable DEFAULT = new MobClassTable();

    /** 系統。 */
    public enum Lineage {
        PARASITE("寄生種"), KIN("眷属種"), PLANT("植物種"), CRITTER("環境生物");

        public final String ja;

        Lineage(String ja) {
            this.ja = ja;
        }

        @Nullable
        public static Lineage byName(@Nullable String s) {
            if (s == null) return null;
            for (Lineage l : values()) if (l.name().equalsIgnoreCase(s)) return l;
            return null;
        }
    }

    /** 型。 */
    public enum Role {
        MELEE("近接型"), RANGED("遠距離型"), SPREAD("拡散型"), HINDER("妨害型"), SUPPORT("支援型"), COMMAND("指揮型"), SCOUT("偵察型");

        public final String ja;

        Role(String ja) {
            this.ja = ja;
        }

        @Nullable
        public static Role byName(@Nullable String s) {
            if (s == null) return null;
            for (Role r : values()) if (r.name().equalsIgnoreCase(s)) return r;
            return null;
        }
    }

    /** 危険度の「災害級」。 */
    public static final int CALAMITY = 12;

    public Price price = new Price();
    public Map<String, Entry> mobs = new LinkedHashMap<>();

    /** 値段の式【仮】。 */
    public static final class Price {
        public double base = 3.0D;
        public double step = 1.6D;
        public Map<String, Double> roles = new LinkedHashMap<>();

        /** 型の倍率(表に無ければ 1)。 */
        public double roleFactor(Role r) {
            Double v = roles == null ? null : roles.get(r.name().toLowerCase(Locale.ROOT));
            return v != null && v > 0.0D ? v : 1.0D;
        }
    }

    /** 1 種の分類。 */
    public static final class Entry {
        public String lineage;
        public List<String> roles = new ArrayList<>();
        public int danger;
        public int phase = 1;
        /** false = 自然に湧かない・森も湧かせない(特別な条件でだけ)。無ければ true。 */
        public Boolean natural;

        @Nullable
        public Lineage lineage() {
            return Lineage.byName(lineage);
        }

        public List<Role> roleList() {
            List<Role> out = new ArrayList<>();
            if (roles != null) for (String s : roles) {
                Role r = Role.byName(s);
                if (r != null) out.add(r);
            }
            return out;
        }

        public boolean natural() {
            return natural == null || natural;
        }

        /** 「近接型-危険度 5-眷属種」の形(日本語。開発用の表示・記録)。 */
        public String label() {
            StringBuilder sb = new StringBuilder();
            List<Role> rs = roleList();
            for (int i = 0; i < rs.size(); i++) sb.append(i > 0 ? "/" : "").append(rs.get(i).ja);
            if (danger > 0) sb.append(sb.isEmpty() ? "" : "-").append(danger >= CALAMITY ? "災害級" : "危険度 " + danger);
            Lineage l = lineage();
            if (l != null) sb.append(sb.isEmpty() ? "" : "-").append(l.ja);
            return sb.toString();
        }
    }

    // ---------------------------------------------------------------- 引く口

    /** id(名前空間を省くとこの MOD)の分類。表に無ければ null。 */
    @Nullable
    public Entry get(@Nullable String id) {
        if (id == null || mobs == null) return null;
        int c = id.indexOf(':');
        if (c >= 0) {
            if (!id.substring(0, c).equals(OvergrownVegetation.MODID)) return null;
            id = id.substring(c + 1);
        }
        return mobs.get(id);
    }

    @Nullable
    public Entry get(@Nullable EntityType<?> type) {
        if (type == null) return null;
        ResourceLocation k = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return k == null ? null : get(k.toString());
    }

    @Nullable
    public Entry get(@Nullable Entity e) {
        return e == null ? null : get(e.getType());
    }

    /** 出るフェーズ(表に無ければ {@code fallback})。 */
    public int phaseOf(@Nullable String id, int fallback) {
        Entry e = get(id);
        return e != null ? Math.max(1, e.phase) : fallback;
    }

    /** 自然に湧く・森が湧かせる種か(表に無ければ true)。 */
    public boolean natural(@Nullable String id) {
        Entry e = get(id);
        return e == null || e.natural();
    }

    /** 値段の式そのもの: base × step^(危険度 − 1) × 型の倍率(複合型は高い方)。危険度 0 は 0。 */
    public double priceOf(Entry e) {
        if (e == null || e.danger <= 0) return 0.0D;
        double role = 0.0D;
        for (Role r : e.roleList()) role = Math.max(role, price.roleFactor(r));
        if (role <= 0.0D) role = 1.0D;
        return price.base * Math.pow(price.step, Math.min(e.danger, CALAMITY) - 1) * role;
    }

    /** 森がその種を湧かせる値段(バイオマス、段で割る前)。表に無い・危険度 0 なら {@code fallback}。 */
    public double price(@Nullable EntityType<?> type, double fallback) {
        Entry e = get(type);
        double p = e == null ? 0.0D : priceOf(e);
        return p > 0.0D ? p : fallback;
    }
}

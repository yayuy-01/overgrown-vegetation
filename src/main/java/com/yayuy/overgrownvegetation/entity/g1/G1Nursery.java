package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;

/**
 * G1 の苗床(id {@code nursery_parasitized_<key>}、素材 {@code g1_<key>_nursery})の種の表(2026-11-13、司令塔 第 1 段の区切り 6)。
 * 仕様の正本は Vault「森の知性(司令塔)」の D-9・D-14・D-15・D-17 と 11-13 の「苗床の直し」。素材は「mobアニメーション」のセッションが書き出した
 * (共有ノート {@code g1-rooted-export.md} の苗床の節)。動きは 11 種で共通で、芽と大きな嚢の数だけ種で違う。
 *
 * <ul>
 *   <li>{@code buds}: 芽の数(クリップ {@code sprout_1}〜)。萌芽体を出すとき、どれか 1 つを流す。</li>
 *   <li>{@code pods}: 大きな嚢の数(クリップ {@code hatch_1}〜)。G4〜G8 を孵すとき、どれか 1 つを流す。</li>
 *   <li>体力は【仮】根付きと同じ(1 段目の 1.5 倍。11-13 ユーザー「後で決める」)。当たり判定は殻と嚢のかたまり【仮】(床の根は外)。</li>
 * </ul>
 * クリップの長さ(tick。11 種で共通): 待機 120・{@code sprout_N} 32(萌芽体は tick 12)・{@code hatch_N} 60(孵すのは tick 24)・生える 60・枯れる 44。
 */
public enum G1Nursery {

    // key, 段階 0 の種, 体力, 幅, 高さ, 芽, 大きな嚢
    ZOMBIE("zombie", G1Species.ZOMBIE, 45, 1.2F, 1.0F, 6, 2),
    BABY_ZOMBIE("baby_zombie", G1Species.BABY_ZOMBIE, 36, 0.9F, 0.7F, 4, 1),
    VILLAGER("villager", G1Species.VILLAGER, 45, 1.2F, 1.1F, 6, 2),
    SKELETON("skeleton", G1Species.SKELETON, 45, 1.2F, 1.0F, 6, 2),
    ENDERMAN("enderman", G1Species.ENDERMAN, 90, 1.2F, 1.0F, 6, 2),
    COW("cow", G1Species.COW, 23, 1.3F, 1.2F, 6, 2),
    PIG("pig", G1Species.PIG, 23, 1.1F, 1.0F, 6, 2),
    SHEEP("sheep", G1Species.SHEEP, 18, 1.2F, 1.1F, 6, 2),
    CHICKEN("chicken", G1Species.CHICKEN, 9, 0.8F, 0.6F, 4, 1),
    SPIDER("spider", G1Species.SPIDER, 36, 1.2F, 1.0F, 6, 2),
    CREEPER("creeper", G1Species.CREEPER, 45, 1.2F, 1.0F, 6, 2);

    public static final int SPROUT = 32, SPROUT_AT = 12, HATCH = 60, HATCH_AT = 24, EMERGE = 60, WITHER = 44;

    private final String key;
    private final G1Species base;
    public final double health;
    public final float width;
    public final float height;
    public final int buds;
    public final int pods;

    G1Nursery(String key, G1Species base, double health, float width, float height, int buds, int pods) {
        this.key = key;
        this.base = base;
        this.health = health;
        this.width = width;
        this.height = height;
        this.buds = buds;
        this.pods = pods;
    }

    public String key() {
        return key;
    }

    /** 苗床になる前の段階 0 の種。 */
    public G1Species base() {
        return base;
    }

    /** 登録名。例 {@code nursery_parasitized_zombie}。 */
    public String id() {
        return "nursery_parasitized_" + key;
    }

    /** 素材(geo・アニメ・絵)の名前。例 {@code g1_zombie_nursery}。 */
    public String assetName() {
        return "g1_" + key + "_nursery";
    }

    /** エンティティの型から(登録名で引く)。苗床でなければ null。 */
    @Nullable
    public static G1Nursery of(EntityType<?> type) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        for (G1Nursery n : values()) {
            if (n.id().equals(path)) return n;
        }
        return null;
    }
}

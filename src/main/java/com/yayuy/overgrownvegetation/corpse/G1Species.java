package com.yayuy.overgrownvegetation.corpse;

import com.yayuy.overgrownvegetation.entity.AbstractParasitizedMonster;
import com.yayuy.overgrownvegetation.registry.ModG1;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
import java.util.function.Supplier;

/**
 * G1 段階 0 の種の表(2026-11-08、作り直し 2 回目の組み込み)。仕様の正本は Vault「Mob の能力/G1 段階 0 の共通」と種ごとのノート。
 *
 * <p>1 行 = 1 種: 感染する元のバニラの Mob・死体ブロック・起き上がる G1・素材の名前({@code overgrown_<key>})と、
 * サーバーが知っておく必要のある素材の値(起き上がりの長さ・歩きの基準の速さ)。素材の値はモデルの記録
 * (Vault {@code モデル制作/G1<種>作り直し/})と生成物 {@code tools/g5-models/out/g1_<種>_v2_rN/} から写した —— assets はクライアントにしか無いので、
 * サーバーの時計(起き上がりの間 AI を止める)に要る長さはここに置く。
 *
 * <ul>
 *   <li>{@code riseTicks}: アニメ {@code rise} の長さ(tick)。起き上がりの間は AI を止める。</li>
 *   <li>{@code walkRef}: アニメ {@code walk} の足が滑らない速さ(ブロック/tick)。{@code MobAnimation#walkSpeedFor} に渡す。</li>
 *   <li>{@code attacks}: 3 段の 1 撃を持つか(クリーパーだけ持たない。11-05 ユーザー)。</li>
 * </ul>
 */
public enum G1Species {

    // key, 元の Mob, 子どもか, 起き上がり tick, walk の ref, 攻撃するか(ref と起き上がりの長さはモデルの記録 rN.md と生成物の rise から)
    ZOMBIE("zombie", () -> EntityType.ZOMBIE, false, 52, 0.112, true),
    BABY_ZOMBIE("baby_zombie", () -> EntityType.ZOMBIE, true, 52, 0.26, true),
    VILLAGER("villager", () -> EntityType.VILLAGER, false, 68, 0.112, true),
    COW("cow", () -> EntityType.COW, false, 52, 0.135, true),
    PIG("pig", () -> EntityType.PIG, false, 52, 0.194, true),
    SHEEP("sheep", () -> EntityType.SHEEP, false, 52, 0.169, true),
    CHICKEN("chicken", () -> EntityType.CHICKEN, false, 52, 0.194, true),
    CREEPER("creeper", () -> EntityType.CREEPER, false, 57, 0.194, false),
    SKELETON("skeleton", () -> EntityType.SKELETON, false, 52, 0.137, true),
    SPIDER("spider", () -> EntityType.SPIDER, false, 52, 0.124, true),
    ENDERMAN("enderman", () -> EntityType.ENDERMAN, false, 52, 0.194, true),
    IRON_GOLEM("iron_golem", () -> EntityType.IRON_GOLEM, false, 52, 0.135, true),
    WARDEN("warden", () -> EntityType.WARDEN, false, 52, 0.194, true);

    private final String key;
    private final Supplier<EntityType<?>> vanilla;
    private final boolean baby;
    private final int riseTicks;
    private final double walkRef;
    private final boolean attacks;

    G1Species(String key, Supplier<EntityType<?>> vanilla, boolean baby, int riseTicks, double walkRef, boolean attacks) {
        this.key = key;
        this.vanilla = vanilla;
        this.baby = baby;
        this.riseTicks = riseTicks;
        this.walkRef = walkRef;
        this.attacks = attacks;
    }

    /** 素材の名前(geo・アニメ・絵・崩れ落ちの姿勢・突き破り)。例 {@code overgrown_zombie}。 */
    public String assetName() {
        return "overgrown_" + key;
    }

    /** 登録名。例 {@code parasitized_zombie}。死体ブロックは {@code overgrown_<key>_corpse}。 */
    public String entityId() {
        return "parasitized_" + key;
    }

    public String key() {
        return key;
    }

    public int riseTicks() {
        return riseTicks;
    }

    public double walkRef() {
        return walkRef;
    }

    public boolean attacks() {
        return attacks;
    }

    /**
     * 元の Mob が感染して死体になるか。ウォーデンは今は感染の道を作らない(11-09 ユーザー「今はモデルだけを作る」。どこでどう感染させるかは
     * 地下の汚染 = 0.7.0 と一緒に決める)ので、バニラのウォーデンは死んでも死体にならない。G1 のウォーデン・死体ブロック・卵は登録してある。
     */
    public boolean infectable() {
        return this != WARDEN;
    }

    public EntityType<? extends AbstractParasitizedMonster> entityType() {
        return ModG1.entity(this).get();
    }

    public Block corpseBlock() {
        return ModG1.corpse(this).get();
    }

    /** 死体ブロックの種。G1 の死体でなければ null。 */
    @Nullable
    public static G1Species ofCorpse(Block block) {
        for (G1Species s : values()) {
            if (s.corpseBlock() == block) {
                return s;
            }
        }
        return null;
    }

    /**
     * 感染して死んだ Mob の種(死体になるか)。元の Mob と大人 / 子どもが合うもの。種の違うもの(ハスク・ストレイ・キノコウシなど)は
     * 見た目が違うので外す(旧 {@code SpeciesCorpses} と同じ)。
     */
    @Nullable
    public static G1Species ofVictim(LivingEntity victim) {
        for (G1Species s : values()) {
            if (victim.getType() == s.vanilla.get() && victim.isBaby() == s.baby && s.infectable()) {
                return s;
            }
        }
        return null;
    }

    /**
     * 種は表にあるが子どもで死体を作らない Mob か(子どもの村人・動物 → 汚染シダ。11-05 共通の決定「子どもの動物は汚染シダ」)。
     */
    public static boolean isChildOfListedSpecies(LivingEntity victim) {
        if (!victim.isBaby()) {
            return false;
        }
        for (G1Species s : values()) {
            if (victim.getType() == s.vanilla.get() && s.infectable()) {
                return true;
            }
        }
        return false;
    }
}

package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;

/**
 * G1 の根付き(第 0 段からの根付き。id {@code rooted_parasitized_<key>}、素材 {@code g1_<key>_rooted})の種の表(2026-11-13、司令塔 第 1 段の区切り 2・3)。
 * 仕様の正本は Vault「Mob の能力/G1 根付きの共通」。素材は「mobアニメーション」のセッションが書き出す(共有ノート {@code g1-rooted-export.md})。
 *
 * <p><b>ここに行のある種だけが MOD に入る</b>(アニメの無い姿は組み込まない、11-13 ユーザー)。行を足すと登録・卵・司令塔の根付きの候補が増える。
 *
 * <p>クリップの長さ(tick)は書き出しの記録から写す —— assets はクライアントにしか無いので、サーバーの時計に要る長さはここに置く。
 * アニメを書き出し直したら、ここの数を記録に合わせる。蔦の柱の型に共通: 生える 60・枯れる 44・薙ぎ払い 14 / 8 / 24。
 * <ul>
 *   <li>体力: 【仮】1 段目の 1.5 倍(11-13 ユーザー「後で決める」)。ドロップなし【仮】。</li>
 *   <li>幅・高さ: 当たり判定は柱の本体だけ【仮】(薙ぎ払いの蔦・蔦の腕・スケルトンの弓ははみ出す)。</li>
 *   <li>{@code lashRange}: 薙ぎ払いが届く長さ(柱の芯から。蔦の先 + 少し)。{@code shootHeight}: 塊・弾の出どころの高さ。</li>
 * </ul>
 */
public enum G1Rooted {

    // key, 段階 0 の種, 体力, 幅, 高さ, 薙ぎ払いの届く長さ, 出どころの高さ, 撃つ(溜め・撃つ・戻り), 種の能力, その 3 本組(溜め・実行・戻り。1 本だけなら溜め = 長さ)
    ZOMBIE("zombie", G1Species.ZOMBIE, 45, 1.0F, 4.4F, 2.4, 3.7, 16, 3, 16, Skill.GRAB, 12, 4, 16),
    BABY_ZOMBIE("baby_zombie", G1Species.BABY_ZOMBIE, 36, 0.8F, 2.5F, 1.5, 2.0, 16, 3, 16, Skill.SMOKE, 14, 0, 0),
    VILLAGER("villager", G1Species.VILLAGER, 45, 1.0F, 4.6F, 2.4, 2.6, 16, 3, 16, Skill.BELL, 48, 0, 0),
    ENDERMAN("enderman", G1Species.ENDERMAN, 90, 1.0F, 4.6F, 2.4, 3.8, 16, 3, 16, Skill.REACH, 12, 4, 16),
    SKELETON("skeleton", G1Species.SKELETON, 45, 1.0F, 5.2F, 2.4, 4.7, 20, 3, 36, Skill.BALLISTA, 0, 0, 0),
    // 背から咲く型(区切り 4)。薙ぎ払いは無い(届く長さ 0)。「撃つ」の欄 = 胞子の雲 puff_*(20 / 4 / 20)。当たり判定はへたり込んだ宿主の体【仮】
    COW("cow", G1Species.COW, 23, 1.4F, 1.6F, 0.0, 1.0, 20, 4, 20, Skill.MILK, 12, 6, 14),
    PIG("pig", G1Species.PIG, 23, 1.2F, 1.2F, 0.0, 1.0, 20, 4, 20, Skill.BIND, 8, 4, 20),
    SHEEP("sheep", G1Species.SHEEP, 18, 1.3F, 1.6F, 0.0, 1.0, 20, 4, 20, Skill.LOOSE, 16, 6, 30),
    CHICKEN("chicken", G1Species.CHICKEN, 9, 0.7F, 0.9F, 0.0, 0.6, 20, 4, 20, Skill.LAY, 12, 4, 12),
    // 地面の捕食植物の型(区切り 5)。「薙ぎ払いの届く長さ」の欄 = 葉の輪の半径(踏むと捕まる)。「撃つ」は無い。当たり判定は輪の真ん中に立つ宿主【仮】
    SPIDER("spider", G1Species.SPIDER, 36, 1.0F, 1.8F, 2.4, 0.5, 0, 0, 0, Skill.WEB, 10, 8, 14),
    CREEPER("creeper", G1Species.CREEPER, 45, 0.8F, 1.8F, 2.3, 0.5, 0, 0, 0, Skill.BURST, 12, 3, 24);

    /** 種の能力(根付きの共通「種ごとの能力」)。 */
    public enum Skill {
        /** ゾンビ: 蔦の腕で絡め取り、薙ぎ払いの間合いへ引き寄せる({@code grab_*}。骨 grab_swing をコードが回す)。 */
        GRAB("grab", "grab_swing"),
        /** チビゾンビ: 叩かれると袋から胞子の煙幕({@code smoke_puff} 1 本。煙は tick 5)。 */
        SMOKE("smoke_puff", null),
        /** 村人: 集会の鐘({@code bell_ring} 1 本。鳴るのは tick 3・9・15・21)と、常に暴露量を上げる輪。根の守り手はまだ。 */
        BELL("bell_ring", null),
        /** エンダーマン: 見つめた相手を転移で手元へ引き込む({@code reach_*}。骨 reach_shoulder をコードが回す)。 */
        REACH("reach", "reach_shoulder"),
        /** スケルトン: 撃つのが弓の狙撃({@code shoot_*}。骨 ballista_aim をコードが回す。体は固定)。 */
        BALLISTA(null, "ballista_aim"),
        /** 牛: 周りに乳だまりの沼を広げる({@code milk_*}。踏むと良い効果が消える)。 */
        MILK("milk", null),
        /** 豚: 近づいた相手を根の顎で縛る({@code bind_*} + 繰り返しの {@code bind_hold}。骨 root_jaw_carry をコードが相手の足元へ動かす)。 */
        BIND("bind", null),
        /** 羊: 綿毛を風に乗せて遠くへ飛ばし続ける({@code loose_*})。 */
        LOOSE("loose", null),
        /** 鶏: 周りに汚染された卵を産み落とし続ける({@code lay_*})。 */
        LAY("lay", null),
        /** クモ: 周りに根糸の網を張る({@code web_*}。時間で半径 7 まで広がる)。 */
        WEB("web", null),
        /** クリーパー: 捕まえた獲物の目の前で莢を爆ぜさせる({@code burst_*}。締める trap_hold の頭の姿から始まる)。 */
        BURST("burst", null);

        /** アニメの名前の頭(3 本組なら {@code _windup} などを足す。1 本ならそのまま)。無ければ null。 */
        @Nullable
        public final String clip;
        /** コードが y 回転を入れる骨。無ければ null。 */
        @Nullable
        public final String aimBone;

        Skill(@Nullable String clip, @Nullable String aimBone) {
            this.clip = clip;
            this.aimBone = aimBone;
        }
    }

    public static final int EMERGE = 60, WITHER = 44, LASH_WINDUP = 14, LASH = 8, LASH_RECOVER = 24;

    private final String key;
    private final G1Species base;
    public final double health;
    public final float width;
    public final float height;
    public final double lashRange;
    public final double shootHeight;
    public final int shootWindup, shoot, shootRecover;
    public final Skill skill;
    public final int skillWindup, skillLength, skillRecover;

    G1Rooted(String key, G1Species base, double health, float width, float height, double lashRange, double shootHeight,
             int shootWindup, int shoot, int shootRecover, Skill skill, int skillWindup, int skillLength, int skillRecover) {
        this.key = key;
        this.base = base;
        this.health = health;
        this.width = width;
        this.height = height;
        this.lashRange = lashRange;
        this.shootHeight = shootHeight;
        this.shootWindup = shootWindup;
        this.shoot = shoot;
        this.shootRecover = shootRecover;
        this.skill = skill;
        this.skillWindup = skillWindup;
        this.skillLength = skillLength;
        this.skillRecover = skillRecover;
    }

    public String key() {
        return key;
    }

    /** 地面の捕食植物の型か(クモ・クリーパー)。葉の輪を踏んだ獲物を捕まえる(trap_*)。薙ぎ払い・塊・雲は持たない。 */
    public boolean predator() {
        return skill == Skill.WEB || skill == Skill.BURST;
    }

    /** 背から咲く型か(牛・豚・羊・鶏)。薙ぎ払い・胞子の塊を持たず、胞子の雲を出す。 */
    public boolean bloom() {
        return skill == Skill.MILK || skill == Skill.BIND || skill == Skill.LOOSE || skill == Skill.LAY;
    }

    /** 根付く前の段階 0 の種。 */
    public G1Species base() {
        return base;
    }

    /** 登録名。例 {@code rooted_parasitized_zombie}。 */
    public String id() {
        return "rooted_parasitized_" + key;
    }

    /** 素材(geo・アニメ・絵)の名前。例 {@code g1_zombie_rooted}。 */
    public String assetName() {
        return "g1_" + key + "_rooted";
    }

    /** エンティティの型から(登録名で引く)。根付きでなければ null。 */
    @Nullable
    public static G1Rooted of(EntityType<?> type) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        for (G1Rooted r : values()) {
            if (r.id().equals(path)) return r;
        }
        return null;
    }
}

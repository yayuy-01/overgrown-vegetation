package com.yayuy.overgrownvegetation.entity.g1;

import com.yayuy.overgrownvegetation.corpse.G1Species;
import net.minecraft.world.entity.EntityType;

import javax.annotation.Nullable;

/**
 * G1 の 1 段目(発達した寄生された○○、id {@code developed_parasitized_<key>})の種の表(2026-11-12、第 1 段 = 体・アニメ・(a) 近接・破裂・卵)。
 * 仕様の正本は Vault「Mob の能力/G1 1 段目の共通」と種ごとの「G1 <種>(1 段目)」。モデルはユーザーが Blockbench で確かめた手直し版
 * ({@code tools/g5-models/out/developed_parasitized_<種>_r<n>_yayuy_claude[_anim]})を {@code tools/g5-models/g1/BbExport} で書き出した。
 *
 * <ul>
 *   <li>体力・攻撃・速さ・大きさ: 種ごとの仕様書の数値の節(多くは【仮】。体力は段階 0 の 1.5 倍、攻撃は +2 の目安)。</li>
 *   <li>{@code walkRef}: アニメ {@code walk} の足が滑らない速さ(各種のモデルの記録 rN.md の ref)。</li>
 *   <li>{@code recover}: アニメ {@code attack_recover} の長さ(tick)。11-19 から通常攻撃は溜めも隙も無いので使っていない(大技に溜めを残すときのために値は残す)。</li>
 *   <li>{@code burst}: アニメ {@code death_burst} の長さ(tick)= 破裂して消える時刻(11-11 の型。{@code corpse/G1DeathBurst})。</li>
 *   <li>{@code reach}・{@code knock}・{@code lunge}: (a) 種ごとの 1 撃の違い(2026-11-13 ユーザー「入れよう」。数は各種の仕様書の (a) 欄、どれも【仮】)。
 *   設計データ {@code mobs.json} ではなくここに置く: 間合いと踏み込みは属性でなく、押し返しは属性にすると跳びつき・取り付きにも効いてしまうので
 *   1 撃の当たりの後で足す({@code DevelopedParasitized#onStrikeHit})。
 *   <ul>
 *     <li>{@code reach}: ゾンビ・スケルトン・クモ +0.5、エンダーマン +1.0(段階 0 は +0.5)。鶏・クリーパーは仕様が「間合い 2.5 ブロック」なので、
 *     体の中心から相手の体の手前の端まで 2.5 になる値(2.5 − 体の半幅 − 素の間合い 0.83)= 鶏 1.42・クリーパー 1.37。</li>
 *     <li>{@code knock}: ゾンビ 0.5・村人 0.8・牛 0.9・羊 0.8・スケルトン 0.6。エンダーマン 0.6 は横へ({@link #knockSideways})。
 *     クモ −0.3 は手前へ引き寄せる。チビゾンビ・豚は 0(取り付き・食らいつきにつながるので押さない)。
 *     ゾンビ・村人は盾で防がれても押す({@link #pushesThroughShield}。ゾンビは当たったときと同じ強さ、村人はそれより小さく = {@link #shieldPushFull})。</li>
 *     <li>{@code lunge}: 牛 2・羊 3。</li>
 *   </ul></li>
 * </ul>
 * 第 1 段では (b) 戻った元の能力・(c) 器官の能力・{@code evolve_in} は使わない(次の段)。出し方は司令塔ができるまでスポーンエッグとコマンドだけ(11-09)。
 */
public enum G1Developed {

    // key, 段階 0 の種, 体力, 攻撃, 速さ, 幅, 高さ, walk の ref, 隙(tick), 破裂(tick), (a) 間合い +, (a) 押し返し, (a) 踏み込み
    ZOMBIE("zombie", G1Species.ZOMBIE, 30, 5, 0.25, 0.6F, 1.95F, 0.135, 16, 15, 0.5, 0.5, 0.0),
    BABY_ZOMBIE("baby_zombie", G1Species.BABY_ZOMBIE, 24, 3, 0.36, 0.6F, 0.8F, 0.28, 14, 10, 0.0, 0.0, 0.0),
    VILLAGER("villager", G1Species.VILLAGER, 30, 5, 0.28, 0.8F, 1.9F, 0.169, 16, 18, 0.0, 0.8, 0.0),
    COW("cow", G1Species.COW, 15, 5, 0.25, 1.0F, 1.5F, 0.135, 14, 13, 0.0, 0.9, 2.0),
    PIG("pig", G1Species.PIG, 15, 5, 0.3, 1.0F, 1.0F, 0.194, 14, 14, 0.0, 0.0, 0.0),
    SHEEP("sheep", G1Species.SHEEP, 12, 5, 0.28, 0.9F, 1.7F, 0.169, 14, 14, 0.0, 0.8, 3.0),
    CHICKEN("chicken", G1Species.CHICKEN, 6, 4, 0.3, 0.5F, 1.0F, 0.194, 14, 13, 1.42, 0.0, 0.0),
    CREEPER("creeper", G1Species.CREEPER, 30, 4, 0.3, 0.6F, 1.7F, 0.194, 14, 18, 1.37, 0.0, 0.0),
    SKELETON("skeleton", G1Species.SKELETON, 30, 5, 0.28, 0.6F, 1.99F, 0.166, 14, 18, 0.5, 0.6, 0.0),
    SPIDER("spider", G1Species.SPIDER, 24, 4, 0.3, 1.4F, 1.2F, 0.124, 14, 18, 0.5, -0.3, 0.0),
    ENDERMAN("enderman", G1Species.ENDERMAN, 60, 9, 0.3, 0.6F, 3.0F, 0.193, 14, 18, 1.0, 0.6, 0.0);

    private final String key;
    private final G1Species base;
    public final double health;
    public final double attack;
    public final double speed;
    public final float width;
    public final float height;
    public final double walkRef;
    public final int recover;
    public final int burst;
    /** (a) 間合いに足す長さ(ブロック。バニラの間合いの箱を水平に広げる)。 */
    public final double reach;
    /** (a) 押し返し(バニラの属性 ATTACK_KNOCKBACK と同じ単位。1 撃が当たったときだけ足す)。負は引き寄せ。 */
    public final double knock;
    /** (a) 踏み込み(ブロック。実行の段でまっすぐ前へ出る長さ。このぶん遠くから振り出す)。 */
    public final double lunge;

    G1Developed(String key, G1Species base, double health, double attack, double speed, float width, float height, double walkRef,
                int recover, int burst, double reach, double knock, double lunge) {
        this.key = key;
        this.base = base;
        this.health = health;
        this.attack = attack;
        this.speed = speed;
        this.width = width;
        this.height = height;
        this.walkRef = walkRef;
        this.recover = recover;
        this.burst = burst;
        this.reach = reach;
        this.knock = knock;
        this.lunge = lunge;
    }

    public String key() {
        return key;
    }

    /** 段階 0 の種(声・攻撃するか)。 */
    public G1Species base() {
        return base;
    }

    /** (a) の押し返しが横向きか(エンダーマンの横薙ぎ: 根の左腕が振り抜く向き = 体の右へ押す【仮】)。 */
    public boolean knockSideways() {
        return this == ENDERMAN;
    }

    /**
     * (a) 盾で防がれても相手を押すか(2026-11-13 ユーザー「入れよう」)。仕様書の (a) 欄に書いてある 2 種だけ:
     * ゾンビ「盾で受けると押し返しだけ受ける」・村人「盾で受けると少し後ずさる」。ほかの種の欄は「盾」とあるだけ。
     */
    public boolean pushesThroughShield() {
        return this == ZOMBIE || this == VILLAGER;
    }

    /**
     * (a) 盾ごしの押し返しが、当たったときと同じ強さか(2026-11-13 ユーザー「A案でいこうかな」= 仕様の言い回しどおり)。
     * ゾンビ「押し返しだけ受ける」= 同じ強さ(跳ね上げも)。村人「少し後ずさる」= それより小さく、跳ね上げ無し。
     */
    public boolean shieldPushFull() {
        return this == ZOMBIE;
    }

    /**
     * (a) 次の 1 撃までの間、届く所では寄らずに待つか【仮】。踏み込む牛・羊と、首の伸びる鶏。寄ってしまうと 2 撃目から踏み込み・長い間合いが
     * 見えなくなるため。クリーパー(間合い 2.5)は入れない: 爆ぜは中心から 3 ブロック以内で始まるので、待つと幅の広い相手に届かなくなる。
     */
    public boolean holdsDistance() {
        return lunge > 0.0D || this == CHICKEN;
    }

    /** 登録名・素材の名前。例 {@code developed_parasitized_zombie}。 */
    public String id() {
        return "developed_parasitized_" + key;
    }

    /** エンティティの型から(登録名で引く)。1 段目でなければ null。 */
    @Nullable
    public static G1Developed of(EntityType<?> type) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).getPath();
        for (G1Developed d : values()) {
            if (d.id().equals(path)) {
                return d;
            }
        }
        return null;
    }
}

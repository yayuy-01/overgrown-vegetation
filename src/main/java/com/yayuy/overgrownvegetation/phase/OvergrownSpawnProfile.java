package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.data.DesignData;
import com.yayuy.overgrownvegetation.data.MobStat;
import com.yayuy.overgrownvegetation.data.MobStats;
import com.yayuy.overgrownvegetation.registry.ModBiomes;
import com.yayuy.overgrownvegetation.registry.OvergrowthVariant;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.biome.Biome;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 過繁茂バイオームの生態系スポーン({@link OvergrownBiomeSpawner})で使う、種ごとの
 * <b>相対的な多さ</b>({@code weight})と <b>1 回のスポーンパスでその種に許容する最大数</b>
 * ({@code clusterMax}、＝群れの大きさ)の表。
 *
 * <p>{@link com.yayuy.overgrownvegetation.entity.Predation 捕食関係}と噛み合うよう、被食側
 * (グループ2 の原生生産種)の weight を捕食側(グループ3)より大きく取ってある
 * (合計でおよそ 3 倍)。これにより「被食者が多く・捕食者が少ない」ピラミッドが自然にできる。
 *
 * <p>{@link OvergrownBiomeSpawner} は各種の目標個体数を
 * {@code round(weight / (そのフェーズで湧ける全種の weight 合計) * overgrownSpawnTargetTotal)}
 * で決め、プレイヤー周辺の実個体数がそれを下回っている種だけを補充する。
 *
 * <h2>数の読み方 —— weight は「四捨五入したあとの体数」で効く</h2>
 * 目標個体数は {@code max(1, round(weight / 合計 * overgrownSpawnTargetTotal))}(既定 18)なので、
 * <b>weight を少し動かすと体数が 1 段まるごと変わる</b>。逆に大きく動かしても同じ段に留まることがある。
 * 減らしたいときは「今どの段にいるか」を見てから決めること。フェーズごとの合計 weight は
 * {@code mobs.json} の {@code spawns.overgrown} 次第で、現状はフェーズ5(群2・3・4)が 95.6、
 * フェーズ6以降(群4のみ)が 21.6。
 *
 * <p>下限 1 があるので、<b>weight をいくら下げても「まったく湧かない」にはならない</b>
 * (逆に、{@code overgrownSpawnTargetTotal} を下げても少数種がまるごと消えることはない)。
 * 「たまに出てくる程度」にしたい種は weight ではなく {@link Profile#spawnChance} で作る。
 *
 * <p>現状の目標個体数(既定 18)。フェーズ5 は合計 25 体、フェーズ6以降は合計 19 体:
 * <ul>
 *     <li>フェーズ5: プロトファスマ4・ステノデクティア3・クセナカンサス2、その他の原生種は各1、
 *     アルボラ3・ヴェロフィル1・スカトゥリウム1・フィロセンス1・スポロフィル1</li>
 *     <li>フェーズ6以降: アルボラ12・ヴェロフィル3・スカトゥリウム2・フィロセンス1・スポロフィル1
 *     (＝指定比率 7:2:1)</li>
 * </ul>
 *
 * <p>weight は<b>混成の群れ</b>({@link OvergrownBiomeSpawner} の {@code spawnMixedPack})の種の抽選にも
 * そのまま使われるので、フェーズ6以降の 3〜5 体の群れも上の 7:2:1 でばらける。
 * レイド(襲撃)の編成は別の表なので、ここを触っても襲撃の中身は変わらない。
 *
 * <p>ここはハードコードの表({@link MobGroup} / {@link com.yayuy.overgrownvegetation.entity.Predation}
 * と同じ方針)。表に無い id はグループ既定値、グループも不明なら {@link #ZERO}(＝湧かない)。
 */
public final class OvergrownSpawnProfile {

    /**
     * 種ごとのスポーン特性。
     *
     * @param weight      相対的な多さ(目標個体数の比例配分に使う)
     * @param clusterMax  1 回のスポーンパスでその種に許容する最大数(＝群れの大きさ)
     * @param spawnChance 1 回のスポーンパスでその種を<b>候補に入れる</b>確率。1.0 なら毎回候補。
     *                    下げると「目標個体数はそのままだが、なかなか湧かない」＝たまに出てくる種になる。
     *                    weight を下げるだけでは目標個体数が 1 で止まる({@link OvergrownBiomeSpawner} が
     *                    最低 1 体を保証している)ので、<b>レア枠はこちらで作る</b>。
     *                    混成の群れに混ざるかどうかも同じ確率で決まる。
     */
    public record Profile(double weight, int clusterMax, double spawnChance) {

        /** {@code spawnChance} 既定 1.0(毎回候補に入る)の特性。 */
        public Profile(double weight, int clusterMax) {
            this(weight, clusterMax, 1.0D);
        }
    }

    /**
     * バイオームの環境 → {@code mobs.json} の {@code spawns} のどの枠を見るか(2026-09-21)。
     *
     * <p>以前はここに「環境ごとに湧いてよい種」をハードコードしていたが、マネージャーから
     * 触れるように <b>Mob ごとの設定({@code spawns.jungle} / {@code swamp} / {@code ocean})</b>へ移した。
     * 湿地と菌湿地は同じ枠({@code swamp})にまとめてある —— どちらも同じ水環境なので、
     * 分けて指定したくなるまでは 1 つで足りる。
     *
     * @param variant 過繁茂の環境。海など環境を持たないバイオームは {@code null} = 海の枠
     */
    public static MobStat.SpawnPlace placeOf(@Nullable OvergrowthVariant variant) {
        if (variant == null) {
            return MobStat.SpawnPlace.OCEAN;
        }
        return variant.isSwamp() ? MobStat.SpawnPlace.SWAMP : MobStat.SpawnPlace.JUNGLE;
    }

    /** そのバイオームがどの枠か。 */
    public static MobStat.SpawnPlace placeOf(net.minecraft.core.Holder<Biome> biome) {
        return placeOf(OvergrowthVariant.fromBiome(biome));
    }

    /**
     * {@code type} がそのフェーズ・そのバイオームで湧いてよいか。
     *
     * <p><b>極相バイオームには通常の Mob を一切湧かせない</b>(2026-09-22、{@code climax.json} の
     * {@code biome.naturalSpawns})。極相に湧くのは G-EX だけ、という設計のため。
     * 判定をここに置いてあるのは、陸・水中・洞窟の 3 つの湧き経路が
     * ({@code OvergrownBiomeSpawner} の 3 箇所)いずれもこのメソッドを通るから ——
     * 経路ごとに書くと、どれか 1 つを直し忘れて「洞窟にだけ湧く」といった漏れ方をする。
     *
     * <p>{@code ModBiomes.isFullyOvergrown} は極相にも {@code true} を返すので、
     * 呼び出し元の「過繁茂バイオームか?」の足切りでは極相は弾かれない。ここで弾く必要がある。
     */
    public static boolean allowedIn(net.minecraft.core.Holder<Biome> biome, int phase, EntityType<?> type) {
        if (ModBiomes.isClimax(biome) && !DesignData.climax().naturalSpawns()) {
            return false;
        }
        return MobStats.spawnsIn(type, phase, placeOf(biome));
    }

    /** 表にもグループ既定にも無い種。生態系スポーンの対象外。 */
    public static final Profile ZERO = new Profile(0.0D, 0);

    /**
     * 「たまに出てくる程度」の種(索敵役のフィロセンス・支援役のスポロフィル)の {@code spawnChance}。
     * スポーン判定は {@code overgrownSpawnIntervalTicks}(既定 60 tick)ごと・{@code overgrownSpawnChance}
     * (既定 0.9)なので、過繁茂バイオームに居続けても候補に入るのは平均 40〜50 秒に 1 回ほど。
     * 湧いた個体は非永続なので、移動しながら遊んでいる間は「ときどき出会う」密度になる。
     */
    private static final double RARE_CHANCE = 0.08D;

    /** id(overgrown_vegetation 名前空間内のパス)→ 特性。 */
    private static final Map<String, Profile> BY_ID = Map.ofEntries(
            // --- グループ2: 原生生産種(被食者)。多い。 ---
            Map.entry("protophasma", new Profile(20.0D, 5)),
            Map.entry("stenodictya_lobata", new Profile(16.0D, 4)),
            Map.entry("arthropleura", new Profile(5.0D, 2)),
            // 脱皮で抜け殻を残す種。少しずつ会えるくらいでよい。
            Map.entry("lithopleura", new Profile(6.0D, 2)),

            // --- グループ2 洞窟: 地下にだけ湧く。地上には出てこない。 ---
            // 群れで洞窟を移動する絵を作りたいので、1 回に多めに湧かせる。
            Map.entry("spelaeopleura", new Profile(10.0D, 4)),

            // --- グループ2 水生: 原生生産種(被食者)。海・川バイオームの水中に湧く。 ---
            Map.entry("xenacanthus", new Profile(12.0D, 3)),
            Map.entry("rhizodus", new Profile(3.0D, 1)),

            // --- グループ3: 原生消費種(捕食者)。少ない。 ---
            Map.entry("petrolacosaurus", new Profile(4.0D, 3)),
            Map.entry("hylonomus", new Profile(4.0D, 3)),
            Map.entry("ianthasaurus", new Profile(2.0D, 2)),
            Map.entry("phalangiotarbus", new Profile(2.0D, 1)),
            Map.entry("meganeura", new Profile(2.0D, 2)),
            // 滑空種。<b>群れない</b>(ユーザー指定 2026-09-21)ので 1 体ずつ。
            Map.entry("coelurosauravus", new Profile(3.0D, 1)),

            // --- グループ4: 拡大種。雑魚枠なので多い。湧いた先から過繁茂バイオームの外へ出ていく。 ---
            // アルボラ 7 : ヴェロフィル 2 : 過繁茂したスカトゥリウム 1(ユーザー指定 2026-09-19)。
            // この 3 種でグループ4 の weight の 20.0/21.6 を占めるので、混成の群れ(フェーズ6以降)の
            // 中身もほぼこの比率になる。残りの 1.6 が下のレア 2 種。
            Map.entry("arbora", new Profile(14.0D, 6)),
            // 爆撃役。上空から嫌がらせをする役なので少数で効く。1.8 → 4.0(上の比率の「2」)。
            Map.entry("velophyll", new Profile(4.0D, 1)),
            // 盾役。1.0 → 2.0(上の比率の「1」)。1.0 のままだとフェーズ5 での目標個体数が
            // 端数で 0 に落ち、自然湧きしていなかった。
            Map.entry("overgrown_shielder", new Profile(2.0D, 1)),
            // グループ5(適応種)。G4 より湧きにくく数が出ない(ユーザー指定 2026-09-23): 1 体ずつ・レア枠。
            Map.entry("falcimnes", new Profile(0.6D, 1, RARE_CHANCE)),
            // 索敵役。1 体で周囲全体を動かすので数は少なくてよい。フェーズ5以降は
            // 「たまに出てくる程度」にする(ユーザー要望 2026-09-19)ため、weight を 2.0 → 0.8 に下げ、
            // さらに spawnChance でパスごとの候補入りを絞っている。
            Map.entry("phyllosens", new Profile(0.8D, 1, RARE_CHANCE)),
            // 支援役。1 体で周囲全体を強化するので、こちらも同じ扱い(1.8 → 0.8 + レア化)。
            Map.entry("sporophyll", new Profile(0.8D, 1, RARE_CHANCE)),

            // --- グループ3 水生: 水中の捕食者。 ---
            Map.entry("orthacanthus", new Profile(2.0D, 1)),
            Map.entry("megarachne", new Profile(2.0D, 2)),

            // --- グループ1: 媒介種(フェーズ1〜3)。感染した家畜・モンスター。 ---
            Map.entry("parasitized_cow", new Profile(6.0D, 3)),
            Map.entry("parasitized_sheep", new Profile(6.0D, 3)),
            Map.entry("parasitized_pig", new Profile(6.0D, 3)),
            Map.entry("parasitized_zombie", new Profile(4.0D, 2)),
            Map.entry("parasitized_creeper", new Profile(3.0D, 1)),
            Map.entry("parasitized_spider", new Profile(4.0D, 2)));

    /** グループ番号 → そのグループの種で id が表に無かったときの既定特性。 */
    private static Profile groupDefault(MobGroup group) {
        if (group == null) {
            return ZERO;
        }
        return switch (group) {
            case GROUP_1, GROUP_2 -> new Profile(6.0D, 3);
            case GROUP_3 -> new Profile(3.0D, 2);
            // グループ4 以降は表に書いた種だけが湧く。汚染シダ/汚染水草は専用のスポーン経路
            // (胞子濃度による自然発生など)があるので、表に載せず ZERO のままにしてある。
            default -> ZERO;
        };
    }

    /** {@code type} のスポーン特性。 */
    public static Profile forType(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        // mobs.json で naturalSpawn=false にした種は生態系スポーンから外す(テスト用 Mob 向け)。
        if (!com.yayuy.overgrownvegetation.data.MobStats.naturalSpawn(type)) {
            return ZERO;
        }
        Profile byId = BY_ID.get(key.getPath());
        if (byId != null) {
            return byId;
        }
        return groupDefault(MobGroup.of(type));
    }

    /** {@code type} の weight(相対的な多さ)。0 なら生態系スポーンの対象外。 */
    public static double weight(EntityType<?> type) {
        return forType(type).weight();
    }

    /** {@code type} を 1 回のスポーンパスで候補に入れる確率。1.0 なら毎回。 */
    public static double spawnChance(EntityType<?> type) {
        return forType(type).spawnChance();
    }

    /**
     * {@code type} が水生({@link com.yayuy.overgrownvegetation.entity.AquaticOvergrownMob})か。
     * {@link OvergrownBiomeSpawner} が「陸の地表」ではなく「水中」に湧かせる分岐に使う。
     * エンティティのインスタンスを作らずに判定するためのハードコード表。
     */
    public static boolean isAquatic(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return AQUATIC_IDS.contains(key.getPath());
    }

    private static final java.util.Set<String> AQUATIC_IDS =
            java.util.Set.of("rhizodus", "xenacanthus", "orthacanthus", "megarachne");

    /**
     * <b>洞窟にだけ湧く種</b>か。地表の湧き処理へ回すと、地下の生き物が地面の上に出てしまう。
     * 「洞窟」の判定は地表の植物と同じ {@code caveDepthMargin}
     * ({@link com.yayuy.overgrownvegetation.corruption.CaveGrowth#isCave})。
     */
    public static boolean isCave(EntityType<?> type) {
        ResourceLocation key = BuiltInRegistries.ENTITY_TYPE.getKey(type);
        return CAVE_IDS.contains(key.getPath());
    }

    private static final java.util.Set<String> CAVE_IDS = java.util.Set.of("spelaeopleura");

    private OvergrownSpawnProfile() {
    }
}

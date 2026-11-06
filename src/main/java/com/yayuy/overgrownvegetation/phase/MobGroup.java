package com.yayuy.overgrownvegetation.phase;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 「過繁茂した Mob」の 10 分類。所属はエンティティタイプタグ
 * ({@code #overgrown_vegetation:group_1} 〜 {@code group_10}、{@link ModTags} 参照)で定義され、
 * データパックで差し替えできる。
 *
 * <p>現時点の分類:
 * <ul>
 *     <li>{@link #GROUP_1}(媒介種): バニラ Mob をベースにした「過繁茂した(MOB名)」全種
 *     ({@code parasitized_cow} 等)。</li>
 *     <li>{@link #GROUP_2}(原生生産種): 未実装(枠のみ)。</li>
 *     <li>{@link #GROUP_3}(原生消費種): ファランギオターブス・メガネウラなどの捕食者。</li>
 *     <li>{@link #GROUP_4}(拡大種): 汚染シダ・アルボラ・フィロセンス・ヴェロフィル・スポロフィル、
 *     <b>過繁茂したスカトゥリウム</b>(2026-09-15 にグループ3 から移動)、および
 *     <b>レイドビーコン</b>({@code overgrown_nexus}。2026-09-17 に追加 —— どのグループにも
 *     属していなかったため、グループ4 の索敵が「まだ寄生されていない野生の Mob」とみなして
 *     レイドの中核を殴りに来ていた。グループに入れたことで索敵からも
 *     {@link MobGroupFriendlyFire} の同士討ち禁止からも味方扱いになる)。<br>
 *     レイドビーコンは移動速度 0・{@code OvergrownSpawnProfile} の重み 0・
 *     {@code RaidWaveSpawner} の編成表にも載っていないので、グループに入れても自然湧き・
 *     混成の群れ・増援ウェーブのどれにも現れない。</li>
 *     <li>{@link #GROUP_5}(適応種): ファルキムネス(2026-09-23)。倒された武器への適応
 *     ({@code adaptation/Adaptation})はこのグループだけの特性。</li>
 *     <li>{@link #GROUP_6}(進化種) / {@link #GROUP_7}(脅威種) /
 *     {@link #GROUP_8}(守護種) / {@link #GROUP_9}(侵略級): 未実装(枠のみ)。
 *     グループ9 は将来ハイヴノードの出現条件にする予定(2026-09-19)。</li>
 *     <li>{@link #GROUP_10}(災害級): {@code dorga_archelaus_titanica}(攻城戦ボス
 *     ドルガ=アーケロウス・ティタニカ)。<b>どのフェーズの湧きプールにも入っていない</b>ので、
 *     このタグに入れても自然湧き・レイドの増援には現れない({@code phases.json} の
 *     {@code spawnGroups}/{@code raidWaveGroups} は 1〜3 だけ)。分類のためのタグ。</li>
 * </ul>
 *
 * <p>フェーズごとの挙動:
 * <ul>
 *     <li>フェーズ1: グループ1が過繁茂バイオームに湧くようになる。</li>
 *     <li>フェーズ2: グループ1が汚染地形から離れて他の Mob を襲い、体力・攻撃力が強化される
 *     ({@link GroupPhaseBehavior} / {@link Group1FleeCorruptionGoal})。</li>
 *     <li>フェーズ3: グループ2も過繁茂バイオームに湧くようになる。</li>
 *     <li>フェーズ4: グループ3も過繁茂バイオームに湧くようになる。</li>
 *     <li>フェーズ5: グループ4も過繁茂バイオームに湧くようになる(突入時にレイドも確定で1回発生)。</li>
 *     <li>フェーズ6: グループ4が「種がばらばらな 3〜5 体の群れ」で湧くようになる
 *     ({@code phases.json} の {@code mixedPacks}、{@link OvergrownBiomeSpawner})。</li>
 * </ul>
 */
public enum MobGroup {

    /** 媒介種。 */
    GROUP_1(ModTags.GROUP_1),
    /** 原生生産種(未実装)。 */
    GROUP_2(ModTags.GROUP_2),
    /** 原生消費種。 */
    GROUP_3(ModTags.GROUP_3),
    /** 拡大種。 */
    GROUP_4(ModTags.GROUP_4),
    /** 適応種(ファルキムネス)。倒された武器への適応は {@code adaptation/Adaptation} に実装済み。 */
    GROUP_5(ModTags.GROUP_5),
    /** 進化種(未実装)。 */
    GROUP_6(ModTags.GROUP_6),
    /** 脅威種(未実装)。 */
    GROUP_7(ModTags.GROUP_7),
    /** 守護種(未実装)。 */
    GROUP_8(ModTags.GROUP_8),
    /** 侵略級(未実装。ハイヴノードの出現条件にする予定)。 */
    GROUP_9(ModTags.GROUP_9),
    /** 災害級。攻城戦ボス(ドルガ=アーケロウス・ティタニカ)。 */
    GROUP_10(ModTags.GROUP_10);

    /**
     * {@link #values()} の使い回し。{@code values()} は呼ぶたびに配列を複製するので、
     * 毎 tick 何百回と呼ばれる {@link #of(EntityType)} からは触らせない
     * (ここを外に渡さないこと —— 中身を書き換えられると enum の並びが壊れる)。
     */
    private static final MobGroup[] VALUES = values();

    private final TagKey<EntityType<?>> tag;

    MobGroup(TagKey<EntityType<?>> tag) {
        this.tag = tag;
    }

    public TagKey<EntityType<?>> tag() {
        return tag;
    }

    /** 1〜10 の番号 → {@link MobGroup}。範囲外は {@code null}。 */
    @Nullable
    public static MobGroup byNumber(int n) {
        return (n >= 1 && n <= VALUES.length) ? VALUES[n - 1] : null;
    }

    /** このグループの 1 始まりの番号(GROUP_1 → 1)。 */
    public int number() {
        return ordinal() + 1;
    }

    public boolean contains(EntityType<?> type) {
        return type.is(tag);
    }

    /**
     * {@code type} の所属グループ。複数グループに属していれば番号の小さい方を返す。
     * どのグループにも属していなければ {@code null}。
     */
    @Nullable
    public static MobGroup of(EntityType<?> type) {
        Optional<MobGroup> cached = BY_TYPE.get(type);
        if (cached == null) {
            // 作る側は BY_TYPE に触らないので、computeIfAbsent の入れ子(Recursive update)にはならない。
            cached = BY_TYPE.computeIfAbsent(type, t -> Optional.ofNullable(lookup(t)));
        }
        return cached.orElse(null);
    }

    /**
     * {@link #of(EntityType)} の答えの控え(どこにも属さない種は {@link Optional#empty()})。
     * 索敵の絞り込みやダメージ処理から毎 tick 何百回と引かれ、グループ外の種(プレイヤー・バニラの Mob)だと
     * 毎回 10 個のタグを全部見ることになるので、種ごとに 1 回だけ引いて使い回す。
     *
     * <p>所属はタグだけで決まり、タグの中身が変わるのは結び直したときだけ(データパックの {@code /reload}・
     * ワールドを開いたとき・サーバーからタグを受け取ったとき)。バニラはどの経路でも結び直した直後に
     * {@link TagsUpdatedEvent} を出すので、そこで控えを捨てる({@link Events})。
     * サーバーとクライアントのスレッドから同時に引かれるので ConcurrentHashMap。
     */
    private static final Map<EntityType<?>, Optional<MobGroup>> BY_TYPE = new ConcurrentHashMap<>();

    /** タグを直接見る版(控えを作るときだけ使う)。 */
    @Nullable
    private static MobGroup lookup(EntityType<?> type) {
        for (MobGroup group : VALUES) {
            if (type.is(group.tag)) {
                return group;
            }
        }
        return null;
    }

    /**
     * {@link #of(EntityType)} の {@link Entity} 版。(11-05 に戻したとき: 寄生植物装備 PlantArmor は退避中なので、着けたプレイヤーをグループ 6 とする分岐は外した。戻したら足す)。
     * 種類だけを見る {@link #of(EntityType)} ではこれは効かないので、相手がプレイヤーでありうる判定はこちらを使う。
     */
    @Nullable
    public static MobGroup of(Entity entity) {
        return of(entity.getType());
    }

    /** {@code entity} が {@code group} に属しているか。 */
    public static boolean isIn(Entity entity, MobGroup group) {
        return group.contains(entity.getType());
    }

    /** このグループのタグに含まれる {@link EntityType} を全て返す(順不同、空になることもある)。 */
    public List<EntityType<?>> members() {
        Optional<HolderSet.Named<EntityType<?>>> holders = BuiltInRegistries.ENTITY_TYPE.getTag(tag);
        if (holders.isEmpty()) {
            return List.of();
        }
        List<EntityType<?>> result = new ArrayList<>();
        for (Holder<EntityType<?>> holder : holders.get()) {
            result.add(holder.value());
        }
        return result;
    }

    /** このグループのタグに含まれる {@link EntityType} をランダムに 1 つ返す。空なら {@code null}。 */
    @Nullable
    public EntityType<?> randomMember(RandomSource random) {
        Optional<HolderSet.Named<EntityType<?>>> holders = BuiltInRegistries.ENTITY_TYPE.getTag(tag);
        if (holders.isEmpty() || holders.get().size() == 0) {
            return null;
        }
        return holders.get().getRandomElement(random).map(Holder::value).orElse(null);
    }

    /**
     * タグが結び直されたら {@link #BY_TYPE} を捨てる。サーバー側({@code SERVER_DATA_LOAD})でも
     * クライアント側({@code CLIENT_PACKET_RECEIVED})でも、原因を問わず捨てる(余計に捨てても引き直すだけ)。
     * 優先度を最高にして、ほかの受け手がこのイベントの中で {@link #of(EntityType)} を引いても古い答えを見ないようにする。
     */
    @EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
    public static final class Events {
        @SubscribeEvent(priority = EventPriority.HIGHEST)
        public static void onTagsUpdated(TagsUpdatedEvent event) {
            BY_TYPE.clear();
        }

        private Events() {
        }
    }
}

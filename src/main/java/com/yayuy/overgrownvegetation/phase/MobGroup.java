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
 * 植物側の Mob のグループ(group_1〜10)。所属はエンティティタイプタグ
 * ({@code #overgrown_vegetation:group_1} 〜 {@code group_10}、{@link ModTags} 参照)で定義され、データパックで差し替えできる。
 *
 * <p>2026-11-18 夜 ユーザー決定でコード内の分類を組み直している途中。<b>分類(系統・型・危険度・出るフェーズ)の正本は
 * {@code design/mob_class.json}</b>({@link MobClassTable})と系統のタグ({@link ModTags#LINEAGE_PARASITE} など)。
 * 出るフェーズと森の値段は、もうグループでは決めない。グループがまだ使われている所(縁の上限の箱・キル点の種類・植物側の判定・
 * G5 の適応・遠征のドロップなど)と各グループの中身は {@link ModTags#GROUP_1} の説明を参照。
 * 移し替えの 2 段目で系統のタグか専用のタグへ移し、3 段目でこの enum ごと消す予定。
 * (前の説明の「媒介種・原生生産種 …」の 10 分類と、フェーズごとの湧き方は古くなったので消した。)
 */
public enum MobGroup {

    /** 寄生種(G1)。 */
    GROUP_1(ModTags.GROUP_1),
    /** 環境生物(G2)。 */
    GROUP_2(ModTags.GROUP_2),
    /** 環境生物(G3)。 */
    GROUP_3(ModTags.GROUP_3),
    /** 眷属種(G4)と植物種(萌芽体・ペディキュラ)。 */
    GROUP_4(ModTags.GROUP_4),
    /** 眷属種(G5)。倒された武器への適応は {@code adaptation/Adaptation} に実装済み。 */
    GROUP_5(ModTags.GROUP_5),
    /** ゲスタトリクス(眷属種)とフィロセンス(植物種)。 */
    GROUP_6(ModTags.GROUP_6),
    /** 空(0.4.0 以降の枠)。 */
    GROUP_7(ModTags.GROUP_7),
    /** 空(0.4.0 以降の枠)。 */
    GROUP_8(ModTags.GROUP_8),
    /** 空(0.4.0 以降の枠)。 */
    GROUP_9(ModTags.GROUP_9),
    /** 空(0.4.0 以降の枠)。 */
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

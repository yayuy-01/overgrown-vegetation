package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.data.ResearchState;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import com.yayuy.overgrownvegetation.spore.SporeExposure;

/**
 * エンティティに付随させる一時データ({@link net.neoforged.neoforge.attachment.IAttachmentHolder}経由)。
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, OvergrownVegetation.MODID);

    /**
     * 胞子への曝露カウンタ(胞子濃度帯への連続滞在 tick / 過繫茂バイオームへの連続滞在 tick など)。
     * {@link com.yayuy.overgrownvegetation.effect.SporeInfectionTracker} が「胞子感染」の
     * 付与判定にのみ使う一時カウンタで、ログアウトやワールド再読み込みをまたいで保持する必要が
     * 無いため serialize() しない(セーブされず、再ログイン後は 0 から数え直しになる)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<SporeExposure>> SPORE_EXPOSURE =
            ATTACHMENT_TYPES.register("spore_exposure",
                    () -> AttachmentType.builder(SporeExposure::new).build());

    /**
     * 水への連続滞在 tick 数。{@link com.yayuy.overgrownvegetation.effect.WaterRotTracker} が
     * 「水ぐされ」の発症までの 30 秒を数えるためだけに使う。{@link #SPORE_EXPOSURE} と同じく
     * ログアウトをまたいで保つ必要が無いので serialize() しない(再読み込み後は 0 から数え直し)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> WATER_SOAK =
            ATTACHMENT_TYPES.register("water_soak",
                    () -> AttachmentType.builder(() -> 0).build());

    /**
     * チャンクごとの「胞子濃度への加算 %」。プレイヤーが胞子感染にかかるたびに、そのチャンクへ
     * 段階ごとの {@code chunkSporeBonusPercent} を積む({@link com.yayuy.overgrownvegetation.spore.SporeChunkBonus})。
     * チャンクと一緒にセーブされる。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Double>> SPORE_CHUNK_BONUS =
            ATTACHMENT_TYPES.register("spore_chunk_bonus",
                    () -> AttachmentType.builder(() -> 0.0D).serialize(Codec.DOUBLE).build());

    /**
     * チャンクごとの<b>植物に覆われたマス</b>({@link net.minecraft.core.BlockPos#asLong()} の集合、2026-09-22)。
     *
     * <p>汚染の対応表に変換先が無い「中身を持たないブロック」(金床・砥石・コンクリートなど)は
     * 置き換えずにその場へ残し、代わりにここへ座標を控える。見た目の苔も、右クリックを
     * 受け付けなくする処理も、この記録だけを見て動く
     * ({@link com.yayuy.overgrownvegetation.corruption.OvergrownLayerData})。
     *
     * <p><b>1 マスあたり 8 バイト。</b> 同じことを BlockEntity でやると 1 マスにつき
     * NBT が数十〜百バイト要るうえ、チャンクの BlockEntity 一覧にも載る ——
     * 装飾ブロックだらけの拠点を飲み込むと桁違いに重くなる。
     *
     * <p>チャンクと一緒にセーブされ、チャンクを見始めたプレイヤーへ送られる。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<it.unimi.dsi.fastutil.longs.LongOpenHashSet>>
            OVERGROWN_LAYER = ATTACHMENT_TYPES.register("overgrown_layer",
                    () -> AttachmentType.<it.unimi.dsi.fastutil.longs.LongOpenHashSet>builder(
                                    () -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet())
                            .serialize(Codec.LONG.listOf().xmap(
                                    list -> {
                                        it.unimi.dsi.fastutil.longs.LongOpenHashSet set =
                                                new it.unimi.dsi.fastutil.longs.LongOpenHashSet(list.size());
                                        list.forEach(set::add);
                                        return set;
                                    },
                                    set -> {
                                        java.util.List<Long> list = new java.util.ArrayList<>(set.size());
                                        set.forEach((java.util.function.LongConsumer) list::add);
                                        return list;
                                    }))
                            .build());

    /**
     * この個体に確定した実効 {@code SCALE}(体格倍率)。0 なら「未決定」。
     * {@link com.yayuy.overgrownvegetation.entity.MobSizeVariance} が湧いた瞬間に
     * {@code scale × ランダム(scaleMin〜scaleMax)} を書き込み、ワールド再読み込み後もこの値を
     * 使い回して個体の体格を保つ(serialize する)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> SIZE_VARIANCE =
            ATTACHMENT_TYPES.register("size_variance",
                    () -> AttachmentType.builder(() -> 0.0F).serialize(Codec.FLOAT).build());

    /**
     * プレイヤーごとの研究の進捗(研究ポイント + 開放済みノード)。ワールドセーブに保存し、
     * 死亡しても引き継ぐ({@code copyOnDeath})。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<ResearchState>> RESEARCH =
            ATTACHMENT_TYPES.register("research",
                    () -> AttachmentType.builder(ResearchState::new)
                            .serialize(ResearchState.CODEC)
                            .copyOnDeath()
                            .build());

    /**
     * チャンクごとの「汚染された苗木の設置時刻」の表({@link com.yayuy.overgrownvegetation.worldgen.SaplingRecords})。
     * 読み込まれていない間に育つはずだった苗木を、チャンク読み込み時に追いつかせる
     * ({@link com.yayuy.overgrownvegetation.worldgen.SaplingOfflineGrowth})。苗木の無いチャンクは持たない。
     * チャンクと一緒にセーブされる。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.yayuy.overgrownvegetation.worldgen.SaplingRecords>> SAPLING_PLANTED =
            ATTACHMENT_TYPES.register("sapling_planted",
                    () -> AttachmentType.builder(com.yayuy.overgrownvegetation.worldgen.SaplingRecords::new)
                            .serialize(com.yayuy.overgrownvegetation.worldgen.SaplingRecords.CODEC)
                            .build());

    /**
     * 「大きな未発達な果実」を食べたプレイヤーに、遅れて来る空腹までの残り tick。0 なら予約なし。
     * {@link com.yayuy.overgrownvegetation.effect.DelayedHungerTracker} が毎 tick 1 ずつ減らし、
     * 0 になった瞬間にバニラの「空腹」を付ける。
     *
     * <p>6 分という長さはログアウトやワールド再読み込みをまたぐのが普通なので<b>セーブする</b>
     * ({@link #SPORE_EXPOSURE} などの一時カウンタと違う点)。ただし {@code copyOnDeath} はしない ——
     * 死ねば効果も予約も帳消し、というのが自然だし、この果実で死んだ直後の復活を罰にしないため。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> DELAYED_HUNGER =
            ATTACHMENT_TYPES.register("delayed_hunger",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /**
     * 「寄生植物装備」を着けていた経過 tick。段階の昇格に使う。着けている間だけ進み、ワールドセーブに残る
     * ({@link com.yayuy.overgrownvegetation.item.PlantArmorEvents})。死んでも残す(装備を拾って着け直せば続きから)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> PLANT_ARMOR_TICKS =
            ATTACHMENT_TYPES.register("plant_armor_ticks",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).copyOnDeath().build());

    /** 「寄生植物装備」の一時状態(直前の疲労値・最後に踏んだ位置・光合成中か)。セーブしない。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.yayuy.overgrownvegetation.item.PlantArmorEvents.PlantArmorState>> PLANT_ARMOR_STATE =
            ATTACHMENT_TYPES.register("plant_armor_state",
                    () -> AttachmentType.builder(com.yayuy.overgrownvegetation.item.PlantArmorEvents.PlantArmorState::new).build());

    /**
     * 死んだときに体から外れなかった「寄生植物装備」(ユーザー指定 2026-09-18: 死んでもアイテムとして落とさない)。
     * {@link com.yayuy.overgrownvegetation.item.PlantArmorEvents#onDrops} でドロップから抜いてここへ入れ、
     * リスポーン後に {@link com.yayuy.overgrownvegetation.item.PlantArmorEvents#onClone} が胸へ戻す。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<net.minecraft.world.item.ItemStack>> PLANT_ARMOR_KEPT =
            ATTACHMENT_TYPES.register("plant_armor_kept",
                    () -> AttachmentType.builder(() -> net.minecraft.world.item.ItemStack.EMPTY)
                            .serialize(net.minecraft.world.item.ItemStack.OPTIONAL_CODEC).copyOnDeath().build());


    /**
     * 亜種の種別(2026-09-19、{@code variant/MobVariants})。0 = 未抽選、1 以降 = {@code MobVariant#code()}。
     * 保存するので一度決まった個体は変わらない。クライアントには追跡開始時に同期パケットで配る。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> MOB_VARIANT =
            ATTACHMENT_TYPES.register("mob_variant",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /**
     * 小規模レイドで狙わせるプレイヤーの UUID(2026-09-19、{@code raid/SmallRaidHuntGoal})。
     * 空文字 = 狙いなし。保存するので、一団が未ロードになって戻ってきても狙いを覚えている。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<String>> HUNT_TARGET =
            ATTACHMENT_TYPES.register("hunt_target",
                    () -> AttachmentType.builder(() -> "").serialize(Codec.STRING).build());

    /**
     * この個体が属する「群れ」の番号(2026-09-21、{@link com.yayuy.overgrownvegetation.phase.PackTracker})。
     * 生態系スポーンが 1 か所にまとめて湧かせたひとかたまりに同じ番号が入る。0 = 群れに属さない
     * (スポーンエッグ・レイド・召喚で出た個体)。{@code phases.json} の {@code maxPacks}
     * (ワールドに同時に存在できる群れの数)を数えるのに使うので、セーブする。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> PACK_ID =
            ATTACHMENT_TYPES.register("pack_id",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());


    /**
     * プレイヤーの<b>浸食度</b>(0〜100 %、2026-09-22)。極相({@code engulfed})バイオームに居ると
     * 増え、外へ出ると減る({@code spore/Erosion})。最大体力・食料ゲージの上限・経験値の取得量・
     * 装備の汚染速度に、この % に比例して効く。
     *
     * <p>ログアウトをまたいで<b>保つ</b>(serialize する) —— 極相へ潜った代償は寝て消えては困るため。
     * 他人のプレイヤーも苔むして見える必要があるので、値が動いたら
     * {@code network/ErosionPayload} で周りへ配る。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> EROSION =
            ATTACHMENT_TYPES.register("erosion",
                    () -> AttachmentType.builder(() -> 0.0F).serialize(Codec.FLOAT).copyOnDeath().build());
    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }

    private ModAttachments() {
    }
}

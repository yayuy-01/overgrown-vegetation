package com.yayuy.overgrownvegetation.registry;

import com.mojang.serialization.Codec;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import net.minecraftforge.eventbus.api.IEventBus;
import com.yayuy.overgrownvegetation.port.attachment.AttachmentType;
import com.yayuy.overgrownvegetation.port.registries.DeferredHolder;
import com.yayuy.overgrownvegetation.port.registries.DeferredRegister;
import com.yayuy.overgrownvegetation.port.registries.NeoForgeRegistries;

/**
 * エンティティに付随させる一時データ({@link net.neoforged.neoforge.attachment.IAttachmentHolder}経由)。
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, OvergrownVegetation.MODID);

    /**
     * 水への連続滞在 tick 数。{@link com.yayuy.overgrownvegetation.effect.WaterRotTracker} が
     * 「水ぐされ」の発症までの 30 秒を数えるためだけに使う。
     * ログアウトをまたいで保つ必要が無いので serialize() しない(再読み込み後は 0 から数え直し)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> WATER_SOAK =
            ATTACHMENT_TYPES.register("water_soak",
                    () -> AttachmentType.builder(() -> 0).build());

    /**
     * 胞子の暴露量(%秒、2026-11-04 感染システムの第 1 段)。{@link com.yayuy.overgrownvegetation.spore.SporeExposureTracker} が
     * 積むときに初めて付ける(胞子と無縁の個体には付かない)。11-04 第 2 段から保存する(感染と Mob の 20 ダメージに意味ができたため。
     * プレイヤーも Mob も。仮決め)。死んだら引き継がない(復活は 0 から)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.yayuy.overgrownvegetation.spore.SporeExposure>> SPORE_EXPOSURE =
            ATTACHMENT_TYPES.register("spore_exposure",
                    () -> AttachmentType.builder(com.yayuy.overgrownvegetation.spore.SporeExposure::new)
                            .serialize(com.yayuy.overgrownvegetation.spore.SporeExposure.CODEC).build());

    /**
     * チャンクごとの「このチャンクに出した萌芽体の数」(2026-10-23 胞子の場 第 2 段 E・F。胞子感染で積む濃度の加算
     * spore_chunk_bonus は無くした)。胞子の場の萌芽体の数(1〜2)より少なければ、読み込んだときに足りない分を根付いた姿で置く。
     * チャンクと一緒にセーブされる。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> SPROUTS_PLACED =
            ATTACHMENT_TYPES.register("sprouts_placed",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /**
     * チャンクごとの「洞窟の床に芽の点を置いた回数」(2026-11-04 胞子の場の地下の層)。場の洞窟の発芽の回数より少なければ、
     * 読み込んだときに足りない分の点を洞窟の床に置く。チャンクと一緒にセーブされる。
     */
    /**
     * チャンクごとの<b>菌糸の染み下りの進み</b>(2026-11-07、{@code spore/field/MyceliumSeep})。地表から世界の底までのうち、
     * 処理し終えた割合 × 1,000,000(0 = まだ、1,000,000 = 底まで)。チャンクと一緒にセーブされる(読み込まれていない間の分は、次に読み込んだときに追いつく)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> MYCELIUM_SEEP =
            ATTACHMENT_TYPES.register("mycelium_seep",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT, v -> v != 0).build());

    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> CAVE_SPROUTS_PLACED =
            ATTACHMENT_TYPES.register("cave_sprouts_placed",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());

    /**
     * チャンクごとの<b>胞子の粉を払った升</b>(2026-10-23 胞子の場と風 第 2 段の追加)。チャンクを 4×4 ブロックの升 16 個に分けた 16 ビット
     * (ビット = (z / 4) * 4 + x / 4)。払った升は粉を描かない。降り積もりが {@link #SWEPT_DUST_REFILL} まで戻ったら全部消える。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> SWEPT_DUST =
            ATTACHMENT_TYPES.register("swept_dust",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT, v -> v != 0).build());

    /** 最後に払う直前のそのチャンクの降り積もり(ここまで戻ったら {@link #SWEPT_DUST} を消す)。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> SWEPT_DUST_REFILL =
            ATTACHMENT_TYPES.register("swept_dust_refill",
                    () -> AttachmentType.builder(() -> 0.0F).serialize(Codec.FLOAT, v -> v != 0.0F).build());

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
                            // 2026-09-24 軽量化(監査 2000): LONG.listOf() は要素ごとに Long / LongTag / DataResult を
                            // 作っていた。LONG_STREAM は NbtOps が LongArrayTag を直接読み書きする。保存の形は前と同じ
                            // LongArrayTag(同じ順番)で、前の形で保存された空の集合(空の ListTag)も getLongStream の
                            // 既定の実装で読める。
                            .serialize(Codec.LONG_STREAM.xmap(
                                    stream -> new it.unimi.dsi.fastutil.longs.LongOpenHashSet(stream.toArray()),
                                    set -> java.util.Arrays.stream(set.toLongArray())))
                            .build());

    /**
     * 覆った砂・砂利が草ブロックになる時刻(2026-11-01 ユーザー指定)。マス({@code BlockPos#asLong}) → その時刻(ゲーム時刻)。
     * {@link com.yayuy.overgrownvegetation.corruption.CoveredSandToGrass} が書く。サーバーだけ(送らない)。保存の形は COVER_CREEP と同じ。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>>
            COVER_SETTLE = ATTACHMENT_TYPES.register("cover_settle",
                    () -> AttachmentType.<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>builder(
                                    () -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap())
                            .serialize(Codec.LONG_STREAM.xmap(
                                    stream -> {
                                        long[] a = stream.toArray();
                                        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
                                        for (int i = 0; i + 1 < a.length; i += 2) {
                                            map.put(a[i], a[i + 1]);
                                        }
                                        return map;
                                    },
                                    map -> {
                                        long[] a = new long[map.size() * 2];
                                        int i = 0;
                                        for (var e : map.long2LongEntrySet()) {
                                            a[i++] = e.getLongKey();
                                            a[i++] = e.getLongValue();
                                        }
                                        return java.util.Arrays.stream(a);
                                    }))
                            .build());

    /**
     * 覆いを剥がした時刻(2026-11-03 ユーザー指定「剥がしたマスはしばらく這い上がる苔が付かない」)。マス → 剥がしたゲーム時刻。
     * {@link com.yayuy.overgrownvegetation.corruption.CoverCreepData} が書き、猶予が過ぎたものは読むときに捨てる。サーバーだけ(送らない)。保存の形は COVER_SETTLE と同じ。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>>
            COVER_PEELED = ATTACHMENT_TYPES.register("cover_peeled",
                    () -> AttachmentType.<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>builder(
                                    () -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap())
                            .serialize(Codec.LONG_STREAM.xmap(
                                    stream -> {
                                        long[] a = stream.toArray();
                                        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
                                        for (int i = 0; i + 1 < a.length; i += 2) {
                                            map.put(a[i], a[i + 1]);
                                        }
                                        return map;
                                    },
                                    map -> {
                                        long[] a = new long[map.size() * 2];
                                        int i = 0;
                                        for (var e : map.long2LongEntrySet()) {
                                            a[i++] = e.getLongKey();
                                            a[i++] = e.getLongValue();
                                        }
                                        return java.util.Arrays.stream(a);
                                    }))
                            .build());

    /**
     * 這い上がる苔の記録(案 H の 28、2026-11-01 ユーザー指定: 覆いに接して<b>新しく置かれた</b>ブロックだけに帯が出る)。
     * マス({@code BlockPos#asLong}) → {@code (置いたときのゲーム時刻 << 6) | 置いたときに覆われていた隣の向きの印(ordinal のビット)}。
     * {@link com.yayuy.overgrownvegetation.corruption.CoverCreepData} が書き、チャンクと一緒に保存し、見ているプレイヤーへ送る。
     * 保存の形は [マス, 値, マス, 値, …] の LongArrayTag。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>>
            COVER_CREEP = ATTACHMENT_TYPES.register("cover_creep",
                    () -> AttachmentType.<it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap>builder(
                                    () -> new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap())
                            .serialize(Codec.LONG_STREAM.xmap(
                                    stream -> {
                                        long[] a = stream.toArray();
                                        it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap map = new it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap();
                                        for (int i = 0; i + 1 < a.length; i += 2) {
                                            map.put(a[i], a[i + 1]);
                                        }
                                        return map;
                                    },
                                    map -> {
                                        long[] a = new long[map.size() * 2];
                                        int i = 0;
                                        for (var e : map.long2LongEntrySet()) {
                                            a[i++] = e.getLongKey();
                                            a[i++] = e.getLongValue();
                                        }
                                        return java.util.Arrays.stream(a);
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
     * ({@link #WATER_SOAK} などの一時カウンタと違う点)。ただし {@code copyOnDeath} はしない ——
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
     * <b>墓標</b>(2026-09-25、群れ v2)。もとは「群れ」の番号(2026-09-21、{@code phase/PackTracker})で、
     * 生態系スポーンの個体にセーブされていた。群れは {@link #SWARM} に置き換わったが、登録ごと消すと
     * 古いセーブの個体を読み込むたびに NeoForge が「知らない attachment」のエラーをログへ出す(レビュー指摘)。
     * そこで名前だけ残し、<b>読んで捨てる・二度と書かない</b>(serialize の条件が常に false)。
     * {@code PackTracker} は 2026-09-25 に消したので、もう誰も読み書きしない。
     * 数版たって古いセーブが無くなったら登録ごと消してよい。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> PACK_ID =
            ATTACHMENT_TYPES.register("pack_id",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT, v -> false).build());

    /**
     * プレイヤーの<b>浸食度</b>(0〜100 %、2026-09-22)。増やす仕組み(極相のバイオームに居ると増える {@code spore/Erosion})は
     * 2026-10-14 に止め、極相は 2026-10-30 に消した。今は値を足す所も読む所も無い。セーブに残った値を読み捨てないよう
     * 登録だけ残す(浸食度を作り直すか消すかは後で決める)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Float>> EROSION =
            ATTACHMENT_TYPES.register("erosion",
                    () -> AttachmentType.builder(() -> 0.0F).serialize(Codec.FLOAT).copyOnDeath().build());
    /**
     * プレイヤーの胞子感染の、今の段階に入ってから進んだ tick(10-14 の 10 段階。2026-11-04 から再び使う、
     * {@code effect/SporeInfectionEffects})。10 分(effects.json の stageTicks)で 1 段上がる。保存する・死んだら引き継がない。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> SPORE_STAGE_TICKS =
            ATTACHMENT_TYPES.register("spore_stage_ticks",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    /**
     * プレイヤーの疲労(2026-11-13 サバイバルの背骨 段 1、{@code survival/FatigueTracker})。保存する・死んだら引き継がない(復活は 0。ユーザー指定)。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.yayuy.overgrownvegetation.survival.Fatigue>> FATIGUE =
            ATTACHMENT_TYPES.register("fatigue",
                    () -> AttachmentType.builder(com.yayuy.overgrownvegetation.survival.Fatigue::new)
                            .serialize(com.yayuy.overgrownvegetation.survival.Fatigue.CODEC).build());
    /** 暖花の丸薬で最大体力の低下を免れた段の数(2026-10-16、effect/MedicineEffects)。治ると 0。保存する。 */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Integer>> SPORE_HEALTH_RELIEF =
            ATTACHMENT_TYPES.register("spore_health_relief",
                    () -> AttachmentType.builder(() -> 0).serialize(Codec.INT).build());
    /**
     * ガイドブックの Mob の章に載った Mob(2026-10-15、{@code guide/GuidebookDiscovery})。<b>人ごと</b>で、その Mob を倒すか
     * その Mob に倒されると足される(ユーザー指定)。ログアウトと死をまたいで保つ。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<java.util.Set<net.minecraft.resources.ResourceLocation>>>
            GUIDEBOOK_MOBS = ATTACHMENT_TYPES.register("guidebook_mobs",
                    () -> AttachmentType.<java.util.Set<net.minecraft.resources.ResourceLocation>>builder(() -> new java.util.HashSet<>())
                            .serialize(net.minecraft.resources.ResourceLocation.CODEC.listOf().xmap(
                                    list -> (java.util.Set<net.minecraft.resources.ResourceLocation>) new java.util.HashSet<>(list),
                                    set -> java.util.List.copyOf(set)))
                            .copyOnDeath()
                            .build());
    /**
     * 最初のログインでガイドブックを渡したか(2026-10-15 ユーザー指定「ガイドブックは最初のログインで手に入る」)。人ごと・死をまたいで残る。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<Boolean>> GUIDEBOOK_GIVEN =
            ATTACHMENT_TYPES.register("guidebook_given",
                    () -> AttachmentType.builder(() -> false).serialize(Codec.BOOL).copyOnDeath().build());
    /**
     * 汚染したスポナー・試練のスポナーの<b>元の湧く Mob の登録</b>(2026-10-09、{@code corruption/SpawnerCorruption})。
     * スポナーのブロックエンティティに付き、覆いが剥がれたら(浄化・工兵の整備)この登録へ戻す。読むときは {@code getExistingData}・{@code hasData}。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<net.minecraft.nbt.CompoundTag>> SPAWNER_ORIGINAL =
            ATTACHMENT_TYPES.register("spawner_original",
                    () -> AttachmentType.builder(() -> new net.minecraft.nbt.CompoundTag())
                            .serialize(net.minecraft.nbt.CompoundTag.CODEC).build());

    /**
     * 汎用の感染体の印(2026-11-15、{@code infect/GenericInfection})。変わった個体だけ保存する。クライアントへは追跡開始時と変わったときに配る。
     */
    public static final DeferredHolder<AttachmentType<?>, AttachmentType<com.yayuy.overgrownvegetation.infect.GenericInfected>> GENERIC_INFECTED =
            ATTACHMENT_TYPES.register("generic_infected",
                    () -> AttachmentType.builder(com.yayuy.overgrownvegetation.infect.GenericInfected::new)
                            .serialize(com.yayuy.overgrownvegetation.infect.GenericInfected.CODEC, com.yayuy.overgrownvegetation.infect.GenericInfected::shouldSave).build());

    public static void register(IEventBus modBus) {
        ATTACHMENT_TYPES.register(modBus);
    }

    private ModAttachments() {
    }
}

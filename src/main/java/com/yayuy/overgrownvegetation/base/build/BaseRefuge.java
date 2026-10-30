package com.yayuy.overgrownvegetation.base.build;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.raid.Raider;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

import javax.annotation.Nullable;
import java.util.EnumSet;
import java.util.Set;

/**
 * 同盟軍の基地は避難場所(順 2 の着手順 3、2026-10-04)。<b>ユーザー指定(10-01)</b>: TD のときも普段も、プレイヤーの避難場所
 * —— 壁の中で敵が湧かない。仕様は {@code docs/ORDER2_SPEC.md} の「着手順 3 の中身の案」。
 *
 * <p>Claude 仮決め:
 * <ul>
 *     <li><b>範囲</b>: 陥落していない基地の壁の中(浄化圏の和 + 外周の帯、{@link BaseSite#contains})の上から下まで(地下の洞窟も)。
 *     陥落した基地は避難場所にならない(順 5 で G7 の前哨基地になる)。</li>
 *     <li><b>止める Mob</b>: 過繁茂の Mob(グループ 1〜10)とバニラの敵対 Mob(湧きの区分が MONSTER か {@link Enemy})。兵士が撃つ相手と同じ。
 *     止めない: グループの無いこの MOD の種(兵士・巨大樹の巣の節など)・ボス・タグ {@code refuge_exempt}(汚染シダ・汚染水草・レイドの苗床・
 *     ティタニカ。理由は {@link ModTags#REFUGE_EXEMPT})・バニラのレイドの襲撃者(レイドは襲撃者を一覧に入れてから足すので、取り消すとレイドが 40 分止まる)。</li>
 *     <li><b>止める湧き方</b>: 世界が湧かせるもの({@link #WORLD_SPAWNS}: 自然湧き・パトロール・援軍・イベント・Mob が呼んだもの・騎手)。
 *     スポナー・スポーンエッグ・コマンド・ディスペンサー・繁殖・変換は止めない(プレイヤーの仕掛けを壊さない)。歩いて入ってくるのも止めない。</li>
 * </ul>
 *
 * <p>止め方: バニラの自然湧きは {@link MobSpawnEvent.SpawnPlacementCheck} を FAIL(作る前に落とす。軽い)。それ以外 —— この MOD の湧かせ方は
 * どれも {@code EntityType#create(..., MobSpawnType, ...)} を通る —— は {@link FinalizeSpawnEvent} で湧きを取り消す(create が null を返し、
 * 呼んだ側はどれも null を扱う。TD の実体化も、取り消された分は予算に戻り、群れが輪に沿ってずれる)。Join は取り消さない
 * (チャンクと一緒に読み込まれた Mob・歩いて入った Mob を消さないため)。レイドの苗床の置き場所と死骸の孵化は、取り消す前に候補から外す
 * ({@link #covers}・{@link #blocksSpawn})。生成の裏の作業場(別スレッド)からの湧きは見ない(台帳は本体のスレッドのもの。
 * 生成のときに湧くのは動物だけ)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class BaseRefuge {

    /** 止める湧き方(世界が湧かせるもの)。 */
    static final Set<MobSpawnType> WORLD_SPAWNS = EnumSet.of(MobSpawnType.NATURAL, MobSpawnType.CHUNK_GENERATION, MobSpawnType.PATROL,
            MobSpawnType.REINFORCEMENT, MobSpawnType.EVENT, MobSpawnType.MOB_SUMMONED, MobSpawnType.JOCKEY);

    /** 世界の (x, z) が、そのディメンションの陥落していない基地の壁の中か。 */
    public static boolean covers(ServerLevel level, double x, double z) {
        return siteAt(level, Mth.floor(x), Mth.floor(z)) != null;
    }

    /** 世界の (x, z) を壁の中に含む、陥落していない基地(無ければ null)。 */
    @Nullable
    public static BaseSite siteAt(ServerLevel level, int x, int z) {
        for (BaseSite site : BaseSites.get(level.getServer()).all()) {
            if (site.fallen() || !site.dimension.equals(level.dimension())) {
                continue;
            }
            int[] box = site.refugeBox();
            if (x < box[0] || x > box[2] || z < box[1] || z > box[3]) {
                continue;
            }
            if (site.contains(x, z, site.level())) {
                return site;
            }
        }
        return null;
    }

    /** この MOD の湧かせ方が作る前に候補から外す口: {@code pos} に {@code type} を湧かせないか(世界が湧かせるもののとき)。 */
    public static boolean blocksSpawn(ServerLevel level, EntityType<?> type, BlockPos pos) {
        return isBlockedType(type) && covers(level, pos.getX() + 0.5D, pos.getZ() + 0.5D);
    }

    /** 種で決まる「壁の中で湧かせない」敵か(個体を見ない自然湧きの下調べ)。 */
    public static boolean isBlockedType(EntityType<?> type) {
        Boolean fixed = fixedVerdict(type);
        return fixed != null ? fixed : type.getCategory() == MobCategory.MONSTER;
    }

    /** 個体で決まる「壁の中で湧かせない」敵か(種 + {@link Enemy}。バニラのレイドの襲撃者は止めない)。 */
    public static boolean isBlocked(Mob mob) {
        Boolean fixed = fixedVerdict(mob.getType());
        if (fixed != null) {
            return fixed;
        }
        if (mob instanceof Raider raider && raider.getCurrentRaid() != null) {
            return false;
        }
        return mob.getType().getCategory() == MobCategory.MONSTER || mob instanceof Enemy;
    }

    /** 種だけで決まる答え(止めない種・グループのある種・グループの無いこの MOD の種)。どれでもなければ null(バニラ・ほかの MOD の種)。 */
    @Nullable
    private static Boolean fixedVerdict(EntityType<?> type) {
        if (type.is(ModTags.REFUGE_EXEMPT) || type.is(Tags.EntityTypes.BOSSES)) {
            return Boolean.FALSE;
        }
        if (MobGroup.of(type) != null) {
            return Boolean.TRUE;
        }
        if (OvergrownVegetation.MODID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(type).getNamespace())) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** 本体のスレッドの {@link ServerLevel}(生成の作業場からなら null)。 */
    @Nullable
    private static ServerLevel mainLevel(net.minecraft.world.level.ServerLevelAccessor accessor) {
        ServerLevel level = accessor.getLevel();
        return level != null && level.getServer().isSameThread() ? level : null;
    }

    /** バニラの自然湧き: 壁の中の敵はエンティティを作る前に落とす。 */
    @SubscribeEvent
    public static void onSpawnPlacementCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (!WORLD_SPAWNS.contains(event.getSpawnType()) || !isBlockedType(event.getEntityType())) {
            return;
        }
        ServerLevel level = mainLevel(event.getLevel());
        BlockPos pos = event.getPos();
        if (level != null && covers(level, pos.getX() + 0.5D, pos.getZ() + 0.5D)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }

    /**
     * それ以外の世界の湧き(この MOD の湧かせ方・パトロール・援軍・村の襲撃・TD の実体化など): 壁の中の敵は湧きを取り消す。イベントも取り消して
     * {@code finalizeSpawn} を飛ばす(湧きの取り消しだけだと {@code finalizeSpawn} は走り、ゾンビの騎乗用のニワトリなどが世界に残る)。
     */
    @SubscribeEvent
    public static void onFinalizeSpawn(FinalizeSpawnEvent event) {
        if (!WORLD_SPAWNS.contains(event.getSpawnType()) || !isBlocked(event.getEntity())) {
            return;
        }
        ServerLevel level = mainLevel(event.getLevel());
        if (level != null && covers(level, event.getX(), event.getZ())) {
            event.setSpawnCancelled(true);
            event.setCanceled(true);
        }
    }

    private BaseRefuge() {
    }
}

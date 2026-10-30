package com.yayuy.overgrownvegetation.difficulty;

import com.yayuy.overgrownvegetation.ModTags;
import com.yayuy.overgrownvegetation.OvergrownVegetation;
import com.yayuy.overgrownvegetation.phase.MobGroup;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobDespawnEvent;

/**
 * 難易度ピースフルでは、この MOD の敵を出さない(2026-09-26、ユーザー報告「ピースフルでもレイドやアルボラが湧いてしまう」)。
 *
 * <p>バニラはピースフルの敵対 Mob を 3 か所で止める: いる個体は {@code Monster#shouldDespawnInPeaceful} で毎 tick 消し、
 * 自然湧きは {@code Monster#checkMonsterSpawnRules} で落とし、レイドは {@code Raid#tick} で止める。この MOD の Mob は
 * ほとんどが {@code PathfinderMob} で、湧かせ方も自前(バニラの自然湧きを通らない)なので、どれも効いていなかった。
 *
 * <h2>敵 = グループ 1〜10 の種のうち、{@link ModTags#PEACEFUL_ALLOWED} に無いもの</h2>
 * 既定でタグにあるのは<b>環境生物</b> —— 原生生産種(グループ 2。何も襲わない)と原生消費種(グループ 3。
 * グループ 2 を狩る食物連鎖はあるが<b>プレイヤーは襲わない</b>)、それに汚染シダ・汚染水草(動かない植物。
 * 汚染は環境の側として続く)。グループの無い種(ハイヴノード・巣の蔦)は何も襲わないので残る。
 * 新しく足した種は、タグに入れない限り敵になる。
 *
 * <p><b>グループ 3 は 2026-10-03 に敵から外した。</b>それまでは「コエルロサウラヴスを除いてプレイヤーも襲う
 * ({@code Predation#isIntruder})」という理由でグループごと敵にしていた(仮決め)。
 * ユーザー指定で {@code isIntruder} がプレイヤーを返さなくなり、根拠が消えたため。
 * <b>殴られたら反撃する</b>のは残っているが、それはプレイヤーが手を出したときだけなので、
 * ピースフルで消す理由にはならない。
 *
 * <h2>止め方</h2>
 * <ul>
 *     <li><b>いる敵</b>: {@link MobDespawnEvent} で消す(バニラの {@code Monster} と同じ口・同じ間合い = 毎 tick の
 *     {@code checkDespawn}。名札・永続の個体も消える)。拾ったプレイヤーの持ち物だけは落とす。レイドビーコンもこれで消え、
 *     レイドの台帳・強制ロードはビーコンが消えたときの後始末({@code RaidData#onNexusRemoved})で片付く
 *     (強制ロードの中にいるので、ワールドを開いた直後でもすぐ消える)。</li>
 *     <li><b>湧かせる側</b>: 生態系の補充・外のはぐれは敵の種を候補から外す(原生生産種の目標数は変えない)。生態系防衛は出さない。
 *     死骸は敵の種を孵さず汚染シダになる。</li>
 *     <li><b>イベント</b>: レイド・小規模レイド・TD・攻城戦は始めない(コマンドも断る)。TD は予告中・侵攻中なら中止、攻城戦は
 *     止める(ボスは {@code checkDespawn} を通らない)。次のイベントまでの時計(周期レイド・小規模レイド・TD の待ち・
 *     攻城戦の待ち時間)はピースフルの間は止める(進めたままだと、ピースフルを抜けた途端に溜まったイベントが一度に始まる)。
 *     フェーズ昇格のレイド・攻城戦は済んだことにしないので、ピースフルを抜けてから起きる。</li>
 * </ul>
 * 湧かせ方を新しく足すときは {@link #blocksSpawn} を通す(通さなくても次の tick に消えるが、作っては消すのを繰り返す)。
 */
@EventBusSubscriber(modid = OvergrownVegetation.MODID, bus = EventBusSubscriber.Bus.GAME)
public final class Peaceful {

    /** 難易度がピースフルか(難易度はサーバーに 1 つなので、どのディメンションで見ても同じ)。 */
    public static boolean is(LevelAccessor level) {
        return level.getDifficulty() == Difficulty.PEACEFUL;
    }

    /** ピースフルで消す・湧かせない種か(クラスの説明「敵」)。 */
    public static boolean isEnemy(EntityType<?> type) {
        return MobGroup.of(type) != null && !type.is(ModTags.PEACEFUL_ALLOWED);
    }

    /** いまピースフルで、{@code type} を湧かせてはいけないか。 */
    public static boolean blocksSpawn(LevelAccessor level, EntityType<?> type) {
        return is(level) && isEnemy(type);
    }

    /** いる敵を消す(バニラの {@code Mob#checkDespawn} の中、毎 tick)。 */
    @SubscribeEvent
    public static void onMobDespawn(MobDespawnEvent event) {
        Mob mob = event.getEntity();
        if (!is(mob.level()) || !isEnemy(mob.getType())) {
            return;
        }
        mob.dropPreservedEquipment();
        event.setResult(MobDespawnEvent.Result.ALLOW);
    }

    private Peaceful() {
    }
}

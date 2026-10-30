package com.yayuy.overgrownvegetation.mixin;

import com.yayuy.overgrownvegetation.td.TdHostiles;
import net.minecraft.server.commands.SummonCommand;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * {@code /summon} で置いた Mob に、TD の侵攻で消さない・止めない「プレイヤーが置いた Mob」の印({@link TdHostiles#EXEMPT_TAG})を
 * 乗り手ごと付ける。
 *
 * <p>印はふつう NeoForge の {@code FinalizeSpawnEvent}(湧き方 COMMAND)で付くが、<b>NBT 付きの {@code /summon} は
 * {@code finalizeSpawn} を通らない</b>(バニラは NBT が無いときだけ呼ぶ)。印の無い敵対 Mob は侵攻の範囲の中で世界へ入るのを
 * 取り消され({@code TdHostiles#onJoin})、コマンドは「UUID が重複」と失敗していた(レビュー指摘 2026-09-25、2026-09-27 に直した)。
 * そこで {@code SummonCommand#createEntity} が世界へ足す直前({@code tryAddFreshEntityWithPassengers} の引数)で印を付ける。
 * {@code /execute summon} も同じ口を通る。
 */
@Mixin(SummonCommand.class)
public abstract class SummonCommandTdExemptMixin {

    @ModifyArg(method = "createEntity", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tryAddFreshEntityWithPassengers(Lnet/minecraft/world/entity/Entity;)Z"))
    private static Entity overgrown_vegetation$markSummoned(Entity entity) {
        TdHostiles.markCommandPlaced(entity);
        return entity;
    }
}

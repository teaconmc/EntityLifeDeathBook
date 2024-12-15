package org.teacon.eldbook.mixin;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.EntityAccess;
import net.minecraft.world.level.entity.EntityInLevelCallback;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.teacon.eldbook.EntityLifeDeathBook;

import java.time.OffsetDateTime;

@Mixin(PersistentEntitySectionManager.class)
public abstract class PersistentEntitySectionManagerMixin {
    @Redirect(method = "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At(value = "INVOKE", target = "setLevelCallback(Lnet/minecraft/world/level/entity/EntityInLevelCallback;)V"))
    public void onAddEntityWithoutEvent(EntityAccess access, EntityInLevelCallback eilc) {
        access.setLevelCallback(EntityLifeDeathBook.ENABLED && access instanceof Entity e ? new EntityLifeDeathBook.WrappedCallback(e, eilc) : eilc);
    }

    @Inject(method = "addEntityWithoutEvent(Lnet/minecraft/world/level/entity/EntityAccess;Z)Z", at = @At("RETURN"))
    public void onReturn(EntityAccess access, boolean load, CallbackInfoReturnable<Boolean> cir) {
        if (EntityLifeDeathBook.ENABLED && access instanceof Entity e && cir.getReturnValueZ()) {
            var time = OffsetDateTime.now();
            // noinspection resource
            var dim = e.level().dimension();
            var stacktrace = ExceptionUtils.getStackFrames(new Throwable());
            var type = load ? EntityLifeDeathBook.Type.LOAD : EntityLifeDeathBook.Type.CREATE;
            EntityLifeDeathBook.log(time, type, e.getUUID(), e.getType(), dim, e.position(), stacktrace);
        }
    }
}

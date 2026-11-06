package dev.maxwellyoung.t3craft.mixin;
import net.minecraft.world.entity.decoration.Mannequin;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
@Mixin(Mannequin.class)
public interface MannequinAccess {
 @Invoker("setImmovable") void t3craft$immovable(boolean value);
 @Invoker("getDescription") Component t3craft$getDescription();
 @Invoker("setDescription") void t3craft$description(Component value);
}

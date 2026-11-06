package dev.maxwellyoung.t3craft.mixin;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Client-only speech bubbles need the display setters vanilla keeps for its own data loading. */
@Mixin(Display.class)
public interface DisplayAccess {
 @Invoker("setBillboardConstraints") void t3craft$billboard(Display.BillboardConstraints value);
 @Invoker("setViewRange") void t3craft$viewRange(float value);
}

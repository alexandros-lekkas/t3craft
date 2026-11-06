package dev.maxwellyoung.t3craft.mixin;
import net.minecraft.world.entity.decoration.ItemFrame;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
@Mixin(ItemFrame.class)
public interface ItemFrameAccess {
 @Accessor("fixed") void t3craft$fixed(boolean value);
}

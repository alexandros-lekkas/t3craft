package dev.maxwellyoung.t3craft.mixin;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
/** Text, wrap width and background for the agents' speech bubbles (see OfficeTalks). */
@Mixin(Display.TextDisplay.class)
public interface TextDisplayAccess {
 @Invoker("setText") void t3craft$text(Component value);
 @Invoker("setLineWidth") void t3craft$lineWidth(int value);
 @Invoker("setBackgroundColor") void t3craft$background(int argb);
}

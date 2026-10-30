package redero.abyssaldepths.client;

import org.joml.Matrix4f;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.network.chat.Component;
import net.minecraft.ChatFormatting;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import redero.abyssaldepths.AbyssalDepths;

/** The epithet under an artifact's title ("the Stillness Below"): small (3/4 size), dark, italic, centred. */
public record ArtifactEpithet(String text) implements TooltipComponent {
    private static final float SCALE = 0.75F;

    @EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
    public static final class Factory {
        @SubscribeEvent
        public static void register(RegisterClientTooltipComponentFactoriesEvent event) {
            event.register(ArtifactEpithet.class, Line::new);
        }
    }

    record Line(ArtifactEpithet epithet) implements ClientTooltipComponent {
        private Component styled() {
            return Component.literal(epithet.text).withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC);
        }

        @Override
        public int getHeight() {
            return 8;
        }

        @Override
        public int getWidth(Font font) {
            return (int) Math.ceil(font.width(styled()) * SCALE);
        }

        @Override
        public void renderText(Font font, int x, int y, Matrix4f matrix, MultiBufferSource.BufferSource buffer) {
            final Matrix4f small = new Matrix4f(matrix).translate(x, y, 0).scale(SCALE, SCALE, 1.0F);
            font.drawInBatch(styled(), 0, 0, 0xFFFFFFFF, true, small, buffer, Font.DisplayMode.NORMAL, 0, LightTexture.FULL_BRIGHT);
        }
    }
}

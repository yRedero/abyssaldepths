package redero.abyssaldepths.client;

import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderPlayerEvent;
import redero.abyssaldepths.AbyssalDepths;

/** Players with a Mirage under way are drawn translucent and purple, for everyone ({@code mirage/<id>}). */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class MirageClient {
    private static final float ALPHA = 0.4F;
    private static final int TINT = 0xB070FF;
    /** Entity ids of players under a mirage, with the client tick until which. */
    private static final Int2LongOpenHashMap ACTIVE = new Int2LongOpenHashMap();

    private MirageClient() {
    }

    public static void set(int entityId, int ticks) {
        final Minecraft mc = Minecraft.getInstance();
        if (ticks <= 0 || mc.level == null) {
            ACTIVE.remove(entityId);
        } else {
            ACTIVE.put(entityId, mc.level.getGameTime() + ticks);
        }
    }

    @SubscribeEvent
    public static void onRender(RenderPlayerEvent.Pre event) {
        final Minecraft mc = Minecraft.getInstance();
        if (FadeDraw.active || ACTIVE.isEmpty() || mc.level == null || ACTIVE.get(event.getEntity().getId()) < mc.level.getGameTime()) {
            return;
        }
        event.setCanceled(true);
        FadeDraw.drawHere(event.getEntity(), event.getPartialTick(), event.getPoseStack(), event.getMultiBufferSource(), ALPHA, TINT, null);
    }
}

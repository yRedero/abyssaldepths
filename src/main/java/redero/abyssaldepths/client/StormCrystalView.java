package redero.abyssaldepths.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import redero.abyssaldepths.AbyssalDepths;
import redero.abyssaldepths.block.StormClusterBlock;
import redero.abyssaldepths.worldgen.TunnelLayer;

/**
 * The storm crystals waking: when a dune storm charges a spire (its blocks turn charged), the charge rises slowly
 * from the ground up through it: its dull skin (the dull texture, drawn over it) fades away from below with a soft
 * edge, a faint light at the front, sparks and a slowly rising tone; at the tip it settles with a soft pulse and a
 * crack. The small crystals round it burst into sparks.
 * Spires already charged when they come into sight just glow (it only plays as it happens).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class StormCrystalView {
    private static final int RADIUS_CHUNKS = 4, CLIMB_TICKS = 140, FLASH_TICKS = 40;
    /** How soft the edge between dull and charged is (blocks either side of the front). */
    private static final float SOFT = 1.4F;
    /** Spires seen (by the column's x, z): their foot, height and whether charged when last looked at. */
    private record Spire(int x, int z, int foot, int height, boolean charged) {
    }

    private static final Map<Long, Spire> SPIRES = new HashMap<>();
    private static final Map<Long, Boolean> CLUSTERS = new HashMap<>();
    /** Spires waking now: which, and the tick it began. */
    private static final List<long[]> SURGES = new ArrayList<>();

    private StormCrystalView() {
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            SPIRES.clear();
            CLUSTERS.clear();
            SURGES.clear();
            return;
        }
        final long now = level.getGameTime();
        SURGES.removeIf(s -> now - s[1] > CLIMB_TICKS + FLASH_TICKS);
        for (long[] s : SURGES) { // sparks on the rising band; the crack at the tip
            final Spire spire = SPIRES.get(s[0]);
            if (spire == null) {
                continue;
            }
            final long t = now - s[1];
            if (t <= CLIMB_TICKS) {
                final double y = front(spire, t);
                for (int k = 0; k < (t % 2 == 0 ? 1 : 0); k++) {
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, spire.x() + 0.5 + (level.random.nextDouble() - 0.5) * 1.3, y,
                            spire.z() + 0.5 + (level.random.nextDouble() - 0.5) * 1.3, 0, 0.05, 0);
                }
                if (t % 12 == 0) {
                    level.playLocalSound(spire.x() + 0.5, y, spire.z() + 0.5, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.8F,
                            0.6F + 1.0F * t / CLIMB_TICKS, false);
                }
            } else if (t == CLIMB_TICKS + 1) {
                level.playLocalSound(spire.x() + 0.5, spire.foot() + spire.height(), spire.z() + 0.5, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER,
                        0.5F, 1.8F, false);
                for (int k = 0; k < 14; k++) {
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, spire.x() + 0.5, spire.foot() + spire.height(), spire.z() + 0.5,
                            (level.random.nextDouble() - 0.5) * 0.6, level.random.nextDouble() * 0.4, (level.random.nextDouble() - 0.5) * 0.6);
                }
            }
        }
        // the spires and crystals known to be dull: looked at every tick, so the surge starts the moment the charge comes
        for (Map.Entry<Long, Spire> e : SPIRES.entrySet()) {
            final Spire spire = e.getValue();
            if (spire.charged()) {
                continue;
            }
            final BlockState foot = level.getBlockState(new BlockPos(spire.x(), spire.foot(), spire.z()));
            if (foot.hasProperty(StormClusterBlock.CHARGED) && foot.getValue(StormClusterBlock.CHARGED)) {
                SURGES.add(new long[] {e.getKey(), now});
                e.setValue(new Spire(spire.x(), spire.z(), spire.foot(), spire.height(), true));
            }
        }
        for (Map.Entry<Long, Boolean> e : CLUSTERS.entrySet()) {
            if (e.getValue()) {
                continue;
            }
            final BlockPos p = BlockPos.of(e.getKey());
            final BlockState state = level.getBlockState(p);
            if (state.hasProperty(StormClusterBlock.CHARGED) && state.getValue(StormClusterBlock.CHARGED)) {
                e.setValue(true);
                sparks(level, p);
            }
        }
        if (now % 10 != 0 || mc.player.getY() > TunnelLayer.MAX_AIR_Y + 40) {
            return;
        }
        look(level, now);
    }

    /** Looks over the crystals round the player: a spire or crystal that was dull and is charged now wakes. */
    private static void look(ClientLevel level, long now) {
        final Minecraft mc = Minecraft.getInstance();
        final ChunkPos at = mc.player.chunkPosition();
        final Map<Long, int[]> columns = new HashMap<>(); // x, z, lowest, highest, charged
        final Map<Long, Boolean> clusters = new HashMap<>();
        for (int dz = -RADIUS_CHUNKS; dz <= RADIUS_CHUNKS; dz++) {
            for (int dx = -RADIUS_CHUNKS; dx <= RADIUS_CHUNKS; dx++) {
                if (!level.hasChunk(at.x + dx, at.z + dz)) {
                    continue;
                }
                final LevelChunk chunk = level.getChunk(at.x + dx, at.z + dz);
                final LevelChunkSection[] sections = chunk.getSections();
                for (int i = 0; i < sections.length; i++) {
                    final int baseY = SectionPos.sectionToBlockCoord(level.getSectionYFromSectionIndex(i));
                    if (baseY + 15 < TunnelLayer.MIN_AIR_Y - 2 || baseY > TunnelLayer.MAX_AIR_Y + 4) {
                        continue;
                    }
                    final LevelChunkSection section = sections[i];
                    if (section == null || section.hasOnlyAir() || !section.maybeHas(s -> s.hasProperty(StormClusterBlock.CHARGED))) {
                        continue;
                    }
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                final BlockState state = section.getBlockState(x, y, z);
                                if (!state.hasProperty(StormClusterBlock.CHARGED)) {
                                    continue;
                                }
                                final int bx = chunk.getPos().getMinBlockX() + x, by = baseY + y, bz = chunk.getPos().getMinBlockZ() + z;
                                final boolean charged = state.getValue(StormClusterBlock.CHARGED);
                                if (state.is(AbyssalDepths.STORM_CRYSTAL.get())) {
                                    final int[] c = columns.computeIfAbsent(BlockPos.asLong(bx, 0, bz), k -> new int[] {bx, bz, by, by, 0});
                                    c[2] = Math.min(c[2], by);
                                    c[3] = Math.max(c[3], by);
                                    c[4] |= charged ? 1 : 0;
                                } else {
                                    clusters.put(BlockPos.asLong(bx, by, bz), charged);
                                }
                            }
                        }
                    }
                }
            }
        }
        for (Map.Entry<Long, int[]> e : columns.entrySet()) {
            final int[] c = e.getValue();
            final Spire before = SPIRES.get(e.getKey());
            final Spire now2 = new Spire(c[0], c[1], c[2], c[3] - c[2] + 1, c[4] == 1);
            if (before != null && !before.charged() && now2.charged()) {
                SURGES.add(new long[] {e.getKey(), now});
            }
            SPIRES.put(e.getKey(), now2);
        }
        for (Map.Entry<Long, Boolean> e : clusters.entrySet()) {
            final Boolean before = CLUSTERS.get(e.getKey());
            if (before != null && !before && e.getValue()) { // a small crystal waking: a burst of sparks
                sparks(level, BlockPos.of(e.getKey()));
            }
        }
        SPIRES.keySet().retainAll(columns.keySet());
        CLUSTERS.clear();
        CLUSTERS.putAll(clusters);
    }

    private static void sparks(ClientLevel level, BlockPos p) {
        for (int k = 0; k < 10; k++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.getX() + 0.5, p.getY() + 0.4, p.getZ() + 0.5,
                    (level.random.nextDouble() - 0.5) * 0.3, level.random.nextDouble() * 0.25, (level.random.nextDouble() - 0.5) * 0.3);
        }
    }

    // ---- drawn ----------------------------------------------------------------------------

    /** Where the charge's front is in a spire, so many ticks after it began (from below its foot to above its tip). */
    private static double front(Spire spire, double t) {
        final double k = Math.min(1.0, t / CLIMB_TICKS);
        final double eased = k * k * (3 - 2 * k);
        return spire.foot() - SOFT + (spire.height() + 2 * SOFT) * eased;
    }

    /** How much of the dull skin is left at a height (1 above the front, 0 below, soft between). */
    private static float dullness(double y, double front) {
        final double k = Mth.clamp((y - front + SOFT) / (2 * SOFT), 0.0, 1.0);
        return (float) (k * k * (3 - 2 * k));
    }

    private static final int LIGHT = 0xE6FBFF;
    private static final ResourceLocation DULL_SKIN = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "block/storm_crystal");

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || SURGES.isEmpty() || mc.level == null) {
            return;
        }
        final float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final Vec3 cam = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final TextureAtlasSprite skin = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS).getSprite(DULL_SKIN);
        final VertexConsumer tex = buffers.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        for (long[] s : SURGES) {
            final Spire spire = SPIRES.get(s[0]);
            if (spire != null && now - s[1] <= CLIMB_TICKS) {
                skin(tex, pose.last(), mc.level, spire, front(spire, now - s[1]), skin);
            }
        }
        buffers.endBatch(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        final VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        final Matrix4f m = pose.last().pose();
        for (long[] s : SURGES) {
            final Spire spire = SPIRES.get(s[0]);
            if (spire == null) {
                continue;
            }
            final float t = now - s[1];
            if (t <= CLIMB_TICKS) { // a faint light at the front, soft above and below
                final float f = (float) front(spire, t);
                glow(q, m, spire, f - SOFT, f, 0.0F, 0.28F);
                glow(q, m, spire, f, f + SOFT * 0.6F, 0.28F, 0.0F);
            } else { // settled: a soft pulse over it all, fading
                final float k = Mth.clamp(1.0F - (t - CLIMB_TICKS) / FLASH_TICKS, 0.0F, 1.0F);
                glow(q, m, spire, spire.foot(), spire.foot() + spire.height(), 0.4F * k, 0.4F * k);
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        pose.popPose();
    }

    /**
     * The dull skin over a spire, fading out below the front: each block's sides (and the top) in the dull texture, cut
     * into slices so the fade is smooth, each corner as dull as the height it is at; lit like the air beside it.
     */
    private static void skin(VertexConsumer v, PoseStack.Pose pose, ClientLevel level, Spire spire, double front, TextureAtlasSprite sprite) {
        final float e = 0.004F, x0 = spire.x() - e, x1 = spire.x() + 1 + e, z0 = spire.z() - e, z1 = spire.z() + 1 + e;
        final int slices = 4;
        for (int b = 0; b < spire.height(); b++) {
            final int y = spire.foot() + b;
            for (int k = 0; k < slices; k++) {
                final float ya = y + k / (float) slices, yb = y + (k + 1) / (float) slices;
                final float da = dullness(ya, front), db = dullness(yb, front);
                if (da <= 0.004F && db <= 0.004F) {
                    continue;
                }
                final float va = sprite.getV(1.0F - k / (float) slices), vb = sprite.getV(1.0F - (k + 1) / (float) slices);
                final float u0 = sprite.getU0(), u1 = sprite.getU1();
                side(v, pose, level, new BlockPos(spire.x(), y, spire.z() - 1), x0, z0, x1, z0, ya, yb, da, db, u0, u1, va, vb, 0, 0, -1);
                side(v, pose, level, new BlockPos(spire.x(), y, spire.z() + 1), x1, z1, x0, z1, ya, yb, da, db, u0, u1, va, vb, 0, 0, 1);
                side(v, pose, level, new BlockPos(spire.x() - 1, y, spire.z()), x0, z1, x0, z0, ya, yb, da, db, u0, u1, va, vb, -1, 0, 0);
                side(v, pose, level, new BlockPos(spire.x() + 1, y, spire.z()), x1, z0, x1, z1, ya, yb, da, db, u0, u1, va, vb, 1, 0, 0);
            }
        }
        final float top = spire.foot() + spire.height() + e, dt = dullness(top, front);
        if (dt > 0.004F) {
            final int c = (int) (dt * 255) << 24 | 0xFFFFFF;
            final int light = LevelRenderer.getLightColor(level, new BlockPos(spire.x(), spire.foot() + spire.height(), spire.z()));
            vertex(v, pose, x0, top, z0, c, sprite.getU0(), sprite.getV0(), light, 0, 1, 0);
            vertex(v, pose, x0, top, z1, c, sprite.getU0(), sprite.getV1(), light, 0, 1, 0);
            vertex(v, pose, x1, top, z1, c, sprite.getU1(), sprite.getV1(), light, 0, 1, 0);
            vertex(v, pose, x1, top, z0, c, sprite.getU1(), sprite.getV0(), light, 0, 1, 0);
        }
    }

    /** One slice of one side: from (ax, az) to (bx, bz) along it, ya to yb up, its alpha da below and db above. */
    private static void side(VertexConsumer v, PoseStack.Pose pose, ClientLevel level, BlockPos air, float ax, float az, float bx, float bz, float ya, float yb,
                             float da, float db, float u0, float u1, float va, float vb, float nx, float ny, float nz) {
        final int light = LevelRenderer.getLightColor(level, air);
        final int ca = (int) (da * 255) << 24 | 0xFFFFFF, cb = (int) (db * 255) << 24 | 0xFFFFFF;
        vertex(v, pose, ax, ya, az, ca, u0, va, light, nx, ny, nz);
        vertex(v, pose, bx, ya, bz, ca, u1, va, light, nx, ny, nz);
        vertex(v, pose, bx, yb, bz, cb, u1, vb, light, nx, ny, nz);
        vertex(v, pose, ax, yb, az, cb, u0, vb, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer v, PoseStack.Pose pose, float x, float y, float z, int argb, float u, float vv, int light, float nx, float ny, float nz) {
        v.addVertex(pose, x, y, z).setColor(argb).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY).setLight(light).setNormal(pose, nx, ny, nz);
    }

    /** A faint light round a spire from y0 to y1, its strength a0 at the bottom and a1 at the top. */
    private static void glow(VertexConsumer q, Matrix4f m, Spire spire, float y0, float y1, float a0, float a1) {
        y0 = Math.max(y0, spire.foot());
        y1 = Math.min(y1, spire.foot() + spire.height());
        if (y1 <= y0) {
            return;
        }
        final int c0 = (int) (Mth.clamp(a0, 0, 1) * 255) << 24 | LIGHT, c1 = (int) (Mth.clamp(a1, 0, 1) * 255) << 24 | LIGHT;
        final float e = 0.008F, x0 = spire.x() - e, x1 = spire.x() + 1 + e, z0 = spire.z() - e, z1 = spire.z() + 1 + e;
        for (float[] s : new float[][] {{x0, z0, x1, z0}, {x1, z1, x0, z1}, {x0, z1, x0, z0}, {x1, z0, x1, z1}}) {
            q.addVertex(m, s[0], y0, s[1]).setColor(c0);
            q.addVertex(m, s[2], y0, s[3]).setColor(c0);
            q.addVertex(m, s[2], y1, s[3]).setColor(c1);
            q.addVertex(m, s[0], y1, s[1]).setColor(c1);
        }
    }
}

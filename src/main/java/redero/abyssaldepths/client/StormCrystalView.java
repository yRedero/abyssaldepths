package redero.abyssaldepths.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.joml.Matrix4f;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.AmethystClusterBlock;
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
 * The storm crystals waking and falling asleep. Crystals standing together (storm crystal blocks and the small crystals,
 * within two blocks of each other: a spire with those round its feet, or whatever a player built of them) are one
 * <em>formation</em>, and the charge spreads through it as one: from its lowest point (the one nearest its middle),
 * upwards first and outwards slower (height counts twice what distance does). Where the charge has come, the dull skin
 * (the dull texture, drawn over the charged blocks) fades with a soft edge, a faint light at the front, sparks and a
 * rising tone; when it is through, a soft pulse and a crack at the top. When the storm leaves, the same in reverse: the
 * charged look (drawn over the dull blocks) drains from the top back down to the lowest point, the tone falling.
 * Formations already charged when they come into sight just glow (it plays only as it happens).
 */
@EventBusSubscriber(modid = AbyssalDepths.MODID, value = Dist.CLIENT)
public final class StormCrystalView {
    private static final int RADIUS_CHUNKS = 4, LINK = 2, FLASH_TICKS = 40;
    /** Ticks for the charge to cross a block of height (two of distance); how soft the front is (in the same units). */
    private static final float TICKS_PER_UNIT = 24, SOFT = 1.2F, OUTWARD = 0.5F;
    private static final int LIGHT = 0xE6FBFF;
    private static final ResourceLocation DULL = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "block/storm_crystal");
    private static final ResourceLocation DULL_CLUSTER = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "block/storm_crystal_cluster");
    private static final ResourceLocation CHARGED = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "block/storm_crystal_charged");
    private static final ResourceLocation CHARGED_CLUSTER = ResourceLocation.fromNamespaceAndPath(AbyssalDepths.MODID, "block/storm_crystal_cluster_charged");

    /** A formation: its crystal blocks and small crystals, its lowest point, the farthest the charge goes, whether charged. */
    private static final class Formation {
        final List<BlockPos> blocks = new ArrayList<>(), clusters = new ArrayList<>();
        final Set<BlockPos> blockSet = new HashSet<>();
        double ax, az;
        int ay;
        float reach;
        boolean charged;
        /** The animation playing: its start, and whether waking (else falling asleep); -1 for none. */
        long start = -1;
        boolean waking;

        /** When the charge comes to a point (in units: height, then distance at half weight). */
        float wake(double x, double y, double z) {
            return (float) (Math.max(0, y - ay) + OUTWARD * Math.hypot(x - ax, z - az));
        }

        /** Where the front is now (units), or NaN when nothing plays. */
        float front(float now) {
            if (start < 0) {
                return Float.NaN;
            }
            final float k = (now - start) / (TICKS_PER_UNIT * (reach + 2 * SOFT));
            final float eased = Mth.clamp(k, 0, 1);
            final float u = -SOFT + (reach + 2 * SOFT) * eased;
            return waking ? u : reach + SOFT - (u + SOFT);
        }

        long duration() {
            return (long) (TICKS_PER_UNIT * (reach + 2 * SOFT));
        }

        BlockPos top() {
            BlockPos best = blocks.isEmpty() ? clusters.get(0) : blocks.get(0);
            for (BlockPos p : blocks) {
                if (p.getY() > best.getY()) {
                    best = p;
                }
            }
            return best;
        }
    }

    private static final List<Formation> FORMATIONS = new ArrayList<>();

    private StormCrystalView() {
    }

    private static boolean charged(ClientLevel level, BlockPos p) {
        final BlockState s = level.getBlockState(p);
        return s.hasProperty(StormClusterBlock.CHARGED) && s.getValue(StormClusterBlock.CHARGED);
    }

    private static boolean anyCharged(ClientLevel level, Formation f) {
        for (BlockPos p : f.blocks) {
            if (charged(level, p)) {
                return true;
            }
        }
        for (BlockPos p : f.clusters) {
            if (charged(level, p)) {
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        final Minecraft mc = Minecraft.getInstance();
        final ClientLevel level = mc.level;
        if (level == null || mc.player == null) {
            FORMATIONS.clear();
            return;
        }
        final long now = level.getGameTime();
        for (Formation f : FORMATIONS) { // every tick: the moment the charge comes (or goes), it starts
            final boolean c = anyCharged(level, f);
            if (c != f.charged) {
                f.charged = c;
                f.waking = c;
                f.start = now;
                level.playLocalSound(f.ax + 0.5, f.ay + 0.5, f.az + 0.5, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS, 0.7F, c ? 0.6F : 1.4F, false);
            }
            if (f.start >= 0) {
                play(level, f, now);
            }
        }
        if (now % 10 == 0 && mc.player.getY() < TunnelLayer.MAX_AIR_Y + 40) {
            look(level);
        }
    }

    /** Sparks at the front, the tone rising (or falling); the crack at the top when it is through. */
    private static void play(ClientLevel level, Formation f, long now) {
        final long t = now - f.start;
        if (t > f.duration() + FLASH_TICKS) {
            f.start = -1;
            return;
        }
        if (t <= f.duration()) {
            final float front = f.front(now);
            for (BlockPos p : f.blocks) {
                if (Math.abs(f.wake(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5) - front) < 0.5F && level.random.nextInt(4) == 0) {
                    level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.getX() + level.random.nextDouble(), p.getY() + level.random.nextDouble(),
                            p.getZ() + level.random.nextDouble(), 0, f.waking ? 0.05 : -0.03, 0);
                }
            }
            for (BlockPos p : f.clusters) { // a small crystal the front passes: a few sparks
                final float w = f.wake(p.getX() + 0.5, p.getY() + 0.5, p.getZ() + 0.5);
                if (Math.abs(w - front) < 0.05F) {
                    sparks(level, p, f.waking ? 6 : 2);
                }
            }
            if (t % 12 == 0) {
                final float k = t / (float) f.duration();
                level.playLocalSound(f.ax + 0.5, f.ay + 0.5 + Math.max(0, front), f.az + 0.5, SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.BLOCKS,
                        0.6F, f.waking ? 0.6F + 1.0F * k : 1.6F - 1.0F * k, false);
            }
        } else if (t == f.duration() + 1 && f.waking && !f.blocks.isEmpty()) {
            final BlockPos top = f.top();
            level.playLocalSound(top.getX() + 0.5, top.getY() + 1, top.getZ() + 0.5, SoundEvents.LIGHTNING_BOLT_IMPACT, SoundSource.WEATHER, 0.5F, 1.8F, false);
            sparks(level, top.above(), 14);
        }
    }

    private static void sparks(ClientLevel level, BlockPos p, int n) {
        for (int k = 0; k < n; k++) {
            level.addParticle(ParticleTypes.ELECTRIC_SPARK, p.getX() + 0.5, p.getY() + 0.4, p.getZ() + 0.5,
                    (level.random.nextDouble() - 0.5) * 0.3, level.random.nextDouble() * 0.25, (level.random.nextDouble() - 0.5) * 0.3);
        }
    }

    /**
     * Looks over the crystals round the player and groups them into formations, keeping what is known of the old ones
     * (whether charged, what plays) for those that are still there.
     */
    private static void look(ClientLevel level) {
        final Minecraft mc = Minecraft.getInstance();
        final ChunkPos at = mc.player.chunkPosition();
        final Map<BlockPos, Boolean> found = new HashMap<>(); // position -> is a crystal block (else a small crystal)
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
                                if (state.hasProperty(StormClusterBlock.CHARGED)) {
                                    found.put(new BlockPos(chunk.getPos().getMinBlockX() + x, baseY + y, chunk.getPos().getMinBlockZ() + z),
                                            state.is(AbyssalDepths.STORM_CRYSTAL.get()));
                                }
                            }
                        }
                    }
                }
            }
        }
        // the old formations by their members, to carry over what plays
        final Map<BlockPos, Formation> old = new HashMap<>();
        for (Formation f : FORMATIONS) {
            f.blocks.forEach(p -> old.put(p, f));
            f.clusters.forEach(p -> old.put(p, f));
        }
        final List<Formation> next = new ArrayList<>();
        final Set<BlockPos> seen = new HashSet<>();
        for (BlockPos seed : found.keySet()) {
            if (!seen.add(seed)) {
                continue;
            }
            final Formation f = new Formation();
            final ArrayDeque<BlockPos> open = new ArrayDeque<>();
            open.add(seed);
            while (!open.isEmpty()) {
                final BlockPos p = open.poll();
                if (found.get(p)) {
                    f.blocks.add(p);
                    f.blockSet.add(p);
                } else {
                    f.clusters.add(p);
                }
                for (BlockPos n : BlockPos.betweenClosed(p.offset(-LINK, -LINK, -LINK), p.offset(LINK, LINK, LINK))) {
                    if (found.containsKey(n) && !seen.contains(n)) {
                        final BlockPos q = n.immutable();
                        seen.add(q);
                        open.add(q);
                    }
                }
            }
            shape(f);
            Formation before = null;
            for (BlockPos p : f.blocks) {
                before = before == null ? old.get(p) : before;
            }
            for (BlockPos p : f.clusters) {
                before = before == null ? old.get(p) : before;
            }
            if (before != null) {
                f.charged = before.charged;
                f.start = before.start;
                f.waking = before.waking;
            } else {
                f.charged = anyCharged(level, f); // first seen: as it is, nothing plays
            }
            next.add(f);
        }
        FORMATIONS.clear();
        FORMATIONS.addAll(next);
    }

    /** The formation's lowest point (of the lowest, the one nearest its middle) and how far the charge must go. */
    private static void shape(Formation f) {
        final List<BlockPos> all = new ArrayList<>(f.blocks);
        all.addAll(f.clusters);
        int low = Integer.MAX_VALUE;
        double mx = 0, mz = 0;
        for (BlockPos p : all) {
            low = Math.min(low, p.getY());
            mx += p.getX();
            mz += p.getZ();
        }
        mx /= all.size();
        mz /= all.size();
        BlockPos anchor = null;
        for (BlockPos p : all) {
            if (p.getY() == low && (anchor == null || Math.hypot(p.getX() - mx, p.getZ() - mz) < Math.hypot(anchor.getX() - mx, anchor.getZ() - mz))) {
                anchor = p;
            }
        }
        f.ax = anchor.getX() + 0.5;
        f.az = anchor.getZ() + 0.5;
        f.ay = low;
        float reach = 0;
        for (BlockPos p : all) {
            for (int c = 0; c < 8; c++) { // its farthest corner
                reach = Math.max(reach, f.wake(p.getX() + (c & 1), p.getY() + (c >> 1 & 1), p.getZ() + (c >> 2 & 1)));
            }
        }
        f.reach = reach;
    }

    // ---- drawn ----------------------------------------------------------------------------

    /** How much of the old look is left at a point: waking, the dull skin (1 where the charge has not come); asleep, the charged one. */
    private static float left(Formation f, float front, double x, double y, double z) {
        final float w = f.wake(x, y, z);
        final float k = Mth.clamp((w - front + SOFT) / (2 * SOFT), 0, 1);
        final float s = k * k * (3 - 2 * k); // 1 ahead of the front
        return f.waking ? s : 1 - s;
    }

    @SubscribeEvent
    public static void onRender(RenderLevelStageEvent event) {
        final Minecraft mc = Minecraft.getInstance();
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS || mc.level == null) {
            return;
        }
        boolean any = false;
        for (Formation f : FORMATIONS) {
            any |= f.start >= 0;
        }
        if (!any) {
            return;
        }
        final float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final Vec3 cam = event.getCamera().getPosition();
        final PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        final MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        final var atlas = mc.getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        final VertexConsumer tex = buffers.getBuffer(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        for (Formation f : FORMATIONS) {
            if (f.start < 0 || now - f.start > f.duration()) {
                continue;
            }
            final float front = f.front(now);
            final TextureAtlasSprite skin = atlas.getSprite(f.waking ? DULL : CHARGED), small = atlas.getSprite(f.waking ? DULL_CLUSTER : CHARGED_CLUSTER);
            for (BlockPos p : f.blocks) {
                block(tex, pose.last(), mc.level, f, p, front, skin);
            }
            for (BlockPos p : f.clusters) {
                cluster(tex, pose.last(), mc.level, f, p, front, small);
            }
        }
        buffers.endBatch(RenderType.entityTranslucent(InventoryMenu.BLOCK_ATLAS));
        final VertexConsumer q = buffers.getBuffer(RenderType.debugQuads());
        final Matrix4f m = pose.last().pose();
        for (Formation f : FORMATIONS) {
            if (f.start < 0) {
                continue;
            }
            final float t = now - f.start;
            for (BlockPos p : f.blocks) {
                if (t <= f.duration()) { // a faint light at the front
                    glow(q, m, f, p, f.front(now), -1);
                } else if (f.waking) { // through: a soft pulse over it all, fading
                    glow(q, m, f, p, 0, 0.4F * Mth.clamp(1 - (t - f.duration()) / FLASH_TICKS, 0, 1));
                }
            }
        }
        buffers.endBatch(RenderType.debugQuads());
        pose.popPose();
    }

    /** One crystal block's skin: its open sides in slices and its top, each corner as much as is left there. */
    private static void block(VertexConsumer v, PoseStack.Pose pose, ClientLevel level, Formation f, BlockPos p, float front, TextureAtlasSprite sprite) {
        final float e = 0.004F, x0 = p.getX() - e, x1 = p.getX() + 1 + e, z0 = p.getZ() - e, z1 = p.getZ() + 1 + e;
        final int slices = 4;
        final boolean bright = !f.waking;
        for (int k = 0; k < slices; k++) {
            final float ya = p.getY() + k / (float) slices, yb = p.getY() + (k + 1) / (float) slices;
            final float va = sprite.getV(1.0F - k / (float) slices), vb = sprite.getV(1.0F - (k + 1) / (float) slices);
            final float u0 = sprite.getU0(), u1 = sprite.getU1();
            for (Direction d : Direction.Plane.HORIZONTAL) {
                final BlockPos n = p.relative(d);
                if (f.blockSet.contains(n)) {
                    continue; // inside the formation: unseen
                }
                final float ax, az, bx, bz;
                switch (d) {
                    case NORTH -> { ax = x0; az = z0; bx = x1; bz = z0; }
                    case SOUTH -> { ax = x1; az = z1; bx = x0; bz = z1; }
                    case WEST -> { ax = x0; az = z1; bx = x0; bz = z0; }
                    default -> { ax = x1; az = z0; bx = x1; bz = z1; }
                }
                final int light = bright ? 0xF000F0 : LevelRenderer.getLightColor(level, n);
                quad(v, pose, light, sprite, ax, ya, az, bx, ya, bz, bx, yb, bz, ax, yb, az,
                        left(f, front, ax, ya, az), left(f, front, bx, ya, bz), left(f, front, bx, yb, bz), left(f, front, ax, yb, az),
                        u0, va, u1, va, u1, vb, u0, vb, d.getStepX(), 0, d.getStepZ());
            }
        }
        if (!f.blockSet.contains(p.above())) {
            final float y = p.getY() + 1 + e;
            final int light = bright ? 0xF000F0 : LevelRenderer.getLightColor(level, p.above());
            quad(v, pose, light, sprite, x0, y, z0, x0, y, z1, x1, y, z1, x1, y, z0,
                    left(f, front, x0, y, z0), left(f, front, x0, y, z1), left(f, front, x1, y, z1), left(f, front, x1, y, z0),
                    sprite.getU0(), sprite.getV0(), sprite.getU0(), sprite.getV1(), sprite.getU1(), sprite.getV1(), sprite.getU1(), sprite.getV0(), 0, 1, 0);
        }
    }

    /**
     * A small crystal's look: its two crossed planes as the block model lays them (corner to corner, 0.05 to 0.95), in
     * slices, both faces just off the planes; standing ones and hanging ones (those on walls just change).
     */
    private static void cluster(VertexConsumer v, PoseStack.Pose pose, ClientLevel level, Formation f, BlockPos p, float front, TextureAtlasSprite sprite) {
        final BlockState state = level.getBlockState(p);
        if (!state.hasProperty(AmethystClusterBlock.FACING)) {
            return;
        }
        final Direction facing = state.getValue(AmethystClusterBlock.FACING);
        if (facing != Direction.UP && facing != Direction.DOWN) {
            return;
        }
        final boolean hanging = facing == Direction.DOWN;
        final float x = p.getX(), z = p.getZ(), lo = 0.05F, hi = 0.95F, off = 0.003F;
        final int light = f.waking ? LevelRenderer.getLightColor(level, p) : 0xF000F0;
        final int slices = 4;
        final float[][] planes = {{lo, lo, hi, hi, 0.707F, -0.707F}, {lo, hi, hi, lo, 0.707F, 0.707F}};
        for (int k = 0; k < slices; k++) {
            final float ya = p.getY() + k / (float) slices, yb = p.getY() + (k + 1) / (float) slices;
            // the texture's foot is at the block's floor (or its ceiling, hanging)
            final float va = sprite.getV(hanging ? k / (float) slices : 1.0F - k / (float) slices);
            final float vb = sprite.getV(hanging ? (k + 1) / (float) slices : 1.0F - (k + 1) / (float) slices);
            for (float[] pl : planes) {
                for (int side = -1; side <= 1; side += 2) {
                    final float nx = pl[4] * side, nz = pl[5] * side, ox = nx * off, oz = nz * off;
                    final float ax = x + pl[0] + ox, az = z + pl[1] + oz, bx = x + pl[2] + ox, bz = z + pl[3] + oz;
                    quad(v, pose, light, sprite, ax, ya, az, bx, ya, bz, bx, yb, bz, ax, yb, az,
                            left(f, front, ax, ya, az), left(f, front, bx, ya, bz), left(f, front, bx, yb, bz), left(f, front, ax, yb, az),
                            sprite.getU0(), va, sprite.getU1(), va, sprite.getU1(), vb, sprite.getU0(), vb, nx, 0, nz);
                }
            }
        }
    }

    private static void quad(VertexConsumer v, PoseStack.Pose pose, int light, TextureAtlasSprite s, float x1, float y1, float z1, float x2, float y2, float z2,
                             float x3, float y3, float z3, float x4, float y4, float z4, float a1, float a2, float a3, float a4,
                             float u1, float v1, float u2, float v2, float u3, float v3, float u4, float v4, float nx, float ny, float nz) {
        if (a1 <= 0.004F && a2 <= 0.004F && a3 <= 0.004F && a4 <= 0.004F) {
            return;
        }
        vertex(v, pose, x1, y1, z1, a1, u1, v1, light, nx, ny, nz);
        vertex(v, pose, x2, y2, z2, a2, u2, v2, light, nx, ny, nz);
        vertex(v, pose, x3, y3, z3, a3, u3, v3, light, nx, ny, nz);
        vertex(v, pose, x4, y4, z4, a4, u4, v4, light, nx, ny, nz);
    }

    private static void vertex(VertexConsumer v, PoseStack.Pose pose, float x, float y, float z, float a, float u, float vv, int light, float nx, float ny, float nz) {
        v.addVertex(pose, x, y, z).setColor((int) (Mth.clamp(a, 0, 1) * 255) << 24 | 0xFFFFFF).setUv(u, vv).setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light).setNormal(pose, nx, ny, nz);
    }

    /** A faint light round a block: at the front (alpha below 0), or evenly (the pulse). */
    private static void glow(VertexConsumer q, Matrix4f m, Formation f, BlockPos p, float front, float even) {
        final float e = 0.008F, x0 = p.getX() - e, x1 = p.getX() + 1 + e, z0 = p.getZ() - e, z1 = p.getZ() + 1 + e;
        final int slices = 4;
        for (int k = 0; k < slices; k++) {
            final float ya = p.getY() + k / (float) slices, yb = p.getY() + (k + 1) / (float) slices;
            for (float[] s : new float[][] {{x0, z0, x1, z0}, {x1, z1, x0, z1}, {x0, z1, x0, z0}, {x1, z0, x1, z1}}) {
                final float a1 = band(f, front, even, s[0], ya, s[1]), a2 = band(f, front, even, s[2], ya, s[3]);
                final float a3 = band(f, front, even, s[2], yb, s[3]), a4 = band(f, front, even, s[0], yb, s[1]);
                if (a1 + a2 + a3 + a4 < 0.01F) {
                    continue;
                }
                q.addVertex(m, s[0], ya, s[1]).setColor(color(a1));
                q.addVertex(m, s[2], ya, s[3]).setColor(color(a2));
                q.addVertex(m, s[2], yb, s[3]).setColor(color(a3));
                q.addVertex(m, s[0], yb, s[1]).setColor(color(a4));
            }
        }
    }

    private static float band(Formation f, float front, float even, double x, double y, double z) {
        return even >= 0 ? even : 0.3F * Mth.clamp(1 - Math.abs(f.wake(x, y, z) - front) / SOFT, 0, 1);
    }

    private static int color(float a) {
        return (int) (Mth.clamp(a, 0, 1) * 255) << 24 | LIGHT;
    }
}

package redero.abyssaldepths.artifact.reprise;

import java.util.ArrayDeque;
import java.util.Iterator;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

/**
 * What changed in one dimension during the recording, oldest first: every block change with the state
 * (and block entity data) it replaced, and every change of a block entity's contents with what it held
 * before. Undoing them newest first gives the dimension as it was.
 * <p>
 * To stay small, a block that changes many times within one second (flowing water, a redstone clock) is
 * written down only once per second, and a block entity at most once per second (see
 * {@link RepriseRecorder}).
 */
final class LevelLog {
    /** A change at {@code time}: {@code state} null = only the block entity's contents changed. */
    record Change(long time, long pos, BlockState state, CompoundTag blockEntity) {
    }

    final ArrayDeque<Change> changes = new ArrayDeque<>();
    /** The second (game time / 20) a block was last written down in, to write it once per second. */
    final Long2LongOpenHashMap lastSecond = new Long2LongOpenHashMap();
    /** The last known contents of every block entity in the loaded chunks: "before" for its next change. */
    final Long2ObjectOpenHashMap<CompoundTag> known = new Long2ObjectOpenHashMap<>();
    /** Block entities that said they changed since the last time they were written down. */
    final LongOpenHashSet dirty = new LongOpenHashSet();

    LevelLog() {
        lastSecond.defaultReturnValue(Long.MIN_VALUE);
    }

    /** Forgets changes from before {@code time}: they can never be undone any more. */
    void forgetBefore(long time) {
        for (Iterator<Change> it = changes.iterator(); it.hasNext();) {
            if (it.next().time() >= time) {
                break;
            }
            it.remove();
        }
        if (lastSecond.size() > 4 * changes.size() + 1024) {
            lastSecond.clear(); // only an optimisation; rebuilt as changes come in
        }
    }

    void clear() {
        changes.clear();
        lastSecond.clear();
        known.clear();
        dirty.clear();
    }
}

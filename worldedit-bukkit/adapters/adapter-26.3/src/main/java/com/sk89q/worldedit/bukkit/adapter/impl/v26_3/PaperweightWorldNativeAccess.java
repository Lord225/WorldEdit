/*
 * WorldEdit, a Minecraft world manipulation toolkit
 * Copyright (C) sk89q <http://www.sk89q.com>
 * Copyright (C) WorldEdit team and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.sk89q.worldedit.bukkit.adapter.impl.v26_3;

import com.sk89q.worldedit.history.change.BlockChange;
import com.sk89q.worldedit.history.change.SideEffectBlockChange;
import com.sk89q.worldedit.history.changeset.ChangeSet;
import com.sk89q.worldedit.internal.wna.BlockChangeCapture;
import com.sk89q.worldedit.internal.wna.WorldNativeAccess;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.FullChunkStatus;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.Fallable;
import net.minecraft.world.level.block.ScaffoldingBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.redstone.ExperimentalRedstoneUtils;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.CraftBlockEntityState;
import org.bukkit.craftbukkit.block.CraftBlockState;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.enginehub.linbus.tree.LinCompoundTag;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.annotation.Nullable;

public class PaperweightWorldNativeAccess implements WorldNativeAccess<LevelChunk, net.minecraft.world.level.block.state.BlockState, BlockPos> {
    private static final int UPDATE = 1;
    private static final int NOTIFY = 2;

    private final PaperweightAdapter adapter;
    private final WeakReference<ServerLevel> world;
    private SideEffectSet sideEffectSet;
    private @Nullable CapturedSideEffects capture;

    public PaperweightWorldNativeAccess(PaperweightAdapter adapter, WeakReference<ServerLevel> world) {
        this.adapter = adapter;
        this.world = world;
    }

    private ServerLevel getWorld() {
        return Objects.requireNonNull(world.get(), "The reference to the world was lost");
    }

    @Override
    public void setCurrentSideEffectSet(SideEffectSet sideEffectSet) {
        this.sideEffectSet = sideEffectSet;
    }

    @Override
    public BlockChangeCapture captureBlockChanges(ChangeSet changeSet) {
        ServerLevel world = getWorld();
        if (world.captureBlockStates || world.captureTreeGeneration) {
            return BlockChangeCapture.NONE;
        }
        capture = new CapturedSideEffects(world, changeSet);
        return capture;
    }

    @Override
    public LevelChunk getChunk(int x, int z) {
        return getWorld().getChunk(x, z);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState toNative(BlockState state) {
        return adapter.adapt(state);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getBlockState(LevelChunk chunk, BlockPos position) {
        return chunk.getBlockState(position);
    }

    @Nullable
    @Override
    public net.minecraft.world.level.block.state.BlockState setBlockState(LevelChunk chunk, BlockPos position, net.minecraft.world.level.block.state.BlockState state) {
        return chunk.setBlockState(position, state, this.sideEffectSet.shouldApply(SideEffect.UPDATE) ? 0 : 512);
    }

    @Override
    public net.minecraft.world.level.block.state.BlockState getValidBlockForPosition(net.minecraft.world.level.block.state.BlockState block, BlockPos position) {
        return Block.updateFromNeighbourShapes(block, getWorld(), position);
    }

    @Override
    public BlockPos getPosition(int x, int y, int z) {
        return new BlockPos(x, y, z);
    }

    @Override
    public void updateLightingForBlock(BlockPos position) {
        getWorld().getChunkSource().getLightEngine().checkBlock(position);
    }

    @Override
    public boolean updateTileEntity(BlockPos position, LinCompoundTag tag) {
        // We will assume that the tile entity was created for us
        BlockEntity tileEntity = getWorld().getBlockEntity(position);
        if (tileEntity == null) {
            return false;
        }
        Tag nativeTag = adapter.fromNative(tag);
        PaperweightAdapter.readTagIntoTileEntity((net.minecraft.nbt.CompoundTag) nativeTag, tileEntity);
        return true;
    }

    @Override
    public void notifyBlockUpdate(LevelChunk chunk, BlockPos position, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState) {
        if (chunk.getSections()[getWorld().getSectionIndex(position.getY())] != null) {
            getWorld().sendBlockUpdated(position, oldState, newState, UPDATE | NOTIFY);
        }
    }

    @Override
    public boolean isChunkTicking(LevelChunk chunk) {
        return chunk.getFullStatus().isOrAfter(FullChunkStatus.BLOCK_TICKING);
    }

    @Override
    public void markBlockChanged(LevelChunk chunk, BlockPos position) {
        if (chunk.getSections()[getWorld().getSectionIndex(position.getY())] != null) {
            getWorld().getChunkSource().blockChanged(position);
        }
    }

    @Override
    public void notifyNeighbors(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState) {
        ServerLevel world = getWorld();
        if (capture != null) {
            capture.neighbors.add(pos);
        }
        if (!oldState.is(newState.getBlock())) {
            // Chunk writes omit removal notifications, including diode output updates through a solid block.
            oldState.affectNeighborsAfterRemoval(world, pos, false);
        }
        if (newState.getBlock() instanceof ComparatorBlock && sideEffectSet.shouldApply(SideEffect.UPDATE)
                && newState.canSurvive(world, pos)) {
            // onPlace only notifies the output; the pasted comparator must also evaluate its inputs.
            fireNeighborChanged(world, newState.getBlock(), pos);
        }
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            world.updateNeighborsAt(pos, oldState.getBlock());
        } else {
            // When we don't want events, manually run the physics without them.
            Block block = oldState.getBlock();
            fireNeighborChanged(world, block, pos.west());
            fireNeighborChanged(world, block, pos.east());
            fireNeighborChanged(world, block, pos.below());
            fireNeighborChanged(world, block, pos.above());
            fireNeighborChanged(world, block, pos.north());
            fireNeighborChanged(world, block, pos.south());
        }
        if (newState.hasAnalogOutputSignal()) {
            world.updateNeighbourForOutputSignal(pos, newState.getBlock());
        }
    }

    @Override
    public void updateBlock(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState) {
        ServerLevel world = getWorld();
        newState.onPlace(world, pos, oldState, false);
    }

    private void fireNeighborChanged(ServerLevel world, Block block, BlockPos neighborPos) {
        world.getBlockState(neighborPos).handleNeighborChanged(world, neighborPos, block, ExperimentalRedstoneUtils.initialOrientation(world, null, null), false);
    }

    @Override
    public void updateNeighbors(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState, int recursionLimit) {
        ServerLevel world = getWorld();
        if (capture != null) {
            capture.neighbors.add(pos);
        }
        oldState.updateIndirectNeighbourShapes(world, pos, NOTIFY, recursionLimit);
        if (sideEffectSet.shouldApply(SideEffect.EVENTS)) {
            CraftWorld craftWorld = world.getWorld();
            BlockPhysicsEvent event = new BlockPhysicsEvent(craftWorld.getBlockAt(pos.getX(), pos.getY(), pos.getZ()), newState.asBlockData());
            world.getCraftServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }
        }
        newState.updateNeighbourShapes(world, pos, NOTIFY, recursionLimit);
        newState.updateIndirectNeighbourShapes(world, pos, NOTIFY, recursionLimit);
    }

    @Override
    public void onBlockStateChange(BlockPos pos, net.minecraft.world.level.block.state.BlockState oldState, net.minecraft.world.level.block.state.BlockState newState) {
        getWorld().updatePOIOnBlockStateChange(pos, oldState, newState);
    }

    private final class CapturedSideEffects implements BlockChangeCapture {
        private final ServerLevel world;
        private final ChangeSet changeSet;
        private final Map<BlockPos, CraftBlockState> previousStates;
        private final Map<BlockPos, BlockEntity> previousBlockEntities;
        private final @Nullable List<ItemEntity> previousDrops;
        private final Set<BlockPos> neighbors = new LinkedHashSet<>();

        private CapturedSideEffects(ServerLevel world, ChangeSet changeSet) {
            this.world = world;
            this.changeSet = changeSet;
            previousStates = world.capturedBlockStates;
            previousBlockEntities = world.capturedBlockEntities;
            previousDrops = world.captureDrops;
            world.capturedBlockStates = new LinkedHashMap<>();
            world.capturedBlockEntities = new LinkedHashMap<>();
            world.captureDrops = new ArrayList<>();
            world.captureBlockStates = true;
        }

        @Override
        public void close() {
            Map<BlockPos, CraftBlockState> originals = new LinkedHashMap<>();
            try {
                int remaining = 512;
                while (!world.capturedBlockStates.isEmpty() || !neighbors.isEmpty()) {
                    Set<BlockPos> checks = new LinkedHashSet<>(neighbors);
                    neighbors.clear();
                    if (remaining > 0) {
                        checks.forEach(this::settleUnsupportedNeighbors);
                    }
                    List<CraftBlockState> changes = new ArrayList<>(world.capturedBlockStates.values());
                    world.capturedBlockStates.clear();
                    installBlockEntities();
                    for (CraftBlockState previous : changes) {
                        BlockPos pos = previous.getPosition();
                        originals.putIfAbsent(pos, previous);
                        var state = world.getBlockState(pos);
                        if (remaining > 0) {
                            // Paper suppresses physics while capturing; replay it to capture the next dependency layer.
                            world.notifyAndUpdatePhysics(pos, world.getChunkAt(pos), previous.getHandle(), state,
                                state, previous.getFlags(), remaining);
                            neighbors.add(pos);
                        } else {
                            world.getChunkSource().blockChanged(pos);
                        }
                    }
                    remaining--;
                }
            } finally {
                world.capturedBlockStates.forEach(originals::putIfAbsent);
                try {
                    installBlockEntities();
                    for (CraftBlockState previous : originals.values()) {
                        BlockPos pos = previous.getPosition();
                        BaseBlock before = adapter.adapt(previous.getHandle()).toBaseBlock();
                        if (previous instanceof CraftBlockEntityState<?> blockEntity) {
                            var tag = blockEntity.getSnapshotNBT();
                            tag.remove("x");
                            tag.remove("y");
                            tag.remove("z");
                            before = before.toBaseBlock((LinCompoundTag) adapter.toNative(tag));
                        }
                        BaseBlock after = adapter.getFullBlock(new org.bukkit.Location(world.getWorld(),
                            pos.getX(), pos.getY(), pos.getZ()));
                        if (!before.equals(after)) {
                            changeSet.add(new SideEffectBlockChange(new BlockChange(
                                BlockVector3.at(pos.getX(), pos.getY(), pos.getZ()), before, after)));
                        }
                    }
                } finally {
                    world.captureBlockStates = false;
                    world.capturedBlockStates = previousStates;
                    world.capturedBlockEntities = previousBlockEntities;
                    world.captureDrops = previousDrops;
                    capture = null;
                }
            }
        }

        private void installBlockEntities() {
            for (BlockEntity blockEntity : world.capturedBlockEntities.values()) {
                world.getChunkAt(blockEntity.getBlockPos()).addAndRegisterBlockEntity(blockEntity);
            }
            world.capturedBlockEntities.clear();
        }

        private void settleUnsupportedNeighbors(BlockPos pos) {
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (world.isOutsideBuildHeight(neighbor)) {
                    continue;
                }
                var state = world.getBlockState(neighbor);
                Block block = state.getBlock();
                if (!(block instanceof Fallable) && !(block instanceof ScaffoldingBlock)
                        && world.getBlockTicks().hasScheduledTick(neighbor, block)
                        && !state.canSurvive(world, neighbor)) {
                    // Resolve queued support checks inside the edit, before its history entry is closed.
                    state.tick(world, neighbor, world.getRandom());
                }
            }
        }
    }
}

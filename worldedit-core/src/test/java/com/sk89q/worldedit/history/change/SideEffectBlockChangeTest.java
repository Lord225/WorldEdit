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

package com.sk89q.worldedit.history.change;

import com.sk89q.worldedit.BaseWorldEditTest;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.function.operation.ChangeSetExecutor;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.history.UndoContext;
import com.sk89q.worldedit.history.changeset.BlockOptimizedHistory;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.world.block.BaseBlock;
import com.sk89q.worldedit.world.block.BlockStateHolder;
import com.sk89q.worldedit.world.block.BlockType;
import org.enginehub.linbus.tree.LinCompoundTag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SideEffectBlockChangeTest extends BaseWorldEditTest {

    private static final BlockVector3 POSITION = BlockVector3.at(0, 64, 0);
    private final Map<BlockVector3, BaseBlock> blocks = new HashMap<>();
    private final UndoContext context = new UndoContext();

    @BeforeEach
    void setUp() throws Exception {
        Extent extent = mock(Extent.class);
        when(extent.getFullBlock(any())).thenAnswer(invocation -> blocks.get(invocation.getArgument(0)));
        when(extent.setBlock(any(), any())).thenAnswer(invocation -> {
            BlockStateHolder<?> state = invocation.getArgument(1);
            blocks.put(invocation.getArgument(0), state.toBaseBlock());
            return true;
        });
        context.setExtent(extent);
    }

    private static BaseBlock block(String id) {
        return new BlockType("minecraft:" + id).getDefaultState().toBaseBlock();
    }

    private static BaseBlock comparator(int output) {
        return block("comparator").toBaseBlock(
            LinCompoundTag.builder().putInt("OutputSignal", output).build());
    }

    private SideEffectBlockChange collateral(BaseBlock before, BaseBlock after) {
        return new SideEffectBlockChange(new BlockChange(POSITION, before, after));
    }

    @Test
    void restoresFullStateAndNbtAndSupportsRepeatedUndoRedo() throws Exception {
        BaseBlock before = comparator(13);
        BaseBlock after = block("water");
        SideEffectBlockChange change = collateral(before, after);
        blocks.put(POSITION, after);
        for (int cycle = 0; cycle < 3; cycle++) {
            change.undo(context);
            assertEquals(before, blocks.get(POSITION));
            change.redo(context);
            assertEquals(after, blocks.get(POSITION));
        }
    }

    @Test
    void preservesPlayerBlockOutsideSelection() throws Exception {
        BaseBlock before = block("redstone_wire");
        BaseBlock air = block("air");
        BaseBlock playerBlock = block("stone");
        SideEffectBlockChange change = collateral(before, air);
        blocks.put(POSITION, playerBlock);
        change.undo(context);
        assertEquals(playerBlock, blocks.get(POSITION));

        // Even if the player subsequently places the original block, a skipped undo gives redo no ownership.
        blocks.put(POSITION, before);
        change.redo(context);
        assertEquals(before, blocks.get(POSITION));
    }

    @Test
    void preservesPlayerNbtChangesAndChangesAfterUndo() throws Exception {
        BaseBlock before = comparator(13);
        BaseBlock after = comparator(0);
        BaseBlock playerBlock = comparator(7);
        SideEffectBlockChange change = collateral(before, after);
        blocks.put(POSITION, playerBlock);
        change.undo(context);
        assertEquals(playerBlock, blocks.get(POSITION));

        blocks.put(POSITION, after);
        change.undo(context);
        assertEquals(before, blocks.get(POSITION));
        blocks.put(POSITION, playerBlock);
        change.redo(context);
        assertEquals(playerBlock, blocks.get(POSITION));
    }

    @Test
    void mergesCollateralWritesUsingFirstBeforeAndLastAfter() {
        BaseBlock before = comparator(13);
        BaseBlock air = block("air");
        BaseBlock after = block("water");
        BlockOptimizedHistory history = new BlockOptimizedHistory();
        history.add(collateral(before, air));
        history.add(collateral(air, after));
        assertEquals(1, history.size());

        blocks.put(POSITION, after);
        Operations.completeBlindly(ChangeSetExecutor.createUndo(history, context));
        assertEquals(before, blocks.get(POSITION));
        Operations.completeBlindly(ChangeSetExecutor.createRedo(history, context));
        assertEquals(after, blocks.get(POSITION));
    }

    @Test
    void retainsOrdinaryUndoForExplicitEdits() {
        BaseBlock before = comparator(13);
        BaseBlock air = block("air");
        BaseBlock after = block("water");
        BlockOptimizedHistory history = new BlockOptimizedHistory();
        history.add(collateral(before, air));
        history.add(new BlockChange(POSITION, air, after));
        blocks.put(POSITION, block("stone"));
        Operations.completeBlindly(ChangeSetExecutor.createUndo(history, context));
        assertEquals(before, blocks.get(POSITION));
    }

    @Test
    void explicitEditsRetainTheirOriginalStateWhenPhysicsChangesThem() {
        BaseBlock before = comparator(13);
        BaseBlock requested = comparator(0);
        BaseBlock after = block("air");
        BlockOptimizedHistory history = new BlockOptimizedHistory();
        history.add(new BlockChange(POSITION, before, requested));
        history.add(collateral(requested, after));
        blocks.put(POSITION, block("stone"));
        Operations.completeBlindly(ChangeSetExecutor.createUndo(history, context));
        assertEquals(before, blocks.get(POSITION));
        Operations.completeBlindly(ChangeSetExecutor.createRedo(history, context));
        assertEquals(requested, blocks.get(POSITION));
    }

    @Test
    void dropsNetUnchangedCollateralAndHonorsDisabledHistory() {
        BaseBlock before = comparator(13);
        BaseBlock air = block("air");
        BlockOptimizedHistory history = new BlockOptimizedHistory();
        history.add(collateral(before, air));
        history.add(collateral(air, before));
        assertEquals(0, history.size());
        history.setRecordChanges(false);
        history.add(collateral(before, air));
        assertEquals(0, history.size());
    }

    @Test
    void naiveUndoRestoresDirectHistoryWithoutRestoringCollateral() {
        BlockVector3 support = POSITION.subtract(0, 1, 0);
        BaseBlock stone = block("stone");
        BaseBlock wire = block("redstone_wire");
        BaseBlock air = block("air");
        BlockOptimizedHistory history = new BlockOptimizedHistory();
        history.add(new BlockChange(support, stone, air));
        history.add(collateral(wire, air));
        blocks.put(support, air);
        blocks.put(POSITION, air);

        context.setSideEffectUndoEnabled(false);
        Operations.completeBlindly(ChangeSetExecutor.createUndo(history, context));
        assertEquals(stone, blocks.get(support));
        assertEquals(air, blocks.get(POSITION));

        blocks.put(POSITION, wire);
        context.setSideEffectUndoEnabled(true);
        Operations.completeBlindly(ChangeSetExecutor.createRedo(history, context));
        assertEquals(air, blocks.get(support));
        assertEquals(wire, blocks.get(POSITION));
    }

    @Test
    void naiveUndoClearsAnyEarlierCollateralRedoOwnership() throws Exception {
        BaseBlock before = comparator(13);
        BaseBlock after = block("air");
        SideEffectBlockChange change = collateral(before, after);
        blocks.put(POSITION, after);
        change.undo(context);
        blocks.put(POSITION, after);

        context.setSideEffectUndoEnabled(false);
        change.undo(context);
        blocks.put(POSITION, before);
        change.redo(context);
        assertEquals(before, blocks.get(POSITION));
    }
}


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

import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.extent.Extent;
import com.sk89q.worldedit.history.UndoContext;

import static com.google.common.base.Preconditions.checkNotNull;

/**
 * A collateral block change that preserves subsequent edits to the same block.
 */
public final class SideEffectBlockChange implements Change {

    private final BlockChange change;
    private boolean undone;

    public SideEffectBlockChange(BlockChange change) {
        this.change = checkNotNull(change);
    }

    public BlockChange getChange() {
        return change;
    }

    @Override
    public void undo(UndoContext context) throws WorldEditException {
        undone = false;
        if (!context.isSideEffectUndoEnabled()) {
            return;
        }
        Extent extent = checkNotNull(context.getExtent());
        if (extent.getFullBlock(change.position()).equals(change.current())) {
            extent.setBlock(change.position(), change.previous());
            undone = extent.getFullBlock(change.position()).equals(change.previous());
        }
    }

    @Override
    public void redo(UndoContext context) throws WorldEditException {
        Extent extent = checkNotNull(context.getExtent());
        if (undone && extent.getFullBlock(change.position()).equals(change.previous())) {
            extent.setBlock(change.position(), change.current());
        }
        undone = false;
    }
}


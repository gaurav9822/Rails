/*
 * Copyright 2014 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rails.blocks;

import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.math.Side;
import org.terasology.math.Vector3i;
import org.terasology.registry.In;
import org.terasology.world.BlockEntityRegistry;
import org.terasology.world.OnChangedBlock;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.entity.neighbourUpdate.LargeBlockUpdateFinished;
import org.terasology.world.block.entity.neighbourUpdate.LargeBlockUpdateStarting;
import org.terasology.world.block.family.BlockFamily;
import org.terasology.world.block.family.UpdatesWithNeighboursFamily;

import java.util.Set;

@RegisterSystem(RegisterMode.AUTHORITY)
public class RailsBlockFamilyUpdateSystem extends BaseComponentSystem implements UpdateSubscriberSystem {
    private static final Logger logger = LoggerFactory.getLogger(RailsBlockFamilyUpdateSystem.class);

    @In
    private WorldProvider worldProvider;
    @In
    private BlockEntityRegistry blockEntityRegistry;

    private int largeBlockUpdateCount;
    private Set<Vector3i> blocksUpdatedInLargeBlockUpdate = Sets.newHashSet();
    private int[] checkOnHeight = {-1,0,1};

    @ReceiveEvent
    public void largeBlockUpdateStarting(LargeBlockUpdateStarting event, EntityRef entity) {
        largeBlockUpdateCount++;
    }

    @ReceiveEvent
    public void largeBlockUpdateFinished(LargeBlockUpdateFinished event, EntityRef entity) {
        largeBlockUpdateCount--;
        if (largeBlockUpdateCount < 0) {
            largeBlockUpdateCount = 0;
            throw new IllegalStateException("LargeBlockUpdateFinished invoked too many times");
        }

        if (largeBlockUpdateCount == 0) {
            notifyNeighboursOfChangedBlocks();
        }
    }



    private void notifyNeighboursOfChangedBlocks() {
        // Invoke the updates in another large block change for this class only
        largeBlockUpdateCount++;
        while (!blocksUpdatedInLargeBlockUpdate.isEmpty()) {
            Set<Vector3i> blocksToUpdate = blocksUpdatedInLargeBlockUpdate;

            // Setup new collection for blocks changed in this pass
            blocksUpdatedInLargeBlockUpdate = Sets.newHashSet();

            for (Vector3i blockLocation : blocksToUpdate) {
                processUpdateForBlockLocation(blockLocation);
            }
        }
        largeBlockUpdateCount--;
    }

    @ReceiveEvent(components = {BlockComponent.class})
    public void blockUpdate(OnChangedBlock event, EntityRef blockEntity) {
        if (largeBlockUpdateCount > 0) {
            blocksUpdatedInLargeBlockUpdate.add(event.getBlockPosition());
        } else {
            Vector3i blockLocation = event.getBlockPosition();
            processUpdateForBlockLocation(blockLocation);
        }
    }

    private void processUpdateForBlockLocation(Vector3i blockLocation) {
        for (int height : checkOnHeight) {
            for (Side side : Side.horizontalSides()) {
                Vector3i neighborLocation = new Vector3i(blockLocation);
                neighborLocation.add(side.getVector3i());
                neighborLocation.y += height;
                Block neighborBlock = worldProvider.getBlock(neighborLocation);
                final BlockFamily blockFamily = neighborBlock.getBlockFamily();
                if (blockFamily instanceof RailsUpdatesFamily) {
                    RailsUpdatesFamily railsFamily = (RailsUpdatesFamily) blockFamily;
                    Block neighborBlockAfterUpdate = railsFamily.getBlockForNeighborRailUpdate(worldProvider, blockEntityRegistry, neighborLocation, neighborBlock);
                    if (neighborBlock != neighborBlockAfterUpdate && neighborBlockAfterUpdate != null) {
                        worldProvider.setBlock(neighborLocation, neighborBlockAfterUpdate);
                    }
                }
            }
        }
    }

    @Override
    public void update(float delta) {
        if (largeBlockUpdateCount > 0) {
            logger.error("Unmatched LargeBlockUpdateStarted - LargeBlockUpdateFinished not invoked enough times");
        }
        largeBlockUpdateCount = 0;
    }
}


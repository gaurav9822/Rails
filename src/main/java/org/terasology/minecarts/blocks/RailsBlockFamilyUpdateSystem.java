// Copyright 2020 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.minecarts.blocks;

import com.google.common.collect.Sets;
import org.joml.Vector3f;
import org.joml.Vector3i;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.entitySystem.entity.EntityRef;
import org.terasology.entitySystem.event.EventPriority;
import org.terasology.entitySystem.event.ReceiveEvent;
import org.terasology.entitySystem.systems.BaseComponentSystem;
import org.terasology.entitySystem.systems.RegisterMode;
import org.terasology.entitySystem.systems.RegisterSystem;
import org.terasology.entitySystem.systems.UpdateSubscriberSystem;
import org.terasology.logic.common.ActivateEvent;
import org.terasology.logic.health.event.DoDamageEvent;
import org.terasology.logic.health.DoDestroyEvent;
import org.terasology.logic.health.EngineDamageTypes;
import org.terasology.logic.inventory.ItemComponent;
import org.terasology.math.JomlUtil;
import org.terasology.math.Side;
import org.terasology.math.SideBitFlag;
import org.terasology.registry.In;
import org.terasology.world.BlockEntityRegistry;
import org.terasology.world.OnChangedBlock;
import org.terasology.world.WorldProvider;
import org.terasology.world.block.Block;
import org.terasology.world.block.BlockComponent;
import org.terasology.world.block.BlockManager;
import org.terasology.world.block.entity.neighbourUpdate.LargeBlockUpdateFinished;
import org.terasology.world.block.entity.neighbourUpdate.LargeBlockUpdateStarting;
import org.terasology.world.block.family.BlockPlacementData;
import org.terasology.world.block.items.BlockItemComponent;
import org.terasology.world.block.items.OnBlockItemPlaced;

import java.util.Set;

@RegisterSystem(RegisterMode.AUTHORITY)
public class RailsBlockFamilyUpdateSystem extends BaseComponentSystem implements UpdateSubscriberSystem {
    private static final Logger logger = LoggerFactory.getLogger(RailsBlockFamilyUpdateSystem.class);

    @In
    private WorldProvider worldProvider;
    @In
    private BlockEntityRegistry blockEntityRegistry;
    @In
    private BlockManager blockManager;

    private int largeBlockUpdateCount;
    private Set<Vector3i> blocksUpdatedInLargeBlockUpdate = Sets.newHashSet();
    private int[] checkOnHeight = {-1, 0, 1};

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

    @ReceiveEvent()
    public void doDestroy(DoDestroyEvent event, EntityRef entity, BlockComponent blockComponent) {
        Vector3i upBlock = new Vector3i(JomlUtil.from(blockComponent.position));
        upBlock.y += 1;
        Block block = worldProvider.getBlock(upBlock);

        if (block.getBlockFamily() instanceof RailBlockFamily) {
            blockEntityRegistry.getEntityAt(upBlock).send(new DoDamageEvent(1000, EngineDamageTypes.DIRECT.get()));
        }
    }

    //prevents rails from being stacked on top of each other.
    @ReceiveEvent(components = {BlockItemComponent.class, ItemComponent.class}, priority = EventPriority.PRIORITY_HIGH)
    public void onBlockActivated(ActivateEvent event, EntityRef item) {
        BlockComponent blockComponent = event.getTarget().getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }

        Vector3i targetBlock = JomlUtil.from(blockComponent.position);
        Block centerBlock = worldProvider.getBlock(targetBlock.x, targetBlock.y, targetBlock.z);

        if (centerBlock.getBlockFamily() instanceof RailBlockFamily) {
            event.consume();
        }
    }

    @ReceiveEvent(components = {BlockItemComponent.class, ItemComponent.class})
    public void onPlaceBlock(OnBlockItemPlaced event, EntityRef entity) {
        BlockComponent blockComponent = event.getPlacedBlock().getComponent(BlockComponent.class);
        if (blockComponent == null) {
            return;
        }

        Vector3i targetBlock = JomlUtil.from(blockComponent.position);
        Block centerBlock = worldProvider.getBlock(targetBlock.x, targetBlock.y, targetBlock.z);

        if (centerBlock.getBlockFamily() instanceof RailBlockFamily) {
            processUpdateForBlockLocation(targetBlock);
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
            blocksUpdatedInLargeBlockUpdate.add(JomlUtil.from(event.getBlockPosition()));
        } else {
            Vector3i blockLocation = JomlUtil.from(event.getBlockPosition());
            processUpdateForBlockLocation(blockLocation);
        }
    }

    private void processUpdateForBlockLocation(Vector3i blockLocation) {
        for (int height : checkOnHeight) {
            for (Side side : Side.horizontalSides()) {
                Vector3i neighborLocation = new Vector3i(blockLocation);
                neighborLocation.add(side.direction());
                neighborLocation.y += height;
                Block neighborBlock = worldProvider.getBlock(neighborLocation);
                EntityRef blockEntity = blockEntityRegistry.getBlockEntityAt(neighborLocation);
                if (blockEntity.hasComponent(RailComponent.class)) {
                    RailBlockFamily railsFamily = (RailBlockFamily) neighborBlock.getBlockFamily();
                    Block neighborBlockAfterUpdate = railsFamily.getBlockForPlacement(new BlockPlacementData(neighborLocation, Side.FRONT, new Vector3f()));
                    if (neighborBlock != neighborBlockAfterUpdate && neighborBlockAfterUpdate != null) {
                        byte connections = Byte.parseByte(neighborBlock.getURI().getIdentifier().toString());
                        //only add segment with two connections
                        if (SideBitFlag.getSides(connections).size() <= 1) {
                            worldProvider.setBlock(neighborLocation, neighborBlockAfterUpdate);
                        }
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

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

package com.sk89q.worldedit.fabric.internal;

import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.sk89q.worldedit.fabric.FabricAdapter;
import com.sk89q.worldedit.registry.state.BooleanProperty;
import com.sk89q.worldedit.registry.state.DirectionalProperty;
import com.sk89q.worldedit.registry.state.EnumProperty;
import com.sk89q.worldedit.registry.state.IntegerProperty;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.Direction;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.StateDefinition;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Raw, un-cached transformations.
 */
public class FabricTransmogrifier {

    private static final LoadingCache<net.minecraft.world.level.block.state.properties.Property<?>, Property<?>> PROPERTY_CACHE = CacheBuilder.newBuilder().build(new CacheLoader<>() {
        @Override
        public Property<?> load(net.minecraft.world.level.block.state.properties.Property<?> property) throws Exception {
            // FAWE property implementations mutate their value lists during block
            // registry initialisation, so they must be handed mutable copies.
            return switch (property) {
                case net.minecraft.world.level.block.state.properties.BooleanProperty booleanProperty ->
                    new BooleanProperty(property.getName(), new ArrayList<>(booleanProperty.getPossibleValues()));
                case net.minecraft.world.level.block.state.properties.IntegerProperty integerProperty ->
                    new IntegerProperty(property.getName(), new ArrayList<>(integerProperty.getPossibleValues()));
                case net.minecraft.world.level.block.state.properties.EnumProperty<?> enumProperty -> {
                    if (property.getValueClass() == net.minecraft.core.Direction.class) {
                        yield new DirectionalProperty(property.getName(), property.getPossibleValues().stream()
                            .map(v -> FabricAdapter.adaptEnumFacing((net.minecraft.core.Direction) v))
                            .collect(Collectors.toCollection(ArrayList::new)));
                    }
                    // Note: do not make x.asString a method reference.
                    // It will cause runtime bootstrap exceptions.
                    //noinspection Convert2MethodRef
                    yield new EnumProperty(property.getName(), enumProperty.getPossibleValues().stream()
                        .map(x -> x.getSerializedName())
                        .collect(Collectors.toCollection(ArrayList::new)));
                }
                default -> new FabricPropertyAdapter<>(property);
            };
        }
    });

    public static Property<?> transmogToWorldEditProperty(net.minecraft.world.level.block.state.properties.Property<?> property) {
        return PROPERTY_CACHE.getUnchecked(property);
    }

    private static Map<Property<?>, Object> transmogToWorldEditProperties(BlockType block, java.util.List<net.minecraft.world.level.block.state.properties.Property.Value<?>> mcProps) {
        Map<Property<?>, Object> props = new TreeMap<>(Comparator.comparing(Property::getName));
        for (net.minecraft.world.level.block.state.properties.Property.Value<?> prop : mcProps) {
            Object value = prop.value();
            // WE BlockType.getState expects String values; NMS hands us raw Booleans/Integers.
            if (prop.property() instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                if (prop.property().getValueClass() == net.minecraft.core.Direction.class) {
                    value = FabricAdapter.adaptEnumFacing((net.minecraft.core.Direction) value);
                } else {
                    value = ((StringRepresentable) value).getSerializedName();
                }
            }
            props.put(block.getProperty(prop.property().getName()), value);
        }
        return props;
    }

    @SuppressWarnings({ "unchecked", "rawtypes" })
    private static net.minecraft.world.level.block.state.BlockState transmogToMinecraftProperties(
        StateDefinition<Block, net.minecraft.world.level.block.state.BlockState> stateContainer,
        net.minecraft.world.level.block.state.BlockState newState,
        Map<Property<?>, Object> states
    ) {
        for (Map.Entry<Property<?>, Object> state : states.entrySet()) {
            net.minecraft.world.level.block.state.properties.Property property = stateContainer.getProperty(state.getKey().getName());
            Comparable value = (Comparable) state.getValue();
            // we may need to adapt this value, depending on the source prop
            if (property instanceof net.minecraft.world.level.block.state.properties.EnumProperty) {
                if (property.getValueClass() == net.minecraft.core.Direction.class) {
                    Direction dir = (Direction) value;
                    value = FabricAdapter.adapt(dir);
                } else {
                    String enumName = (String) value;
                    value = ((net.minecraft.world.level.block.state.properties.EnumProperty<?>) property).getValue((String) value).orElseThrow(() ->
                        new IllegalStateException("Enum property " + property.getName() + " does not contain " + enumName)
                    );
                }
            }

            newState = newState.setValue(property, value);
        }
        return newState;
    }

    /**
     * WE BlockStates are interned (BlockTypesCache), so caching by value is safe:
     * repeated blocks, the norm in large edits, transmog with a single lookup.
     */
    private static final java.util.concurrent.ConcurrentHashMap<BlockState, net.minecraft.world.level.block.state.BlockState> TO_MINECRAFT_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    public static net.minecraft.world.level.block.state.BlockState transmogToMinecraft(BlockState blockState) {
        return TO_MINECRAFT_CACHE.computeIfAbsent(blockState, FabricTransmogrifier::computeToMinecraft);
    }

    private static net.minecraft.world.level.block.state.BlockState computeToMinecraft(BlockState blockState) {
        Block mcBlock = FabricAdapter.adapt(blockState.getBlockType());
        net.minecraft.world.level.block.state.BlockState newState = mcBlock.defaultBlockState();
        Map<Property<?>, Object> states = blockState.getStates();
        return transmogToMinecraftProperties(mcBlock.getStateDefinition(), newState, states);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static com.sk89q.worldedit.world.block.BlockState transmogToWorldEdit(net.minecraft.world.level.block.state.BlockState blockState) {
        BlockType blockType = FabricAdapter.adapt(blockState.getBlock());
        com.sk89q.worldedit.world.block.BlockState state = blockType.getDefaultState();
        for (Map.Entry<Property<?>, Object> entry : transmogToWorldEditProperties(
                blockType, blockState.getValues().toList()).entrySet()) {
            state = state.with((Property) entry.getKey(), entry.getValue());
        }
        return state;
    }

    private FabricTransmogrifier() {
    }
}

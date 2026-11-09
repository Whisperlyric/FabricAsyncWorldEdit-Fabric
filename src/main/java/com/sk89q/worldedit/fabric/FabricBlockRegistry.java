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

package com.sk89q.worldedit.fabric;

import com.sk89q.worldedit.fabric.internal.FabricTransmogrifier;
import com.sk89q.worldedit.registry.state.Property;
import com.sk89q.worldedit.util.formatting.text.Component;
import com.sk89q.worldedit.util.formatting.text.TranslatableComponent;
import com.sk89q.worldedit.world.block.BlockState;
import com.sk89q.worldedit.world.block.BlockType;
import com.sk89q.worldedit.world.registry.BlockMaterial;
import com.sk89q.worldedit.world.registry.BundledBlockRegistry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.TreeMap;

public class FabricBlockRegistry extends BundledBlockRegistry {

    private final Map<net.minecraft.world.level.block.state.BlockState, FabricBlockMaterial> materialMap = new HashMap<>();

    @Override
    public Component getRichName(BlockType blockType) {
        return TranslatableComponent.of(FabricAdapter.adapt(blockType).getDescriptionId());
    }

    @Override
    public BlockMaterial getMaterial(BlockType blockType) {
        Block block = FabricAdapter.adapt(blockType);
        return materialMap.computeIfAbsent(
            block.defaultBlockState(),
            m -> new FabricBlockMaterial(m, super.getMaterial(blockType))
        );
    }

    @Override
    public Map<String, ? extends Property<?>> getProperties(BlockType blockType) {
        Block block = FabricAdapter.adapt(blockType);
        Map<String, Property<?>> map = new TreeMap<>();
        Collection<net.minecraft.world.level.block.state.properties.Property<?>> propertyKeys = block
                .defaultBlockState()
                .getProperties();
        for (net.minecraft.world.level.block.state.properties.Property<?> key : propertyKeys) {
            map.put(key.getName(), FabricTransmogrifier.transmogToWorldEditProperty(key));
        }
        return map;
    }

    @Override
    public OptionalInt getInternalBlockStateId(BlockState state) {
        net.minecraft.world.level.block.state.BlockState equivalent = FabricAdapter.adapt(state);
        return OptionalInt.of(Block.getId(equivalent));
    }

    // FAWE start
    @Override
    public Collection<String> values() {
        List<String> blocks = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            Identifier id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState state = block.defaultBlockState();
            var values = state.getValues().toList();
            if (values.isEmpty()) {
                blocks.add(id.toString());
                continue;
            }
            StringBuilder builder = new StringBuilder(id.toString()).append('[');
            boolean first = true;
            for (var entry : values.stream()
                .sorted(Comparator.comparing(value -> value.property().getName()))
                .toList()
            ) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(entry.property().getName()).append('=');
                Comparable<?> value = entry.value();
                if (value instanceof StringRepresentable stringRepresentable) {
                    builder.append(stringRepresentable.getSerializedName());
                } else {
                    builder.append(value);
                }
            }
            builder.append(']');
            blocks.add(builder.toString());
        }
        return blocks;
    }

    @Override
    public Map<String, ? extends List<Property<?>>> getAllProperties() {
        Map<String, List<Property<?>>> all = new TreeMap<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            for (net.minecraft.world.level.block.state.properties.Property<?> property : block.defaultBlockState().getProperties()) {
                Property<?> weProperty = FabricTransmogrifier.transmogToWorldEditProperty(property);
                String key = weProperty.getName().toLowerCase(Locale.ROOT);
                all.computeIfAbsent(key, ignored -> new ArrayList<>()).add(weProperty);
            }
        }
        return all;
    }
    // FAWE end
}

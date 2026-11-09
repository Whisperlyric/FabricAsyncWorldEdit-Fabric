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

import com.sk89q.worldedit.util.PropertiesConfiguration;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

public class FabricConfiguration extends PropertiesConfiguration {

    public boolean creativeEnable = false;
    public boolean cheatMode = false;

    /** Capture block entities in history snapshots. */
    public boolean historyTiles = true;
    /** Apply/copy block entities (containers, signs, etc.). */
    public boolean tileOperations = true;
    /** Capture biomes in history snapshots. */
    public boolean historyBiomes = true;
    /** Capture entities in history snapshots. Expensive; disabled by default. */
    public boolean historyEntities = false;
    /** Skip entity creation when the UUID already exists. */
    public boolean historyEntityUuidSkip = true;

    public FabricConfiguration(FabricWorldEdit mod) {
        super(mod.getWorkingDir().resolve("worldedit.properties"));
    }

    @Override
    protected void loadExtra() {
        creativeEnable = getBool("use-in-creative", false);
        cheatMode = getBool("cheat-mode", false);
        historyTiles = getBool("history.tiles", true);
        tileOperations = getBool("tiles.operations", true);
        historyBiomes = getBool("history.biomes", true);
        historyEntities = getBool("history.entities", false);
        historyEntityUuidSkip = getBool("history.entity-uuid-skip", true);
    }

    /**
     * Persists one boolean so in-game toggles survive a restart.
     * PropertiesConfiguration has no public save method, so write the store atomically.
     */
    public void saveBoolean(String key, boolean value) {
        properties.setProperty(key, Boolean.toString(value));
        try (OutputStream out = Files.newOutputStream(path.toPath())) {
            properties.store(out, "FAWE Fabric configuration");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public Path getWorkingDirectoryPath() {
        return FabricWorldEdit.inst.getWorkingDir();
    }
}

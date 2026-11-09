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

import com.google.common.collect.ImmutableList;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.event.platform.CommandSuggestionEvent;
import com.sk89q.worldedit.extension.platform.Actor;
import com.sk89q.worldedit.internal.util.Substring;
import net.minecraft.commands.CommandSourceStack;
import org.enginehub.piston.inject.InjectedValueStore;
import org.enginehub.piston.inject.Key;
import org.enginehub.piston.inject.MapBackedValueStore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.sk89q.worldedit.fabric.FabricAdapter.adaptCommandSource;
import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;


public final class CommandWrapper {

    private static final Pattern SPACE_COORDINATES = Pattern.compile(
            "^(//?pos[12])\\s+(\\S+)\\s+(\\S+)\\s+(\\S+)\\s*$");

    private CommandWrapper() {
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, org.enginehub.piston.Command command) {
        ImmutableList.Builder<String> aliases = ImmutableList.builder();
        aliases.add(command.getName()).addAll(command.getAliases());

        Command<CommandSourceStack> commandRunner = ctx -> {
            // FAWE defers chunk writes to the server thread. Drain what fits in the
            // command budget here so a command issued right after another one (//set 0
            // then //stack 2) reads the applied chunk instead of the blocks //set just
            // replaced. Budget-bound: anything beyond it still applies via the tick
            // handler, so the very tail of a huge backlog can remain briefly stale.
            com.sk89q.worldedit.fabric.internal.FabricPendingApplies.drainAll();
            String input = ctx.getInput();
            if (!input.startsWith("/")) {
                input = "/" + input;
            }
            input = normalizeSpaceCoordinates(input);
            WorldEdit.getInstance().getEventBus().post(new com.sk89q.worldedit.event.platform.CommandEvent(
                adaptCommandSource(ctx.getSource()),
                input
            ));
            return 0;
        };

        for (String alias : aliases.build()) {
            LiteralArgumentBuilder<CommandSourceStack> base = literal(alias).executes(commandRunner)
                    .then(argument("args", StringArgumentType.greedyString())
                            .suggests(CommandWrapper::suggest)
                            .executes(commandRunner));
            if (command.getCondition() != org.enginehub.piston.Command.Condition.TRUE) {
                base.requires(requirementsFor(command));
            }
            dispatcher.register(base);
        }
    }

    /**
     * WorldEdit's BlockVector3 parser expects {@code x,y,z}; Fabric users often type
     * {@code x y z}. Normalise only pos1/pos2 so patterns containing spaces are untouched.
     */
    private static String normalizeSpaceCoordinates(String input) {
        Matcher matcher = SPACE_COORDINATES.matcher(input);
        if (matcher.matches()) {
            return matcher.group(1) + " " + matcher.group(2) + "," + matcher.group(3) + "," + matcher.group(4);
        }
        return input;
    }

    private static Predicate<CommandSourceStack> requirementsFor(org.enginehub.piston.Command mapping) {
        return ctx -> {
            InjectedValueStore store = MapBackedValueStore.create();
            final Actor actor = FabricAdapter.adaptCommandSource(ctx);
            store.injectValue(Key.of(Actor.class), context -> Optional.of(actor));
            return mapping.getCondition().satisfied(store);
        };
    }

    private static CompletableFuture<Suggestions> suggest(CommandContext<CommandSourceStack> context,
            SuggestionsBuilder builder) throws CommandSyntaxException {
        CommandSuggestionEvent event = new CommandSuggestionEvent(
                FabricAdapter.adaptCommandSource(context.getSource()),
                builder.getInput()
        );
        WorldEdit.getInstance().getEventBus().post(event);
        List<Substring> suggestions = event.getSuggestions();

        ImmutableList.Builder<Suggestion> result = ImmutableList.builder();

        for (Substring suggestion : suggestions) {
            String suggestionText = suggestion.getSubstring();
            // If at end, we are actually suggesting the next argument
            // Ensure there is a space!
            if (suggestion.getStart() == suggestion.getEnd()
                    && suggestion.getEnd() == builder.getInput().length()
                    && !builder.getInput().endsWith(" ")
                    && !builder.getInput().endsWith("\"")) {
                suggestionText = " " + suggestionText;
            }
            result.add(new Suggestion(
                    StringRange.between(suggestion.getStart(), suggestion.getEnd()),
                    suggestionText
            ));
        }

        return CompletableFuture.completedFuture(
                Suggestions.create(builder.getInput(), result.build())
        );
    }

}

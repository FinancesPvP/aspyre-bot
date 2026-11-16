package cc.aspyre.bot.features;

import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.List;

/** A group of related commands/listeners. Main registers every feature's commands on startup. */
public interface Feature {
    List<CommandData> commands();
}

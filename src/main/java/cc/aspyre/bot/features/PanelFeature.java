package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Messages;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.util.List;
import java.util.Optional;

/**
 * /rules, /info, /boosts, /sync-panel, /ticket-panel, /bug-panel, /suggestion-panel, /recruit-panel,
 * /general-pin, /media-pin, /commands-pin, /memes-pin.
 *
 * Staff: posts the embed in the channel you run it in (and pins it unless pin:false).
 * Everyone else, or staff with private:true: shows a copy only you can see.
 */
public class PanelFeature extends ListenerAdapter implements Feature {
    private final Bot bot;

    public PanelFeature(Bot bot) {
        this.bot = bot;
    }

    @Override
    public List<CommandData> commands() {
        return Messages.PANELS.stream()
                .map(p -> (CommandData) Commands.slash(p.command(), p.description())
                        .addOption(OptionType.BOOLEAN, "private", "Staff: only show it to yourself instead of posting it")
                        .addOption(OptionType.BOOLEAN, "pin", "Staff: pin the posted message (default: yes)"))
                .toList();
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        Optional<Messages.Panel> panel = Messages.PANELS.stream()
                .filter(p -> p.command().equals(event.getName()))
                .findFirst();
        if (panel.isEmpty()) return;

        MessageCreateData data = panel.get().build().apply(bot);
        boolean wantsPrivate = event.getOption("private", false, OptionMapping::getAsBoolean);
        boolean pin = event.getOption("pin", true, OptionMapping::getAsBoolean);

        if (!bot.isStaff(event.getMember()) || wantsPrivate) {
            event.reply(data).setEphemeral(true).queue();
            return;
        }

        event.deferReply(true).queue();
        event.getChannel().sendMessage(data).queue(msg -> {
            if (pin) msg.pin().queue(null, err -> {});
            event.getHook().sendMessage("Posted" + (pin ? " and pinned." : ".")).queue();
        }, err -> event.getHook()
                .sendMessage("I couldn't post here. Check that I can send messages in this channel.").queue());
    }
}

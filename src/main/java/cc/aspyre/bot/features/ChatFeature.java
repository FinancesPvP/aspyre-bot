package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Database;
import cc.aspyre.bot.Messages;
import cc.aspyre.bot.Util;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.util.List;
import java.util.Map;

/** CHAT: /recruit posts (one per 24h) and /stats for synced players. */
public class ChatFeature extends ListenerAdapter implements Feature {
    private static final long RECRUIT_COOLDOWN = 24 * 3600;
    private final Bot bot;

    public ChatFeature(Bot bot) {
        this.bot = bot;
    }

    @Override
    public List<CommandData> commands() {
        return List.of(
                Commands.slash("recruit", "Post that you're looking for a team or for players")
                        .addOptions(new OptionData(OptionType.STRING, "kind", "What you're looking for", true)
                                .addChoice("LFT – looking for a team", "LFT")
                                .addChoice("LFP – looking for players", "LFP")),
                Commands.slash("stats", "See a member's linked Minecraft account")
                        .addOption(OptionType.USER, "member", "Who to look up (default: you)"));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "recruit" -> recruit(event);
            case "stats" -> stats(event);
            default -> { }
        }
    }

    private void recruit(SlashCommandInteractionEvent event) {
        Map<String, Object> row = bot.db.one("SELECT last_post FROM recruit_posts WHERE user_id = ?", event.getUser().getIdLong());
        if (row != null && Util.nowSec() - Database.num(row, "last_post") < RECRUIT_COOLDOWN && !bot.isStaff(event.getMember())) {
            event.reply("You can post again " + Util.ts(Database.num(row, "last_post") + RECRUIT_COOLDOWN, 'R') + ".")
                    .setEphemeral(true).queue();
            return;
        }
        String kind = event.getOption("kind", OptionMapping::getAsString);
        Modal modal = Modal.create("recruit:" + kind, kind.equals("LFT") ? "Looking for Team" : "Looking for Players")
                .addActionRow(TextInput.create("name", "Your name or team name", TextInputStyle.SHORT).setMaxLength(60).build())
                .addActionRow(TextInput.create("role", "Role / playstyle", TextInputStyle.SHORT)
                        .setPlaceholder("PvP, builder, base, farmer...").setMaxLength(100).build())
                .addActionRow(TextInput.create("availability", "Availability", TextInputStyle.SHORT)
                        .setPlaceholder("Weekends, 6–11pm CST").setMaxLength(100).build())
                .addActionRow(TextInput.create("experience", "Experience", TextInputStyle.PARAGRAPH)
                        .setPlaceholder("Past teams or maps").setMaxLength(500).build())
                .build();
        event.replyModal(modal).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().startsWith("recruit:")) return;
        String kind = event.getModalId().substring("recruit:".length());
        GuildMessageChannel ch = bot.channelOrComplain(event, "recruitment");
        if (ch == null) return;

        Map<String, Object> link = bot.db.one("SELECT ign FROM links WHERE discord_id = ?", event.getUser().getIdLong());
        String ign = link == null ? "Not synced" : Database.str(link, "ign");
        String heading = kind.equals("LFT") ? "Looking for a Team" : "Looking for Players";
        ch.sendMessageEmbeds(Messages.base(bot, heading, "**" + event.getValue("name").getAsString() + "**")
                .addField("IGN", ign, true)
                .addField("Role", event.getValue("role").getAsString(), true)
                .addField("Availability", event.getValue("availability").getAsString(), true)
                .addField("Experience", event.getValue("experience").getAsString(), false)
                .addField("Contact", event.getUser().getAsMention(), true)
                .setThumbnail(event.getUser().getEffectiveAvatarUrl())
                .build()).queue();
        bot.db.update("INSERT OR REPLACE INTO recruit_posts VALUES (?, ?)", event.getUser().getIdLong(), Util.nowSec());
        event.reply("Your post is up in recruitment!").setEphemeral(true).queue();
    }

    private void stats(SlashCommandInteractionEvent event) {
        Member member = event.getOption("member", event.getMember(), OptionMapping::getAsMember);
        if (member == null) {
            event.reply("That person isn't in the server.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> link = bot.db.one("SELECT * FROM links WHERE discord_id = ?", member.getIdLong());
        if (link == null) {
            String who = member.equals(event.getMember()) ? "You haven't" : member.getEffectiveName() + " hasn't";
            event.reply(who + " synced a Minecraft account yet. See " + Messages.ch(bot, "sync") + ".").setEphemeral(true).queue();
            return;
        }
        String rank = Database.str(link, "rank");
        event.replyEmbeds(Messages.base(bot, Database.str(link, "ign"), null)
                .addField("Discord", member.getAsMention(), true)
                .addField("Rank", Util.or(rank, "Default"), true)
                .addField("Synced", Util.ts(Database.num(link, "linked_at"), 'D'), true)
                .setThumbnail("https://mc-heads.net/avatar/" + Database.str(link, "uuid") + "/64")
                .build()).queue();
    }
}

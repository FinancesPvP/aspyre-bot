package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Database;
import cc.aspyre.bot.Messages;
import cc.aspyre.bot.Util;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageReaction;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** NEWS: announcements, live updates, polls, payouts, giveaways, leaderboard and f-top strikes. */
public class NewsFeature extends ListenerAdapter implements Feature {
    private static final Logger log = LoggerFactory.getLogger(NewsFeature.class);
    private static final EnumSet<Message.MentionType> PINGS = EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE);
    private static final Gson GSON = new Gson();

    private final Bot bot;
    /** Background thread for timers and anything that waits on Discord (keeps JDA's event thread free). */
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    public NewsFeature(Bot bot) {
        this.bot = bot;
    }

    @Override
    public List<CommandData> commands() {
        return List.of(
                Commands.slash("say", "Staff: post a message as the bot")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where to post it (default: this channel)")
                                .setChannelTypes(net.dv8tion.jda.api.entities.channel.ChannelType.TEXT,
                                        net.dv8tion.jda.api.entities.channel.ChannelType.NEWS)),
                Commands.slash("announce", "Staff: post an announcement")
                        .addOption(OptionType.BOOLEAN, "ping", "Ping the announcements role? (default: yes)"),
                Commands.slash("liveupdate", "Staff: announce a live update on the UPDATE stage")
                        .addOption(OptionType.STRING, "topic", "What the update is about", true),
                Commands.slash("poll", "Staff: start a poll")
                        .addOption(OptionType.STRING, "question", "What are you asking?", true)
                        .addOption(OptionType.STRING, "options", "2 to 5 options separated by | (e.g. Yes | No | Maybe)", true)
                        .addOption(OptionType.STRING, "duration", "How long, e.g. 30m, 6h, 1d (default 1d)"),
                Commands.slash("payout", "Staff: log a payout in #payouts")
                        .addOption(OptionType.STRING, "event", "e.g. KOTH, EOTW, Giveaway", true)
                        .addOption(OptionType.STRING, "winners", "Mention the winner(s) or type the team name", true)
                        .addOption(OptionType.STRING, "reward", "e.g. $50 PayPal, 3x Crate Keys", true),
                Commands.slash("giveaway", "Staff: run giveaways").addSubcommands(
                        new SubcommandData("start", "Start a giveaway")
                                .addOption(OptionType.STRING, "prize", "What they win", true)
                                .addOption(OptionType.STRING, "duration", "e.g. 1h, 2d", true)
                                .addOptions(new OptionData(OptionType.INTEGER, "winners", "Number of winners (default 1)")
                                        .setRequiredRange(1, 20))
                                .addOption(OptionType.STRING, "requirements", "Optional entry requirements"),
                        new SubcommandData("end", "End a giveaway early")
                                .addOption(OptionType.STRING, "message_id", "Right-click the giveaway → Copy Message ID", true),
                        new SubcommandData("reroll", "Pick new winner(s) for an ended giveaway")
                                .addOption(OptionType.STRING, "message_id", "Right-click the giveaway → Copy Message ID", true)
                                .addOptions(new OptionData(OptionType.INTEGER, "winners", "How many (default 1)")
                                        .setRequiredRange(1, 20))),
                Commands.slash("leaderboard", "Faction leaderboard").addSubcommands(
                        new SubcommandData("show", "See the current leaderboard"),
                        new SubcommandData("set", "Staff: set the leaderboard by hand")
                                .addOption(OptionType.STRING, "entries", "e.g. Raiders:1200, Vipers:950", true)
                                .addOption(OptionType.STRING, "title", "e.g. Map 3")),
                Commands.slash("strike", "F-top strikes").addSubcommands(
                        new SubcommandData("add", "Staff: give a faction an f-top strike")
                                .addOption(OptionType.STRING, "faction", "Faction name", true)
                                .addOption(OptionType.STRING, "reason", "Why", true)
                                .addOptions(new OptionData(OptionType.INTEGER, "points", "F-top points removed")
                                        .setMinValue(0)),
                        new SubcommandData("remove", "Staff: remove a strike (e.g. after an appeal)")
                                .addOption(OptionType.INTEGER, "strike_id", "The Strike ID in the strike's footer", true),
                        new SubcommandData("list", "See a faction's active strikes")
                                .addOption(OptionType.STRING, "faction", "Faction name", true),
                        new SubcommandData("reset", "Staff: clear every strike (new map)")));
    }

    // ==================================================================== routing

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        String sub = event.getSubcommandName();
        switch (event.getName()) {
            case "announce" -> announce(event);
            case "say" -> say(event);
            case "liveupdate" -> liveUpdate(event);
            case "poll" -> poll(event);
            case "payout" -> payout(event);
            case "giveaway" -> {
                if (!bot.requireStaff(event)) return;
                switch (sub) {
                    case "start" -> giveawayStart(event);
                    case "end" -> giveawayEnd(event);
                    case "reroll" -> giveawayReroll(event);
                    default -> { }
                }
            }
            case "leaderboard" -> {
                if ("show".equals(sub)) leaderboardShow(event);
                else leaderboardSet(event);
            }
            case "strike" -> {
                switch (sub) {
                    case "add" -> strikeAdd(event);
                    case "remove" -> strikeRemove(event);
                    case "list" -> strikeList(event);
                    case "reset" -> strikeReset(event);
                    default -> { }
                }
            }
            default -> { }
        }
    }

    // ==================================================================== announcements

    private void announce(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        boolean ping = event.getOption("ping", true, OptionMapping::getAsBoolean);
        Modal modal = Modal.create("announce:modal:" + ping, "New announcement")
                .addActionRow(TextInput.create("title", "Title", TextInputStyle.SHORT).setMaxLength(200).build())
                .addActionRow(TextInput.create("body", "Message", TextInputStyle.PARAGRAPH).setMaxLength(3000).build())
                .addActionRow(TextInput.create("changes", "What's changing (one per line, optional)", TextInputStyle.PARAGRAPH)
                        .setRequired(false).setMaxLength(1000).build())
                .addActionRow(TextInput.create("when", "When (optional: text, or a unix timestamp)", TextInputStyle.SHORT)
                        .setRequired(false).setMaxLength(100).build())
                .build();
        event.replyModal(modal).queue();
    }

    private void say(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        var opt = event.getOption("channel");
        long target = opt != null ? opt.getAsChannel().getIdLong() : event.getChannel().getIdLong();
        event.replyModal(Modal.create("say:modal:" + target, "Post as the bot")
                .addActionRow(TextInput.create("title", "Title (optional)", TextInputStyle.SHORT)
                        .setRequired(false).setMaxLength(256).build())
                .addActionRow(TextInput.create("body", "Message", TextInputStyle.PARAGRAPH).setMaxLength(4000).build())
                .build()).queue();
    }

    private void submitSay(ModalInteractionEvent event) {
        long target = Long.parseLong(event.getModalId().substring("say:modal:".length()));
        GuildMessageChannel ch = event.getGuild() == null ? null : event.getGuild().getChannelById(GuildMessageChannel.class, target);
        if (ch == null) {
            event.reply("I can't find that channel.").setEphemeral(true).queue();
            return;
        }
        String title = event.getValue("title") == null ? "" : event.getValue("title").getAsString().trim();
        EmbedBuilder eb = Messages.base(bot, title.isEmpty() ? null : title, null)
                .setDescription(event.getValue("body").getAsString());
        ch.sendMessageEmbeds(eb.build()).queue(
                msg -> event.reply("Posted: " + msg.getJumpUrl()).setEphemeral(true).queue(),
                err -> event.reply("I couldn't post there (missing permissions?).").setEphemeral(true).queue());
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (event.getModalId().startsWith("say:modal:")) { submitSay(event); return; }
        if (!event.getModalId().startsWith("announce:modal:")) return;
        boolean ping = event.getModalId().endsWith("true");
        String title = event.getValue("title").getAsString();
        EmbedBuilder eb = Messages.base(bot, title, event.getValue("body").getAsString());

        String changes = event.getValue("changes") == null ? "" : event.getValue("changes").getAsString().trim();
        if (!changes.isEmpty()) {
            StringBuilder list = new StringBuilder();
            for (String line : changes.split("\n")) if (!line.isBlank()) list.append("• ").append(line.trim()).append('\n');
            eb.addField("What's changing", list.toString(), false);
        }
        String when = event.getValue("when") == null ? "" : event.getValue("when").getAsString().trim();
        if (!when.isEmpty()) {
            eb.addField("When", when.matches("\\d+") ? Util.ts(Long.parseLong(when), 'F') : when, true);
        }
        eb.addField("Posted by", event.getUser().getAsMention(), true);

        GuildMessageChannel ch = bot.channelOrComplain(event, "announcements");
        if (ch == null) return;
        String role = Messages.role(bot, "announcements_ping");
        var action = ch.sendMessageEmbeds(eb.build()).setAllowedMentions(PINGS);
        if (ping && !role.isEmpty()) action = action.setContent(role);
        action.queue(msg -> {
            if (ch instanceof NewsChannel) msg.crosspost().queue(null, err -> {}); // publish to followers
            event.reply("Posted: " + msg.getJumpUrl()).setEphemeral(true).queue();
        });
    }

    private void liveUpdate(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        GuildMessageChannel ch = bot.channelOrComplain(event, "announcements");
        if (ch == null) return;
        String topic = event.getOption("topic", OptionMapping::getAsString);
        String ping = Messages.role(bot, "announcements_ping");
        var live = ch.sendMessageEmbeds(Messages.embed(bot, "Live Update",
                        "We're live now in " + Messages.ch(bot, "update_stage") + "!\n"
                        + "Join us for **" + topic + "**: patch notes, Q&A and what's coming next."))
                .setAllowedMentions(PINGS);
        if (!ping.isEmpty()) live = live.setContent(ping);
        live.queue();
        event.reply("Live update announced.").setEphemeral(true).queue();
    }

    // ==================================================================== polls

    private void poll(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        String question = event.getOption("question", OptionMapping::getAsString);
        List<String> opts = Arrays.stream(event.getOption("options", OptionMapping::getAsString).split("\\|"))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        long secs = Util.parseDuration(event.getOption("duration", "1d", OptionMapping::getAsString));
        if (opts.size() < 2 || opts.size() > 5) {
            event.reply("Give 2 to 5 options separated by `|`.").setEphemeral(true).queue();
            return;
        }
        if (secs <= 0) {
            event.reply("I couldn't read that duration. Try `30m`, `6h` or `1d`.").setEphemeral(true).queue();
            return;
        }
        GuildMessageChannel ch = bot.channelOrComplain(event, "polls");
        if (ch == null) return;

        long ends = Util.nowSec() + secs;
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < opts.size(); i++) lines.append(Util.LETTERS.get(i)).append(' ').append(opts.get(i)).append('\n');
        MessageEmbed e = Messages.base(bot, "Poll", "**" + question + "**\n\n" + lines)
                .addField("Closes", Util.ts(ends, 'R'), true)
                .addField("How to vote", "React below", true)
                .build();

        var action = ch.sendMessageEmbeds(e).setAllowedMentions(PINGS);
        String role = Messages.role(bot, "polls_ping");
        if (!role.isEmpty()) action = action.setContent(role);
        action.queue(msg -> {
            for (int i = 0; i < opts.size(); i++) msg.addReaction(Emoji.fromUnicode(Util.LETTERS.get(i))).queue();
            bot.db.update("INSERT INTO polls VALUES (?, ?, ?, ?, ?, 0)",
                    msg.getIdLong(), ch.getIdLong(), question, GSON.toJson(opts), ends);
            event.reply("Poll posted: " + msg.getJumpUrl()).setEphemeral(true).queue();
        });
    }

    /** Runs on the worker thread. */
    private void endPoll(Map<String, Object> row) {
        bot.db.update("UPDATE polls SET ended = 1 WHERE message_id = ?", Database.num(row, "message_id"));
        GuildMessageChannel ch = bot.jda.getChannelById(GuildMessageChannel.class, Database.num(row, "channel_id"));
        if (ch == null) return;
        Message msg;
        try {
            msg = ch.retrieveMessageById(Database.num(row, "message_id")).complete();
        } catch (Exception e) {
            return; // message deleted
        }
        List<String> opts = GSON.fromJson(Database.str(row, "options"), new TypeToken<List<String>>() {}.getType());
        int[] counts = new int[opts.size()];
        for (MessageReaction r : msg.getReactions()) {
            int idx = Util.LETTERS.indexOf(r.getEmoji().getName());
            if (idx >= 0 && idx < counts.length) counts[idx] = Math.max(0, r.getCount() - (r.isSelf() ? 1 : 0));
        }
        int total = Math.max(1, Arrays.stream(counts).sum());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < opts.size(); i++) {
            int pct = Math.round(counts[i] * 100f / total);
            String bar = "█".repeat(pct / 10) + "░".repeat(10 - pct / 10);
            sb.append(Util.LETTERS.get(i)).append(" **").append(opts.get(i)).append("**\n`")
              .append(bar).append("` ").append(counts[i]).append(counts[i] == 1 ? " vote" : " votes")
              .append(" (").append(pct).append("%)\n");
        }
        msg.editMessageEmbeds(Messages.base(bot, "Poll Results", "**" + Database.str(row, "question") + "**\n\n" + sb)
                        .addField("Total votes", String.valueOf(Arrays.stream(counts).sum()), true)
                        .build())
                .complete();
    }

    // ==================================================================== payouts

    private void payout(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        GuildMessageChannel ch = bot.channelOrComplain(event, "payouts");
        if (ch == null) return;
        MessageEmbed e = Messages.base(bot, "Payout",
                        "Didn't get your payout within 48 hours? Open a ticket in " + Messages.ch(bot, "support_tickets") + ".")
                .addField("Event", event.getOption("event", OptionMapping::getAsString), true)
                .addField("Reward", event.getOption("reward", OptionMapping::getAsString), true)
                .addField("Date", Util.ts(Util.nowSec(), 'd'), true)
                .addField("Winner(s)", event.getOption("winners", OptionMapping::getAsString), true)
                .addField("Paid by", event.getUser().getAsMention(), true)
                .build();
        ch.sendMessageEmbeds(e).queue();
        event.reply("Payout logged.").setEphemeral(true).queue();
    }

    // ==================================================================== giveaways

    private static Button enterButton() {
        return Button.success("giveaway:enter", "Enter").withEmoji(Emoji.fromUnicode("🎉"));
    }

    private void giveawayStart(SlashCommandInteractionEvent event) {
        String prize = event.getOption("prize", OptionMapping::getAsString);
        long secs = Util.parseDuration(event.getOption("duration", OptionMapping::getAsString));
        int winners = event.getOption("winners", 1, OptionMapping::getAsInt);
        String requirements = event.getOption("requirements", "None", OptionMapping::getAsString);
        if (secs <= 0) {
            event.reply("I couldn't read that duration. Try `1h` or `2d`.").setEphemeral(true).queue();
            return;
        }
        GuildMessageChannel ch = bot.channelOrComplain(event, "giveaways");
        if (ch == null) return;

        long ends = Util.nowSec() + secs;
        MessageEmbed e = giveawayEmbed(prize, winners, ends, requirements, event.getUser().getIdLong(), 0);

        var action = ch.sendMessageEmbeds(e).setComponents(ActionRow.of(enterButton())).setAllowedMentions(PINGS);
        String role = Messages.role(bot, "giveaways_ping");
        if (!role.isEmpty()) action = action.setContent(role);
        action.queue(msg -> {
            bot.db.update("INSERT INTO giveaways VALUES (?, ?, ?, ?, ?, ?, ?, 0)",
                    msg.getIdLong(), ch.getIdLong(), prize, winners, ends, event.getUser().getIdLong(), requirements);
            event.reply("Giveaway started: " + msg.getJumpUrl()).setEphemeral(true).queue();
        });
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().equals("giveaway:enter")) return;
        long msgId = event.getMessageIdLong();
        long userId = event.getUser().getIdLong();
        Map<String, Object> g = bot.db.one("SELECT * FROM giveaways WHERE message_id = ?", msgId);
        if (g == null || Database.num(g, "ended") == 1) {
            event.reply("This giveaway has ended.").setEphemeral(true).queue();
            return;
        }
        String text;
        if (bot.db.one("SELECT 1 FROM giveaway_entries WHERE message_id = ? AND user_id = ?", msgId, userId) != null) {
            bot.db.update("DELETE FROM giveaway_entries WHERE message_id = ? AND user_id = ?", msgId, userId);
            text = "You left the giveaway.";
        } else {
            bot.db.update("INSERT INTO giveaway_entries VALUES (?, ?)", msgId, userId);
            text = "🎉 You're entered! Good luck.";
        }
        long count = Database.num(bot.db.one("SELECT COUNT(*) AS n FROM giveaway_entries WHERE message_id = ?", msgId), "n");
        MessageEmbed updated = giveawayEmbed(Database.str(g, "prize"), (int) Database.num(g, "winners"),
                Database.num(g, "ends_at"), Database.str(g, "requirements"), Database.num(g, "host_id"), count);
        event.editMessageEmbeds(updated).queue(hook -> hook.sendMessage(text).setEphemeral(true).queue());
    }

    private List<Long> pickWinners(long messageId, int n) {
        List<Long> ids = new ArrayList<>();
        for (Map<String, Object> r : bot.db.all("SELECT user_id FROM giveaway_entries WHERE message_id = ?", messageId)) {
            ids.add(Database.num(r, "user_id"));
        }
        Collections.shuffle(ids);
        return ids.subList(0, Math.min(n, ids.size()));
    }

    private String mentions(List<Long> ids) {
        return String.join(", ", ids.stream().map(id -> "<@" + id + ">").toList());
    }

    private MessageEmbed giveawayEmbed(String prize, int winners, long ends, String requirements, long hostId, long entries) {
        return Messages.base(bot, "Giveaway", "**" + prize + "**\nPress **Enter** below to join!")
                .addField("Ends", Util.ts(ends, 'R'), true)
                .addField("Winners", String.valueOf(winners), true)
                .addField("Entries", String.valueOf(entries), true)
                .addField("Requirements", Util.or(requirements, "None"), true)
                .addField("Hosted by", "<@" + hostId + ">", true)
                .build();
    }

    private MessageEmbed congrats(List<Long> winners, String prize, boolean reroll) {
        return Messages.embed(bot, reroll ? "Giveaway Reroll" : "Giveaway Winner",
                "Congratulations " + mentions(winners) + "!\n"
                + "You won **" + prize + "**.\n\n"
                + "Open a ticket in " + Messages.ch(bot, "support_tickets") + " within 24 hours to claim it.");
    }

    /** Runs on the worker thread. */
    private void endGiveaway(Map<String, Object> g) {
        long msgId = Database.num(g, "message_id");
        bot.db.update("UPDATE giveaways SET ended = 1 WHERE message_id = ?", msgId);
        GuildMessageChannel ch = bot.jda.getChannelById(GuildMessageChannel.class, Database.num(g, "channel_id"));
        if (ch == null) return;
        String prize = Database.str(g, "prize");
        List<Long> winners = pickWinners(msgId, (int) Database.num(g, "winners"));
        try {
            Message msg = ch.retrieveMessageById(msgId).complete();
            long entries = Database.num(bot.db.one("SELECT COUNT(*) AS n FROM giveaway_entries WHERE message_id = ?", msgId), "n");
            MessageEmbed e = Messages.base(bot, "Giveaway Ended", "**" + prize + "**")
                    .addField("Winner(s)", winners.isEmpty() ? "Nobody entered" : mentions(winners), true)
                    .addField("Entries", String.valueOf(entries), true)
                    .addField("Hosted by", "<@" + Database.num(g, "host_id") + ">", true)
                    .build();
            msg.editMessageEmbeds(e).setComponents(ActionRow.of(enterButton().asDisabled())).complete();
        } catch (Exception ignored) {
            // message was deleted; still announce below
        }
        if (winners.isEmpty()) {
            ch.sendMessageEmbeds(Messages.embed(bot, "Giveaway Ended",
                    "Nobody entered the **" + prize + "** giveaway, so there's no winner.")).complete();
        } else {
            ch.sendMessage(mentions(winners)).setEmbeds(congrats(winners, prize, false)).setAllowedMentions(PINGS).complete();
        }
    }

    private Map<String, Object> giveawayFromOption(SlashCommandInteractionEvent event) {
        String raw = event.getOption("message_id", OptionMapping::getAsString).trim();
        return raw.matches("\\d+") ? bot.db.one("SELECT * FROM giveaways WHERE message_id = ?", Long.parseLong(raw)) : null;
    }

    private void giveawayEnd(SlashCommandInteractionEvent event) {
        Map<String, Object> g = giveawayFromOption(event);
        if (g == null || Database.num(g, "ended") == 1) {
            event.reply("No running giveaway with that ID.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> {
            try {
                endGiveaway(g);
                event.getHook().sendMessage("Giveaway ended.").queue();
            } catch (Exception e) {
                log.error("Failed to end giveaway", e);
                event.getHook().sendMessage("Something went wrong ending that giveaway. Check the bot's console.").queue();
            }
        });
    }

    private void giveawayReroll(SlashCommandInteractionEvent event) {
        Map<String, Object> g = giveawayFromOption(event);
        if (g == null) {
            event.reply("No giveaway with that ID.").setEphemeral(true).queue();
            return;
        }
        List<Long> winners = pickWinners(Database.num(g, "message_id"), event.getOption("winners", 1, OptionMapping::getAsInt));
        if (winners.isEmpty()) {
            event.reply("Nobody entered that giveaway.").setEphemeral(true).queue();
            return;
        }
        GuildMessageChannel ch = bot.jda.getChannelById(GuildMessageChannel.class, Database.num(g, "channel_id"));
        if (ch != null) ch.sendMessage(mentions(winners)).setEmbeds(congrats(winners, Database.str(g, "prize"), true))
                .setAllowedMentions(PINGS).queue();
        event.reply("Rerolled.").setEphemeral(true).queue();
    }

    // ==================================================================== leaderboard

    /**
     * Edits (or posts) the single leaderboard message in #leaderboards. Blocks, so call it off JDA's
     * event thread (the web API thread or the worker). Returns false if the channel isn't set.
     */
    public boolean updateLeaderboard(String title, List<Map.Entry<String, Long>> entries) {
        GuildMessageChannel ch = bot.channel("leaderboards");
        if (ch == null) return false;
        List<Map.Entry<String, Long>> sorted = entries.stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed()).limit(10).toList();
        String[] medals = {"🥇", "🥈", "🥉"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            var en = sorted.get(i);
            String pts = String.format("%,d", en.getValue());
            if (i < 3) sb.append(medals[i]).append(" **").append(en.getKey()).append("**  ").append(pts).append('\n');
            else sb.append("**#").append(i + 1).append("** ").append(en.getKey()).append("  ").append(pts).append('\n');
        }
        if (sorted.isEmpty()) sb.append("No factions yet.\n");
        MessageEmbed e = Messages.base(bot, title + " Leaderboard", sb.toString())
                .addField("Updated", Util.ts(Util.nowSec(), 'R'), true)
                .build();

        Map<String, Object> data = new HashMap<>();
        data.put("title", title);
        data.put("entries", sorted.stream().map(en -> List.of(en.getKey(), en.getValue())).toList());
        bot.db.set("leaderboard:data", GSON.toJson(data));

        String msgId = bot.db.get("leaderboard:message");
        if (msgId != null) {
            try {
                ch.editMessageEmbedsById(msgId, e).complete();
                return true;
            } catch (Exception ignored) {
                // message was deleted; post a new one
            }
        }
        Message msg = ch.sendMessageEmbeds(e).complete();
        bot.db.set("leaderboard:message", msg.getId());
        return true;
    }

    private void leaderboardSet(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        String title = event.getOption("title", "Faction", OptionMapping::getAsString);
        List<Map.Entry<String, Long>> parsed = new ArrayList<>();
        for (String part : event.getOption("entries", OptionMapping::getAsString).split(",")) {
            int colon = part.lastIndexOf(':');
            if (colon < 0) continue;
            try {
                parsed.add(Map.entry(part.substring(0, colon).trim(),
                        Long.parseLong(part.substring(colon + 1).trim().replace(",", ""))));
            } catch (NumberFormatException ex) {
                event.reply("`" + part.trim() + "` isn't `Name:points`.").setEphemeral(true).queue();
                return;
            }
        }
        if (parsed.isEmpty()) {
            event.reply("Use the format `Raiders:1200, Vipers:950`.").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();
        worker.execute(() -> {
            boolean ok = updateLeaderboard(title, parsed);
            event.getHook().sendMessage(ok ? "Leaderboard updated." : "The leaderboards channel isn't set in config.json.").queue();
        });
    }

    private void leaderboardShow(SlashCommandInteractionEvent event) {
        String json = bot.db.get("leaderboard:data");
        if (json == null) {
            event.reply("No leaderboard yet.").setEphemeral(true).queue();
            return;
        }
        Map<?, ?> data = GSON.fromJson(json, Map.class);
        StringBuilder sb = new StringBuilder();
        int i = 1;
        for (Object o : (List<?>) data.get("entries")) {
            List<?> en = (List<?>) o;
            sb.append("**#").append(i++).append("** ").append(en.get(0)).append("  ")
              .append(String.format("%,d", ((Number) en.get(1)).longValue())).append('\n');
        }
        event.replyEmbeds(Messages.embed(bot, data.get("title") + " Leaderboard", sb.toString()))
                .setEphemeral(true).queue();
    }

    // ==================================================================== f-top strikes

    private long activeStrikes(String faction) {
        return Database.num(bot.db.one(
                "SELECT COUNT(*) AS n FROM strikes WHERE lower(faction) = lower(?) AND active = 1", faction), "n");
    }

    private void strikeAdd(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        GuildMessageChannel ch = bot.channelOrComplain(event, "ftop_strikes");
        if (ch == null) return;
        String faction = event.getOption("faction", OptionMapping::getAsString);
        String reason = event.getOption("reason", OptionMapping::getAsString);
        long points = event.getOption("points", 0L, OptionMapping::getAsLong);

        long id = bot.db.update("INSERT INTO strikes (faction, reason, points, staff_id, created_at) VALUES (?, ?, ?, ?, ?)",
                faction, reason, points, event.getUser().getIdLong(), Util.nowSec());
        long n = activeStrikes(faction);
        int max = bot.cfg.maxStrikes;
        String desc = "**" + faction + "** has received an F-Top strike.\n"
                + "Think this is wrong? Appeal in " + Messages.ch(bot, "support_tickets") + " within 24 hours.";
        if (n >= max) desc += "\n\n**" + faction + "** has reached " + max + " strikes and is removed from F-Top for this map.";
        MessageEmbed e = Messages.base(bot, "F-Top Strike", desc)
                .addField("Strike", n + "/" + max, true)
                .addField("Points Removed", String.format("%,d", points), true)
                .addField("Strike ID", "#" + id, true)
                .addField("Reason", reason, false)
                .addField("Issued by", event.getUser().getAsMention(), true)
                .build();
        ch.sendMessageEmbeds(e).queue();
        event.reply("Strike #" + id + " issued.").setEphemeral(true).queue();
    }

    private void strikeRemove(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        long id = event.getOption("strike_id", OptionMapping::getAsLong);
        Map<String, Object> row = bot.db.one("SELECT * FROM strikes WHERE id = ? AND active = 1", id);
        if (row == null) {
            event.reply("No active strike with that ID.").setEphemeral(true).queue();
            return;
        }
        bot.db.update("UPDATE strikes SET active = 0 WHERE id = ?", id);
        String faction = Database.str(row, "faction");
        GuildMessageChannel ch = bot.channel("ftop_strikes");
        if (ch != null) {
            ch.sendMessageEmbeds(Messages.base(bot, "Strike Removed", "Strike **#" + id + "** on **" + faction + "** was removed.")
                    .addField("Strikes now", activeStrikes(faction) + "/" + bot.cfg.maxStrikes, true)
                    .addField("Removed by", event.getUser().getAsMention(), true)
                    .build()).queue();
        }
        event.reply("Strike removed.").setEphemeral(true).queue();
    }

    private void strikeList(SlashCommandInteractionEvent event) {
        String faction = event.getOption("faction", OptionMapping::getAsString);
        List<Map<String, Object>> rows = bot.db.all(
                "SELECT * FROM strikes WHERE lower(faction) = lower(?) AND active = 1 ORDER BY id", faction);
        if (rows.isEmpty()) {
            event.reply("**" + faction + "** has no active strikes.").setEphemeral(true).queue();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> r : rows) {
            sb.append("**#").append(Database.num(r, "id")).append("** ").append(Database.str(r, "reason"))
              .append(" (−").append(String.format("%,d", Database.num(r, "points"))).append(" pts, ")
              .append(Util.ts(Database.num(r, "created_at"), 'd')).append(")\n");
        }
        event.replyEmbeds(Messages.base(bot, faction + " Strikes", sb.toString())
                .addField("Active", rows.size() + "/" + bot.cfg.maxStrikes, true)
                .build()).setEphemeral(true).queue();
    }

    private void strikeReset(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        bot.db.update("UPDATE strikes SET active = 0 WHERE active = 1");
        event.reply("All strikes cleared for the new map.").setEphemeral(true).queue();
    }

    // ==================================================================== timers

    /** Ends giveaways and polls when their time is up. Checks every 30 seconds. */
    public void startTimers() {
        worker.scheduleAtFixedRate(() -> {
            long now = Util.nowSec();
            for (Map<String, Object> g : bot.db.all("SELECT * FROM giveaways WHERE ended = 0 AND ends_at <= ?", now)) {
                try { endGiveaway(g); } catch (Exception e) { log.error("Failed to end giveaway", e); }
            }
            for (Map<String, Object> p : bot.db.all("SELECT * FROM polls WHERE ended = 0 AND ends_at <= ?", now)) {
                try { endPoll(p); } catch (Exception e) { log.error("Failed to end poll", e); }
            }
        }, 10, 30, TimeUnit.SECONDS);
    }
}

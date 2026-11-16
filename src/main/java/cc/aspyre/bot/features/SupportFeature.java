package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Database;
import cc.aspyre.bot.Messages;
import cc.aspyre.bot.Util;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;
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
import net.dv8tion.jda.api.utils.FileUpload;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** SUPPORT: ticket buttons + private ticket channels, bug reports and suggestions. */
public class SupportFeature extends ListenerAdapter implements Feature {
    private static final Map<String, String> BUG_STATUS = new LinkedHashMap<>();
    private static final Map<String, String> SUGGESTION_STATUS = new LinkedHashMap<>();
    static {
        BUG_STATUS.put("open", "🟡 Open");
        BUG_STATUS.put("investigating", "🔵 Investigating");
        BUG_STATUS.put("fixed", "🟢 Fixed");
        BUG_STATUS.put("wontfix", "⚫ Won't fix");
        SUGGESTION_STATUS.put("pending", "⏳ Pending review");
        SUGGESTION_STATUS.put("approved", "✅ Approved");
        SUGGESTION_STATUS.put("implemented", "🚀 Implemented");
        SUGGESTION_STATUS.put("denied", "❌ Denied");
    }
    private static final EnumSet<Permission> TICKET_PERMS = EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND,
            Permission.MESSAGE_HISTORY, Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'");

    private final Bot bot;

    public SupportFeature(Bot bot) {
        this.bot = bot;
        // Columns added for ticket claiming (older databases won't have them yet).
        for (String col : List.of("claimed_by INTEGER", "message_id INTEGER")) {
            try {
                bot.db.update("ALTER TABLE tickets ADD COLUMN " + col);
            } catch (RuntimeException alreadyThere) {
                // column exists
            }
        }
    }

    @Override
    public List<CommandData> commands() {
        OptionData bugStatus = new OptionData(OptionType.STRING, "status", "New status", true);
        BUG_STATUS.forEach((k, v) -> bugStatus.addChoice(v, k));
        OptionData sugStatus = new OptionData(OptionType.STRING, "status", "New status", true);
        SUGGESTION_STATUS.forEach((k, v) -> sugStatus.addChoice(v, k));

        return List.of(
                Commands.slash("ticket", "Ticket tools").addSubcommands(
                        new SubcommandData("close", "Close this ticket"),
                        new SubcommandData("claim", "Staff: claim this ticket"),
                        new SubcommandData("unclaim", "Staff: give up your claim on this ticket"),
                        new SubcommandData("add", "Staff: add someone to this ticket")
                                .addOption(OptionType.USER, "member", "Who to add", true),
                        new SubcommandData("remove", "Staff: remove someone from this ticket")
                                .addOption(OptionType.USER, "member", "Who to remove", true)),
                Commands.slash("bugreport", "Report a bug"),
                Commands.slash("bugstatus", "Staff: change a bug report's status")
                        .addOption(OptionType.INTEGER, "bug_id", "The bug report number", true)
                        .addOptions(bugStatus),
                Commands.slash("suggest", "Suggest something for the server"),
                Commands.slash("suggestion", "Staff: approve, deny or mark a suggestion done")
                        .addOption(OptionType.INTEGER, "suggestion_id", "The suggestion number", true)
                        .addOptions(sugStatus)
                        .addOption(OptionType.STRING, "note", "Optional note shown on the suggestion"));
    }

    // ==================================================================== routing

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "ticket" -> {
                switch (Objects.requireNonNull(event.getSubcommandName())) {
                    case "close" -> closeTicket(event);
                    case "claim" -> claimTicket(event);
                    case "unclaim" -> unclaimTicket(event);
                    case "add" -> ticketAdd(event);
                    case "remove" -> ticketRemove(event);
                    default -> { }
                }
            }
            case "bugreport" -> event.replyModal(bugModal()).queue();
            case "bugstatus" -> bugStatus(event);
            case "suggest" -> event.replyModal(suggestModal()).queue();
            case "suggestion" -> suggestionStatus(event);
            default -> { }
        }
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        String id = event.getComponentId();
        if (id.startsWith("ticket:open:")) openTicket(event, id.substring("ticket:open:".length()));
        else if (id.equals("ticket:close")) closeTicket(event);
        else if (id.equals("ticket:claim")) claimTicket(event);
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        switch (event.getModalId()) {
            case "bug:modal" -> submitBug(event);
            case "suggest:modal" -> submitSuggestion(event);
            default -> { }
        }
    }

    // ==================================================================== tickets

    private void openTicket(ButtonInteractionEvent event, String type) {
        if (!Messages.TICKET_TYPES.containsKey(type)) return;
        Member member = event.getMember();
        Guild guild = event.getGuild();

        // One open ticket per person, like the Fyre bot.
        Map<String, Object> existing = bot.db.one(
                "SELECT channel_id FROM tickets WHERE user_id = ? AND closed = 0", member.getIdLong());
        if (existing != null && guild.getTextChannelById(Database.num(existing, "channel_id")) != null) {
            event.reply("You already have one open: <#" + Database.num(existing, "channel_id") + ">").setEphemeral(true).queue();
            return;
        }
        event.deferReply(true).queue();

        long catId = bot.cfg.ticketCategories.getOrDefault(type, 0L);
        if (catId == 0) catId = bot.cfg.ticketCategories.getOrDefault("general", 0L);
        Category category = catId == 0 ? null : guild.getCategoryById(catId);

        String name = ("ticket-" + member.getUser().getName()).toLowerCase();
        if (name.length() > 90) name = name.substring(0, 90);

        var action = guild.createTextChannel(name, category)
                .setTopic(Messages.TICKET_TYPES.get(type).label() + " ticket for " + member.getUser().getName()
                        + " (" + member.getId() + ")")
                .addPermissionOverride(guild.getPublicRole(), null, EnumSet.of(Permission.VIEW_CHANNEL))
                .addMemberPermissionOverride(member.getIdLong(), TICKET_PERMS, null)
                .addMemberPermissionOverride(guild.getSelfMember().getIdLong(),
                        EnumSet.of(Permission.VIEW_CHANNEL, Permission.MESSAGE_SEND, Permission.MESSAGE_HISTORY,
                                Permission.MESSAGE_ATTACH_FILES, Permission.MESSAGE_EMBED_LINKS, Permission.MANAGE_CHANNEL),
                        null);
        long staff = bot.cfg.role("staff");
        if (staff != 0 && guild.getRoleById(staff) != null) action = action.addRolePermissionOverride(staff, TICKET_PERMS, null);

        action.queue(channel -> {
            bot.db.update("INSERT INTO tickets (channel_id, user_id, type, opened_at, closed) VALUES (?, ?, ?, ?, 0)",
                    channel.getIdLong(), member.getIdLong(), type, Util.nowSec());
            String staffPing = Messages.role(bot, "staff");
            channel.sendMessage(member.getAsMention() + (staffPing.isEmpty() ? "" : " " + staffPing))
                    .setEmbeds(Messages.ticketOpened(bot, member))
                    .setComponents(ticketButtons(false))
                    .setAllowedMentions(EnumSet.of(Message.MentionType.USER, Message.MentionType.ROLE))
                    .queue(msg -> bot.db.update("UPDATE tickets SET message_id = ? WHERE channel_id = ?",
                            msg.getIdLong(), channel.getIdLong()));
            event.getHook().sendMessage("Your ticket is open: " + channel.getAsMention()).queue();
        }, err -> event.getHook().sendMessage("I couldn't create your ticket (missing Manage Channels permission?). Tell staff!").queue());
    }

    private void closeTicket(IReplyCallback event) {
        if (!(event.getChannel() instanceof TextChannel channel)) {
            event.reply("This isn't an open ticket.").setEphemeral(true).queue();
            return;
        }
        Map<String, Object> row = bot.db.one("SELECT * FROM tickets WHERE channel_id = ? AND closed = 0", channel.getIdLong());
        if (row == null) {
            event.reply("This isn't an open ticket.").setEphemeral(true).queue();
            return;
        }
        long ownerId = Database.num(row, "user_id");
        if (event.getUser().getIdLong() != ownerId && !bot.isStaff(event.getMember())) {
            event.reply("Only the ticket owner or staff can close this.").setEphemeral(true).queue();
            return;
        }
        bot.db.update("UPDATE tickets SET closed = 1 WHERE channel_id = ?", channel.getIdLong());
        event.replyEmbeds(Messages.embed(bot, "Ticket Closing", "This ticket will be deleted in 5 seconds.")).queue();
        long claimedBy = Database.num(row, "claimed_by");

        String closer = event.getUser().getAsMention();

        // Newest first from Discord, so reverse it for the transcript.
        channel.getIterableHistory().takeAsync(5000).thenAccept(messages -> {
            List<Message> ordered = new ArrayList<>(messages);
            Collections.reverse(ordered);
            StringBuilder sb = new StringBuilder("Transcript of #" + channel.getName() + " (" + channel.getId() + ")\n\n");
            for (Message m : ordered) {
                sb.append('[').append(m.getTimeCreated().atZoneSameInstant(ZoneOffset.UTC).format(STAMP)).append("] ")
                  .append(m.getAuthor().getName()).append(": ").append(m.getContentDisplay());
                for (MessageEmbed e : m.getEmbeds()) {
                    sb.append(" [embed: ").append(Util.or(e.getTitle(), "")).append(' ')
                      .append(Util.or(e.getDescription(), "")).append(']');
                }
                m.getAttachments().forEach(a -> sb.append(" [file: ").append(a.getUrl()).append(']'));
                sb.append('\n');
            }
            byte[] data = sb.toString().getBytes(StandardCharsets.UTF_8);
            String filename = channel.getName() + ".txt";

            GuildMessageChannel logs = bot.channel("transcripts");
            if (logs != null) {
                logs.sendMessageEmbeds(Messages.base(bot, "Ticket Closed", "#" + channel.getName())
                                .addField("Opened by", "<@" + ownerId + ">", true)
                                .addField("Claimed by", claimedBy == 0 ? "Nobody" : "<@" + claimedBy + ">", true)
                                .addField("Closed by", closer, true)
                                .build())
                        .addFiles(FileUpload.fromData(data, filename)).queue();
            }
            bot.jda.retrieveUserById(ownerId)
                    .flatMap(u -> u.openPrivateChannel())
                    .flatMap(dm -> dm.sendMessageEmbeds(Messages.embed(bot, "Ticket Closed",
                                    "Your ticket **#" + channel.getName() + "** in Aspyre Network was closed.\n"
                                    + "A copy of the conversation is attached."))
                            .addFiles(FileUpload.fromData(data, filename)))
                    .queue(null, err -> {}); // DMs closed
            channel.delete().reason("Ticket closed").queueAfter(5, TimeUnit.SECONDS, null, err -> {});
        });
    }

    /** Claim + Close buttons under a ticket's opening message. */
    private static ActionRow ticketButtons(boolean claimed) {
        Button claim = claimed
                ? Button.secondary("ticket:claim", "Claimed").withEmoji(Emoji.fromUnicode("✋")).asDisabled()
                : Button.primary("ticket:claim", "Claim").withEmoji(Emoji.fromUnicode("✋"));
        return ActionRow.of(claim, Button.danger("ticket:close", "Close"));
    }

    private Map<String, Object> openTicketHere(IReplyCallback event) {
        if (!(event.getChannel() instanceof TextChannel channel)) return null;
        return bot.db.one("SELECT * FROM tickets WHERE channel_id = ? AND closed = 0", channel.getIdLong());
    }

    /** Re-draws the ticket's opening message to show who (if anyone) has claimed it. */
    private void refreshOpening(TextChannel channel, Map<String, Object> row, long claimedBy) {
        long msgId = Database.num(row, "message_id");
        if (msgId == 0) return;
        Member owner = channel.getGuild().getMemberById(Database.num(row, "user_id"));
        var eb = owner != null
                ? new net.dv8tion.jda.api.EmbedBuilder(Messages.ticketOpened(bot, owner))
                : Messages.base(bot, "Ticket", "Thanks for creating a ticket!\nA staff member will be assisting you shortly.");
        if (claimedBy != 0) eb.addField("Claimed by", "<@" + claimedBy + ">", true);
        channel.editMessageEmbedsById(msgId, eb.build()).setComponents(ticketButtons(claimedBy != 0)).queue(null, err -> {});
    }

    private void claimTicket(IReplyCallback event) {
        Map<String, Object> row = openTicketHere(event);
        if (row == null) {
            event.reply("This isn't an open ticket.").setEphemeral(true).queue();
            return;
        }
        if (!bot.isStaff(event.getMember())) {
            event.reply("Only staff can claim tickets.").setEphemeral(true).queue();
            return;
        }
        long current = Database.num(row, "claimed_by");
        if (current != 0) {
            event.reply(current == event.getUser().getIdLong() ? "You've already claimed this ticket."
                    : "This ticket is already claimed by <@" + current + ">.").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = (TextChannel) event.getChannel();
        bot.db.update("UPDATE tickets SET claimed_by = ? WHERE channel_id = ?", event.getUser().getIdLong(), channel.getIdLong());
        refreshOpening(channel, row, event.getUser().getIdLong());
        event.replyEmbeds(Messages.embed(bot, "Ticket Claimed",
                event.getUser().getAsMention() + " will be handling this ticket.")).queue();
    }

    private void unclaimTicket(SlashCommandInteractionEvent event) {
        Map<String, Object> row = openTicketHere(event);
        if (row == null) {
            event.reply("This isn't an open ticket.").setEphemeral(true).queue();
            return;
        }
        long current = Database.num(row, "claimed_by");
        if (current == 0) {
            event.reply("Nobody has claimed this ticket.").setEphemeral(true).queue();
            return;
        }
        boolean admin = event.getMember() != null && event.getMember().hasPermission(Permission.MANAGE_SERVER);
        if (current != event.getUser().getIdLong() && !admin) {
            event.reply("Only <@" + current + "> or an admin can unclaim this ticket.").setEphemeral(true).queue();
            return;
        }
        TextChannel channel = (TextChannel) event.getChannel();
        bot.db.update("UPDATE tickets SET claimed_by = NULL WHERE channel_id = ?", channel.getIdLong());
        refreshOpening(channel, row, 0);
        event.replyEmbeds(Messages.embed(bot, "Ticket Unclaimed",
                "This ticket is open for any staff member to claim again.")).queue();
    }

    private void ticketAdd(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        Member target = event.getOption("member", OptionMapping::getAsMember);
        if (!(event.getChannel() instanceof TextChannel channel)
                || bot.db.one("SELECT 1 FROM tickets WHERE channel_id = ? AND closed = 0", channel.getIdLong()) == null) {
            event.reply("This isn't an open ticket.").setEphemeral(true).queue();
            return;
        }
        if (target == null) {
            event.reply("That person isn't in the server.").setEphemeral(true).queue();
            return;
        }
        channel.upsertPermissionOverride(target).grant(TICKET_PERMS).queue();
        event.replyEmbeds(Messages.embed(bot, "Member Added", target.getAsMention() + " was added to this ticket.")).queue();
    }

    private void ticketRemove(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        Member target = event.getOption("member", OptionMapping::getAsMember);
        if (!(event.getChannel() instanceof TextChannel channel) || target == null) {
            event.reply("Use this inside a ticket, on someone in the server.").setEphemeral(true).queue();
            return;
        }
        var override = channel.getPermissionOverride(target);
        if (override != null) override.delete().queue();
        event.replyEmbeds(Messages.embed(bot, "Member Removed", target.getAsMention() + " was removed from this ticket.")).queue();
    }

    // ==================================================================== bug reports

    private Modal bugModal() {
        return Modal.create("bug:modal", "Report a bug")
                .addActionRow(TextInput.create("where", "Where did it happen?", TextInputStyle.SHORT)
                        .setPlaceholder("Spawn, KOTH, /f home, Discord...").setMaxLength(100).build())
                .addActionRow(TextInput.create("what", "What happened?", TextInputStyle.PARAGRAPH).setMaxLength(1000).build())
                .addActionRow(TextInput.create("steps", "Steps to make it happen again", TextInputStyle.PARAGRAPH)
                        .setRequired(false).setMaxLength(1000).build())
                .build();
    }

    private MessageEmbed bugEmbed(Map<String, Object> r) {
        String status = Database.str(r, "status");
        return Messages.base(bot, "Bug Report #" + Database.num(r, "id"), Database.str(r, "description"))
                .addField("Where", Database.str(r, "location"), true)
                .addField("Status", BUG_STATUS.getOrDefault(status, status), true)
                .addField("Reported by", "<@" + Database.num(r, "user_id") + ">", true)
                .addField("Steps to reproduce", Database.str(r, "steps"), false)
                .build();
    }

    private void submitBug(ModalInteractionEvent event) {
        GuildMessageChannel ch = bot.channelOrComplain(event, "bug_reports");
        if (ch == null) return;
        String steps = event.getValue("steps") == null ? "" : event.getValue("steps").getAsString();
        long id = bot.db.update("INSERT INTO bugs (channel_id, user_id, location, description, steps) VALUES (?, ?, ?, ?, ?)",
                ch.getIdLong(), event.getUser().getIdLong(), event.getValue("where").getAsString(),
                event.getValue("what").getAsString(), Util.or(steps, "Not given"));
        ch.sendMessageEmbeds(bugEmbed(bot.db.one("SELECT * FROM bugs WHERE id = ?", id))).queue(msg -> {
            bot.db.update("UPDATE bugs SET message_id = ? WHERE id = ?", msg.getIdLong(), id);
            event.reply("Thanks! Your report is #" + id + ": " + msg.getJumpUrl()).setEphemeral(true).queue();
        });
    }

    private void bugStatus(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        long id = event.getOption("bug_id", OptionMapping::getAsLong);
        String status = event.getOption("status", OptionMapping::getAsString);
        Map<String, Object> row = bot.db.one("SELECT * FROM bugs WHERE id = ?", id);
        if (row == null) {
            event.reply("No bug report with that number.").setEphemeral(true).queue();
            return;
        }
        bot.db.update("UPDATE bugs SET status = ? WHERE id = ?", status, id);
        editPost(row, bugEmbed(bot.db.one("SELECT * FROM bugs WHERE id = ?", id)));
        event.reply("Bug #" + id + " is now **" + BUG_STATUS.get(status) + "**.").setEphemeral(true).queue();
    }

    // ==================================================================== suggestions

    private Modal suggestModal() {
        return Modal.create("suggest:modal", "Make a suggestion")
                .addActionRow(TextInput.create("text", "Your idea", TextInputStyle.PARAGRAPH).setMaxLength(1500).build())
                .build();
    }

    private MessageEmbed suggestionEmbed(Map<String, Object> r) {
        String status = Database.str(r, "status");
        var eb = Messages.base(bot, "Suggestion #" + Database.num(r, "id"), Database.str(r, "text"))
                .addField("From", "<@" + Database.num(r, "user_id") + ">", true)
                .addField("Status", SUGGESTION_STATUS.getOrDefault(status, status), true)
                .addField("Vote", "👍  /  👎", true);
        String note = Database.str(r, "note");
        if (note != null && !note.isBlank()) eb.addField("Staff note", note, false);
        return eb.build();
    }

    private void submitSuggestion(ModalInteractionEvent event) {
        GuildMessageChannel ch = bot.channelOrComplain(event, "suggestions");
        if (ch == null) return;
        long id = bot.db.update("INSERT INTO suggestions (channel_id, user_id, text) VALUES (?, ?, ?)",
                ch.getIdLong(), event.getUser().getIdLong(), event.getValue("text").getAsString());
        ch.sendMessageEmbeds(suggestionEmbed(bot.db.one("SELECT * FROM suggestions WHERE id = ?", id))).queue(msg -> {
            msg.addReaction(Emoji.fromUnicode("👍")).queue();
            msg.addReaction(Emoji.fromUnicode("👎")).queue();
            bot.db.update("UPDATE suggestions SET message_id = ? WHERE id = ?", msg.getIdLong(), id);
            event.reply("Posted as suggestion #" + id + ": " + msg.getJumpUrl()).setEphemeral(true).queue();
        });
    }

    private void suggestionStatus(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        long id = event.getOption("suggestion_id", OptionMapping::getAsLong);
        String status = event.getOption("status", OptionMapping::getAsString);
        String note = event.getOption("note", OptionMapping::getAsString);
        Map<String, Object> row = bot.db.one("SELECT * FROM suggestions WHERE id = ?", id);
        if (row == null) {
            event.reply("No suggestion with that number.").setEphemeral(true).queue();
            return;
        }
        bot.db.update("UPDATE suggestions SET status = ?, note = ? WHERE id = ?", status, note, id);
        editPost(row, suggestionEmbed(bot.db.one("SELECT * FROM suggestions WHERE id = ?", id)));
        event.reply("Suggestion #" + id + " is now **" + SUGGESTION_STATUS.get(status) + "**.").setEphemeral(true).queue();
    }

    private void editPost(Map<String, Object> row, MessageEmbed embed) {
        GuildMessageChannel ch = bot.jda.getChannelById(GuildMessageChannel.class, Database.num(row, "channel_id"));
        long msgId = Database.num(row, "message_id");
        if (ch != null && msgId != 0) ch.editMessageEmbedsById(msgId, embed).queue(null, err -> {});
    }
}

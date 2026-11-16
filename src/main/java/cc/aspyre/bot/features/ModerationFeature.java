package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Database;
import cc.aspyre.bot.Util;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.StringSelectInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.commands.build.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Ported from the Fyre bot: /lock, /unlock, /ban, /schedule and the alert-roles select menu. */
public class ModerationFeature extends ListenerAdapter implements Feature {
    private static final Logger log = LoggerFactory.getLogger(ModerationFeature.class);
    private final Bot bot;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();

    public ModerationFeature(Bot bot) {
        this.bot = bot;
        bot.db.update("CREATE TABLE IF NOT EXISTS schedules (id INTEGER PRIMARY KEY AUTOINCREMENT, channel_id INTEGER, "
                + "message TEXT, post_at INTEGER, author_id INTEGER, posted INTEGER DEFAULT 0)");
    }

    @Override
    public List<CommandData> commands() {
        return List.of(
                Commands.slash("lock", "Staff: stop everyone from talking in this channel"),
                Commands.slash("unlock", "Staff: let everyone talk in this channel again"),
                Commands.slash("ban", "Ban a member from the server")
                        .addOption(OptionType.USER, "member", "Who to ban", true)
                        .addOption(OptionType.STRING, "reason", "Why (shown in the audit log)")
                        .addOptions(new OptionData(OptionType.INTEGER, "delete_days", "Delete their messages from the last N days (0–7)")
                                .setRequiredRange(0, 7))
                        .setDefaultPermissions(DefaultMemberPermissions.enabledFor(Permission.BAN_MEMBERS)),
                Commands.slash("schedule", "Staff: post a message in a channel later")
                        .addOptions(new OptionData(OptionType.CHANNEL, "channel", "Where to post it", true)
                                .setChannelTypes(ChannelType.TEXT, ChannelType.NEWS))
                        .addOption(OptionType.STRING, "in", "How long from now, e.g. 30m, 2h, 1d", true)
                        .addOption(OptionType.STRING, "message", "What to post", true));
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "lock" -> lock(event, true);
            case "unlock" -> lock(event, false);
            case "ban" -> ban(event);
            case "schedule" -> schedule(event);
            default -> { }
        }
    }

    // ==================================================================== lock / unlock

    private void lock(SlashCommandInteractionEvent event, boolean locked) {
        if (!bot.requireStaff(event)) return;
        GuildChannel channel = event.getGuildChannel();
        if (!(channel instanceof IPermissionContainer container)) {
            event.reply("This channel can't be locked.").setEphemeral(true).queue();
            return;
        }
        var override = container.upsertPermissionOverride(event.getGuild().getPublicRole());
        (locked ? override.deny(Permission.MESSAGE_SEND) : override.clear(Permission.MESSAGE_SEND))
                .reason((locked ? "Locked" : "Unlocked") + " by " + event.getUser().getName())
                .queue(ok -> event.reply(locked ? "🔒 This channel has been locked." : "🔓 This channel has been unlocked.").queue(),
                        err -> event.reply("I couldn't change this channel's permissions.").setEphemeral(true).queue());
    }

    // ==================================================================== ban

    private void ban(SlashCommandInteractionEvent event) {
        Member mod = event.getMember();
        if (mod == null || !mod.hasPermission(Permission.BAN_MEMBERS)) {
            event.reply("You need the Ban Members permission to do that.").setEphemeral(true).queue();
            return;
        }
        User target = event.getOption("member", OptionMapping::getAsUser);
        Member targetMember = event.getOption("member", OptionMapping::getAsMember);
        String reason = event.getOption("reason", "No reason given", OptionMapping::getAsString);
        int days = event.getOption("delete_days", 0, OptionMapping::getAsInt);

        if (targetMember != null && (!mod.canInteract(targetMember) || !event.getGuild().getSelfMember().canInteract(targetMember))) {
            event.reply("You can't ban someone with a role equal to or higher than yours (or the bot's).").setEphemeral(true).queue();
            return;
        }
        event.getGuild().ban(target, days, TimeUnit.DAYS)
                .reason(reason + " (by " + event.getUser().getName() + ")")
                .queue(ok -> event.reply("🔨 **" + target.getName() + "** was banned. Reason: " + reason).queue(),
                        err -> event.reply("I couldn't ban that member. Check my Ban Members permission and role position.")
                                .setEphemeral(true).queue());
    }

    // ==================================================================== schedule

    private void schedule(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        GuildChannel channel = event.getOption("channel", OptionMapping::getAsChannel);
        long secs = Util.parseDuration(event.getOption("in", OptionMapping::getAsString));
        String message = event.getOption("message", OptionMapping::getAsString);
        if (!(channel instanceof GuildMessageChannel)) {
            event.reply("Pick a text channel.").setEphemeral(true).queue();
            return;
        }
        if (secs <= 0) {
            event.reply("I couldn't read that time. Try `30m`, `2h` or `1d`.").setEphemeral(true).queue();
            return;
        }
        long postAt = Util.nowSec() + secs;
        bot.db.update("INSERT INTO schedules (channel_id, message, post_at, author_id) VALUES (?, ?, ?, ?)",
                channel.getIdLong(), message, postAt, event.getUser().getIdLong());
        event.reply("Your message has been scheduled for " + Util.ts(postAt, 'F') + " in " + channel.getAsMention() + ".")
                .setEphemeral(true).queue();
    }

    /** Posts scheduled messages when they're due. Checks every 30 seconds. */
    public void startTimers() {
        worker.scheduleAtFixedRate(() -> {
            for (Map<String, Object> row : bot.db.all("SELECT * FROM schedules WHERE posted = 0 AND post_at <= ?", Util.nowSec())) {
                try {
                    bot.db.update("UPDATE schedules SET posted = 1 WHERE id = ?", Database.num(row, "id"));
                    GuildMessageChannel ch = bot.jda.getChannelById(GuildMessageChannel.class, Database.num(row, "channel_id"));
                    if (ch != null) ch.sendMessage(Database.str(row, "message")).complete();
                } catch (Exception e) {
                    log.error("Failed to post scheduled message", e);
                }
            }
        }, 15, 30, TimeUnit.SECONDS);
    }

    // ==================================================================== alert roles

    @Override
    public void onStringSelectInteraction(StringSelectInteractionEvent event) {
        if (!event.getComponentId().equals("roles:select") || event.getGuild() == null) return;
        Set<String> picked = new HashSet<>(event.getValues());
        List<Role> add = new ArrayList<>();
        List<Role> remove = new ArrayList<>();
        List<String> now = new ArrayList<>();

        for (Map.Entry<String, Long> e : bot.cfg.notificationRoles.entrySet()) {
            Role role = event.getGuild().getRoleById(e.getValue());
            if (role == null) continue;
            boolean has = event.getMember().getRoles().contains(role);
            if (picked.contains(String.valueOf(e.getValue()))) {
                now.add(e.getKey());
                if (!has) add.add(role);
            } else if (has) {
                remove.add(role);
            }
        }
        event.getGuild().modifyMemberRoles(event.getMember(), add, remove).reason("Alert roles").queue(
                ok -> event.reply(now.isEmpty() ? "You won't receive any alerts." : "You'll now receive: **" + String.join(", ", now) + "**")
                        .setEphemeral(true).queue(),
                err -> event.reply("I couldn't update your roles. Ask staff to move my role higher.").setEphemeral(true).queue());
    }
}

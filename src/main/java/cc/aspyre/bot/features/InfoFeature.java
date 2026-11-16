package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Messages;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateBoostTimeEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;

import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Automatic things: welcome + default role on join, boost thank-yous, rotating status. */
public class InfoFeature extends ListenerAdapter implements Feature {
    private final Bot bot;
    private final ScheduledExecutorService presenceTimer = Executors.newSingleThreadScheduledExecutor();
    private int presenceIndex = 0;

    public InfoFeature(Bot bot) {
        this.bot = bot;
    }

    @Override
    public List<CommandData> commands() {
        return List.of();
    }

    @Override
    public void onGuildMemberJoin(GuildMemberJoinEvent event) {
        if (event.getGuild().getIdLong() != bot.cfg.guildId) return;

        Role defaultRole = event.getGuild().getRoleById(bot.cfg.role("default"));
        if (defaultRole != null) {
            event.getGuild().addRoleToMember(event.getMember(), defaultRole).reason("Default role").queue(null, err -> {});
        }

        GuildMessageChannel ch = bot.channel("welcome");
        if (ch != null) {
            ch.sendMessage(Messages.welcome(bot, event.getMember()))
                    .setAllowedMentions(EnumSet.of(Message.MentionType.USER))
                    .queue();
        }
    }

    @Override
    public void onGuildMemberUpdateBoostTime(GuildMemberUpdateBoostTimeEvent event) {
        if (event.getOldTimeBoosted() == null && event.getNewTimeBoosted() != null) {
            GuildMessageChannel ch = bot.channel("boosts");
            if (ch != null) ch.sendMessageEmbeds(Messages.boostThanks(bot, event.getMember())).queue();
        }
    }

    /** Cycles the bot's "Playing ..." status through config presences every 15 seconds. */
    public void startPresence() {
        if (bot.cfg.presences == null || bot.cfg.presences.isEmpty()) return;
        presenceTimer.scheduleAtFixedRate(() -> {
            try {
                String text = bot.cfg.presences.get(presenceIndex++ % bot.cfg.presences.size());
                Guild g = bot.guild();
                if (g != null) text = text.replace("{members}", String.valueOf(g.getMemberCount()));
                bot.jda.getPresence().setActivity(Activity.playing(text));
            } catch (Exception ignored) {
                // never let a status update kill the timer
            }
        }, 0, 15, TimeUnit.SECONDS);
    }
}

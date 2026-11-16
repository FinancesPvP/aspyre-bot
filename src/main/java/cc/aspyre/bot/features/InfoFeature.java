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

    /**
     * Posts the changelog bundled in this jar (resources/changelog.txt) to the changelog channel,
     * once per version. Runs at startup, so every deploy announces what changed.
     */
    public void postChangelog() {
        try (var in = InfoFeature.class.getResourceAsStream("/changelog.txt")) {
            if (in == null) return;
            List<String> lines = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8).lines().toList();
            String version = lines.stream().filter(l -> l.startsWith("version:")).map(l -> l.substring(8).trim())
                    .findFirst().orElse(null);
            if (version == null || version.equals(bot.db.get("changelog:posted"))) return;

            // "- " lines are what changed, "+ " lines are ideas for the next update.
            StringBuilder changes = new StringBuilder();
            StringBuilder ideas = new StringBuilder();
            for (String l : lines) {
                if (l.startsWith("- ")) changes.append("**•** ").append(l.substring(2).trim()).append('\n');
                else if (l.startsWith("+ ")) ideas.append("**•** ").append(l.substring(2).trim()).append('\n');
            }
            GuildMessageChannel ch = bot.channel("changelog");
            if (ch == null || changes.length() == 0) return;
            var eb = Messages.base(bot, "Update " + version, changes.toString());
            if (ideas.length() > 0) eb.addField("Ideas for the next update", ideas.toString(), false);
            ch.sendMessageEmbeds(eb.build()).complete();
            bot.db.set("changelog:posted", version);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(InfoFeature.class).warn("Couldn't post changelog", e);
        }
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

package cc.aspyre.bot;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.middleman.GuildMessageChannel;
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback;

import java.awt.Color;

/** Shared state every feature can reach: config, database, JDA and small helpers. */
public class Bot {
    public final Config cfg;
    public final Database db;
    public JDA jda;

    public Bot(Config cfg, Database db) {
        this.cfg = cfg;
        this.db = db;
    }

    public Guild guild() {
        return jda.getGuildById(cfg.guildId);
    }

    /** A channel from config.json by key, e.g. channel("announcements"). Null if not set. */
    public GuildMessageChannel channel(String key) {
        long id = cfg.channel(key);
        return id == 0 ? null : jda.getChannelById(GuildMessageChannel.class, id);
    }

    public Color color() {
        return Color.decode(cfg.embedColor);
    }

    public boolean isStaff(Member member) {
        if (member == null) return false;
        if (member.hasPermission(Permission.MANAGE_SERVER)) return true;
        long staff = cfg.role("staff");
        return member.getRoles().stream().anyMatch(r -> r.getIdLong() == staff);
    }

    /** Replies "Only staff can use this command." and returns false if the user isn't staff. */
    public boolean requireStaff(IReplyCallback event) {
        if (isStaff(event.getMember())) return true;
        event.reply("Only staff can use this command.").setEphemeral(true).queue();
        return false;
    }

    /** Replies with an error and returns null if a config channel is missing. */
    public GuildMessageChannel channelOrComplain(IReplyCallback event, String key) {
        GuildMessageChannel ch = channel(key);
        if (ch == null) {
            event.reply("The `" + key + "` channel isn't set in config.json.").setEphemeral(true).queue();
        }
        return ch;
    }
}

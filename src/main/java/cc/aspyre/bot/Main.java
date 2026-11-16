package cc.aspyre.bot;

import cc.aspyre.bot.features.*;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.ChunkingFilter;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class Main {
    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {
        Config cfg = Config.load(Path.of("config.json"));
        Bot bot = new Bot(cfg, new Database("data.db"));

        PanelFeature panels = new PanelFeature(bot);
        InfoFeature info = new InfoFeature(bot);
        SyncFeature sync = new SyncFeature(bot);
        NewsFeature news = new NewsFeature(bot);
        SupportFeature support = new SupportFeature(bot);
        ChatFeature chat = new ChatFeature(bot);
        ModerationFeature moderation = new ModerationFeature(bot);
        List<Feature> features = List.of(panels, info, sync, news, support, chat, moderation);

        JDA jda = JDABuilder.createDefault(cfg.token)
                // Both are "privileged" intents: turn them on in the Developer Portal → Bot.
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.MESSAGE_CONTENT)
                .setMemberCachePolicy(MemberCachePolicy.ALL)   // needed for boost events
                .setChunkingFilter(ChunkingFilter.ALL)
                .setActivity(Activity.playing(cfg.serverIp))
                .addEventListeners(panels, info, sync, news, support, chat, moderation)
                .build()
                .awaitReady();
        bot.jda = jda;

        Guild guild = bot.guild();
        if (guild == null) {
            log.error("The bot isn't in the server with guild_id {}. Check config.json and the invite.", cfg.guildId);
            jda.shutdown();
            return;
        }

        // Register every slash command on your server (they show up instantly).
        List<CommandData> commands = new ArrayList<>();
        features.forEach(f -> commands.addAll(f.commands()));
        guild.updateCommands().addCommands(commands).queue(
                ok -> log.info("Registered {} slash commands", ok.size()),
                err -> log.error("Failed to register commands", err));

        news.startTimers();
        moderation.startTimers();
        support.startTimers();
        info.startPresence();
        info.postChangelog();
        if (cfg.syncApi.enabled) {
            new SyncApi(bot, sync, news).start();
        }
        log.info("Aspyre Network bot is online as {}", jda.getSelfUser().getName());
    }
}

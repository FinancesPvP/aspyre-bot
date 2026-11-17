package cc.aspyre.bot;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.interactions.components.ActionRow;
import net.dv8tion.jda.api.interactions.components.buttons.Button;
import net.dv8tion.jda.api.interactions.components.selections.SelectOption;
import net.dv8tion.jda.api.interactions.components.selections.StringSelectMenu;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageCreateData;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Every message the bot posts, in one place so the wording is easy to change.
 *
 * Style (matches the Fyre/HCTeams bot): a title, the text in a quote block, a timestamp, and a
 * "Powered by ParaPvP" footer with the bot's avatar. Panels also show the ParaPvP banner.
 */
public final class Messages {
    private Messages() {}

    public static final String POWERED_BY = "Powered by ParaPvP";
    private static final String BANNER_FILE = "banner.png";
    private static byte[] bannerBytes;

    // ------------------------------------------------------------------ helpers

    /** The standard embed: title, quoted text, colour, footer and timestamp. */
    public static EmbedBuilder base(Bot bot, String title, String description) {
        EmbedBuilder b = new EmbedBuilder()
                .setTitle(title)
                .setDescription(description == null || description.isBlank() ? null : ">>> " + description)
                .setColor(bot.color())
                .setTimestamp(Instant.now());
        String icon = bot.jda == null ? null : bot.jda.getSelfUser().getEffectiveAvatarUrl();
        b.setFooter(POWERED_BY, icon);
        return b;
    }

    public static MessageEmbed embed(Bot bot, String title, String description) {
        return base(bot, title, description).build();
    }

    /** A panel embed: the standard embed plus the ParaPvP banner along the bottom. */
    public static MessageEmbed panel(Bot bot, String title, String description) {
        return base(bot, title, description).setImage("attachment://" + BANNER_FILE).build();
    }

    /** The banner image file that panel messages must attach. */
    public static FileUpload bannerFile() {
        if (bannerBytes == null) {
            try (InputStream in = Messages.class.getResourceAsStream("/brand/" + BANNER_FILE)) {
                bannerBytes = in == null ? new byte[0] : in.readAllBytes();
            } catch (IOException e) {
                bannerBytes = new byte[0];
            }
        }
        return FileUpload.fromData(bannerBytes, BANNER_FILE);
    }

    /** Clickable #channel mention for a config key, or a plain #name if it isn't set. */
    public static String ch(Bot bot, String key) {
        long id = bot.cfg.channel(key);
        return id != 0 ? "<#" + id + ">" : "#" + key.replace('_', '-');
    }

    /** Role mention for a config key, or "" if it isn't set. */
    public static String role(Bot bot, String key) {
        long id = bot.cfg.role(key);
        return id != 0 ? "<@&" + id + ">" : "";
    }

    // ------------------------------------------------------------------ INFORMATION

    public static MessageEmbed syncPanel(Bot bot) {
        return panel(bot, "Sync",
                "Link your Minecraft account to get your **Synced** role, your rank and your IGN as your nickname.\n\n"
                + "**#1**: Join `" + bot.cfg.serverIp + "`\n"
                + "**#2**: Type `/sync` in-game and copy your code\n"
                + "**#3**: Type `!sync <code>` in this channel, or press **Sync** below\n\n"
                + "Codes expire after 5 minutes and work once.");
    }

    /** Discord rules, a numbered list like CavePvP's. */
    public static MessageEmbed rules(Bot bot) {
        String[] r = {
                "Do not be toxic.",
                "Do not post inappropriate pictures, links or videos.",
                "No NSFW posts.",
                "Do not sell anything in this Discord.",
                "Do not self-promote, unless it's a video made on ParaPvP posted in " + ch(bot, "media") + ".",
                "Do not tag staff members.",
                "Do not advertise or mention other Minecraft servers.",
                "Do not spam in any channel.",
                "Only use bot commands in " + ch(bot, "bot_commands") + ".",
                "Do not be racist, sexist or homophobic in any channel.",
                "No doxing or DDoS threats, even as a joke.",
                "Do not post anybody's personal information.",
                "Listen to and respect staff members.",
                "Do not cause unneeded drama.",
                "Do not get other people to break the rules.",
                "Do not impersonate staff members or content creators.",
                "Do not DM members inviting them to other Discords or promoting another Minecraft server.",
                "Being blacklisted in-game will result in a ban from the Discord.",
                "Only post ParaPvP recruitment messages in " + ch(bot, "recruitment") + ".",
                "Use common sense.",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) sb.append(i + 1).append(". ").append(r[i]).append('\n');
        return base(bot, "Rules", null).setDescription(sb.toString()).build();
    }

    /** In-game HCF rules, posted under the Discord rules with the banner. */
    public static MessageEmbed gameRules(Bot bot) {
        Config c = bot.cfg;
        String[] r = {
                "No hacked clients or X-ray.",
                "No auto-clicking or macros of any kind.",
                "Do not abuse bugs or dupes. Report them in a ticket.",
                "Do not evade bans or punishments on alts.",
                "Do not build lag machines.",
                "Do not trade items, accounts or factions for real money.",
                "Do not glitch KOTH, Conquest or EOTW caps or abuse safe zones.",
                "Combat logging is at your own risk.",
                "Appeal punishments in " + ch(bot, "support_tickets") + ", not in chat.",
        };
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < r.length; i++) sb.append(i + 1).append(". ").append(r[i]).append('\n');
        return base(bot, "In-Game Rules", null).setDescription(sb.toString())
                .addField("Outside of ParaPvP",
                        "We only handle what happens on ParaPvP: in-game and in this Discord. We can't act on anything "
                        + "that happens anywhere else. Turn off DMs from server members if you're worried, and if someone "
                        + "threatens you outside of ParaPvP, contact the authorities.", false)
                .setImage("attachment://" + BANNER_FILE).build();
    }

    /**
     * Welcome line posted when someone joins. The custom emoji named in welcome_emoji is looked up on the
     * server each time, so it always uses the real emoji (left off if the server does not have it).
     */
    public static String welcome(Bot bot, Member member) {
        String name = bot.cfg.welcomeEmoji;
        var found = member.getGuild().getEmojisByName(name, true);
        String emoji = found.isEmpty() ? "" : " " + found.get(0).getAsMention();
        return "Welcome to the official **ParaPvP** Discord, " + member.getAsMention() + "!" + emoji;
    }

    public static MessageEmbed boosts(Bot bot) {
        String boosterRole = role(bot, "booster");
        return panel(bot, "Boosts",
                "Boosting unlocks perks for the whole server and gets you:\n"
                + "**#1**: The " + (boosterRole.isEmpty() ? "**Booster**" : boosterRole) + " role\n"
                + "**#2**: Access to booster-only giveaways\n"
                + (bot.cfg.boosterPerk.isBlank() ? "" : "**#3**: " + bot.cfg.boosterPerk + "\n")
                + "\nThanks to everyone keeping us boosted!");
    }

    public static MessageEmbed boostThanks(Bot bot, Member member) {
        return base(bot, "New Boost",
                member.getAsMention() + " just boosted the server!\n"
                + "We're now at **" + member.getGuild().getBoostCount() + "** boosts. Thank you!")
                .setThumbnail(member.getEffectiveAvatarUrl())
                .build();
    }

    public static MessageEmbed information(Bot bot) {
        StringBuilder sb = new StringBuilder();
        sb.append("**IP**  `").append(bot.cfg.serverIp).append("`\n");
        if (!bot.cfg.storeLink.isBlank()) sb.append("**Store**  ").append(bot.cfg.storeLink).append('\n');
        if (!bot.cfg.websiteLink.isBlank()) sb.append("**Website**  ").append(bot.cfg.websiteLink).append('\n');
        if (!bot.cfg.twitterLink.isBlank()) sb.append("**Twitter**  ").append(bot.cfg.twitterLink).append('\n');
        return panel(bot, "ParaPvP", sb.toString());
    }

    /** Store / Website / Twitter link buttons under the info panel (only the ones that are set). */
    public static List<Button> infoButtons(Bot bot) {
        List<Button> buttons = new ArrayList<>();
        if (isLink(bot.cfg.storeLink)) buttons.add(Button.link(bot.cfg.storeLink, "Store").withEmoji(Emoji.fromUnicode("🪙")));
        if (isLink(bot.cfg.websiteLink)) buttons.add(Button.link(bot.cfg.websiteLink, "Website").withEmoji(Emoji.fromUnicode("🌐")));
        if (isLink(bot.cfg.twitterLink)) buttons.add(Button.link(bot.cfg.twitterLink, "Twitter").withEmoji(Emoji.fromUnicode("🐦")));
        return buttons;
    }

    private static boolean isLink(String s) {
        return s != null && (s.startsWith("https://") || s.startsWith("http://"));
    }

    // ------------------------------------------------------------------ SUPPORT

    /**
     * label: shown in the dropdown and ticket; prefix: channel name prefix; prompt: what the member should send first.
     */
    public record TicketType(String label, String emoji, String description, String prefix, String prompt) {}

    /** Ticket types in the panel's dropdown, in order. Each can have its own Discord category in ticket_categories. */
    public static final Map<String, TicketType> TICKET_TYPES = new LinkedHashMap<>();
    static {
        TICKET_TYPES.put("general", new TicketType("General Support", "📩", "Questions, help, or anything else",
                "ticket", "Tell us what you need help with and a staff member will be with you shortly."));
        TICKET_TYPES.put("report", new TicketType("Player Report", "🚨", "Report a cheater or rule breaker",
                "report", "Send the player's **IGN**, the **rule** they broke, and any **video or screenshots**."));
        TICKET_TYPES.put("store", new TicketType("Store Support", "💳", "Missing purchases, payments or ranks",
                "store", "Send your **IGN**, the **package** you bought, and your **transaction ID** from the receipt."));
        TICKET_TYPES.put("appeal", new TicketType("Punishment Appeal", "⚖️", "Appeal a ban, mute or blacklist",
                "appeal", "Send your **IGN**, the **punishment** you received, and **why** it should be removed."));
        TICKET_TYPES.put("apply", new TicketType("Staff Application", "📝", "Apply to join the ParaPvP staff team",
                "apply", "Send your **IGN**, **age**, **timezone**, and any **past staff experience**."));
    }

    public static MessageEmbed ticketPanel(Bot bot) {
        return new net.dv8tion.jda.api.EmbedBuilder(panel(bot, "Need Help?",
                "Select the ticket type below and our team will assist you as soon as possible.\n\n"
                + "⚠️ *Abuse of the ticket system may result in restrictions.*"))
                .setAuthor("Support Center", null, bot.jda.getSelfUser().getEffectiveAvatarUrl())
                .build();
    }

    public static StringSelectMenu ticketMenu() {
        List<SelectOption> options = new ArrayList<>();
        TICKET_TYPES.forEach((key, t) -> options.add(SelectOption.of(t.label(), key)
                .withDescription(t.description()).withEmoji(Emoji.fromUnicode(t.emoji()))));
        return StringSelectMenu.create("ticket:select").setPlaceholder("Make a selection").addOptions(options).build();
    }

    public static MessageEmbed ticketOpened(Bot bot, Member member) {
        return ticketOpened(bot, member, "general");
    }

    public static MessageEmbed ticketOpened(Bot bot, Member member, String type) {
        TicketType t = TICKET_TYPES.getOrDefault(type, TICKET_TYPES.get("general"));
        return new net.dv8tion.jda.api.EmbedBuilder(embed(bot, t.label() + " — " + member.getUser().getName(),
                "Thanks for creating a ticket!\n" + t.prompt()))
                .addField("Type", t.emoji() + " " + t.label(), true)
                .build();
    }

    public static MessageEmbed bugPanel(Bot bot) {
        return panel(bot, "Bug Reports",
                "Found a bug? Use `/bugreport` and fill in the form.\n"
                + "Exploits that give an unfair advantage go in a **ticket**, not here.");
    }

    public static MessageEmbed suggestionPanel(Bot bot) {
        return panel(bot, "Suggestions",
                "Got an idea? Use `/suggest` and the community can vote on it.\n"
                + "Check that your idea hasn't already been suggested.");
    }

    // ------------------------------------------------------------------ ALERT ROLES

    public static MessageEmbed rolesPanel(Bot bot) {
        StringBuilder sb = new StringBuilder("Select the roles you want to receive alerts for.\n");
        bot.cfg.notificationRoles.forEach((name, id) -> sb.append("\n**").append(name).append("**"));
        return panel(bot, "🔊 Alerts", sb.toString());
    }

    public static StringSelectMenu rolesMenu(Bot bot) {
        List<SelectOption> options = new ArrayList<>();
        bot.cfg.notificationRoles.forEach((name, id) -> options.add(SelectOption.of(name, String.valueOf(id))));
        return StringSelectMenu.create("roles:select")
                .setPlaceholder("Select Alerts")
                .addOptions(options)
                .setMinValues(0)
                .setMaxValues(Math.max(1, options.size()))
                .build();
    }

    // ------------------------------------------------------------------ CHAT

    public static MessageEmbed generalPin(Bot bot) {
        return embed(bot, "General",
                "Talk about anything, keep it friendly.\n"
                + "**Commands** " + ch(bot, "bot_commands") + "\n"
                + "**Memes** " + ch(bot, "memes") + "\n"
                + "**Help** " + ch(bot, "support_tickets"));
    }

    public static MessageEmbed mediaPin(Bot bot) {
        return embed(bot, "Media",
                "Share clips, screenshots, montages and art.\n"
                + "No chatting here, use threads to reply. Keep it SFW and credit other people's work.");
    }

    public static MessageEmbed recruitPanel(Bot bot) {
        return panel(bot, "Recruitment",
                "Looking for a team or players? Use `/recruit` and fill in the form.\n"
                + "One post per 24 hours. No poaching in DMs.");
    }

    public static MessageEmbed botCommandsPin(Bot bot) {
        return embed(bot, "Bot Commands",
                "Run all bot commands here.\n"
                + "**#1**: `/stats`\n**#2**: `/leaderboard show`\n**#3**: `/suggest`\n**#4**: `/bugreport`\n\n"
                + "Type `/` to see everything the bot can do.");
    }

    public static MessageEmbed memesPin(Bot bot) {
        return embed(bot, "Memes",
                "Post your best ones.\n"
                + "No NSFW, no memes targeting real people, no reposting the same meme all day.");
    }

    // ------------------------------------------------------------------ panel commands

    /**
     * One slash command per panel. Staff running it posts the panel in the current channel;
     * anyone else (or staff with private:true) sees a private copy.
     */
    public record Panel(String command, String description, Function<Bot, MessageCreateData> build) {}

    public static final List<Panel> PANELS = new ArrayList<>();
    static {
        PANELS.add(new Panel("rules", "Show the server rules", b ->
                new MessageCreateBuilder().setEmbeds(rules(b), gameRules(b)).addFiles(bannerFile()).build()));
        PANELS.add(new Panel("info", "Show the server IP, store and links", b -> {
            MessageCreateBuilder m = new MessageCreateBuilder().setEmbeds(information(b)).addFiles(bannerFile());
            List<Button> buttons = infoButtons(b);
            if (!buttons.isEmpty()) m.setComponents(ActionRow.of(buttons));
            return m.build();
        }));
        PANELS.add(new Panel("boosts", "Show the booster perks", b -> withBanner(boosts(b))));
        PANELS.add(new Panel("sync-panel", "Show the account sync panel with its Sync button", b ->
                new MessageCreateBuilder().setEmbeds(syncPanel(b)).addFiles(bannerFile())
                        .setComponents(ActionRow.of(Button.secondary("sync:open", "Sync").withEmoji(Emoji.fromUnicode("✨"))))
                        .build()));
        PANELS.add(new Panel("ticket-panel", "Show the Create a Ticket panel", b ->
                new MessageCreateBuilder().setEmbeds(ticketPanel(b)).addFiles(bannerFile())
                        .setComponents(ActionRow.of(ticketMenu()))
                        .build()));
        PANELS.add(new Panel("roles-panel", "Show the alert roles select menu", b -> {
            MessageCreateBuilder m = new MessageCreateBuilder().setEmbeds(rolesPanel(b)).addFiles(bannerFile());
            if (!b.cfg.notificationRoles.isEmpty()) m.setComponents(ActionRow.of(rolesMenu(b)));
            return m.build();
        }));
        PANELS.add(new Panel("bug-panel", "Show how to report bugs", b -> withBanner(bugPanel(b))));
        PANELS.add(new Panel("suggestion-panel", "Show how to make suggestions", b -> withBanner(suggestionPanel(b))));
        PANELS.add(new Panel("recruit-panel", "Show how recruitment posts work", b -> withBanner(recruitPanel(b))));
        PANELS.add(new Panel("general-pin", "Show the general chat guidelines", b -> plain(generalPin(b))));
        PANELS.add(new Panel("media-pin", "Show the media channel guidelines", b -> plain(mediaPin(b))));
        PANELS.add(new Panel("commands-pin", "Show the useful bot commands", b -> plain(botCommandsPin(b))));
        PANELS.add(new Panel("memes-pin", "Show the memes channel guidelines", b -> plain(memesPin(b))));
    }

    private static MessageCreateData withBanner(MessageEmbed e) {
        return new MessageCreateBuilder().setEmbeds(e).addFiles(bannerFile()).build();
    }

    private static MessageCreateData plain(MessageEmbed e) {
        return new MessageCreateBuilder().setEmbeds(e).build();
    }
}

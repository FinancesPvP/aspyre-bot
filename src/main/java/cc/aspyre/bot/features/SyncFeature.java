package cc.aspyre.bot.features;

import cc.aspyre.bot.Bot;
import cc.aspyre.bot.Database;
import cc.aspyre.bot.Util;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.events.interaction.ModalInteractionEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent;
import net.dv8tion.jda.api.events.interaction.component.ButtonInteractionEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.interactions.commands.OptionMapping;
import net.dv8tion.jda.api.interactions.commands.OptionType;
import net.dv8tion.jda.api.interactions.commands.build.CommandData;
import net.dv8tion.jda.api.interactions.commands.build.Commands;
import net.dv8tion.jda.api.interactions.components.text.TextInput;
import net.dv8tion.jda.api.interactions.components.text.TextInputStyle;
import net.dv8tion.jda.api.interactions.modals.Modal;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * ✨sync: links Minecraft accounts to Discord.
 * 1. Player runs /sync in-game; your plugin sends the code to the bot's web API (see SyncApi).
 * 2. Player runs /sync code:123456 on Discord, or presses the Sync button.
 * 3. Bot gives the Synced role + rank role and sets their nickname to their IGN.
 */
public class SyncFeature extends ListenerAdapter implements Feature {
    private static final long CODE_TTL_SEC = 300;
    private final Bot bot;

    public SyncFeature(Bot bot) {
        this.bot = bot;
    }

    @Override
    public List<CommandData> commands() {
        return List.of(
                Commands.slash("sync", "Link your Minecraft account with the code from /sync in-game")
                        .addOption(OptionType.STRING, "code", "The 6-digit code shown in chat", true),
                Commands.slash("unsync", "Staff: unlink a member's Minecraft account")
                        .addOption(OptionType.USER, "member", "Who to unlink", true));
    }

    /** Called by the web API when the Minecraft plugin creates a code. */
    public void addCode(String code, String uuid, String ign, String rank) {
        bot.db.update("DELETE FROM sync_codes WHERE expires_at < ? OR uuid = ?", Util.nowSec(), uuid);
        bot.db.update("INSERT OR REPLACE INTO sync_codes VALUES (?, ?, ?, ?, ?)",
                code, uuid, ign, rank, Util.nowSec() + CODE_TTL_SEC);
    }

    @Override
    public void onSlashCommandInteraction(SlashCommandInteractionEvent event) {
        switch (event.getName()) {
            case "sync" -> event.reply(link(event.getMember(), event.getOption("code", OptionMapping::getAsString)))
                    .setEphemeral(true).queue();
            case "unsync" -> unsync(event);
            default -> { }
        }
    }

    /** People whose !sync is being processed, so double posts are ignored. */
    private final Set<Long> performing = ConcurrentHashMap.newKeySet();

    /**
     * Fyre-style syncing: type "!sync <code>" in the sync channel. Every message there is deleted
     * and the bot answers with a reply that removes itself after 5 seconds.
     */
    @Override
    public void onMessageReceived(MessageReceivedEvent event) {
        if (!event.isFromGuild() || event.getAuthor().isBot()) return;
        if (event.getChannel().getIdLong() != bot.cfg.channel("sync")) return;

        event.getMessage().delete().queue(null, err -> {});
        String mention = event.getAuthor().getAsMention();
        String raw = event.getMessage().getContentRaw().trim();

        if (!raw.toLowerCase().startsWith("!sync ")) {
            temporary(event, "Usage: `!sync <code>` " + mention);
            return;
        }
        if (!performing.add(event.getAuthor().getIdLong())) return;
        try {
            String result = link(event.getMember(), raw.substring(6).trim());
            temporary(event, result + " " + mention);
        } finally {
            performing.remove(event.getAuthor().getIdLong());
        }
    }

    private static void temporary(MessageReceivedEvent event, String text) {
        event.getChannel().sendMessage(text)
                .queue(m -> m.delete().queueAfter(5, TimeUnit.SECONDS, null, err -> {}));
    }

    @Override
    public void onButtonInteraction(ButtonInteractionEvent event) {
        if (!event.getComponentId().equals("sync:open")) return;
        TextInput code = TextInput.create("code", "6-digit code from /sync in-game", TextInputStyle.SHORT)
                .setMinLength(6).setMaxLength(6).setPlaceholder("123456").build();
        event.replyModal(Modal.create("sync:modal", "Sync your Minecraft account").addActionRow(code).build()).queue();
    }

    @Override
    public void onModalInteraction(ModalInteractionEvent event) {
        if (!event.getModalId().equals("sync:modal")) return;
        event.reply(link(event.getMember(), event.getValue("code").getAsString())).setEphemeral(true).queue();
    }

    /** Tries to link the member. Returns the message to show them. */
    private String link(Member member, String code) {
        code = code.trim();
        if (!code.matches("\\d{6}")) {
            return "❌ That doesn't look right. Codes are 6 digits, like `482913`.";
        }
        Map<String, Object> row = bot.db.one("SELECT * FROM sync_codes WHERE code = ?", code);
        if (row != null) bot.db.update("DELETE FROM sync_codes WHERE code = ?", code); // codes work once
        if (row == null || Database.num(row, "expires_at") < Util.nowSec()) {
            return "❌ That code is wrong or expired. Run `/sync` in-game to get a new one.";
        }
        String uuid = Database.str(row, "uuid");
        String ign = Database.str(row, "ign");
        String rank = Database.str(row, "rank");

        if (bot.db.one("SELECT 1 FROM links WHERE uuid = ? AND discord_id != ?", uuid, member.getIdLong()) != null) {
            return "❌ That Minecraft account is already linked to another Discord account. Open a ticket if that's wrong.";
        }
        Map<String, Object> mine = bot.db.one("SELECT uuid FROM links WHERE discord_id = ?", member.getIdLong());
        if (mine != null && !uuid.equals(Database.str(mine, "uuid"))) {
            return "❌ Your Discord is already linked to a different Minecraft account. Ask staff to unlink it first.";
        }

        bot.db.update("INSERT OR REPLACE INTO links VALUES (?, ?, ?, ?, ?)",
                member.getIdLong(), uuid, ign, rank, Util.nowSec());
        applyRoles(member, ign, rank);
        return "✅ Synced to **" + ign + "**" + (rank == null || rank.isBlank() ? "" : " with rank **" + rank + "**") + "!";
    }

    private void applyRoles(Member member, String ign, String rank) {
        Guild guild = member.getGuild();
        Role synced = guild.getRoleById(bot.cfg.role("synced"));
        if (synced != null) guild.addRoleToMember(member, synced).queue(null, err -> {});

        for (Map.Entry<String, Long> e : bot.cfg.rankRoles.entrySet()) {
            Role r = e.getValue() == null || e.getValue() == 0 ? null : guild.getRoleById(e.getValue());
            if (r == null) continue;
            if (rank != null && e.getKey().equalsIgnoreCase(rank)) {
                guild.addRoleToMember(member, r).queue(null, err -> {});
            } else if (member.getRoles().contains(r)) {
                guild.removeRoleFromMember(member, r).queue(null, err -> {});
            }
        }
        // Fails quietly for the server owner or anyone above the bot's role.
        guild.modifyNickname(member, ign.length() > 32 ? ign.substring(0, 32) : ign).queue(null, err -> {});
    }

    private void unsync(SlashCommandInteractionEvent event) {
        if (!bot.requireStaff(event)) return;
        Member target = event.getOption("member", OptionMapping::getAsMember);
        if (target == null) {
            event.reply("That person isn't in the server.").setEphemeral(true).queue();
            return;
        }
        long removed = bot.db.update("DELETE FROM links WHERE discord_id = ?", target.getIdLong());
        if (removed == 0) {
            event.reply(target.getAsMention() + " isn't synced.").setEphemeral(true).queue();
            return;
        }
        Role synced = event.getGuild().getRoleById(bot.cfg.role("synced"));
        if (synced != null) event.getGuild().removeRoleFromMember(target, synced).queue(null, err -> {});
        event.reply("Unlinked " + target.getAsMention() + ".").setEphemeral(true).queue();
    }
}

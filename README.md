# Aspyre Network Bot (JDA)

A Java Discord bot for the Aspyre Network HCF server, built on JDA 5. Each panel and embed is its own slash command, so nothing is posted until you ask for it. The bot also runs tickets, giveaways, polls, payouts, the faction leaderboard, f-top strikes, bug reports, suggestions and recruitment posts, and syncs Minecraft accounts.

Requires Java 17 or newer on the machine that runs it.

## 1. Create the bot

1. Go to https://discord.com/developers/applications, click **New Application** and name it.
2. Open **Bot**:
   - Click **Reset Token** and copy the token. Keep it secret.
   - Turn on **Server Members Intent**. Welcome and boost messages need it.
   - Turn on **Message Content Intent**. Ticket transcripts need it.
3. Open **OAuth2 → URL Generator**:
   - Under scopes, tick `bot` and `applications.commands`.
   - Under bot permissions, tick: Manage Channels, Manage Roles, Manage Nicknames, View Channels, Send Messages, Embed Links, Attach Files, Manage Messages, Read Message History and Add Reactions.
   - Open the generated link and add the bot to Aspyre Network.
4. In **Server Settings → Roles**, drag the bot's role **above** the Synced, rank and staff roles. Otherwise it can't give out those roles.

## 2. Configure it

1. Copy `config.example.json` to `config.json` and put it next to the jar.
2. In Discord, turn on **Settings → Advanced → Developer Mode**. You can now right-click any channel, category or role and choose **Copy ID**.
3. Fill in `config.json`:

| Key | What to put |
| --- | --- |
| `token` | The bot token from step 1 |
| `guild_id` | Your server's ID (already filled in for Aspyre Network) |
| `server_ip`, `store_link`, `website_link` | Shown in the sync panel, `/info` and the rules |
| `max_cps`, `faction_size`, `ally_limit` | Numbers used in the rules |
| `max_strikes` | Strikes before a faction is removed from F-Top (default 3) |
| `channels.*` | The ID of each channel. `update_stage` is the UPDATE stage channel. |
| `ticket_categories.*` | The category each ticket type opens in. A type left at 0 uses `general`. For example, put your `—general tickets—` category in `general` and `—store tickets—` in `store`. |
| `roles.staff` | The role that can use staff commands and see tickets |
| `roles.synced` | The role given after `/sync` |
| `roles.*_ping` | The roles pinged for announcements, polls and giveaways. Leave at 0 for no ping. |
| `rank_roles` | In-game rank name → Discord role ID, for example `"VIP": 123...` |
| `sync_api.secret` | A long random password that your Minecraft plugin will send |

## 3. Run it

`AspyreBot.jar` is already built and has every library inside it. Upload it to your host with `config.json` in the same folder and run:

```bash
java -jar AspyreBot.jar
```

When it's working you'll see `Registered 28 slash commands` and `Aspyre Network bot is online`.

Run the jar from the folder that holds `config.json`. The bot creates `data.db` there to save everything, so back that file up.

Keep the bot running 24/7 on a VPS or bot host. Giveaways and polls end on a 30-second timer, so they only close while the bot is online.

## Panel commands

These commands show the embeds and pinned messages. Go to the channel where you want the panel and run its command:

- **Staff:** it posts in that channel and pins it. Add `pin:false` to skip pinning, or `private:true` to preview it only for yourself.
- **Everyone else:** they get a private copy, so members can run `/rules` or `/info` any time.

| Command | Shows | Usually goes in |
| --- | --- | --- |
| `/rules` | HCF rules | #rules |
| `/info` | IP, store, website, where to go | #information |
| `/boosts` | Booster perks | #boosts |
| `/sync-panel` | Sync instructions with a **Sync** button | #sync |
| `/ticket-panel` | Ticket panel with 5 buttons | #support-tickets |
| `/bug-panel` | How to report bugs | #bug-reports |
| `/suggestion-panel` | How suggestions work | #suggestions |
| `/recruit-panel` | How recruitment posts work | #recruitment |
| `/general-pin`, `/media-pin`, `/commands-pin`, `/memes-pin` | Channel guidelines | each chat channel |

The buttons keep working after the bot restarts. To change a panel's wording, edit `Messages.java`, rebuild, delete the old message and run the command again.

Welcome messages and boost thank-yous are the only things the bot posts by itself, when someone joins or boosts.

## Other commands

**Everyone**

| Command | What it does |
| --- | --- |
| `/sync code:` | Links your Minecraft account (or press the Sync button) |
| `/stats [member]` | Shows a member's IGN, rank and sync date |
| `/leaderboard show` | Shows the current faction leaderboard |
| `/strike list faction:` | Shows a faction's active strikes |
| `/bugreport` | Opens the bug report form |
| `/suggest` | Opens the suggestion form (posts with 👍/👎) |
| `/recruit kind:` | Posts an LFT/LFP card (once per 24 hours) |
| `/ticket close` | Closes your ticket (same as the 🔒 button) |

**Staff**

| Command | What it does |
| --- | --- |
| `/announce [ping]` | Opens a form and posts to #announcements. Auto-publishes if it's an announcement channel. |
| `/liveupdate topic:` | Pings that a live update is starting on the UPDATE stage |
| `/poll question: options:A \| B \| C duration:1d` | Reaction poll with 2–5 options. Results post when it closes. |
| `/payout event: winners: reward:` | Logs a payout in #payouts |
| `/giveaway start prize: duration: winners: requirements:` | Giveaway with an Enter button |
| `/giveaway end message_id:` / `/giveaway reroll message_id:` | Ends a giveaway early or picks new winners |
| `/leaderboard set entries:Aspyre:1200, Raiders:950 title:Map 3` | Sets the leaderboard by hand |
| `/strike add faction: reason: points:` | Issues an f-top strike |
| `/strike remove strike_id:` / `/strike reset` | Removes one strike / clears all strikes for a new map |
| `/bugstatus bug_id: status:` | Sets a bug to open, investigating, fixed or won't fix |
| `/suggestion suggestion_id: status: note:` | Marks a suggestion pending, approved, implemented or denied |
| `/ticket add member:` / `/ticket remove member:` | Adds or removes someone from a ticket |
| `/unsync member:` | Unlinks a member's Minecraft account |

"Staff" means anyone with the `roles.staff` role or the Manage Server permission.

When a ticket is closed, the bot sends a `.txt` transcript to your transcripts channel and DMs a copy to the person who opened it.

## Connecting the Minecraft server

Discord can't see your Minecraft server, so the server has to tell the bot about sync codes (and, optionally, leaderboard points). The bot runs a small web API on `sync_api.port` (default 8095) for this. That port must be reachable from your Minecraft server. If both run on the same machine, use `http://127.0.0.1:8095`.

Every request needs the header `Authorization: Bearer <your sync_api.secret>`.

**Sync codes.** When a player runs `/sync` in-game, your plugin makes a random 6-digit code, shows it in chat and sends:

```
POST /sync
{"code": "482913", "uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "ign": "Notch", "rank": "VIP"}
```

Codes expire after 5 minutes and work once. `rank` must match a name in `rank_roles` to get a rank role.

**Leaderboard (optional).** Send this whenever points change, or every few minutes. The bot edits a single message in #leaderboards.

```
POST /leaderboard
{"title": "Map 3", "entries": [{"name": "Aspyre", "points": 1200}, {"name": "Raiders", "points": 950}]}
```

Test it with curl:

```bash
curl -X POST http://127.0.0.1:8095/sync \
  -H "Authorization: Bearer CHANGE_ME" -H "Content-Type: application/json" \
  -d '{"code":"123456","uuid":"test-uuid","ign":"TestPlayer","rank":"VIP"}'
```

Then run `/sync code:123456` in Discord.

## Rebuilding after code changes

```bash
./gradlew shadowJar           # on Windows: gradlew.bat shadowJar
```

The new jar is written to `build/libs/AspyreBot.jar`.

## Code layout

All source is in `src/main/java/cc/aspyre/bot/`:

- `Main.java` — starts JDA and registers the commands
- `Messages.java` — all the message wording and the list of panel commands. Edit this to change what the bot says.
- `features/PanelFeature.java` — the panel commands
- `features/InfoFeature.java` — welcome and boost messages
- `features/SyncFeature.java` + `SyncApi.java` — account sync and the web API
- `features/NewsFeature.java` — announcements, polls, payouts, giveaways, leaderboard, strikes and live updates
- `features/SupportFeature.java` — tickets, bug reports and suggestions
- `features/ChatFeature.java` — `/recruit` and `/stats`
- `Config.java`, `Database.java`, `Bot.java`, `Util.java` — config loading, SQLite storage and helpers

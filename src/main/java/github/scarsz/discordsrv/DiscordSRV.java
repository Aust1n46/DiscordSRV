package github.scarsz.discordsrv;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import github.scarsz.discordsrv.api.ApiManager;
import github.scarsz.discordsrv.api.events.DiscordGuildMessagePostBroadcastEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePostProcessEvent;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import github.scarsz.discordsrv.hooks.MultiverseCoreHook;
import github.scarsz.discordsrv.hooks.VaultHook;
import github.scarsz.discordsrv.hooks.chat.*;
import github.scarsz.discordsrv.listeners.*;
import github.scarsz.discordsrv.objects.*;
import github.scarsz.discordsrv.objects.threads.ChannelTopicUpdater;
import github.scarsz.discordsrv.objects.threads.ConsoleMessageQueueWorker;
import github.scarsz.discordsrv.util.*;
import lombok.Getter;
import net.dv8tion.jda.core.AccountType;
import net.dv8tion.jda.core.JDA;
import net.dv8tion.jda.core.JDABuilder;
import net.dv8tion.jda.core.OnlineStatus;
import net.dv8tion.jda.core.entities.Guild;
import net.dv8tion.jda.core.entities.Role;
import net.dv8tion.jda.core.entities.TextChannel;
import net.dv8tion.jda.core.entities.User;
import net.dv8tion.jda.core.exceptions.RateLimitedException;
import net.dv8tion.jda.core.utils.SimpleLog;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.configuration.MemorySection;
import org.bukkit.entity.Player;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.plugin.java.JavaPlugin;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.*;

@SuppressWarnings({"Convert2streamapi", "unused", "unchecked", "ResultOfMethodCallIgnored", "WeakerAccess", "ConstantConditions"})
public class DiscordSRV extends JavaPlugin implements Listener {

    public static final ApiManager api = new ApiManager();
    public static final long startTime = System.currentTimeMillis();
    public static boolean updateIsAvailable = false;

    @Getter private Map<String, TextChannel> channels = new LinkedHashMap<>(); // <in-game channel name, discord channel>
    @Getter private TextChannel consoleChannel;
    @Getter private JDA jda;
    @Getter private List<String> randomPhrases = new ArrayList<>();
    @Getter private ChannelTopicUpdater channelTopicUpdater;
    @Getter private ConsoleMessageQueueWorker consoleMessageQueueWorker;
    @Getter private Map<String, String> colors = new HashMap<>();
    @Getter private Gson gson = new GsonBuilder().setPrettyPrinting().create();
    @Getter private Random random = new Random();
    @Getter private Map<String, String> responses = new HashMap<>();
    @Getter private Queue<String> consoleMessageQueue = new LinkedList<>();
    @Getter private List<UUID> unsubscribedPlayers = new ArrayList<>();
    @Getter private AccountLinkManager accountLinkManager;
    @Getter private File configFile = new File(getDataFolder(), "config.yml"), channelsFile = new File(getDataFolder(), "channels.json"), linkedAccountsFile = new File(getDataFolder(), "linkedaccounts.json");
    @Getter private List<String> hookedPlugins = new ArrayList<>();
    @Getter private CancellationDetector<AsyncPlayerChatEvent> cancellationDetector = new CancellationDetector<>(AsyncPlayerChatEvent.class);
    @Getter private GroupSynchronizationManager groupSynchronizationManager = new GroupSynchronizationManager();

    public static DiscordSRV getPlugin() {
        return getPlugin(DiscordSRV.class);
    }
    public Map.Entry<String, TextChannel> getMainChatChannelPair() {
        return channels.size() != 0 ? channels.entrySet().iterator().next() : null;
    }
    public String getMainChatChannel() {
        Map.Entry<String, TextChannel> pair = getMainChatChannelPair();
        return pair != null ? pair.getKey() : "";
    }
    public TextChannel getMainTextChannel() {
        Map.Entry<String, TextChannel> pair = getMainChatChannelPair();
        return pair != null ? pair.getValue() : null;
    }
    public TextChannel getDestinationTextChannelForGameChannelName(String gameChannelName) {
        TextChannel foundChannel = channels.get(gameChannelName);
        if (foundChannel != null) return foundChannel; // found case-sensitive channel

        // no case-sensitive channel found, try case in-sensitive
        for (Map.Entry<String, TextChannel> channelEntry : channels.entrySet())
            if (channelEntry.getKey().equalsIgnoreCase(gameChannelName)) return channelEntry.getValue();

        return null; // no channel found, case-insensitive or not
    }
    public String getDestinationGameChannelNameForTextChannel(TextChannel source) {
        for (Map.Entry<String, TextChannel> channelEntry : channels.entrySet())
            if (channelEntry.getValue().getId().equals(source.getId()))
                return channelEntry.getKey();
        return null;
    }

    // log messages
    public static void info(String message) {
        getPlugin().getLogger().info(message);
    }
    public static void info(Object object) {
        info(object.toString());
    }
    public static void warning(String message) {
        getPlugin().getLogger().warning(message);
    }
    public static void error(String message) {
        getPlugin().getLogger().severe(message);
    }
    public static void debug(String message) {
        // return if plugin is not in debug mode
        if (!getPlugin().getConfig().getBoolean("Debug")) return;

        getPlugin().getLogger().info("[DEBUG] " + message + "\n" + DebugUtil.getStackTrace());
    }

    @Override
    public void onEnable() {
        new Thread(this::init, "DiscordSRV - Initialization").start();
    }

    public void init() {
        // check if the person is trying to use the plugin without updating to ASM 5
        try {
            File specialSourceFile = new File("libraries/net/md-5/SpecialSource/1.7-SNAPSHOT/SpecialSource-1.7-SNAPSHOT.jar");
            if (!specialSourceFile.exists()) specialSourceFile = new File("bin/net/md-5/SpecialSource/1.7-SNAPSHOT/SpecialSource-1.7-SNAPSHOT.jar");
            if (specialSourceFile.exists() && DigestUtils.md5Hex(FileUtils.readFileToByteArray(specialSourceFile)).equalsIgnoreCase("096777a1b6098130d6c925f1c04050a3")) {
                warning("");
                warning("");
                warning("You're attempting to use DiscordSRV on ASM 4. DiscordSRV requires ASM 5 to function.");
                warning("DiscordSRV WILL NOT WORK without ASM 5. Blame your server software's developers for having outdated libraries.");
                warning("");
                warning("Instructions for updating to ASM 5:");
                warning("1. Navigate to the " + specialSourceFile.getParentFile().getPath() + " folder of the server");
                warning("2. Delete the SpecialSource-1.7-SNAPSHOT.jar jar file");
                warning("3. Download SpecialSource v1.7.4 from http://central.maven.org/maven2/net/md-5/SpecialSource/1.7.4/SpecialSource-1.7.4.jar");
                warning("4. Copy the jar file to the " + specialSourceFile.getParentFile().getPath() + " folder of the server you navigated to earlier");
                warning("5. Rename the jar file you just copied to SpecialSource-1.7-SNAPSHOT.jar");
                warning("6. Restart the server");
                warning("");
                warning("");
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        // remove all event listeners from existing jda to prevent having multiple listeners when jda is recreated
        if (jda != null) jda.getRegisteredListeners().forEach(o -> jda.removeEventListener(o));

        // make sure configuration file exist, save default ones if they don't
        if (!configFile.exists()) {
            saveResource("config.yml", false);
            reloadConfig();
        }

        //todo config migration

        // update check
        if (!getConfig().getBoolean("UpdateCheckDisabled")) {
            updateIsAvailable = UpdateUtil.checkForUpdates();
            if (!isEnabled()) return; // don't load other shit if the plugin was disabled by the update checker
        }

        // cool kids club thank yous
        if (!getConfig().getBoolean("CoolKidsClubThankYousDisabled")) {
            String thankYou = HttpUtil.requestHttp("https://github.com/Scarsz/DiscordSRV/raw/randomaccessfiles/coolkidsclub").replace("\n", "");
            if (thankYou.length() > 1) info("Thank you so much to these people for allowing DiscordSRV to grow to what it is: " + thankYou);
        }

        // random phrases for debug handler
        if (!getConfig().getBoolean("RandomPhrasesDisabled"))
            Collections.addAll(randomPhrases, HttpUtil.requestHttp("https://raw.githubusercontent.com/Scarsz/DiscordSRV/randomaccessfiles/randomphrases").split("\n"));

        // set simplelog level to jack shit because we have our own appender; remove timestamps from JDA messages
        if (SimpleLog.LEVEL != SimpleLog.Level.OFF) {
            SimpleLog.LEVEL = SimpleLog.Level.OFF;
            SimpleLog.addListener(new SimpleLog.LogListener() {
                @Override
                public void onLog(SimpleLog simpleLog, SimpleLog.Level level, Object o) {
                    switch (level) {
                        case INFO:
                            info("[JDA] " + o);
                            break;
                        case WARNING:
                            warning("[JDA] " + o);
                            break;
                        case FATAL:
                            error("[JDA] " + o);
                            break;
                    }
                }
                @Override
                public void onError(SimpleLog simpleLog, Throwable throwable) {}
            });
        }

        // shutdown previously existing jda if plugin gets reloaded
        if (jda != null) try { jda.shutdown(false); } catch (Exception e) { e.printStackTrace(); }

        // log in to discord
        try {
            jda = new JDABuilder(AccountType.BOT)
                    .setAudioEnabled(false)
                    .setAutoReconnect(true)
                    .setBulkDeleteSplittingEnabled(false)
                    .setToken(getConfig().getString("BotToken"))
                    .addListener(new DiscordChatListener())
                    .addListener(new DiscordConsoleListener())
                    .addListener(new DiscordDebugListener())
                    .addListener(new DiscordPrivateMessageListener())
                    .buildBlocking();
        } catch (LoginException | RateLimitedException e) {
            error("DiscordSRV failed to connect to Discord. Reason: " + e.getLocalizedMessage());
            return;
        } catch (InterruptedException e) {
            error("This shouldn't have happened under any circumstance. Weird.");
            e.printStackTrace();
            return;
        }

        // game status
        if (!getConfig().getString("DiscordGameStatus").isEmpty())
            DiscordUtil.setGameStatus(getConfig().getString("DiscordGameStatus"));

        // print the things the bot can see
        for (Guild server : jda.getGuilds()) {
            info("Found guild " + server);
            for (TextChannel channel : server.getTextChannels()) info("- text channel " + channel);
            for (Role role : server.getRoles()) info("- role " + role);
        }

        // show warning if bot wasn't in any guilds
        if (jda.getGuilds().size() == 0) {
            DiscordSRV.error("The bot is not a part of any Discord guilds. Follow the installation instructions.");
            return;
        }

        // set console channel
        consoleChannel = StringUtils.isNotBlank(getConfig().getString("DiscordConsoleChannelId")) ? jda.getTextChannelById(getConfig().getString("DiscordConsoleChannelId")) : null;

        // see if console channel exists; if it does, tell user where it's been assigned & add console appender
        if (consoleChannel != null) {
            info("Console forwarding assigned to " + consoleChannel);

            // attach appender to queue console messages
            Logger rootLogger = (Logger) LogManager.getRootLogger();
            rootLogger.addAppender(new ConsoleAppender());

            // start console message queue worker thread
            if (consoleMessageQueueWorker != null ) {
                if (consoleMessageQueueWorker.getState() == Thread.State.NEW) {
                    consoleMessageQueueWorker.start();
                } else {
                    consoleMessageQueueWorker.interrupt();
                    consoleMessageQueueWorker = new ConsoleMessageQueueWorker();
                    consoleMessageQueueWorker.start();
                }
            } else {
                consoleMessageQueueWorker = new ConsoleMessageQueueWorker();
                consoleMessageQueueWorker.start();
            }
        } else {
            info("Console channel ID was blank, not forwarding console output");
        }

        // load channels
        for (Map.Entry<String, Object> channelEntry : ((MemorySection) getConfig().get("Channels")).getValues(true).entrySet())
            channels.put(channelEntry.getKey(), jda.getTextChannelById((String) channelEntry.getValue()));

        // warn if no channels have been linked
        if (getMainTextChannel() == null) warning("No channels have been linked");
        if (getMainTextChannel() == null && consoleChannel == null) error("No channels nor a console channel have been linked. Have you followed the installation instructions?");
        // warn if the console channel is connected to a chat channel
        if (getMainTextChannel() != null && consoleChannel != null && getMainTextChannel().getId().equals(consoleChannel.getId())) warning("The console channel was assigned to a channel that's being used for chat. Did you blindly copy/paste an ID into the channel ID config option?");

        // send server startup message
        DiscordUtil.sendMessage(getMainTextChannel(), getConfig().getString("DiscordChatChannelServerStartupMessage"));

        // start channel topic updater
        if (channelTopicUpdater != null ) {
            if (channelTopicUpdater.getState() == Thread.State.NEW) {
                channelTopicUpdater.start();
            } else {
                channelTopicUpdater.interrupt();
                channelTopicUpdater = new ChannelTopicUpdater();
                channelTopicUpdater.start();
            }
        } else {
            channelTopicUpdater = new ChannelTopicUpdater();
            channelTopicUpdater.start();
        }

        // start lag (tps) monitor
        Bukkit.getServer().getScheduler().scheduleSyncRepeatingTask(this, new Lag(), 100L, 1L);

        // cancellation detector
        if (getConfig().getBoolean("Debug")) {
            getLogger().info("Chat event cancellation detector has been enabled");
            cancellationDetector.addListener((plugin, event) -> info("Plugin " + plugin.toString() + " cancelled " + event.getClass().getSimpleName() + " (author: " + event.getPlayer().getName() + " | message: " + event.getMessage() + ")"));
        }

        // register events
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getPluginManager().registerEvents(new PlayerAchievementsListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerDeathListener(), this);
        Bukkit.getPluginManager().registerEvents(new PlayerJoinLeaveListener(), this);

        // in-game chat events
        if (PluginUtil.checkIfPluginEnabled("herochat") && pluginHookIsEnabled("herochat")) {
            getLogger().info("Enabling Herochat hook");
            getServer().getPluginManager().registerEvents(new HerochatHook(), this);
        } else if (PluginUtil.checkIfPluginEnabled("legendchat") && pluginHookIsEnabled("legendchat")) {
            getLogger().info("Enabling LegendChat hook");
            getServer().getPluginManager().registerEvents(new LegendChatHook(), this);
        } else if (PluginUtil.checkIfPluginEnabled("LunaChat") && pluginHookIsEnabled("lunachat")) {
            getLogger().info("Enabling LunaChat hook");
            getServer().getPluginManager().registerEvents(new LunaChatHook(), this);
        } else if (PluginUtil.checkIfPluginEnabled("Towny") && PluginUtil.checkIfPluginEnabled("TownyChat") && pluginHookIsEnabled("townychat")) {
            getLogger().info("Enabling TownyChat hook");
            getServer().getPluginManager().registerEvents(new TownyChatHook(), this);
        } else if (PluginUtil.checkIfPluginEnabled("venturechat") && pluginHookIsEnabled("venturechat")) {
            getLogger().info("Enabling VentureChat hook");
            getServer().getPluginManager().registerEvents(new VentureChatHook(), this);
        } else {
            getLogger().info("No chat plugin hooks enabled");
            getServer().getPluginManager().registerEvents(new PlayerChatListener(), this);
        }

        // enable metrics
        if (!getConfig().getBoolean("MetricsDisabled"))
            try {
                Metrics metrics = new Metrics(this);
                metrics.start();
            } catch (IOException e) {
                warning("Unable to start metrics. Oh well.");
            }

        // load user-defined colors
        colors.clear();
        for (Map.Entry<String, Object> responseEntry : ((MemorySection) getConfig().get("DiscordChatChannelColorTranslations")).getValues(true).entrySet())
            colors.put(responseEntry.getKey(), (String) responseEntry.getValue());
        info("Colors: " + colors);

        // load canned responses
        responses.clear();
        for (Map.Entry<String, Object> responseEntry : ((MemorySection) getConfig().get("DiscordCannedResponses")).getValues(true).entrySet())
            responses.put(responseEntry.getKey(), (String) responseEntry.getValue());

        // load account links
        accountLinkManager = new AccountLinkManager(linkedAccountsFile);

        // initialize group synchronization manager
        groupSynchronizationManager.init();
    }

    @Override
    public void onDisable() {
        long shutdownStartTime = System.currentTimeMillis();

        // send server shutdown message
        DiscordUtil.sendMessageBlocking(getMainTextChannel(), getConfig().getString("DiscordChatChannelServerShutdownMessage"));

        // set server shutdown topics if enabled
        if (getConfig().getBoolean("ChannelTopicUpdaterChannelTopicsAtShutdownEnabled")) {
            DiscordUtil.setTextChannelTopic(getMainTextChannel(), ChannelTopicUpdater.applyPlaceholders(getConfig().getString("ChannelTopicUpdaterChatChannelTopicAtServerShutdownFormat")));
            DiscordUtil.setTextChannelTopic(getConsoleChannel(), ChannelTopicUpdater.applyPlaceholders(getConfig().getString("ChannelTopicUpdaterConsoleChannelTopicAtServerShutdownFormat")));
        }

        // set status as invisible to not look like bot is online when it's not
        if (jda != null) jda.getPresence().setStatus(OnlineStatus.INVISIBLE);

        // shut down jda gracefully
        if (jda != null) jda.shutdown(false);

        // kill channel topic updater
        if (channelTopicUpdater != null) channelTopicUpdater.interrupt();

        // kill console message queue worker
        if (consoleMessageQueueWorker != null) consoleMessageQueueWorker.interrupt();

        // serialize account links to disk
        if (accountLinkManager != null) accountLinkManager.save();

        // close cancellation detector
        cancellationDetector.close();

        info("Shutdown completed in " + (System.currentTimeMillis() - shutdownStartTime) + "ms");
    }

    public void processChatMessage(Player player, String message, String channel, boolean cancelled) {
        // log debug message to notify that a chat message was being processed
        debug("Chat message received, canceled: " + cancelled);

        // return if player doesn't have permission
        if (!player.hasPermission("discordsrv.chat") && !(player.isOp() || player.hasPermission("discordsrv.admin"))) {
            debug("User " + player.getName() + " sent a message but it was not delivered to Discord due to lack of permission");
            return;
        }

        // return if mcMMO is enabled and message is from party or admin chat
        //todo if (Bukkit.getPluginManager().isPluginEnabled("mcMMO") && (ChatAPI.isUsingPartyChat(sender) || ChatAPI.isUsingAdminChat(sender))) return;

        // return if event canceled
        if (getConfig().getBoolean("DontSendCanceledChatEvents") && cancelled) {
            debug("User " + player.getName() + " sent a message but it was not delivered to Discord because the chat event was canceled and DontSendCanceledChatEvents is true");
            return;
        }

        // return if should not send in-game chat
        if (!getConfig().getBoolean("DiscordChatChannelMinecraftToDiscord")) {
            debug("User " + player.getName() + " sent a message but it was not delivered to Discord because DiscordChatChannelMinecraftToDiscord is false");
            return;
        }

        // return if user is unsubscribed from Discord and config says don't send those peoples' messages
        if (getUnsubscribedPlayers().contains(player.getUniqueId()) && !getConfig().getBoolean("MinecraftUnsubscribedMessageForwarding")) {
            debug("User " + player.getName() + " sent a message but it was not delivered to Discord because the user is unsubscribed to Discord and MinecraftUnsubscribedMessageForwarding is false");
            return;
        }

        // return if doesn't match prefix filter
        if (!message.startsWith(getConfig().getString("DiscordChatChannelPrefix"))) {
            debug("User " + player.getName() + " sent a message but it was not delivered to Discord because the message didn't start with \"" + getConfig().getString("DiscordChatChannelPrefix") + "\" (DiscordChatChannelPrefix)");
            return;
        }

        GameChatMessagePreProcessEvent preEvent = (GameChatMessagePreProcessEvent) DiscordSRV.api.callEvent(new GameChatMessagePreProcessEvent(channel, message, player));
        channel = preEvent.getChannel(); // update channel from event in case any listeners modified it
        message = preEvent.getMessage(); // update message from event in case any listeners modified it

        String userPrimaryGroup = VaultHook.getPrimaryGroup(player);
        boolean hasGoodGroup = !"".equals(userPrimaryGroup.replace(" ", ""));

        String format = hasGoodGroup ? getConfig().getString("MinecraftChatToDiscordMessageFormat") : getConfig().getString("MinecraftChatToDiscordMessageFormatNoPrimaryGroup");
        String discordMessage = format
                .replaceAll("&([0-9a-qs-z])", "")
                .replace("%message%", DiscordUtil.stripColor(message))
                .replace("%primarygroup%", VaultHook.getPrimaryGroup(player))
                .replace("%displayname%", DiscordUtil.stripColor(DiscordUtil.escapeMarkdown(player.getDisplayName())))
                .replace("%username%", DiscordUtil.stripColor(DiscordUtil.escapeMarkdown(player.getName())))
                .replace("%world%", player.getWorld().getName())
                .replace("%worldalias%", DiscordUtil.stripColor(MultiverseCoreHook.getWorldAlias(player.getWorld().getName())))
                .replaceAll("%time%|%date%", new Date().toString())
        ;

        discordMessage = DiscordUtil.convertMentionsFromNames(discordMessage, getMainTextChannel().getGuild());

        GameChatMessagePostProcessEvent postEvent = (GameChatMessagePostProcessEvent) DiscordSRV.api.callEvent(new GameChatMessagePostProcessEvent(channel, discordMessage, player, preEvent.isCancelled()));
        if (postEvent.isCancelled()) {
            DiscordSRV.debug("GameChatMessagePreProcessEvent was cancelled, message send aborted");
            return;
        }
        channel = postEvent.getChannel(); // update channel from event in case any listeners modified it
        discordMessage = postEvent.getProcessedMessage(); // update message from event in case any listeners modified it

        if (channel == null) DiscordUtil.sendMessage(getMainTextChannel(), discordMessage);
        else DiscordUtil.sendMessage(getDestinationTextChannelForGameChannelName(channel), discordMessage);
    }

    public static void broadcastMessageToMinecraftServer(String channel, String message) {
        // apply regex to message
        if (StringUtils.isNotBlank(getPlugin().getConfig().getString("DiscordChatChannelRegex")))
            message = message.replaceAll(getPlugin().getConfig().getString("DiscordChatChannelRegex"), getPlugin().getConfig().getString("DiscordChatChannelRegexReplacement"));

        if (getPlugin().getHookedPlugins().size() == 0 || channel == null) {
            for (Player player : PlayerUtil.getOnlinePlayers()) {
                if (getPlugin().getUnsubscribedPlayers().contains(player.getUniqueId())) continue; // don't send this player the message if they're unsubscribed
                player.sendMessage(message);
            }
            api.callEvent(new DiscordGuildMessagePostBroadcastEvent(channel, message));
        } else {
            if (getPlugin().getHookedPlugins().contains("herochat")) HerochatHook.broadcastMessageToChannel(channel, message);
            else if (getPlugin().getHookedPlugins().contains("legendchat")) LegendChatHook.broadcastMessageToChannel(channel, message);
            else if (getPlugin().getHookedPlugins().contains("lunachat")) LunaChatHook.broadcastMessageToChannel(channel, message);
            else if (getPlugin().getHookedPlugins().contains("townychat")) TownyChatHook.broadcastMessageToChannel(channel, message);
            else if (getPlugin().getHookedPlugins().contains("venturechat")) VentureChatHook.broadcastMessageToChannel(channel, message);
            else {
                error("Hooked plugins " + DiscordSRV.getPlugin().getHookedPlugins() + " are somehow in the hooked plugins list yet aren't supported.");
                broadcastMessageToMinecraftServer(null, message);
                return;
            }
            api.callEvent(new DiscordGuildMessagePostBroadcastEvent(channel, message));
        }
    }

    @SuppressWarnings("deprecation")
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', getConfig().getString("DiscordCommandFormat")));
            return true;
        }
        if (args[0].equals("?") || args[0].equalsIgnoreCase("help") || args[0].equalsIgnoreCase("dowhatyouwantcauseapirateisfreeyouareapirateyarharfiddledeedee")) {
            if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) sender.sendMessage(ChatColor.AQUA + "/discord toggle/subscribe/unsubscribe/link/linked/clearlinked");
            else sender.sendMessage(ChatColor.AQUA + "/discord bcast/setpicture/reload/debug/toggle/subscribe/unsubscribe/link/linked/clearlinked");
        }
        if (args[0].equalsIgnoreCase("bcast")) {
            if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            List<String> messageStrings = Arrays.asList(args);
            String message = String.join(" ", messageStrings.subList(1, messageStrings.size()));
            DiscordUtil.sendMessage(getMainTextChannel(), message);
        }
        if (args[0].equalsIgnoreCase("setpicture")) {
            if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            if (args.length < 2) {
                sender.sendMessage(ChatColor.AQUA + "Must give URL to picture to set as bot picture");
                return true;
            }

            sender.sendMessage(ChatColor.AQUA + "Downloading picture...");
            File pictureFile = new File(getDataFolder(), "picture.jpg");
            try {
                FileUtils.copyURLToFile(new URL(args[1]), pictureFile);
                DiscordUtil.setAvatarBlocking(pictureFile);
                sender.sendMessage(ChatColor.AQUA + "Picture updated successfully");
            } catch (IOException | RuntimeException e) {
                sender.sendMessage("Failed to update picture: " + e.getLocalizedMessage());
            }
            pictureFile.delete();
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            reloadConfig();
            sender.sendMessage(ChatColor.AQUA + "The DiscordSRV config has been reloaded. Some config options may require a restart.");
        }
        if (args[0].equalsIgnoreCase("debug")) {
            if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }
            sender.sendMessage(ChatColor.AQUA + DebugUtil.run(sender instanceof ConsoleCommandSender ? "CONSOLE" : sender.getName()));
        }

        if (!(sender instanceof Player)) return true;
        Player senderPlayer = (Player) sender;

        // subscriptions
        if (args[0].equalsIgnoreCase("toggle") && (sender.hasPermission("discordsrv.togglesubscribe") || sender.hasPermission("discordsrv.admin") || sender.isOp())) setIsSubscribed(senderPlayer.getUniqueId(), unsubscribedPlayers.contains(senderPlayer.getUniqueId()));
        if (args[0].equalsIgnoreCase("subscribe") && (sender.hasPermission("discordsrv.togglesubscribe") || sender.hasPermission("discordsrv.admin") || sender.isOp())) setIsSubscribed(senderPlayer.getUniqueId(), true);
        if (args[0].equalsIgnoreCase("unsubscribe") && (sender.hasPermission("discordsrv.togglesubscribe") || sender.hasPermission("discordsrv.admin") || sender.isOp())) setIsSubscribed(senderPlayer.getUniqueId(), false);
        if (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("subscribe") || args[0].equalsIgnoreCase("unsubscribe"))
            if (!sender.hasPermission("discordsrv.togglesubscribe") && !sender.hasPermission("discordsrv.admin") && !sender.isOp()) {
                sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                return true;
            }

            sender.sendMessage(ChatColor.translateAlternateColorCodes('&', !unsubscribedPlayers.contains(senderPlayer.getUniqueId())
                    ? getConfig().getString("MinecraftSubscriptionMessagesOnSubscribe")
                    : getConfig().getString("MinecraftSubscriptionMessagesOnUnsubscribe")
            ));

        // account linking
        if (args[0].equalsIgnoreCase("link")) {
            String code = accountLinkManager.generateCode(senderPlayer.getUniqueId());
            sender.sendMessage(ChatColor.AQUA + "Your link code is " + code + ". Send a private message to the bot (" + getMainTextChannel().getGuild().getMember(jda.getSelfUser()).getEffectiveName() + ") on Discord with just this code as the message to link your Discord account to your UUID.");
        }
        if (args[0].equalsIgnoreCase("linked")) {
            if (args.length == 1) {
                sender.sendMessage(ChatColor.AQUA + "Your UUID is linked to " + (accountLinkManager.getDiscordId(senderPlayer.getUniqueId()) != null ? jda.getUserById(accountLinkManager.getDiscordId(senderPlayer.getUniqueId())) != null ? jda.getUserById(accountLinkManager.getDiscordId(senderPlayer.getUniqueId())) : accountLinkManager.getDiscordId(senderPlayer.getUniqueId()) : "nobody."));
            } else {
                if (!sender.isOp() && !sender.hasPermission("discordsrv.admin")) {
                    sender.sendMessage(ChatColor.RED + "You do not have permission to do that.");
                    return true;
                }

                String target = args[1];

                if (jda.getUserById(target) != null) { // discord id given
                    User targetUser = jda.getUserById(target);
                    UUID targetUuid = accountLinkManager.getUuid(target);
                    OfflinePlayer targetPlayer = Bukkit.getPlayer(targetUuid);

                    if (targetUuid == null) sender.sendMessage(ChatColor.RED + "Discord user " + targetUser + " is not linked to any Minecraft account");
                    else sender.sendMessage(ChatColor.AQUA + "Discord user " + targetUser + " is linked to Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ")");
                } else if (target.length() == 32 || target.length() == 36) { // uuid given
                    UUID targetUuid = UUID.fromString(target);
                    OfflinePlayer targetPlayer = Bukkit.getPlayer(targetUuid);
                    User targetUser = jda.getUserById(accountLinkManager.getDiscordId(targetUuid));

                    if (targetUser == null) sender.sendMessage(ChatColor.RED + "Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ") is not linked to any Discord account");
                    else sender.sendMessage(ChatColor.AQUA + "Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ") is linked to Discord account " + targetUser);
                } else if (Bukkit.getPlayerExact(target) != null) { // player name given
                    OfflinePlayer targetPlayer = Bukkit.getPlayerExact(target);
                    UUID targetUuid = targetPlayer.getUniqueId();
                    User targetUser = jda.getUserById(accountLinkManager.getDiscordId(targetUuid));

                    if (targetUser == null) sender.sendMessage(ChatColor.RED + "Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ") is not linked to any Discord account");
                    else sender.sendMessage(ChatColor.AQUA + "Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ") is linked to Discord account " + targetUser);
                } else { // discord name given?
                    List<User> matchingUsers = jda.getUsersByName(String.join(" ", args), true);

                    if (matchingUsers.size() == 0) {
                        sender.sendMessage(ChatColor.RED + "Nobody found with Discord ID/Discord name/Minecraft name/Minecraft UUID matching \"" + target + "\" to look up.");
                        return true;
                    }

                    for (User targetUser : matchingUsers) {
                        UUID targetUuid = accountLinkManager.getUuid(targetUser.getId());
                        OfflinePlayer targetPlayer = Bukkit.getPlayer(targetUuid);

                        sender.sendMessage(ChatColor.AQUA + "Discord user " + targetUser + " is linked to Minecraft account " + targetPlayer.getName() + " (UUID " + targetUuid + ")");
                    }
                }
            }
        }
        if (args[0].equalsIgnoreCase("clearlinked")) {
            sender.sendMessage(ChatColor.AQUA + "Your UUID is no longer associated with " + (accountLinkManager.getDiscordId(senderPlayer.getUniqueId()) != null ? jda.getUserById(accountLinkManager.getDiscordId(senderPlayer.getUniqueId())) != null ? jda.getUserById(accountLinkManager.getDiscordId(senderPlayer.getUniqueId())) : accountLinkManager.getDiscordId(senderPlayer.getUniqueId()) : "nobody. Never was."));
            accountLinkManager.unlink(senderPlayer.getUniqueId());
        }

        return true;
    }

    private void setIsSubscribed(UUID playerUuid, boolean subscribed) {
        if (subscribed) {
            unsubscribedPlayers.remove(playerUuid);
        } else {
            if (!unsubscribedPlayers.contains(playerUuid))
                unsubscribedPlayers.add(playerUuid);
        }
    }

    public boolean pluginHookIsEnabled(String pluginName) {
        for (String enabledPluginHookName : getConfig().getStringList("EnabledPluginsHooks"))
            if (pluginName.toLowerCase().startsWith(enabledPluginHookName)) return true;
        return false;
    }

}

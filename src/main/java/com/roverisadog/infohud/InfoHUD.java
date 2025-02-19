
package com.roverisadog.infohud;

import com.roverisadog.infohud.config.ConfigManager;
import com.roverisadog.infohud.config.PlayerCfg;
import com.roverisadog.infohud.message.*;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.BaseComponent;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public class InfoHUD extends JavaPlugin {

	/** CraftBukkit, Spigot, Paper, ... */
	public static String serverVendor;
	/** Minecraft release 1.XX. */
	public static int apiVersion;
	private static InfoHUD instance;

	public InfoHUD() {
		instance = this;
	}

	/**
	 * Get the current InfoHUD singleton instance. Not to be used before
	 * {@link #onEnable()} is called .
	 * @return Instance of the plugin.
	 */
	public static InfoHUD getPlugin() {
		return instance;
	}

	private boolean isSpigot;

	//Version for reflection
	public static ActionBarSender actionBarSender;

	// Tasks
	protected BukkitTask msgSenderTask;
	protected BukkitTask biomeUpdateTask;

	private ConfigManager configManager;

	/**
	 * Initial setup:
	 * Load config, get version, get NMS packet classes.
	 * Configure CommandHandler
	 */
	@Override
	public void onEnable() {
		try {
			Util.printToTerminal(Util.GRN + "InfoHUD Enabling...");
			this.saveDefaultConfig(); // Does nothing if config.yml already exists.

			//Save initial cfg or load.
			this.configManager = new ConfigManager(this);
			if (!configManager.loadConfig()) {
				throw new Exception(Util.ERR + "Error while reading config.yml.");
			}

			//Version check eg: org.bukkit.craftbukkit.v1_16_R2.blabla
			String bukkitVer = Bukkit.getServer().getBukkitVersion(); // 1.16.2-R0.1-SNAPSHOT
			String mcVer = bukkitVer.split("-")[0]; //[1.16.2]
			apiVersion = Integer.parseInt(mcVer.split("\\.")[1]); // 16

			isSpigot = isSpigot(); // AKA can avoid using NMS

//			Util.printToTerminal("versionStr: %s, apiVersion: %s, serverVendor: %s"
//					, versionStr, Util.apiVersion, Util.serverVendor);

			// Either use spigot API or try to use NMS otherwise.
			if (!initializeActionBarSender()) {
				throw new Exception(Util.ERR + "Version error.");
			}

			// Setup command executor
			this.getCommand(CommandExecutor.CMD_NAME).setExecutor(new CommandExecutor(this));

			//Start sender and biome updater tasks
			msgSenderTask = startMessageUpdaterTask(MessageUpdaterTask.getMessageUpdateDelay());
			biomeUpdateTask = startBiomeUpdaterTask(BrightBiomes.getBiomeUpdateDelay());

			// Register action bar listener
			PauseListener plb = new PauseListener(this);
			Bukkit.getServer().getPluginManager().registerEvents(plb, this);
			if (isSpigot) {
				PauseListenerExtra pls = new PauseListenerExtra(this);
				Bukkit.getServer().getPluginManager().registerEvents(pls, this);
			}

			Util.printToTerminal(Util.GRN + "InfoHUD Successfully Enabled on "
					+ Util.WHI + (isSpigot ? "Spigot API" : "NMS")
					+ " v1." + apiVersion);
		}
		catch (Exception e) {
			Util.printToTerminal(e.getMessage());
			e.printStackTrace();
			Util.printToTerminal("Shutting down...");
			Bukkit.getPluginManager().disablePlugin(this);
		}
	}

	/** Clean up while shutting down (Currently nothing). */
	@Override
	public void onDisable() {
		Util.printToTerminal(Util.GRN + "InfoHUD Disabled");
	}

	/**
	 * Starts a synchronous task whose job is to get each player's config and
	 * send the right message accordingly. Does NOT change any value from the
	 * {@link PlayerCfg}. Cannot be asynchronous as it accesses
	 * non thread-safe methods from bukkit API.
	 * @param messageUpdateDelay config.yml: messageUpdateDelay
	 * @return BukkitTask created.
	 */
	public BukkitTask startMessageUpdaterTask(long messageUpdateDelay) {
		MessageUpdaterTask mut = new MessageUpdaterTask(this, (int) messageUpdateDelay);
		return Bukkit.getScheduler().runTaskTimer(this, mut, 0L, messageUpdateDelay);
	}

	/**
	 * Starts and get a reference to the synchronous task responsible for fetching biomes.
	 * Relatively very expensive. Not asynchronous as it accesses non thread-safe methods
	 * from the bukkit API.
	 * @param biomeUpdateDelay Ticks between each players' biomes updates, preferably larger.
	 *                         config.yml: biomeUpdateDelay
	 * @return Reference to the task (so that it can be stopped, etc.)
	 */
	private BukkitTask startBiomeUpdaterTask(long biomeUpdateDelay) {
		Runnable but = new BrightBiomes.BrightBiomesUpdaterTask(this);
		return Bukkit.getScheduler().runTaskTimer(this, but, 0L, biomeUpdateDelay);
	}

	private boolean isSpigot() {
		// Use spigot API when possible by checking Player$Spigot and method exists.
		// (Actionbar DNE MC < 1.9, and API DNE craftbukkit / early 1.9 spigot builds)
		try {
//			Player.Spigot.class.getMethod("sendMessage", ChatMessageType.class, BaseComponent.class);
			Class.forName("org.bukkit.entity.Player$Spigot").getDeclaredMethod(
					"sendMessage", ChatMessageType.class, BaseComponent.class); // Exists
//			Util.printToTerminal("Classname: " + Player.Spigot.class);
			return true;
		} catch (Exception | Error ignored) {
//			ignored.printStackTrace();
			return false;
		}
	}

	/**
	 * Utility method to initialise the ActionBarSender, depending on server
	 * version and vendor, can be spigot API wrapper (spigot/paper, preferred)
	 * or NMS (craftbukkit). NMS sender gotten through reflection and needs to
	 * be updated every update.
	 * @return Whether an {@link ActionBarSender} initialized correctly.
	 */
	private boolean initializeActionBarSender() {
		// Prefer using spigot api when possible
		if (isSpigot) {
			Util.printToTerminal(Util.GRN + "Using Spigot API");
			actionBarSender = new ActionBarSenderSpigot();
		}
		// Fallback to NMS if using craftbukkit / very old spigot
		else {
			Util.printToTerminal(Util.GRN + "Spigot API unavailable or incompatible:" +
					"falling back to NMS");

			/* Get the CraftBukkit package name using reflection for use with each ActionBarSenders
			This operation fails on PaperMC 1.20.5+, but this is ok since the versionStr isn't used
			with Paper since Paper supports Spigot API.
			https://forums.papermc.io/threads/important-dev-psa-future-removal-of-cb-package-relocation.1106/
			 */

			String nmsVersionStr = "";
			try {
				String ver = Bukkit.getServer().getClass().getPackage().getName();
				nmsVersionStr = ver.split("\\.")[3]; // v1_16_R2
			} catch (IndexOutOfBoundsException e) {
				//ignored
			}

			try {
				if (apiVersion < 12) // 1.8 - 1.11
					actionBarSender = new ActionBarSenderNMS1_8(nmsVersionStr);
				else if (apiVersion < 16) // 1.12 - 1.15
					actionBarSender = new ActionBarSenderNMS1_12(nmsVersionStr);
				else if (apiVersion < 17) // 1.16
					actionBarSender = new ActionBarSenderNMS1_16(nmsVersionStr);
				else if (apiVersion < 18) // 1.17
					actionBarSender = new ActionBarSenderNMS1_17(nmsVersionStr);
				else if (apiVersion < 19) // 1.18
					actionBarSender = new ActionBarSenderNMS1_18(nmsVersionStr);
				else if (apiVersion < 20) // 1.19
					actionBarSender = new ActionBarSenderNMS1_19(nmsVersionStr);
				else // 1.20.1 - 1.20.4
					actionBarSender = new ActionBarSenderNMS1_20(nmsVersionStr);

				//Broken without crash 1.20.2 onward (nothing sent to actionbar)
				//Broken with crash 1.20.4 onward (plugin doesn't init)


			} catch (Exception | Error e) { // Reflection error
				Util.printToTerminal(Util.ERR + "Exception while initializing packets with " +
						"NMS " + nmsVersionStr + ". Version may be incompatible.");
				e.printStackTrace();
				return false;
			}
		}
		return true;
	}

	/**
	 * Stops all tasks, reloads content of config.yml and restarts tasks with the updated values.
	 * @param sender Sender who issued the reload command.
	 */
	public void reload(CommandSender sender) {
		boolean success;
		try {
			// Cancel tasks, reload config, and restart tasks.
			msgSenderTask.cancel();
			biomeUpdateTask.cancel();
			success = configManager.loadConfig();
			msgSenderTask = startMessageUpdaterTask(MessageUpdaterTask.getMessageUpdateDelay());
			biomeUpdateTask = startBiomeUpdaterTask(BrightBiomes.getBiomeUpdateDelay());

			if (success) {
				Util.sendMsg(sender, Util.GRN + "Reloaded successfully.");
			}
			else {
				Util.sendMsg(sender, Util.ERR + "Reload failed.");
			}
		}
		catch (Exception e) {
			Util.sendMsg(sender, Util.ERR + "An internal error has occurred.");
			e.printStackTrace();
		}
	}

	public ConfigManager getConfigManager() {
		return configManager;
	}
}

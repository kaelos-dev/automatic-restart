package net.kaelos.ar;

import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.util.concurrent.ThreadFactoryBuilder;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.kaelos.ar.command.ArCommands;
import net.kaelos.ar.config.ConfigManager;
import net.kaelos.ar.data.holder.RestartDataHolder;
import net.kaelos.ar.init.SchedulerHandler;

public class AutomaticRestart implements DedicatedServerModInitializer {

    public static final String MOD_ID = "automaticrestart";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static RestartDataHolder restartDataHolder;
    public static final ScheduledExecutorService executorService = Executors.newSingleThreadScheduledExecutor(new ThreadFactoryBuilder()
        .setNameFormat(MOD_ID + "-Executor-Thread-%d")
        .setDaemon(true)
        .build());

    public static boolean isDisableShutdown = false;

    public static volatile boolean restartRequested = false;
    public static volatile boolean hookRegistered = false;

    @Override
    public void onInitializeServer() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, environment) ->  ArCommands.register(dispatcher));

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try {
                ConfigManager configManager = new ConfigManager();
                configManager.readConfig();
            } catch (IOException exception) {
                LOGGER.error("Failed to read config file: ", exception);
                restartDataHolder = null;
            }

            if (restartDataHolder != null) {
                restartDataHolder.recalcSchedule();
                SchedulerHandler.startRestartScheduler(server);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SchedulerHandler.cancelSchedulerTasks();
            executorService.shutdown();
        });
    }

    public static void shutdownHook() {
        if (isDisableShutdown) 
            return;

        if (restartDataHolder == null) {
            LOGGER.warn("Failed to register reboot hook or reboot script disabled");
            return;
        }

        if (hookRegistered) {
            LOGGER.info("Shutdown hook successfully logged stage skipped");
            return;
        }

        synchronized (AutomaticRestart.class) {
            if (hookRegistered) 
                return;

            hookRegistered = true;

            String script = restartDataHolder.getPathToScript();
            String os = System.getProperty("os.name").toLowerCase();
            long pid = ProcessHandle.current().pid();
            LOGGER.info("A shutdown hook is registered -> Script: {}, OS: {}, PID: {}", script, os, pid);

            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(2000);

                    ProcessBuilder processBuilder;
                    if (os.startsWith("windows")) {
                        processBuilder = new ProcessBuilder(
                                "powershell", "-NoProfile", "-Command",
                                "$p=" + pid + "; Wait-Process -Id $p; Start-Process -FilePath '" + script.replace("'", "''") + "'"
                        );
                    } else {
                        processBuilder = new ProcessBuilder(
                                "bash", "-lc",
                                "while kill -0 " + pid + " 2>/dev/null; do sleep 1; done; exec " + script
                        );
                    }

                    processBuilder.start();
                } catch (Exception exception) {
                    LOGGER.warn("Failed to restart the server: ", exception);
                }
            }, MOD_ID + "-ShutdownHook");

            Runtime.getRuntime().addShutdownHook(thread);
        }
    }

    public static void requestRestart(MinecraftServer server) {
        if (restartDataHolder.isEnableScript()) {
            server.getPlayerList().saveAll();
            server.saveAllChunks(true, true, true);
            restartRequested = true;
            shutdownHook();
            server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), "stop");
        }
    }
}

package onevnl.ru.elytrya.commands.subcommands;

import org.bukkit.command.CommandSender;

import onevnl.ru.elytrya.api.BoostyClient;
import onevnl.ru.elytrya.api.managers.MessageManager;
import onevnl.ru.elytrya.tasks.SubscriptionSyncTask;

public class ForceSyncSubCommand implements SubCommand {

    private final BoostyClient client;

    public ForceSyncSubCommand(BoostyClient client) {
        this.client = client;
    }

    @Override
    public String getName() {
        return "forcesync";
    }

    @Override
    public String getPermission() {
        return "boosty.admin.forcesync";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        MessageManager msg = client.getMessageManager();
        
        sender.sendMessage(msg.getMessage("admin_forcesync_started"));
        

        SubscriptionSyncTask task = new SubscriptionSyncTask(client);
        task.synchronize().thenAccept(result -> task.reply(sender, result));
        
        sender.sendMessage(msg.getMessage("admin_forcesync_async"));
    }
}
package com.example.examplemod;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.entity.helpful.EntityFriendlyCreature;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;

/**
 * {@code /allycraft <アイテムID> [個数] [メタ値]} … 一番近い自分の味方に「これを作って」と頼む。
 * 味方はレシピ（作業台・かまど・GregTechの機械レシピ）を逆引きし、原木集め→作業台→道具→
 * 採掘→精錬…の手順を自分で導き出して実行し、完成品を届ける。
 *
 * <p>{@code /allycraft cancel} … 近くの自分の味方への依頼を取り消す。<br>
 * {@code /allycraft status} … 近くの味方の仕事・役割・スキル・満腹度を表示。</p>
 */
public class CommandAllyCraft extends CommandBase {

    @Override
    public String getName() {
        return "allycraft";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/allycraft <item> [count] [meta] | cancel | status";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return sender instanceof EntityPlayerMP;
    }

    private static List<EntityFriendlyCreature> ownedNearby(EntityPlayerMP player) {
        List<EntityFriendlyCreature> out = new ArrayList<EntityFriendlyCreature>();
        UUID id = player.getUniqueID();
        for (EntityFriendlyCreature e : player.world.getEntitiesWithinAABB(EntityFriendlyCreature.class,
                player.getEntityBoundingBox().grow(64.0D))) {
            if (e.isEntityAlive() && id.equals(AllyAreas.ownerId(e))) {
                out.add(e);
            }
        }
        return out;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        EntityPlayerMP player = getCommandSenderAsPlayer(sender);
        if (args.length == 0) {
            throw new WrongUsageException(getUsage(sender));
        }
        List<EntityFriendlyCreature> allies = ownedNearby(player);
        if ("status".equalsIgnoreCase(args[0])) {
            if (allies.isEmpty()) {
                player.sendMessage(new TextComponentString("近く（64ブロック）に自分の味方がいません。"));
            }
            for (EntityFriendlyCreature e : allies) {
                String job = EngenderGatheringBridge.jobLabel(e.getEntityData().getString("EngenderJob"));
                AllyCraftRequests.Request r = AllyCraftRequests.get(e);
                player.sendMessage(new TextComponentString(TextFormatting.AQUA + e.getName() + TextFormatting.RESET
                        + " 仕事:" + job + " 役割:" + AllyTeamManager.roleOf(e).label
                        + " 満腹:" + AllyMaintenance.food(e) + "/20 " + AllySkills.summary(e)
                        + (r != null ? " 依頼:" + r.name + " x" + r.count : "")));
            }
            return;
        }
        if ("cancel".equalsIgnoreCase(args[0])) {
            int n = 0;
            for (EntityFriendlyCreature e : allies) {
                if (AllyCraftRequests.get(e) != null) {
                    AllyCraftRequests.remove(e);
                    n++;
                }
            }
            player.sendMessage(new TextComponentString("依頼を " + n + " 件取り消しました。"));
            return;
        }
        Item item = getItemByText(sender, args[0]);
        int count = args.length >= 2 ? parseInt(args[1], 1, 576) : 1;
        int meta = args.length >= 3 ? parseInt(args[2], 0, 32767) : 0;
        if (allies.isEmpty()) {
            throw new CommandException("近く（64ブロック）に自分の味方がいません。");
        }
        // 依頼を持っていない味方のうち一番近い者へ
        EntityFriendlyCreature best = null;
        double bestD = Double.MAX_VALUE;
        for (EntityFriendlyCreature e : allies) {
            double d = e.getDistanceSq(player) + (AllyCraftRequests.get(e) != null ? 1.0E6 : 0);
            if (d < bestD) {
                bestD = d;
                best = e;
            }
        }
        ItemStack target = new ItemStack(item, 1, meta);
        AllyCraftRequests.put(best, new AllyCraftRequests.Request(target, count, player.getUniqueID()));
        player.sendMessage(new TextComponentString("[" + best.getName() + "] 「" + target.getDisplayName() + "」を "
                + count + " 個作ります。レシピを逆引きして手順を考えます…"));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, BlockPos targetPos) {
        if (args.length == 1) {
            List<String> base = new ArrayList<String>();
            base.add("status");
            base.add("cancel");
            List<String> names = getListOfStringsMatchingLastWord(args, Item.REGISTRY.getKeys());
            base.addAll(names);
            return getListOfStringsMatchingLastWord(args, base);
        }
        return Collections.<String>emptyList();
    }
}

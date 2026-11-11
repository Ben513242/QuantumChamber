package dev.quantumchamber.gametest;

import dev.quantumchamber.universe.UniverseId;
import java.util.UUID;
import net.fabricmc.fabric.api.gametest.v1.FabricGameTest;
import net.minecraft.block.BedBlock;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.RespawnAnchorBlock;
import net.minecraft.block.enums.BedPart;
import net.minecraft.entity.Entity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.test.GameTest;
import net.minecraft.test.TestContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.TeleportTarget;
import net.minecraft.world.World;

/**
 * Task R：宇宙內死亡只把原版兩個 canonical fallback 換成同一 U，位置照原版；床／錨與其他維度照原版。
 * 本 batch 會同步物化 U，單獨成批；兩個 U 每個 server 只物化一次，其餘案例沿用。床／錨各案使用不重疊的遠處座標。
 */
public final class M6UniverseRespawnGameTests implements FabricGameTest {
    private record Universes(MinecraftServer server,ServerWorld first,ServerWorld second) {}
    private static Universes universes;

    private static Universes universes(MinecraftServer server) {
        if(universes==null || universes.server()!=server) {
            universes=new Universes(server,
                    M6ProfileProbePhases.fixtureSource(server,UniverseId.of(UUID.randomUUID()),"normal"),
                    M6ProfileProbePhases.fixtureSource(server,UniverseId.of(UUID.randomUUID()),"normal"));
        }
        return universes;
    }

    /** 原版登入固定落在 canonical，之後再以原版 teleport 送到指定世界。 */
    private static ConnectedGameTestPlayer playerIn(TestContext context,ServerWorld world,Vec3d pos) {
        var connection=new ConnectedGameTestPlayer(context.getWorld());
        connection.player().teleportTo(new TeleportTarget(world,pos,Vec3d.ZERO,0,0,TeleportTarget.NO_OP));
        context.assertTrue(connection.player().getServerWorld()==world,"RESPAWN_FIXTURE_WORLD：測試玩家須實際位於 "+world.getRegistryKey().getValue());
        return connection;
    }

    /** 在 {@code base} 清出空間並鋪石台，回傳放好的方塊位置；只用 NOTIFY_LISTENERS，不觸發鄰居更新。 */
    private static void platform(ServerWorld world,BlockPos base) {
        for(int dx=-2;dx<=2;dx++) for(int dz=-3;dz<=2;dz++) {
            world.setBlockState(base.add(dx,-1,dz),Blocks.STONE.getDefaultState(),Block.NOTIFY_LISTENERS);
            for(int dy=0;dy<3;dy++) world.setBlockState(base.add(dx,dy,dz),Blocks.AIR.getDefaultState(),Block.NOTIFY_LISTENERS);
        }
    }

    /** 床頭朝北：回傳床頭位置，即原版睡覺時記錄的 spawn point。 */
    private static BlockPos bed(ServerWorld world,BlockPos foot) {
        platform(world,foot);
        var head=foot.north();
        var state=Blocks.RED_BED.getDefaultState().with(BedBlock.FACING,Direction.NORTH);
        world.setBlockState(foot,state.with(BedBlock.PART,BedPart.FOOT),Block.NOTIFY_LISTENERS);
        world.setBlockState(head,state.with(BedBlock.PART,BedPart.HEAD),Block.NOTIFY_LISTENERS);
        return head;
    }

    private static void near(TestContext context,TeleportTarget target,ServerWorld world,BlockPos pos,int radius,String label) {
        context.assertTrue(target.world()==world,label+"：目標世界應為 "+world.getRegistryKey().getValue()+"，實際 "+target.world().getRegistryKey().getValue());
        context.assertTrue(Math.abs(target.pos().x-(pos.getX()+0.5))<=radius+1 && Math.abs(target.pos().z-(pos.getZ()+0.5))<=radius+1,
                label+"：目標 XZ 應在 "+pos+" 附近 "+radius+" 格內，實際 "+target.pos());
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_without_spawn_point_stays_in_same_universe(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first();
        try(var connection=playerIn(context,u,new Vec3d(1500.5,200,1500.5))) {
            var target=connection.player().getRespawnTarget(true,TeleportTarget.NO_OP);
            near(context,target,u,u.getSpawnPos(),server.getSpawnRadius(u),"RESPAWN_NO_SPAWN_POINT_SAME_UNIVERSE");
            context.assertTrue(!target.missingRespawnBlock(),"RESPAWN_NO_SPAWN_POINT_SAME_UNIVERSE：沒有重生點不是床失效");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_with_bed_in_same_universe_uses_bed(TestContext context) {
        var u=universes(context.getWorld().getServer()).first();
        var head=bed(u,new BlockPos(1000,150,1000));
        try(var connection=playerIn(context,u,new Vec3d(1500.5,200,1500.5))) {
            connection.player().setSpawnPoint(u.getRegistryKey(),head,0,false,false);
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),u,head,2,"RESPAWN_SAME_UNIVERSE_BED");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_with_bed_in_other_universe_uses_that_bed(TestContext context) {
        var both=universes(context.getWorld().getServer());
        var head=bed(both.second(),new BlockPos(1000,150,1000));
        try(var connection=playerIn(context,both.first(),new Vec3d(1600.5,200,1500.5))) {
            connection.player().setSpawnPoint(both.second().getRegistryKey(),head,0,false,false);
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),both.second(),head,2,"RESPAWN_OTHER_UNIVERSE_BED");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_with_canonical_bed_uses_canonical_bed(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first(); var canonical=server.getOverworld();
        var head=bed(canonical,new BlockPos(3000,150,3000));
        try(var connection=playerIn(context,u,new Vec3d(1700.5,200,1500.5))) {
            connection.player().setSpawnPoint(World.OVERWORLD,head,0,false,false);
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),canonical,head,2,"RESPAWN_CANONICAL_BED");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_with_nether_anchor_uses_anchor(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first(); var nether=server.getWorld(World.NETHER);
        var anchor=new BlockPos(1000,100,1000);
        platform(nether,anchor);
        nether.setBlockState(anchor,Blocks.RESPAWN_ANCHOR.getDefaultState().with(RespawnAnchorBlock.CHARGES,4),Block.NOTIFY_LISTENERS);
        try(var connection=playerIn(context,u,new Vec3d(1800.5,200,1500.5))) {
            connection.player().setSpawnPoint(World.NETHER,anchor,0,false,false);
            // 錨在原版 offset 94 前就 return，不經被包住的 getOverworld；消耗由原版決定，這裡只驗目標。
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),nether,anchor,2,"RESPAWN_NETHER_ANCHOR");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void universe_death_with_missing_bed_stays_in_same_universe(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first();
        var missing=new BlockPos(1100,150,1000);
        platform(u,missing);
        try(var connection=playerIn(context,u,new Vec3d(1900.5,200,1500.5))) {
            connection.player().setSpawnPoint(u.getRegistryKey(),missing,0,false,false);
            var target=connection.player().getRespawnTarget(true,TeleportTarget.NO_OP);
            near(context,target,u,u.getSpawnPos(),server.getSpawnRadius(u),"RESPAWN_MISSING_BED_SAME_UNIVERSE");
            context.assertTrue(target.missingRespawnBlock(),"RESPAWN_MISSING_BED_SAME_UNIVERSE：床失效時保留原版 missingRespawnBlock");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void canonical_death_without_spawn_point_stays_vanilla(TestContext context) {
        var server=context.getWorld().getServer(); universes(server); var canonical=server.getOverworld();
        try(var connection=playerIn(context,canonical,new Vec3d(4000.5,200,4000.5))) {
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),canonical,canonical.getSpawnPos(),
                    server.getSpawnRadius(canonical),"RESPAWN_CANONICAL_VANILLA");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void nether_death_without_spawn_point_stays_vanilla(TestContext context) {
        var server=context.getWorld().getServer(); universes(server); var canonical=server.getOverworld();
        try(var connection=playerIn(context,server.getWorld(World.NETHER),new Vec3d(1200.5,100,1000.5))) {
            near(context,connection.player().getRespawnTarget(true,TeleportTarget.NO_OP),canonical,canonical.getSpawnPos(),
                    server.getSpawnRadius(canonical),"RESPAWN_NETHER_VANILLA");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void end_exit_with_universe_bed_returns_to_universe_bed(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first();
        var head=bed(u,new BlockPos(1200,150,1000));
        try(var connection=playerIn(context,server.getWorld(World.END),new Vec3d(1000.5,100,1000.5))) {
            connection.player().setSpawnPoint(u.getRegistryKey(),head,0,false,false);
            // End→主世界出口與原版 EndPortalBlock 相同，傳 false。
            near(context,connection.player().getRespawnTarget(false,TeleportTarget.NO_OP),u,head,2,"RESPAWN_END_EXIT_UNIVERSE_BED");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void end_exit_without_bed_returns_to_canonical(TestContext context) {
        var server=context.getWorld().getServer(); universes(server); var canonical=server.getOverworld();
        try(var connection=playerIn(context,server.getWorld(World.END),new Vec3d(1100.5,100,1000.5))) {
            near(context,connection.player().getRespawnTarget(false,TeleportTarget.NO_OP),canonical,canonical.getSpawnPos(),
                    server.getSpawnRadius(canonical),"RESPAWN_END_EXIT_CANONICAL");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void corridor_death_stays_vanilla_without_universe_log(TestContext context) {
        var server=context.getWorld().getServer(); universes(server); var canonical=server.getOverworld();
        var corridor=server.getWorld(dev.quantumchamber.superposition.SuperpositionWorld.KEY);
        try(var connection=playerIn(context,corridor,new Vec3d(0.5,100,-50000.5))) {
            TeleportTarget target;
            String player=connection.player().getUuid().toString();
            // 同批案例並行；capture 只包這一次同步呼叫，並以本案玩家 UUID 篩選。
            try(var capture=new LogCapture("quantumchamber")) {
                target=connection.player().getRespawnTarget(true,TeleportTarget.NO_OP);
                context.assertTrue(capture.entries().stream().noneMatch(entry -> entry.message().contains("UNIVERSE_RESPAWN") && entry.message().contains(player)),
                        "RESPAWN_CORRIDOR_NO_LOG：非 U 世界不得記 Task R log");
            }
            near(context,target,canonical,canonical.getSpawnPos(),server.getSpawnRadius(canonical),"RESPAWN_CORRIDOR_VANILLA");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void two_players_in_two_universes_stay_in_their_own(TestContext context) {
        var server=context.getWorld().getServer(); var both=universes(server);
        try(var first=playerIn(context,both.first(),new Vec3d(2000.5,200,1500.5));
                var second=playerIn(context,both.second(),new Vec3d(2000.5,200,1500.5))) {
            near(context,first.player().getRespawnTarget(true,TeleportTarget.NO_OP),both.first(),both.first().getSpawnPos(),
                    server.getSpawnRadius(both.first()),"RESPAWN_TWO_PLAYERS_FIRST");
            near(context,second.player().getRespawnTarget(true,TeleportTarget.NO_OP),both.second(),both.second().getSpawnPos(),
                    server.getSpawnRadius(both.second()),"RESPAWN_TWO_PLAYERS_SECOND");
        }
        context.complete();
    }

    @GameTest(templateName="quantumchamber:m1_empty", batchId="m6_universe_respawn", tickLimit=1200)
    public void respawn_player_after_universe_death_stays_in_same_universe(TestContext context) {
        var server=context.getWorld().getServer(); var u=universes(server).first();
        try(var connection=playerIn(context,u,new Vec3d(2100.5,200,1500.5))) {
            ServerPlayerEntity fresh=server.getPlayerManager().respawnPlayer(connection.player(),false,Entity.RemovalReason.KILLED);
            try {
                context.assertTrue(fresh.getServerWorld()==u,"RESPAWN_PLAYER_SAME_UNIVERSE：原版 respawnPlayer 後應在同一 U，實際 "
                        +fresh.getServerWorld().getRegistryKey().getValue());
            } finally {
                // respawnPlayer 建立同 UUID 的新 entity；原 close() 不會移除它，這裡先移除，close() 只釋放 channel。
                if(server.getPlayerManager().getPlayer(fresh.getUuid())==fresh) server.getPlayerManager().remove(fresh);
            }
        }
        context.complete();
    }
}

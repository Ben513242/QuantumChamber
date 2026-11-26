package dev.quantumchamber.corridor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.quantumchamber.chamber.ChamberInstanceKind;
import dev.quantumchamber.chamber.ChamberOriginAuthority;
import dev.quantumchamber.corridor.CorridorPageManager.HomeAttach;
import dev.quantumchamber.persistence.HomeSessionRecord;
import dev.quantumchamber.persistence.HomeSessionRecord.Close;
import dev.quantumchamber.persistence.HomeSessionRecord.CloseReason;
import dev.quantumchamber.persistence.HomeSessionRecord.DoorGoal;
import dev.quantumchamber.persistence.HomeSessionRecord.EntryEndpoint;
import dev.quantumchamber.persistence.HomeSessionRecord.Home;
import dev.quantumchamber.persistence.HomeSessionRecord.None;
import dev.quantumchamber.persistence.HomeSessionRecord.RootReason;
import dev.quantumchamber.persistence.HomeSessionRecord.RootStop;
import dev.quantumchamber.persistence.HomeSessionRecord.Seal;
import dev.quantumchamber.persistence.HomeSessionRecord.Selected;
import dev.quantumchamber.persistence.HomeSessionRecord.Unselected;
import dev.quantumchamber.persistence.SessionRecoveryRecord.Participant;
import dev.quantumchamber.persistence.SessionRecoveryRecord.SpaceLease;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.UniverseId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockBox;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

/**
 * M7 T5-5：開服接手（attach）的決策純函式 {@link CorridorPageManager#homeAttachAction}（設計稿第 2 節的動作表）。
 * <p>C9-b「attach 把選定的 MEASURED 轉 RETURNING」在執行期有兩道結構性防線（{@code homeReturning} 對選定列直接 return、journal 的 edge 不允許
 * MEASURED→RETURNING），整合層等價，只有這裡能確定殺掉；同理 C9 的「沒有返還工作的列也取 B 票」。只用無參數的 {@code @Test}（m7-ci-gametest 的宣告限制）。
 */
class M7HomeAttachActionTest {
    private static final UUID SID = UUID.fromString("00000000-0000-0000-0000-0000000005a1");
    private static final UUID CHAMBER = UUID.fromString("00000000-0000-0000-0000-0000000005c1");
    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-0000000005b1");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-0000000005b2");
    private static final UniverseId UNIVERSE = UniverseId.of(UUID.fromString("00000000-0000-0000-0000-0000000005e1"));
    private static final BlockPos POS = new BlockPos(8, 70, 8);
    private static final List<SpaceLease> LEASES = List.of(new SpaceLease(0, new BlockBox(610000, 64, 610000, 610006, 70, 610006)));
    private static final long SELECTED_AT = 100, COMMITTED_AT = 150, STOPPED_AT = 120;

    @Test void unselectedArmingAndSuperpositionReturnWithTicketAndSpace() {
        for (var state : List.of(SessionState.ARMING, SessionState.SUPERPOSITION))
            assertEquals(HomeAttach.RETURN_TICKET_AND_SPACE, CorridorPageManager.homeAttachAction(unselected(state)),
                    "ATTACH_UNSELECTED_NOT_RETURNING：未選定的 " + state + " 開服後要取 B 票、重建 space 並轉 RETURNING");
    }

    @Test void returningTakesTicketAndSpaceWithoutRewrite() {
        assertEquals(HomeAttach.TICKET_AND_SPACE, CorridorPageManager.homeAttachAction(unselected(SessionState.RETURNING)),
                "ATTACH_RETURNING_ACTION：磁碟上已是 RETURNING 的未選定列只取 B 票與重建 space，不再寫 RETURNING");
    }

    @Test void selectedRowsWithLeasesKeepSelection() {
        var rows = List.of(selected(SessionState.MEASURED, List.of(person(FIRST, false)), LEASES, Optional.empty(), -1),
                selected(SessionState.MEASURED, List.of(person(FIRST, false)), LEASES, Optional.of(RootReason.ROOT_DISABLED), STOPPED_AT),
                selected(SessionState.MEASURED, List.of(person(FIRST, true)), LEASES, Optional.empty(), -1),
                selected(SessionState.COLLAPSING, List.of(person(FIRST, true), person(SECOND, false)), LEASES, Optional.empty(), -1),
                selected(SessionState.COLLAPSING, List.of(person(FIRST, true), person(SECOND, true)), LEASES, Optional.empty(), -1));
        for (var row : rows)
            assertEquals(HomeAttach.TICKET_AND_SPACE, CorridorPageManager.homeAttachAction(row),
                    "ATTACH_SELECTED_TO_RETURNING：選定、租約非空的列保留選擇（取 B 票、重建 space、絕不轉 RETURNING）：" + row.state() + " " + row.participants());
    }

    @Test void settledCollapsingKeepsTicketWithoutSpace() {
        assertEquals(HomeAttach.TICKET_ONLY, CorridorPageManager.homeAttachAction(
                        selected(SessionState.COLLAPSING, List.of(person(FIRST, true), person(SECOND, true)), List.of(), Optional.empty(), -1)),
                "ATTACH_COLLAPSING_TICKET：已清理的 COLLAPSING 持 B 來源票（交 T6 的 PREPARE 原地接手），不重建 space");
    }

    @Test void rowsWithoutReturnWorkTakeNothing() {
        var returned = List.of(person(FIRST, true));
        var rows = new java.util.ArrayList<HomeSessionRecord>();
        rows.add(selected(SessionState.MEASURED, returned, List.of(), Optional.empty(), -1));
        rows.add(selected(SessionState.MEASURED, returned, List.of(), Optional.of(RootReason.ROOT_DISABLED), STOPPED_AT));
        rows.add(row(SessionState.PASSAGE_READY, returned, List.of(), home(SessionState.PASSAGE_READY, 200, List.of(), Optional.empty(), -1, new None(), Optional.empty())));
        for (var seal : Seal.values()) {
            boolean opened = seal == Seal.TRANSFER_RECOVERY_REQUIRED || seal == Seal.AUTHORITY_UNREADABLE;
            rows.add(row(SessionState.SEALED, returned, List.of(), home(SessionState.SEALED, opened ? 200 : -1,
                    seal == Seal.TRANSFER_RECOVERY_REQUIRED ? List.of(FIRST) : List.of(), Optional.empty(), -1, new None(), Optional.of(seal))));
        }
        var close = new Close(CloseReason.ROOT_DISABLED, 300, DoorGoal.OPEN, new RootStop(RootReason.ROOT_DISABLED, STOPPED_AT));
        rows.add(row(SessionState.MEASURED, returned, List.of(), home(SessionState.MEASURED, -1, List.of(), Optional.of(RootReason.ROOT_DISABLED), STOPPED_AT, close, Optional.empty())));
        // CloseIntent 優先：已清理的 COLLAPSING 帶 CLOSE 時也不取票（否則會是 TICKET_ONLY）。
        rows.add(row(SessionState.COLLAPSING, returned, List.of(), home(SessionState.COLLAPSING, -1, List.of(), Optional.of(RootReason.ROOT_DISABLED), STOPPED_AT, close, Optional.empty())));
        for (var row : rows)
            assertEquals(HomeAttach.NONE, CorridorPageManager.homeAttachAction(row),
                    "ATTACH_TICKET_WITHOUT_RETURN_WORK：沒有返還工作的列（DORMANT、PASSAGE_READY、SEALED、CLOSE）不取 B 票、不建 space：" + row.state() + " " + row.home().sealedReason()
                            + " " + row.home().closeIntent());
    }

    // ---- fixture ----

    private static Participant person(UUID id, boolean returned) {
        return new Participant(id, Vec3d.ZERO, Vec3d.ZERO, 0, 0, new NbtCompound(), returned);
    }

    private static HomeSessionRecord unselected(SessionState state) {
        return row(state, List.of(person(FIRST, false)), LEASES,
                new Home(new Unselected(), -1, -1, -1, List.of(), List.of(), Optional.empty(), -1, new None(), Optional.empty()));
    }

    private static HomeSessionRecord selected(SessionState state, List<Participant> people, List<SpaceLease> leases, Optional<RootReason> stop, long stoppedAt) {
        return row(state, people, leases, home(state, -1, List.of(), stop, stoppedAt, new None(), Optional.empty()));
    }

    /** 選定之後的 Home：COLLAPSING／PASSAGE_READY／SEALED 帶提交時間；{@code opened>=0} 時帶通道時間（到期＝開啟＋1200）。 */
    private static Home home(SessionState state, long opened, List<UUID> recovery, Optional<RootReason> stop, long stoppedAt,
            HomeSessionRecord.CloseIntent close, Optional<Seal> seal) {
        boolean committed = state == SessionState.COLLAPSING || state == SessionState.PASSAGE_READY || state == SessionState.SEALED;
        return new Home(new Selected(HomeDoorLayout.key(SID), FIRST, SELECTED_AT), committed ? COMMITTED_AT : -1, opened, opened >= 0 ? opened + 1200 : -1,
                List.of(), recovery, stop, stoppedAt, close, seal);
    }

    private static HomeSessionRecord row(SessionState state, List<Participant> people, List<SpaceLease> leases, Home home) {
        var root = new ChamberOriginAuthority(CHAMBER, World.OVERWORLD.getValue(), DimensionRole.OVERWORLD, POS, Direction.NORTH, ChamberInstanceKind.ORIGIN);
        var entry = new EntryEndpoint(CHAMBER, UNIVERSE, Identifier.of("quantumchamber", "universe/" + UNIVERSE.value() + "/overworld"), DimensionRole.OVERWORLD,
                POS, Direction.NORTH, ChamberInstanceKind.PROJECTION);
        return new HomeSessionRecord(SID, CHAMBER, root, entry, people, leases, state, home);
    }
}

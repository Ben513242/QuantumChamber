package dev.quantumchamber.persistence;

import dev.quantumchamber.collapse.CollapseDestination;
import dev.quantumchamber.collapse.SealedReason;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * schema 4 record 的 {@code Collapse} compound（spec §5）。ARMING、SUPERPOSITION、RETURNING、MEASURED 一律是 {@link #EMPTY}
 * （NBT 為空 compound）；三個塌縮狀態帶完整欄位。這裡只檢查欄位本身的形狀，哪個狀態允許哪些值由 {@link SessionRecoveryRecord} 驗證。
 *
 * @param destination 只有 {@link #EMPTY} 為 null；其餘一律非 null，而且一經寫入不可改（{@link SessionRecoveryRecord#sameAuthority}）
 * @param crossed 已成功完成 passage 傳送的參與者，依跨越順序只附加不移除
 * @param recoveryRequired 傳送無法回滾的參與者；只有 SEALED(TRANSFER_RECOVERY_REQUIRED) 恰有一筆
 */
public record CollapseFields(int revision, long committedAtGameTime, CollapseDestination destination,
        long passageOpenedAtGameTime, long passageExpiresAtGameTime, List<UUID> crossed,
        List<UUID> recoveryRequired, Optional<SealedReason> sealedReason) {
    /** {@code CollapseRevision} 固定為 1。 */
    public static final int REVISION = 1;
    /** 通道開啟的長度：expires＝opened＋1200 ticks（60 秒）。 */
    public static final long PASSAGE_TICKS = 1200;
    /** opened／expires 未寫入時的值。 */
    public static final long UNSET = -1;
    /** M4 狀態的空 Collapse。 */
    public static final CollapseFields EMPTY = new CollapseFields(0, UNSET, null, UNSET, UNSET, List.of(), List.of(), Optional.empty());

    public CollapseFields {
        crossed = List.copyOf(crossed);
        recoveryRequired = List.copyOf(recoveryRequired);
        Objects.requireNonNull(sealedReason, "sealedReason");
        if (new HashSet<>(crossed).size() != crossed.size() || new HashSet<>(recoveryRequired).size() != recoveryRequired.size()) {
            throw new IllegalArgumentException("Crossed 與 RecoveryRequired 不得有重複 UUID");
        }
        if (destination == null) {
            if (revision != 0 || committedAtGameTime != UNSET || passageOpenedAtGameTime != UNSET || passageExpiresAtGameTime != UNSET
                    || !crossed.isEmpty() || !recoveryRequired.isEmpty() || sealedReason.isPresent()) {
                throw new IllegalArgumentException("沒有 Destination 的 Collapse 只能是 EMPTY");
            }
        } else {
            if (revision != REVISION) throw new IllegalArgumentException("CollapseRevision 必須為 1");
            if (committedAtGameTime < 0) throw new IllegalArgumentException("CommittedAtGameTime 不得為負");
            boolean unset = passageOpenedAtGameTime == UNSET && passageExpiresAtGameTime == UNSET;
            boolean opened = passageOpenedAtGameTime >= 0 && passageOpenedAtGameTime <= Long.MAX_VALUE - PASSAGE_TICKS
                    && passageExpiresAtGameTime == passageOpenedAtGameTime + PASSAGE_TICKS;
            if (!unset && !opened) throw new IllegalArgumentException("通道時間必須同為 -1，或 expires＝opened＋1200");
        }
    }

    public boolean isEmpty() { return destination == null; }

    public boolean passageOpened() { return passageOpenedAtGameTime != UNSET; }

    /** MEASURED（或 DORMANT）→COLLAPSING 提交時的初始值。 */
    public static CollapseFields committed(long committedAtGameTime, CollapseDestination destination) {
        return new CollapseFields(REVISION, committedAtGameTime, Objects.requireNonNull(destination, "destination"),
                UNSET, UNSET, List.of(), List.of(), Optional.empty());
    }

    /** COLLAPSING→PASSAGE_READY：opened／expires 只在這裡寫入一次。 */
    public CollapseFields withPassage(long openedAtGameTime) {
        return new CollapseFields(revision, committedAtGameTime, destination, openedAtGameTime, openedAtGameTime + PASSAGE_TICKS,
                crossed, recoveryRequired, sealedReason);
    }

    /** PASSAGE_READY 的進度：把成功跨越的參與者附加到 Crossed 尾端。 */
    public CollapseFields withCrossed(UUID player) {
        var next = new ArrayList<>(crossed); next.add(Objects.requireNonNull(player, "player"));
        return new CollapseFields(revision, committedAtGameTime, destination, passageOpenedAtGameTime, passageExpiresAtGameTime,
                next, recoveryRequired, sealedReason);
    }

    /** COLLAPSING→SEALED。TRANSFER_RECOVERY_REQUIRED 必須經 {@link #transferRecoveryRequired}。 */
    public CollapseFields sealed(SealedReason reason) {
        if (Objects.requireNonNull(reason, "reason") == SealedReason.TRANSFER_RECOVERY_REQUIRED) {
            throw new IllegalArgumentException("TRANSFER_RECOVERY_REQUIRED 必須帶 RecoveryRequired");
        }
        return new CollapseFields(revision, committedAtGameTime, destination, passageOpenedAtGameTime, passageExpiresAtGameTime,
                crossed, recoveryRequired, Optional.of(reason));
    }

    /** PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)：保留 opened／expires 與 Crossed，RecoveryRequired 寫入那位玩家。 */
    public CollapseFields transferRecoveryRequired(UUID player) {
        return new CollapseFields(revision, committedAtGameTime, destination, passageOpenedAtGameTime, passageExpiresAtGameTime,
                crossed, List.of(Objects.requireNonNull(player, "player")), Optional.of(SealedReason.TRANSFER_RECOVERY_REQUIRED));
    }

    /** SEALED→COLLAPSING 重試：只清空 SealedReason。 */
    public CollapseFields retry() {
        return new CollapseFields(revision, committedAtGameTime, destination, passageOpenedAtGameTime, passageExpiresAtGameTime,
                crossed, recoveryRequired, Optional.empty());
    }
}

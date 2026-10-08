package dev.quantumchamber.collapse;

import static dev.quantumchamber.collapse.CollapseService.LOG_MARKER;

import dev.quantumchamber.candidate.QuantumCandidate;
import dev.quantumchamber.chamber.ChamberCollapsePort;
import dev.quantumchamber.chamber.ChamberFrame;
import dev.quantumchamber.chamber.ChamberSpaceCoordinates;
import dev.quantumchamber.collapse.CollapseService.SourceSignal;
import dev.quantumchamber.collapse.CollapseService.Track;
import dev.quantumchamber.persistence.FailureText;
import dev.quantumchamber.persistence.SessionRecoveryRecord;
import dev.quantumchamber.persistence.SessionRecoveryState;
import dev.quantumchamber.persistence.SessionRecoveryState.TerminalOrigin;
import dev.quantumchamber.superposition.SessionState;
import dev.quantumchamber.transfer.SessionTransferService;
import dev.quantumchamber.universe.DimensionRole;
import dev.quantumchamber.universe.DynamicDimensionBackend;
import dev.quantumchamber.universe.MaterializeResult;
import dev.quantumchamber.universe.UniverseId;
import dev.quantumchamber.universe.UniverseKeys;
import dev.quantumchamber.universe.UniverseLifecycleService;
import dev.quantumchamber.universe.UniverseRegistryState;
import dev.quantumchamber.universe.UniverseTransferResult;
import dev.quantumchamber.universe.UniverseTransferService;
import dev.quantumchamber.universe.UniverseWorldDescriptor;
import dev.quantumchamber.universe.UnloadResult;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 量子通道（PASSAGE_READY；spec §10.3–§10.6、§10.8，F5-1；到達提示與「量子通道已關閉」的對象，F5-3）。每個 server instance 一個，由 {@link CollapseService} 建立並持有；只在 server thread 上、經它的 tick
 * （{@link #tick}，執行期間提交的 PASSAGE_READY；開服時就是 PASSAGE_READY 的由 {@code CollapseService} 依 §12 直接終局）或原艙門的攔截（{@link #endByParticipant}）呼叫，
 * 共用它每個 tick 取得的 context（journal、session manager、server tick）與每個 session 的簿記（{@link CollapseService.Track}），比照 {@link OriginDoors}。
 * <ul>
 * <li><b>只收斂</b>（§7 開頭）：roll-forward 尚未確認時，寫出由 recovery 的 RETRY_FLUSH 以 journal 為單位帶退避重試，這裡只收斂。兩種寫入確認之後的走向不同
 *     （F5-1 fix round 1，quality Q-m4）：
 *     <ul>
 *     <li>加入 {@code Crossed}：collapse view 的 Crossed 已含那位玩家；不跨越、不傳送、不評估結束條件、不終局（參與者關門由 {@link OriginDoors#doorAction} 拒絕）。確認之後通道
 *         恢復，這時若已到期，依 (c) 結束。</li>
 *     <li>PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)：collapse view 已是 SEALED，通道已關閉（§10.3 :571：門依 §11.2、目的世界的 ticket 已釋放、presence 為 COLLAPSE）；
 *         flushed 仍是 PASSAGE_READY 的期間這裡只收斂（{@code step} 依 flushed 交給這裡）。確認之後 flushed 是 SEALED，由 {@code CollapseService} 的 SEALED 分支處理（TRR 是
 *         永久原因，不重試），通道不會恢復。</li>
 *     </ul></li>
 * <li><b>結束條件</b>（§10.6，{@link #endCondition}）：(b) 全員都在 Crossed 中；(c) game time 已到 {@code PassageExpiresAtGameTime}；(d) 來源艙沒有供電、Origin 被停用（含 registry
 *     讀不到）或結構失效，依即時訊號（{@link CollapseService#sourceSignal}：Controller 的紅石輸入、registry 的 enabled、結構驗證），不經 {@code ChamberPowerCoordinator.returnToOrigin}。
 *     (a) 參與者在原艙這一側開關原艙門（bulkhead 或 Controller）由攔截處理（{@link #endByParticipant}）。結束的順序：checked 終局（{@code PASSAGE_ENDED}）→ 釋放 runtime、
 *     來源 ticket 與目的世界的 ticket（{@link CollapseService#terminated}：之後 presence 才是 NONE，終局之後的門才不會被當成新 session 的門而放棄）→ 門：(a) 改為關閉（門口有
 *     entity 時一般開門），(b)(c)(d) 一般開門 → 「量子通道已關閉」（只對在線、而且不在結束時那筆 record 的 Crossed 中的參與者，{@link #notifyNotCrossed}；spec §20.9）
 *     → 刷新 Controller（原艙解除封鎖；尚未處理的 LOW 由 {@code reconcile} 接著走一般的 OFF 流程）。全部記 INFO。
 *     終局沒有完成時記 WARN、per-session 退避，不改門，也不再跨越（結束條件仍成立）。</li>
 * <li><b>跨越</b>（§10.3，{@link #crosses}）：每個 tick 逐一檢查參與者，七個條件全部成立才觸發：參與者、還不在 Crossed；在線、exact 的玩家 instance、存活、非旁觀；
 *     位在 exact 的來源 world instance；沒有騎乘；沒有被其他 session 佔用（§5）；腳在門口（local x∈[1,6)、y∈[1,6)）；local z 已越過門板中線往外（z&lt;0.5）而上一個 tick
 *     還在中線內側（z≥0.5；只認上一個 tick 的觀察，更舊的不算，{@link #previousTick}，fix round 1）。觸發之後以 {@link PassageTransferPolicy} 傳送到目的世界的同 XYZ、yaw、pitch 與速度（{@code UniverseTransferService} 的 plan 決策 4 多載）。
 *     非參與者不在檢查的對象裡：穿過量子膜只會走進原世界。</li>
 * <li><b>傳送結果</b>（§10.3 的表）：成功（MOVED）→ checked（roll-forward）加入 Crossed，之後對到達的那位玩家以 title 顯示到達提示（NEW「你已進入新的宇宙。」、EXISTING
 *     「你已進入另一個宇宙。」；先送淡入、停留、淡出 10／70／20；§10.8、§20.8、§20.9，{@link #arrived}）——寫入確認、roll-forward 未確認、被拒時都顯示，因為他都已在目的世界；
 *     {@code FAILED_ROLLED_BACK}（以及移動之前就被拒的 {@code REJECTED_BEFORE_MOVE}，玩家同樣留在原世界；這些與下一種都不顯示到達提示）→ actionbar
 *     「通道傳送失敗，你仍在原世界。」、依原因去重記一次 WARN，通道仍開著，可以再試；{@code FAILED_RECOVERY_REQUIRED} → 不移除 record，checked
 *     （roll-forward）PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)、RecoveryRequired 記下那位玩家（保留 participants、Crossed、opened／expires 與收據），記 ERROR（含移動與
 *     回滾的證據），之後與其他原因進入 SEALED 相同（{@link CollapseService#sealCommitted}：門依 §11.2、釋放目的世界的 ticket）；不再自動移動他，也不重試。Crossed 的寫入沒有
 *     確認時，這個 tick 其他參與者不傳送（§7）。</li>
 * </ul>
 * 訊息的對象（§10.8）：「量子通道已關閉」只顯示給在線、而且不在結束時那筆 record 的 Crossed 中的參與者（v5.5，§20.9；不論他在原艙或別處）。已跨過的人不再收到：(b) 在最後
 * 一位跨過的下一個 tick 結束，兩則都在 actionbar 時會蓋掉他的到達提示（F5-1 原本對每一位在線的參與者顯示）。「進入 SEALED」仍對所有參與者顯示（含
 * PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)，§20.9「不受影響的部分」）；「量子通道已開啟」只對艙內的參與者（§10.2 第 3 步）。這裡的每一則 log 都帶
 * {@link CollapseService#LOG_MARKER}，能歸屬到 session 的 WARN／ERROR 帶 {@code session=}。
 */
final class PassageService {
    private static final Logger LOGGER = LoggerFactory.getLogger("quantumchamber");
    /** §10.3：門板中線的 local z（門板那一格的中央）。 */
    static final double MIDLINE = 0.5;
    /** spec §10.8「跨越成功、到達目的宇宙」（§20.8、§20.9，F5-3）：候選是 NEW 時的到達提示，以 title 只對到達的那位玩家顯示（{@link #arrived}）。 */
    static final String MESSAGE_ARRIVED_NEW = "message.quantumchamber.m5.arrived_new";
    /** 同 {@link #MESSAGE_ARRIVED_NEW}，候選是 EXISTING 時（重逢到既有的宇宙）。 */
    static final String MESSAGE_ARRIVED_EXISTING = "message.quantumchamber.m5.arrived_existing";
    /** spec §20.9：到達提示的淡入、停留、淡出（ticks），原版的預設值；送 title 之前先明確送出，不沿用 client 先前的設定。 */
    static final int ARRIVAL_FADE_IN = 10, ARRIVAL_STAY = 70, ARRIVAL_FADE_OUT = 20;
    /** {@link CollapseService#warn} 的寫入種類：傳送沒有完成、玩家仍在原世界（成功時清除）；Crossed 的寫入；TRANSFER_RECOVERY_REQUIRED 的寫入。 */
    private static final String TRANSFER = "通道傳送", CROSSED_WRITE = "寫入 Crossed", RECOVERY_WRITE = "寫入 SEALED(TRANSFER_RECOVERY_REQUIRED)";
    private final CollapseService service;
    /** 原生移動（{@code UniverseTransferService} 的 NativeMove）；它自己以 entity 去重原生例外的 WARN。 */
    private final SessionTransferService transfers = new SessionTransferService();

    PassageService(CollapseService service) { this.service = Objects.requireNonNull(service, "service"); }

    // ---- 每個 tick ----

    /** 執行期間提交的 PASSAGE_READY 的一個 tick（{@code record} 是 flushed）。例外由呼叫端（{@code CollapseService.step}）依 session 隔離。 */
    void tick(SessionRecoveryRecord record) {
        var sid = record.sessionUuid();
        var track = service.track(sid);
        var previous = observe(record, track);
        if (service.journal.rollForwardPending(sid)) return;
        if (!record.equals(service.journal.records().get(sid))) return;
        if (track.pendingRecovery != null) {
            retryRecovery(record, track);
            return;
        }
        var signal = service.sourceSignal(record);
        var end = endCondition(record.collapse().crossed().containsAll(participantIds(record)), service.server.getOverworld().getTime(),
                record.collapse().passageExpiresAtGameTime(), signal);
        if (end.isPresent()) {
            finish(record, track, end.get());
            return;
        }
        if (signal == SourceSignal.NOT_LOADED) return;
        cross(record, track, previous);
    }

    /**
     * 記下每位參與者這個 tick（{@code service.now}）的腳在來源艙的 local z（不在來源世界、或不是 exact 的在線玩家時移除），回傳上一個 tick（now−1）記下的值
     * （{@link #previousTick}）。只讀世界、只寫記憶體，所以 roll-forward 未確認、結束條件成立的 tick 也照做。
     * <p>這個 session 的推進被略過的 tick 不會觀察：journal 有未 checked 的 authority（{@code CollapseService.tick} 整個略過）、隔離中、走廊操作進行中、推進在觀察之前丟出例外，
     * 或整個 tick 無法執行。這時記下的值比上一個 tick 更舊，期間玩家可能已由中線內側走到外側（z 沒有下限，可能已離門口一段距離）。§10.3 的「上一個 tick 還在中線內側」只認
     * now−1 的觀察（F5-1 fix round 1，spec review S-m1）：恢復之後的第一個 tick 不會因為更舊的觀察而觸發，這個 tick 的觀察成為下一個 tick 的「上一個 tick」。
     */
    private Map<UUID, Double> observe(SessionRecoveryRecord record, Track track) {
        var previous = previousTick(track.passageZ, service.now);
        var world = sourceWorld(record);
        var frame = frame(record);
        for (var person : record.participants()) {
            var id = person.playerUuid();
            var player = online(id);
            if (player != null && inSourceWorld(player, world)) {
                track.passageZ.put(id, new ObservedZ(ChamberSpaceCoordinates.localPosition(frame, player.getPos()).z, service.now));
            } else {
                track.passageZ.remove(id);
            }
        }
        return previous;
    }

    /** 一位參與者的腳在來源艙的 local z，與觀察到它的 server tick（{@link Track#passageZ}；F5-1 fix round 1，S-m1）。 */
    record ObservedZ(double z, int tick) { }

    /**
     * 上一個 tick（{@code now − 1}）記下的 local z（純函式，F5-1 fix round 1，spec review S-m1）：更早的觀察不算（那段期間這個 session 的推進被略過，看不到中線的穿越）。
     */
    static Map<UUID, Double> previousTick(Map<UUID, ObservedZ> observed, int now) {
        var result = new HashMap<UUID, Double>();
        observed.forEach((id, seen) -> { if (seen.tick() == now - 1) result.put(id, seen.z()); });
        return result;
    }

    /** §10.6 的結束原因（(a) 由攔截觸發，其餘由 {@link #endCondition}）。{@code text} 是 INFO 的字句。 */
    enum PassageEnd {
        PARTICIPANT_CLOSED("(a) 參與者在原艙這一側開關原艙門"),
        ALL_CROSSED("(b) 全員都已跨過"),
        EXPIRED("(c) game time 已到 PassageExpiresAtGameTime"),
        SOURCE_LOW("(d) 來源艙沒有供電"),
        /** registry 讀不到時也是這一個（{@link CollapseService#sourceSignal} 歸為 DISABLED；F5-1 fix round 1，quality Q-m4 補上字句）。 */
        SOURCE_DISABLED("(d) Origin 被停用或 registry 讀不到"),
        SOURCE_INVALID("(d) 結構失效");

        final String text;

        PassageEnd(String text) { this.text = text; }
    }

    /**
     * §10.6 (b)(c)(d)（純函式）：全員都在 Crossed 中、game time 已到 expires、來源艙的即時訊號是 LOW／DISABLED／INVALID 時結束；同時成立時依 (b)(c)(d) 的順序回報一個。
     * 來源艙沒有載入（{@code NOT_LOADED}）時無法判斷 (d)，不結束（(b)(c) 照常）。
     */
    static Optional<PassageEnd> endCondition(boolean allCrossed, long gameTime, long expiresAt, SourceSignal signal) {
        Objects.requireNonNull(signal, "signal");
        if (allCrossed) return Optional.of(PassageEnd.ALL_CROSSED);
        if (gameTime >= expiresAt) return Optional.of(PassageEnd.EXPIRED);
        return switch (signal) {
            case LOW -> Optional.of(PassageEnd.SOURCE_LOW);
            case DISABLED -> Optional.of(PassageEnd.SOURCE_DISABLED);
            case INVALID -> Optional.of(PassageEnd.SOURCE_INVALID);
            case OK, NOT_LOADED -> Optional.empty();
        };
    }

    /** (b)(c)(d)：checked 終局（restore 類；沒有完成時退避、不改門），之後依 {@link #closed} 的順序。 */
    private void finish(SessionRecoveryRecord record, Track track, PassageEnd end) {
        if (!track.backoff.ready(service.now)) return;
        var result = service.journal.terminate(service.server, record, TerminalOrigin.PASSAGE_ENDED);
        if (!result.removed()) {
            service.terminalFailed(record, track, "通道結束的終局（" + end + "）", result);
            return;
        }
        closed(record, end, OriginDoors.DoorGoal.OPEN_AFTER_TERMINAL);
    }

    /**
     * 終局已讀回確認之後：釋放 runtime、來源 ticket 與目的世界的 ticket（{@link CollapseService#terminated}）→ INFO → 門（{@code door}）→「量子通道已關閉」（只對還沒跨過的參與者，
     * {@link #notifyNotCrossed}）→ 刷新 Controller。
     */
    private void closed(SessionRecoveryRecord record, PassageEnd end, OriginDoors.DoorGoal door) {
        service.terminated(record);
        LOGGER.info(LOG_MARKER, "量子通道已結束（正常結束：{}）：已 checked 終局，原艙門{}，原艙解除封鎖：session={} chamber={} 已跨過 {}/{}", end.text,
                door == OriginDoors.DoorGoal.CLOSE_AFTER_TERMINAL ? "改為關閉（門口有 entity 時一般開門）" : "改為一般開門", record.sessionUuid(), record.chamberUuid(),
                record.collapse().crossed().size(), record.participants().size());
        service.originDoors.queueDoor(record, 0, door);
        notifyNotCrossed(record, CollapseService.MESSAGE_PASSAGE_CLOSED);
        service.refresh(record);
    }

    /**
     * 對在線、而且不在 {@code record}（結束時那筆）的 Crossed 中的參與者顯示 actionbar（spec §10.8「通道結束」、§20.9 第 2 點；在線的判斷與
     * {@link CollapseService#notifyParticipants} 相同，{@link #online}）。已跨過的人不再收到：(b) 在最後一位跨過的下一個 tick 結束，這則不會接在他的到達提示之後；先跨過的人也不會
     * 因之後的 (a)(c)(d) 再收到。Crossed 的寫入被拒、沒有記下的人已在目的世界，但不在 Crossed 中，所以仍會收到（到達提示是 title，這則是 actionbar，不會互相蓋掉）。
     */
    private void notifyNotCrossed(SessionRecoveryRecord record, String key) {
        var crossed = record.collapse().crossed();
        for (var person : record.participants()) {
            if (crossed.contains(person.playerUuid())) continue;
            var player = online(person.playerUuid());
            if (player != null) player.sendMessage(Text.translatable(key), true);
        }
    }

    /**
     * (a)：通道期間參與者在原艙這一側開關原艙門（{@link OriginDoors#door} 的 {@code END_PASSAGE}；roll-forward 尚未確認時已在 {@link OriginDoors#doorAction} 拒絕）。先 checked
     * 終局、再改門（關閉；門口有 entity 時一般開門）。寫入前的條件與終局沒有完成時的處置見 {@link OriginDoors#terminateByDoor}；另外通道不得在記憶體內關閉
     * （{@link Track#pendingRecovery}），否則拒絕、不改門。F5-2（n-2）起這段期間的開關門在 {@link OriginDoors#doorAction} 就照一般門的邏輯（PASS），不會走到這裡；這個檢查保留作防禦。
     */
    ChamberCollapsePort.DoorUse endByParticipant(SessionRecoveryRecord view, Track known, PlayerEntity player) {
        var track = known != null ? known : service.track(view.sessionUuid());
        var blocked = track.pendingRecovery != null ? "通道傳送無法回滾的封存還沒寫入（通道在記憶體內已關閉）" : null;
        if (!service.originDoors.terminateByDoor(view, track, player, TerminalOrigin.PASSAGE_ENDED, "通道期間", blocked)) return ChamberCollapsePort.DoorUse.REFUSED;
        closed(view, PassageEnd.PARTICIPANT_CLOSED, OriginDoors.DoorGoal.CLOSE_AFTER_TERMINAL);
        return ChamberCollapsePort.DoorUse.HANDLED;
    }

    // ---- 跨越與傳送（§10.3、§10.4） ----

    /**
     * 跨越判定的輸入（{@link #crosses}）：{@code local} 是腳這個 tick 在來源艙的 local 座標（觀察不到時為 null），{@code previousLocalZ} 是上一個 tick 的 local z（{@link #previousTick}；
     * 上一個 tick 沒有觀察時為 null）。以 {@link #builder} 依名稱設定（F5-1 fix round 1，quality Q-m7：9 個位置參數容易排錯）；沒有設定的欄位是 false 或 null。
     */
    record Candidate(boolean participant, boolean alreadyCrossed, boolean exactOnlineAlive, boolean spectator, boolean inSourceWorld, boolean riding,
            boolean occupiedElsewhere, Vec3d local, Double previousLocalZ) {
        static Builder builder() { return new Builder(); }

        /** 以這一份的每個欄位為起點的 builder（例如由全部成立的候選只改一個條件）。 */
        Builder toBuilder() {
            return builder().participant(participant).alreadyCrossed(alreadyCrossed).exactOnlineAlive(exactOnlineAlive).spectator(spectator).inSourceWorld(inSourceWorld)
                    .riding(riding).occupiedElsewhere(occupiedElsewhere).local(local).previousLocalZ(previousLocalZ);
        }

        /** {@link Candidate} 的具名設定。 */
        static final class Builder {
            private boolean participant, alreadyCrossed, exactOnlineAlive, spectator, inSourceWorld, riding, occupiedElsewhere;
            private Vec3d local;
            private Double previousLocalZ;

            private Builder() { }

            Builder participant(boolean value) { participant = value; return this; }
            Builder alreadyCrossed(boolean value) { alreadyCrossed = value; return this; }
            Builder exactOnlineAlive(boolean value) { exactOnlineAlive = value; return this; }
            Builder spectator(boolean value) { spectator = value; return this; }
            Builder inSourceWorld(boolean value) { inSourceWorld = value; return this; }
            Builder riding(boolean value) { riding = value; return this; }
            Builder occupiedElsewhere(boolean value) { occupiedElsewhere = value; return this; }
            Builder local(Vec3d value) { local = value; return this; }
            Builder previousLocalZ(Double value) { previousLocalZ = value; return this; }

            Candidate build() {
                return new Candidate(participant, alreadyCrossed, exactOnlineAlive, spectator, inSourceWorld, riding, occupiedElsewhere, local, previousLocalZ);
            }
        }
    }

    /** §10.3 的七個條件全部成立（純函式）。 */
    static boolean crosses(Candidate candidate) {
        return candidate.participant() && !candidate.alreadyCrossed() && candidate.exactOnlineAlive() && !candidate.spectator() && candidate.inSourceWorld()
                && !candidate.riding() && !candidate.occupiedElsewhere() && candidate.local() != null && inDoorway(candidate.local())
                && candidate.local().z < MIDLINE && candidate.previousLocalZ() != null && candidate.previousLocalZ() >= MIDLINE;
    }

    /** 腳在門口：local x∈[1,6)、y∈[1,6)（§10.3）。 */
    static boolean inDoorway(Vec3d local) {
        return local.x >= 1 && local.x < 6 && local.y >= 1 && local.y < 6;
    }

    /** 逐一檢查參與者；傳送成功就 checked 加入 Crossed，之後的參與者以新的 record 為寫入前提。Crossed 沒有確認或傳送無法回滾時，這個 tick 不再檢查其他參與者。 */
    private void cross(SessionRecoveryRecord record, Track track, Map<UUID, Double> previous) {
        var world = sourceWorld(record);
        if (world == null) return;
        var frame = frame(record);
        var current = record;
        for (var person : record.participants()) {
            var id = person.playerUuid();
            var player = online(id);
            boolean exact = player != null && player.isAlive();
            boolean inSource = exact && inSourceWorld(player, world);
            var candidate = Candidate.builder()
                    .participant(true)
                    .alreadyCrossed(current.collapse().crossed().contains(id))
                    .exactOnlineAlive(exact)
                    .spectator(exact && player.isSpectator())
                    .inSourceWorld(inSource)
                    .riding(exact && player.hasVehicle())
                    .occupiedElsewhere(!service.journal.participantsAvailable(List.of(id)))
                    .local(inSource ? ChamberSpaceCoordinates.localPosition(frame, player.getPos()) : null)
                    .previousLocalZ(previous.get(id))
                    .build();
            if (!crosses(candidate)) continue;
            var result = transfer(current, player);
            if (result == null) continue;
            switch (result.outcome()) {
                case MOVED -> {
                    var next = crossed(current, track, player);
                    // 到達提示排在 Crossed 的寫入之後：寫入的三種結果（確認、roll-forward 未確認、被拒）他都已在目的世界，都顯示（spec §20.9 的邊界情況）；提示不會妨礙寫入。
                    arrived(current, player);
                    if (next == null) return;
                    current = next;
                }
                case FAILED_RECOVERY_REQUIRED -> {
                    recoveryRequired(current, track, id, recoveryCause(id, result));
                    return;
                }
                case FAILED_ROLLED_BACK, REJECTED_BEFORE_MOVE, RETURNED -> transferFailed(current, player, result);
            }
        }
    }

    /**
     * 以 passage 的政策傳送（§10.4）：目的地是 flushed catalog 這個 UniverseId 的 OVERWORLD descriptor，exact ACTIVE world 由 lifecycle 提供（{@link LifecycleView}，
     * M5 不自建 backend，spec §6.3）。descriptor 讀不到時（catalog 不健康或沒有這筆，不應發生）不傳送：玩家仍在原世界，與傳送失敗相同地提示並記 WARN，回 null。
     */
    private UniverseTransferResult transfer(SessionRecoveryRecord record, ServerPlayerEntity player) {
        var id = ((CollapseDestination.Universe) record.collapse().destination()).id();
        UniverseWorldDescriptor descriptor;
        try {
            var universe = UniverseRegistryState.get(service.server).flushedRecords().get(id);
            descriptor = universe == null ? null : universe.definition().worlds().get(DimensionRole.OVERWORLD);
        } catch (IllegalStateException unhealthy) {
            notTransferred(record, player, unhealthy);
            return null;
        }
        if (descriptor == null) {
            notTransferred(record, player, new IllegalStateException("flushed catalog 沒有目的宇宙的 OVERWORLD descriptor：universe=" + id.value()));
            return null;
        }
        return new UniverseTransferService(new LifecycleView(id), transfers).moveToUniverse(player, descriptor, PassageTransferPolicy.INSTANCE);
    }

    /** 傳送成功：checked（roll-forward）把他加入 Crossed。回傳新的 record；沒有確認時回 null（§7：這個 tick 其他參與者不傳送）。 */
    private SessionRecoveryRecord crossed(SessionRecoveryRecord current, Track track, ServerPlayerEntity player) {
        var sid = current.sessionUuid();
        var id = player.getUuid();
        track.warnings.remove(TRANSFER);
        LOGGER.info(LOG_MARKER, "參與者已穿過量子通道，傳送到目的宇宙的同 XYZ、yaw、pitch 與速度：session={} chamber={} 玩家={} 目的地={} 位置={} yaw={} pitch={}", sid,
                current.chamberUuid(), id, CollapseService.describe(current.collapse().destination()), player.getPos(), player.getYaw(), player.getPitch());
        var next = current.withCollapse(SessionState.PASSAGE_READY, current.collapse().withCrossed(id));
        var commit = service.journal.commitRollForward(service.server, current, next);
        switch (commit.status()) {
            case COMMITTED -> {
                track.warnings.remove(CROSSED_WRITE);
                return next;
            }
            case ROLL_FORWARD_PENDING -> {
                service.warn(sid, CROSSED_WRITE, commit.failure().orElseThrow(), commit.status().name(), "Crossed 已依 collapse view 生效；寫出由 recovery 以 journal 為單位"
                        + "帶退避重試，確認之前不傳送其他參與者、不評估結束條件、參與者關門被拒：玩家=" + id);
                return null;
            }
            default -> {
                // journal 沒有改變（前置檢查被拒，不應發生）：他已在目的世界，不會再被傳送；通道之後依結束條件結束。
                service.warn(sid, CROSSED_WRITE, commit.failure().orElseThrow(), commit.status().name(), "玩家已在目的世界，但 Crossed 沒有記下；通道之後依結束條件結束：玩家=" + id);
                return null;
            }
        }
    }

    /**
     * 跨越成功（MOVED）之後，對到達的那位玩家以 title 顯示到達提示（spec §10.8、§20.8、§20.9）：先明確送出淡入、停留、淡出（{@link #ARRIVAL_FADE_IN}／{@link #ARRIVAL_STAY}／
     * {@link #ARRIVAL_FADE_OUT}，不沿用 client 先前的設定），再送 title；不送 subtitle、不用 actionbar，不顯示 Universe ID。只有 MOVED 走到這裡
     * （{@code FAILED_ROLLED_BACK}、{@code REJECTED_BEFORE_MOVE}、{@code RETURNED}、{@code FAILED_RECOVERY_REQUIRED} 與 descriptor 讀不到時玩家不在目的世界，不顯示）。
     * <p>選中的候選不是 NEW 或 EXISTING 時不顯示，記一則 WARN：不應發生——PASSAGE_READY 的 record 建構時就要求 Destination 是 UNIVERSE、而且對應被選中的候選，只有 EXISTING
     * 與 NEW 對得上（{@code SessionRecoveryRecord} 的驗證）。不丟例外：玩家已在目的世界、Crossed 的寫入也已處理，丟出只會讓這個 tick 之後的參與者不被檢查（例外由
     * {@code CollapseService.step} 依 session 隔離）。也不改用其中一則：錯的字面（「新的」或「另一個」）比沒有提示更誤導。
     */
    private void arrived(SessionRecoveryRecord record, ServerPlayerEntity player) {
        var key = arrivalKey(record.selectedCandidate());
        if (key.isEmpty()) {
            LOGGER.warn(LOG_MARKER, "到達提示沒有顯示：選中的候選不是 NEW 或 EXISTING（不應發生，PASSAGE_READY 的 record 建構時已驗證）：session={} chamber={} 玩家={} 候選={}",
                    record.sessionUuid(), record.chamberUuid(), player.getUuid(), record.selectedCandidate().map(chosen -> chosen.getClass().getSimpleName()).orElse("（沒有）"));
            return;
        }
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(ARRIVAL_FADE_IN, ARRIVAL_STAY, ARRIVAL_FADE_OUT));
        player.networkHandler.sendPacket(new TitleS2CPacket(Text.translatable(key.get())));
    }

    /** 到達提示的 key（純函式，spec §20.8）：NEW「你已進入新的宇宙。」、EXISTING「你已進入另一個宇宙。」；SOURCE 或沒有選中的候選為空（見 {@link #arrived}）。 */
    static Optional<String> arrivalKey(Optional<QuantumCandidate> selected) {
        return selected.flatMap(chosen -> switch (chosen) {
            case QuantumCandidate.New ignored -> Optional.of(MESSAGE_ARRIVED_NEW);
            case QuantumCandidate.Existing ignored -> Optional.of(MESSAGE_ARRIVED_EXISTING);
            case QuantumCandidate.Source ignored -> Optional.empty();
        });
    }

    /** {@code FAILED_ROLLED_BACK}、{@code REJECTED_BEFORE_MOVE}：玩家仍在原世界，通道仍開著；actionbar 提示，WARN 依原因去重（成功傳送時清除）。 */
    private void transferFailed(SessionRecoveryRecord record, ServerPlayerEntity player, UniverseTransferResult result) {
        var failure = new IllegalStateException("通道傳送沒有完成，玩家仍在原世界：" + result.outcome() + " decision=" + result.decision() + " move="
                + result.move().outcome() + " rollback=" + result.rollback().outcome());
        service.warn(record.sessionUuid(), TRANSFER, failure, null, "通道仍開著，可以再試：玩家=" + player.getUuid() + " 位置=" + player.getPos() + " detail=" + result.detail());
        player.sendMessage(Text.translatable(CollapseService.MESSAGE_PASSAGE_TRANSFER_FAILED), true);
    }

    /** 沒有呼叫傳送就失敗（descriptor 讀不到）：與 {@link #transferFailed} 相同地提示並去重記 WARN。 */
    private void notTransferred(SessionRecoveryRecord record, ServerPlayerEntity player, RuntimeException failure) {
        service.warn(record.sessionUuid(), TRANSFER, failure, null, "沒有傳送，玩家仍在原世界，通道仍開著：玩家=" + player.getUuid());
        player.sendMessage(Text.translatable(CollapseService.MESSAGE_PASSAGE_TRANSFER_FAILED), true);
    }

    /** {@code FAILED_RECOVERY_REQUIRED} 的 root cause：移動與回滾的證據（transfer result 的 source、move、rollback、detail）。 */
    private static IllegalStateException recoveryCause(UUID player, UniverseTransferResult result) {
        return new IllegalStateException("通道傳送無法回滾（FAILED_RECOVERY_REQUIRED）：玩家=" + player + " 的實際位置未確認，不再自動移動他、也不重試；decision=" + result.decision()
                + " source=" + result.source() + " move=" + result.move() + " rollback=" + result.rollback() + " detail=" + result.detail());
    }

    /**
     * {@code FAILED_RECOVERY_REQUIRED}：checked（roll-forward）PASSAGE_READY→SEALED(TRANSFER_RECOVERY_REQUIRED)，RecoveryRequired 記下那位玩家。寫出沒有確認時 current 保留
     * SEALED 並保持 dirty（§10.3 :571），依 collapse view 照樣生效；之後與其他原因進入 SEALED 相同（{@link CollapseService#sealCommitted}，ERROR 的 cause 是證據）。寫入在
     * 前置檢查就被拒時（journal 沒有改變；防禦性），通道在記憶體內關閉（{@link Track#pendingRecovery}）、原艙門改為一般開門（第一次被拒時排入
     * {@link OriginDoors.DoorGoal#OPEN_WHILE_PASSAGE_CLOSED}，比照 PREPARE 中寫入被拒時的門；F5-1 fix round 1，spec review S-m3），依 per-session 退避只重試這筆寫入；寫入成功時
     * 由 {@code sealCommitted} 的關門取代它。這段期間（F5-2）：
     * <ul>
     * <li>門照一般門的邏輯開關（{@link OriginDoors#doorAction} 回 PASS，spec re-review n-2；比照 PREPARE 被拒時 PREPARE 不擁有門）。</li>
     * <li>presence 維持 PASSAGE、比較器 15（已供電而且 enabled 時；spec §10.7）：journal 沒有改變，collapse view 仍是 PASSAGE_READY，presence 只依它推導（不改
     *     {@code SuperpositionSessionManager} 與 port）。通道其實已在記憶體內關閉，所以這是短暫誤報的 15，到寫入成功（SEALED，11）為止；只在前置檢查被拒的防禦路徑出現（M5 note
     *     F5-2，已知限制）。</li>
     * </ul>
     */
    private void recoveryRequired(SessionRecoveryRecord current, Track track, UUID player, IllegalStateException cause) {
        var sid = current.sessionUuid();
        var next = current.withCollapse(SessionState.SEALED, current.collapse().transferRecoveryRequired(player));
        var commit = service.journal.commitRollForward(service.server, current, next);
        if (commit.status() == SessionRecoveryState.CommitResult.Status.REJECTED) {
            boolean first = track.pendingRecovery == null;
            track.pendingRecovery = new PendingRecovery(player, cause);
            track.backoff.failed(service.now);
            if (first) {
                // F5-1 fix round 1（spec review S-m3）：比照 PREPARE 中寫入被拒時把門交還為一般開門：通道已在記憶體內關閉，原艙門不再是 passable（之後照一般門的邏輯開關；presence 與比較器見上方 Javadoc）。
                service.originDoors.queueDoor(current, 0, OriginDoors.DoorGoal.OPEN_WHILE_PASSAGE_CLOSED);
                LOGGER.error(LOG_MARKER, "通道傳送無法回滾，但 SEALED(TRANSFER_RECOVERY_REQUIRED) 的寫入被拒：通道在記憶體內關閉（不跨越、不終局、不評估結束條件，原艙門改為一般開門），"
                        + "依 per-session 退避只重試這筆寫入；需要管理員處理：session={} chamber={} 玩家={}：{}", sid, current.chamberUuid(), player,
                        FailureText.describe(cause), cause);
            }
            service.warn(sid, RECOVERY_WRITE, commit.failure().orElseThrow(), commit.status().name(), "依 per-session 退避只重試這筆寫入");
            return;
        }
        track.pendingRecovery = null;
        track.warnings.remove(RECOVERY_WRITE);
        service.sealCommitted(current, track, SealedReason.TRANSFER_RECOVERY_REQUIRED, cause, commit);
    }

    /** 被拒的 TRANSFER_RECOVERY_REQUIRED 寫入，退避期過後重試（同一位玩家、同一份證據）。 */
    private void retryRecovery(SessionRecoveryRecord record, Track track) {
        if (!track.backoff.ready(service.now)) return;
        var pending = track.pendingRecovery;
        recoveryRequired(record, track, pending.player(), pending.cause());
    }

    /** 被拒而待重試的 TRANSFER_RECOVERY_REQUIRED 寫入（{@link Track#pendingRecovery}）。 */
    record PendingRecovery(UUID player, IllegalStateException cause) {
        PendingRecovery {
            Objects.requireNonNull(player, "player"); Objects.requireNonNull(cause, "cause");
        }
    }

    // ---- 共用 ----

    private static List<UUID> participantIds(SessionRecoveryRecord record) {
        return record.participants().stream().map(SessionRecoveryRecord.Participant::playerUuid).toList();
    }

    private ServerWorld sourceWorld(SessionRecoveryRecord record) {
        return service.server.getWorld(RegistryKey.of(RegistryKeys.WORLD, record.origin().worldKey()));
    }

    /** {@code id} 在線的玩家 instance（不在線或已移除時為 null）。{@link #observe} 與 {@link #cross} 共用（F5-2 Step 0，F5-1 quality Q-m6）。 */
    private ServerPlayerEntity online(UUID id) {
        var player = service.server.getPlayerManager().getPlayer(id);
        return player != null && !player.isRemoved() ? player : null;
    }

    /**
     * {@code player} 位在 exact 的來源 world instance（§10.3）：他所在的世界就是 {@code world}，而且 {@code world} 依 UUID 找到的 entity 就是他（{@code world} 為 null 時為假）。
     * {@link #observe} 與 {@link #cross} 共用同一個判斷（F5-2 Step 0，F5-1 quality Q-m6）。
     */
    private static boolean inSourceWorld(ServerPlayerEntity player, ServerWorld world) {
        return world != null && player.getServerWorld() == world && world.getEntity(player.getUuid()) == player;
    }

    private static ChamberFrame frame(SessionRecoveryRecord record) {
        return new ChamberFrame(record.origin().controllerPos(), record.origin().facing());
    }

    /**
     * passage 的 transfer 只需要「目的地的 exact ACTIVE world」：委派 lifecycle 的 {@link UniverseLifecycleService#resolveActive}（M5 不自建 backend、不繞過 lifecycle 的
     * runtime owner，spec §6.3）。descriptor 的 world key 不是這個 UniverseId 的 OVERWORLD key 時為空；不物化、不卸載。
     */
    private record LifecycleView(UniverseId id) implements DynamicDimensionBackend {
        @Override public Optional<ServerWorld> resolveActive(MinecraftServer server, UniverseWorldDescriptor descriptor) {
            if (!UniverseKeys.world(id, DimensionRole.OVERWORLD).equals(descriptor.worldKey())) return Optional.empty();
            return UniverseLifecycleService.resolveActive(server, id);
        }

        @Override public MaterializeResult materialize(MinecraftServer server, UniverseWorldDescriptor descriptor) {
            throw new UnsupportedOperationException("passage 不物化（物化由 lifecycle 擁有，spec §6.3）");
        }

        @Override public UnloadResult unload(MinecraftServer server, UniverseWorldDescriptor descriptor, ServerWorld expectedWorld) {
            throw new UnsupportedOperationException("passage 不卸載");
        }
    }
}

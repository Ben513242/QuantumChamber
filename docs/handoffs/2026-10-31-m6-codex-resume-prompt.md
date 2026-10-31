# QuantumChamber M6：Codex 接續提示（2026-10-31）

全程使用繁體中文（台灣）、精簡回報。先核原件，再推論。此檔是 Codex 下次接續入口；Claude 交接已取消。

## 工作區與先讀

- Worktree：`C:/Users/Ben/Documents/minecraft QuantumChamber/.worktrees/m6-universe-generation`；分支 `codex/m6-universe-generation`。不要在主 checkout 或 `main` 實作。
- 依序讀本工作區 `AGENTS.md`、本檔全文、`docs/implementation-notes/2026-10-24-m6-universe-generation.md` 末三節、`docs/superpowers/plans/2026-10-24-m6-universe-generation.md` Task8–22，以及 ignored `.superpowers/sdd/2026-10-24-m6-universe-generation/STATUS.md` 首段與 `progress.md` 末筆。ignored 原件不在 Git，換機先確認存在，不可補造。
- 首先重核 `git status --porcelain --untracked-files=all`、`git rev-parse HEAD`、`git ls-remote origin refs/heads/codex/m6-universe-generation`、`git worktree list`，以及 workspace Java owner 與 `run/gametest-m6/world/session.lock` 可獨占。

## 2026-10-31 停點事實

- 本次交接前 local／origin feature 均為 docs HEAD `d5004398c1bb9e502c75650f3d5095c3e329c443`、porcelain 空；`main` 在另一 checkout `0d05f889da6f8b82b97af756aaa50066b3d42854`。M6 工作樹無 live Java；M6 session.lock 存在但已可獨占。實際狀態仍須到場重核。
- 最新 code pin `96cc6c3ba2cac4d8806684e1443de0e9335d9590`（S1a 自然 TestServer startup refresh seal），range `350aebce..96cc6c3` 恰十四檔。它只證一次自然成功 startup 的瞬時 seal 診斷，不發 Sources／lease，也不等於完整 refresh seal 或正式設定快照。
- S1a 真 false-stub 行為 RED、三個 production 突變被殺、tag／loot 兩個 oracle 負控制各具名失敗；乾淨同 pin 雙 focused 各 1 GameTest／十事件與 strict oracle PASS。完整 `run-all-203ba85dadc64a9e99253cc99587275f` 自然 `END ALL exit=0`：JUnit971（fail0/error0/既有skip2）、M6 9/9、mainOnly PASS、recovery24、collapse69、M6 native 仍 DIAGNOSTIC2。summary SHA256 `0f97996ae3f351894843a223bab1a7f6126921ca5a9d24c17cb59312a2e2fc80`，console SHA256 `ee982a9414c41db571c31f6695062cdceaf8ba79448d2f1ecf5b319032271b3d`；額外 full S1a checker PASS，oracle SHA256 `b4a00a5b7880982764bab69fb8e715e72b98b1cbd2a921cdc294044260f89685`。spec／quality 各 C0/I0/M0；細節與原失敗見 M6 note 末節、ignored `task-08/8b3l-s1a-natural-evidence/`。
- docs HEAD `d5004398` 的 GitHub Build run [41145800231](https://github.com/Ben513242/QuantumChamber/actions/runs/41145800231) 同 SHA completed/success attempt1，Ubuntu `build`／Windows `windows-native` 雙 job success；三份 artifact 清單已讀、未解壓。Windows log 有 S1a 事件與 M6／main-only PASS，但**沒有執行 `S1A_ORACLE`**。本地 strict oracle 不可冒稱遠端 strict 或 8B3-F 三 bundle。
- 先前局部 code pins：owner Cell `d88bed9060f55d8f7651b1c1fed98d161a3861b6`（docs `8c484828`，CI run `41109557592` 雙 job success）；initial metadata `0c757c60cc43ff69ba8a6a9302bf2f9a09907355`（docs `350aebce`，CI run `41129836626` 雙 job success）。D1b-3d 的 dynamic JUKEBOX_SONG／SOUND_EVENT 局部 code `ccccb9697e005ad9eece0f88545df949c5ca3c45` 已結；D1c LOOT_TABLE 尚未放行。

## 尚未完成與原 FAIL

- S1b 異常 startup／舊 seal 撤銷／重入／overlap、S2 integrated 新 active RM 與舊 content 同代、正式 source bridge／同票同 seal publication、consumer 最終接受、D1c LOOT_TABLE、100ms live hold I-1、8B3-F Windows named／Ubuntu named／Windows intermediary 三 bundle及 Task8 終局均待。`createSnapshot`／`resolve` 仍 fail closed。Task9–14 的 random seed／same-seed fresh／generation profile，以及 Task15–22 的完整維度家族、portal／gateway、重生尚未開始。
- LT2 STOP-PENDING 原 nonce `6bc60fc91584008b983aa668ebad1f00`：STOPPING 後 worker off-thread 安裝 holder／refresh／templates且 F success，oracle RUN_FAIL；**永久保留 FAIL**，不可稱 `STOP_UNPROVEN` 或安全 PASS。v4 父端、B27、3b AccessDenied、T2 額外100ms FAIL 也維持原判；S1a 兩個 setup FAIL、false-stub RED、五個 mutation／負控制 FAIL 各留原 root。人工第二次 refresh 會清掉 public 可變 fuel map 中普通 mod 的寫入，不可直接採用原 S1 方案。
- S1b 原提案 `8B3L-S1B-FAILURE-PROPOSAL.md` SHA256 `0436c14a65eb2456c4309f51a41eb65d26954bd4bf545c51f26738d63f80a0e1` 首審 C0/I2/M1；ownership addendum v1 SHA256 `9957d1c0aaca5221d16313201aba16c4ad507e37b16869c742b7cf1bb40cc5cc` 複審 C0/I0/M3，只允後續唯讀設計。最新 `8B3L-S1B0-EXECUTION-ADDENDUM-v2.md` SHA256 `6ba74d6fc88bf1cfd8d653fe706bfefde109d30eadbb440109999ce0e83841ff` **尚未獨審**：fault/property 與實際 pack／Fabric listener admission 仍 UNKNOWN；不得派實作或 fault native。固定原版 TestServer.create 的異常分支呼 `System.exit(-1)`，沒有 Session/RM close；process exit 不算清理。
- integrated RM 唯讀提案 `8B3L-S2-INTEGRATED-RM-PROPOSAL.md` SHA256 `1eb5e823675a11fdaea0dff0562b95ebc36393f8516693c0596c8f0ea2a76ca5` **尚未獨審**，結論 STOP：舊 content refresh seal 不能授權新 active RM；單純 re-refresh、同 pack 名稱/config/hash 或 token 搬移不能證同源。

## 下次工作順序

1. 先核 Git／CI 與 ignored 原件；以獨立唯讀 reviewer 分別審 S1b-0 v2 與 integrated RM 提案。Critical／Important 未關閉前不啟 F1/F2、S2 或正式 publisher。
2. 選一個最窄、真能證 owner／來源同代的後續切片；先設計獨審，再 implementer 具名 RED→GREEN、mutation-only＋REVERT、新 nonce 雙 focused／完整 run-all、獨立 spec→quality、docs truth→正常 push→雙平台 CI。失敗 root 原判不改。不要擴無關驗證框架。
3. Task8 全部通過（含 8B3-F 三 bundle 原件）後才進 Task9 random seed，後續依 plan Task9–22。不要重做 M5／A7 或已結案切片，不重跑原相同23案 focused。

## 授權與邊界

- 子代理只用 `gpt-5.6-sol`、`gpt-6-sol` 或 `gpt-6-astra`，依任務切換。Subagent-Driven Development：一個 implementer 對應一片，之後獨立 spec／quality；C/I 清零才往下一片。
- 正常 push 已持續授權。force push、PR、main fast-forward、tag、人工代驗與 worktree 退役不在本授權。不得 `git add -A`、`reset --hard`、`checkout --`、`git clean`、force-add ignored 證據或修改主 checkout。
- 使用者已同意只有必須繞過存取限制的 mod 殘餘可文件化先推主線；一般 mod 用 public API 或 vanilla 受影響者仍須當場修。任何 retained FAIL 不得追認整體 PASS。

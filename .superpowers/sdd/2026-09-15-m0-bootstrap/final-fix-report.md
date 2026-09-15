# M0 Broad Final Review Fix Report

## 範圍與基線

- Fix base：`ca5f000efed03aad2d63ba5880d34fadb8521418`。
- 本修復波次僅調整 Gradle Wrapper 的 Git 可執行模式、CI artifact 缺檔策略，以及 M0 計畫勾選；不修改 `gradlew` 腳本內容、不建立 remote、不執行或宣稱執行 GitHub Actions。

## 異動

1. 將 `gradlew` 的 Git tree mode 由 `100644` 改為 `100755`，使 Ubuntu runner 可直接執行 workflow 中的 `./gradlew clean build`。
2. 將 `.github/workflows/build.yml` 的 `if-no-files-found` 由 `warn` 改為 `error`；artifact 缺失會讓 CI 明確失敗。
3. 將計畫中 Task 1 的 8 個步驟與 Task 2 的 7 個步驟標示為完成。依據為既有提交：Task 1 的 `28a670f build: bootstrap Fabric 1.21 toolchain`，Task 2 的 `c5a03eb feat: add Fabric entrypoints and compatibility detection`；本次 review 基線也已包含後續的驗證／文件提交 `38f505f` 與 `ca5f000`。
4. Task 3 維持既有真實狀態；Task 4 的全部 3 個步驟仍為 `[ ]`，因尚未建立 GitHub remote、push 或線上 CI run。

## 執行與關鍵輸出

```text
git rev-parse HEAD
ca5f000efed03aad2d63ba5880d34fadb8521418

git ls-files -s gradlew (修正前)
100644 b740cf13397ab16efc23cba3d6234ff8433403b1 0 gradlew

git update-index --chmod=+x gradlew
```

```text
git diff --check
exit 0（無輸出）

python -c "import yaml; ..."
YAML_PARSE_OK

Test-Path LICENSE
False / LICENSE_ABSENT
```

## 驗收重點與疑慮

- 提交後必須用 `git ls-tree HEAD gradlew` 確認最終 tree mode 為 `100755`。
- workflow 已改為 `if-no-files-found: error`，並通過本機 YAML 解析；這不是 GitHub Actions 的實際線上執行證明。
- `LICENSE` 仍不存在。
- 本修復未建立 GitHub repository、remote、push 或 CI 線上 run，因此 Task 4 維持未完成。
- 此次為設定與文件修復，未改動 Java／Gradle 建置邏輯，未另外重跑完整 Gradle build。

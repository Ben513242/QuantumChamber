# QuantumChamber M0 Bootstrap Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 建立可重現建置、可啟動 client 與 dedicated server、具備 client/common 分離與空白相容性偵測器的 Minecraft 1.21.0 Fabric M0 專案。

**Architecture:** 專案維持單一 Fabric module，伺服器權威與共用程式置於 `src/main/java`，純客戶端入口置於 `src/client/java`。M0 只建立工具鏈、入口點、可測試的選用模組偵測邊界與 CI，不實作 Chamber、Potion、Universe 或動態 Dimension。

**Tech Stack:** Java 21、Gradle Wrapper 8.8、Fabric Loom 1.7.4、Minecraft 1.21.0、Yarn 1.21+build.9、Fabric Loader 0.17.2、Fabric API 0.102.0+1.21、JUnit Jupiter 5.10.3、GitHub Actions。

**Spec:** `docs/quantum_superposition_chamber_design.md`

## Global Constraints

- Minecraft 必須固定為 `1.21`，不得升級或宣告支援 1.21.1。
- 開發與驗證使用 Java 21。
- Fabric Loader 固定使用 `0.17.2`，Fabric API 固定使用 `0.102.0+1.21`。
- Yarn mappings 固定使用 `1.21+build.9`，Fabric Loom 固定使用 `1.7.4`，Gradle Wrapper 固定使用 `8.8`。
- Mod ID 為 `quantumchamber`，Java package root 為 `dev.quantumchamber`。
- 專案為單一 Fabric module，並啟用 split environment source sets。
- `src/main/java` 不得引用 client、Iris、Sodium、Immersive Portals 或其他選用模組類別。
- Iris、Sodium、Immersive Portals、Cloth Config、Mod Menu、Cardinal Components 與 DimLib 均不得成為 M0 必要依賴。
- GitHub Repository 為 Public，但目前不附授權；不得建立 `LICENSE`，README 必須提醒未授權再利用。
- `run/client-base`、`run/client-render`、`run/server` 與 `run/testworlds` 必須隔離且不納入 Git。

---

### Task 1: 建立可重現的 Fabric／Gradle 專案骨架

**Files:**

- Create: `settings.gradle`
- Create: `gradle.properties`
- Create: `build.gradle`
- Create: `gradle/wrapper/gradle-wrapper.properties`
- Create: `gradle/wrapper/gradle-wrapper.jar`
- Create: `gradlew`
- Create: `gradlew.bat`
- Create: `.gitignore`
- Create: `src/main/resources/fabric.mod.json`

**Interfaces:**

- Consumes: 規格第 13.1、13.2、13.6、13.7 節的鎖定版本與 source-set 規則。
- Produces: 可由 `./gradlew` 使用的 Gradle 8.8 Wrapper，以及 `main`／`client`／`test` source sets。

- [ ] **Step 1: 確認 Java 21 前置條件**

Run:

```powershell
java -version
javac -version
```

Expected: 兩者 major version 都是 `21`。若本機沒有 Java 21，先透過受信任的套件來源安裝 Temurin/OpenJDK 21，再重跑本步；不得以 Java 17 或 22 代替。

- [ ] **Step 2: 建立 Gradle 設定與鎖定版本**

`settings.gradle`：

```groovy
pluginManagement {
    repositories {
        maven { url = 'https://maven.fabricmc.net/' }
        gradlePluginPortal()
    }
}

rootProject.name = 'QuantumChamber'
```

`gradle.properties`：

```properties
org.gradle.jvmargs=-Xmx2G
org.gradle.parallel=true

minecraft_version=1.21
yarn_mappings=1.21+build.9
loader_version=0.17.2
fabric_version=0.102.0+1.21
loom_version=1.7.4

mod_version=0.1.0-SNAPSHOT
maven_group=dev.quantumchamber
archives_base_name=quantumchamber
```

- [ ] **Step 3: 建立單模組 Loom build**

`build.gradle` 必須：

```groovy
plugins {
    id 'fabric-loom' version "${loom_version}"
    id 'maven-publish'
}

version = project.mod_version
group = project.maven_group

base {
    archivesName = project.archives_base_name
}

repositories {
    mavenCentral()
}

loom {
    splitEnvironmentSourceSets()

    mods {
        quantumchamber {
            sourceSet sourceSets.main
            sourceSet sourceSets.client
        }
    }

    runs {
        client {
            client()
            runDir 'run/client-base'
        }
        clientRender {
            client()
            name 'Minecraft Client - Render Compatibility'
            runDir 'run/client-render'
        }
        server {
            server()
            runDir 'run/server'
        }
    }
}

dependencies {
    minecraft "com.mojang:minecraft:${project.minecraft_version}"
    mappings "net.fabricmc:yarn:${project.yarn_mappings}:v2"
    modImplementation "net.fabricmc:fabric-loader:${project.loader_version}"
    modImplementation "net.fabricmc.fabric-api:fabric-api:${project.fabric_version}"

    testImplementation platform('org.junit:junit-bom:5.10.3')
    testImplementation 'org.junit.jupiter:junit-jupiter'
}

processResources {
    inputs.property 'version', project.version
    filesMatching('fabric.mod.json') {
        expand 'version': inputs.properties.version
    }
}

tasks.withType(JavaCompile).configureEach {
    it.options.release = 21
}

java {
    withSourcesJar()
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

test {
    useJUnitPlatform()
}
```

- [ ] **Step 4: 建立保守的 Fabric metadata**

`src/main/resources/fabric.mod.json`：

```json
{
  "schemaVersion": 1,
  "id": "quantumchamber",
  "version": "${version}",
  "name": "QuantumChamber",
  "description": "A server-authoritative quantum superposition chamber for persistent parallel universes.",
  "environment": "*",
  "entrypoints": {
    "main": [
      "dev.quantumchamber.QuantumSuperpositionMod"
    ],
    "client": [
      "dev.quantumchamber.client.QuantumSuperpositionClient"
    ]
  },
  "depends": {
    "fabricloader": ">=0.17.2",
    "minecraft": "1.21",
    "java": ">=21",
    "fabric-api": ">=0.102.0"
  }
}
```

- [ ] **Step 5: 以官方 Gradle 8.8 發行檔產生並鎖定 Wrapper**

Run:

```powershell
New-Item -ItemType Directory -Force .tools | Out-Null
Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.8-bin.zip -OutFile .tools/gradle-8.8-bin.zip
Invoke-WebRequest https://services.gradle.org/distributions/gradle-8.8-bin.zip.sha256 -OutFile .tools/gradle-8.8-bin.zip.sha256
$expected = (Get-Content .tools/gradle-8.8-bin.zip.sha256 -Raw).Trim()
$actual = (Get-FileHash .tools/gradle-8.8-bin.zip -Algorithm SHA256).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "Gradle 8.8 checksum mismatch" }
Expand-Archive .tools/gradle-8.8-bin.zip -DestinationPath .tools -Force
.\.tools\gradle-8.8\bin\gradle.bat wrapper --gradle-version 8.8 --distribution-type bin
.\gradlew.bat --version
```

Expected: 官方 SHA-256 校驗相符，`Gradle 8.8` 與 Java 21；提交 `gradlew`、`gradlew.bat`、`gradle-wrapper.jar`、`gradle-wrapper.properties`，但不提交 `.tools/`。

- [ ] **Step 6: 建立忽略規則**

`.gitignore` 至少忽略：

```gitignore
.gradle/
build/
out/
.idea/
*.iml
run/
.tools/
```

- [ ] **Step 7: 驗證 Gradle 模型可載入**

Run:

```powershell
.\gradlew.bat tasks --all
```

Expected: exit code `0`，且可看到 `build`、`runClient`、`runClientRender`、`runServer`、`test`。

- [ ] **Step 8: Commit**

```powershell
git add settings.gradle gradle.properties build.gradle gradlew gradlew.bat gradle/wrapper .gitignore src/main/resources/fabric.mod.json
git commit -m "build: bootstrap Fabric 1.21 toolchain"
```

---

### Task 2: 以 TDD 建立 common/client 入口與選用模組偵測邊界

**Files:**

- Create: `src/test/java/dev/quantumchamber/compat/CompatibilityManagerTest.java`
- Create: `src/main/java/dev/quantumchamber/compat/ModPresenceProbe.java`
- Create: `src/main/java/dev/quantumchamber/compat/RuntimeCompatibility.java`
- Create: `src/main/java/dev/quantumchamber/compat/CompatibilityManager.java`
- Create: `src/main/java/dev/quantumchamber/QuantumSuperpositionMod.java`
- Create: `src/client/java/dev/quantumchamber/client/QuantumSuperpositionClient.java`

**Interfaces:**

- Consumes: `ModPresenceProbe#isLoaded(String modId)`，由 production adapter 接到 `FabricLoader.isModLoaded`。
- Produces: `CompatibilityManager.detect(ModPresenceProbe)` 回傳 `RuntimeCompatibility`；common initializer 只記錄偵測結果，client initializer 不承擔伺服器權威狀態。

- [ ] **Step 1: 寫入會因類別尚不存在而失敗的測試**

`CompatibilityManagerTest` 必須驗證兩個行為：偵測已載入選用模組，以及所有選用模組皆未載入時的安全預設。

```java
package dev.quantumchamber.compat;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class CompatibilityManagerTest {
    @Test
    void detectsOptionalRenderAndPortalModsWithoutLoadingTheirClasses() {
        Set<String> loaded = Set.of("sodium", "iris");

        RuntimeCompatibility result = CompatibilityManager.detect(loaded::contains);

        assertTrue(result.sodiumLoaded());
        assertTrue(result.irisLoaded());
        assertFalse(result.immersivePortalsLoaded());
    }

    @Test
    void defaultsToNoOptionalModsWhenNoneArePresent() {
        RuntimeCompatibility result = CompatibilityManager.detect(modId -> false);

        assertFalse(result.sodiumLoaded());
        assertFalse(result.irisLoaded());
        assertFalse(result.immersivePortalsLoaded());
    }
}
```

- [ ] **Step 2: 執行測試並確認 RED**

Run:

```powershell
.\gradlew.bat test --tests dev.quantumchamber.compat.CompatibilityManagerTest
```

Expected: FAIL，原因為 `CompatibilityManager`／`RuntimeCompatibility` 尚未定義，而非 Gradle、下載或語法問題。

- [ ] **Step 3: 實作最小可通過的 compatibility domain**

```java
package dev.quantumchamber.compat;

@FunctionalInterface
public interface ModPresenceProbe {
    boolean isLoaded(String modId);
}
```

```java
package dev.quantumchamber.compat;

public record RuntimeCompatibility(
        boolean sodiumLoaded,
        boolean irisLoaded,
        boolean immersivePortalsLoaded) {
}
```

```java
package dev.quantumchamber.compat;

import java.util.Objects;

public final class CompatibilityManager {
    private CompatibilityManager() {
    }

    public static RuntimeCompatibility detect(ModPresenceProbe probe) {
        Objects.requireNonNull(probe, "probe");
        return new RuntimeCompatibility(
                probe.isLoaded("sodium"),
                probe.isLoaded("iris"),
                probe.isLoaded("immersive_portals"));
    }
}
```

- [ ] **Step 4: 執行測試並確認 GREEN**

Run:

```powershell
.\gradlew.bat test --tests dev.quantumchamber.compat.CompatibilityManagerTest
```

Expected: `2 tests completed, 0 failed`。

- [ ] **Step 5: 接上 Fabric common 與 client 入口**

`QuantumSuperpositionMod` 實作 `ModInitializer`，透過 `FabricLoader.getInstance()::isModLoaded` 呼叫 `CompatibilityManager.detect`，並用 SLF4J 記錄偵測摘要。`QuantumSuperpositionClient` 實作 `ClientModInitializer`，M0 僅記錄 client 初始化，不註冊 renderer、封包或遊戲內容。

- [ ] **Step 6: 執行完整單元測試與編譯**

Run:

```powershell
.\gradlew.bat clean test build
```

Expected: exit code `0`、JUnit 零失敗、產生 remapped mod JAR，且 common sources 無 client class loading 錯誤。

- [ ] **Step 7: Commit**

```powershell
git add src/main/java src/client/java src/test/java
git commit -m "feat: add Fabric entrypoints and compatibility detection"
```

---

### Task 3: 建立文件、CI 與 M0 執行證據

**Files:**

- Create: `README.md`
- Create: `docs/implementation-notes/m0-bootstrap.md`
- Create: `.github/workflows/build.yml`
- Modify: `docs/plans/2026-09-15-m0-bootstrap.md`

**Interfaces:**

- Consumes: Task 1/2 的 Wrapper、Fabric metadata、entrypoints 與測試。
- Produces: 對貢獻者可重現的建置說明、GitHub Actions build gate、client/dedicated-server 驗證紀錄。

- [x] **Step 1: 建立 README**

README 必須包含：專案概念、M0 實際範圍、鎖定版本、Java 21 前置條件、`gradlew.bat clean build`、`gradlew.bat runClient`、`gradlew.bat runServer`、規格／計畫連結，以及以下授權聲明：

```text
No license has been granted for this repository. All rights are reserved unless a license is added later.
```

- [x] **Step 2: 建立 GitHub Actions build gate**

`.github/workflows/build.yml` 必須在 push 與 pull request 上使用 Temurin 21、驗證 Wrapper、執行 `./gradlew clean build`，並上傳 `build/libs` 的 JAR artifact；Workflow 權限採 `contents: read`。

- [x] **Step 3: 驗證 client 啟動**

Run:

```powershell
.\gradlew.bat runClient
```

Expected: Minecraft 1.21 開啟至主選單、log 顯示 QuantumChamber common/client 初始化、無 crash。人工關閉 client 後，將實際日期、Java、Loader、Fabric API 與結果記入 `m0-bootstrap.md`。

- [ ] **Step 4: 驗證 dedicated server 啟動**

首次執行 `runServer` 後，在隔離的 `run/server/eula.txt` 將 EULA 設為 true，再重新啟動：

```powershell
.\gradlew.bat runServer
```

Expected: Minecraft 1.21 dedicated server 到達 `Done`、log 顯示 common 初始化、沒有 client class loading 錯誤。輸入 `stop` 正常關閉，並把實際結果記入 `m0-bootstrap.md`。

- [x] **Step 5: 執行完成前完整驗證**

Run:

```powershell
.\gradlew.bat clean build
git status --short --branch --untracked-files=all
```

Expected: build exit code `0`、測試零失敗；Git 僅顯示本 Task 預期文件／Workflow 變更，`run/`、`.gradle/`、`build/` 與 `.tools/` 不得出現。

- [x] **Step 6: 更新計畫勾選與 Commit**

在每個已證實步驟改為 `[x]`；無法執行的人工相容性項目保持 `[ ]` 並在 implementation note 說明，不得假報通過。

```powershell
git add README.md docs .github/workflows/build.yml
git commit -m "docs: record M0 bootstrap verification"
```

---

### Task 4: 建立並回讀 GitHub Public Repository

**Files:**

- Modify: Git remote configuration only.

**Interfaces:**

- Consumes: 已通過 M0 驗證且已提交的本機 `main` branch。
- Produces: `Ben513242/QuantumChamber` Public Repository、`origin` remote 與已推送的 `main`。

- [ ] **Step 1: 確認遠端名稱尚未被占用**

Run:

```powershell
gh repo view Ben513242/QuantumChamber
```

Expected: 若不存在則回報 not found；若已存在，停止建立並先核對是否為本專案，不得覆寫既有 Repository。

- [ ] **Step 2: 建立 Public Repository 並推送**

Run:

```powershell
gh repo create QuantumChamber --public --source . --remote origin --push
```

Expected: Repository 建立成功，`main` 已推送，且不新增 GitHub 自動產生的 README、`.gitignore` 或 `LICENSE`。

- [ ] **Step 3: 回讀遠端驗收**

Run:

```powershell
gh repo view Ben513242/QuantumChamber --json nameWithOwner,visibility,url,defaultBranchRef
git remote -v
git status --short --branch --untracked-files=all
```

Expected: `nameWithOwner=Ben513242/QuantumChamber`、`visibility=PUBLIC`、default branch 為 `main`、`origin` 指向該 Repository，working tree clean 且 `main` tracking `origin/main`。

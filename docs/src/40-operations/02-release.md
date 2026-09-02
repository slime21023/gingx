# 正式發布流程

1.0 的硬發布目標是單 JVM、JDK 25 的 Maven artifact。Native Image 是獨立
compatibility lane，不應在沒有 GraalVM runner 時阻塞 JVM release。

## Release gate

```text
mvn --settings .mvn/settings.xml -U verify
mvn --settings .mvn/settings.xml -P release-metadata -DskipTests verify
```

再依[效能驗收](01-performance.md)執行 TCK、stress、million-actor memory gate、
JMH throughput／latency 與 preemption workload。Linux x86_64 與 Windows x86_64
都要在 JDK 25 執行 reactor。

## Artifact review

release metadata profile 應產生並檢查：

- sources jar 與 Javadocs jar。
- CycloneDX SBOM（`target/bom.xml`、`target/bom.json`）。
- API baseline comparison。
- dependency vulnerability review。
- checksums 與 signed artifacts。

公共 API 的變更必須同步更新[API 契約](../30-api/05-api-contract.md)、TCK、migration
note 與 release note。任何未解釋的 public enum／exception／lifecycle 行為變更都
應阻擋 promote。

## Maven repository promotion

建議流程是 snapshot → candidate → signed release：

1. 固定版本、commit、JDK 與基準報告。
2. 將所有 artifacts 發布到 internal staging repository。
3. 由第二位 reviewer 檢查 POM dependency、SBOM、Javadocs、checksums 與 API diff。
4. 通過所有 gate 後 promote；失敗則保留 raw logs，修正後重新建立 candidate。
5. 產生 upgrade note，明確列出 delivery、shutdown、cancellation 與 extension 相容性。

簽章私鑰、repository credential、TLS trust store 與 certificate rotation 不應
進入 repository；由 CI secret 與 deployment platform 管理。

## Native Image lane

```text
mvn --settings .mvn/settings.xml -pl actor-demo -am install
mvn --settings .mvn/settings.xml -Pnative -pl actor-demo \
  org.graalvm.buildtools:native-maven-plugin:compile
```

Native build 必須使用支援的 GraalVM JDK。Microsoft OpenJDK 不提供
`native-image`，因此本地 JVM 驗證成功不等於 native build 成功。Native job 要另行
審查 reflection/resource metadata、啟動時間、binary size、TLS／JSON 行為與
第三方 extension 的限制。


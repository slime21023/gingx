# Preemptive Actor Runtime 文件

本書說明以 Java 25 與 Groovy 5 建立的 Preemptive Actor Runtime。文件刻意
按照以下順序編排：

```text
原理與底層設計
        ↓
高階使用與實務指南
        ↓
穩定 API Reference
```

這個順序很重要：使用者先理解 delivery、failure、shutdown 與 preemption
語意，再使用 API；runtime 開發者則可以直接進入 `10. 原理與底層設計`。

## 專案定位

第一個 production line 是單 JVM、JVM-only 的 Java 25 library。Actor 使用
Virtual Thread activation，Mailbox 使用 MPSC chunked queue，Actor 狀態以
單一 packed CAS state 維護。

Groovy、Jackson、Micrometer 與 Native Image 都是 optional extension 或
compatibility lane，不是 `actor-core` 的必要依賴。

## 快速選擇閱讀路徑

- 想建立第一個 Actor：閱讀[快速開始](20-guides/01-getting-started.md)。
- 想理解 scheduler：閱讀[Actor 執行模型](10-architecture/03-actor-execution-model.md)。
- 想理解 queue 與記憶體順序：閱讀[MPSC Queue](10-architecture/06-mpsc-queue.md)。
- 想處理 overload：閱讀[Mailbox 與 Backpressure](20-guides/03-mailbox-and-backpressure.md)。
- 想部署 production：閱讀[Release Checklist](40-operations/02-release.md)。
- 想查詢 signature 與錯誤契約：閱讀[API Reference](30-api/README.md)。

## 建置本書

在 repository root 執行：

```text
mdbook build docs
mdbook serve docs
```

產物位於 `target/mdbook/`，不應提交到 repository。

本書沒有 Rust code sample，因此不執行 mdBook 內建的 `mdbook test`；該命令是
給 Rust `rustdoc` 範例使用。Java／Groovy 範例的 API 一致性由 Maven、TCK 與
source review 驗證。

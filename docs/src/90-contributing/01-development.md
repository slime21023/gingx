# 開發與文件貢獻

## 本地驗證

```text
mvn --settings .mvn/settings.xml verify
mvn --settings .mvn/settings.xml -pl actor-tck -am test
mdbook build docs
```

`mdbook test` 是 Rust `rustdoc` code-sample runner；本書範例是 Java、Groovy
與 Maven 指令，因此不把它列為文件 gate。範例的 API 一致性由 Maven 編譯、TCK
與 source review 負責。

需要 benchmark 或 stress 時，再執行 `-Pjmh`、`-Pstress` 與 opt-in memory gate；
不要把長時間壓測當成每次文件修改的必要步驟。

## 修改順序

runtime 行為變更建議依這個順序提交：

1. 先更新 core／extension source 與對應 unit test。
2. 更新 actor-tck 或 failure／lifecycle test，固定 public contract。
3. 若是效能路徑，補 JMH workload 與 raw result，說明環境。
4. 更新 `10-architecture` 的原理與限制。
5. 更新 `20-guides` 的可執行使用方式。
6. 最後更新 `30-api` 的簽名、結果與 compatibility contract。

這個順序讓「為什麼如此設計」、「如何使用」與「到底保證什麼」分層，避免把
implementation detail 誤寫成 public promise。

## 文件寫作規範

每章以一個讀者問題開始，先給可運作的最小例子，再補限制與 failure semantics。
命名使用 Java／Groovy 原始 API 名稱；enum、method、class 使用反引號；外部連結
只在確實需要時加入。程式碼範例要與目前 Maven module 的實際 public signature
一致，概念性片段要明確標記為 pseudocode。

## Module boundary

- `actor-core` 不能依賴 Groovy、HTTP、Jackson、Micrometer 或 supervision。
- `actor-groovy`、`actor-http*`、`actor-observability-micrometer` 是 optional。
- `actor-tck`、`actor-stress`、`actor-benchmarks` 是驗證／工具模組，不是 runtime
  consumer 的必要 dependency。
- 文件只描述 public API；packed state bit、queue chunk retention 等內部細節
  必須標記為 implementation detail 或已知限制。

## 提交前 checklist

- [ ] `mvn --settings .mvn/settings.xml verify` 成功。
- [ ] `mdbook build docs` 成功，且 SUMMARY 中的章節檔案存在。
- [ ] SUMMARY 中的章節檔案存在，沒有孤立的新文件。
- [ ] 新增或改變的 public behavior 有測試與 API／guide 說明。
- [ ] 效能數字附帶環境、參數與 raw output 位置。
- [ ] 沒有把 `target/`、credential、signed key 或 generated artifact 提交進 source。

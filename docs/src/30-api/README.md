# 30. API Reference

本篇是穩定 API 契約，不是底層原理教學。每個 API 會交代：

- 用途與最小範例。
- thread-safety 與 lifecycle。
- blocking、timeout 與 cancellation 行為。
- failure、drop 與 delivery guarantee。
- 效能與相容性注意事項。

精確的 method signature 仍以 Maven 產出的 Javadocs 為準；本篇補充跨 API
的使用情境與選型說明。

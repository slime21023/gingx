# 20. 高階使用指南

本篇面向應用程式開發者與系統整合者。文件以「如何選擇與如何避免錯誤」
為主，不要求讀者先閱讀 CAS 或 VarHandle 實作。

所有範例都應配合 bounded mailbox、timeout、cancellation 與 graceful
shutdown 一起使用；不要只複製 happy-path 的 `send()` 呼叫。

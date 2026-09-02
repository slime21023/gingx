# 10. 原理與底層設計

本篇面向 runtime 開發者，說明系統的設計原因、同步邊界、記憶體模型與
失敗語意。閱讀本篇不需要先記住所有 API，但需要理解：

1. Actor activation 如何被排程。
2. Mailbox 如何發布與取出訊息。
3. Packed state 如何防止重複執行。
4. 協作式 preemption 的能力與限制。
5. failure、restart、shutdown 如何互相作用。

底層實作主要位於 `actor-core`；Groovy、supervision 與 HTTP 都建立在 core
契約之上。

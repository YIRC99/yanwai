package dev.jev.wechatmood.hook

import dev.jev.wechatmood.reply.ReplyContactKey
import org.junit.Assert.fail
import org.junit.Test

/** 自动整理必须用打开时的账号与联系人快照，切走聊天后整次作废。 */
class RelationSnapshotTest {
    private val account = "a".repeat(64)

    @Test fun `snapshot of the same contact is accepted`() {
        val key = requireNotNull(ReplyContactKey.of(account, "wxid_abc"))
        RelationFactRefresher.verify(key, RelationFactRefresher.Snapshot(account, "wxid_abc", emptyList()))
    }

    @Test fun `snapshot taken after switching contacts cancels the refresh`() {
        val key = requireNotNull(ReplyContactKey.of(account, "wxid_abc"))
        try {
            RelationFactRefresher.verify(key, RelationFactRefresher.Snapshot("b".repeat(64), "wxid_abc", emptyList()))
            fail("切换到另一个账号后不能继续整理")
        } catch (expected: IllegalStateException) {
            // 期望：整次整理被取消。
        }
    }

    @Test fun `snapshot of another talker cancels the refresh`() {
        val key = requireNotNull(ReplyContactKey.of(account, "wxid_abc"))
        try {
            RelationFactRefresher.verify(key, RelationFactRefresher.Snapshot(account, "wxid_other", emptyList()))
            fail("切换到另一个联系人后不能继续整理")
        } catch (expected: IllegalStateException) {
            // 期望：整次整理被取消。
        }
    }
}

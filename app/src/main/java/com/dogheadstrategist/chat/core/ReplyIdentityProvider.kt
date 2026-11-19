package com.dogheadstrategist.chat.core

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Bundle
import com.dogheadstrategist.chat.BuildConfig
import com.dogheadstrategist.chat.reply.*
import java.io.File

/** SettingsProvider checks the Binder UID before dispatching here. */
object ReplyIdentityProvider {
    const val KEY_ROLE_REVISION = "reply_roles_revision"
    val CHANGES_URI: android.net.Uri = SettingsProvider.URI.buildUpon().appendPath("roles").build()
    private var store: ReplyIdentityStore? = null
    private fun storage(context: Context): ReplyIdentityStore {
        check(context.packageName == BuildConfig.APPLICATION_ID)
        store?.let { return it }
        val db = SQLiteDatabase.openOrCreateDatabase(File(context.noBackupFilesDir, "reply_identities_v1.db"), null)
        return try {
            ReplyIdentityStore(object : CacheDatabase {
                override fun execute(sql: String, args: List<String>) {
                    if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.toTypedArray())
                }
                override fun query(sql: String, args: List<String>): String? = db.rawQuery(sql, args.toTypedArray()).use {
                    if (it.moveToFirst()) it.getString(0) else null
                }
                override fun close() = db.close()
            }).also { store = it }
        } catch (error: Exception) { db.close(); throw error }
    }

    /**
     * 把微信里写的背景记成画像的第一版。
     *
     * 角色卡背景和画像本来就是同一件事——都是「这个人的背景描述」。微信里生成或
     * 手改的是它当前的样子，人物档案之后在同一份上增量补充，所以只存这一处。
     *
     * 用户主动改动优先：这里直接覆盖，不跟已有画像做取舍。
     */
    private fun rememberProfile(context: Context, talker: String?, text: String) {
        if (talker.isNullOrBlank() || text.isBlank()) return
        runCatching {
            com.dogheadstrategist.chat.feature.IntelligenceStore
                .setProfile(context, talker, text, System.currentTimeMillis())
        }
    }


    @Synchronized fun call(context: Context, caller: Int, method: String, arg: String?, extras: Bundle?): Bundle {
        if (method.startsWith("contact_background_") || method.startsWith("reply_role_") || extras?.containsKey(SettingsProvider.KEY_GENERATION) == true) {
            val generation = context.getSharedPreferences(ModulePrefs.FILE_NAME, 0).getString(SettingsProvider.KEY_GENERATION, null)
            check(generation != null && extras?.getString(SettingsProvider.KEY_GENERATION) == generation) { "设置已重置，请重新打开" }
        }
        // 人物档案用：谁绑了角色卡。返回的是 talker 摘要而不是身份——app 进程拿聊天
        // 记录里的 talker 自己算一遍就能对上，全程不需要知道对方是谁。
        if (method == "reply_bound_talker_tags") return Bundle().apply {
            putStringArrayList("tags", ArrayList(storage(context).boundTalkerTags()))
        }
        // 人物档案用：角色卡背景即画像的初稿。只读不回写——角色卡有乐观锁，
        // 后台自动更新会让用户在微信里保存失败。
        if (method == "reply_identity_labels") return Bundle().apply {
            val labels = storage(context).displayLabels()
            putStringArrayList("tags", ArrayList(labels.keys))
            putStringArrayList("labels", ArrayList(labels.values))
        }
        if (method == "reply_background_for_talker") return Bundle().apply {
            putString("text", storage(context).backgroundForTag(arg.orEmpty()))
        }
        // 人物档案用：生成回复时把画像带进 prompt。和上面的背景分开取，因为背景
        // 要留给用户在编辑框里改，画像则是只读的最终结果。
        if (method == "reply_profile_for_talker") return Bundle().apply {
            putString("text", com.dogheadstrategist.chat.feature.IntelligenceStore.profile(context, arg.orEmpty()))
        }
        val requested = ReplyContactKey(requireNotNull(arg))
        val key = ReplyContactKey(CacheKey.digest(caller.toString(), requested.value))
        // talker 只有调用方知道（它就在那个聊天窗口里）。顺手换算成摘要存下来，
        // 人物档案分析之后凭摘要就能认出「这个人绑过角色」。
        val talkerTag = extras?.getString("talker")?.takeIf { it.isNotBlank() }
            ?.let { CacheKey.digest("reply-talker-v1", it) }
        return when (method) {
            "reply_role_put" -> {
                val applied = storage(context).roles.saveFromChat(key,
                    ReplyIdentitySetting.decode(requireNotNull(extras?.getString("identity"))),
                    requireNotNull(extras?.getString("text")), talkerTag)
                rememberProfile(context, extras?.getString("talker"), applied.background.text)
                Bundle().apply {
                    putString("identity", applied.identity.encode())
                    putString("background", applied.background.encode())
                }
            }
            // 清除角色卡：身份和背景一起删。画像也一起清——它就是从这个人的
            // 背景长出来的，卡都删了还留着，下次生成又会把旧画像当成「他了解这个人」。
            "reply_role_clear" -> {
                storage(context).clearAll(key)
                extras?.getString("talker")?.takeIf { it.isNotBlank() }?.let {
                    com.dogheadstrategist.chat.feature.IntelligenceStore.clear(context, it)
                }
                Bundle()
            }
            "contact_background_get" -> Bundle().apply { putString("payload", storage(context).background(key).encode()) }
            "contact_background_put" -> Bundle().apply {
                val background = storage(context).saveBackground(key, requireNotNull(extras?.getString("text")))
                rememberProfile(context, extras?.getString("talker"), background.text)
                putString("payload", background.encode())
            }
            "reply_identity_get" -> Bundle().apply { putString("payload", storage(context).find(key).encode()) }
            "reply_identity_put" -> {
                val identity = ReplyIdentitySetting.decode(requireNotNull(extras?.getString("payload")))
                storage(context).save(key, identity)
                if (identity.roleId == null) storage(context).saveBackground(key, "")
                Bundle().apply { putBoolean("saved", true) }
            }
            else -> throw IllegalArgumentException("Unknown identity method")
        }
    }
}

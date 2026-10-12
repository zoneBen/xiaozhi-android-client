package me.xiaozhi.androidclient.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * 本项目的第一个（也是唯一一个）数据库。
 *
 * 在这之前持久化只有 `SharedPreferences` + JSON 文件，**没有 Room / SQLite / DataStore**。
 * 引入它是为了四件事：对话历史长期留存、定时任务、执行记录、硬件探测结果缓存。
 *
 * 版本策略：**这四张表一次性进 v1**。后面的功能是分阶段做的，但建表语句不该跟着分阶段——
 * 每多加一张表就要多一次迁移、多一个 schema 版本，而 `exportSchema` 导出的 JSON
 * 一旦提交进版本库就得维护到底。字段一次想全比分四次加便宜得多。
 *
 * `exportSchema = true` 且 schema 目录由 `app/build.gradle.kts` 的
 * `ksp { arg("room.schemaLocation", ...) }` 指到 `app/schemas`——**那份 JSON 要入库**，
 * 后续改表只有和它 diff 才看得出结构变了。
 */
@Database(
    entities = [
        ConversationMessageEntity::class,
        ScheduledTaskEntity::class,
        TaskExecutionEntity::class,
        HardwareProbeEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class XiaozhiDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun scheduledTaskDao(): ScheduledTaskDao
    abstract fun taskExecutionDao(): TaskExecutionDao
    abstract fun hardwareProbeDao(): HardwareProbeDao

    companion object {
        private const val DATABASE_NAME = "xiaozhi.db"

        /**
         * 双重检查单例。
         *
         * 为什么不用 Application 持有：本项目**没有 Application 类**，也不打算为了一个数据库
         * 引入 DI 框架或服务定位器（见 `MainViewModel` 手工搭对象图的既有风格）。
         * 每个 DAO 的第一行拿到的都是同一个实例，代价只是这一次加锁。
         */
        @Volatile
        private var instance: XiaozhiDatabase? = null

        fun get(context: Context): XiaozhiDatabase {
            return instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }
        }

        private fun build(context: Context): XiaozhiDatabase {
            return Room.databaseBuilder(context, XiaozhiDatabase::class.java, DATABASE_NAME)
                // v1 没有历史包袱，暂时不需要 addMigrations。将来真的要改表时，
                // 优先用 @AutoMigration 并在 app/schemas 里留好两版 JSON。
                .build()
        }
    }
}

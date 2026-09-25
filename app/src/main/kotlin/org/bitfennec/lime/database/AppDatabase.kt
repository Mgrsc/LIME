package org.bitfennec.lime.database

import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.database.dao.ClipboardDao
import org.bitfennec.lime.database.dao.SideSymbolDao
import org.bitfennec.lime.database.dao.SkbFunDao
import org.bitfennec.lime.database.dao.UsedSymbolDao
import org.bitfennec.lime.database.entity.Clipboard
import org.bitfennec.lime.database.entity.SideSymbol
import org.bitfennec.lime.database.entity.SkbFun
import org.bitfennec.lime.database.entity.UsedSymbol
import org.bitfennec.lime.prefs.behavior.SkbMenuMode

/**
 * Main Room database class with asynchronous suspend operations.
 *
 * MIGRATION POLICY:
 * Destructive migration (fallbackToDestructiveMigration) is deliberately DISABLED to prevent
 * silent loss of user data (clipboard history, used symbols, side symbols, etc.).
 * When upgrading `version`:
 * 1. MUST provide explicit `Migration` instances via `RoomDatabase.Builder.addMigrations(...)`.
 * 2. NEVER re-enable destructive migration on production database instances.
 */
@Database(
    entities = [SideSymbol::class, Clipboard::class, UsedSymbol::class, SkbFun::class],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun sideSymbolDao(): SideSymbolDao
    abstract fun clipboardDao(): ClipboardDao
    abstract fun usedSymbolDao(): UsedSymbolDao
    abstract fun skbFunDao(): SkbFunDao

    companion object {
        val instance: AppDatabase by lazy {
            Room.databaseBuilder(Launcher.instance.context, AppDatabase::class.java, "ime_db")
                .addCallback(object : Callback() {
                    override fun onCreate(db: SupportSQLiteDatabase) {
                        super.onCreate(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            initDb()
                        }
                    }

                    override fun onOpen(db: SupportSQLiteDatabase) {
                        super.onOpen(db)
                        CoroutineScope(Dispatchers.IO).launch {
                            initSkbFunsDb()
                        }
                    }
                })
                .build()
        }

        suspend fun initDb() {
            val symbolPinyin = listOf("，", "。", "？", "！", "……", "：", "；", ".").map { symbolKey ->
                SideSymbol(symbolKey, symbolKey)
            }
            instance.sideSymbolDao().insertAll(symbolPinyin)
            val symbolNumber = listOf("%", "/", "-", "+", "*", "#", "@").map { symbolKey ->
                SideSymbol(symbolKey, symbolKey, "number")
            }
            instance.sideSymbolDao().insertAll(symbolNumber)
        }

        suspend fun initSkbFunsDb() {
            if (instance.skbFunDao().getAllMenu().isEmpty()) {
                val skbFuns = listOf(
                    // Default pinned toolbar items (isKeep = 1)
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 1, position = 0),
                    SkbFun(name = SkbMenuMode.JianFan.name, isKeep = 1, position = 1),
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 1, position = 2),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 1, position = 3),
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 1, position = 4),

                    // Default toolbox menu items (isKeep = 0)
                    SkbFun(name = SkbMenuMode.Feedback.name, isKeep = 0, position = 0),
                    SkbFun(name = SkbMenuMode.KeyboardHeight.name, isKeep = 0, position = 1),
                    SkbFun(name = SkbMenuMode.DarkTheme.name, isKeep = 0, position = 2),
                    SkbFun(name = SkbMenuMode.OneHanded.name, isKeep = 0, position = 3),
                    SkbFun(name = SkbMenuMode.NumberRow.name, isKeep = 0, position = 4),
                    SkbFun(name = SkbMenuMode.FloatKeyboard.name, isKeep = 0, position = 5),
                    SkbFun(name = SkbMenuMode.Settings.name, isKeep = 0, position = 6),
                    SkbFun(name = SkbMenuMode.Custom.name, isKeep = 0, position = 7),
                    SkbFun(name = SkbMenuMode.SwitchKeyboard.name, isKeep = 0, position = 8),
                    SkbFun(name = SkbMenuMode.JianFan.name, isKeep = 0, position = 9),
                    SkbFun(name = SkbMenuMode.Emojicon.name, isKeep = 0, position = 10),
                    SkbFun(name = SkbMenuMode.TextEdit.name, isKeep = 0, position = 11),
                    SkbFun(name = SkbMenuMode.ClipBoard.name, isKeep = 0, position = 12),
                )
                instance.skbFunDao().insertAll(skbFuns)
            }
        }
    }
}

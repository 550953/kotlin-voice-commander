package com.example.voskspeechdemo

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction

class DictionaryDatabaseHelper(context: Context) {

    private val database = Room.databaseBuilder(
        context.applicationContext,
        AppRoomDatabase::class.java,
        DATABASE_NAME,
    )
        .fallbackToDestructiveMigration()
        .allowMainThreadQueries()
        .build()

    private val phoneticDao = database.phoneticDao()
    private val mainDao = database.mainDao()

    fun getPhoneticDictionary(): Map<String, List<String>> {
        val result = linkedMapOf<String, MutableList<String>>()
        phoneticDao.getAll().forEach { entry ->
            result.getOrPut(entry.letter) { mutableListOf() }.add(entry.pronunciation)
        }
        return result
    }

    fun getMainDictionary(): Map<String, String> =
        linkedMapOf<String, String>().apply {
            mainDao.getAll().forEach { put(it.abbreviation, it.valueText) }
        }

    fun replacePhoneticDictionary(entries: Map<String, List<String>>) {
        val flattened = entries.flatMap { (letter, variants) ->
            variants.map { pronunciation ->
                PhoneticEntryEntity(letter = letter, pronunciation = pronunciation)
            }
        }
        phoneticDao.replaceAll(flattened)
    }

    fun replaceMainDictionary(entries: Map<String, String>) {
        val flattened = entries.map { (abbreviation, value) ->
            MainEntryEntity(abbreviation = abbreviation, valueText = value)
        }
        mainDao.replaceAll(flattened)
    }

    fun upsertPhoneticEntry(letter: String, pronunciations: List<String>) {
        val flattened = pronunciations.map { pronunciation ->
            PhoneticEntryEntity(letter = letter, pronunciation = pronunciation)
        }
        phoneticDao.replaceLetter(letter, flattened)
    }

    fun deletePhoneticEntry(letter: String) {
        phoneticDao.deleteByLetter(letter)
    }

    fun upsertMainEntry(abbreviation: String, value: String) {
        mainDao.upsert(MainEntryEntity(abbreviation = abbreviation, valueText = value))
    }

    fun deleteMainEntry(abbreviation: String) {
        mainDao.deleteByAbbreviation(abbreviation)
    }

    companion object {
        private const val DATABASE_NAME = "dictionaries.db"
    }
}

@Entity(
    tableName = "phonetic_entries",
    primaryKeys = ["letter", "pronunciation"],
)
data class PhoneticEntryEntity(
    val letter: String,
    val pronunciation: String,
)

@Entity(tableName = "main_entries")
data class MainEntryEntity(
    @PrimaryKey val abbreviation: String,
    val valueText: String,
)

@Dao
interface PhoneticDao {
    @Query("SELECT * FROM phonetic_entries ORDER BY letter ASC, pronunciation ASC")
    fun getAll(): List<PhoneticEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<PhoneticEntryEntity>)

    @Query("DELETE FROM phonetic_entries")
    fun deleteAll()

    @Query("DELETE FROM phonetic_entries WHERE letter = :letter")
    fun deleteByLetter(letter: String)

    @Transaction
    fun replaceAll(entries: List<PhoneticEntryEntity>) {
        deleteAll()
        if (entries.isNotEmpty()) {
            insertAll(entries)
        }
    }

    @Transaction
    fun replaceLetter(letter: String, entries: List<PhoneticEntryEntity>) {
        deleteByLetter(letter)
        if (entries.isNotEmpty()) {
            insertAll(entries)
        }
    }
}

@Dao
interface MainDao {
    @Query("SELECT * FROM main_entries ORDER BY abbreviation ASC")
    fun getAll(): List<MainEntryEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(entries: List<MainEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun upsert(entry: MainEntryEntity)

    @Query("DELETE FROM main_entries")
    fun deleteAll()

    @Query("DELETE FROM main_entries WHERE abbreviation = :abbreviation")
    fun deleteByAbbreviation(abbreviation: String)

    @Transaction
    fun replaceAll(entries: List<MainEntryEntity>) {
        deleteAll()
        if (entries.isNotEmpty()) {
            insertAll(entries)
        }
    }
}

@Database(
    entities = [PhoneticEntryEntity::class, MainEntryEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class AppRoomDatabase : RoomDatabase() {
    abstract fun phoneticDao(): PhoneticDao
    abstract fun mainDao(): MainDao
}

package com.whale.music.di

import android.content.Context
import androidx.room.Room
import com.whale.music.data.dao.SongDao
import com.whale.music.data.db.AppDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(
            context,
            AppDatabase::class.java,
            "ohmusic.db"
        )
            .addMigrations(
                AppDatabase.MIGRATION_1_2,
                AppDatabase.MIGRATION_2_3,
                AppDatabase.MIGRATION_3_4
            )
            .build()
    }

    @Provides
    @Singleton
    fun provideSongDao(db: AppDatabase): SongDao = db.songDao()

    @Provides
    @Singleton
    fun provideRemoteSongDao(db: AppDatabase): com.whale.music.data.dao.RemoteSongDao = db.remoteSongDao()

    @Provides
    @Singleton
    fun provideCustomPlaylistDao(db: AppDatabase): com.whale.music.data.dao.CustomPlaylistDao =
        db.customPlaylistDao()
}

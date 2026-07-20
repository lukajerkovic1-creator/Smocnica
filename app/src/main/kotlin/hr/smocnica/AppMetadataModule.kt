package hr.smocnica

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import hr.smocnica.core.domain.AppMetadata

@Module
@InstallIn(SingletonComponent::class)
object AppMetadataModule {
    @Provides
    fun appMetadata(): AppMetadata = AppMetadata(
        versionName = BuildConfig.VERSION_NAME,
        projectUrl = "https://github.com/lukajerkovic1-creator/Smocnica",
        supportEmail = "luka.jerkovic1@gmail.com",
    )
}

package com.carlauncher.companion.data

import android.content.Context
import com.carlauncher.companion.BuildConfig
import com.carlauncher.companion.data.cloud.AuthRepository
import com.carlauncher.companion.data.cloud.CloudPrefsRepository
import com.carlauncher.companion.data.cloud.CloudRestoreManager
import com.carlauncher.companion.data.cloud.CloudSyncManager
import com.carlauncher.companion.data.cloud.FeedRepository
import com.carlauncher.companion.data.cloud.FriendsRepository
import com.carlauncher.companion.data.cloud.PlatformContext
import com.carlauncher.companion.data.cloud.SharedContentRepository
import com.carlauncher.companion.data.cloud.SupabaseClientProvider
import com.carlauncher.companion.data.cloud.crypto.KeyVault
import com.carlauncher.companion.data.db.getDatabaseBuilder
import com.carlauncher.companion.data.db.getRoomDatabase
import com.carlauncher.companion.data.repo.CarRepository
import com.carlauncher.companion.data.repo.DepartmentLocator
import com.carlauncher.companion.data.repo.DeviceRepository
import com.carlauncher.companion.data.repo.EventRepository
import com.carlauncher.companion.data.repo.PlatformFileStore
import com.carlauncher.companion.data.repo.ProfileRepository
import com.carlauncher.companion.data.repo.RemoteTrackSync
import com.carlauncher.companion.data.repo.TrackRepository
import com.carlauncher.companion.data.repo.TrophyRepository

/** Hand-rolled DI container — small enough app that Hilt would be pure overhead. */
class AppContainer(context: Context) {
    private val db = getRoomDatabase(getDatabaseBuilder(context))

    /** Opaque handle the `:shared` module's platform seams (secure storage, bundled assets,
     * file storage) need — see `PlatformContext`'s own doc comment. */
    private val platformContext = PlatformContext(context)

    private val remoteTrackSync = RemoteTrackSync(
        pointDao = db.locationPointDao(),
        syncStateDao = db.syncStateDao(),
    )

    val trackRepository = TrackRepository(
        remoteSync = remoteTrackSync,
        pointDao = db.locationPointDao(),
    )

    val deviceRepository = DeviceRepository(
        deviceDao = db.deviceDao(),
        pointDao = db.locationPointDao(),
        syncStateDao = db.syncStateDao(),
        appStateDao = db.appStateDao(),
    )

    val mapFocusRequestHolder = MapFocusRequestHolder()

    /** Beta-only singletons (radars, Bluetooth trigger). Empty in the prod flavor — see [BetaContainer]. */
    val beta = BetaContainer(context)

    private val photoStore = PlatformFileStore(platformContext)

    val profileRepository = ProfileRepository(db.userProfileDao())
    val carRepository = CarRepository(db.carDao(), db.carModificationDao(), photoStore)
    val eventRepository = EventRepository(db.eventDao(), db.eventPointDao(), db.locationPointDao())

    // Supabase — accounts, opt-in cloud backup and the community Feed. Unlike `beta` above
    // this ships in both flavors. It is inert (isEnabled == false) when the build carries no
    // Supabase credentials, so the app stays a working offline recorder either way.
    val supabase = SupabaseClientProvider(
        context = platformContext,
        supabaseUrl = BuildConfig.SUPABASE_URL,
        supabaseAnonKey = BuildConfig.SUPABASE_ANON_KEY,
        authRedirectScheme = BuildConfig.AUTH_REDIRECT_SCHEME,
        authRedirectHost = BuildConfig.AUTH_REDIRECT_HOST,
    )
    val keyVault = KeyVault(platformContext)
    val authRepository = AuthRepository(platformContext, supabase, keyVault, termsVersion = BuildConfig.TERMS_VERSION)
    val cloudPrefsRepository = CloudPrefsRepository(db.cloudPrefsDao())
    val friendsRepository = FriendsRepository(supabase)
    val feedRepository = FeedRepository(supabase)
    val sharedContentRepository = SharedContentRepository(supabase)

    val trophyRepository = TrophyRepository(
        trophyDao = db.trophyDao(),
        locationPointDao = db.locationPointDao(),
        deviceDao = db.deviceDao(),
        eventDao = db.eventDao(),
        carDao = db.carDao(),
        modificationDao = db.carModificationDao(),
        userProfileDao = db.userProfileDao(),
        departmentLocator = DepartmentLocator(platformContext),
    )

    val cloudSyncManager = CloudSyncManager(
        provider = supabase,
        authRepository = authRepository,
        cloudPrefsRepository = cloudPrefsRepository,
        keyVault = keyVault,
        carDao = db.carDao(),
        modificationDao = db.carModificationDao(),
        eventDao = db.eventDao(),
        eventPointDao = db.eventPointDao(),
        userProfileDao = db.userProfileDao(),
        trophyDao = db.trophyDao(),
        locationPointDao = db.locationPointDao(),
        deviceDao = db.deviceDao(),
        photoStore = photoStore,
    )

    val cloudRestoreManager = CloudRestoreManager(
        provider = supabase,
        authRepository = authRepository,
        cloudPrefsRepository = cloudPrefsRepository,
        keyVault = keyVault,
        onGpsRestored = { trophyRepository.refresh() },
        carDao = db.carDao(),
        modificationDao = db.carModificationDao(),
        eventDao = db.eventDao(),
        eventPointDao = db.eventPointDao(),
        userProfileDao = db.userProfileDao(),
        trophyDao = db.trophyDao(),
        locationPointDao = db.locationPointDao(),
        deviceDao = db.deviceDao(),
        photoStore = photoStore,
    )
}

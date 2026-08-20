package org.quicklauncher.prototypes.platformprobe

import android.Manifest
import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.window.BackEvent
import android.window.OnBackAnimationCallback
import android.window.OnBackInvokedDispatcher

/**
 * PROTOTYPE ONLY. This activity records public Android API behavior for manual device checks.
 * It deliberately keeps all state in process memory and contains no launcher implementation.
 */
class MainActivity : Activity() {
    private val roleManager by lazy { getSystemService(RoleManager::class.java) }
    private val launcherApps by lazy { getSystemService(LauncherApps::class.java) }
    private val userManager by lazy { getSystemService(UserManager::class.java) }

    private lateinit var roleStatus: TextView
    private lateinit var backStatus: TextView
    private lateinit var profileContainer: LinearLayout
    private lateinit var routeContainer: LinearLayout
    private lateinit var eventLog: TextView

    private var profileReceiverRegistered = false

    private val profileReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val user = intent.getParcelableExtra(Intent.EXTRA_USER, UserHandle::class.java)
            ProbeMemory.record(
                "profile broadcast action=${intent.action} user=${user?.toString() ?: "missing"}",
            )
            refreshAll()
        }
    }

    private val backCallback = object : OnBackAnimationCallback {
        override fun onBackStarted(backEvent: BackEvent) {
            ProbeMemory.backStarts += 1
            backStatus.text = backLine("started", backEvent)
            ProbeMemory.record("predictive Back started edge=${backEvent.swipeEdge}")
            renderEvents()
        }

        override fun onBackProgressed(backEvent: BackEvent) {
            backStatus.text = backLine("progress", backEvent)
        }

        override fun onBackCancelled() {
            ProbeMemory.backCancels += 1
            backStatus.text = backLine("cancelled", null)
            ProbeMemory.record("predictive Back cancelled")
            renderEvents()
        }

        override fun onBackInvoked() {
            ProbeMemory.backCommits += 1
            backStatus.text = backLine("committed", null)
            ProbeMemory.record("predictive Back committed and consumed by probe")
            renderEvents()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildScreen())
        recordEntry("onCreate", intent)
        registerProfileReceiver()
        onBackInvokedDispatcher.registerOnBackInvokedCallback(
            OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            backCallback,
        )
        ProbeMemory.record("predictive Back callback registered")
        refreshAll()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        recordEntry("onNewIntent", intent)
        refreshAll()
    }

    override fun onResume() {
        super.onResume()
        if (::eventLog.isInitialized) {
            ProbeMemory.record("onResume roleHeld=${holdsHomeRole()}")
            refreshAll()
        }
    }

    override fun onDestroy() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback)
        if (profileReceiverRegistered) {
            unregisterReceiver(profileReceiver)
            profileReceiverRegistered = false
        }
        super.onDestroy()
    }

    private fun registerProfileReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PROFILE_AVAILABLE)
            addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
            addAction(Intent.ACTION_PROFILE_ADDED)
            addAction(Intent.ACTION_PROFILE_REMOVED)
        }
        registerReceiver(profileReceiver, filter, Context.RECEIVER_EXPORTED)
        profileReceiverRegistered = true
    }

    private fun recordEntry(callback: String, intent: Intent?) {
        val categories = intent?.categories ?: emptySet()
        val kind = classifyEntry(intent?.action, categories)
        when (kind) {
            EntryKind.HOME -> ProbeMemory.homeEntries += 1
            EntryKind.APP_ICON -> ProbeMemory.iconEntries += 1
            EntryKind.OTHER -> ProbeMemory.otherEntries += 1
        }
        ProbeMemory.record(
            "$callback entry=$kind action=${intent?.action} categories=${categories.sorted()} " +
                "flags=0x${intent?.flags?.toString(16)}",
        )
    }

    private fun buildScreen(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }

        root.addView(title("Platform behavior probe"))
        root.addView(body("PROTOTYPE ONLY. Results live in memory and disappear when the process stops."))
        root.addView(body(deviceLine()))
        root.addView(actionButton("Refresh observations") {
            ProbeMemory.record("manual refresh")
            refreshAll()
        })

        root.addView(sectionTitle("Home role and repeated Home"))
        roleStatus = body("")
        root.addView(roleStatus)
        root.addView(
            actionButton("Request Home role") {
                requestHomeRole()
            },
        )
        root.addView(body("After granting the role, press Home three times. onNewIntent and the Home count should increase each time."))

        root.addView(sectionTitle("Predictive Back"))
        backStatus = body(backLine("waiting", null))
        root.addView(backStatus)
        root.addView(body("Swipe Back partway and cancel, then complete a swipe. The probe consumes completed Back so it stays visible."))
        root.addView(actionButton("Close without Back") { finish() })

        root.addView(sectionTitle("Profiles and Private Space"))
        profileContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(profileContainer)

        root.addView(sectionTitle("Settings route checks"))
        root.addView(body("A route is enabled only when it resolves to the expected package, is exported, and needs no unheld permission."))
        routeContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(routeContainer)

        root.addView(sectionTitle("Captured events"))
        root.addView(actionButton("Clear events") {
            ProbeMemory.clearEvents()
            ProbeMemory.record("events cleared")
            renderEvents()
        })
        eventLog = body("").apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
        }
        root.addView(eventLog)

        return ScrollView(this).apply {
            isFillViewport = true
            addView(
                root,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
    }

    private fun refreshAll() {
        refreshRole()
        refreshProfiles()
        refreshRoutes()
        renderEvents()
    }

    private fun refreshRole() {
        val available = roleManager.isRoleAvailable(RoleManager.ROLE_HOME)
        val held = holdsHomeRole()
        val hiddenPermission = checkSelfPermission(Manifest.permission.ACCESS_HIDDEN_PROFILES) ==
            PackageManager.PERMISSION_GRANTED
        val prerequisite = hiddenProfilePrerequisite(available, held, hiddenPermission)
        roleStatus.text = buildString {
            append("ROLE_HOME available=$available held=$held\n")
            append("ACCESS_HIDDEN_PROFILES granted=$hiddenPermission\n")
            append("Private profile API prerequisite=$prerequisite\n")
            append("entries Home=${ProbeMemory.homeEntries} appIcon=${ProbeMemory.iconEntries} ")
            append("other=${ProbeMemory.otherEntries}")
        }
    }

    private fun requestHomeRole() {
        if (!roleManager.isRoleAvailable(RoleManager.ROLE_HOME)) {
            ProbeMemory.record("Home role request skipped because ROLE_HOME is unavailable")
            renderEvents()
            return
        }
        try {
            startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME))
            ProbeMemory.record("Home role request dispatched")
        } catch (error: ActivityNotFoundException) {
            ProbeMemory.record("Home role request failed ${error.javaClass.simpleName}")
        }
        renderEvents()
    }

    private fun holdsHomeRole(): Boolean =
        roleManager.isRoleAvailable(RoleManager.ROLE_HOME) &&
            roleManager.isRoleHeld(RoleManager.ROLE_HOME)

    private fun refreshProfiles() {
        profileContainer.removeAllViews()
        val hiddenPermission = checkSelfPermission(Manifest.permission.ACCESS_HIDDEN_PROFILES) ==
            PackageManager.PERMISSION_GRANTED
        profileContainer.addView(
            body(
                "roleHeld=${holdsHomeRole()} permissionGranted=$hiddenPermission. " +
                    "An absent private profile is inconclusive until both are true.",
            ),
        )

        val profiles = try {
            launcherApps.profiles
        } catch (error: SecurityException) {
            ProbeMemory.record("LauncherApps.getProfiles denied ${error.message}")
            emptyList()
        }

        profileContainer.addView(body("LauncherApps profiles=${profiles.size}"))
        profiles.forEach { user -> renderProfile(user) }
    }

    private fun renderProfile(user: UserHandle) {
        val launcherUserInfo = try {
            launcherApps.getLauncherUserInfo(user)
        } catch (error: SecurityException) {
            ProbeMemory.record("getLauncherUserInfo denied user=$user")
            null
        }
        val userType = launcherUserInfo?.userType ?: "unavailable"
        val isProfile = userType.contains(".profile.")
        val quiet = if (isProfile) {
            try {
                userManager.isQuietModeEnabled(user)
            } catch (error: RuntimeException) {
                ProbeMemory.record("isQuietModeEnabled failed user=$user ${error.javaClass.simpleName}")
                false
            }
        } else {
            false
        }
        val running = try {
            userManager.isUserRunning(user)
        } catch (error: SecurityException) {
            false
        }
        val unlocked = try {
            userManager.isUserUnlocked(user)
        } catch (error: SecurityException) {
            false
        }
        val activityCount = try {
            launcherApps.getActivityList(null, user).size
        } catch (error: SecurityException) {
            ProbeMemory.record("getActivityList denied user=$user")
            -1
        }
        val policy = profileCatalogPolicy(userType, quiet)
        val serial = launcherUserInfo?.userSerialNumber?.toString() ?: "unavailable"

        profileContainer.addView(
            body(
                "handle=$user serial=$serial\n" +
                    "type=$userType\n" +
                    "quiet=$quiet running=$running unlocked=$unlocked launchableActivities=$activityCount\n" +
                    "required catalog policy=$policy",
            ).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
        )

        if (userType == UserManager.USER_TYPE_PROFILE_PRIVATE) {
            profileContainer.addView(
                actionButton(if (quiet) "Request Private Space unlock" else "Request Private Space lock") {
                    requestQuietMode(user, enable = !quiet)
                },
            )
            if (quiet && activityCount > 0) {
                profileContainer.addView(
                    warning("FAILURE: locked private profile returned $activityCount launchable activities."),
                )
            }
        }
    }

    private fun requestQuietMode(user: UserHandle, enable: Boolean) {
        try {
            val accepted = userManager.requestQuietModeEnabled(enable, user)
            ProbeMemory.record(
                "requestQuietModeEnabled enable=$enable user=$user accepted=$accepted",
            )
        } catch (error: RuntimeException) {
            ProbeMemory.record(
                "requestQuietModeEnabled failed user=$user ${error.javaClass.simpleName}: ${error.message}",
            )
        }
        refreshAll()
    }

    private fun refreshRoutes() {
        routeContainer.removeAllViews()
        routeContainer.addView(body("Catalog: GrapheneOS Settings 15-qpr2 at 1949c50, plus Android public fallbacks."))
        routeCandidates().forEach { candidate -> renderRoute(candidate) }
    }

    private fun renderRoute(candidate: RouteCandidate) {
        val intent = candidate.intent(packageName)
        val resolved = packageManager.resolveActivity(
            intent,
            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()),
        )
        val activityInfo = resolved?.activityInfo
        val permission = activityInfo?.permission
        val permissionHeld = permission == null ||
            checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
        val facts = RouteFacts(
            resolvedPackage = activityInfo?.packageName,
            expectedPackage = candidate.expectedPackage,
            exported = activityInfo?.exported == true,
            requiredPermission = permission,
            requiredPermissionHeld = permissionHeld,
        )
        val availability = decideRoute(facts)
        val component = activityInfo?.let { "${it.packageName}/${it.name}" } ?: "none"
        routeContainer.addView(
            body(
                "${candidate.label}\n" +
                    "action=${candidate.action}\n" +
                    "result=$availability component=$component\n" +
                    "exported=${facts.exported} permission=${permission ?: "none"} held=$permissionHeld",
            ).apply {
                typeface = Typeface.MONOSPACE
                setTextIsSelectable(true)
            },
        )
        routeContainer.addView(
            actionButton("Open ${candidate.label}") {
                openRoute(candidate, intent, availability)
            }.apply {
                isEnabled = availability == RouteAvailability.CALLABLE
            },
        )
    }

    private fun openRoute(
        candidate: RouteCandidate,
        intent: Intent,
        availability: RouteAvailability,
    ) {
        if (availability != RouteAvailability.CALLABLE) {
            ProbeMemory.record("blocked route ${candidate.label} result=$availability")
            renderEvents()
            return
        }
        try {
            startActivity(intent)
            ProbeMemory.record("route dispatch succeeded ${candidate.label}")
        } catch (error: RuntimeException) {
            ProbeMemory.record(
                "route dispatch failed ${candidate.label} ${error.javaClass.simpleName}: ${error.message}",
            )
        }
        renderEvents()
    }

    private fun renderEvents() {
        eventLog.text = ProbeMemory.events.joinToString(separator = "\n")
    }

    private fun backLine(state: String, event: BackEvent?): String = buildString {
        append("state=$state progress=")
        append(event?.let { (it.progress * 100).toInt() } ?: 0)
        append("% touch=")
        append(event?.let { "${it.touchX.toInt()},${it.touchY.toInt()}" } ?: "none")
        append("\nstarts=${ProbeMemory.backStarts} cancels=${ProbeMemory.backCancels} ")
        append("commits=${ProbeMemory.backCommits}")
    }

    private fun deviceLine(): String =
        "sdk=${Build.VERSION.SDK_INT} manufacturer=${Build.MANUFACTURER} model=${Build.MODEL}\n" +
            "product=${Build.PRODUCT} display=${Build.DISPLAY}\n" +
            "fingerprint=${Build.FINGERPRINT}"

    private fun routeCandidates(): List<RouteCandidate> = listOf(
        RouteCandidate(
            label = "Public Settings parent",
            action = Settings.ACTION_SETTINGS,
        ),
        RouteCandidate(
            label = "Public Security parent",
            action = Settings.ACTION_SECURITY_SETTINGS,
        ),
        RouteCandidate(
            label = "GrapheneOS exploit protection",
            action = "com.android.settings.EXPLOIT_PROTECTION_SETTINGS",
            expectedPackage = SETTINGS_PACKAGE,
        ),
        RouteCandidate(
            label = "GrapheneOS duress password",
            action = "com.android.settings.DURESS_PASSWORD_SETTINGS",
            expectedPackage = SETTINGS_PACKAGE,
        ),
        RouteCandidate(
            label = "GrapheneOS app native debugging",
            action = "android.settings.OPEN_APP_NATIVE_DEBUGGING_SETTINGS",
            expectedPackage = SETTINGS_PACKAGE,
            needsPackageData = true,
        ),
        RouteCandidate(
            label = "GrapheneOS app hardened malloc",
            action = "android.settings.OPEN_APP_HARDENED_MALLOC_SETTINGS",
            expectedPackage = SETTINGS_PACKAGE,
            needsPackageData = true,
        ),
    )

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 24f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, 0, 0, dp(8))
    }

    private fun sectionTitle(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 19f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(0, dp(22), 0, dp(6))
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun warning(text: String): TextView = body(text).apply {
        setTextColor(0xFFB00020.toInt())
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun actionButton(label: String, action: () -> Unit): Button = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { action() }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private data class RouteCandidate(
        val label: String,
        val action: String,
        val expectedPackage: String? = null,
        val needsPackageData: Boolean = false,
    ) {
        fun intent(probePackage: String): Intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            expectedPackage?.let(::setPackage)
            if (needsPackageData) {
                data = Uri.parse("package:$probePackage")
            }
        }
    }

    private companion object {
        const val SETTINGS_PACKAGE = "com.android.settings"
    }
}

private object ProbeMemory {
    private const val MAX_EVENTS = 160

    val events = ArrayDeque<String>()
    var homeEntries: Int = 0
    var iconEntries: Int = 0
    var otherEntries: Int = 0
    var backStarts: Int = 0
    var backCancels: Int = 0
    var backCommits: Int = 0

    fun record(message: String) {
        events.addFirst("t=${SystemClock.elapsedRealtime()} $message")
        while (events.size > MAX_EVENTS) {
            events.removeLast()
        }
    }

    fun clearEvents() {
        events.clear()
    }
}

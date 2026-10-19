package ai.opencyvis.backend

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import ai.opencyvis.AgentService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Requires the isolated API 34 standard device with its real ADB Direct backend configured. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 34, maxSdkVersion = 34)
class BackendCapabilitiesDeviceTest {
    @Test fun adbDirectReportsVirtualDisplayAccess(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val connected = CompletableDeferred<AgentService>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                connected.complete((binder as AgentService.AgentBinder).getService())
            }
            override fun onServiceDisconnected(name: ComponentName?) = Unit
        }
        var bound = false
        try {
            withContext(Dispatchers.Main) {
                val intent = Intent(context, AgentService::class.java)
                context.startForegroundService(intent)
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
                check(bound) { "Cannot bind the actual AgentService" }
            }
            withTimeout(30_000) {
                val service = connected.await()
                while (service.activeBackendName == null) delay(100)
                val capabilities = requireNotNull(service.getInputBackend()).capabilities
                assertEquals("adb-direct", capabilities.name)
                assertTrue("The real shell backend must report display access: $capabilities",
                    capabilities.canCreateVirtualDisplay)
            }
        } finally {
            if (bound) withContext(Dispatchers.Main) { context.unbindService(connection) }
        }
    }
}

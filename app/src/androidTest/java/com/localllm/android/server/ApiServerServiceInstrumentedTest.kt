package com.localllm.android.server

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device proof for the FGS fix: starting the API foreground service on a
 * real API 35 runtime must NOT throw MissingForegroundServiceTypeException,
 * must bind, and must stop cleanly. No model needs to be loaded — an idle
 * server still binds the port and answers /api/tags.
 */
@RunWith(AndroidJUnit4::class)
class ApiServerServiceInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun waitFor(timeoutSec: Long, condition: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutSec * 1000
        while (System.currentTimeMillis() < end) {
            try {
                if (condition()) return true
            } catch (_: Throwable) {
            }
            Thread.sleep(500)
        }
        return try {
            condition()
        } catch (_: Throwable) {
            false
        }
    }

    @Test
    fun startBindSnapshotStop() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, ApiServerService::class.java).setAction(ApiServerService.ACTION_START)
        )
        assertTrue(
            "service did not reach running state (startForeground path)",
            waitFor(20) { ApiServerService.isRunning }
        )

        var snapshot: ApiServerService.Snapshot? = null
        val bound = CountDownLatch(1)
        var binder: ApiServerService.ServerBinder? = null
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binder = service as? ApiServerService.ServerBinder
                snapshot = binder?.snapshot()
                bound.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
            }
        }
        try {
            context.bindService(
                Intent(context, ApiServerService::class.java),
                conn,
                Context.BIND_AUTO_CREATE
            )
            assertTrue("bind timed out", bound.await(15, TimeUnit.SECONDS))
            val snap = snapshot
            assertNotNull(snap)
            assertTrue(snap!!.running)
            assertTrue(snap.apiKey.startsWith("sk-local-"))
        } finally {
            try {
                context.unbindService(conn)
            } catch (_: Throwable) {
            }
        }

        context.startService(
            Intent(context, ApiServerService::class.java).setAction(ApiServerService.ACTION_STOP)
        )
        assertTrue("service did not stop", waitFor(15) { !ApiServerService.isRunning })
    }
}

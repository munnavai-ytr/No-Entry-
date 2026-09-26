package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.CountdownTime
import com.example.data.SubscriptionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.time.temporal.ChronoUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("No Entry", appName)
  }

  @Test
  fun `test subscription active logic`() {
    val activeExpiry = Instant.now().plus(365, ChronoUnit.DAYS).toString()
    val activeRecord = SubscriptionRecord(
      deviceId = "test-device-id",
      expiresAt = activeExpiry
    )
    assertTrue(activeRecord.isSubscriptionActive())

    val expiredInstant = Instant.now().minus(1, ChronoUnit.DAYS).toString()
    val expiredRecord = SubscriptionRecord(
      deviceId = "test-device-id",
      expiresAt = expiredInstant
    )
    assertFalse(expiredRecord.isSubscriptionActive())
  }

  @Test
  fun `test countdown formatting`() {
    val countdown = CountdownTime(days = 364, hours = 23, minutes = 59, seconds = 8)
    assertEquals("364", countdown.formattedDays)
    assertEquals("23", countdown.formattedHours)
    assertEquals("59", countdown.formattedMinutes)
    assertEquals("08", countdown.formattedSeconds)
  }

  @Test
  fun `test cleanbrowsing dns constants`() {
    assertEquals("185.228.168.168", com.example.vpn.ShieldVpnService.CLEAN_BROWSING_DNS_PRIMARY)
    assertEquals("185.228.169.168", com.example.vpn.ShieldVpnService.CLEAN_BROWSING_DNS_SECONDARY)
  }
}
